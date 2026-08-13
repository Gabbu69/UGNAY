import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle, ArrowRight, BarChart3, Check, Database, Download, Filter,
  GitBranch, History, RefreshCw, ShieldCheck, Tags,
} from 'lucide-react'
import { useAuthSession } from '../../hooks/useAuthSession'
import {
  continuationCsvUrl, getContinuationHistory, getLatestWarehouseLoad, getWarehouseAnalytics,
  refreshWarehouse, warehouseAnalyticsCsvUrl,
} from '../../lib/researchLabApi'
import { StatusPill } from '../Primitives'

function DataBars({ values, label, valueLabel }: {
  values: Array<{ key: string; label: string; value: number }>
  label: string
  valueLabel: string
}) {
  const max = Math.max(1, ...values.map((value) => value.value))
  return <div className="warehouse-bars" role="img" aria-label={label}>{values.map((value) => <div key={value.key}><span title={value.label}>{value.label}</span><i><b style={{ width: `${(value.value / max) * 100}%` }} /></i><strong>{value.value}</strong></div>)}<small className="sr-only">{valueLabel}</small></div>
}

function analyticsFilters(filters: { department: string; fromYear: string; toYear: string }) {
  return {
    department: filters.department.trim() || undefined,
    fromYear: filters.fromYear ? Number(filters.fromYear) : undefined,
    toYear: filters.toYear ? Number(filters.toYear) : undefined,
  }
}

