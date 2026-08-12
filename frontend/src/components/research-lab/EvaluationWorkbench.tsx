import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle, BarChart3, CheckCircle2, ChevronDown, Download, FileCheck2,
  Fingerprint, Gauge, LockKeyhole, Play, Plus, RefreshCw, UsersRound,
} from 'lucide-react'
import { useAuthSession } from '../../hooks/useAuthSession'
import {
  createEvaluationDataset, createEvaluationQuery, evaluationCsvUrl, freezeEvaluationDataset,
  getEvaluationReport, getEvaluationRun, listEvaluationDatasets, listEvaluationQueries,
  startEvaluationRun, submitEvaluationJudgment, adjudicateEvaluationQrel,
  type EvaluationMetric, type EvaluationRun,
} from '../../lib/researchLabApi'
import { StatusPill } from '../Primitives'

function tone(status: string): 'teal' | 'violet' | 'amber' | 'coral' | 'neutral' {
  if (status === 'FROZEN' || status === 'COMPLETED' || status === 'COMPARABLE' || status === 'AVAILABLE') return 'teal'
  if (status === 'DRAFT' || status === 'RUNNING' || status === 'QUEUED') return 'violet'
  if (status === 'PARTIAL' || status === 'UNAVAILABLE') return 'amber'
  if (status === 'FAILED') return 'coral'
  return 'neutral'
}

function metric(value: number | null | undefined, status?: string) {
  if (status === 'UNAVAILABLE' || value == null) return 'UNAVAILABLE'
  return value.toFixed(3)
}

function primaryMetric(metrics: EvaluationMetric[]) {
  return metrics.find((value) => value.k === 5)
}

function ErrorNotice({ error }: { error: unknown }) {
  return <div className="lab-error" role="alert"><AlertCircle size={18} /><div><strong>The evaluation request was not completed.</strong><span>{error instanceof Error ? error.message : 'Check the evidence and try again.'}</span></div></div>
}

