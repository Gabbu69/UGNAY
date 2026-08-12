import { apiRequest } from './api'

export interface SourceSpan {
  startOffset: number
  endOffset: number
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
}

export interface QueryDiagnostic {
  stage: 'LEXER' | 'PARSER' | 'SEMANTIC' | 'EXECUTION'
  code: string
  message: string
  span: SourceSpan
  expected: string[]
}

export interface QueryToken {
  type: string
  lexeme: string
  literal: string | number | null
  span: SourceSpan
}

export interface AstNode {
  kind: string
  value?: string | number | null
  span?: SourceSpan
  children: AstNode[]
}

export interface ResearchQueryResult {
  rank: number
  id: string
  code?: string | null
  title: string
  academicYear?: string | null
  year?: number | null
  department?: string | null
  lifecycleStatus: string
  visibility: string
  abstractText?: string | null
  methodology?: string | null
  keywords: string[]
  researchAreas: string[]
  similarityScore?: number | null
  scoreStatus: string
  components: { lexical?: number; tfIdf?: number; semantic?: number | null; controlledConcept?: number }
  matchedTerms: string[]
  explanations: string[]
  restricted: boolean
}

export interface ResearchQueryResponse {
  languageVersion: string
  valid: boolean
  status: 'INVALID' | 'EXECUTED' | 'PARTIAL' | 'UNAVAILABLE' | 'TIMED_OUT' | 'FAILED'
  traceIncluded: boolean
  tokens: QueryToken[]
  ast?: AstNode | null
  validation: { valid: boolean; completedStage: string }
  interpretedAction?: {
    target: string
    contextType?: string | null
    contextAuthorized: boolean
    algorithmVersion: string
    sort: string
    direction: string
    limit: number
    filterCount: number
    executor: string
  } | null
  algorithmVersion?: string | null
  semanticProvider?: string | null
  assessmentStatus: string
  warehouse: { status: string; snapshotId?: string | null; asOf?: string | null; explanation: string }
  diagnostics: QueryDiagnostic[]
  results: ResearchQueryResult[]
  latencyMillis: number
}

export interface GrammarDescription {
  version: string
  ebnf: string
  fields: string[]
  comparators: string[]
  algorithms: string[]
  examples: string[]
  limits: Record<string, number>
  safety: string
}

export function getResearchGrammar() {
  return apiRequest<GrammarDescription>('/research-queries/grammar')
}

export function executeResearchQuery(source: string, includeTrace: boolean, selectedProposalId?: string) {
  return apiRequest<ResearchQueryResponse>('/research-queries/execute', {
    method: 'POST',
    body: JSON.stringify({ source, includeTrace, selectedProposalId: selectedProposalId || undefined }),
  })
}

export type DatasetStatus = 'DRAFT' | 'FROZEN'
export type EvaluationRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'UNAVAILABLE' | 'FAILED'

export interface EvaluationDataset {
  datasetId: string
  versionId: string
  version: number
  name: string
  description?: string | null
  status: DatasetStatus
  corpusSha256: string
  datasetSha256?: string | null
  corpusSize: number
  queryCount: number
  adjudicatedQrelCount: number
  createdAt: string
  frozenAt?: string | null
}

export interface EvaluationQuery {
  id: string
  datasetVersionId: string
  externalKey: string
  split: 'DEV' | 'TEST'
  title: string
  querySha256: string
  distinctReviewerCount: number
  adjudicatedQrelCount: number
  createdAt: string
}

export interface EvaluationRun {
  id: string
  datasetVersionId: string
  status: EvaluationRunStatus
  comparability: 'COMPARABLE' | 'PARTIAL' | 'UNAVAILABLE'
  primaryK: number
  cutoffs: number[]
  repetitions: number
  executionSeed: number
  codeBuild: string
  environmentSha256: string
  runSha256: string
  failureReason?: string | null
  queuedAt: string
  startedAt?: string | null
  completedAt?: string | null
}

