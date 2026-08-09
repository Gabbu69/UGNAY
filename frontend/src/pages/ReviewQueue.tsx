import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Clock3, Filter, ShieldCheck } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { healthLabel, overallHealth } from '../lib/analysis'
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

export default function ReviewQueue() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const workspace = data?.data
  const health = workspace?.health ?? []
  const queue = workspace?.reviewQueue ?? []
  const [filter, setFilter] = useState<RiskLevel | 'ALL'>('ALL')
  const visible = queue.filter((item) => filter === 'ALL' || item.risk === filter)
  const overall = health.length ? overallHealth(health) : 0
  const roles = auth?.session.roles ?? []
  const roleSummary = roles.length ? roles.map((role) => role.toLowerCase().replaceAll('_', ' ')).join(', ') : 'No granted review role'

  return (
    <div className="page review-page">
      <PageHeader eyebrow="Academic actions, not task management" title="Review Queue"
        description="Only persisted findings and academic actions for the selected project appear here."
        actions={<StatusPill tone="violet"><Filter size={13} /> Granted roles only</StatusPill>}
        meta={<><StatusPill tone={queue.length ? 'coral' : 'violet'}>{queue.length} persisted reviews</StatusPill><span>Source: {data?.source ?? 'UNAVAILABLE'}</span></>} />

      <div className="review-overview">
        <section className="review-health paper-panel">
          <div className="health-summary"><ScoreRing score={overall} label={health.length ? healthLabel(overall) : 'UNASSESSED'} size="large" /><div><span>CURRENT PROJECT HEALTH</span><h2>{workspace?.project.title ?? 'Unavailable'}</h2><p>Overall status follows the weakest assessed dimension; no average hides a critical gate.</p></div></div>
          <div className="dimension-key">{health.length ? health.map((item) => <div key={item.id}><span>{item.label}</span><strong>{item.score ?? '—'}</strong><small>{item.detail}</small></div>) : <p>No persisted health snapshot is available.</p>}</div>
        </section>
        <aside className="role-card panel-dark" aria-label="Granted review roles"><ShieldCheck size={22} /><span>GRANTED ACCOUNT ROLES</span><h2>{roleSummary}</h2><p>UGNAY shows every granted role and never offers role impersonation.</p><small>Project membership and academic authority are separately enforced and audited.</small></aside>
      </div>

      <section className="queue-section">
        <div className="queue-toolbar"><div><span>REQUIRES JUDGMENT</span><h2>{visible.length} review{visible.length === 1 ? '' : 's'} in this view</h2></div><div className="filter-chips">{(['ALL', 'CRITICAL', 'HIGH', 'MODERATE'] as const).map((value) => <button key={value} className={filter === value ? 'is-active' : ''} onClick={() => setFilter(value)}>{value === 'ALL' ? 'All' : value}</button>)}</div></div>
        {visible.length ? <div className="queue-list">{visible.map((item, index) => <article key={item.id} className="queue-item"><span className="queue-index">{String(index + 1).padStart(2, '0')}</span><div className="queue-copy"><small>{item.eyebrow} - {item.owner}</small><h3>{item.title}</h3><p>{item.summary}</p></div><div className="queue-due"><Clock3 size={14} /><span>{item.due}</span></div><RiskPill level={item.risk} /><Link className="button button-secondary" to={reviewDestination(item.eyebrow, workspace?.project.id ?? '')}>{item.action} <ArrowRight size={14} /></Link></article>)}</div>
          : <div className="paper-panel empty-state"><ShieldCheck size={20} /><h3>No persisted reviews</h3><p>The queue is empty for this project and role. UGNAY does not insert sample decisions.</p></div>}
      </section>

      <section className="recent-decisions"><div className="section-heading"><div><span>ACCOUNTABILITY TRAIL</span><h2>Append-only academic history</h2></div><span className="audit-api-note">Curator access only</span></div><div className="paper-panel empty-state"><ShieldCheck size={20} /><p>Authorized curators can inspect exact persisted decision events through the audit API.</p></div></section>
    </div>
  )
}
