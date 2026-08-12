import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, CircleDashed, FileArchive, GitBranch, KeyRound, PackageCheck, PenLine } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { ApiProblem, assessProjectCompletion, claimContinuationItem, getCompletionPackage, getContinuationItems, getProjectTraceability } from '../lib/api'
import { PageHeader, ScoreRing, StatusPill } from '../components/Primitives'
import { CompletionEvidenceStudio } from '../components/CompletionEvidenceStudio'

export default function ContinuityExplorer() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const queryClient = useQueryClient()
  const project = data?.data.project
  const lineage = data?.data.lineage ?? []
  const [selected, setSelected] = useState<string>()
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [claimingItemId, setClaimingItemId] = useState('')
  const [successorObjectiveId, setSuccessorObjectiveId] = useState('')
  const [claimRationale, setClaimRationale] = useState('')
  const packageQuery = useQuery({ queryKey: ['completion-package', project?.id], queryFn: () => getCompletionPackage(project?.id ?? ''), enabled: data?.source === 'LIVE' && project?.id !== 'unavailable' })
  const opportunityQuery = useQuery({ queryKey: ['continuation-items'], queryFn: getContinuationItems, enabled: data?.source === 'LIVE' })
  const traceQuery = useQuery({ queryKey: ['traceability', project?.id], queryFn: () => getProjectTraceability(project?.id ?? ''), enabled: data?.source === 'LIVE' && project?.id !== 'unavailable' })
  const pack = packageQuery.data
  const objectives = traceQuery.data?.items.filter((item) => item.type === 'OBJECTIVE' && item.lifecycleStatus !== 'OBSOLETE') ?? []
  const roles = auth?.session.roles ?? []
  const canComplete = data?.source === 'LIVE' && auth?.session.authenticated === true && roles.includes('COORDINATOR')
  const canAuthor = data?.source === 'LIVE' && auth?.session.authenticated === true && roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const completion = useMutation({ mutationFn: () => assessProjectCompletion(project?.id ?? ''), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workspace'] }) })
  const claim = useMutation({
    mutationFn: (input: { itemId: string; objectiveId: string; rationale: string }) => claimContinuationItem(project?.id ?? '', input.itemId, input.objectiveId, input.rationale),
    onSuccess: () => {
      setMessage('Continuation item claimed without modifying predecessor history.')
      setClaimingItemId(''); setSuccessorObjectiveId(''); setClaimRationale('')
      queryClient.invalidateQueries({ queryKey: ['continuation-items'] })
    },
  })
  const error = completion.error instanceof ApiProblem ? completion.error.detail : completion.error?.message

  return (
    <div className="page continuity-page">
      <PageHeader eyebrow="Make completed work reusable" title="Continuity Explorer" description="Readiness, lineage, and opportunities below come only from the selected persisted project."
        actions={<><button className="button button-secondary" disabled={!canAuthor} onClick={() => setEvidenceOpen(true)}><PenLine size={15} /> Edit handoff evidence</button><button className="button button-primary" disabled={!canComplete || completion.isPending} onClick={() => completion.mutate()}><PackageCheck size={16} /> {completion.isPending ? 'Checking gates...' : 'Check completion gates'}</button></>}
        meta={<><StatusPill tone={lineage.length ? 'violet' : 'amber'}>{lineage.length ? 'Persisted lineage' : 'UNASSESSED'}</StatusPill><span>{lineage.length} lineage records - {opportunityQuery.data?.length ?? 0} open-work records</span></>} />
      {message ? <div className="recorded-banner" role="status"><PackageCheck size={20} /><div><strong>Record updated</strong><span>{message}</span></div></div> : null}
      {completion.isSuccess ? <div className="recorded-banner" role="status"><PackageCheck size={20} /><div><strong>{completion.data.eligible ? 'Project completed' : 'Completion remains blocked'}</strong><span>{completion.data.eligible ? 'All gates passed and one source-linked catalogue study was created.' : completion.data.blockers.join(' ')}</span></div></div> : null}
      {completion.isError ? <div className="recorded-banner" role="alert"><KeyRound size={20} /><div><strong>Completion gates were not evaluated</strong><span>{error ?? 'Reload and try again.'}</span></div></div> : null}

      <section className="lineage-field panel-dark"><div className="panel-heading on-dark"><div><span>PROJECT LINEAGE</span><h2>Predecessors and successors preserved as evidence</h2></div></div>
        {lineage.length ? <div className="lineage-track" role="list" aria-label="Project continuation lineage">{lineage.map((node, index) => <div className="lineage-step" key={node.id} role="listitem"><button onClick={() => setSelected(node.id)} className={`${selected === node.id ? 'is-selected' : ''} state-${node.state.toLowerCase()}`}><span className="lineage-year">{node.year || '—'}</span><div className="lineage-icon">{node.state === 'COMPLETE' ? <Check size={16} /> : node.state === 'ACTIVE' ? <GitBranch size={17} /> : <CircleDashed size={17} />}</div><small>{node.relation}</small><strong>{node.title}</strong><code>{node.code}</code><p>{node.inherited.length} inherited evidence items</p></button>{index < lineage.length - 1 ? <div className="lineage-connector"><span /><i>{lineage[index + 1].relation.toLowerCase()}</i></div> : null}</div>)}</div>
          : <div className="empty-state on-dark"><CircleDashed size={22} /><h3>No lineage recorded</h3><p>A new route can legitimately have no predecessor.</p></div>}
      </section>

      <div className="continuity-grid">
        <section className="readiness-panel paper-panel"><div className="section-heading compact"><div><span>HANDOFF READINESS</span><h2>Can another team continue?</h2></div><ScoreRing score={Math.round(pack?.readinessScore ?? 0)} label={pack?.status ?? 'UNASSESSED'} size="large" /></div>
          <div className="readiness-list">{pack?.criteria.length ? pack.criteria.map((item) => <div key={item.key}><div><span>{item.label}</span><strong>{Math.round(item.completion * item.weight)}/{item.weight}</strong></div><i><b style={{ width: `${item.completion * 100}%` }} /></i><small>{item.explanation}</small></div>) : <p>No structured package evidence is available.</p>}</div>
          {!pack?.codeDataRightsConfirmed ? <div className="rights-gate"><KeyRound size={18} /><div><strong>Rights confirmation blocks Ready</strong><p>Repository or data continuation rights have not been confirmed.</p></div></div> : null}
        </section>

        <aside className="package-panel paper-panel" aria-label="Continuity package readiness"><span className="panel-overline">CONTINUITY PACKAGE - {pack?.status ?? 'UNASSESSED'}</span><h2>Preserved evidence</h2>
          <div className="package-list"><div><GitBranch size={17} /><p><b>Source repository</b><small>{pack?.repositoryUrl || 'Not recorded'} {pack?.commitHash ? `- ${pack.commitHash}` : ''}</small></p><StatusPill tone={pack?.repositoryUrl ? 'teal' : 'coral'}>{pack?.repositoryUrl ? 'Recorded' : 'Missing'}</StatusPill></div><div><FileArchive size={17} /><p><b>Setup instructions</b><small>{pack?.setupInstructions || 'Not recorded'}</small></p><StatusPill tone={pack?.setupInstructions ? 'teal' : 'coral'}>{pack?.setupInstructions ? 'Recorded' : 'Missing'}</StatusPill></div><div><PackageCheck size={17} /><p><b>Known limitations</b><small>{pack?.limitations.length ?? 0} structured records</small></p><StatusPill tone={(pack?.limitations.length ?? 0) > 0 ? 'teal' : 'amber'}>{pack?.limitations.length ?? 0}</StatusPill></div></div>
          <button className="button button-secondary full-width" disabled={!canAuthor} onClick={() => setEvidenceOpen(true)}><PenLine size={15} /> Edit structured handoff</button>
        </aside>
      </div>

      <section className="continuation-items"><div className="section-heading"><div><span>OPEN WORK, PRESERVED</span><h2>Continuation opportunities</h2></div><p>A claim appends successor intent; it never edits the predecessor.</p></div>
        <div className="opportunity-grid">{opportunityQuery.data?.length ? opportunityQuery.data.map((item) => <article key={item.id} className={item.claimed ? 'is-claimed' : ''}><span>{item.type}</span><h3>{item.title}</h3><p>{item.description}</p><div><small>Source study {item.studyId.slice(0, 8)}</small><button className="text-button" disabled={!canAuthor || item.claimed || !objectives.length || claim.isPending} onClick={() => { setClaimingItemId(item.id); setSuccessorObjectiveId(''); setClaimRationale(''); claim.reset() }}>{item.claimed ? 'Claimed' : objectives.length ? 'Claim for successor' : 'Add an objective first'} <ArrowRight size={14} /></button></div>{claimingItemId === item.id && !item.claimed ? <form className="continuation-claim-form" onSubmit={(event) => { event.preventDefault(); claim.mutate({ itemId: item.id, objectiveId: successorObjectiveId, rationale: claimRationale }) }}><label><span>Successor objective</span><select required value={successorObjectiveId} onChange={(event) => setSuccessorObjectiveId(event.target.value)}><option value="">Choose the objective this work supports</option>{objectives.map((objective) => <option key={objective.id} value={objective.id}>{objective.key} · {objective.title}</option>)}</select></label><label><span>Claim rationale</span><textarea required minLength={12} value={claimRationale} onChange={(event) => setClaimRationale(event.target.value)} placeholder="Explain the evidence-based connection without changing predecessor history." /></label>{claim.isError ? <small className="form-alert">{claim.error instanceof ApiProblem ? claim.error.detail : claim.error.message}</small> : null}<div><button type="button" className="button button-ghost" onClick={() => setClaimingItemId('')}>Cancel</button><button className="button button-secondary" disabled={!successorObjectiveId || claimRationale.trim().length < 12 || claim.isPending}>{claim.isPending ? 'Recording…' : 'Record claim'}</button></div></form> : null}</article>) : <article><span>UNASSESSED</span><h3>No opportunities found</h3><p>Published predecessor limitations and unfinished work will appear here.</p></article>}</div>
      </section>
      <CompletionEvidenceStudio open={evidenceOpen} onOpenChange={setEvidenceOpen} projectId={project?.id ?? ''} source={data?.source ?? 'UNAVAILABLE'} roles={roles} onRecorded={setMessage} />
    </div>
  )
}