export interface EvaluationMetric {
  k: number
  status: 'AVAILABLE' | 'UNAVAILABLE'
  precision?: number | null
  recall?: number | null
  f1?: number | null
  mrr?: number | null
  ndcg?: number | null
  eligibleQueries: number
  excludedQueries: number
}

export interface EvaluationAlgorithmReport {
  algorithmRunId: string
  algorithm: 'LEXICAL_KEYWORD' | 'TF_IDF' | 'SEMANTIC_E5' | 'HYBRID'
  version: string
  status: EvaluationRunStatus
  configurationSha256: string
  unavailableReason?: string | null
  indexBuildMillis?: number | null
  latencyP50Millis?: number | null
  latencyP95Millis?: number | null
  aggregateMetrics: EvaluationMetric[]
  queryMetrics: Array<EvaluationMetric & { queryId: string; queryKey: string; relevantCount: number; judgedCount: number }>
  rankedHits: Array<{ queryId: string; studyId: string; rank: number; score: number }>
}

export interface EvaluationReport {
  run: EvaluationRun
  dataset: EvaluationDataset
  environment: Record<string, unknown>
  manifest: Record<string, unknown>
  algorithms: EvaluationAlgorithmReport[]
  interpretationBoundary: string
}

export interface StructuredEvaluationQueryInput {
  externalKey: string
  split: 'DEV' | 'TEST'
  title: string
  problemStatement?: string
  objectives?: string[]
  proposedSolution?: string
  methodology?: string
  dataSources?: string
  technology?: string
  intendedUsers?: string
  stakeholders?: string
  siteContext?: string
}

export interface EvaluationJudgment {
  id: string
  queryId: string
  studyId: string
  reviewer?: string
  relevanceGrade: number
  rationale: string
  revision: number
  recordedAt?: string
}

export function listEvaluationDatasets() {
  return apiRequest<EvaluationDataset[]>('/evaluation/datasets')
}

export function getEvaluationDataset(versionId: string) {
  return apiRequest<EvaluationDataset>(`/evaluation/datasets/${encodeURIComponent(versionId)}`)
}

export function createEvaluationDataset(name: string, description?: string) {
  return apiRequest<EvaluationDataset>('/evaluation/datasets', {
    method: 'POST', body: JSON.stringify({ name, description: description || undefined }),
  })
}

export function listEvaluationQueries(versionId: string) {
  return apiRequest<EvaluationQuery[]>(`/evaluation/datasets/${encodeURIComponent(versionId)}/queries`)
}

export function createEvaluationQuery(versionId: string, input: StructuredEvaluationQueryInput) {
  return apiRequest<EvaluationQuery>(`/evaluation/datasets/${encodeURIComponent(versionId)}/queries`, {
    method: 'POST', body: JSON.stringify(input),
  })
}

export function submitEvaluationJudgment(queryId: string, studyId: string, relevanceGrade: number, rationale: string) {
  return apiRequest<EvaluationJudgment>(`/evaluation/queries/${encodeURIComponent(queryId)}/judgments`, {
    method: 'POST', body: JSON.stringify({ studyId, relevanceGrade, rationale }),
  })
}

export function adjudicateEvaluationQrel(queryId: string, studyId: string, relevanceGrade: number, rationale: string) {
  return apiRequest<EvaluationJudgment>(`/evaluation/queries/${encodeURIComponent(queryId)}/qrels`, {
    method: 'POST', body: JSON.stringify({ studyId, relevanceGrade, rationale }),
  })
}

export function freezeEvaluationDataset(versionId: string) {
  return apiRequest<EvaluationDataset>(`/evaluation/datasets/${encodeURIComponent(versionId)}/freeze`, { method: 'POST' })
}

export function startEvaluationRun(datasetVersionId: string) {
  return apiRequest<EvaluationRun>('/evaluation/runs', { method: 'POST', body: JSON.stringify({ datasetVersionId }) })
}

export function getEvaluationRun(runId: string) {
  return apiRequest<EvaluationRun>(`/evaluation/runs/${encodeURIComponent(runId)}`)
}

