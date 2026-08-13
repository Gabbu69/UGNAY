import type {
  CompletionPackageRecord,
  EvidenceReferenceRecord,
  EvidenceReferenceType,
  AssessmentStatus,
  DecisionDisposition,
  DiscoveryInput,
  DiscoveryRun,
  ProjectTraceability,
  Study,
  TraceItemType,
  WorkspaceData,
} from '../types/domain'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

interface CsrfToken {
  headerName: string
  token: string
}

let csrfTokenRequest: Promise<CsrfToken> | undefined

export class ApiProblem extends Error {
  status: number
  detail: string

  constructor(status: number, detail: string) {
    super(detail)
    this.name = 'ApiProblem'
    this.status = status
    this.detail = detail
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const controller = new AbortController()
  const isMultipart = init?.body instanceof FormData
  const timeout = window.setTimeout(() => controller.abort(), isMultipart ? 60_000 : SAFE_METHODS.has(method) ? 4_000 : 15_000)
  try {
    const headers = new Headers({ Accept: 'application/json', ...init?.headers })
    if (!isMultipart && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (!SAFE_METHODS.has(method) && path !== '/auth/login') {
      csrfTokenRequest ??= fetch(`${API_BASE}/auth/csrf`, {
        credentials: 'include',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      }).then(async (response) => {
        if (!response.ok) throw new ApiProblem(response.status, 'A CSRF token could not be issued.')
        return await response.json() as CsrfToken
      }).catch((error) => {
        csrfTokenRequest = undefined
        throw error
      })
      const csrf = await csrfTokenRequest
      headers.set(csrf.headerName, csrf.token)
    }
    const response = await fetch(`${API_BASE}${path}`, {
      credentials: 'include',
      ...init,
      headers,
      signal: controller.signal,
    })
    if (!response.ok) {
      const problem = await response.json().catch(() => ({ detail: response.statusText })) as { detail?: string }
      throw new ApiProblem(response.status, problem.detail ?? 'The request could not be completed.')
    }
    if (response.status === 204) return undefined as T
    return await response.json() as T
  } finally {
    window.clearTimeout(timeout)
  }
}

/** Shared same-origin transport for domain-specific clients such as the Research Laboratory. */
export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  return request<T>(path, init)
}

const unavailableWorkspace: WorkspaceData = {
  currentUser: { name: 'Not signed in', initials: 'UG', roles: [], department: 'University research workspace' },
  project: null,
  projects: [], studies: [], traceNodes: [], traceEdges: [], graphTruncated: false, graphTotalNodes: 0, graphTotalEdges: 0, findings: [], health: [], reviewQueue: [], lineage: [], generatedAt: '',
}

function coerceWorkspace(payload: Partial<WorkspaceData>): WorkspaceData {
  const projects = payload.projects ?? []
  return {
    currentUser: payload.currentUser ?? unavailableWorkspace.currentUser,
    project: payload.project ?? null,
    projects,
    studies: payload.studies ?? [],
    traceNodes: payload.traceNodes ?? [],
    traceEdges: payload.traceEdges ?? [],
    graphTruncated: payload.graphTruncated ?? false,
    graphTotalNodes: payload.graphTotalNodes ?? payload.traceNodes?.length ?? 0,
    graphTotalEdges: payload.graphTotalEdges ?? payload.traceEdges?.length ?? 0,
    findings: payload.findings ?? [],
    health: payload.health ?? [],
    reviewQueue: payload.reviewQueue ?? [],
    lineage: payload.lineage ?? [],
    generatedAt: payload.generatedAt ?? '',
  }
}

export interface WorkspaceEnvelope {
  data: WorkspaceData
  source: 'LIVE' | 'UNAVAILABLE'
}

export interface AuthSession {
  authenticated: boolean
  email: string | null
  roles: string[]
}

export interface AuthSessionEnvelope {
  session: AuthSession
  source: 'LIVE' | 'UNAVAILABLE'
}

export interface UserAdminRecord {
  id: string
  email: string
  displayName: string
  department: string | null
  status: string
  roles: string[]
}

export interface InvitationAdminRecord {
  id: string
  email: string
  intendedRole: string
  expiresAt: string
  acceptedAt: string | null
  createdAt: string
  oneTimeToken?: string
}

export interface ProjectMembershipRecord {
  userId: string
  email: string
  displayName: string
  role: string
  joinedAt: string
}

export interface CatalogueStudySummary {
  id: string
  institutionalCode: string
  title: string
  academicYear?: string | null
  completionYear?: number | null
  departmentCode?: string | null
  departmentName?: string | null
  program?: string | null
  lifecycleStatus: string
  visibility: string
  abstractText?: string | null
  problemStatement?: string | null
  methodology?: string | null
  keywords: string[]
  resultsText?: string | null
  objectiveCount: number
}

export interface CatalogueSearchPage {
  items: CatalogueStudySummary[]
  totalItems: number
  page: number
  pageSize: number
  generatedAt: string
}

export interface CatalogueSearchFilters {
  q?: string
  department?: string
  yearFrom?: number
  yearTo?: number
  lifecycle?: string
  topic?: string
  page?: number
  size?: number
  sort?: 'YEAR_DESC' | 'YEAR_ASC' | 'TITLE_ASC' | 'TITLE_DESC'
}

export async function searchCatalogue(filters: CatalogueSearchFilters): Promise<CatalogueSearchPage> {
  const params = new URLSearchParams()
  if (filters.q) params.set('q', filters.q)
  if (filters.department) params.set('department', filters.department)
  if (filters.yearFrom != null) params.set('yearFrom', String(filters.yearFrom))
  if (filters.yearTo != null) params.set('yearTo', String(filters.yearTo))
  if (filters.lifecycle) params.set('lifecycle', filters.lifecycle)
  if (filters.topic) params.set('topic', filters.topic)
  params.set('page', String(filters.page ?? 0))
  params.set('size', String(filters.size ?? 20))
  params.set('sort', filters.sort ?? 'YEAR_DESC')
  return request<CatalogueSearchPage>(`/catalogue/search?${params.toString()}`)
}

export interface LoginInput {
  email: string
  password: string
}

const anonymousSession: AuthSession = { authenticated: false, email: null, roles: [] }

export async function getAuthSession(): Promise<AuthSessionEnvelope> {
  try {
    const session = await request<AuthSession>('/auth/me')
    return { session, source: 'LIVE' }
  } catch {
    return { session: anonymousSession, source: 'UNAVAILABLE' }
  }
}

export async function login(input: LoginInput): Promise<AuthSession> {
  const session = await request<AuthSession>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  csrfTokenRequest = undefined
  return session
}

export async function logout(): Promise<void> {
  await request<void>('/auth/logout', { method: 'POST' })
  csrfTokenRequest = undefined
}

export async function listUsers(): Promise<UserAdminRecord[]> {
  return request<UserAdminRecord[]>('/users')
}

export async function listInvitations(): Promise<InvitationAdminRecord[]> {
  return request<InvitationAdminRecord[]>('/invitations')
}

export async function createInvitation(email: string, role: string): Promise<InvitationAdminRecord> {
  return request<InvitationAdminRecord>('/invitations', { method: 'POST', body: JSON.stringify({ email, role }) })
}

export async function listProjectMemberships(projectId: string): Promise<ProjectMembershipRecord[]> {
  return request<ProjectMembershipRecord[]>(`/projects/${projectId}/memberships`)
}

export async function grantProjectMembership(projectId: string, userId: string, role: string): Promise<ProjectMembershipRecord> {
  return request<ProjectMembershipRecord>(`/projects/${projectId}/memberships`, { method: 'POST', body: JSON.stringify({ userId, role }) })
}

export async function getWorkspace(projectId?: string): Promise<WorkspaceEnvelope> {
  try {
    const query = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
    const payload = await request<Partial<WorkspaceData> | { data: Partial<WorkspaceData> }>(`/workspace${query}`)
    const data = 'data' in payload ? payload.data : payload
    return { data: coerceWorkspace(data), source: 'LIVE' }
  } catch {
    return { data: unavailableWorkspace, source: 'UNAVAILABLE' }
  }
}

export async function runDiscovery(input: DiscoveryInput): Promise<DiscoveryRun> {
  return request<DiscoveryRun>('/discovery-runs', { method: 'POST', body: JSON.stringify(input) })
}

export type { EvidenceReferenceType } from '../types/domain'

export interface IntakeEvidenceReferenceInput {
  type: EvidenceReferenceType
  label: string
  location?: string
  storedDocumentId?: string
  sha256?: string
}

export interface PersistedIntakeInput {
  problem: {
    title: string
    problemStatement: string
    stakeholder: string
    affectedUsers: string
    siteContext: string
    desiredOutcome: string
    constraints?: string
    privacyClassification: 'PUBLIC' | 'INTERNAL' | 'RESTRICTED'
  }
  proposal: {
    title: string
    objectives: string[]
    proposedSolution: string
    methodology?: string
    dataSources?: string
    technology?: string
    intendedUsers?: string
  }
  evidenceReferences?: IntakeEvidenceReferenceInput[]
}

export interface IntakeResult {
  idempotencyKey: string
  replayed: boolean
  problem: { id: string }
  proposal: ProposalRecord
  discovery: DiscoveryRun
}

export async function submitIntakeForDiscovery(input: PersistedIntakeInput, idempotencyKey: string): Promise<IntakeResult> {
  return request<IntakeResult>('/intakes', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(input),
  })
}