export function EvaluationWorkbench() {
  const queryClient = useQueryClient()
  const { data: auth } = useAuthSession()
  const roles = auth?.session.roles ?? []
  const curator = roles.includes('CURATOR')
  const coordinator = roles.includes('COORDINATOR')
  const canRun = curator || coordinator || roles.includes('ADVISER')
  const canReview = coordinator || roles.includes('ADVISER')
  const datasets = useQuery({ queryKey: ['evaluation-datasets'], queryFn: listEvaluationDatasets })
  const [selectedVersion, setSelectedVersion] = useState('')
  const selectedId = selectedVersion || datasets.data?.[0]?.versionId || ''
  const selected = datasets.data?.find((value) => value.versionId === selectedId)
  const queries = useQuery({
    queryKey: ['evaluation-queries', selectedId],
    queryFn: () => listEvaluationQueries(selectedId),
    enabled: Boolean(selectedId),
  })
  const [activeRunId, setActiveRunId] = useState('')
  const run = useQuery({
    queryKey: ['evaluation-run', activeRunId],
    queryFn: () => getEvaluationRun(activeRunId),
    enabled: Boolean(activeRunId),
    refetchInterval: (query) => ['QUEUED', 'RUNNING'].includes((query.state.data as EvaluationRun | undefined)?.status ?? '') ? 1500 : false,
  })
  const terminal = run.data && ['COMPLETED', 'PARTIAL', 'UNAVAILABLE', 'FAILED'].includes(run.data.status)
  const report = useQuery({
    queryKey: ['evaluation-report', activeRunId],
    queryFn: () => getEvaluationReport(activeRunId),
    enabled: Boolean(activeRunId && terminal && run.data?.status !== 'FAILED'),
  })

  const [datasetName, setDatasetName] = useState('')
  const [datasetDescription, setDatasetDescription] = useState('')
  const createDataset = useMutation({
    mutationFn: () => createEvaluationDataset(datasetName, datasetDescription),
    onSuccess: (created) => {
      setDatasetName(''); setDatasetDescription(''); setSelectedVersion(created.versionId)
      queryClient.invalidateQueries({ queryKey: ['evaluation-datasets'] })
    },
  })
  const [queryKey, setQueryKey] = useState('')
  const [queryTitle, setQueryTitle] = useState('')
  const [queryProblem, setQueryProblem] = useState('')
  const [querySplit, setQuerySplit] = useState<'DEV' | 'TEST'>('TEST')
  const addQuery = useMutation({
    mutationFn: () => createEvaluationQuery(selectedId, { externalKey: queryKey, title: queryTitle, problemStatement: queryProblem, split: querySplit }),
    onSuccess: () => {
      setQueryKey(''); setQueryTitle(''); setQueryProblem('')
      queryClient.invalidateQueries({ queryKey: ['evaluation-queries', selectedId] })
      queryClient.invalidateQueries({ queryKey: ['evaluation-datasets'] })
    },
  })
  const freeze = useMutation({
    mutationFn: () => freezeEvaluationDataset(selectedId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['evaluation-datasets'] }),
  })
  const [reviewQueryId, setReviewQueryId] = useState('')
  const [reviewStudyId, setReviewStudyId] = useState('')
  const [reviewGrade, setReviewGrade] = useState('1')
  const [reviewRationale, setReviewRationale] = useState('')
  const [reviewAction, setReviewAction] = useState<'JUDGMENT' | 'ADJUDICATION'>('JUDGMENT')
  const recordReview = useMutation({
    mutationFn: () => {
      const queryId = reviewQueryId || queries.data?.[0]?.id || ''
      const grade = Number(reviewGrade)
      return reviewAction === 'ADJUDICATION'
        ? adjudicateEvaluationQrel(queryId, reviewStudyId, grade, reviewRationale)
        : submitEvaluationJudgment(queryId, reviewStudyId, grade, reviewRationale)
    },
    onSuccess: () => {
      setReviewStudyId(''); setReviewRationale('')
      queryClient.invalidateQueries({ queryKey: ['evaluation-queries', selectedId] })
      queryClient.invalidateQueries({ queryKey: ['evaluation-datasets'] })
    },
  })
  const startRun = useMutation({
    mutationFn: () => startEvaluationRun(selectedId),
    onSuccess: (created) => setActiveRunId(created.id),
  })

  const submitDataset = (event: FormEvent) => { event.preventDefault(); createDataset.mutate() }
  const submitQuery = (event: FormEvent) => { event.preventDefault(); addQuery.mutate() }
  const submitReview = (event: FormEvent) => { event.preventDefault(); recordReview.mutate() }
  const evaluationError = datasets.error || queries.error || createDataset.error || addQuery.error || freeze.error || recordReview.error || startRun.error || run.error || report.error

  return (
    <div className="evaluation-workbench">
      <div className="lab-intro-row">
        <div><span>DATA MINING EVALUATION</span><h2>Compare algorithms on one frozen ground truth</h2><p>Every arm receives the same corpus, structured query set, adjudicated qrels, cutoffs, execution order, and environment manifest. Results never change an academic decision.</p></div>
        <div className="evaluation-protocol-card"><FileCheck2 size={19} /><span><small>PRIMARY CONTRACT</small><strong>Precision · Recall · F1 · MRR · NDCG @ 5</strong><em>K = 1, 3, 5, 10 · graded qrels 0–3</em></span></div>
      </div>

      {evaluationError ? <ErrorNotice error={evaluationError} /> : null}

      <section className="evaluation-controls paper-panel">
        <div className="lab-section-heading"><div><span>FROZEN EXPERIMENT INPUT</span><h2>Dataset manifest</h2></div>{selected ? <StatusPill tone={tone(selected.status)}>{selected.status}</StatusPill> : null}</div>
        <div className="dataset-toolbar">
          <label><span>Dataset version</span><select value={selectedId} onChange={(event) => { setSelectedVersion(event.target.value); setReviewQueryId('') }} disabled={!datasets.data?.length}>{datasets.data?.length ? datasets.data.map((value) => <option key={value.versionId} value={value.versionId}>{value.name} · v{value.version} · {value.status}</option>) : <option value="">No dataset available</option>}</select></label>
          {canRun ? <button type="button" className="button button-primary" disabled={!selected || selected.status !== 'FROZEN' || startRun.isPending} onClick={() => startRun.mutate()}><Play size={16} />{startRun.isPending ? 'Queueing…' : 'Run four-arm comparison'}</button> : null}
        </div>
        {selected ? <dl className="manifest-grid"><div><dt>Corpus</dt><dd>{selected.corpusSize} immutable studies</dd></div><div><dt>Queries</dt><dd>{selected.queryCount}</dd></div><div><dt>Adjudicated qrels</dt><dd>{selected.adjudicatedQrelCount}</dd></div><div><dt>Corpus SHA-256</dt><dd><code title={selected.corpusSha256}>{selected.corpusSha256.slice(0, 16)}…</code></dd></div><div><dt>Dataset SHA-256</dt><dd>{selected.datasetSha256 ? <code title={selected.datasetSha256}>{selected.datasetSha256.slice(0, 16)}…</code> : 'UNASSESSED until freeze'}</dd></div><div><dt>Frozen at</dt><dd>{selected.frozenAt ? new Date(selected.frozenAt).toLocaleString() : 'Not frozen'}</dd></div></dl> : <div className="lab-empty inline"><Fingerprint size={21} /><div><strong>No evaluation dataset exists yet.</strong><span>A curator can snapshot the actual catalogue. UGNAY does not ship fabricated thesis metrics.</span></div></div>}
      </section>

      {(curator || coordinator) ? <section className="evidence-authoring-grid">
        {curator ? <details className="lab-authoring-card"><summary><Plus size={16} /><strong>Create corpus snapshot</strong><ChevronDown size={16} /></summary><form onSubmit={submitDataset}><label>Name<input required maxLength={240} value={datasetName} onChange={(event) => setDatasetName(event.target.value)} /></label><label>Description<textarea maxLength={4000} value={datasetDescription} onChange={(event) => setDatasetDescription(event.target.value)} /></label><p>All current catalogue studies are copied into an immutable evaluation version with hashes; source records are not changed.</p><button className="button button-secondary" disabled={createDataset.isPending}>{createDataset.isPending ? 'Creating…' : 'Create draft dataset'}</button></form></details> : null}
        {curator && selected?.status === 'DRAFT' ? <details className="lab-authoring-card"><summary><Plus size={16} /><strong>Add structured query</strong><ChevronDown size={16} /></summary><form onSubmit={submitQuery}><div className="form-two"><label>External key<input required pattern="[A-Za-z0-9][A-Za-z0-9._-]*" maxLength={120} value={queryKey} onChange={(event) => setQueryKey(event.target.value)} /></label><label>Split<select value={querySplit} onChange={(event) => setQuerySplit(event.target.value as 'DEV' | 'TEST')}><option>TEST</option><option>DEV</option></select></label></div><label>Query title<input required maxLength={600} value={queryTitle} onChange={(event) => setQueryTitle(event.target.value)} /></label><label>Problem statement<textarea required maxLength={12000} value={queryProblem} onChange={(event) => setQueryProblem(event.target.value)} /></label><button className="button button-secondary" disabled={addQuery.isPending}>{addQuery.isPending ? 'Adding…' : 'Add structured query'}</button></form></details> : null}
        {coordinator && selected?.status === 'DRAFT' ? <article className="lab-authoring-card freeze-card"><LockKeyhole size={18} /><div><strong>Freeze ground truth</strong><p>Freeze succeeds only when every query has a relevant adjudicated qrel backed by two independent reviewers.</p><button type="button" className="button button-secondary" onClick={() => freeze.mutate()} disabled={freeze.isPending}>{freeze.isPending ? 'Validating…' : 'Validate and freeze'}</button></div></article> : null}
      </section> : null}

      {selected ? <section className="evaluation-query-ledger">
        <div className="lab-section-heading"><div><span>QUERY & JUDGMENT LEDGER</span><h2>Ground-truth completeness</h2></div><StatusPill tone="neutral">Two reviewers + adjudication</StatusPill></div>
        {queries.data?.length ? <div className="table-scroll"><table className="lab-table"><thead><tr><th>Key</th><th>Structured query</th><th>Split</th><th>Reviewers</th><th>Qrels</th><th>State</th></tr></thead><tbody>{queries.data.map((query) => <tr key={query.id}><td><code>{query.externalKey}</code></td><td>{query.title}</td><td>{query.split}</td><td>{query.distinctReviewerCount} / 2</td><td>{query.adjudicatedQrelCount}</td><td><StatusPill tone={query.distinctReviewerCount >= 2 && query.adjudicatedQrelCount > 0 ? 'teal' : 'amber'}>{query.distinctReviewerCount >= 2 && query.adjudicatedQrelCount > 0 ? 'Reviewed' : 'Incomplete'}</StatusPill></td></tr>)}</tbody></table></div> : <div className="lab-empty inline"><UsersRound size={21} /><div><strong>No structured queries recorded.</strong><span>Real judgments and qrels must be authored before metrics can be assessed.</span></div></div>}
      </section> : null}

      {canReview && selected?.status === 'DRAFT' && queries.data?.length ? <section className="paper-panel evaluation-review-panel">
        <div className="lab-section-heading"><div><span>HUMAN GROUND TRUTH</span><h2>Record independent relevance evidence</h2></div><StatusPill tone="violet">Grade 0–3</StatusPill></div>
        <form className="evaluation-review-form" onSubmit={submitReview}>
          <label><span>Evaluation query</span><select value={reviewQueryId || queries.data[0].id} onChange={(event) => setReviewQueryId(event.target.value)}>{queries.data.map((query) => <option key={query.id} value={query.id}>{query.externalKey} · {query.title}</option>)}</select></label>
          <label><span>Authorized catalogue study UUID</span><input required pattern="[0-9a-fA-F-]{36}" value={reviewStudyId} onChange={(event) => setReviewStudyId(event.target.value)} placeholder="00000000-0000-0000-0000-000000000000" /><small>Copy the study ID from Research Atlas. The server verifies that it belongs to this corpus and is visible to you.</small></label>
          <div className="form-two">
            <label><span>Relevance grade</span><select value={reviewGrade} onChange={(event) => setReviewGrade(event.target.value)}><option value="0">0 · Not relevant</option><option value="1">1 · Marginally relevant</option><option value="2">2 · Relevant</option><option value="3">3 · Highly relevant</option></select></label>
            {coordinator ? <label><span>Evidence action</span><select value={reviewAction} onChange={(event) => setReviewAction(event.target.value as 'JUDGMENT' | 'ADJUDICATION')}><option value="JUDGMENT">Independent judgment</option><option value="ADJUDICATION">Coordinator adjudication</option></select></label> : null}
          </div>
          <label><span>Evidence rationale</span><textarea required maxLength={4000} value={reviewRationale} onChange={(event) => setReviewRationale(event.target.value)} placeholder="Explain why this study receives the selected grade." /></label>
          <div className="review-form-footer"><p>Two distinct reviewers are required. Adjudication remains a separate coordinator action and never changes a thesis route.</p><button className="button button-secondary" disabled={recordReview.isPending}>{recordReview.isPending ? 'Recording…' : reviewAction === 'ADJUDICATION' ? 'Record adjudicated qrel' : 'Record judgment'}</button></div>
        </form>
      </section> : null}

      {activeRunId ? <section className="evaluation-run-panel panel-dark" aria-live="polite">
        <div className="evaluation-run-head"><div><RefreshCw className={['QUEUED', 'RUNNING'].includes(run.data?.status ?? '') ? 'is-spinning' : ''} size={19} /><span><small>RUN {activeRunId.slice(0, 8)}</small><strong>{run.data?.status ?? 'Loading durable run…'}</strong></span></div>{run.data ? <StatusPill tone={tone(run.data.comparability)}>{run.data.comparability}</StatusPill> : null}</div>
        {run.data ? <dl><div><dt>Primary cutoff</dt><dd>K = {run.data.primaryK}</dd></div><div><dt>Timed repetitions</dt><dd>{run.data.repetitions}</dd></div><div><dt>Execution seed</dt><dd>{run.data.executionSeed}</dd></div><div><dt>Build</dt><dd>{run.data.codeBuild}</dd></div><div><dt>Environment hash</dt><dd><code>{run.data.environmentSha256.slice(0, 16)}…</code></dd></div></dl> : null}
        {run.data?.failureReason ? <p className="run-failure"><AlertCircle size={15} />{run.data.failureReason}</p> : null}
      </section> : null}

      {report.data ? <section className="algorithm-report">
        <div className="lab-section-heading"><div><span>AUTHORITATIVE COMPARISON</span><h2>Algorithm results at K = 5</h2></div><a className="button button-secondary" href={evaluationCsvUrl(report.data.run.id)}><Download size={16} />Export CSV</a></div>
        <div className="table-scroll"><table className="lab-table metrics-table"><thead><tr><th>Algorithm / version</th><th>Status</th><th>Precision</th><th>Recall</th><th>F1</th><th>MRR</th><th>NDCG</th><th>p50 / p95</th></tr></thead><tbody>{report.data.algorithms.map((algorithm) => { const primary = primaryMetric(algorithm.aggregateMetrics); return <tr key={algorithm.algorithmRunId}><td><strong>{algorithm.algorithm.replaceAll('_', ' ')}</strong><small>{algorithm.version}</small></td><td><StatusPill tone={tone(algorithm.status)}>{algorithm.status}</StatusPill>{algorithm.unavailableReason ? <small>{algorithm.unavailableReason}</small> : null}</td><td>{metric(primary?.precision, primary?.status)}</td><td>{metric(primary?.recall, primary?.status)}</td><td>{metric(primary?.f1, primary?.status)}</td><td>{metric(primary?.mrr, primary?.status)}</td><td>{metric(primary?.ndcg, primary?.status)}</td><td>{algorithm.latencyP50Millis == null ? 'UNAVAILABLE' : `${algorithm.latencyP50Millis.toFixed(1)} / ${algorithm.latencyP95Millis?.toFixed(1) ?? '—'} ms`}</td></tr> })}</tbody></table></div>
        <div className="evaluation-metric-chart" aria-label="NDCG at five comparison chart">{report.data.algorithms.map((algorithm) => { const primary = primaryMetric(algorithm.aggregateMetrics); const value = primary?.status === 'AVAILABLE' ? primary.ndcg ?? 0 : 0; return <div key={algorithm.algorithmRunId}><span>{algorithm.algorithm.replaceAll('_', ' ')}</span><i><b style={{ width: `${Math.max(0, Math.min(100, value * 100))}%` }} /></i><strong>{primary?.status === 'AVAILABLE' ? value.toFixed(3) : 'UNAVAILABLE'}</strong></div> })}</div>
        <div className="per-query-reports"><h3>Per-query metric drilldown</h3>{report.data.algorithms.map((algorithm) => <details key={algorithm.algorithmRunId}><summary><strong>{algorithm.version}</strong><span>{algorithm.queryMetrics.length} query × cutoff rows</span><ChevronDown size={16} /></summary><div className="table-scroll"><table className="lab-table"><thead><tr><th>Query</th><th>K</th><th>Status</th><th>Relevant / judged</th><th>Precision</th><th>Recall</th><th>F1</th><th>MRR</th><th>NDCG</th></tr></thead><tbody>{algorithm.queryMetrics.map((row) => <tr key={`${row.queryId}-${row.k}`}><td><code>{row.queryKey}</code></td><td>{row.k}</td><td>{row.status}</td><td>{row.relevantCount} / {row.judgedCount}</td><td>{metric(row.precision, row.status)}</td><td>{metric(row.recall, row.status)}</td><td>{metric(row.f1, row.status)}</td><td>{metric(row.mrr, row.status)}</td><td>{metric(row.ndcg, row.status)}</td></tr>)}</tbody></table></div></details>)}</div>
        <div className="report-boundary"><CheckCircle2 size={17} /><p>{report.data.interpretationBoundary}</p></div>
        <details className="environment-manifest"><summary><Gauge size={16} /><strong>Parameters and captured environment</strong><ChevronDown size={16} /></summary><div><section><h3>Environment</h3><pre>{JSON.stringify(report.data.environment, null, 2)}</pre></section><section><h3>Reproducibility manifest</h3><pre>{JSON.stringify(report.data.manifest, null, 2)}</pre></section></div></details>
      </section> : null}

      {!activeRunId ? <div className="lab-empty lab-empty-start"><BarChart3 size={24} /><div><strong>Metrics remain UNASSESSED until a real dataset is frozen and run.</strong><span>Targets are not claims. Synthetic records, if used by tests, are excluded from this research report.</span></div></div> : null}
    </div>
  )
}
