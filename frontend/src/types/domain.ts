export type Recommendation = 'NEW' | 'IMPROVE' | 'CONTINUE' | 'POSSIBLE_DUPLICATE' | 'REVIEW_REQUIRED'
export type DecisionDisposition = 'APPROVE_NEW' | 'APPROVE_IMPROVE' | 'APPROVE_CONTINUE' | 'RETURN_FOR_REVISION' | 'CLOSE_AS_DUPLICATE'
export type TraceItemType = 'PROBLEM' | 'OBJECTIVE' | 'REQUIREMENT' | 'FEATURE' | 'TEST_CASE' | 'OUTPUT'
export type AssessmentStatus = 'ASSESSED' | 'UNASSESSED' | 'UNAVAILABLE' | 'STALE' | 'PARTIAL'
export type FindingState = 'OPEN' | 'ACCEPTED' | 'RESOLVED' | 'REOPENED'
export type LineageType = 'CONTINUES' | 'IMPROVES' | 'ADAPTS' | 'REPLICATES' | 'REFERENCES'
export type RiskLevel = 'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL'

export interface Study {
  id: string
  code: string
  title: string
  year: number
  program: string
  status: 'COMPLETED' | 'INCOMPLETE' | 'SUSPENDED'
  abstract: string
  authors: string[]
  keywords: string[]
  problemSimilarity: number | null
  solutionSimilarity: number | null
  objectiveOverlap: number | null
  confidence: number | null
  relationship: LineageType | 'SIMILAR' | 'UNAVAILABLE'
  matchReason: string
  excerpt: string
  restricted?: boolean
}

export interface ProjectSummary {
  id: string
  code: string
  title: string
  stage: string
  route: Recommendation
  department: string
  adviser: string
  updatedAt: string
  openFindings: number
  health: number | null
}

export interface TraceNode {
  id: string
  code: string
  label: string
  type: TraceItemType
  status: 'APPROVED' | 'DRAFT' | 'STALE' | 'PASSING' | 'MISSING'
  readiness?: number
  priority?: 'MUST' | 'SHOULD' | 'COULD'
}

export interface TraceEdge {
  id: string
  source: string
  target: string
  relationship: 'ADDRESSES' | 'DERIVES' | 'REALIZES' | 'VERIFIES' | 'PRODUCES'
}

export interface TraceItemRecord {
  id: string
  key: string
  type: TraceItemType
  title: string
  description: string
  lifecycleStatus: string
  priority: string | null
  acceptanceCriteria: string | null
  verificationMethod: string | null
  currentRevision: number
  readinessScore: number
}

export interface TraceLinkRecord {
  id: string
  sourceId: string
  targetId: string
  type: string
  status: string
  rationale: string
}

export interface TestExecutionRecord {
  id: string
  testItemId: string
  status: 'PASSED' | 'FAILED' | 'BLOCKED'
  buildIdentifier: string
  current: boolean
  hasEvidence: boolean
  executedAt: string
}

export interface ProjectTraceability {
  projectId: string
  baselineId: string | null
  baselineNumber: number
  assessmentStatus: AssessmentStatus
  items: TraceItemRecord[]
  links: TraceLinkRecord[]
  executions: TestExecutionRecord[]
  findings: unknown[]
  coverage: {
    mappedCoverage: number
    executedCoverage: number
    passingCoverage: number
    priorityWeightedPassingCoverage: number
    totalRequirements: number
    verifiedRequirements: number
  }
}

export interface ContinuityCriterionRecord {
  key: string
  label: string
  weight: number
  state: 'ASSESSED' | 'PARTIAL' | 'UNASSESSED' | 'UNAVAILABLE'
  value?: number | null
  source?: string | null
  assessedAt?: string | null
  explanation: string
}

export type EvidenceReferenceType = 'DOCUMENT' | 'URL' | 'REPOSITORY' | 'OUTPUT' | 'TEST_RUN' | 'DATASET' | 'OTHER'

export interface EvidenceReferenceRecord {
  id: string
  type: EvidenceReferenceType
  label: string
  location?: string | null
  storedDocumentId?: string | null
  sha256?: string | null
  verificationState: 'VERIFIED' | 'UNVERIFIED' | 'UNAVAILABLE' | string
  capturedAt?: string | null
  capturedBy?: string | null
}

export interface CompletionPackageRecord {
  id: string
  projectId: string
  status: string
  readinessState: 'ASSESSED' | 'PARTIAL' | 'UNASSESSED' | 'UNAVAILABLE'
  readinessScore: number | null
  codeDataRightsConfirmed: boolean
  criteria: ContinuityCriterionRecord[]
  blockers: string[]
  repositoryUrl: string
  commitHash: string
  setupInstructions: string
  limitations: string[]
  recommendations: string[]
  unfinishedWork: string[]
}

export interface Finding {
  id: string
  code: string
  rule: string
  title: string
  explanation: string
  evidence: string[]
  severity: 'INFO' | 'WARNING' | 'HIGH' | 'CRITICAL'
  state: FindingState
  nextAction: string
  itemCode: string
}

export interface HealthDimension {
  id: string
  label: string
  state: AssessmentStatus
  score: number | null
  delta: number
  detail: string
}

export interface ReviewItem {
  id: string
  eyebrow: string
  title: string
  summary: string
  due: string
  risk: RiskLevel
  owner: string
  action: string
}

export interface LineageNode {
  id: string
  code: string
  title: string
  year: number
  relation: LineageType | 'ORIGIN'
  state: 'COMPLETE' | 'ACTIVE' | 'AVAILABLE'
  inherited: string[]
}

export interface WorkspaceData {
  currentUser: {
    name: string
    initials: string
    roles: string[]
    department: string
  }
  project: ProjectSummary | null
  projects: ProjectSummary[]
  studies: Study[]
  traceNodes: TraceNode[]
  traceEdges: TraceEdge[]
  graphTruncated?: boolean
  graphTotalNodes?: number
  graphTotalEdges?: number
  findings: Finding[]
  health: HealthDimension[]
  reviewQueue: ReviewItem[]
  lineage: LineageNode[]
  generatedAt: string
}

export interface DiscoveryInput {
  title: string
  problemStatement: string
  objectives: string[]
  stakeholders: string[]
  siteContext: string
  domainTerms: string[]
}

export interface DiscoveryRun {
  id: string
  status: AssessmentStatus
  recommendation: Recommendation
  confidenceState: AssessmentStatus
  confidence: number | null
  candidates: Study[]
  algorithmVersion: string
  assessmentStatus?: AssessmentStatus
  explanation?: string
}
