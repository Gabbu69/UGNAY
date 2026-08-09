import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  approveProjectBaseline,
  actOnFinding,
  addChangeOperation,
  createTraceItem,
  createTraceLink,
  getDocumentImportJob,
  getAuthSession,
  login,
  logout,
  recordAcademicDecision,
  recordTestExecution,
  decideChangeRequest,
  rerunProjectAnalysis,
  runDiscovery,
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

  it('never fabricates an assessed route when the analysis service is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const result = await runDiscovery(input)
    expect(result.status).toBe('PARTIAL')
    expect(result.recommendation).toBe('REVIEW_REQUIRED')
    expect(result.confidence).toBe(0)
    expect(result.candidates).toEqual([])
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

  it('records the latest discovered proposal with a human rationale and predecessor', async () => {
    const decision = { id: 'decision-1', disposition: 'APPROVE_IMPROVE', decidedAt: '2026-08-09T00:00:00Z' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ authenticated: true, email: 'coordinator@ugnay.edu', roles: ['COORDINATOR'] }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [{ id: 'proposal-1', submittedAt: '2026-08-08T00:00:00Z' }] })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [{ id: 'run-1', proposalId: 'proposal-1', createdAt: '2026-08-08T01:00:00Z' }] })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => [] })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ headerName: 'X-XSRF-TOKEN', token: 'decision-csrf' }) })
      .mockResolvedValueOnce({ ok: true, status: 201, json: async () => decision })
    vi.stubGlobal('fetch', fetchMock)

    await login({ email: 'coordinator@ugnay.edu', password: 'safe-test-password' })
    await expect(recordAcademicDecision({
      disposition: 'APPROVE_IMPROVE',
      rationale: 'The proposal defines a measurable improvement over the preserved predecessor.',
      primaryPredecessorId: 'study-1',
    })).resolves.toEqual(decision)

    const mutation = fetchMock.mock.calls[5]?.[1] as RequestInit
    expect(JSON.parse(String(mutation.body))).toEqual({
      proposalId: 'proposal-1',
      discoveryRunId: 'run-1',
      disposition: 'APPROVE_IMPROVE',
      rationale: 'The proposal defines a measurable improvement over the preserved predecessor.',
      primaryPredecessorId: 'study-1',
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
      codeDataRightsConfirmed: true,
      repositoryUrl: 'https://git.example.edu/ugnay/project-1',
      commitHash: '4f61ac2',
      setupInstructions: 'Follow the preserved deployment guide.',
      limitations: ['Pilot dataset only'], recommendations: ['Run a wider study'], unfinishedWork: ['Validate export load'],
      criteria: [{ key: 'trace', completion: 1, explanation: 'Baseline history is preserved.' }],
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
})
