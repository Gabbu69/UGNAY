import { useCallback, useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowRight, GitBranch, Grid3X3, PenLine, Search, ShieldCheck } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { ApiProblem, rerunProjectAnalysis } from '../lib/api'
import { TraceGraph } from '../components/TraceGraph'
import { EvidenceChain } from '../components/EvidenceChain'
import { FindingDrawer } from '../components/FindingDrawer'
import { EvidenceAuthoringStudio } from '../components/EvidenceAuthoringStudio'
import { Metric, PageHeader, StatusPill } from '../components/Primitives'
import type { Finding, TraceItemType } from '../types/domain'

const typeTone: Record<TraceItemType, 'coral' | 'violet' | 'teal' | 'neutral' | 'amber'> = {
  PROBLEM: 'coral', OBJECTIVE: 'violet', REQUIREMENT: 'teal', FEATURE: 'neutral', TEST_CASE: 'amber', OUTPUT: 'teal',
}

export default function AlignmentWorkspace() {
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const queryClient = useQueryClient()
  const workspace = data?.data
  const nodes = useMemo(() => workspace?.traceNodes ?? [], [workspace?.traceNodes])
  const edges = useMemo(() => workspace?.traceEdges ?? [], [workspace?.traceEdges])
  const findings = useMemo(() => workspace?.findings ?? [], [workspace?.findings])
  const [view, setView] = useState<'graph' | 'matrix'>('graph')
  const [selectedId, setSelectedId] = useState('r2')
  const [selectedFinding, setSelectedFinding] = useState<Finding>()
  const [authoringOpen, setAuthoringOpen] = useState(false)
  const [authoringConfirmation, setAuthoringConfirmation] = useState('')
  const [artifactFilter, setArtifactFilter] = useState<'all' | 'findings' | 'stale'>('all')
  const [artifactQuery, setArtifactQuery] = useState('')
  const handleSelect = useCallback((id: string) => setSelectedId(id), [])
  const openFindings = useMemo(() => findings.filter((finding) => finding.state === 'OPEN' || finding.state === 'REOPENED'), [findings])
  const findingCodes = useMemo(() => new Set(openFindings.map((finding) => finding.itemCode.toLocaleLowerCase())), [openFindings])
  const staleNodes = useMemo(() => nodes.filter((node) => node.status === 'STALE' || node.status === 'MISSING'), [nodes])
  const findingNodeCount = useMemo(() => nodes.filter((node) => findingCodes.has(node.code.toLocaleLowerCase())).length, [findingCodes, nodes])
  const normalizedQuery = artifactQuery.trim().toLocaleLowerCase()
  const visibleNodes = useMemo(() => nodes.filter((node) => {
    const matchesMode = artifactFilter === 'all'
      || (artifactFilter === 'findings' && findingCodes.has(node.code.toLocaleLowerCase()))
      || (artifactFilter === 'stale' && (node.status === 'STALE' || node.status === 'MISSING'))
    const matchesQuery = !normalizedQuery
      || node.code.toLocaleLowerCase().includes(normalizedQuery)
      || node.label.toLocaleLowerCase().includes(normalizedQuery)
    return matchesMode && matchesQuery
  }), [artifactFilter, findingCodes, nodes, normalizedQuery])
  const visibleNodeIds = useMemo(() => new Set(visibleNodes.map((node) => node.id)), [visibleNodes])
  const visibleEdges = useMemo(() => edges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target)), [edges, visibleNodeIds])
  const selected = visibleNodes.find((node) => node.id === selectedId) ?? visibleNodes[0]
  const related = useMemo(() => selected
    ? visibleEdges.filter((edge) => edge.source === selected.id || edge.target === selected.id)
    : [], [selected, visibleEdges])
  const matrixTargets = useMemo(() => visibleNodes.filter((node) => ['REQUIREMENT', 'FEATURE', 'TEST_CASE', 'OUTPUT'].includes(node.type)), [visibleNodes])
  const matrixSources = useMemo(() => visibleNodes.filter((node) => ['PROBLEM', 'OBJECTIVE', 'REQUIREMENT', 'FEATURE'].includes(node.type)), [visibleNodes])
  const dimension = useCallback((id: string) => workspace?.health.find((item) => item.id === id), [workspace?.health])
  const alignment = dimension('alignment')
  const verification = dimension('verification')
  const readiness = dimension('requirements') ?? dimension('readiness')
  const updatedAt = workspace?.project.updatedAt ? new Date(workspace.project.updatedAt) : undefined
  const baselineSummary = workspace
    ? `${workspace.project.stage} · ${nodes.length} artifacts · ${edges.length} links`
    : `${nodes.length} pilot artifacts · ${edges.length} links`
  const canAnalyze = data?.source === 'LIVE' && auth?.session.authenticated === true
    && auth.session.roles.some((role) => role === 'ADVISER' || role === 'COORDINATOR')
  const authoringRoles = auth?.session.roles ?? []
  const canAuthor = data?.source === 'LIVE' && auth?.session.authenticated === true
    && authoringRoles.some((role) => role === 'STUDENT' || role === 'ADVISER' || role === 'COORDINATOR')
  const analysisMutation = useMutation({
    mutationFn: () => rerunProjectAnalysis(workspace?.project.id ?? ''),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workspace'] }),
  })
  const actionError = analysisMutation.error instanceof ApiProblem ? analysisMutation.error.detail : analysisMutation.error?.message

  return (
    <div className="page alignment-page">
      <PageHeader eyebrow="Project evidence, end to end" title="Alignment Workspace" description="Trace every feature and test back to an approved problem—and surface the gaps before they become scope debt."
        actions={<><button className="button button-secondary" disabled={!canAuthor} title={canAuthor ? 'Add justified evidence to the working chain' : 'Live project authoring role required'} onClick={() => setAuthoringOpen(true)}><PenLine size={15} /> Author evidence</button><button className="button button-primary" disabled={!canAnalyze || analysisMutation.isPending} onClick={() => analysisMutation.mutate()}>{analysisMutation.isPending ? 'Analyzing...' : 'Rerun analysis'} <ArrowRight size={16} /></button></>}
        meta={<><StatusPill tone={workspace ? 'teal' : 'amber'}>{workspace ? 'Live evidence chain' : 'Pilot snapshot'}</StatusPill><span>{baselineSummary}{updatedAt && !Number.isNaN(updatedAt.valueOf()) ? ` · updated ${updatedAt.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}` : ''}</span></>} />
      {authoringConfirmation ? <div className="recorded-banner" role="status"><ShieldCheck size={20} /><div><strong>Evidence chain updated</strong><span>{authoringConfirmation}</span></div><button type="button" onClick={() => setAuthoringConfirmation('')}>Dismiss</button></div> : null}
      {analysisMutation.isSuccess ? <div className="recorded-banner" role="status"><ShieldCheck size={20} /><div><strong>Alignment analysis refreshed</strong><span>Findings, coverage, scope risk, and health were recalculated from the current baseline.</span></div></div> : null}
      {analysisMutation.isError ? <div className="recorded-banner" role="alert"><AlertTriangle size={20} /><div><strong>Analysis was not updated</strong><span>{actionError ?? 'Reload the project and retry with its current baseline.'}</span></div></div> : null}
      <EvidenceChain active="REQUIREMENT" />
      <div className="metric-strip">
        <Metric label="Alignment health" value={alignment?.score == null ? '—' : String(Math.round(alignment.score))} note={alignment?.detail ?? 'Not yet assessed against a live baseline'} accent="teal" />
        <Metric label="Verification health" value={verification?.score == null ? '—' : String(Math.round(verification.score))} note={verification?.detail ?? 'Current test evidence is not yet assessed'} accent="coral" />
        <Metric label="Requirement readiness" value={readiness?.score == null ? '—' : String(Math.round(readiness.score))} note={readiness?.detail ?? 'Requirement quality is not yet assessed'} accent="amber" />
        <Metric label="Open findings" value={String(openFindings.length)} note={`${openFindings.filter((finding) => finding.severity === 'CRITICAL').length} critical · ${openFindings.filter((finding) => finding.severity === 'HIGH').length} high`} accent="violet" />
      </div>

      <div className="workspace-grid">
        <section className="trace-surface panel-dark">
          <div className="trace-toolbar">
            <div><span>TRACEABILITY FIELD</span><h2>Approved evidence network</h2></div>
            <div className="segmented-control" role="group" aria-label="Traceability view">
              <button onClick={() => setView('graph')} className={view === 'graph' ? 'is-active' : ''}><GitBranch size={15} />Graph</button>
              <button onClick={() => setView('matrix')} className={view === 'matrix' ? 'is-active' : ''}><Grid3X3 size={15} />Matrix</button>
            </div>
          </div>
          <div className="trace-filters">
            <button type="button" className={artifactFilter === 'all' ? 'is-active' : ''} aria-pressed={artifactFilter === 'all'} onClick={() => setArtifactFilter('all')}>All artifacts <span>{nodes.length}</span></button>
            <button type="button" className={artifactFilter === 'findings' ? 'is-active' : ''} aria-pressed={artifactFilter === 'findings'} onClick={() => setArtifactFilter('findings')}>Findings <span>{findingNodeCount}</span></button>
            <button type="button" className={artifactFilter === 'stale' ? 'is-active' : ''} aria-pressed={artifactFilter === 'stale'} onClick={() => setArtifactFilter('stale')}>Stale <span>{staleNodes.length}</span></button>
            <div><Search size={15} /><input aria-label="Filter artifacts" placeholder="Find code or artifact" value={artifactQuery} onChange={(event) => setArtifactQuery(event.target.value)} /></div>
          </div>
          {visibleNodes.length === 0 ? <div className="trace-empty" role="status"><Search size={22} /><strong>No artifacts match this view</strong><span>Clear the search or choose another evidence filter.</span><button type="button" className="text-button on-dark" onClick={() => { setArtifactFilter('all'); setArtifactQuery('') }}>Reset filters</button></div> : view === 'graph' ? <TraceGraph nodes={visibleNodes} edges={visibleEdges} selectedId={selected?.id} onSelect={handleSelect} /> : (
            <div className="trace-matrix-wrap" role="region" tabIndex={0} aria-label="Scrollable traceability relationship matrix">
              <table className="trace-matrix"><caption className="sr-only">Traceability relationships by source and target artifact</caption><thead><tr><th>Artifact</th>{matrixTargets.map((node) => <th key={node.id}>{node.code}</th>)}</tr></thead><tbody>{matrixSources.map((source) => <tr key={source.id}><th>{source.code}<small>{source.type}</small></th>{matrixTargets.map((target) => <td key={target.id}>{visibleEdges.some((edge) => edge.source === source.id && edge.target === target.id) ? <span role="img" aria-label="Linked">●</span> : <i>—</i>}</td>)}</tr>)}</tbody></table>
            </div>
          )}
          <div className="graph-legend">{Object.entries(typeTone).map(([type, tone]) => <StatusPill key={type} tone={tone}>{type.replace('_', ' ')}</StatusPill>)}</div>
        </section>

        <aside className="artifact-inspector paper-panel" aria-label="Selected trace artifact details">
          {selected ? <><div className="inspector-head"><StatusPill tone={typeTone[selected.type]}>{selected.type.replace('_', ' ')}</StatusPill><span>{selected.status}</span></div><code>{selected.code}</code><h2>{selected.label}</h2><p>{selected.status === 'DRAFT' ? 'This working artifact remains outside the approved baseline until a coordinator decision.' : 'This record is part of the current evidence chain. Revisions preserve its prior scholarly state.'}</p>{selected.readiness != null ? <div className="readiness-score"><div><strong>{selected.readiness}</strong><span>readiness</span></div><p>{selected.readiness >= 85 ? 'Baseline-ready' : selected.readiness >= 70 ? 'Needs refinement' : 'Incomplete'}<small>Acceptance criteria carry the greatest weight.</small></p></div> : null}<div className="link-list"><span>DIRECT RELATIONSHIPS · {related.length}</span>{related.length ? related.map((edge) => { const otherId = edge.source === selected.id ? edge.target : edge.source; const other = visibleNodes.find((node) => node.id === otherId); return <button key={edge.id} onClick={() => setSelectedId(otherId)}><i /><div><small>{edge.relationship}</small><strong>{other?.code} · {other?.label}</strong></div><ArrowRight size={14} /></button> }) : <p className="artifact-empty">No relationship is visible in the current filter.</p>}</div>{canAuthor ? <button className="button button-secondary full-width" onClick={() => setAuthoringOpen(true)}><PenLine size={15} /> Revise evidence chain</button> : null}</> : <div className="artifact-empty-state"><Search size={22} /><strong>No artifact selected</strong><span>Reset the filters to inspect evidence.</span></div>}
        </aside>
      </div>

      <section className="findings-section">
        <div className="section-heading"><div><span>EXPLAINABLE ANALYSIS</span><h2>Findings that need human attention</h2></div><p>Rules point to evidence and a next valid action. They never change scope on their own.</p></div>
        <div className="finding-list">{openFindings.map((finding) => <button key={finding.id} className={`finding-row severity-${finding.severity.toLowerCase()}`} onClick={() => setSelectedFinding(finding)}><span className="finding-icon">{finding.severity === 'CRITICAL' ? <AlertTriangle size={18} /> : <ShieldCheck size={18} />}</span><div><small>{finding.code} · {finding.rule}</small><strong>{finding.title}</strong><p>{finding.explanation}</p></div><StatusPill tone={finding.severity === 'CRITICAL' ? 'coral' : finding.severity === 'INFO' ? 'teal' : 'amber'}>{finding.severity}</StatusPill><ArrowRight size={17} /></button>)}</div>
      </section>
      <FindingDrawer finding={selectedFinding} open={Boolean(selectedFinding)} projectId={workspace?.project.id ?? ''} roles={authoringRoles}
        onOpenChange={(open) => { if (!open) setSelectedFinding(undefined) }}
        onOpenArtifact={(itemCode) => {
          const implicated = nodes.find((node) => node.code.toLocaleLowerCase() === itemCode.toLocaleLowerCase())
          setArtifactFilter('all')
          setArtifactQuery(itemCode)
          if (implicated) setSelectedId(implicated.id)
          setSelectedFinding(undefined)
        }} />
      <EvidenceAuthoringStudio
        open={authoringOpen}
        onOpenChange={setAuthoringOpen}
        projectId={workspace?.project.id ?? ''}
        source={data?.source ?? 'UNAVAILABLE'}
        roles={authoringRoles}
        onRecorded={setAuthoringConfirmation}
      />
    </div>
  )
}
