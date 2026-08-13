import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ArrowRight, CheckCircle2, ChevronDown, Clock3, Filter, LoaderCircle, MessageSquareReply, Send, ShieldCheck } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { healthLabel, overallHealth } from '../lib/analysis'
import {
  ApiProblem,
  getProjectReviewQueue,
  requestReviewRevision,
  submitReviewRevisionResponse,
  type ProjectReviewRecord,
  type ReviewRevisionInput,
} from '../lib/api'
import { PageHeader, RiskPill, ScoreRing, StatusPill } from '../components/Primitives'
import type { RiskLevel } from '../types/domain'

function reviewDestination(label: string, projectId: string) {
  const root = projectId && projectId !== 'unavailable' ? `/projects/${projectId}` : ''
  const value = label.toLowerCase()
  if (value.includes('change') || value.includes('scope')) return `${root}/changes`
  if (value.includes('discovery') || value.includes('route')) return '/decision'
  if (value.includes('completion') || value.includes('continuity')) return `${root}/continuity`
  return `${root}/alignment`
}

function riskFor(severity: ProjectReviewRecord['severity']): RiskLevel {
  if (severity === 'CRITICAL') return 'CRITICAL'
  if (severity === 'HIGH') return 'HIGH'
  if (severity === 'WARNING') return 'MODERATE'
  return 'LOW'
}

function dateLabel(value: string, includeTime = false) {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return 'Unavailable'
  return includeTime ? parsed.toLocaleString() : parsed.toLocaleDateString()
}

function errorMessage(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  return error instanceof Error ? error.message : 'The review history could not be updated.'
}