export async function listStudies(): Promise<Study[]> {
  try {
    const payload = await request<Study[] | { content: Study[] }>('/studies')
    return Array.isArray(payload) ? payload : payload.content
  } catch {
    return []
  }
}

export interface StudyMetadataInput {
  institutionalCode: string
  title: string
  academicYear: string
  abstractText: string
  problemStatement: string
  objectives: string[]
  keywords: string[]
  methodology: string
  features: string
  stakeholders: string
  siteContext: string
  department?: string
  program?: string
  authors?: string[]
  doi?: string
  repositoryIdentifier?: string
  dataSources?: string
  technology?: string
  intendedUsers?: string
  resultsText?: string
  researchAreas?: string[]
  visibility?: 'PUBLIC' | 'CAMPUS' | 'RESTRICTED' | 'EMBARGOED'
  lifecycleStatus?: 'PUBLISHED' | 'COMPLETED' | 'INCOMPLETE' | 'SUSPENDED' | 'ARCHIVED'
}

export interface DocumentImportAccepted {
  jobId: string
  documentId: string
  documentVersionId: string
  status: string
  statusUrl: string
  eventsUrl: string
  queuedAt: string
}

export interface DocumentImportJob {
  jobId: string
  documentId: string
  documentVersionId: string
  status: string
  queuedAt: string
  originalFilename: string
  mimeType: string
  byteSize: number
  sha256: string
  scanStatus: string
  storageStatus: string
  objectKey: string
  storageEtag: string | null
  progressPercent: number
  pageCount: number
  extractedCharacterCount: number
  maxCharacterCount: number
  timeoutSeconds: number
  attemptCount: number
  manualReviewRequired: boolean
  publicationEligible: boolean
  failureReason: string | null
  textPreview: string
  uploaderEmail: string
  startedAt: string | null
  completedAt: string | null
}

