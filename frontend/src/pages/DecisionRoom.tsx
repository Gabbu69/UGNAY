import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, ClipboardCheck, GitCompareArrows, ShieldCheck, Users } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import {
  ApiProblem, getAdviserRecommendations, getDecisionContext, getRouteEvidence,
  recordAcademicDecision, recordAdviserRecommendation, type AdviserRecommendationRecord,
} from '../lib/api'
import { DimensionBar, ExplainabilityNote, PageHeader, StatusPill } from '../components/Primitives'
import type { DecisionDisposition } from '../types/domain'

const decisionOptions: Array<{ value: DecisionDisposition; label: string; detail: string }> = [
  { value: 'APPROVE_NEW', label: 'New study', detail: 'Distinct problem or substantial novel objectives' },
  { value: 'APPROVE_IMPROVE', label: 'Improve prior work', detail: 'Requires a measured limitation, baseline, target, and evaluation method' },
  { value: 'APPROVE_CONTINUE', label: 'Continue unfinished work', detail: 'Requires objective mappings and confirmed code/data access' },
  { value: 'RETURN_FOR_REVISION', label: 'Return for revision', detail: 'Evidence or framing must be corrected before routing' },
  { value: 'CLOSE_AS_DUPLICATE', label: 'Close as duplicate', detail: 'Human-reviewed duplicate closure with preserved rationale' },
]

