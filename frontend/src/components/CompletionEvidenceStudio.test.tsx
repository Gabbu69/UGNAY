import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CompletionEvidenceStudio } from './CompletionEvidenceStudio'
import { getCompletionEvidenceReferences, getCompletionPackage, updateCompletionEvidence } from '../lib/api'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return {
    ...actual,
    getCompletionEvidenceReferences: vi.fn(),
    getCompletionPackage: vi.fn(),
    updateCompletionEvidence: vi.fn(),
  }
})

const completionPackage = {
  id: 'package-1', projectId: 'project-1', status: 'DRAFT', readinessState: 'UNASSESSED' as const,
  readinessScore: null, codeDataRightsConfirmed: false,
  criteria: [{ key: 'trace', label: 'Trace coverage', weight: 25, state: 'UNASSESSED' as const, value: null, explanation: 'No approved baseline is available.', source: null, assessedAt: null }],
  blockers: ['Approve an immutable baseline.'], repositoryUrl: '', commitHash: '', setupInstructions: '',
  limitations: [], recommendations: [], unfinishedWork: [],
}

function renderStudio() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CompletionEvidenceStudio open onOpenChange={vi.fn()} projectId="project-1" source="LIVE" roles={['STUDENT']} onRecorded={vi.fn()} />
    </QueryClientProvider>,
  )
}

describe('completion evidence studio', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders server assessment and persisted references without sliders or evidence checkboxes', async () => {
    vi.mocked(getCompletionPackage).mockResolvedValue(completionPackage)
    vi.mocked(getCompletionEvidenceReferences).mockResolvedValue([{ id: 'reference-1', type: 'DOCUMENT', label: 'Defense protocol', location: '/documents/protocol.pdf', verificationState: 'UNVERIFIED' }])
    renderStudio()

    expect(await screen.findByText('Defense protocol')).toBeInTheDocument()
    expect(screen.getAllByText('UNASSESSED').length).toBeGreaterThan(0)
    expect(screen.queryByRole('slider')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('submits inspectable references without client-authored criteria or rights claims', async () => {
    vi.mocked(getCompletionPackage).mockResolvedValue(completionPackage)
    vi.mocked(getCompletionEvidenceReferences).mockResolvedValue([])
    vi.mocked(updateCompletionEvidence).mockResolvedValue({ artifact: completionPackage } as never)
    renderStudio()
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Repository URL'), 'https://example.edu/research.git')
    await user.type(screen.getByLabelText('Commit or release tag'), 'abc1234')
    await user.type(screen.getByLabelText('Setup and access instructions'), 'Follow the preserved setup guide.')
    await user.type(screen.getByLabelText('Evidence label'), 'Defense test report')
    await user.type(screen.getByLabelText('Location or URL'), 'reports/defense-test.xml')
    await user.click(screen.getByRole('button', { name: /Append evidence and recalculate/i }))

    await waitFor(() => expect(updateCompletionEvidence).toHaveBeenCalled())
    const input = vi.mocked(updateCompletionEvidence).mock.calls[0]?.[1]
    expect(input).not.toHaveProperty('criteria')
    expect(input).not.toHaveProperty('codeDataRightsConfirmed')
    expect(input?.evidenceReferences).toEqual([{ type: 'DOCUMENT', label: 'Defense test report', location: 'reports/defense-test.xml' }])
  })
})
