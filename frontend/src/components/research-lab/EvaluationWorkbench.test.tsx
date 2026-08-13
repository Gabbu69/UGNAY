import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthSession } from '../../hooks/useAuthSession'
import {
  listEvaluationDatasets, listEvaluationQueries, submitEvaluationJudgment,
} from '../../lib/researchLabApi'
import { EvaluationWorkbench } from './EvaluationWorkbench'

vi.mock('../../hooks/useAuthSession', () => ({ useAuthSession: vi.fn() }))
vi.mock('../../lib/researchLabApi', async () => {
  const actual = await vi.importActual<typeof import('../../lib/researchLabApi')>('../../lib/researchLabApi')
  return {
    ...actual,
    listEvaluationDatasets: vi.fn(),
    listEvaluationQueries: vi.fn(),
    submitEvaluationJudgment: vi.fn(),
    adjudicateEvaluationQrel: vi.fn(),
    createEvaluationDataset: vi.fn(),
    createEvaluationQuery: vi.fn(),
    freezeEvaluationDataset: vi.fn(),
    startEvaluationRun: vi.fn(),
    getEvaluationRun: vi.fn(),
    getEvaluationReport: vi.fn(),
  }
})

function renderWorkbench() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><EvaluationWorkbench /></QueryClientProvider>)
}

const datasets = [
  {
    datasetId: 'dataset-a', versionId: 'version-a', version: 1, name: 'Catalogue A', status: 'DRAFT' as const,
    corpusSha256: 'a'.repeat(64), corpusSize: 12, queryCount: 1, adjudicatedQrelCount: 0,
    createdAt: '2026-08-13T01:00:00Z',
  },
  {
    datasetId: 'dataset-b', versionId: 'version-b', version: 2, name: 'Catalogue B', status: 'DRAFT' as const,
    corpusSha256: 'b'.repeat(64), corpusSize: 18, queryCount: 1, adjudicatedQrelCount: 0,
    createdAt: '2026-08-13T02:00:00Z',
  },
]

describe('EvaluationWorkbench authoritative selection', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('requires explicit dataset and query selections before recording a judgment', async () => {
    vi.mocked(useAuthSession).mockReturnValue({
      data: { source: 'LIVE', session: { authenticated: true, email: 'adviser@ugnay.edu', roles: ['ADVISER'] } },
    } as unknown as ReturnType<typeof useAuthSession>)
    vi.mocked(listEvaluationDatasets).mockResolvedValue(datasets)
    vi.mocked(listEvaluationQueries).mockImplementation(async (versionId) => versionId === 'version-b' ? [{
      id: 'query-b', datasetVersionId: 'version-b', externalKey: 'Q-B', split: 'TEST', title: 'Selected query',
      querySha256: 'c'.repeat(64), distinctReviewerCount: 0, adjudicatedQrelCount: 0,
      createdAt: '2026-08-13T03:00:00Z',
    }] : [{
      id: 'query-a', datasetVersionId: 'version-a', externalKey: 'Q-A', split: 'TEST', title: 'Unselected query',
      querySha256: 'd'.repeat(64), distinctReviewerCount: 0, adjudicatedQrelCount: 0,
      createdAt: '2026-08-13T03:00:00Z',
    }])
    vi.mocked(submitEvaluationJudgment).mockResolvedValue({
      id: 'judgment-b', queryId: 'query-b', studyId: '11111111-1111-4111-8111-111111111111',
      reviewer: 'adviser@ugnay.edu', relevanceGrade: 1,
      rationale: 'The persisted study directly addresses this exact query.', revision: 1,
    })

    renderWorkbench()
    const user = userEvent.setup()

    expect(await screen.findByText('Select an evaluation dataset.')).toBeInTheDocument()
    expect(listEvaluationQueries).not.toHaveBeenCalled()

    await user.selectOptions(screen.getByLabelText('Dataset version'), 'version-b')
    await waitFor(() => expect(listEvaluationQueries).toHaveBeenCalledWith('version-b'))
    expect(await screen.findByText('Selected query')).toBeInTheDocument()
    expect(screen.queryByText('Unselected query')).not.toBeInTheDocument()

    const recordButton = screen.getByRole('button', { name: 'Record judgment' })
    expect(recordButton).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Authorized catalogue study UUID/), { target: { value: '11111111-1111-4111-8111-111111111111' } })
    fireEvent.change(screen.getByLabelText('Evidence rationale'), { target: { value: 'The persisted study directly addresses this exact query.' } })
    expect(recordButton).toBeDisabled()

    await user.selectOptions(screen.getByLabelText('Evaluation query'), 'query-b')
    expect(recordButton).toBeEnabled()
    await user.click(recordButton)

    await waitFor(() => expect(submitEvaluationJudgment).toHaveBeenCalledWith(
      'query-b',
      '11111111-1111-4111-8111-111111111111',
      1,
      'The persisted study directly addresses this exact query.',
    ))
  })
})