export function getEvaluationReport(runId: string) {
  return apiRequest<EvaluationReport>(`/evaluation/runs/${encodeURIComponent(runId)}/report`)
}

export function evaluationCsvUrl(runId: string) {
  return `/api/v1/evaluation/runs/${encodeURIComponent(runId)}/report.csv`
}

export interface WarehouseQualitySummary {
  assessmentStatus: string
  issueCount: number
  bySeverity: Record<string, number>
  byCode: Record<string, number>
}

export interface WarehouseStage {
  stage: string
  order: number
  status: string
  inputCount: number
  outputCount: number
  detailsJson: string
  startedAt?: string | null
  completedAt?: string | null
}

export interface WarehouseLoad {
  id?: string | null
  status: string
  currentStage?: string | null
  assessmentStatus: string
  sourceSha256?: string | null
  sourceCount: number
  acceptedCount: number
  rejectedCount: number
  snapshotId?: string | null
  sourceCutoffAt?: string | null
  startedAt?: string | null
  completedAt?: string | null
  failureReason?: string | null
  stages: WarehouseStage[]
  quality: WarehouseQualitySummary
}

export interface WarehouseAnalytics {
  snapshotId?: string | null
  asOf?: string | null
  assessmentStatus: string
  filters: { department?: string | null; fromYear?: number | null; toYear?: number | null }
  sourceStudyCount: number
  visibleStudyCount: number
  unavailableYearCount: number
  studiesPerYear: Array<{ year: number; studyCount: number }>
  studiesPerDepartment: Array<{ departmentCode: string; departmentName: string; studyCount: number }>
  repeatedTopics: Array<{ termId?: string | null; label: string; termType: string; studyCount: number }>
  commonResearchAreas: Array<{ termId?: string | null; label: string; termType: string; studyCount: number }>
  topicTrends: Array<{ termId?: string | null; label: string; termType: string; year: number; studyCount: number }>
  quality: WarehouseQualitySummary
}

export interface ContinuationHistory {
  snapshotId?: string | null
  asOf?: string | null
  assessmentStatus: string
  total: number
  items: Array<{
    factKey: string
    sourceKind: string
    sourceStudyId?: string | null
    sourceStudyTitle?: string | null
    targetStudyId?: string | null
    targetStudyTitle?: string | null
    successorProjectId?: string | null
    continuationItemId?: string | null
    relationshipType: string
    evidenceStatus?: string | null
    rationale?: string | null
    evidenceAt?: string | null
  }>
}

function warehouseFilterQuery(filters?: { department?: string; fromYear?: number; toYear?: number }) {
  const params = new URLSearchParams()
  if (filters?.department) params.set('department', filters.department)
  if (filters?.fromYear != null) params.set('fromYear', String(filters.fromYear))
  if (filters?.toYear != null) params.set('toYear', String(filters.toYear))
  const query = params.toString()
  return query ? `?${query}` : ''
}

export function refreshWarehouse() {
  return apiRequest<WarehouseLoad>('/warehouse/refresh', { method: 'POST' })
}

export function getLatestWarehouseLoad() {
  return apiRequest<WarehouseLoad>('/warehouse/loads/latest')
}

export function getWarehouseAnalytics(filters?: { department?: string; fromYear?: number; toYear?: number }) {
  return apiRequest<WarehouseAnalytics>(`/warehouse/analytics${warehouseFilterQuery(filters)}`)
}

export function getContinuationHistory(limit = 100) {
  return apiRequest<ContinuationHistory>(`/warehouse/continuation-history?limit=${Math.min(500, Math.max(1, limit))}`)
}

export function warehouseAnalyticsCsvUrl(filters?: { department?: string; fromYear?: number; toYear?: number }) {
  return `/api/v1/warehouse/analytics.csv${warehouseFilterQuery(filters)}`
}

export function continuationCsvUrl(limit = 500) {
  return `/api/v1/warehouse/continuation-history.csv?limit=${Math.min(500, Math.max(1, limit))}`
}
