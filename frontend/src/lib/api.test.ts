import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  approveProjectBaseline,
  actOnFinding,
  addChangeOperation,
  createTraceItem,
  createTraceLink,
  getDocumentImportJob,
  getWorkspace,
  getCompletionEvidenceReferences,
  getDecisionContext,
  getAuthorizedStudyDetail,
  getAuthSession,
  getProjectReviewQueue,
  login,
  logout,
  recordAcademicDecision,
  recordContinuationEvidence,
  recordImprovementClaim,
  recordTestExecution,
  requestReviewRevision,
  decideChangeRequest,
  rerunProjectAnalysis,
  runDiscovery,
  submitIntakeForDiscovery,
  submitReviewRevisionResponse,
  uploadStudyDocument,
  updateCompletionEvidence,
} from './api'

const input = {
  title: 'A real community problem',
  problemStatement: 'A documented condition affecting a specific community and outcome.',
  objectives: ['Measure an improved outcome.'],
  stakeholders: ['Community partner'],
  siteContext: 'Pilot site',
  domainTerms: ['continuity'],
}

describe('discovery fail-safe behavior', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('surfaces analysis failure instead of fabricating a synthetic run or false zero', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await expect(runDiscovery(input)).rejects.toThrow('offline')
  })

  it('obtains and sends the same-origin CSRF token for discovery mutations', async () => {
    const liveResult = {
      id: '3fbb4f40-4c62-4fb8-b49d-3335ad0bd8de',
      status: 'PARTIAL' as const,
      recommendation: 'REVIEW_REQUIRED' as const,
      confidence: 48,
      candidates: [],
      algorithmVersion: 'hybrid-v1.0.0',
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'test-csrf' }) })
      .mockResolvedValueOnce({ ok: true, json: async () => liveResult })
    vi.stubGlobal('fetch', fetchMock)

    await expect(runDiscovery(input)).resolves.toEqual(liveResult)
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', expect.objectContaining({ credentials: 'include' }))
    const mutation = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(new Headers(mutation.headers).get('X-XSRF-TOKEN')).toBe('test-csrf')
  })

  it('submits intake atomically with a stable idempotency key and no invented fields', async () => {
    const result = { idempotencyKey: 'intake-key-1', replayed: false, problem: { id: 'problem-1' }, proposal: { id: 'proposal-1' }, discovery: { id: 'run-1' } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'student@ugnay.edu', roles: ['STUDENT'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'intake-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => result })
    vi.stubGlobal('fetch', fetchMock)
    await login({ email: 'student@ugnay.edu', password: 'safe-test-password' })
    await expect(submitIntakeForDiscovery({
      problem: { title: 'Observed delays', problemStatement: 'A sufficiently detailed observed condition affecting the partner workflow.', stakeholder: 'Partner', affectedUsers: 'Staff', siteContext: 'Campus office', desiredOutcome: 'Shorter verified processing time', privacyClassification: 'INTERNAL' },
      proposal: { title: 'Workflow continuity study', objectives: ['Measure processing time'], proposedSolution: 'Evaluate a traceable workflow intervention' },
    }, 'intake-key-1')).resolves.toEqual(result)

    const mutation = fetchMock.mock.calls[2]?.[1] as RequestInit
    const headers = new Headers(mutation.headers)
    expect(headers.get('Idempotency-Key')).toBe('intake-key-1')
    expect(headers.get('X-XSRF-TOKEN')).toBe('intake-csrf')
    expect(String(mutation.body)).not.toMatch(/Not yet assessed|No constraints recorded|intentionally deferred/i)
  })
})

