import type { ReactNode } from 'react'
import { ArrowDown, ArrowUp, Info, Sparkles } from 'lucide-react'
import { healthLabel, riskBand } from '../lib/analysis'
import type { RiskLevel } from '../types/domain'

export function PageHeader({ eyebrow, title, description, actions, meta }: {
  eyebrow: string
  title: string
  description: string
  actions?: ReactNode
  meta?: ReactNode
}) {
  return (
    <header className="page-header">
      <div className="page-title-block">
        <div className="eyebrow"><span />{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
        {meta ? <div className="page-meta">{meta}</div> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </header>
  )
}

export function StatusPill({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'teal' | 'violet' | 'amber' | 'coral' | 'neutral' }) {
  return <span className={`status-pill tone-${tone}`}><i />{children}</span>
}

export function RiskPill({ level }: { level: RiskLevel }) {
  const tone = level === 'CRITICAL' ? 'coral' : level === 'HIGH' ? 'amber' : level === 'MODERATE' ? 'violet' : 'teal'
  return <StatusPill tone={tone}>{level}</StatusPill>
}

export function ScoreRing({ score, label, size = 'medium' }: { score: number | null; label: string; size?: 'small' | 'medium' | 'large' }) {
  const value = score ?? 0
  const state = healthLabel(score).toLowerCase().replace(' ', '-')
  return (
    <div className={`score-ring score-${size} state-${state}`} style={{ '--score': value } as React.CSSProperties}>
      <div><strong>{score === null ? '—' : score}</strong><span>{label}</span></div>
    </div>
  )
}

export function DimensionBar({ label, value, emphasis = false, detail }: { label: string; value: number; emphasis?: boolean; detail?: string }) {
  return (
    <div className={`dimension-bar ${emphasis ? 'is-emphasis' : ''}`}>
      <div><span>{label}</span><strong>{value}%</strong></div>
      <div className="bar-track" aria-label={`${label}: ${value}%`} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={value}>
        <i style={{ width: `${value}%` }} />
      </div>
      {detail ? <small>{detail}</small> : null}
    </div>
  )
}

export function Delta({ value }: { value: number }) {
  if (value === 0) return <span className="delta is-flat">No change</span>
  const PositiveIcon = value > 0 ? ArrowUp : ArrowDown
  return <span className={`delta ${value > 0 ? 'is-positive' : 'is-negative'}`}><PositiveIcon size={12} />{Math.abs(value)} pts</span>
}

export function Metric({ label, value, note, accent = 'plain' }: { label: string; value: string; note: string; accent?: 'teal' | 'violet' | 'amber' | 'coral' | 'plain' }) {
  return (
    <div className={`metric-card accent-${accent}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{note}</small>
    </div>
  )
}

export function ExplainabilityNote({ children }: { children: ReactNode }) {
  return <div className="explainability-note"><Sparkles size={15} /><p>{children}</p></div>
}

export function AssessmentLegend({ score }: { score: number }) {
  return (
    <span className={`assessment-label band-${riskBand(score).toLowerCase()}`}>
      <Info size={13} /> {score >= 80 ? 'Very strong overlap' : score >= 65 ? 'Strong overlap' : score >= 45 ? 'Related' : 'Weak'}
    </span>
  )
}
