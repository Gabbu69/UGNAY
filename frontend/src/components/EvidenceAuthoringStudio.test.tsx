import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EvidenceAuthoringStudio } from './EvidenceAuthoringStudio'
import { approveProjectBaseline, getProjectTraceability } from '../lib/api'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return {
    ...actual,
    approveProjectBaseline: vi.fn(),
    createTraceItem: vi.fn(),
    createTraceLink: vi.fn(),
    getProjectTraceability: vi.fn(),
    recordTestExecution: vi.fn(),
  }
})

function renderStudio(source: 'LIVE' | 'DEMO', roles: string[], onRecorded = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <EvidenceAuthoringStudio open onOpenChange={vi.fn()} projectId="project-1" source={source} roles={roles} onRecorded={onRecorded} />
    </QueryClientProvider>,
  )
  return onRecorded
}

describe('evidence authoring studio', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('keeps fallback evidence explicitly read-only without querying protected records', () => {
    renderStudio('DEMO', ['COORDINATOR'])
    expect(screen.getByText('Read-only evidence view')).toBeInTheDocument()
    expect(screen.getByText(/Demo continuity records remain read-only|Sign in with a student/i)).toBeInTheDocument()
    expect(getProjectTraceability).not.toHaveBeenCalled()
  })

  it('lets a coordinator submit a reasoned baseline action and waits for server confirmation', async () => {
    vi.mocked(getProjectTraceability).mockResolvedValue({
      projectId: 'project-1', baselineId: 'baseline-2', baselineNumber: 2, assessmentStatus: 'ASSESSED',
      items: [], links: [], executions: [], findings: [],
      coverage: { mappedCoverage: 0, executedCoverage: 0, passingCoverage: 0, priorityWeightedPassingCoverage: 0, totalRequirements: 0, verifiedRequirements: 0 },
    })
    vi.mocked(approveProjectBaseline).mockResolvedValue({ project: { rowVersion: 8 }, baseline: {} })
    const onRecorded = renderStudio('LIVE', ['COORDINATOR'])
    const user = userEvent.setup()

    await screen.findByText('01 / WORKING ARTIFACT')
    await user.click(screen.getByRole('tab', { name: /Baseline/i }))
    await user.type(screen.getByLabelText('Coordinator approval rationale'), 'The evidence chain meets every hard readiness and relationship rule.')
    await user.click(screen.getByRole('button', { name: /Approve immutable baseline/i }))

    await waitFor(() => expect(approveProjectBaseline).toHaveBeenCalledWith(
      'project-1',
      'The evidence chain meets every hard readiness and relationship rule.',
    ))
    expect(await screen.findByText('Server record confirmed')).toBeInTheDocument()
    expect(onRecorded).toHaveBeenCalledWith('Coordinator approval created a new immutable baseline.')
  })
})