describe('same-origin session API', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('distinguishes a live anonymous session from an unavailable backend', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ authenticated: false, email: null, roles: [] }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getAuthSession()).resolves.toEqual({
      session: { authenticated: false, email: null, roles: [] },
      source: 'LIVE',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/me', expect.objectContaining({ credentials: 'include' }))
  })

  it('does not select the first project when the server returns no selected project', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ project: null, projects: [{ id: 'project-1', code: 'P-1', title: 'First accessible project' }] }),
    }))
    const workspace = await getWorkspace()
    expect(workspace.source).toBe('LIVE')
    expect(workspace.data.project).toBeNull()
    expect(workspace.data.projects).toHaveLength(1)
  })

  it('opens a session without a CSRF preflight, then uses a fresh CSRF token to log out', async () => {
    const signedIn = { authenticated: true, email: 'curator@ugnay.edu', roles: ['CURATOR'] }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => signedIn })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'session-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 204 })
    vi.stubGlobal('fetch', fetchMock)

    await expect(login({ email: 'curator@ugnay.edu', password: 'safe-test-password' })).resolves.toEqual(signedIn)
    await expect(logout()).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/login', expect.objectContaining({
      credentials: 'include',
      method: 'POST',
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/csrf', expect.objectContaining({ credentials: 'include' }))
    const logoutRequest = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(new Headers(logoutRequest.headers).get('X-XSRF-TOKEN')).toBe('session-csrf')
  })
})

describe('curator document ingestion API', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('keeps multipart boundaries browser-owned and follows the durable job resource', async () => {
    const accepted = {
      jobId: 'job-1', documentId: 'document-1', documentVersionId: 'version-1', status: 'QUEUED',
      statusUrl: '/api/v1/imports/documents/jobs/job-1', eventsUrl: '/api/v1/imports/documents/jobs/job-1/events',
      queuedAt: '2026-08-09T00:00:00Z',
    }
    const job = { ...accepted, originalFilename: 'study.pdf', progressPercent: 10, publicationEligible: false }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'curator@ugnay.edu', roles: ['CURATOR'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'ingestion-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 202, json: async () => accepted })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => job })
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'curator@ugnay.edu', password: 'safe-test-password' })
    const file = new File(['%PDF-1.4 test'], 'study.pdf', { type: 'application/pdf' })
    await expect(uploadStudyDocument(file)).resolves.toEqual(accepted)
    await expect(getDocumentImportJob('job-1')).resolves.toEqual(job)

    const upload = fetchMock.mock.calls[2]?.[1] as RequestInit
    const headers = new Headers(upload.headers)
    expect(headers.get('Content-Type')).toBeNull()
    expect(headers.get('X-XSRF-TOKEN')).toBe('ingestion-csrf')
    expect(upload.body).toBeInstanceOf(FormData)
    expect((upload.body as FormData).get('file')).toBe(file)
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/imports/documents/jobs/job-1')
  })
})