export async function importStudyMetadata(input: StudyMetadataInput): Promise<unknown> {
  return request('/imports/studies', { method: 'POST', body: JSON.stringify(input) })
}

export async function publishExtractedStudy(jobId: string, input: StudyMetadataInput): Promise<unknown> {
  return request(`/imports/documents/jobs/${encodeURIComponent(jobId)}/publish-study`, {
    method: 'POST', body: JSON.stringify(input),
  })
}

export async function uploadStudyDocument(file: File): Promise<DocumentImportAccepted> {
  const body = new FormData()
  body.append('file', file)
  return request<DocumentImportAccepted>('/imports/documents', { method: 'POST', body })
}

export async function getDocumentImportJob(jobId: string): Promise<DocumentImportJob> {
  return request<DocumentImportJob>(`/imports/documents/jobs/${jobId}`)
}

interface ProjectRecord {
  id: string
  rowVersion: number
}

export interface TraceItemInput {
  key: string
  type: TraceItemType
  title: string
  description: string
  priority?: string | null
  acceptanceCriteria?: string | null
  verificationMethod?: string | null
}

export interface TraceLinkInput {
  sourceId: string
  targetId: string
  relationshipType: string
  rationale: string
}

export interface TestExecutionInput {
  testItemId: string
  status: 'PASSED' | 'FAILED' | 'BLOCKED'
  buildIdentifier: string
  evidenceConfirmed: boolean
}