function ReviewCard({ item, index, projectId, roles }: { item: ProjectReviewRecord; index: number; projectId: string; roles: string[] }) {
  const queryClient = useQueryClient()
  const [expanded, setExpanded] = useState(false)
  const [mode, setMode] = useState<'request' | 'response' | null>(null)
  const [message, setMessage] = useState('')
  const [evidenceLocation, setEvidenceLocation] = useState('')
  const [confirmed, setConfirmed] = useState('')
  const hasRequestAuthority = roles.includes(item.requiredRole) || roles.includes('COORDINATOR')
  const hasResponseAuthority = roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const canRequestRevision = hasRequestAuthority && item.status !== 'REVISION_REQUESTED'
  const canSubmitResponse = hasResponseAuthority && item.status === 'REVISION_REQUESTED'
  const mutation = useMutation({
    mutationFn: ({ kind, input }: { kind: 'request' | 'response'; input: ReviewRevisionInput }) => kind === 'request'
      ? requestReviewRevision(projectId, item.id, input)
      : submitReviewRevisionResponse(projectId, item.id, input),
    onSuccess: async (result, variables) => {
      queryClient.setQueryData<ProjectReviewRecord[]>(['review-queue', projectId], (current) => current?.map((review) => review.id === result.review.id ? result.review : review))
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-queue', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['workspace'] }),
        queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
      ])
      setMessage('')
      setEvidenceLocation('')
      setMode(null)
      setExpanded(true)
      setConfirmed(variables.kind === 'request' ? 'Revision request appended to the review history.' : 'Revision response appended to the review history.')
    },
  })

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!mode) return
    setConfirmed('')
    mutation.mutate({
      kind: mode,
      input: {
        message: message.trim(),
        ...(evidenceLocation.trim() ? { evidenceLocation: evidenceLocation.trim() } : {}),
      },
    })
  }

  return (
    <article className="queue-item">
      <div className="queue-item-summary">
        <span className="queue-index">{String(index + 1).padStart(2, '0')}</span>
        <div className="queue-copy"><small>{item.type.replaceAll('_', ' ')} · {item.requiredRole.replaceAll('_', ' ')}</small><h3>{item.title}</h3><p>{item.reason}</p></div>
        <div className="queue-due"><Clock3 size={14} /><span>{dateLabel(item.dueAt)}</span></div>
        <div className="queue-state"><RiskPill level={riskFor(item.severity)} /><StatusPill tone={item.status === 'RESOLVED' ? 'teal' : 'amber'}>{item.status || 'UNASSESSED'}</StatusPill></div>
        <div className="queue-links"><Link className="button button-secondary" to={reviewDestination(item.type, projectId)}>Open evidence <ArrowRight size={14} /></Link><button className="button button-ghost" aria-expanded={expanded} aria-controls={`review-history-${item.id}`} onClick={() => setExpanded((current) => !current)}>{item.history.length} event{item.history.length === 1 ? '' : 's'} <ChevronDown className={expanded ? 'is-rotated' : ''} size={15} /></button></div>
      </div>

      {expanded ? <div id={`review-history-${item.id}`} className="review-history-panel">
        <div className="review-history-head"><div><span>APPEND-ONLY HISTORY</span><strong>Recorded review conversation</strong></div><small>Entries retain their original actor and time.</small></div>
        {item.history.length ? <ol>{item.history.map((event) => <li key={event.id}><span><MessageSquareReply size={15} /></span><div><header><strong>{event.eventType.replaceAll('_', ' ')}</strong><time dateTime={event.createdAt}>{dateLabel(event.createdAt, true)}</time></header><p>{event.message}</p>{event.evidenceLocation ? <small>Evidence: {event.evidenceLocation}</small> : null}<em>{event.actorEmail || 'Actor unavailable'}</em></div></li>)}</ol> : <p className="review-history-empty">UNASSESSED · No revision events have been appended.</p>}

        <div className="review-action-bar">
          <button className="button button-secondary" disabled={!canRequestRevision || mutation.isPending} title={canRequestRevision ? undefined : item.status === 'REVISION_REQUESTED' ? 'A revision request is already awaiting a response' : `Requires ${item.requiredRole.replaceAll('_', ' ')} review authority`} onClick={() => { setMode('request'); setConfirmed(''); mutation.reset() }}><MessageSquareReply size={15} /> Request revision</button>
          <button className="button button-secondary" disabled={!canSubmitResponse || mutation.isPending} title={canSubmitResponse ? undefined : item.status !== 'REVISION_REQUESTED' ? 'A response requires an active revision request' : 'Requires a student, adviser, or coordinator project role'} onClick={() => { setMode('response'); setConfirmed(''); mutation.reset() }}><Send size={15} /> Submit response</button>
        </div>
        {!canRequestRevision && !canSubmitResponse ? <p className="review-permission-note">No revision action is available for your granted roles and this review's current state.</p> : null}

        {mode ? <form className="review-revision-form" onSubmit={submit}>
          <div><span>{mode === 'request' ? 'REVISION REQUEST' : 'REVISION RESPONSE'}</span><strong>{mode === 'request' ? 'Describe what must change' : 'Explain how the revision was addressed'}</strong></div>
          <label><span>Message</span><textarea value={message} onChange={(event) => setMessage(event.target.value)} minLength={20} maxLength={2000} rows={4} required placeholder={mode === 'request' ? 'Identify the exact evidence gap and expected revision.' : 'Describe the revision and the evidence that now supports it.'} /></label>
          <label><span>Evidence location <small>Optional</small></span><input value={evidenceLocation} onChange={(event) => setEvidenceLocation(event.target.value)} maxLength={1000} placeholder="Document ID, repository path, URL, or output location" /></label>
          {mutation.isError ? <p className="form-alert" role="alert">{errorMessage(mutation.error)}</p> : null}
          <div><button type="button" className="button button-ghost" disabled={mutation.isPending} onClick={() => { setMode(null); mutation.reset() }}>Cancel</button><button className="button button-primary" disabled={mutation.isPending || message.trim().length < 20 || message.trim().length > 2000}>{mutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <Send size={15} />}{mutation.isPending ? 'Appending...' : 'Append to history'}</button></div>
        </form> : null}
        {confirmed ? <div className="review-action-confirmed" role="status"><CheckCircle2 size={17} /><span>{confirmed}</span></div> : null}
      </div> : null}
    </article>
  )
}