describe('audited workflow mutations', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('binds project analysis to a concrete ETag and CSRF token', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'adviser@ugnay.edu', roles: ['ADVISER'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ id: 'project-1', rowVersion: 7 }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'analysis-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ findings: [] }) })
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'adviser@ugnay.edu', password: 'safe-test-password' })
    await rerunProjectAnalysis('project-1')

    const mutation = fetchMock.mock.calls[3]?.[1] as RequestInit
    const headers = new Headers(mutation.headers)
    expect(headers.get('If-Match')).toBe('"7"')
    expect(headers.get('X-XSRF-TOKEN')).toBe('analysis-csrf')
  })

  it('loads decision evidence only through the explicitly paired proposal and run', async () => {
    const exact = { proposal: { id: 'proposal-1' }, discovery: { id: 'run-7', proposalId: 'proposal-1' }, decision: null, adviserRecommendations: [] }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => exact })
    vi.stubGlobal('fetch', fetchMock)
    await expect(getDecisionContext('proposal-1', 'run-7')).resolves.toEqual(exact)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/proposals/proposal-1/decision-context/run-7', expect.objectContaining({ credentials: 'include' }))
  })

  it('records only the explicitly selected proposal, run, and predecessor', async () => {
    const decision = { id: 'decision-1', disposition: 'APPROVE_IMPROVE', decidedAt: '2026-08-09T00:00:00Z' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'decision-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => decision })
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await expect(recordAcademicDecision({
      proposalId: 'proposal-1',
      discoveryRunId: 'run-1',
      disposition: 'APPROVE_IMPROVE',
      rationale: 'The proposal defines a measurable improvement over the preserved predecessor.',
      primaryPredecessorId: 'study-1',
    })).resolves.toEqual(decision)

    const mutation = fetchMock.mock.calls[2]?.[1] as RequestInit
    expect(JSON.parse(String(mutation.body))).toEqual({
      proposalId: 'proposal-1',
      discoveryRunId: 'run-1',
      disposition: 'APPROVE_IMPROVE',
      rationale: 'The proposal defines a measurable improvement over the preserved predecessor.',
      primaryPredecessorId: 'study-1',
    })
  })

  it('loads authorized predecessor items and appends exact route evidence without invented IDs', async () => {
    const study = {
      id: 'study-1', institutionalCode: 'STUDY-1', title: 'Authorized predecessor', academicYear: '2025',
      department: 'CIT', lifecycleStatus: 'INCOMPLETE', visibility: 'DEPARTMENT', abstractText: '',
      problemStatement: '', objectives: [], keywords: [],
      continuationItems: [{ id: 'item-1', studyId: 'study-1', type: 'UNFINISHED_WORK', title: 'Open verification', description: '', status: 'OPEN', claimed: false }],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'student@ugnay.edu', roles: ['STUDENT'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => study })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'route-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ revisionNumber: 1 }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => ({ id: 'claim-1' }) })
    vi.stubGlobal('fetch', fetchMock)
    await login({ email: 'student@ugnay.edu', password: 'safe-test-password' })
    await expect(getAuthorizedStudyDetail('study-1')).resolves.toEqual(study)

    await recordContinuationEvidence('proposal-1', {
      predecessorStudyId: 'study-1',
      objectiveLinks: [{ proposalObjectiveId: 'objective-1', continuationItemId: 'item-1', rationale: 'The objective explicitly continues the open verification work.' }],
      codeAccessConfirmed: true,
      dataAccessConfirmed: false,
      accessNotes: 'Repository access was verified; the required dataset remains unavailable.',
    })
    await recordImprovementClaim('proposal-1', {
      predecessorStudyId: 'study-1', continuationItemId: 'item-1',
      claim: 'Reduce the documented processing delay.', baselineMeasure: '14 minutes median',
      targetMeasure: '8 minutes median', evaluationMethod: 'Compare medians over equal-size observation windows.',
    })

    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/studies/study-1')
    expect(JSON.parse(String((fetchMock.mock.calls[3]?.[1] as RequestInit).body))).toEqual({
      predecessorStudyId: 'study-1',
      objectiveLinks: [{ proposalObjectiveId: 'objective-1', continuationItemId: 'item-1', rationale: 'The objective explicitly continues the open verification work.' }],
      codeAccessConfirmed: true,
      dataAccessConfirmed: false,
      accessNotes: 'Repository access was verified; the required dataset remains unavailable.',
    })
    expect(JSON.parse(String((fetchMock.mock.calls[4]?.[1] as RequestInit).body))).toEqual({
      predecessorStudyId: 'study-1', continuationItemId: 'item-1',
      claim: 'Reduce the documented processing delay.', baselineMeasure: '14 minutes median',
      targetMeasure: '8 minutes median', evaluationMethod: 'Compare medians over equal-size observation windows.',
    })
  })

  it('binds every evidence-authoring action to the latest project ETag', async () => {
    const response = (body: unknown, status = 200) => ({ ok: true, status, json: async () => body })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 11 }))
      .mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'authoring-csrf' }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 12 }, artifact: {}, traceability: {} }, 201))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 12 }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 13 }, artifact: {}, traceability: {} }, 201))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 13 }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 14 }, artifact: {}, traceability: {} }, 201))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 14 }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 15 }, baseline: {} }, 201))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 15 }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 16 }, artifact: { readinessScore: 80 }, traceability: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await createTraceItem('project-1', {
      key: 'REQ-04', type: 'REQUIREMENT', title: 'Export a preserved evidence set',
      description: 'Authorized reviewers can export the approved evidence set.', priority: 'MUST',
      acceptanceCriteria: 'The export contains every approved item and hash.', verificationMethod: 'Inspect the generated archive.',
    })
    await createTraceLink('project-1', {
      sourceId: 'objective-1', targetId: 'requirement-4', relationshipType: 'DECOMPOSES_TO',
      rationale: 'The requirement directly operationalizes the approved objective.',
    })
    await recordTestExecution('project-1', {
      testItemId: 'test-1', status: 'PASSED', buildIdentifier: 'v1.0-rc3', evidenceConfirmed: true,
    })
    await approveProjectBaseline('project-1', 'The chain is complete, measurable, and ready for an immutable baseline.')
    await updateCompletionEvidence('project-1', {
      repositoryUrl: 'https://git.example.edu/ugnay/project-1',
      commitHash: '4f61ac2',
      setupInstructions: 'Follow the preserved deployment guide.',
      limitations: ['Pilot dataset only'], recommendations: ['Run a wider study'], unfinishedWork: ['Validate export load'],
      evidenceReferences: [{ type: 'TEST_RUN', label: 'Release verification', location: 'reports/release-verification.xml' }],
    })

    const mutationCalls = [3, 5, 7, 9, 11]
    const expectedVersions = ['"11"', '"12"', '"13"', '"14"', '"15"']
    mutationCalls.forEach((callIndex, index) => {
      const init = fetchMock.mock.calls[callIndex]?.[1] as RequestInit
      const headers = new Headers(init.headers)
      expect(headers.get('If-Match')).toBe(expectedVersions[index])
      expect(headers.get('X-XSRF-TOKEN')).toBe('authoring-csrf')
    })
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/projects/project-1/trace-items')
    expect(fetchMock.mock.calls[5]?.[0]).toBe('/api/v1/projects/project-1/trace-links')
    expect(fetchMock.mock.calls[7]?.[0]).toBe('/api/v1/projects/project-1/test-executions')
    expect(fetchMock.mock.calls[9]?.[0]).toBe('/api/v1/projects/project-1/baselines/approve')
    expect(fetchMock.mock.calls[11]?.[0]).toBe('/api/v1/projects/project-1/completion-package/evidence')
    const completionPayload = JSON.parse(String((fetchMock.mock.calls[11]?.[1] as RequestInit).body))
    expect(completionPayload).not.toHaveProperty('criteria')
    expect(completionPayload).not.toHaveProperty('codeDataRightsConfirmed')
    expect(completionPayload.evidenceReferences).toEqual([
      { type: 'TEST_RUN', label: 'Release verification', location: 'reports/release-verification.xml' },
    ])
  })

  it('loads persisted completion evidence through the project-scoped endpoint', async () => {
    const references = [{ id: 'evidence-1', type: 'DOCUMENT', label: 'Defense protocol', verificationState: 'UNVERIFIED' }]
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => references })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getCompletionEvidenceReferences('project-1')).resolves.toEqual(references)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project-1/completion-package/evidence-references', expect.objectContaining({ credentials: 'include' }))
  })

  it('binds finding and controlled-change decisions to fresh ETags and CSRF', async () => {
    const response = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 20 }))
      .mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'governance-csrf' }))
      .mockResolvedValueOnce(response({ action: { state: 'ACCEPTED' }, project: { rowVersion: 21 } }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 21 }))
      .mockResolvedValueOnce(response({ id: 'operation-1', type: 'RETIRE' }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 21 }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 22 }, baseline: {} }))
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await actOnFinding('project-1', 'finding-1', 'accept', 'The exception is bounded and academically justified.', '2099-01-01T00:00:00Z')
    await addChangeOperation('project-1', 'change-1', {
      type: 'RETIRE', targetItemId: 'feature-1', rationale: 'The feature no longer realizes an approved requirement.',
    })
    await decideChangeRequest('project-1', 'change-1', 'return-for-revision', 'The operation needs a replacement trace path before approval.')

    for (const [callIndex, expectedVersion] of [[3, '"20"'], [5, '"21"'], [7, '"21"']] as const) {
      const request = fetchMock.mock.calls[callIndex]?.[1] as RequestInit
      const headers = new Headers(request.headers)
      expect(headers.get('If-Match')).toBe(expectedVersion)
      expect(headers.get('X-XSRF-TOKEN')).toBe('governance-csrf')
    }
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/projects/project-1/findings/finding-1/accept')
    expect(fetchMock.mock.calls[5]?.[0]).toBe('/api/v1/change-requests/change-1/operations')
    expect(fetchMock.mock.calls[7]?.[0]).toBe('/api/v1/change-requests/change-1/return-for-revision')
  })

  it('binds approval to the exact operation-set version', async () => {
    const response = (body: unknown) => ({ ok: true, status: 200, json: async () => body })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 22 }))
      .mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'approval-csrf' }))
      .mockResolvedValueOnce(response({ project: { rowVersion: 23 }, baseline: {} }))
    vi.stubGlobal('fetch', fetchMock)
    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await decideChangeRequest('project-1', 'change-1', 'approve', 'The current operation set preserves the approved research boundary.', 7)
    expect(JSON.parse(String((fetchMock.mock.calls[3]?.[1] as RequestInit).body))).toEqual({
      rationale: 'The current operation set preserves the approved research boundary.',
      operationSetVersion: 7,
    })
  })
})