export interface CompletionEvidenceInput {
  repositoryUrl: string
  commitHash: string
  setupInstructions: string
  limitations: string[]
  recommendations: string[]
  unfinishedWork: string[]
  evidenceReferences: Array<{
    type: EvidenceReferenceType
    label: string
    location?: string
    storedDocumentId?: string
    sha256?: string
  }>
}

export interface AuthoringResult<T> {
  project: ProjectRecord
  artifact: T
  traceability: ProjectTraceability
}

export interface ProposalRecord {
  id: string
  title: string
  problemStatement: string
  objectives: string[]
  stakeholder: string
  siteContext: string
  submittedAt: string
}

export interface DiscoveryRecord {
  id: string
  proposalId: string
  assessmentStatus?: AssessmentStatus
  status?: AssessmentStatus
  recommendation: string
  confidenceState: AssessmentStatus
  confidence: number | null
  algorithmVersion: string
  explanation: string
  candidates: Array<{ studyId: string; problemScore: number; objectiveScore: number; solutionScore: number; confidence: number }>
  createdAt: string
}

export interface DecisionRecord {
  id?: string
  proposalId: string
  discoveryRunId?: string
  disposition?: DecisionDisposition
  rationale?: string
  primaryPredecessorId?: string | null
  decidedAt?: string
  decidedBy?: string
}

export interface ChangeRequestRecord {
  id: string
  projectId: string
  basedOnBaselineId: string
  title: string
  rationale: string
  status: string
  changedItemIds: string[]
  boundaryFlags: string[]
  createdAt: string
}

export interface ImpactPreviewRecord {
  changeRequestId: string
  basedOnBaselineId: string
  operationSetVersion: number
  operationSetSha256: string
  baselineCurrent: boolean
  scopeRisk: { status: AssessmentStatus; score: number | null; band: string | null; governance: number | null; alignment: number | null; controlledGrowth: number | null; boundary: number | null; explanations: string[] }
  impactedArtifacts: Array<{ itemId: string; itemKey: string; itemType: TraceItemType; title: string; hopCount: number; severity: string; evidenceBecomesStale: boolean; reason: string }>
  documentsToRevise: string[]
  calculatedAt: string
}

export interface ChangeContext {
  change: ChangeRequestRecord | null
  impact: ImpactPreviewRecord | null
}

export type ChangeOperationType = 'ADD' | 'REVISE' | 'RETIRE' | 'RELINK'
export type ChangeDecision = 'approve' | 'reject' | 'return-for-revision'

export interface ChangeOperationInput {
  type: ChangeOperationType
  targetItemId?: string | null
  itemType?: TraceItemType | null
  itemKey?: string | null
  title?: string | null
  description?: string | null
  priority?: string | null
  acceptanceCriteria?: string | null
  verificationMethod?: string | null
  sourceItemId?: string | null
  linkTargetItemId?: string | null
  relationshipType?: string | null
  removeRelationship?: boolean
  rationale: string
}

export interface ChangeOperationRecord extends ChangeOperationInput {
  id: string
  changeRequestId: string
  order: number
}

export interface AcademicDecisionInput {
  proposalId: string
  discoveryRunId: string
  disposition: DecisionDisposition
  rationale: string
  primaryPredecessorId?: string
}

export interface AcademicDecisionResult {
  id: string
  disposition: AcademicDecisionInput['disposition']
  decidedAt: string
}

export interface DecisionContext {
  proposal: ProposalRecord | null
  proposalObjectives: ProposalObjectiveRecord[]
  discovery: DiscoveryRecord | null
  candidateStudies: Study[]
  decision: DecisionRecord | null
  adviserRecommendations: AdviserRecommendationRecord[]
}