export function WarehouseWorkbench() {
  const queryClient = useQueryClient()
  const { data: auth } = useAuthSession()
  const curator = auth?.session.roles.includes('CURATOR') === true
  const [filterDraft, setFilterDraft] = useState({ department: '', fromYear: '', toYear: '' })
  const [filters, setFilters] = useState(filterDraft)
  const appliedFilters = analyticsFilters(filters)
  const analytics = useQuery({ queryKey: ['warehouse-analytics', filters], queryFn: () => getWarehouseAnalytics(appliedFilters) })
  const continuation = useQuery({ queryKey: ['warehouse-continuation'], queryFn: () => getContinuationHistory(100) })
  const load = useQuery({ queryKey: ['warehouse-latest-load'], queryFn: getLatestWarehouseLoad, enabled: curator })
  const refresh = useMutation({
    mutationFn: refreshWarehouse,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['warehouse-latest-load'] })
      queryClient.invalidateQueries({ queryKey: ['warehouse-analytics'] })
      queryClient.invalidateQueries({ queryKey: ['warehouse-continuation'] })
    },
  })
  const submitFilters = (event: FormEvent) => { event.preventDefault(); setFilters({ ...filterDraft }) }
  const error = analytics.error || continuation.error || load.error || refresh.error
  const data = analytics.data
  const loadAssessed = load.data != null && load.data.assessmentStatus !== 'UNASSESSED'
  const analyticsAssessed = data?.assessmentStatus === 'ASSESSED' && Boolean(data.snapshotId)
  const stages = loadAssessed ? load.data.stages : []
  const sourceCount = loadAssessed ? load.data.sourceCount : analyticsAssessed ? data?.sourceStudyCount : null
  const acceptedCount = loadAssessed ? load.data.acceptedCount : null
  const rejectedCount = loadAssessed ? load.data.rejectedCount : null
  const loadQualityAssessed = loadAssessed && load.data.quality.assessmentStatus === 'ASSESSED'
  const analyticsQualityAssessed = analyticsAssessed && data?.quality.assessmentStatus === 'ASSESSED'
  const qualityIssueCount = loadQualityAssessed ? load.data.quality.issueCount : analyticsQualityAssessed ? data?.quality.issueCount : null

  return (
    <div className="warehouse-workbench">
      <div className="lab-intro-row">
        <div><span>DATA WAREHOUSING</span><h2>Turn research history into traceable analysis</h2><p>Operational evidence is collected at a fixed cutoff, validated without coercion, normalized without changing raw facts, transformed into dimensions and facts, then atomically published for authorized analysis.</p></div>
        <div className="warehouse-snapshot-card"><Database size={19} /><span><small>LATEST AUTHORIZED SNAPSHOT</small><strong>{data?.snapshotId ? data.snapshotId.slice(0, 8) : 'UNASSESSED'}</strong><em>{data?.asOf ? `As of ${new Date(data.asOf).toLocaleString()}` : 'No successful warehouse load is available'}</em></span></div>
      </div>

      {error ? <div className="lab-error" role="alert"><AlertCircle size={18} /><div><strong>Some warehouse evidence is unavailable.</strong><span>{error instanceof Error ? error.message : 'The local warehouse API could not be read.'}</span></div></div> : null}

      <section className="pipeline-panel panel-dark" aria-labelledby="pipeline-title">
        <div className="pipeline-head"><div><span>PIPELINE EVIDENCE</span><h2 id="pipeline-title">Collect → Validate → Clean → Transform → Store → Analyze</h2></div>{curator ? <button type="button" className="button button-dark" onClick={() => refresh.mutate()} disabled={refresh.isPending}>{refresh.isPending ? <RefreshCw className="is-spinning" size={16} /> : <RefreshCw size={16} />}{refresh.isPending ? 'Refreshing…' : 'Refresh warehouse'}</button> : null}</div>
        {stages.length ? <ol className="pipeline-rail">{stages.map((stage) => <li key={stage.stage} className={`stage-${stage.status.toLowerCase()}`}><span>{stage.status === 'COMPLETED' ? <Check size={14} /> : stage.order}</span><div><b>{stage.stage}</b><small>{stage.status}</small><em>{stage.status === 'PENDING' ? '—' : `${stage.inputCount} in · ${stage.outputCount} out`}</em></div><ArrowRight size={15} /></li>)}</ol> : <div className="lab-empty inline"><Database size={20} /><div><strong>Pipeline stages are UNASSESSED.</strong><span>No persisted warehouse-stage evidence is available for display.</span></div></div>}
        <div className="pipeline-summary"><dl><div><dt>Source studies</dt><dd>{sourceCount ?? '—'}</dd></div><div><dt>Accepted</dt><dd>{acceptedCount ?? '—'}</dd></div><div><dt>Rejected</dt><dd>{rejectedCount ?? '—'}</dd></div><div><dt>Quality issues</dt><dd>{qualityIssueCount ?? '—'}</dd></div></dl><p><ShieldCheck size={15} />A failed load never replaces the latest successful snapshot. Raw catalogue evidence remains authoritative.</p></div>
      </section>

      <section className="warehouse-filter paper-panel">
        <form onSubmit={submitFilters}><Filter size={17} /><label><span>Department</span><input value={filterDraft.department} onChange={(event) => setFilterDraft({ ...filterDraft, department: event.target.value })} placeholder="All authorized departments" /></label><label><span>From year</span><input type="number" min="1900" max="2200" value={filterDraft.fromYear} onChange={(event) => setFilterDraft({ ...filterDraft, fromYear: event.target.value })} /></label><label><span>To year</span><input type="number" min="1900" max="2200" value={filterDraft.toYear} onChange={(event) => setFilterDraft({ ...filterDraft, toYear: event.target.value })} /></label><button className="button button-secondary">Apply filters</button><a className="button button-ghost" href={warehouseAnalyticsCsvUrl(appliedFilters)}><Download size={15} />CSV</a></form>
      </section>

      {analyticsAssessed && data ? <>
        <section className="warehouse-metrics"><article><span>VISIBLE STUDIES</span><strong>{data.visibleStudyCount}</strong><small>of {data.sourceStudyCount} snapshot records in scope</small></article><article><span>VALIDATED YEARS</span><strong>{data.studiesPerYear.length}</strong><small>{data.unavailableYearCount} rows have unavailable year</small></article><article><span>REPEATED TOPICS</span><strong>{data.repeatedTopics.length}</strong><small>appearing in at least two distinct studies</small></article><article><span>RESEARCH AREAS</span><strong>{data.commonResearchAreas.length}</strong><small>curated taxonomy assignments only</small></article></section>

        <div className="warehouse-analysis-grid">
          <section className="warehouse-analysis-card paper-panel"><div className="lab-section-heading"><div><span>HISTORICAL VOLUME</span><h2>Studies per validated year</h2></div><BarChart3 size={20} /></div><DataBars label="Studies per validated year" valueLabel="study count" values={data.studiesPerYear.map((item) => ({ key: String(item.year), label: String(item.year), value: item.studyCount }))} /><div className="table-scroll"><table className="lab-table compact"><thead><tr><th>Year</th><th>Distinct studies</th></tr></thead><tbody>{data.studiesPerYear.map((item) => <tr key={item.year}><td>{item.year}</td><td>{item.studyCount}</td></tr>)}</tbody></table></div></section>
          <section className="warehouse-analysis-card paper-panel"><div className="lab-section-heading"><div><span>ORGANIZATIONAL VIEW</span><h2>Studies per department</h2></div><Database size={20} /></div><DataBars label="Studies per department" valueLabel="study count" values={data.studiesPerDepartment.map((item) => ({ key: item.departmentCode, label: item.departmentCode, value: item.studyCount }))} /><div className="table-scroll"><table className="lab-table compact"><thead><tr><th>Department</th><th>Distinct studies</th></tr></thead><tbody>{data.studiesPerDepartment.map((item) => <tr key={item.departmentCode}><td><strong>{item.departmentCode}</strong><small>{item.departmentName}</small></td><td>{item.studyCount}</td></tr>)}</tbody></table></div></section>
          <section className="warehouse-analysis-card paper-panel"><div className="lab-section-heading"><div><span>ACTUAL RECURRENCE</span><h2>Repeated topics</h2></div><Tags size={20} /></div>{data.repeatedTopics.length ? <div className="topic-ledger">{data.repeatedTopics.map((topic) => <div key={`${topic.termType}-${topic.label}`}><span>{topic.label}</span><small>{topic.termType}</small><strong>{topic.studyCount} studies</strong></div>)}</div> : <div className="lab-empty inline"><Tags size={19} /><div><strong>No repeated topics in scope.</strong><span>Only normalized terms occurring in at least two studies are included.</span></div></div>}</section>
          <section className="warehouse-analysis-card paper-panel"><div className="lab-section-heading"><div><span>CURATED TAXONOMY</span><h2>Common research areas</h2></div><Tags size={20} /></div>{data.commonResearchAreas.length ? <div className="topic-ledger">{data.commonResearchAreas.map((topic) => <div key={`${topic.termType}-${topic.label}`}><span>{topic.label}</span><small>{topic.termType}</small><strong>{topic.studyCount} studies</strong></div>)}</div> : <div className="lab-empty inline"><Tags size={19} /><div><strong>No explicit research-area assignments.</strong><span>UGNAY does not infer or fabricate taxonomy evidence.</span></div></div>}</section>
        </div>

        <section className="topic-trends-panel">
          <div className="lab-section-heading"><div><span>TOPIC-BY-YEAR FACTS</span><h2>Research trends without forecasts</h2></div><StatusPill tone="neutral">Observed history only</StatusPill></div>
          {data.topicTrends.length ? <div className="table-scroll"><table className="lab-table"><thead><tr><th>Topic</th><th>Type</th><th>Year</th><th>Distinct studies</th></tr></thead><tbody>{data.topicTrends.map((trend) => <tr key={`${trend.termType}-${trend.label}-${trend.year}`}><td>{trend.label}</td><td>{trend.termType}</td><td>{trend.year}</td><td>{trend.studyCount}</td></tr>)}</tbody></table></div> : <div className="lab-empty inline"><BarChart3 size={20} /><div><strong>No topic-by-year trend rows in this scope.</strong><span>No forecast or synthetic trend has been substituted.</span></div></div>}
        </section>
      </> : <div className="lab-empty lab-empty-start"><Database size={24} /><div><strong>Warehouse analytics are UNASSESSED.</strong><span>A curator must publish a successful load from actual catalogue evidence before charts or counts appear.</span></div></div>}

      <section className="continuation-history-panel">
        <div className="lab-section-heading"><div><span>CONTINUATION FACTS</span><h2>Research continuity history</h2></div><a className="button button-secondary" href={continuationCsvUrl()}><Download size={15} />Export history</a></div>
        {continuation.data?.items.length ? <ol className="continuation-timeline">{continuation.data.items.map((item) => <li key={item.factKey}><span><GitBranch size={15} /></span><div><small>{item.sourceKind} · {item.relationshipType}</small><strong>{item.sourceStudyTitle ?? 'Authorized predecessor evidence'}{item.targetStudyTitle ? ` → ${item.targetStudyTitle}` : ''}</strong><p>{item.rationale ?? 'Rationale unavailable in source evidence.'}</p><em>{item.evidenceAt ? new Date(item.evidenceAt).toLocaleString() : 'Evidence time unavailable'} · {item.evidenceStatus ?? 'Status unavailable'}</em></div></li>)}</ol> : <div className="lab-empty inline"><History size={21} /><div><strong>No authorized continuation facts are available.</strong><span>Only approved relationships, claims, outcomes, and completion links are shown.</span></div></div>}
      </section>
    </div>
  )
}
