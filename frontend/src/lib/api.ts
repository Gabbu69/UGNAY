import type {
  CompletionPackageRecord,
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

const unavailableProject: WorkspaceData['project'] = {
  id: 'unavailable', code: 'UNASSESSED', title: 'No persisted project is available', stage: 'UNASSESSED',
  route: 'REVIEW_REQUIRED', department: 'Unavailable', adviser: 'Unassigned', updatedAt: '', openFindings: 0, health: 0,
}

const unavailableWorkspace: WorkspaceData = {
  currentUser: { name: 'Not signed in', initials: 'UG', roles: [], department: 'University research workspace' },
  project: unavailableProject,
  projects: [], studies: [], traceNodes: [], traceEdges: [], findings: [], health: [], reviewQueue: [], lineage: [], generatedAt: '',
}

function coerceWorkspace(payload: Partial<WorkspaceData>): WorkspaceData {
  const projects = payload.projects ?? []
  return {
    currentUser: payload.currentUser ?? unavailableWorkspace.currentUser,
    project: payload.project ?? projects[0] ?? unavailableProject,
    projects,
    studies: payload.studies ?? [],
    traceNodes: payload.traceNodes ?? [],
    traceEdges: payload.traceEdges ?? [],
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
  try {
    return await request<DiscoveryRun>('/discovery-runs', { method: 'POST', body: JSON.stringify(input) })
  } catch {
    return {
      id: `demo-run-${Date.now()}`,
      status: 'PARTIAL',
      recommendation: 'REVIEW_REQUIRED',
      confidence: 0,
      candidates: [],
      algorithmVersion: 'UNAVAILABLE-NOT-ASSESSED',
    }
  }
}

export interface PersistedIntakeInput {
  title: string
  problemStatement: string
  stakeholder: string
  siteContext: string
  objectives: string[]
}

export async function submitIntakeForDiscovery(input: PersistedIntakeInput): Promise<DiscoveryRun> {
  const problem = await request<{ id: string }>('/problems', { method: 'POST', body: JSON.stringify({
    title: input.title,
    problemStatement: input.problemStatement,
    stakeholder: input.stakeholder,
    affectedUsers: input.stakeholder,
    siteContext: input.siteContext,
    desiredOutcome: input.objectives.join(' '),
    constraints: 'No constraints recorded in the current intake.',
    privacyClassification: 'INTERNAL',
    evidenceCount: 0,
  }) })
  const proposal = await request<{ id: string }>('/proposals', { method: 'POST', body: JSON.stringify({
    problemId: problem.id,
    title: input.title,
    objectives: input.objectives,
    proposedSolution: 'Solution approach intentionally deferred until academic review.',
    methodology: 'Not yet assessed',
    dataSources: 'No data sources recorded',
    technology: 'Not yet selected',
    intendedUsers: input.stakeholder,
  }) })
  return request<DiscoveryRun>('/discovery-runs', { method: 'POST', body: JSON.stringify({ proposalId: proposal.id }) })
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
  codeDataRightsConfirmed: boolean
  repositoryUrl: string
  commitHash: string
  setupInstructions: string
  limitations: string[]
  recommendations: string[]
  unfinishedWork: string[]
  criteria: Array<{ key: string; completion: number; explanation: string }>
}

export interface AuthoringResult<T> {
  project: ProjectRecord
  artifact: T
  traceability: ProjectTraceability
}

interface ProposalRecord {
  id: string
  title: string
  problemStatement: string
  objectives: string[]
  stakeholder: string
  siteContext: string
  submittedAt: string
}

interface DiscoveryRecord {
  id: string
  proposalId: string
  assessmentStatus: string
  recommendation: string
  confidence: number
  algorithmVersion: string
  explanation: string
  candidates: Array<{ studyId: string; problemScore: number; objectiveScore: number; solutionScore: number; confidence: number }>
  createdAt: string
}

interface DecisionRecord {
  proposalId: string
}

export interface ChangeRequestRecord {
  id: string
  projectId: string
  basedOnBaselineId: string | null
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
  baselineCurrent: boolean
  scopeRisk: { status: string; score: number | null; band: string | null; governance: number; alignment: number; controlledGrowth: number; boundary: number; explanations: string[] }
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
  discovery: DiscoveryRecord | null
  decided: boolean
}

export async function getDecisionContext(): Promise<DecisionContext> {
  const [proposals, runs, decisions] = await Promise.all([
    request<ProposalRecord[]>('/proposals'), request<DiscoveryRecord[]>('/discovery-runs'), request<DecisionRecord[]>('/proposal-decisions'),
  ])
  const decidedIds = new Set(decisions.map((item) => item.proposalId))
  const discovery = runs.find((run) => proposals.some((proposal) => proposal.id === run.proposalId) && !decidedIds.has(run.proposalId)) ?? runs[0] ?? null
  const proposal = discovery ? proposals.find((item) => item.id === discovery.proposalId) ?? null : null
  return { proposal, discovery, decided: Boolean(discovery && decidedIds.has(discovery.proposalId)) }
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

export interface ContinuationItemRecord {
  id: string
  studyId: string
  type: string
  title: string
  description: string
  status: string
  claimed: boolean
}

export async function getContinuationItems(): Promise<ContinuationItemRecord[]> {
  return request<ContinuationItemRecord[]>('/continuation-items')
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
  const [proposals, runs, decisions] = await Promise.all([
    request<ProposalRecord[]>('/proposals'),
    request<DiscoveryRecord[]>('/discovery-runs'),
    request<DecisionRecord[]>('/proposal-decisions'),
  ])
  const proposalIds = new Set(proposals.map((proposal) => proposal.id))
  const decidedProposalIds = new Set(decisions.map((decision) => decision.proposalId))
  const discovery = runs.find((run) => proposalIds.has(run.proposalId) && !decidedProposalIds.has(run.proposalId))
  if (!discovery) throw new ApiProblem(409, 'No submitted proposal with a completed discovery run is available for a formal decision.')
  return request<AcademicDecisionResult>('/proposal-decisions', {
    method: 'POST',
    body: JSON.stringify({
      proposalId: discovery.proposalId,
      discoveryRunId: discovery.id,
      disposition: input.disposition,
      rationale: input.rationale,
      primaryPredecessorId: input.disposition === 'APPROVE_IMPROVE' || input.disposition === 'APPROVE_CONTINUE'
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

export async function previewLatestChangeImpact(projectId: string): Promise<unknown> {
  const changes = await request<ChangeRequestRecord[]>('/change-requests')
  const change = changes.find((candidate) => candidate.projectId === projectId)
  if (!change) throw new ApiProblem(409, 'This project has no change request awaiting an impact preview.')
  const etag = await exactProjectEtag(projectId)
  return request(`/change-requests/${change.id}/preview-impact`, {
    method: 'POST',
    headers: { 'If-Match': etag },
  })
}

export async function getLatestChangeContext(projectId: string): Promise<ChangeContext> {
  const changes = await request<ChangeRequestRecord[]>('/change-requests')
  const change = changes.filter((candidate) => candidate.projectId === projectId)
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0] ?? null
  if (!change) return { change: null, impact: null }
  const impact = await request<ImpactPreviewRecord>(`/change-requests/${change.id}/impact`).catch(() => null)
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

export async function decideChangeRequest(projectId: string, changeRequestId: string, decision: ChangeDecision, rationale: string): Promise<unknown> {
  const etag = await exactProjectEtag(projectId)
  return request(`/change-requests/${changeRequestId}/${decision}`, {
    method: 'POST', headers: { 'If-Match': etag }, body: JSON.stringify({ rationale }),
  })
}

export async function assessProjectCompletion(projectId: string): Promise<CompletionAssessment> {
  const etag = await exactProjectEtag(projectId)
  return request<CompletionAssessment>(`/projects/${projectId}/complete`, {
    method: 'POST',
    headers: { 'If-Match': etag },
  })
}
