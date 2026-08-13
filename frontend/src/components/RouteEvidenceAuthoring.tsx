import { useMemo, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, FileCheck2, GitPullRequestArrow, ShieldCheck } from 'lucide-react'
import {
  ApiProblem,
  recordContinuationEvidence,
  recordImprovementClaim,
  type AuthorizedStudyDetail,
  type ProposalObjectiveRecord,
} from '../lib/api'
import { StatusPill } from './Primitives'

interface RouteEvidenceAuthoringProps {
  proposalId: string
  predecessorStudyId: string
  objectives: ProposalObjectiveRecord[]
  study?: AuthorizedStudyDetail
  studyLoading: boolean
  studyUnavailable: boolean
  canAuthor: boolean
}

interface ObjectiveLinkDraft {
  continuationItemId: string
  rationale: string
}

function mutationMessage(error: Error | null) {
  return error instanceof ApiProblem ? error.detail : error?.message
}

export default function RouteEvidenceAuthoring({
  proposalId,
  predecessorStudyId,
  objectives,
  study,
  studyLoading,
  studyUnavailable,
  canAuthor,
}: RouteEvidenceAuthoringProps) {
  const queryClient = useQueryClient()
  const [objectiveLinks, setObjectiveLinks] = useState<Record<string, ObjectiveLinkDraft>>({})
  const [codeAccessConfirmed, setCodeAccessConfirmed] = useState(false)
  const [dataAccessConfirmed, setDataAccessConfirmed] = useState(false)
  const [accessNotes, setAccessNotes] = useState('')
  const [improvementItemId, setImprovementItemId] = useState('')
  const [claim, setClaim] = useState('')
  const [baselineMeasure, setBaselineMeasure] = useState('')
  const [targetMeasure, setTargetMeasure] = useState('')
  const [evaluationMethod, setEvaluationMethod] = useState('')

  const openItems = useMemo(
    () => (study?.continuationItems ?? []).filter((item) => item.status === 'OPEN' && !item.claimed),
    [study?.continuationItems],
  )
  const improvementItems = useMemo(
    () => openItems.filter((item) => item.type === 'LIMITATION' || item.type === 'RECOMMENDATION'),
    [openItems],
  )
  const mappedLinks = objectives.flatMap((objective) => {
    const draft = objectiveLinks[objective.id]
    if (!draft?.continuationItemId) return []
    return [{
      proposalObjectiveId: objective.id,
      continuationItemId: draft.continuationItemId,
      rationale: draft.rationale.trim(),
    }]
  })
  const continuationValid = mappedLinks.length > 0
    && mappedLinks.every((link) => link.rationale.length >= 10)
    && accessNotes.trim().length >= 10
  const improvementValid = Boolean(
    improvementItemId
    && claim.trim().length >= 10
    && baselineMeasure.trim()
    && targetMeasure.trim()
    && evaluationMethod.trim().length >= 10,
  )

  const refreshExactEvidence = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['route-evidence', proposalId, predecessorStudyId] }),
      queryClient.invalidateQueries({ queryKey: ['decision-context', proposalId] }),
    ])
  }
  const continuationMutation = useMutation({
    mutationFn: () => recordContinuationEvidence(proposalId, {
      predecessorStudyId,
      objectiveLinks: mappedLinks,
      codeAccessConfirmed,
      dataAccessConfirmed,
      accessNotes: accessNotes.trim(),
    }),
    onSuccess: async () => {
      setObjectiveLinks({})
      setCodeAccessConfirmed(false)
      setDataAccessConfirmed(false)
      setAccessNotes('')
      await refreshExactEvidence()
    },
  })
  const improvementMutation = useMutation({
    mutationFn: () => recordImprovementClaim(proposalId, {
      predecessorStudyId,
      continuationItemId: improvementItemId,
      claim: claim.trim(),
      baselineMeasure: baselineMeasure.trim(),
      targetMeasure: targetMeasure.trim(),
      evaluationMethod: evaluationMethod.trim(),
    }),
    onSuccess: async () => {
      setImprovementItemId('')
      setClaim('')
      setBaselineMeasure('')
      setTargetMeasure('')
      setEvaluationMethod('')
      await refreshExactEvidence()
    },
  })

  const updateLink = (objectiveId: string, patch: Partial<ObjectiveLinkDraft>) => {
    setObjectiveLinks((current) => ({
      ...current,
      [objectiveId]: { ...(current[objectiveId] ?? { continuationItemId: '', rationale: '' }), ...patch },
    }))
  }
  const submitContinuation = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (continuationValid) continuationMutation.mutate()
  }
  const submitImprovement = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (improvementValid) improvementMutation.mutate()
  }

  if (studyLoading) return <section className="paper-panel route-authoring-panel" aria-live="polite"><p>Loading authorized predecessor details…</p></section>
  if (studyUnavailable || !study) return <section className="paper-panel route-authoring-panel" role="alert"><p>Predecessor details are UNAVAILABLE. UGNAY will not infer continuation items or accept invented references.</p></section>

  const supportsContinuation = study.lifecycleStatus === 'INCOMPLETE' || study.lifecycleStatus === 'SUSPENDED'
  const supportsImprovement = study.lifecycleStatus === 'COMPLETED'

  return (
    <section className="paper-panel route-authoring-panel" aria-labelledby="route-evidence-authoring-heading">
      <div className="section-heading compact">
        <div><span>APPEND-ONLY ROUTE EVIDENCE</span><h2 id="route-evidence-authoring-heading">Author prerequisites for the selected predecessor</h2></div>
        <StatusPill tone="neutral">{study.lifecycleStatus}</StatusPill>
      </div>
      <p className="route-authoring-intro">Every reference below is bound to <strong>{study.institutionalCode}</strong>. Select persisted objectives and open predecessor items; no ID or evidence is supplied automatically.</p>

      {!canAuthor ? <div className="permission-note">A signed-in Student, Adviser, or Coordinator with proposal access may append route evidence.</div> : null}

      {canAuthor && supportsContinuation ? <form className="route-evidence-form" onSubmit={submitContinuation}>
        <div className="route-form-heading"><GitPullRequestArrow size={18} /><div><strong>Continue evidence revision</strong><small>Map at least 60% of proposal objectives and record the actual access state.</small></div></div>
        {!objectives.length ? <p className="form-alert" role="status">Proposal objectives are UNAVAILABLE; a continuation revision cannot be authored.</p> : null}
        {!openItems.length ? <p className="form-alert" role="status">No authorized open continuation item is available for this predecessor.</p> : null}
        <div className="objective-link-grid">
          {objectives.map((objective) => {
            const draft = objectiveLinks[objective.id] ?? { continuationItemId: '', rationale: '' }
            return <fieldset key={objective.id}>
              <legend>Objective {objective.order}: {objective.statement}</legend>
              <label><span>Open predecessor item</span><select value={draft.continuationItemId} onChange={(event) => updateLink(objective.id, { continuationItemId: event.target.value })} disabled={!openItems.length}><option value="">Not mapped</option>{openItems.map((item) => <option key={item.id} value={item.id}>{item.type} · {item.title}</option>)}</select></label>
              <label><span>Mapping rationale</span><textarea value={draft.rationale} onChange={(event) => updateLink(objective.id, { rationale: event.target.value })} disabled={!draft.continuationItemId} required={Boolean(draft.continuationItemId)} minLength={10} placeholder={draft.continuationItemId ? 'Explain how this objective continues the selected open work.' : 'Select an item before recording a rationale.'} /></label>
            </fieldset>
          })}
        </div>
        <div className="route-access-grid">
          <label className="evidence-confirmation"><input type="checkbox" checked={codeAccessConfirmed} onChange={(event) => setCodeAccessConfirmed(event.target.checked)} /><span><Check size={12} /></span>Repository and code access confirmed</label>
          <label className="evidence-confirmation"><input type="checkbox" checked={dataAccessConfirmed} onChange={(event) => setDataAccessConfirmed(event.target.checked)} /><span><Check size={12} /></span>Required data access confirmed</label>
        </div>
        <label><span>Access notes</span><textarea required minLength={10} value={accessNotes} onChange={(event) => setAccessNotes(event.target.value)} placeholder="Record what was verified and what remains unavailable." /></label>
        <button className="button button-secondary" type="submit" disabled={!continuationValid || continuationMutation.isPending || !objectives.length || !openItems.length}><FileCheck2 size={16} />{continuationMutation.isPending ? 'Recording revision…' : 'Append continuation evidence'}</button>
        {continuationMutation.isSuccess ? <p className="form-success" role="status"><ShieldCheck size={15} />The continuation evidence revision was appended and readiness was recalculated.</p> : null}
        {continuationMutation.isError ? <p className="form-alert" role="alert">{mutationMessage(continuationMutation.error)}</p> : null}
      </form> : null}

      {canAuthor && supportsImprovement ? <form className="route-evidence-form" onSubmit={submitImprovement}>
        <div className="route-form-heading"><FileCheck2 size={18} /><div><strong>Measured improvement claim</strong><small>Bind the claim to one persisted limitation or recommendation.</small></div></div>
        {!improvementItems.length ? <p className="form-alert" role="status">No authorized open limitation or recommendation is available for this predecessor.</p> : null}
        <label><span>Predecessor basis</span><select required value={improvementItemId} onChange={(event) => setImprovementItemId(event.target.value)} disabled={!improvementItems.length}><option value="">Select an open item…</option>{improvementItems.map((item) => <option key={item.id} value={item.id}>{item.type} · {item.title}</option>)}</select></label>
        <label><span>Improvement claim</span><textarea required minLength={10} value={claim} onChange={(event) => setClaim(event.target.value)} placeholder="State the specific predecessor limitation this proposal will improve." /></label>
        <div className="route-measure-grid">
          <label><span>Baseline measure</span><input required value={baselineMeasure} onChange={(event) => setBaselineMeasure(event.target.value)} placeholder="Existing measured condition and unit" /></label>
          <label><span>Target measure</span><input required value={targetMeasure} onChange={(event) => setTargetMeasure(event.target.value)} placeholder="Target condition using the same unit" /></label>
        </div>
        <label><span>Evaluation method</span><textarea required minLength={10} value={evaluationMethod} onChange={(event) => setEvaluationMethod(event.target.value)} placeholder="Explain how the baseline and target will be compared." /></label>
        <button className="button button-secondary" type="submit" disabled={!improvementValid || improvementMutation.isPending || !improvementItems.length}><FileCheck2 size={16} />{improvementMutation.isPending ? 'Recording claim…' : 'Append improvement claim'}</button>
        {improvementMutation.isSuccess ? <p className="form-success" role="status"><ShieldCheck size={15} />The measured improvement claim was appended and readiness was recalculated.</p> : null}
        {improvementMutation.isError ? <p className="form-alert" role="alert">{mutationMessage(improvementMutation.error)}</p> : null}
      </form> : null}

      {canAuthor && !supportsContinuation && !supportsImprovement ? <p className="permission-note">This predecessor lifecycle does not support a Continue or Improve prerequisite record.</p> : null}
    </section>
  )
}