describe('project review inbox API', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads only the canonical project-scoped review inbox', async () => {
    const reviews = [{ id: 'review-1', projectId: 'project-1', history: [] }]
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => reviews })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getProjectReviewQueue('project-1')).resolves.toEqual(reviews)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/project-1/reviews', expect.objectContaining({ credentials: 'include' }))
  })

  it('binds revision requests and responses to a fresh ETag and the authenticated CSRF session', async () => {
    const response = (body: unknown, status = 200) => ({ ok: true, status, json: async () => body })
    const reviewResult = { project: { id: 'project-1', rowVersion: 8 }, review: { id: 'review-1', history: [] } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 7 }))
      .mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'review-csrf' }))
      .mockResolvedValueOnce(response(reviewResult, 201))
      .mockResolvedValueOnce(response({ id: 'project-1', rowVersion: 8 }))
      .mockResolvedValueOnce(response({ ...reviewResult, project: { id: 'project-1', rowVersion: 9 } }, 201))
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await requestReviewRevision('project-1', 'review-1', { message: 'Revise the missing acceptance evidence.', evidenceLocation: 'trace/REQ-04' })
    await submitReviewRevisionResponse('project-1', 'review-1', { message: 'The requested acceptance evidence is now attached.' })

    for (const [callIndex, version] of [[3, '"7"'], [5, '"8"']] as const) {
      const request = fetchMock.mock.calls[callIndex]?.[1] as RequestInit
      expect(new Headers(request.headers).get('If-Match')).toBe(version)
      expect(new Headers(request.headers).get('X-XSRF-TOKEN')).toBe('review-csrf')
    }
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/v1/projects/project-1/reviews/review-1/revision-requests')
    expect(fetchMock.mock.calls[5]?.[0]).toBe('/api/v1/projects/project-1/reviews/review-1/revision-responses')
    expect(JSON.parse(String((fetchMock.mock.calls[3]?.[1] as RequestInit).body))).toEqual({ message: 'Revise the missing acceptance evidence.', evidenceLocation: 'trace/REQ-04' })
    expect(JSON.parse(String((fetchMock.mock.calls[5]?.[1] as RequestInit).body))).toEqual({ message: 'The requested acceptance evidence is now attached.' })
  })
})
