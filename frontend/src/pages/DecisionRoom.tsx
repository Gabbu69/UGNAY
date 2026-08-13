import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, ClipboardCheck, GitCompareArrows, ShieldCheck, Users } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import {
  ApiProblem, getAuthorizedStudyDetail, getDecisionContext, getRouteEvidence, listDecisionProposals, listDiscoveryRuns,
  recordAcademicDecision, recordAdviserRecommendation, type AdviserRecommendationRecord,
} from '../lib/api'
import { DimensionBar, ExplainabilityNote, PageHeader, StatusPill } from '../components/Primitives'
import RouteEvidenceAuthoring from '../components/RouteEvidenceAuthoring'
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
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedProposalId = searchParams.get('proposalId') ?? ''
  const selectedRunId = searchParams.get('runId') ?? ''
  const selectedCandidateId = searchParams.get('candidateId') ?? ''
  const proposals = useQuery({ queryKey: ['decision-proposals'], queryFn: listDecisionProposals, enabled: data?.source === 'LIVE' })
  const runs = useQuery({ queryKey: ['decision-runs'], queryFn: listDiscoveryRuns, enabled: data?.source === 'LIVE' })
  const proposalRuns = useMemo(() => (runs.data ?? []).filter((run) => run.proposalId === selectedProposalId), [runs.data, selectedProposalId])
  const context = useQuery({
    queryKey: ['decision-context', selectedProposalId, selectedRunId],
    queryFn: () => getDecisionContext(selectedProposalId, selectedRunId),
    enabled: data?.source === 'LIVE' && Boolean(selectedProposalId && selectedRunId),
  })
  const proposal = context.data?.proposal
  const discovery = context.data?.discovery
  const studiesById = useMemo(() => new Map((context.data?.candidateStudies ?? []).map((study) => [study.id, study])), [context.data?.candidateStudies])
  const candidateScore = discovery?.candidates.find((candidate) => candidate.studyId === selectedCandidateId)
  const primary = candidateScore ? studiesById.get(selectedCandidateId) : undefined
  const candidateDetail = useQuery({
    queryKey: ['authorized-study-detail', selectedCandidateId],
    queryFn: () => getAuthorizedStudyDetail(selectedCandidateId),
    enabled: data?.source === 'LIVE' && auth?.session.authenticated === true && Boolean(candidateScore),
  })
  const [decision, setDecision] = useState<DecisionDisposition>('RETURN_FOR_REVISION')
  const [rationale, setRationale] = useState('')
  const [reviewed, setReviewed] = useState(false)
  const canRecord = data?.source === 'LIVE' && auth?.session.authenticated === true && auth.session.roles.includes('COORDINATOR')
  const canRecommend = data?.source === 'LIVE' && auth?.session.authenticated === true && auth.session.roles.includes('ADVISER')
  const canAuthorRoute = data?.source === 'LIVE' && auth?.session.authenticated === true
    && auth.session.roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const routeEvidence = useQuery({ queryKey: ['route-evidence', proposal?.id, selectedCandidateId], queryFn: () => getRouteEvidence(proposal?.id ?? '', selectedCandidateId), enabled: Boolean(data?.source === 'LIVE' && proposal?.id && candidateScore && auth?.session.authenticated) })
  const [adviserRoute, setAdviserRoute] = useState<AdviserRecommendationRecord['recommendation']>('REVIEW_REQUIRED')
  const [adviserRationale, setAdviserRationale] = useState('')
  const adviserMutation = useMutation({
    mutationFn: () => recordAdviserRecommendation(proposal?.id ?? '', discovery?.id ?? '', adviserRoute, adviserRationale),
    onSuccess: () => {
      setAdviserRationale('')
      queryClient.invalidateQueries({ queryKey: ['decision-context', selectedProposalId, selectedRunId] })
    },
  })
  const decisionMutation = useMutation({
    mutationFn: () => recordAcademicDecision({ proposalId: selectedProposalId, discoveryRunId: selectedRunId, disposition: decision, rationale, primaryPredecessorId: selectedCandidateId || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['workspace'] })
      queryClient.invalidateQueries({ queryKey: ['decision-context', selectedProposalId, selectedRunId] })
      queryClient.invalidateQueries({ queryKey: ['decision-proposals'] })
    },
  })
  const actionError = decisionMutation.error instanceof ApiProblem ? decisionMutation.error.detail : decisionMutation.error?.message
  const discoveryAssessed = discovery?.assessmentStatus === 'ASSESSED'
  const recommendation = discoveryAssessed ? discovery.recommendation?.replaceAll('_', ' ') ?? 'UNASSESSED' : 'UNASSESSED'
  const assessedConfidence = discoveryAssessed ? discovery?.confidence : null
  const route = routeEvidence.data
  const recommendations = context.data?.adviserRecommendations ?? []
  const requiresCandidate = decision === 'APPROVE_CONTINUE' || decision === 'APPROVE_IMPROVE' || decision === 'CLOSE_AS_DUPLICATE'
  const continuationReady = route?.continuationState === 'ASSESSED'
    && route.continuationCoverage != null && route.continuationCoverage >= 60
    && route.codeAccess === true && route.dataAccess === true
  const improvementReady = route?.improvementState === 'ASSESSED'
    && route.improvementClaimCount != null && route.improvementClaimCount > 0
  const structuredPrerequisiteReady = decision === 'APPROVE_CONTINUE' ? continuationReady
    : decision === 'APPROVE_IMPROVE' ? improvementReady : true
  const selectProposal = (proposalId: string) => {
    const next = new URLSearchParams()
    if (proposalId) next.set('proposalId', proposalId)
    setSearchParams(next)
  }
  const selectRun = (runId: string) => {
    const next = new URLSearchParams()
    if (selectedProposalId) next.set('proposalId', selectedProposalId)
    if (runId) next.set('runId', runId)
    setSearchParams(next)
  }
  const selectCandidate = (candidateId: string) => {
    const next = new URLSearchParams(searchParams)
    if (candidateId) next.set('candidateId', candidateId)
    else next.delete('candidateId')
    setSearchParams(next)
  }

  return (
    <div className="page decision-page">
      <PageHeader eyebrow="Human decision, machine-explained evidence" title="Decision Room"
        description="Compare the selected persisted proposal and frozen discovery run before recording an accountable academic route."
        actions={<StatusPill tone="violet">Coordinator-controlled record</StatusPill>}
        meta={<><StatusPill tone={discovery ? 'violet' : 'amber'}>{discovery ? `Run ${discovery.id.slice(0, 8)}` : 'UNASSESSED'}</StatusPill><span>Algorithm: {discovery?.algorithmVersion ?? 'Unavailable'}</span></>} />

      <section className="decision-context-selector paper-panel" aria-labelledby="decision-context-heading">
        <div className="section-heading compact"><div><span>AUTHORITATIVE RECORD SELECTION</span><h2 id="decision-context-heading">Choose the proposal and frozen run</h2></div><StatusPill tone={proposal && discovery ? 'teal' : 'amber'}>{proposal && discovery ? 'Exact context loaded' : 'Selection required'}</StatusPill></div>
        <div className="decision-context-fields">
          <label><span>Persisted proposal</span><select value={selectedProposalId} onChange={(event) => selectProposal(event.target.value)} disabled={proposals.isPending}><option value="">Select a proposal...</option>{proposals.data?.map((item) => <option key={item.id} value={item.id}>{item.title} · {item.id.slice(0, 8)}</option>)}</select></label>
          <label><span>Frozen discovery run</span><select value={selectedRunId} onChange={(event) => selectRun(event.target.value)} disabled={!selectedProposalId || runs.isPending}><option value="">Select a run for this proposal...</option>{proposalRuns.map((run) => <option key={run.id} value={run.id}>{run.id.slice(0, 8)} · {run.assessmentStatus}</option>)}</select></label>
          <label><span>Candidate for comparison</span><select value={selectedCandidateId} onChange={(event) => selectCandidate(event.target.value)} disabled={!discovery?.candidates.length}><option value="">Select a candidate...</option>{discovery?.candidates.map((candidate) => <option key={candidate.studyId} value={candidate.studyId}>{studiesById.get(candidate.studyId)?.title ?? `Authorized candidate ${candidate.studyId.slice(0, 8)}`}</option>)}</select></label>
        </div>
        <p>No first-record fallback is used. Changing the proposal clears the run and candidate selections.</p>
      </section>
      {(proposals.isError || runs.isError || context.isError) ? <div className="recorded-banner" role="alert"><ShieldCheck size={20} /><div><strong>Decision evidence unavailable</strong><span>{context.error instanceof ApiProblem ? context.error.detail : 'Reload the accessible proposal and run lists, then select the exact records again.'}</span></div></div> : null}

      {decisionMutation.isSuccess ? <div className="recorded-banner" role="status"><ShieldCheck size={20} /><div><strong>Formal route recorded</strong><span>The immutable coordinator decision is now part of the evidence trail.</span></div></div> : null}
      {context.data?.decision ? <div className="recorded-banner" role="status"><ShieldCheck size={20} /><div><strong>This proposal already has a formal route</strong><span>{context.data.decision.disposition?.replaceAll('_', ' ') ?? 'Recorded decision'} is immutable. Select another undecided proposal and frozen run to continue.</span></div></div> : null}
      {decisionMutation.isError ? <div className="recorded-banner" role="alert"><ShieldCheck size={20} /><div><strong>Decision was not recorded</strong><span>{actionError ?? 'Reload the live evidence and try again.'}</span></div></div> : null}

      <section className="recommendation-ribbon">
        <div><span>SYSTEM RECOMMENDATION</span><h2>{recommendation}</h2><p>{discovery?.explanation ?? 'No frozen discovery run is available; UGNAY cannot recommend a route.'}</p></div>
        <div className="confidence-stamp"><strong>{assessedConfidence == null ? '—' : Math.round(assessedConfidence)}</strong><span>{assessedConfidence == null ? 'UNASSESSED' : 'evidence'}<br />confidence</span></div>
        <div className="human-control"><Users size={18} /><p><b>Decision boundary</b><br />UGNAY cannot approve, reject, or declare duplication.</p></div>
      </section>

      {(recommendations.length || canRecommend) && proposal && discovery ? <section className="adviser-recommendation-panel paper-panel">
        <div className="section-heading compact"><div><span>INDEPENDENT HUMAN REVIEW</span><h2>Adviser recommendations</h2></div><StatusPill tone="neutral">Does not decide the route</StatusPill></div>
        {recommendations.length ? <div className="adviser-recommendation-list">{recommendations.map((item) => <article key={item.id}><ClipboardCheck size={16} /><div><span>{item.recommendation.replaceAll('_', ' ')}</span><strong>{item.rationale}</strong><small>{item.adviser} · {new Date(item.recordedAt).toLocaleString()}</small></div></article>)}</div> : <p className="adviser-empty">No adviser recommendation has been recorded against this frozen run.</p>}
        {canRecommend && proposal && discovery ? <form onSubmit={(event) => { event.preventDefault(); adviserMutation.mutate() }}><label><span>Recommended route</span><select value={adviserRoute} onChange={(event) => setAdviserRoute(event.target.value as AdviserRecommendationRecord['recommendation'])}><option value="REVIEW_REQUIRED">Review required</option><option value="NEW">New</option><option value="IMPROVE">Improve</option><option value="CONTINUE">Continue</option><option value="POSSIBLE_DUPLICATE">Possible duplicate</option></select></label><label><span>Adviser rationale</span><textarea required minLength={20} value={adviserRationale} onChange={(event) => setAdviserRationale(event.target.value)} placeholder="Explain how the frozen evidence supports this recommendation." /></label><button className="button button-secondary" disabled={adviserMutation.isPending || adviserRationale.trim().length < 20}>{adviserMutation.isPending ? 'Recording…' : 'Record immutable recommendation'}</button></form> : null}
        {adviserMutation.isError ? <p className="form-alert" role="alert">{adviserMutation.error instanceof ApiProblem ? adviserMutation.error.detail : adviserMutation.error.message}</p> : null}
      </section> : null}

      {proposal && candidateScore ? <RouteEvidenceAuthoring
        key={`${proposal.id}:${selectedCandidateId}`}
        proposalId={proposal.id}
        predecessorStudyId={selectedCandidateId}
        objectives={context.data?.proposalObjectives ?? []}
        study={candidateDetail.data}
        studyLoading={candidateDetail.isPending}
        studyUnavailable={candidateDetail.isError}
        canAuthor={canAuthorRoute}
      /> : null}

      <div className="comparison-grid">
        <section className="comparison-card proposed"><div className="compare-label"><span>A</span>SELECTED PROPOSAL</div><h2>{proposal?.title ?? 'Selection required'}</h2><p>{proposal?.problemStatement ?? 'Choose a persisted proposal and its exact frozen discovery run above.'}</p><div className="compare-facts"><span>{proposal?.siteContext ?? 'Context UNASSESSED'}</span><span>{proposal?.stakeholder ?? 'Stakeholder UNASSESSED'}</span><span>{proposal ? `${proposal.objectives.length} objectives` : 'Objectives UNASSESSED'}</span></div></section>
        <div className="compare-axis" aria-hidden="true"><GitCompareArrows size={18} /><span /></div>
        <section className="comparison-card prior"><div className="compare-label"><span>B</span>SELECTED CANDIDATE {primary?.code ?? selectedCandidateId.slice(0, 8)}</div><h2>{primary?.title ?? (selectedCandidateId ? 'Catalogue detail unavailable' : 'Selection required')}</h2><p>{primary?.abstract ?? (selectedCandidateId ? 'The run identifies this candidate, but no authorized catalogue detail was returned to this workspace.' : 'Select one candidate from the exact frozen run above.')}</p><div className="compare-facts"><span>{primary ? `${primary.status} - ${primary.year}` : 'Status UNAVAILABLE'}</span><span>{primary ? `${primary.authors.length} recorded authors` : 'Authors UNAVAILABLE'}</span><span>{primary ? primary.restricted ? 'Protected evidence' : 'Accessible evidence' : 'Visibility UNAVAILABLE'}</span></div></section>
      </div>

      <div className="decision-body">
        <section className="evidence-comparison paper-panel"><div className="section-heading compact"><div><span>FIELD-BY-FIELD EVIDENCE</span><h2>Why these records are related</h2></div><StatusPill tone={discovery?.assessmentStatus === 'ASSESSED' ? 'teal' : 'amber'}>{discovery?.assessmentStatus ?? 'UNASSESSED'}</StatusPill></div>
          {candidateScore ? <div className="evidence-dimensions"><DimensionBar label="Problem similarity" value={candidateScore.problemScore} emphasis detail="Problem framing, stakeholder, and site evidence" /><DimensionBar label="Objective alignment" value={candidateScore.objectiveScore} detail="One-to-one objective matching" /><DimensionBar label="Solution approach" value={candidateScore.solutionScore} detail="Features, method, data, technology, and users" /><DimensionBar label="Comparable evidence" value={candidateScore.confidence} detail="Weighted fields that were available for comparison" /></div> : <p>No candidate dimensions are available.</p>}
          <ExplainabilityNote>Each prior objective can match only one proposed objective. Restricted passages may affect scoring but are never sent to unauthorized clients.</ExplainabilityNote>
        </section>

        <aside className="decision-panel panel-dark" aria-label="Formal academic decision record"><span className="panel-overline">FORMAL ROUTE</span><h2>Record the academic decision</h2><p>Every route references the frozen run above. Improve and Continue remain blocked until their structured evidence is complete.</p>
          {(decision === 'APPROVE_IMPROVE' || decision === 'APPROVE_CONTINUE') ? <div className="route-prerequisite-card"><strong>{decision === 'APPROVE_CONTINUE' ? 'Continue prerequisites' : 'Improve prerequisites'}</strong>
            {routeEvidence.isError ? <p>Route evidence is UNAVAILABLE. No prerequisite facts are being inferred.</p>
              : routeEvidence.isPending ? <p>Loading the selected route evidence...</p>
                : !route ? <p>Route evidence is UNASSESSED for the selected candidate.</p>
                  : decision === 'APPROVE_CONTINUE' ? route.continuationState !== 'ASSESSED' ? <p>Continuation evidence is {route.continuationState}. Coverage and access facts are not numeric or confirmed.</p> : <ul>
                    <li className={route.continuationCoverage != null && route.continuationCoverage >= 60 ? 'is-ready' : ''}><Check size={11} />Objective coverage {route.continuationCoverage == null ? 'UNASSESSED' : `${route.continuationCoverage.toFixed(0)}% / 60%`}</li>
                    <li className={route.codeAccess === true ? 'is-ready' : ''}><Check size={11} />Code access {route.codeAccess == null ? 'UNASSESSED' : route.codeAccess ? 'confirmed' : 'not confirmed'}</li>
                    <li className={route.dataAccess === true ? 'is-ready' : ''}><Check size={11} />Data access {route.dataAccess == null ? 'UNASSESSED' : route.dataAccess ? 'confirmed' : 'not confirmed'}</li>
                  </ul> : route.improvementState !== 'ASSESSED' ? <p>Improvement evidence is {route.improvementState}. Claim totals and readiness are not inferred.</p> : <ul>
                    <li className={improvementReady ? 'is-ready' : ''}><Check size={11} />Measured improvement evidence {improvementReady ? 'ready' : 'incomplete'}</li>
                    <li className={route.improvementClaimCount != null && route.improvementClaimCount > 0 ? 'is-ready' : ''}><Check size={11} />Baseline, target, and method {route.improvementClaimCount == null ? 'UNASSESSED' : `(${route.improvementClaimCount})`}</li>
                  </ul>}
            <small>{structuredPrerequisiteReady ? 'Structured evidence is ready for coordinator review.' : 'The backend will reject this route until every prerequisite is assessed and complete.'}</small></div> : null}
          <div className="decision-options">{decisionOptions.map((option) => <label key={option.value} className={decision === option.value ? 'is-selected' : ''}><input type="radio" name="decision" value={option.value} checked={decision === option.value} onChange={() => setDecision(option.value)} /><span>{decision === option.value ? <Check size={13} /> : null}</span><div><strong>{option.label}</strong><small>{option.detail}</small></div></label>)}</div>
          <label className="dark-field" htmlFor="decision-rationale">Coordinator rationale <textarea id="decision-rationale" rows={4} value={rationale} onChange={(event) => setRationale(event.target.value)} placeholder="Explain how the reviewed evidence supports this disposition." /></label>
          <label className="confirmation-check"><input type="checkbox" checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} /><span><Check size={12} /></span>I reviewed the selected proposal, frozen run, candidate evidence where required, and route prerequisites.</label>
          <button className="button button-primary full-width" disabled={!canRecord || !proposal || !discovery || Boolean(context.data?.decision) || !reviewed || rationale.trim().length < 20 || decisionMutation.isPending || (requiresCandidate && !selectedCandidateId) || ((decision === 'APPROVE_IMPROVE' || decision === 'APPROVE_CONTINUE') && !structuredPrerequisiteReady)} onClick={() => decisionMutation.mutate()}>{decisionMutation.isPending ? 'Recording decision...' : 'Record formal decision'} <ArrowRight size={15} /></button>
          <small className="decision-foot">{canRecord ? 'This action is audited and immutable.' : 'A signed-in Coordinator with project access is required.'}</small>
        </aside>
      </div>
    </div>
  )
}
