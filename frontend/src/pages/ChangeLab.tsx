import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertOctagon, Check, GitCommitHorizontal, Plus, ScanLine, ShieldCheck } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import {
  addChangeOperation, ApiProblem, createChangeRequest, decideChangeRequest, getChangeContext, getChangeOperations,
  getProjectTraceability, listProjectChangeRequests, previewChangeImpact,
  type ChangeDecision, type ChangeOperationInput, type ChangeOperationType,
} from '../lib/api'
import type { TraceItemType } from '../types/domain'
import { PageHeader, RiskPill, StatusPill } from '../components/Primitives'

function value(data: FormData, key: string) {
  const field = String(data.get(key) ?? '').trim()
  return field || null
}

export default function ChangeLab() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const queryClient = useQueryClient()
  const [operationType, setOperationType] = useState<ChangeOperationType>('REVISE')
  const [selectedChangeId, setSelectedChangeId] = useState('')
  const project = data?.data.project
  const projectId = project?.id ?? ''
  const roles = auth?.session.roles ?? []
  const isLive = data?.source === 'LIVE' && projectId !== '' && projectId !== 'unavailable'
  const canAuthor = isLive && auth?.session.authenticated === true && roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const isCoordinator = isLive && auth?.session.authenticated === true && roles.includes('COORDINATOR')
  const changes = useQuery({ queryKey: ['change-requests', projectId], queryFn: () => listProjectChangeRequests(projectId), enabled: isLive })
  const selectedChange = changes.data?.find((item) => item.id === selectedChangeId)
  const context = useQuery({ queryKey: ['change-context', projectId, selectedChangeId], queryFn: () => getChangeContext(selectedChange!), enabled: Boolean(selectedChange) })
  const trace = useQuery({ queryKey: ['traceability', projectId], queryFn: () => getProjectTraceability(projectId), enabled: isLive })
  const change = context.data?.change
  const operations = useQuery({
    queryKey: ['change-operations', change?.id],
    queryFn: () => getChangeOperations(change?.id ?? ''),
    enabled: Boolean(change?.id),
  })
  const refresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['workspace'] }),
      queryClient.invalidateQueries({ queryKey: ['change-requests', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['change-context', projectId, selectedChangeId] }),
      queryClient.invalidateQueries({ queryKey: ['change-operations'] }),
      queryClient.invalidateQueries({ queryKey: ['traceability', projectId] }),
    ])
  }
  const createMutation = useMutation({
    mutationFn: (input: { title: string; rationale: string; itemId: string }) => createChangeRequest(projectId, input.title, input.rationale, input.itemId),
    onSuccess: async (created) => { setSelectedChangeId(created.id); await refresh() },
  })
  const impactMutation = useMutation({ mutationFn: () => previewChangeImpact(projectId, selectedChangeId), onSuccess: refresh })
  const operationMutation = useMutation({
    mutationFn: (input: ChangeOperationInput) => addChangeOperation(projectId, change?.id ?? '', input),
    onSuccess: refresh,
  })
  const decisionMutation = useMutation({
    mutationFn: (input: { decision: ChangeDecision; rationale: string }) => decideChangeRequest(projectId, change?.id ?? '', input.decision, input.rationale, context.data?.impact?.operationSetVersion),
    onSuccess: refresh,
  })

  const createRequest = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    createMutation.mutate({ title: String(fields.get('title')), rationale: String(fields.get('rationale')), itemId: String(fields.get('itemId')) })
  }
  const addOperation = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    operationMutation.mutate({
      type: operationType,
      targetItemId: value(fields, 'targetItemId'),
      itemType: value(fields, 'itemType') as TraceItemType | null,
      itemKey: value(fields, 'itemKey'), title: value(fields, 'title'), description: value(fields, 'description'),
      priority: value(fields, 'priority'), acceptanceCriteria: value(fields, 'acceptanceCriteria'), verificationMethod: value(fields, 'verificationMethod'),
      sourceItemId: value(fields, 'sourceItemId'), linkTargetItemId: value(fields, 'linkTargetItemId'), relationshipType: value(fields, 'relationshipType'),
      removeRelationship: fields.get('removeRelationship') === 'on', rationale: String(fields.get('rationale') ?? ''),
    })
  }
  const decide = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    const decision = ((event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value as ChangeDecision | undefined
    if (decision === 'approve' && !context.data?.impact) return
    if (decision) decisionMutation.mutate({ decision, rationale: String(fields.get('decisionRationale') ?? '') })
  }
  const actionError = [createMutation.error, impactMutation.error, operationMutation.error, decisionMutation.error]
    .find(Boolean)
  const errorDetail = actionError instanceof ApiProblem ? actionError.detail : actionError instanceof Error ? actionError.message : undefined
  const impact = context.data?.impact
  const risk = impact?.scopeRisk
  const riskAssessed = risk?.status === 'ASSESSED' && risk.score !== null
  const items = trace.data?.items ?? []
  const finalDecision = change && ['APPROVED', 'REJECTED'].includes(change.status)

  return (
    <div className="page change-page">
      <PageHeader eyebrow="Preview consequences before approval" title="Change Lab"
        description="Author typed changes against an immutable baseline, inspect their blast radius, then record a human decision."
        actions={change && canAuthor && !finalDecision ? <button className="button button-primary" disabled={impactMutation.isPending} onClick={() => impactMutation.mutate()}><ScanLine size={16} /> {impactMutation.isPending ? 'Calculating…' : 'Recalculate impact'}</button> : undefined}
        meta={<><StatusPill tone={change ? 'amber' : 'violet'}>{change?.status ?? 'UNASSESSED'}</StatusPill><span>{change?.basedOnBaselineId ? `Baseline ${change.basedOnBaselineId.slice(0, 8)}` : 'No baseline-bound request selected'}</span></>} />

      <section className="paper-panel decision-context-selector"><div className="section-heading compact"><div><span>AUTHORITATIVE REQUEST SELECTION</span><h2>Choose the persisted change request</h2></div><StatusPill tone={change ? 'teal' : 'amber'}>{change ? 'Exact request loaded' : 'Selection required'}</StatusPill></div><div className="decision-context-fields"><label><span>Project change request</span><select value={selectedChangeId} onChange={(event) => setSelectedChangeId(event.target.value)} disabled={changes.isPending || !changes.data?.length}><option value="">{changes.data?.length ? 'Select a request...' : 'No persisted request available'}</option>{changes.data?.map((item) => <option key={item.id} value={item.id}>{item.title} · {item.status} · {item.id.slice(0, 8)}</option>)}</select></label></div><p>No latest or first request is selected automatically.</p></section>

      {errorDetail ? <div className="recorded-banner" role="alert"><AlertOctagon size={20} /><div><strong>The workflow was not changed</strong><span>{errorDetail}</span></div></div> : null}
      {createMutation.isSuccess || operationMutation.isSuccess || decisionMutation.isSuccess || impactMutation.isSuccess ? <div className="recorded-banner" role="status"><Check size={20} /><div><strong>Persisted change workflow updated</strong><span>UGNAY refreshed the selected request, baseline version, impact, and project evidence.</span></div></div> : null}

      {!change ? <section className="paper-panel change-authoring-panel">
        <div className="section-heading compact"><div><span>CONTROLLED CHANGE</span><h2>Create a baseline-bound request</h2></div><p>Choose the first implicated artifact. Typed operations are added after the request exists.</p></div>
        {!trace.data?.baselineId ? <div className="permission-note">Approve a traceability baseline before proposing a controlled change.</div> : canAuthor ? <form className="change-form" onSubmit={createRequest}>
          <label>Request title<input name="title" required minLength={5} placeholder="Revise offline notification behavior" /></label>
          <label>First implicated artifact<select name="itemId" required defaultValue=""><option value="" disabled>Select an approved artifact</option>{items.map((item) => <option key={item.id} value={item.id}>{item.key} — {item.title}</option>)}</select></label>
          <label className="form-span">Academic rationale<textarea name="rationale" required minLength={20} placeholder="Explain the evidence, expected benefit, and why this belongs inside the approved project boundary." /></label>
          <button className="button button-primary" type="submit" disabled={createMutation.isPending}><Plus size={16} /> Create request</button>
        </form> : <div className="permission-note">A student, adviser, or coordinator project member may create a change request.</div>}
      </section> : <>
        <section className="change-brief paper-panel"><div className="change-number">{change.id.slice(0, 2).toUpperCase()}</div><div><span>PERSISTED CHANGE</span><h2>{change.title}</h2><p>{change.rationale}</p></div><div className="change-owner"><span>FRESHNESS</span><strong>{new Date(change.createdAt).toLocaleDateString('en-PH')}</strong><small>{change.changedItemIds.length} explicit targets · {operations.data?.length ?? 0} typed operations</small></div></section>

        {!finalDecision && canAuthor ? <section className="paper-panel change-authoring-panel">
          <div className="section-heading compact"><div><span>OPERATION SET</span><h2>Describe the exact baseline delta</h2></div><p>Approval applies these operations transactionally as baseline N+1.</p></div>
          <div className="operation-ledger">{operations.data?.map((operation) => <article key={operation.id}><span>{operation.order}</span><div><strong>{operation.type}</strong><p>{operation.itemKey || operation.targetItemId?.slice(0, 8) || `${operation.sourceItemId?.slice(0, 8)} → ${operation.linkTargetItemId?.slice(0, 8)}`}</p><small>{operation.rationale}</small></div></article>)}</div>
          <form className="change-form" onSubmit={addOperation}>
            <label>Operation<select value={operationType} onChange={(event) => setOperationType(event.target.value as ChangeOperationType)}><option>ADD</option><option>REVISE</option><option>RETIRE</option><option>RELINK</option></select></label>
            {operationType === 'ADD' ? <><label>Artifact type<select name="itemType" required>{['PROBLEM', 'OBJECTIVE', 'REQUIREMENT', 'FEATURE', 'TEST_CASE', 'OUTPUT'].map((type) => <option key={type}>{type}</option>)}</select></label><label>Artifact key<input name="itemKey" required placeholder="REQ-07" /></label><label>Priority<select name="priority"><option value="">Not applicable</option><option>MUST</option><option>SHOULD</option><option>COULD</option></select></label></> : null}
            {operationType === 'REVISE' || operationType === 'RETIRE' ? <label>Target artifact<select name="targetItemId" required>{items.map((item) => <option key={item.id} value={item.id}>{item.key} — {item.title}</option>)}</select></label> : null}
            {operationType === 'ADD' || operationType === 'REVISE' ? <><label>Title<input name="title" required={operationType === 'ADD'} /></label><label className="form-span">Description<textarea name="description" required={operationType === 'ADD'} /></label><label>Acceptance criteria<input name="acceptanceCriteria" /></label><label>Verification method<input name="verificationMethod" /></label></> : null}
            {operationType === 'RELINK' ? <><label>Source<select name="sourceItemId" required>{items.map((item) => <option key={item.id} value={item.id}>{item.key}</option>)}</select></label><label>Target<select name="linkTargetItemId" required>{items.map((item) => <option key={item.id} value={item.id}>{item.key}</option>)}</select></label><label>Typed relationship<select name="relationshipType"><option>MOTIVATES</option><option>DECOMPOSES_TO</option><option>REALIZED_BY</option><option>VERIFIED_BY</option><option>PRODUCES</option></select></label><label className="check-label"><input name="removeRelationship" type="checkbox" /> Retire this link</label></> : null}
            <label className="form-span">Operation rationale<textarea name="rationale" required minLength={10} /></label>
            <button className="button button-secondary" type="submit" disabled={operationMutation.isPending}><Plus size={15} /> Add typed operation</button>
          </form>
        </section> : null}

        <div className="change-grid"><section className="impact-map panel-dark"><div className="panel-heading on-dark"><div><span>BLAST RADIUS {impact ? `· OPERATION SET v${impact.operationSetVersion}` : ''}</span><h2>{impact ? `${impact.impactedArtifacts.length} artifacts need attention` : 'Run impact analysis to assess this request'}</h2></div><StatusPill tone={impact ? 'coral' : 'amber'}>{impact ? `${impact.impactedArtifacts.filter((item) => item.evidenceBecomesStale).length} evidence paths stale` : 'UNASSESSED'}</StatusPill></div><div className="impact-origin"><span>CHANGE</span><strong>{change.title}</strong><small>{change.boundaryFlags.length ? change.boundaryFlags.join(', ') : 'No unresolved boundary flags recorded'}</small></div><div className="impact-columns">{(['OBJECTIVE', 'REQUIREMENT', 'FEATURE', 'TEST_CASE', 'OUTPUT'] as const).map((type, column) => <div key={type}><span>{type.replace('_', ' ')}</span>{impact?.impactedArtifacts.filter((item) => item.itemType === type).map((item) => <div key={item.itemId} className="impact-node"><i>{column + 1}</i><p><b>{item.itemKey}</b>{item.title}<small>{item.hopCount} hops — {item.reason}</small></p></div>)}</div>)}</div><p className="impact-note"><GitCommitHorizontal size={15} /> Cycle-safe traversal; each persisted artifact appears once.</p></section>
          <aside className="scope-card paper-panel" aria-label="Scope risk assessment"><span className="panel-overline">SCOPE RISK · {risk?.status ?? 'UNASSESSED'}</span><div className="scope-score"><strong>{riskAssessed ? risk.score : '—'}</strong><div>{riskAssessed && risk.band ? <RiskPill level={risk.band as 'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL'} /> : <StatusPill tone="violet">{risk?.status ?? 'UNASSESSED'}</StatusPill>}<p>{riskAssessed ? risk.explanations.join(' ') || 'No scope-risk explanation was recorded.' : 'No assessed scope-risk value or component score is available.'}</p></div></div><div className="scope-breakdown">{[['Governance', risk?.governance, 35], ['Alignment', risk?.alignment, 25], ['Controlled growth', risk?.controlledGrowth, 20], ['Boundary', risk?.boundary, 20]].map(([label, score, max]) => { const assessed = riskAssessed && typeof score === 'number'; return <div key={String(label)}><span>{label}</span><i>{assessed ? <b style={{ width: `${Number(score) / Number(max) * 100}%` }} /> : null}</i><strong>{assessed ? `${score}/${max}` : '—'}</strong></div> })}</div>{impact && !impact.baselineCurrent ? <div className="critical-floor"><AlertOctagon size={17} /><p><b>Recalculation required</b><br />This request is not based on the current baseline and cannot be approved.</p></div> : null}</aside></div>

        {!finalDecision && isCoordinator ? <section className="paper-panel change-decision-panel"><ShieldCheck size={21} /><div><span>COORDINATOR DECISION</span><h2>Record the controlled-change disposition</h2><p>{impact ? `Approval is bound to operation set v${impact.operationSetVersion} · ${impact.operationSetSha256.slice(0, 12)}…` : 'Run a current impact preview before approval. Return and Reject remain available.'}</p></div><form onSubmit={decide}><textarea name="decisionRationale" required minLength={20} placeholder="Record the academic and scope-control basis for this decision." /><div><button type="submit" value="return-for-revision" className="button button-secondary">Return</button><button type="submit" value="reject" className="button button-secondary">Reject</button><button type="submit" value="approve" className="button button-primary" disabled={!impact}>Approve baseline N+1</button></div></form></section> : null}
      </>}
    </div>
  )
}
