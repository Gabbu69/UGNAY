import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAuthSession } from '../hooks/useAuthSession'
import { useWorkspace } from '../hooks/useWorkspace'
import { searchCatalogue, type CatalogueSearchPage, type CatalogueStudySummary } from '../lib/api'
import ResearchAtlas from './ResearchAtlas'

vi.mock('../hooks/useAuthSession', () => ({ useAuthSession: vi.fn() }))
vi.mock('../hooks/useWorkspace', () => ({ useWorkspace: vi.fn() }))
vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, searchCatalogue: vi.fn() }
})
vi.mock('../components/CatalogueIngestionStudio', () => ({ CatalogueIngestionStudio: () => null }))

function renderAtlas() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <ResearchAtlas />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('server-backed Research Atlas', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('uses the paginated catalogue search but never presents an unrelated similarity score', async () => {
    vi.mocked(useWorkspace).mockReturnValue({
      data: { source: 'LIVE', data: { studies: [], generatedAt: '2026-08-11T12:00:00Z' } },
    } as unknown as ReturnType<typeof useWorkspace>)
    vi.mocked(useAuthSession).mockReturnValue({
      data: { source: 'LIVE', session: { authenticated: true, email: 'adviser@ugnay.edu', roles: ['ADVISER'] } },
    } as unknown as ReturnType<typeof useAuthSession>)

    const serverItem: CatalogueStudySummary & { similarityScore: number; relevanceScore: number } = {
      id: 'study-server-1',
      institutionalCode: 'CICS-2025-014',
      title: 'Climate Resilience Evidence Archive',
      academicYear: '2024-2025',
      completionYear: 2025,
      departmentCode: 'CICS',
      departmentName: 'College of Information and Computing Sciences',
      program: 'BS Computer Science',
      lifecycleStatus: 'PUBLISHED',
      visibility: 'INTERNAL',
      abstractText: 'Authorized catalogue metadata returned by the server.',
      problemStatement: 'Local evidence continuity is fragmented.',
      methodology: 'Design science',
      keywords: ['climate', 'resilience'],
      resultsText: 'A reviewed continuity package was produced.',
      objectiveCount: 2,
      // Deliberately simulate stale score-shaped fields from a permissive server payload.
      // Research Atlas must ignore them because catalogue filtering is not retrieval evaluation.
      similarityScore: 91,
      relevanceScore: 91,
    }
    const page: CatalogueSearchPage = {
      items: [serverItem],
      totalItems: 1,
      page: 0,
      pageSize: 12,
      generatedAt: '2026-08-11T12:00:00Z',
    }
    vi.mocked(searchCatalogue).mockResolvedValue(page)
    renderAtlas()

    expect(await screen.findByRole('button', { name: /Climate Resilience Evidence Archive/ })).toBeInTheDocument()
    expect(searchCatalogue).toHaveBeenCalledWith(expect.objectContaining({
      q: undefined,
      page: 0,
      size: 12,
      sort: 'YEAR_DESC',
    }))
    expect(screen.getByText('Select a catalogue record')).toBeInTheDocument()
    expect(screen.queryByText('No similarity score is attached to a catalogue search.')).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /Climate Resilience Evidence Archive/ }))
    expect(screen.getByText('No similarity score is attached to a catalogue search.')).toBeInTheDocument()
    expect(screen.queryByText('91%')).not.toBeInTheDocument()
    expect(document.querySelector('.score-ring')).toBeNull()

    await user.type(screen.getByRole('textbox', { name: 'Search studies' }), 'flood')
    await waitFor(() => expect(searchCatalogue).toHaveBeenLastCalledWith(expect.objectContaining({
      q: 'flood',
      page: 0,
      size: 12,
      sort: 'YEAR_DESC',
    })))
    expect(screen.getByText('Select a catalogue record')).toBeInTheDocument()
    expect(screen.queryByText('91%')).not.toBeInTheDocument()
  })
})
