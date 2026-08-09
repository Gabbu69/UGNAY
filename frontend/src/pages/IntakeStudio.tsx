import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, Check, CheckCircle2, ChevronRight, Circle, FileText, MapPin, Save, Sparkles, Users } from 'lucide-react'
import { ApiProblem, submitIntakeForDiscovery } from '../lib/api'
import type { DiscoveryRun } from '../types/domain'
import { EvidenceChain } from '../components/EvidenceChain'
import { ExplainabilityNote, PageHeader, StatusPill } from '../components/Primitives'

const intakeSteps = ['Problem', 'Context', 'Objectives', 'Evidence', 'Review']

export default function IntakeStudio() {
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const saved = (() => { try { return JSON.parse(localStorage.getItem('ugnay-intake-draft') ?? '{}') as Record<string, string> } catch { return {} } })()
  const [title, setTitle] = useState(saved.title ?? '')
  const [problem, setProblem] = useState(saved.problem ?? '')
  const [stakeholder, setStakeholder] = useState(saved.stakeholder ?? '')
  const [context, setContext] = useState(saved.context ?? '')
  const [objectiveOne, setObjectiveOne] = useState(saved.objectiveOne ?? '')
  const [objectiveTwo, setObjectiveTwo] = useState(saved.objectiveTwo ?? '')
  const [isRunning, setIsRunning] = useState(false)
  const [result, setResult] = useState<DiscoveryRun>()
  const [error, setError] = useState('')
  useEffect(() => {
    localStorage.setItem('ugnay-intake-draft', JSON.stringify({ title, problem, stakeholder, context, objectiveOne, objectiveTwo }))
  }, [context, objectiveOne, objectiveTwo, problem, stakeholder, title])

  const checks = useMemo(() => [
    { label: 'Specific affected users', done: stakeholder.length > 8 },
    { label: 'Observed current condition', done: problem.length > 80 },
    { label: 'Desired measurable outcome', done: /reduce|increase|within|at least/i.test(`${objectiveOne} ${objectiveTwo}`) },
    { label: 'Place and operating context', done: context.length > 12 },
    { label: 'Supporting evidence attached', done: false },
  ], [context, objectiveOne, objectiveTwo, problem, stakeholder])
  const completeness = Math.round(checks.filter((check) => check.done).length / checks.length * 100)

  async function handleDiscovery() {
    setIsRunning(true)
    setError('')
    try {
      const discovery = await submitIntakeForDiscovery({ title, problemStatement: problem, objectives: [objectiveOne, objectiveTwo].filter(Boolean), stakeholder, siteContext: context })
      setResult(discovery)
    } catch (reason) {
      setError(reason instanceof ApiProblem ? reason.detail : 'The intake could not be persisted. Sign in and verify the required evidence.')
    } finally { setIsRunning(false) }
  }

  return (
    <div className="page intake-page">
      <PageHeader
        eyebrow="Frame the need before the solution"
        title="Intake Studio"
        description="Capture enough real-world evidence to search prior work without prematurely designing a system."
        actions={<StatusPill tone="amber"><Save size={13} /> Locally autosaved draft</StatusPill>}
        meta={<><StatusPill tone="amber">Not yet submitted</StatusPill><span>Server records are created only when discovery begins</span></>}
      />
      <EvidenceChain active="PROBLEM" />
      {error ? <div className="recorded-banner" role="alert"><FileText size={20} /><div><strong>Intake not submitted</strong><span>{error}</span></div></div> : null}

      {result ? (
        <section className={`discovery-result-banner ${result.status === 'PARTIAL' ? 'is-partial' : ''}`} aria-live="polite">
          <div className="result-symbol"><Sparkles size={21} /></div>
          <div>
            <span>{result.status === 'PARTIAL' ? 'DISCOVERY UNAVAILABLE · NOT ASSESSED' : `DISCOVERY READY · ${result.algorithmVersion}`}</span>
            <h2>{result.status === 'PARTIAL' ? 'No route recommendation was produced.' : 'Prior work suggests an improvement pathway—not an automatic decision.'}</h2>
            <p>{result.status === 'PARTIAL' ? 'Your intake remains saved. Retry when the live analysis service is available.' : `${result.candidates.length} explainable candidates found with ${result.confidence}% evidence confidence.`}</p>
          </div>
          <button className="button button-primary" onClick={result.status === 'PARTIAL' ? handleDiscovery : () => navigate('/decision')}>{result.status === 'PARTIAL' ? 'Retry discovery' : 'Enter Decision Room'} <ArrowRight size={16} /></button>
        </section>
      ) : null}

      <div className="studio-grid">
        <aside className="studio-steps" aria-label="Problem intake progress">
          {intakeSteps.map((label, index) => (
            <button key={label} onClick={() => setStep(index)} className={`${index === step ? 'is-current' : ''} ${index < step ? 'is-complete' : ''}`}>
              <span>{index < step ? <Check size={14} /> : String(index + 1).padStart(2, '0')}</span>
              <div><strong>{label}</strong><small>{index === 0 ? 'What is happening?' : index === 1 ? 'Where and for whom?' : index === 2 ? 'What must improve?' : index === 3 ? 'What supports this?' : 'Ready to discover?'}</small></div>
              <ChevronRight size={15} />
            </button>
          ))}
          <div className="completeness-gauge" style={{ '--complete': completeness } as React.CSSProperties}>
            <div><strong>{completeness}%</strong><span>discovery ready</span></div>
          </div>
        </aside>

        <section className="intake-form paper-panel">
          <div className="form-section-heading"><span>01 · PROBLEM EVIDENCE</span><h2>Describe the condition, not your proposed app.</h2><p>A good problem statement names who is affected, what happens now, and why the outcome matters.</p></div>
          <div className="field-group">
            <label htmlFor="case-title">Working problem title <span>Required</span></label>
            <input id="case-title" value={title} onChange={(event) => setTitle(event.target.value)} />
            <small>{title.length}/160 · Avoid product names at this stage.</small>
          </div>
          <div className="field-group">
            <label htmlFor="problem-statement">Observed problem <span>Required</span></label>
            <textarea id="problem-statement" rows={6} value={problem} onChange={(event) => setProblem(event.target.value)} />
            <small>{problem.length}/1,500 · Based on interviews and incident records.</small>
          </div>
          <div className="form-row">
            <div className="field-group with-icon"><label htmlFor="stakeholder">Primary stakeholder</label><div><Users size={16} /><input id="stakeholder" value={stakeholder} onChange={(event) => setStakeholder(event.target.value)} /></div></div>
            <div className="field-group with-icon"><label htmlFor="context">Site and context</label><div><MapPin size={16} /><input id="context" value={context} onChange={(event) => setContext(event.target.value)} /></div></div>
          </div>
          <div className="objective-editor">
            <div><span>PROPOSED OUTCOMES</span><small>2 objectives</small></div>
            <label htmlFor="objective-one"><b>O1</b><textarea id="objective-one" rows={2} value={objectiveOne} onChange={(event) => setObjectiveOne(event.target.value)} /></label>
            <label htmlFor="objective-two"><b>O2</b><textarea id="objective-two" rows={2} value={objectiveTwo} onChange={(event) => setObjectiveTwo(event.target.value)} /></label>
          </div>
          <ExplainabilityNote>UGNAY compares the problem and objectives separately. A matching title alone can never trigger a duplicate recommendation.</ExplainabilityNote>
          <div className="form-actions"><button className="button button-ghost" disabled={step === 0} onClick={() => setStep((current) => Math.max(0, current - 1))}>Back</button><button className="button button-primary" onClick={handleDiscovery} disabled={isRunning}>{isRunning ? 'Building evidence profile…' : 'Find related studies'}<ArrowRight size={16} /></button></div>
        </section>

        <aside className="intake-evidence-panel" aria-label="Problem evidence checklist">
          <div className="evidence-score"><span>DISCOVERY INPUT</span><strong>{checks.filter((check) => check.done).length}/{checks.length}</strong><small>evidence conditions ready</small></div>
          <div className="checklist">
            {checks.map((check) => <div key={check.label} className={check.done ? 'is-done' : ''}>{check.done ? <CheckCircle2 size={17} /> : <Circle size={17} />}<span>{check.label}</span></div>)}
          </div>
          <div className="evidence-upload"><FileText size={20} /><strong>Field evidence</strong><p>0 stored attachments or references</p><button className="text-button" disabled>Evidence upload is not yet available for this draft</button></div>
          <p className="panel-footnote">Missing evidence returns <b>Review required</b>. It never silently lowers the matching threshold.</p>
        </aside>
      </div>
    </div>
  )
}
