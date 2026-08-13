import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import ChangeLab from './ChangeLab'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { getChangeContext, getProjectTraceability, listProjectChangeRequests, previewChangeImpact } from '../lib/api'

vi.mock('../hooks/useWorkspace', () => ({ useWorkspace: vi.fn() }))
vi.mock('../hooks/useAuthSession', () => ({ useAuthSession: vi.fn() }))
vi.mock('../lib/api', async (importOriginal) => {
  const original = await importOriginal<typeof import('../lib/api')>()
  return {
    ...original,
    getChangeContext: vi.fn(),
    getProjectTraceability: vi.fn(),
    listProjectChangeRequests: vi.fn(),
    previewChangeImpact: vi.fn(),
  }
})

const requestOne = {
  id: 'change-1', projectId: 'project-1', basedOnBaselineId: 'baseline-1', title: 'First persisted request',
  rationale: 'A persisted request that must not be selected automatically.', status: 'PROPOSED',
  changedItemIds: ['item-1'], boundaryFlags: [], createdAt: '2026-08-13T00:00:00Z',
}
const requestTwo = {
  ...requestOne,
  id: 'change-2',
  title: 'Explicitly selected request',
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><ChangeLab /></QueryClientProvider>)
}

describe('Change Lab record selection', () => {
  it('loads no request context until the user selects an exact persisted request', async () => {
    vi.mocked(useWorkspace).mockReturnValue({ data: { source: 'LIVE', data: { project: { id: 'project-1', title: 'Selected project' } } } } as ReturnType<typeof useWorkspace>)
    vi.mocked(useAuthSession).mockReturnValue({ data: { source: 'LIVE', session: { authenticated: false, email: null, roles: [] } } } as unknown as ReturnType<typeof useAuthSession>)
    vi.mocked(listProjectChangeRequests).mockResolvedValue([requestOne, requestTwo])
    vi.mocked(getProjectTraceability).mockResolvedValue({ baselineId: 'baseline-1', items: [] } as never)
    vi.mocked(getChangeContext).mockResolvedValue({ change: requestTwo, impact: null })

    renderPage()

    await screen.findByRole('option', { name: /Explicitly selected request/ })
    const selector = screen.getByLabelText('Project change request')
    expect(getChangeContext).not.toHaveBeenCalled()
    expect(previewChangeImpact).not.toHaveBeenCalled()
    expect(screen.queryByRole('heading', { name: 'Explicitly selected request' })).not.toBeInTheDocument()

    await userEvent.selectOptions(selector, 'change-2')

    await waitFor(() => expect(getChangeContext).toHaveBeenCalledTimes(1))
    expect(getChangeContext).toHaveBeenCalledWith(requestTwo)
    expect(await screen.findByRole('heading', { name: 'Explicitly selected request' })).toBeInTheDocument()
    expect(previewChangeImpact).not.toHaveBeenCalled()
  })
})