export interface ProposalObjectiveRecord {
  id: string
  order: number
  statement: string
}

export interface AdviserRecommendationRecord {
  id: string
  proposalId: string
  discoveryRunId: string
  recommendation: 'NEW' | 'IMPROVE' | 'CONTINUE' | 'POSSIBLE_DUPLICATE' | 'REVIEW_REQUIRED'
  rationale: string
  adviser: string
  recordedAt: string
}

export interface RouteEvidenceAssessment {
  continuationState: AssessmentStatus
  continuationCoverage?: number | null
  codeAccess?: boolean | null
  dataAccess?: boolean | null
  improvementState: AssessmentStatus
  improvementClaimCount?: number | null
}

export interface AuthorizedStudyDetail {
  id: string
  institutionalCode: string
  title: string
  academicYear: string
  department: string
  lifecycleStatus: string
  visibility: string
  abstractText: string
  problemStatement: string
  objectives: string[]
  keywords: string[]
  continuationItems: ContinuationItemRecord[]
}

export interface ObjectiveContinuationLinkInput {
  proposalObjectiveId: string
  continuationItemId: string
  rationale: string
}

export interface ContinuationEvidenceInput {
  predecessorStudyId: string
  objectiveLinks: ObjectiveContinuationLinkInput[]
  codeAccessConfirmed: boolean
  dataAccessConfirmed: boolean
  accessNotes: string
}

export interface ImprovementClaimInput {
  predecessorStudyId: string
  continuationItemId: string
  claim: string
  baselineMeasure: string
  targetMeasure: string
  evaluationMethod: string
}

export function listDecisionProposals() {
  return request<ProposalRecord[]>('/proposals')
}

export function listDiscoveryRuns() {
  return request<DiscoveryRecord[]>('/discovery-runs')
}

export async function getDecisionContext(proposalId: string, discoveryRunId: string): Promise<DecisionContext> {
  return request<DecisionContext>(`/proposals/${encodeURIComponent(proposalId)}/decision-context/${encodeURIComponent(discoveryRunId)}`)
}

export async function getAdviserRecommendations(proposalId: string) {
  return request<AdviserRecommendationRecord[]>(`/proposals/${encodeURIComponent(proposalId)}/adviser-recommendations`)
}

export async function recordAdviserRecommendation(
  proposalId: string,
  discoveryRunId: string,
  recommendation: AdviserRecommendationRecord['recommendation'],
  rationale: string,
) {
  return request<AdviserRecommendationRecord>(`/proposals/${encodeURIComponent(proposalId)}/adviser-recommendations`, {
    method: 'POST', body: JSON.stringify({ discoveryRunId, recommendation, rationale }),
  })
}

export async function getRouteEvidence(proposalId: string, predecessorId: string) {
  return request<RouteEvidenceAssessment>(`/proposals/${encodeURIComponent(proposalId)}/route-evidence/${encodeURIComponent(predecessorId)}`)
}

export async function getAuthorizedStudyDetail(studyId: string) {
  return request<AuthorizedStudyDetail>(`/studies/${encodeURIComponent(studyId)}`)
}

