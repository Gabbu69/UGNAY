import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthSession } from '../hooks/useAuthSession'
import { useWorkspace } from '../hooks/useWorkspace'
import { getProjectReviewQueue, requestReviewRevision, submitReviewRevisionResponse, type ProjectReviewRecord } from '../lib/api'
import ReviewQueue from './ReviewQueue'

vi.mock('../hooks/useAuthSession', () => ({ useAuthSession: vi.fn() }))
vi.mock('../hooks/useWorkspace', () => ({ useWorkspace: vi.fn() }))
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return {
    ...actual,
    getProjectReviewQueue: vi.fn(),
    requestReviewRevision: vi.fn(),
    submitReviewRevisionResponse: vi.fn(),
  }
})

const review: ProjectReviewRecord = {
  id: 'review-1', projectId: 'project-1', type: 'TRACE_GAP', title: 'Acceptance evidence is incomplete',
  projectCode: 'UGN-001', severity: 'HIGH', requiredRole: 'COORDINATOR', reason: 'One mandatory requirement has no current passing test.',
  dueAt: '2026-08-20T00:00:00Z', status: 'OPEN',
  history: [{ id: 'event-1', eventType: 'REVIEW_CREATED', message: 'Trace analysis opened this review.', actorEmail: 'system@ugnay.local', createdAt: '2026-08-13T10:00:00Z' }],
}

function renderQueue(roles: string[], inboxReview = review) {
  vi.mocked(useWorkspace).mockReturnValue({
    data: { source: 'LIVE', data: { project: { id: 'project-1', title: 'Evidence Continuity Project' }, health: [] } },
  } as unknown as ReturnType<typeof useWorkspace>)
  vi.mocked(useAuthSession).mockReturnValue({
    data: { source: 'LIVE', session: { authenticated: true, email: 'user@ugnay.edu', roles } },
  } as unknown as ReturnType<typeof useAuthSession>)
  vi.mocked(getProjectReviewQueue).mockResolvedValue([inboxReview])
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<MemoryRouter><QueryClientProvider client={client}><ReviewQueue /></QueryClientProvider></MemoryRouter>)
}

describe('persisted project Review Queue', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('shows actor-attributed append-only history and submits an authorized revision request', async () => {
    const appendedReview = { ...review, history: [...review.history, { id: 'event-2', eventType: 'REVISION_REQUESTED', message: 'Add a current test execution.', evidenceLocation: 'trace/TEST-01', actorEmail: 'coordinator@ugnay.edu', createdAt: '2026-08-13T11:00:00Z' }] }
    vi.mocked(requestReviewRevision).mockResolvedValue({ project: { id: 'project-1', rowVersion: 8 }, review: appendedReview } as never)
    renderQueue(['COORDINATOR'])
    const user = userEvent.setup()

    expect(await screen.findByText('Acceptance evidence is incomplete')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /1 event/i }))
    expect(screen.getByText('system@ugnay.local')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Submit response/i })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: /Request revision/i }))
    await user.type(screen.getByLabelText('Message'), 'Add a current test execution with inspectable evidence.')
    await user.type(screen.getByLabelText(/Evidence location/i), 'trace/TEST-01')
    await user.click(screen.getByRole('button', { name: /Append to history/i }))

    await waitFor(() => expect(requestReviewRevision).toHaveBeenCalledWith('project-1', 'review-1', {
      message: 'Add a current test execution with inspectable evidence.', evidenceLocation: 'trace/TEST-01',
    }))
    expect(await screen.findByText('Revision request appended to the review history.')).toBeInTheDocument()
  })

  it('lets a student respond but not impersonate the required reviewer role', async () => {
    renderQueue(['STUDENT'], { ...review, status: 'REVISION_REQUESTED' })
    const user = userEvent.setup()
    await screen.findByText('Acceptance evidence is incomplete')
    await user.click(screen.getByRole('button', { name: /1 event/i }))

    expect(screen.getByRole('button', { name: /Request revision/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Submit response/i })).toBeEnabled()
    expect(submitReviewRevisionResponse).not.toHaveBeenCalled()
  })
})
