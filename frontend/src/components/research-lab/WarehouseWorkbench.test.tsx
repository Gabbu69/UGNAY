import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthSession } from '../../hooks/useAuthSession'
import {
  getContinuationHistory, getLatestWarehouseLoad, getWarehouseAnalytics,
} from '../../lib/researchLabApi'
import { WarehouseWorkbench } from './WarehouseWorkbench'

vi.mock('../../hooks/useAuthSession', () => ({ useAuthSession: vi.fn() }))
vi.mock('../../lib/researchLabApi', async () => {
  const actual = await vi.importActual<typeof import('../../lib/researchLabApi')>('../../lib/researchLabApi')
  return {
    ...actual,
    getContinuationHistory: vi.fn(),
    getLatestWarehouseLoad: vi.fn(),
    getWarehouseAnalytics: vi.fn(),
    refreshWarehouse: vi.fn(),
  }
})

function renderWorkbench() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><WarehouseWorkbench /></QueryClientProvider>)
}

describe('WarehouseWorkbench assessment honesty', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('does not turn an UNASSESSED warehouse response into zero counts or pipeline evidence', async () => {
    vi.mocked(useAuthSession).mockReturnValue({
      data: { source: 'LIVE', session: { authenticated: true, email: 'curator@ugnay.edu', roles: ['CURATOR'] } },
    } as unknown as ReturnType<typeof useAuthSession>)
    vi.mocked(getLatestWarehouseLoad).mockResolvedValue({
      status: 'UNASSESSED', assessmentStatus: 'UNASSESSED', sourceCount: 0, acceptedCount: 0, rejectedCount: 0,
      stages: [], quality: { assessmentStatus: 'UNASSESSED', issueCount: 0, bySeverity: {}, byCode: {} },
    })
    vi.mocked(getWarehouseAnalytics).mockResolvedValue({
      snapshotId: null, asOf: null, assessmentStatus: 'UNASSESSED', filters: {}, sourceStudyCount: 0,
      visibleStudyCount: 0, unavailableYearCount: 0, studiesPerYear: [], studiesPerDepartment: [],
      repeatedTopics: [], commonResearchAreas: [], topicTrends: [],
      quality: { assessmentStatus: 'UNASSESSED', issueCount: 0, bySeverity: {}, byCode: {} },
    })
    vi.mocked(getContinuationHistory).mockResolvedValue({
      snapshotId: null, asOf: null, assessmentStatus: 'UNASSESSED', total: 0, items: [],
    })

    const { container } = renderWorkbench()

    expect(await screen.findByText('Pipeline stages are UNASSESSED.')).toBeInTheDocument()
    expect(screen.getByText('Warehouse analytics are UNASSESSED.')).toBeInTheDocument()
    expect(container.querySelector('.pipeline-rail')).toBeNull()
    expect(container.querySelector('.warehouse-bars')).toBeNull()
    expect(container.querySelector('.warehouse-metrics')).toBeNull()
    await waitFor(() => expect(container.querySelectorAll('.pipeline-summary dd')).toHaveLength(4))
    expect(Array.from(container.querySelectorAll('.pipeline-summary dd')).map((node) => node.textContent)).toEqual(['—', '—', '—', '—'])
  })
})