export async function recordContinuationEvidence(proposalId: string, input: ContinuationEvidenceInput) {
  return request<unknown>(`/proposals/${encodeURIComponent(proposalId)}/continuation-evidence`, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export async function recordImprovementClaim(proposalId: string, input: ImprovementClaimInput) {
  return request<unknown>(`/proposals/${encodeURIComponent(proposalId)}/improvement-claims`, {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export interface CompletionAssessment {
  projectId: string
  eligible: boolean
  blockers: string[]
  evaluatedAt: string
}

async function exactProjectEtag(projectId: string): Promise<string> {
  const project = await request<ProjectRecord>(`/projects/${projectId}`)
  return `"${project.rowVersion}"`
}

export async function getProjectTraceability(projectId: string): Promise<ProjectTraceability> {
  return request<ProjectTraceability>(`/projects/${projectId}/traceability`)
}

export async function getCompletionPackage(projectId: string): Promise<CompletionPackageRecord> {
  return request<CompletionPackageRecord>(`/projects/${projectId}/completion-package`)
}

export async function getCompletionEvidenceReferences(projectId: string): Promise<EvidenceReferenceRecord[]> {
  return request<EvidenceReferenceRecord[]>(`/projects/${projectId}/completion-package/evidence-references`)
}

export interface ContinuationItemRecord {
  id: string
  studyId: string
  type: string
  title: string
  description: string
  status: string
  claimed: boolean
}

export async function getContinuationItems(projectId: string): Promise<ContinuationItemRecord[]> {
  return request<ContinuationItemRecord[]>(`/continuation-items?projectId=${encodeURIComponent(projectId)}`)
}

export interface ProjectReviewRecord {
  id: string
  projectId: string
  type: string
  title: string
  projectCode: string
  severity: 'INFO' | 'WARNING' | 'HIGH' | 'CRITICAL'
  requiredRole: string
  reason: string
  dueAt: string
  status: string
  history: ProjectReviewHistoryRecord[]
}

export interface ProjectReviewHistoryRecord {
  id: string
  eventType: string
  message: string
  evidenceLocation?: string | null
  actorEmail: string
  createdAt: string
}

export interface ReviewRevisionInput {
  message: string
  evidenceLocation?: string
}

export interface ReviewRevisionResult {
  project: ProjectRecord
  review: ProjectReviewRecord
}

export async function getProjectReviewQueue(projectId: string): Promise<ProjectReviewRecord[]> {
  return request<ProjectReviewRecord[]>(`/projects/${encodeURIComponent(projectId)}/reviews`)
}

export async function requestReviewRevision(projectId: string, reviewId: string, input: ReviewRevisionInput): Promise<ReviewRevisionResult> {
  const etag = await exactProjectEtag(projectId)
  return request<ReviewRevisionResult>(`/projects/${encodeURIComponent(projectId)}/reviews/${encodeURIComponent(reviewId)}/revision-requests`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify({ message: input.message, ...(input.evidenceLocation ? { evidenceLocation: input.evidenceLocation } : {}) }),
  })
}

export async function submitReviewRevisionResponse(projectId: string, reviewId: string, input: ReviewRevisionInput): Promise<ReviewRevisionResult> {
  const etag = await exactProjectEtag(projectId)
  return request<ReviewRevisionResult>(`/projects/${encodeURIComponent(projectId)}/reviews/${encodeURIComponent(reviewId)}/revision-responses`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify({ message: input.message, ...(input.evidenceLocation ? { evidenceLocation: input.evidenceLocation } : {}) }),
  })
}

export async function claimContinuationItem(projectId: string, continuationItemId: string, successorObjectiveId: string, rationale: string): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/projects/${projectId}/continuation-claims`, { method: 'POST', headers: { 'If-Match': etag },
    body: JSON.stringify({ continuationItemId, successorObjectiveId, rationale }) })
}

export async function createTraceItem(projectId: string, input: TraceItemInput): Promise<AuthoringResult<unknown>> {
  const etag = await exactProjectEtag(projectId)
  return request<AuthoringResult<unknown>>(`/projects/${projectId}/trace-items`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify(input),
  })
}

export async function createTraceLink(projectId: string, input: TraceLinkInput): Promise<AuthoringResult<unknown>> {
  const etag = await exactProjectEtag(projectId)
  return request<AuthoringResult<unknown>>(`/projects/${projectId}/trace-links`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify(input),
  })
}

export async function recordTestExecution(projectId: string, input: TestExecutionInput): Promise<AuthoringResult<unknown>> {
  const etag = await exactProjectEtag(projectId)
  return request<AuthoringResult<unknown>>(`/projects/${projectId}/test-executions`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify(input),
  })
}

export async function approveProjectBaseline(projectId: string, rationale: string): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/projects/${projectId}/baselines/approve`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify({ rationale }),
  })
}