export default function ReviewQueue() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const workspace = data?.data
  const health = workspace?.health ?? []
  const project = workspace?.project
  const projectId = project?.id ?? ''
  const queueQuery = useQuery({ queryKey: ['review-queue', projectId], queryFn: () => getProjectReviewQueue(projectId), enabled: data?.source === 'LIVE' && Boolean(projectId && projectId !== 'unavailable') })
  const queue = queueQuery.data ?? []
  const [filter, setFilter] = useState<RiskLevel | 'ALL'>('ALL')
  const visible = queue.filter((item) => filter === 'ALL' || riskFor(item.severity) === filter)
  const assessedHealth = health.filter((item) => item.state === 'ASSESSED' && item.score !== null)
  const overall = overallHealth(health)
  const roles = auth?.session.roles ?? []
  const roleSummary = roles.length ? roles.map((role) => role.toLowerCase().replaceAll('_', ' ')).join(', ') : 'No granted review role'
  const eventCount = queue.reduce((count, item) => count + item.history.length, 0)

  return (
    <div className="page review-page">
      <PageHeader eyebrow="Academic actions, not task management" title="Review Queue"
        description="Only persisted review records and append-only revision history for the selected project appear here."
        actions={<StatusPill tone="violet"><Filter size={13} /> Granted roles only</StatusPill>}
        meta={<><StatusPill tone={queueQuery.isError ? 'coral' : queue.length ? 'amber' : 'violet'}>{queueQuery.isPending ? 'Loading reviews' : queueQuery.isError ? 'UNAVAILABLE' : `${queue.length} persisted reviews`}</StatusPill><span>Source: {data?.source ?? 'UNAVAILABLE'}</span></>} />

      {queueQuery.isPending ? <div className="authoring-loading review-loading" role="status"><LoaderCircle className="is-spinning" size={18} />Loading the selected project's review inbox...</div> : null}
      {queueQuery.isError ? <div className="recorded-banner" role="alert"><ShieldCheck size={20} /><div><strong>Review inbox unavailable</strong><span>No cached, seeded, or global review items are being substituted. Reconnect and reload this project.</span></div></div> : null}

      <div className="review-overview">
        <section className="review-health paper-panel">
          <div className="health-summary"><ScoreRing score={overall} label={healthLabel(overall)} size="large" /><div><span>CURRENT PROJECT HEALTH</span><h2>{project?.title ?? 'No project selected'}</h2><p>Overall status follows the weakest assessed dimension; no average hides a critical gate.</p></div></div>
          <div className="dimension-key">{health.length ? health.map((item) => { const assessed = item.state === 'ASSESSED' && item.score !== null; return <div key={item.id}><span>{item.label}</span><strong>{assessed ? item.score : '—'}</strong><small>{assessed ? item.detail : item.state}</small></div> }) : <p>No persisted health snapshot is available.</p>}</div>
          {!assessedHealth.length && health.length ? <p className="unassessed-note">Health dimensions exist, but none has an assessed numeric value.</p> : null}
        </section>
        <aside className="role-card panel-dark" aria-label="Granted review roles"><ShieldCheck size={22} /><span>GRANTED ACCOUNT ROLES</span><h2>{roleSummary}</h2><p>UGNAY shows every granted role and never offers role impersonation.</p><small>Project membership and academic authority are separately enforced and audited.</small></aside>
      </div>

      <section className="queue-section">
        <div className="queue-toolbar"><div><span>REQUIRES JUDGMENT</span><h2>{visible.length} review{visible.length === 1 ? '' : 's'} in this view</h2></div><div className="filter-chips">{(['ALL', 'CRITICAL', 'HIGH', 'MODERATE'] as const).map((value) => <button key={value} className={filter === value ? 'is-active' : ''} onClick={() => setFilter(value)}>{value === 'ALL' ? 'All' : value}</button>)}</div></div>
        {!queueQuery.isPending && !queueQuery.isError && visible.length ? <div className="queue-list">{visible.map((item, index) => <ReviewCard key={item.id} item={item} index={index} projectId={projectId} roles={roles} />)}</div> : null}
        {!queueQuery.isPending && !queueQuery.isError && !visible.length ? <div className="paper-panel empty-state"><ShieldCheck size={20} /><h3>{queue.length ? 'No reviews match this filter' : 'No persisted reviews'}</h3><p>{queue.length ? 'Choose another severity filter to inspect the project inbox.' : 'The inbox is empty for this project and role. UGNAY does not insert sample decisions.'}</p></div> : null}
      </section>

      <section className="recent-decisions"><div className="section-heading"><div><span>ACCOUNTABILITY TRAIL</span><h2>Append-only academic history</h2></div><span className="audit-api-note">{queueQuery.isError ? 'UNAVAILABLE' : `${eventCount} persisted events`}</span></div><div className="paper-panel review-history-summary"><ShieldCheck size={20} /><p>Expand a review above to inspect its exact actor-attributed revision requests and responses. Prior entries cannot be edited from this interface.</p></div></section>
    </div>
  )
}
