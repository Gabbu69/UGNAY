import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ArrowRight, Check, CheckCircle2, ChevronRight, Circle, FilePlus2, FileText,
  Link2, MapPin, Plus, Save, Sparkles, Trash2, Users,
} from 'lucide-react'
import {
  ApiProblem, submitIntakeForDiscovery, type EvidenceReferenceType,
  type IntakeEvidenceReferenceInput, type IntakeResult,
} from '../lib/api'
import { EvidenceChain } from '../components/EvidenceChain'
import { ExplainabilityNote, PageHeader, StatusPill } from '../components/Primitives'

const DRAFT_KEY = 'ugnay-intake-draft-v2'
const intakeSteps = [
  { label: 'Problem', hint: 'What is happening?' },
  { label: 'Context', hint: 'Where and for whom?' },
  { label: 'Proposal', hint: 'What will be studied?' },
  { label: 'Evidence', hint: 'What supports this?' },
  { label: 'Review', hint: 'Ready to discover?' },
]

interface IntakeDraft {
  idempotencyKey: string
  problemTitle: string
  problemStatement: string
  stakeholder: string
  affectedUsers: string
  siteContext: string
  desiredOutcome: string
  constraints: string
  privacyClassification: 'PUBLIC' | 'INTERNAL' | 'RESTRICTED'
  proposalTitle: string
  objectives: string[]
  proposedSolution: string
  methodology: string
  dataSources: string
  technology: string
  intendedUsers: string
  evidenceReferences: IntakeEvidenceReferenceInput[]
}

function emptyDraft(): IntakeDraft {
  return {
    idempotencyKey: crypto.randomUUID(),
    problemTitle: '', problemStatement: '', stakeholder: '', affectedUsers: '', siteContext: '', desiredOutcome: '',
    constraints: '', privacyClassification: 'INTERNAL', proposalTitle: '', objectives: ['', ''], proposedSolution: '',
    methodology: '', dataSources: '', technology: '', intendedUsers: '', evidenceReferences: [],
  }
}

function loadDraft(): IntakeDraft {
  try {
    const value = JSON.parse(localStorage.getItem(DRAFT_KEY) ?? 'null') as Partial<IntakeDraft> | null
    if (!value?.idempotencyKey) return emptyDraft()
    const defaults = emptyDraft()
    return { ...defaults, ...value, objectives: value.objectives?.length ? value.objectives : ['', ''], evidenceReferences: value.evidenceReferences ?? [] }
  } catch {
    return emptyDraft()
  }
}

function optional(value: string) {
  const normalized = value.trim()
  return normalized || undefined
}