export async function updateCompletionEvidence(
  projectId: string,
  input: CompletionEvidenceInput,
): Promise<AuthoringResult<CompletionPackageRecord>> {
  const etag = await exactProjectEtag(projectId)
  return request<AuthoringResult<CompletionPackageRecord>>(`/projects/${projectId}/completion-package/evidence`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify(input),
  })
}

export async function recordAcademicDecision(input: AcademicDecisionInput): Promise<AcademicDecisionResult> {
  return request<AcademicDecisionResult>('/proposal-decisions', {
    method: 'POST',
    body: JSON.stringify({
      proposalId: input.proposalId,
      discoveryRunId: input.discoveryRunId,
      disposition: input.disposition,
      rationale: input.rationale,
      primaryPredecessorId: input.disposition === 'APPROVE_IMPROVE' || input.disposition === 'APPROVE_CONTINUE' || input.disposition === 'CLOSE_AS_DUPLICATE'
        ? input.primaryPredecessorId
        : null,
    }),
  })
}

export async function rerunProjectAnalysis(projectId: string): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/projects/${projectId}/analysis-runs`, {
    method: 'POST',
    headers: { 'If-Match': etag },
  })
}

export type FindingAction = 'resolve' | 'accept' | 'reopen'

export async function actOnFinding(
  projectId: string,
  findingId: string,
  action: FindingAction,
  rationale: string,
  expiresAt?: string,
): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/projects/${projectId}/findings/${findingId}/${action}`, {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify({ rationale, expiresAt: expiresAt || null }),
  })
}

export async function previewChangeImpact(projectId: string, changeRequestId: string): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/change-requests/${changeRequestId}/preview-impact`, {
    method: 'POST',
    headers: { 'If-Match': etag },
  })
}

export async function listProjectChangeRequests(projectId: string): Promise<ChangeRequestRecord[]> {
  const changes = await request<ChangeRequestRecord[]>('/change-requests')
  return changes.filter((candidate) => candidate.projectId === projectId)
}

export async function getChangeContext(changeRequest: ChangeRequestRecord): Promise<ChangeContext> {
  const impact = await request<ImpactPreviewRecord>(`/change-requests/${changeRequest.id}/impact`).catch((error) => {
    if (error instanceof ApiProblem && error.status === 404) return null
    throw error
  })
  const change = changeRequest
  return { change, impact }
}

export async function createChangeRequest(projectId: string, title: string, rationale: string, changedItemId: string): Promise<ChangeRequestRecord> {
  const [etag, trace] = await Promise.all([exactProjectEtag(projectId), getProjectTraceability(projectId)])
  if (!trace.baselineId) throw new ApiProblem(409, 'Approve a project baseline before proposing a controlled change.')
  return request<ChangeRequestRecord>('/change-requests', {
    method: 'POST',
    headers: { 'If-Match': etag },
    body: JSON.stringify({ projectId, basedOnBaselineId: trace.baselineId, title, rationale, changedItemIds: [changedItemId], boundaryFlags: [] }),
  })
}

export async function getChangeOperations(changeRequestId: string): Promise<ChangeOperationRecord[]> {
  return request<ChangeOperationRecord[]>(`/change-requests/${changeRequestId}/operations`)
}

export async function addChangeOperation(projectId: string, changeRequestId: string, input: ChangeOperationInput): Promise<ChangeOperationRecord> {
  const etag = await exactProjectEtag(projectId)
  return request<ChangeOperationRecord>(`/change-requests/${changeRequestId}/operations`, {
    method: 'POST', headers: { 'If-Match': etag }, body: JSON.stringify(input),
  })
}

export async function decideChangeRequest(projectId: string, changeRequestId: string, decision: ChangeDecision, rationale: string, operationSetVersion?: number): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/change-requests/${changeRequestId}/${decision}`, {
    method: 'POST', headers: { 'If-Match': etag }, body: JSON.stringify({ rationale, ...(decision === 'approve' ? { operationSetVersion } : {}) }),
  })
}

export async function assessProjectCompletion(projectId: string): Promise<CompletionAssessment> {
  const etag = await exactProjectEtag(projectId)
  return request<CompletionAssessment>(`/projects/${projectId}/complete`, {
    method: 'POST',
    headers: { 'If-Match': etag },
  })
}