export default function DecisionRoom() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const queryClient = useQueryClient()
  const context = useQuery({ queryKey: ['decision-context'], queryFn: getDecisionContext, enabled: data?.source === 'LIVE' })
  const proposal = context.data?.proposal
  const discovery = context.data?.discovery
  const candidateScore = discovery?.candidates[0]
  const primary = data?.data.studies.find((study) => study.id === candidateScore?.studyId) ?? data?.data.studies[0]
  const [decision, setDecision] = useState<DecisionDisposition>('RETURN_FOR_REVISION')
  const [rationale, setRationale] = useState('')
  const [reviewed, setReviewed] = useState(false)
  const canRecord = data?.source === 'LIVE' && auth?.session.authenticated === true && auth.session.roles.includes('COORDINATOR')
  const canRecommend = data?.source === 'LIVE' && auth?.session.authenticated === true && auth.session.roles.includes('ADVISER')
  const recommendations = useQuery({ queryKey: ['adviser-recommendations', proposal?.id], queryFn: () => getAdviserRecommendations(proposal?.id ?? ''), enabled: Boolean(data?.source === 'LIVE' && proposal?.id) })
  const routeEvidence = useQuery({ queryKey: ['route-evidence', proposal?.id, primary?.id], queryFn: () => getRouteEvidence(proposal?.id ?? '', primary?.id ?? ''), enabled: Boolean(data?.source === 'LIVE' && proposal?.id && primary?.id && auth?.session.authenticated) })
  const [adviserRoute, setAdviserRoute] = useState<AdviserRecommendationRecord['recommendation']>('REVIEW_REQUIRED')
  const [adviserRationale, setAdviserRationale] = useState('')
  const adviserMutation = useMutation({
    mutationFn: () => recordAdviserRecommendation(proposal?.id ?? '', discovery?.id ?? '', adviserRoute, adviserRationale),
    onSuccess: () => {
      setAdviserRationale('')
      queryClient.invalidateQueries({ queryKey: ['adviser-recommendations', proposal?.id] })
    },
  })
  const decisionMutation = useMutation({
    mutationFn: () => recordAcademicDecision({ disposition: decision, rationale, primaryPredecessorId: primary?.id }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workspace'] })
      queryClient.invalidateQueries({ queryKey: ['decision-context'] })
    },
  })
  const actionError = decisionMutation.error instanceof ApiProblem ? decisionMutation.error.detail : decisionMutation.error?.message
  const recommendation = discovery?.recommendation?.replaceAll('_', ' ') ?? 'UNASSESSED'
  const route = routeEvidence.data
  const structuredPrerequisiteReady = decision === 'APPROVE_CONTINUE' ? route?.continuationReady === true
    : decision === 'APPROVE_IMPROVE' ? route?.improvementReady === true : true

  return (
    <div className="page decision-page">
      <PageHeader eyebrow="Human decision, machine-explained evidence" title="Decision Room"
        description="Compare the selected persisted proposal and frozen discovery run before recording an accountable academic route."
        actions={<StatusPill tone="violet">Coordinator-controlled record</StatusPill>}
        meta={<><StatusPill tone={discovery ? 'violet' : 'amber'}>{discovery ? `Run ${discovery.id.slice(0, 8)}` : 'UNASSESSED'}</StatusPill><span>Algorithm: {discovery?.algorithmVersion ?? 'Unavailable'}</span></>} />

      {decisionMutation.isSuccess ? <div className="recorded-banner" role="status"><ShieldCheck size={20} /><div><strong>Formal route recorded</strong><span>The immutable coordinator decision is now part of the evidence trail.</span></div></div> : null}
      {decisionMutation.isError ? <div className="recorded-banner" role="alert"><ShieldCheck size={20} /><div><strong>Decision was not recorded</strong><span>{actionError ?? 'Reload the live evidence and try again.'}</span></div></div> : null}

      <section className="recommendation-ribbon">
        <div><span>SYSTEM RECOMMENDATION</span><h2>{recommendation}</h2><p>{discovery?.explanation ?? 'No frozen discovery run is available; UGNAY cannot recommend a route.'}</p></div>
        <div className="confidence-stamp"><strong>{Math.round(discovery?.confidence ?? 0)}</strong><span>evidence<br />confidence</span></div>
        <div className="human-control"><Users size={18} /><p><b>Decision boundary</b><br />UGNAY cannot approve, reject, or declare duplication.</p></div>
      </section>

      {(recommendations.data?.length || canRecommend) ? <section className="adviser-recommendation-panel paper-panel">
        <div className="section-heading compact"><div><span>INDEPENDENT HUMAN REVIEW</span><h2>Adviser recommendations</h2></div><StatusPill tone="neutral">Does not decide the route</StatusPill></div>
        {recommendations.data?.length ? <div className="adviser-recommendation-list">{recommendations.data.map((item) => <article key={item.id}><ClipboardCheck size={16} /><div><span>{item.recommendation.replaceAll('_', ' ')}</span><strong>{item.rationale}</strong><small>{item.adviser} · {new Date(item.recordedAt).toLocaleString()}</small></div></article>)}</div> : <p className="adviser-empty">No adviser recommendation has been recorded against this frozen run.</p>}
        {canRecommend && proposal && discovery ? <form onSubmit={(event) => { event.preventDefault(); adviserMutation.mutate() }}><label><span>Recommended route</span><select value={adviserRoute} onChange={(event) => setAdviserRoute(event.target.value as AdviserRecommendationRecord['recommendation'])}><option value="REVIEW_REQUIRED">Review required</option><option value="NEW">New</option><option value="IMPROVE">Improve</option><option value="CONTINUE">Continue</option><option value="POSSIBLE_DUPLICATE">Possible duplicate</option></select></label><label><span>Adviser rationale</span><textarea required minLength={20} value={adviserRationale} onChange={(event) => setAdviserRationale(event.target.value)} placeholder="Explain how the frozen evidence supports this recommendation." /></label><button className="button button-secondary" disabled={adviserMutation.isPending || adviserRationale.trim().length < 20}>{adviserMutation.isPending ? 'Recording…' : 'Record immutable recommendation'}</button></form> : null}
        {adviserMutation.isError ? <p className="form-alert" role="alert">{adviserMutation.error instanceof ApiProblem ? adviserMutation.error.detail : adviserMutation.error.message}</p> : null}
      </section> : null}

      <div className="comparison-grid">
        <section className="comparison-card proposed"><div className="compare-label"><span>A</span>SELECTED PROPOSAL</div><h2>{proposal?.title ?? 'No persisted proposal available'}</h2><p>{proposal?.problemStatement ?? 'Submit an intake and complete discovery before academic routing.'}</p><div className="compare-facts"><span>{proposal?.siteContext ?? 'Context unavailable'}</span><span>{proposal?.stakeholder ?? 'Stakeholder unavailable'}</span><span>{proposal?.objectives.length ?? 0} objectives</span></div></section>
        <div className="compare-axis" aria-hidden="true"><GitCompareArrows size={18} /><span /></div>
        <section className="comparison-card prior"><div className="compare-label"><span>B</span>TOP CANDIDATE {primary?.code ?? ''}</div><h2>{primary?.title ?? 'No candidate'}</h2><p>{primary?.abstract ?? 'No comparable catalogue evidence was returned.'}</p><div className="compare-facts"><span>{primary ? `${primary.status} - ${primary.year}` : 'Unavailable'}</span><span>{primary?.authors.length ?? 0} recorded authors</span><span>{primary?.restricted ? 'Protected evidence' : 'Accessible evidence'}</span></div></section>
      </div>

      <div className="decision-body">
        <section className="evidence-comparison paper-panel"><div className="section-heading compact"><div><span>FIELD-BY-FIELD EVIDENCE</span><h2>Why these records are related</h2></div><StatusPill tone={discovery?.assessmentStatus === 'ASSESSED' ? 'teal' : 'amber'}>{discovery?.assessmentStatus ?? 'UNASSESSED'}</StatusPill></div>
          {candidateScore ? <div className="evidence-dimensions"><DimensionBar label="Problem similarity" value={candidateScore.problemScore} emphasis detail="Problem framing, stakeholder, and site evidence" /><DimensionBar label="Objective alignment" value={candidateScore.objectiveScore} detail="One-to-one objective matching" /><DimensionBar label="Solution approach" value={candidateScore.solutionScore} detail="Features, method, data, technology, and users" /><DimensionBar label="Comparable evidence" value={candidateScore.confidence} detail="Weighted fields that were available for comparison" /></div> : <p>No candidate dimensions are available.</p>}
          <ExplainabilityNote>Each prior objective can match only one proposed objective. Restricted passages may affect scoring but are never sent to unauthorized clients.</ExplainabilityNote>
        </section>

        <aside className="decision-panel panel-dark" aria-label="Formal academic decision record"><span className="panel-overline">FORMAL ROUTE</span><h2>Record the academic decision</h2><p>Every route references the frozen run above. Improve and Continue remain blocked until their structured evidence is complete.</p>
          {(decision === 'APPROVE_IMPROVE' || decision === 'APPROVE_CONTINUE') ? <div className="route-prerequisite-card"><strong>{decision === 'APPROVE_CONTINUE' ? 'Continue prerequisites' : 'Improve prerequisites'}</strong>{route ? decision === 'APPROVE_CONTINUE' ? <ul><li className={route.continuationCoverage >= 60 ? 'is-ready' : ''}><Check size={11} />Objective coverage {route.continuationCoverage.toFixed(0)}% / 60%</li><li className={route.codeAccess ? 'is-ready' : ''}><Check size={11} />Code access confirmed</li><li className={route.dataAccess ? 'is-ready' : ''}><Check size={11} />Data access confirmed</li></ul> : <ul><li className={route.improvementReady ? 'is-ready' : ''}><Check size={11} />Measured limitation claim recorded</li><li className={route.improvementClaimCount > 0 ? 'is-ready' : ''}><Check size={11} />Baseline, target, and method ({route.improvementClaimCount})</li></ul> : <p>Sign in to inspect the structured route evidence.</p>}<small>{structuredPrerequisiteReady ? 'Structured evidence is ready for coordinator review.' : 'The backend will reject this route until every prerequisite is recorded.'}</small></div> : null}
          <div className="decision-options">{decisionOptions.map((option) => <label key={option.value} className={decision === option.value ? 'is-selected' : ''}><input type="radio" name="decision" value={option.value} checked={decision === option.value} onChange={() => setDecision(option.value)} /><span>{decision === option.value ? <Check size={13} /> : null}</span><div><strong>{option.label}</strong><small>{option.detail}</small></div></label>)}</div>
          <label className="dark-field" htmlFor="decision-rationale">Coordinator rationale <textarea id="decision-rationale" rows={4} value={rationale} onChange={(event) => setRationale(event.target.value)} placeholder="Explain how the reviewed evidence supports this disposition." /></label>
          <label className="confirmation-check"><input type="checkbox" checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} /><span><Check size={12} /></span>I reviewed the matched evidence and route prerequisites.</label>
          <button className="button button-primary full-width" disabled={!canRecord || !proposal || !discovery || !reviewed || rationale.trim().length < 20 || decisionMutation.isPending || ((decision === 'APPROVE_IMPROVE' || decision === 'APPROVE_CONTINUE') && (!primary || !structuredPrerequisiteReady))} onClick={() => decisionMutation.mutate()}>{decisionMutation.isPending ? 'Recording decision...' : 'Record formal decision'} <ArrowRight size={15} /></button>
          <small className="decision-foot">{canRecord ? 'This action is audited and immutable.' : 'A signed-in Coordinator with project access is required.'}</small>
        </aside>
      </div>
    </div>
  )
}