export default function IntakeStudio() {
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const [draft, setDraft] = useState<IntakeDraft>(loadDraft)
  const [isRunning, setIsRunning] = useState(false)
  const [result, setResult] = useState<IntakeResult>()
  const [error, setError] = useState('')

  useEffect(() => {
    const timeout = window.setTimeout(() => localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)), 250)
    return () => window.clearTimeout(timeout)
  }, [draft])

  const update = <K extends keyof IntakeDraft>(key: K, value: IntakeDraft[K]) => setDraft((current) => ({ ...current, [key]: value }))
  const trimmedObjectives = draft.objectives.map((value) => value.trim()).filter(Boolean)
  const stepReady = [
    draft.problemTitle.trim().length >= 3 && draft.problemStatement.trim().length >= 40 && draft.desiredOutcome.trim().length >= 3,
    Boolean(draft.stakeholder.trim() && draft.affectedUsers.trim() && draft.siteContext.trim()),
    Boolean(draft.proposalTitle.trim() && trimmedObjectives.length && draft.proposedSolution.trim()),
    draft.evidenceReferences.every((reference) => Boolean(reference.label.trim() && (reference.location?.trim() || reference.storedDocumentId?.trim()))),
  ]
  const readyToSubmit = stepReady.every(Boolean)
  const checks = [
    { label: 'Observed condition recorded', done: stepReady[0] },
    { label: 'People and site identified', done: stepReady[1] },
    { label: 'Proposal and objectives recorded', done: stepReady[2] },
    { label: draft.evidenceReferences.length ? 'Evidence references are complete' : 'Evidence explicitly left unassessed', done: stepReady[3] },
  ]
  const completeness = Math.round(checks.filter((check) => check.done).length / checks.length * 100)

  const updateObjective = (index: number, value: string) => update('objectives', draft.objectives.map((item, itemIndex) => itemIndex === index ? value : item))
  const addEvidence = () => update('evidenceReferences', [...draft.evidenceReferences, { type: 'URL', label: '', location: '' }])
  const updateEvidence = (index: number, patch: Partial<IntakeEvidenceReferenceInput>) => update('evidenceReferences', draft.evidenceReferences.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item))

  async function handleDiscovery(event: FormEvent) {
    event.preventDefault()
    if (!readyToSubmit) {
      setError('Complete the required fields in Problem, Context, and Proposal before submitting the intake.')
      return
    }
    setIsRunning(true)
    setError('')
    try {
      const created = await submitIntakeForDiscovery({
        problem: {
          title: draft.problemTitle.trim(), problemStatement: draft.problemStatement.trim(), stakeholder: draft.stakeholder.trim(),
          affectedUsers: draft.affectedUsers.trim(), siteContext: draft.siteContext.trim(), desiredOutcome: draft.desiredOutcome.trim(),
          constraints: optional(draft.constraints), privacyClassification: draft.privacyClassification,
        },
        proposal: {
          title: draft.proposalTitle.trim(), objectives: trimmedObjectives, proposedSolution: draft.proposedSolution.trim(),
          methodology: optional(draft.methodology), dataSources: optional(draft.dataSources), technology: optional(draft.technology),
          intendedUsers: optional(draft.intendedUsers),
        },
        evidenceReferences: draft.evidenceReferences.length ? draft.evidenceReferences.map((reference) => ({
          type: reference.type, label: reference.label.trim(), location: optional(reference.location ?? ''),
          storedDocumentId: optional(reference.storedDocumentId ?? ''), sha256: optional(reference.sha256 ?? ''),
        })) : undefined,
      }, draft.idempotencyKey)
      setResult(created)
    } catch (reason) {
      setError(reason instanceof ApiProblem ? reason.detail : reason instanceof Error ? reason.message : 'The intake could not be persisted. No local success was assumed.')
    } finally {
      setIsRunning(false)
    }
  }

  const discovery = result?.discovery
  const discoveryState = discovery?.assessmentStatus ?? discovery?.status ?? 'UNASSESSED'
  const assessed = discoveryState === 'ASSESSED'

  return (
    <div className="page intake-page">
      <PageHeader eyebrow="Frame the need before the solution" title="Intake Studio"
        description="Create one traceable problem, proposal, evidence set, and queued discovery run through a repeat-safe submission."
        actions={<StatusPill tone="amber"><Save size={13} /> Local draft only</StatusPill>}
        meta={<><StatusPill tone={result ? 'teal' : 'amber'}>{result ? result.replayed ? 'Existing submission recovered' : 'Server submission confirmed' : 'Not submitted'}</StatusPill><span>Retry key {draft.idempotencyKey.slice(0, 8)}</span></>} />
      <EvidenceChain active="PROBLEM" />
      {error ? <div className="recorded-banner" role="alert"><FileText size={20} /><div><strong>Intake not submitted</strong><span>{error}</span></div></div> : null}

      {result ? <section className={`discovery-result-banner ${assessed ? '' : 'is-partial'}`} aria-live="polite">
        <div className="result-symbol"><Sparkles size={21} /></div>
        <div><span>{discoveryState} · {discovery?.algorithmVersion || 'algorithm unavailable'}</span>
          <h2>{assessed ? `Machine route: ${discovery?.recommendation.replaceAll('_', ' ')}` : 'No assessed route is being claimed.'}</h2>
          <p>{assessed
            ? `${discovery?.candidates.length ?? 0} candidate records were returned${discovery?.confidence == null ? '; confidence is UNASSESSED.' : ` with ${Math.round(discovery.confidence)}% evidence confidence.`}`
            : discovery?.explanation || 'The records are saved, but discovery is partial or unavailable. A human decision remains required.'}</p></div>
        <button className="button button-primary" onClick={() => navigate(`/decision?proposalId=${encodeURIComponent(result.proposal.id)}&runId=${encodeURIComponent(result.discovery.id)}`)}>Select in Decision Room <ArrowRight size={16} /></button>
      </section> : null}

      <div className="studio-grid">
        <aside className="studio-steps" aria-label="Problem intake progress">{intakeSteps.map((item, index) => <button type="button" key={item.label} onClick={() => setStep(index)} aria-current={index === step ? 'step' : undefined} className={`${index === step ? 'is-current' : ''} ${index < 4 && stepReady[index] ? 'is-complete' : ''}`}><span>{index < 4 && stepReady[index] ? <Check size={14} /> : String(index + 1).padStart(2, '0')}</span><div><strong>{item.label}</strong><small>{item.hint}</small></div><ChevronRight size={15} /></button>)}
          <div className="completeness-gauge" style={{ '--complete': completeness } as React.CSSProperties}><div><strong>{completeness}%</strong><span>required sections</span></div></div>
        </aside>

        <form className="intake-form paper-panel" onSubmit={handleDiscovery}>
          {step === 0 ? <>
            <div className="form-section-heading"><span>01 · PROBLEM EVIDENCE</span><h2>Describe the observed condition.</h2><p>State the condition and desired outcome without assuming a technical answer.</p></div>
            <div className="field-group"><label htmlFor="problem-title">Working problem title <span>Required</span></label><input id="problem-title" maxLength={160} required value={draft.problemTitle} onChange={(event) => update('problemTitle', event.target.value)} /><small>{draft.problemTitle.length}/160</small></div>
            <div className="field-group"><label htmlFor="problem-statement">Observed problem <span>Required · minimum 40 characters</span></label><textarea id="problem-statement" rows={6} minLength={40} maxLength={1500} required value={draft.problemStatement} onChange={(event) => update('problemStatement', event.target.value)} /><small>{draft.problemStatement.length}/1,500</small></div>
            <div className="field-group"><label htmlFor="desired-outcome">Desired outcome <span>Required</span></label><textarea id="desired-outcome" rows={3} required value={draft.desiredOutcome} onChange={(event) => update('desiredOutcome', event.target.value)} /></div>
          </> : null}

          {step === 1 ? <>
            <div className="form-section-heading"><span>02 · OPERATING CONTEXT</span><h2>Name the people, place, constraints, and privacy boundary.</h2><p>These facts narrow discovery and govern who may see the resulting evidence.</p></div>
            <div className="form-row"><div className="field-group with-icon"><label htmlFor="stakeholder">Primary stakeholder <span>Required</span></label><div><Users size={16} /><input id="stakeholder" required value={draft.stakeholder} onChange={(event) => update('stakeholder', event.target.value)} /></div></div><div className="field-group with-icon"><label htmlFor="affected-users">Affected users <span>Required</span></label><div><Users size={16} /><input id="affected-users" required value={draft.affectedUsers} onChange={(event) => update('affectedUsers', event.target.value)} /></div></div></div>
            <div className="field-group with-icon"><label htmlFor="site-context">Site and operating context <span>Required</span></label><div><MapPin size={16} /><input id="site-context" required value={draft.siteContext} onChange={(event) => update('siteContext', event.target.value)} /></div></div>
            <div className="field-group"><label htmlFor="constraints">Known constraints <span>Optional · blank remains unassessed</span></label><textarea id="constraints" rows={4} value={draft.constraints} onChange={(event) => update('constraints', event.target.value)} /></div>
            <div className="field-group"><label htmlFor="privacy">Privacy classification</label><select id="privacy" value={draft.privacyClassification} onChange={(event) => update('privacyClassification', event.target.value as IntakeDraft['privacyClassification'])}><option value="PUBLIC">Public</option><option value="INTERNAL">Internal</option><option value="RESTRICTED">Restricted</option></select></div>
          </> : null}

          {step === 2 ? <>
            <div className="form-section-heading"><span>03 · PROPOSED STUDY</span><h2>Separate the proposed study from the problem.</h2><p>The proposal remains subject to adviser and coordinator review.</p></div>
            <div className="field-group"><label htmlFor="proposal-title">Proposal title <span>Required</span></label><input id="proposal-title" required value={draft.proposalTitle} onChange={(event) => update('proposalTitle', event.target.value)} /></div>
            <div className="objective-editor"><div><span>OBJECTIVES</span><button type="button" className="text-button" onClick={() => update('objectives', [...draft.objectives, ''])}><Plus size={14} /> Add objective</button></div>{draft.objectives.map((objective, index) => <label key={index} htmlFor={`objective-${index}`}><b>O{index + 1}</b><textarea id={`objective-${index}`} rows={2} required={index === 0} value={objective} onChange={(event) => updateObjective(index, event.target.value)} /></label>)}</div>
            <div className="field-group"><label htmlFor="solution">Proposed solution or intervention <span>Required</span></label><textarea id="solution" rows={4} required value={draft.proposedSolution} onChange={(event) => update('proposedSolution', event.target.value)} /></div>
            <div className="form-row"><div className="field-group"><label htmlFor="methodology">Methodology <span>Optional</span></label><input id="methodology" value={draft.methodology} onChange={(event) => update('methodology', event.target.value)} /></div><div className="field-group"><label htmlFor="technology">Technology <span>Optional</span></label><input id="technology" value={draft.technology} onChange={(event) => update('technology', event.target.value)} /></div></div>
            <div className="form-row"><div className="field-group"><label htmlFor="data-sources">Data sources <span>Optional</span></label><input id="data-sources" value={draft.dataSources} onChange={(event) => update('dataSources', event.target.value)} /></div><div className="field-group"><label htmlFor="intended-users">Intended users <span>Optional</span></label><input id="intended-users" value={draft.intendedUsers} onChange={(event) => update('intendedUsers', event.target.value)} /></div></div>
          </> : null}

          {step === 3 ? <>
            <div className="form-section-heading"><span>04 · SOURCE REFERENCES</span><h2>Link evidence that a reviewer can inspect.</h2><p>References are optional. If none are recorded, evidence remains explicitly UNASSESSED.</p></div>
            <div className="intake-reference-list">{draft.evidenceReferences.map((reference, index) => <fieldset key={index}><legend>Reference {index + 1}</legend><div className="form-row"><label>Type<select value={reference.type} onChange={(event) => updateEvidence(index, { type: event.target.value as EvidenceReferenceType })}><option value="DOCUMENT">Document</option><option value="URL">URL</option><option value="REPOSITORY">Repository</option><option value="OUTPUT">Output</option><option value="TEST_RUN">Test run</option><option value="DATASET">Dataset</option><option value="OTHER">Other</option></select></label><label>Evidence label<input required value={reference.label} onChange={(event) => updateEvidence(index, { label: event.target.value })} /></label></div><div className="form-row"><label>Location or URL<input value={reference.location ?? ''} onChange={(event) => updateEvidence(index, { location: event.target.value })} /></label><label>Stored document ID<input value={reference.storedDocumentId ?? ''} onChange={(event) => updateEvidence(index, { storedDocumentId: event.target.value })} /></label></div><label>SHA-256 <small>Optional</small><input pattern="[0-9a-fA-F]{64}" value={reference.sha256 ?? ''} onChange={(event) => updateEvidence(index, { sha256: event.target.value })} /></label><button type="button" className="text-button danger" onClick={() => update('evidenceReferences', draft.evidenceReferences.filter((_, itemIndex) => itemIndex !== index))}><Trash2 size={14} /> Remove reference</button></fieldset>)}</div>
            {draft.evidenceReferences.length ? null : <div className="intake-empty-evidence"><FilePlus2 size={22} /><strong>No evidence reference recorded</strong><span>This is honest and allowed; readiness will remain unassessed where evidence is required.</span></div>}
            <button type="button" className="button button-secondary" onClick={addEvidence}><Link2 size={15} /> Add evidence reference</button>
          </> : null}

          {step === 4 ? <>
            <div className="form-section-heading"><span>05 · REVIEW AND SUBMIT</span><h2>Confirm the exact records to create.</h2><p>One atomic request creates the problem, proposal, evidence references, and discovery run. Retrying uses the same key.</p></div>
            <dl className="intake-review"><div><dt>Problem</dt><dd>{draft.problemTitle || 'UNASSESSED'}</dd></div><div><dt>Context</dt><dd>{draft.siteContext || 'UNASSESSED'} · {draft.privacyClassification}</dd></div><div><dt>Proposal</dt><dd>{draft.proposalTitle || 'UNASSESSED'} · {trimmedObjectives.length} objectives</dd></div><div><dt>Evidence</dt><dd>{draft.evidenceReferences.length ? `${draft.evidenceReferences.length} inspectable reference(s)` : 'UNASSESSED'}</dd></div><div><dt>Retry protection</dt><dd><code>{draft.idempotencyKey}</code></dd></div></dl>
            <ExplainabilityNote>UGNAY may explain similarity, but it cannot approve, reject, certify duplication, or replace adviser and coordinator judgment.</ExplainabilityNote>
          </> : null}

          <div className="form-actions"><button type="button" className="button button-ghost" disabled={step === 0 || isRunning} onClick={() => setStep((current) => Math.max(0, current - 1))}>Back</button>{step < 4 ? <button type="button" className="button button-primary" disabled={!stepReady[step]} onClick={() => setStep((current) => Math.min(4, current + 1))}>Continue <ArrowRight size={16} /></button> : <button className="button button-primary" disabled={!readyToSubmit || isRunning}>{isRunning ? 'Creating one evidence chain...' : 'Submit and run discovery'} <ArrowRight size={16} /></button>}</div>
        </form>

        <aside className="intake-evidence-panel" aria-label="Intake evidence checklist"><div className="evidence-score"><span>REQUIRED SECTIONS</span><strong>{checks.filter((check) => check.done).length}/{checks.length}</strong><small>ready for atomic submission</small></div><div className="checklist">{checks.map((check) => <div key={check.label} className={check.done ? 'is-done' : ''}>{check.done ? <CheckCircle2 size={17} /> : <Circle size={17} />}<span>{check.label}</span></div>)}</div><p className="panel-footnote">Missing optional evidence remains <b>UNASSESSED</b>. UGNAY never inserts plausible text or a false zero.</p></aside>
      </div>
    </div>
  )
}
