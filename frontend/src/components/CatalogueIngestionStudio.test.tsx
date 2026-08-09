import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CatalogueIngestionStudio } from './CatalogueIngestionStudio'
import { getDocumentImportJob, importStudyMetadata, uploadStudyDocument } from '../lib/api'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return {
    ...actual,
    getDocumentImportJob: vi.fn(),
    importStudyMetadata: vi.fn(),
    uploadStudyDocument: vi.fn(),
  }
})

function renderStudio() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <CatalogueIngestionStudio open onOpenChange={vi.fn()} />
    </QueryClientProvider>,
  )
}

function enter(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

describe('catalogue ingestion studio', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('records structured reviewed metadata with objective and keyword boundaries', async () => {
    vi.mocked(importStudyMetadata).mockResolvedValue({ id: 'study-1' })
    renderStudio()
    const user = userEvent.setup()

    enter('Institutional code', 'CIS-2026-041')
    enter('Study title', 'Offline Flood Response Evidence')
    enter('Abstract', 'A reviewed abstract describing the preserved research evidence.')
    enter('Problem statement', 'Barangay responders lose access to incident evidence when connectivity fails.')
    enter('Objectives · one per line', 'Measure offline continuity\nEvaluate responder coordination')
    enter('Controlled keywords · comma separated', 'flood, offline, response')
    enter('Methodology', 'Design science with scenario-based evaluation')
    enter('Features / deliverables', 'Offline incident ledger and verified handoff report')
    enter('Stakeholders and intended users', 'Barangay responders and residents')
    enter('Site and operating context', 'Rural campus community with intermittent connectivity')
    await user.click(screen.getByRole('button', { name: /Record reviewed study/i }))

    await waitFor(() => expect(importStudyMetadata).toHaveBeenCalledWith(expect.objectContaining({
      institutionalCode: 'CIS-2026-041',
      objectives: ['Measure offline continuity', 'Evaluate responder coordination'],
      keywords: ['flood', 'offline', 'response'],
    }), expect.anything()))
    expect(await screen.findByText('Catalogue record persisted')).toBeInTheDocument()
  })

  it('uploads a PDF and reconciles against the durable extraction record', async () => {
    vi.mocked(uploadStudyDocument).mockResolvedValue({
      jobId: 'job-1', documentId: 'document-1', documentVersionId: 'version-1', status: 'QUEUED',
      statusUrl: '/api/v1/imports/documents/jobs/job-1', eventsUrl: '/api/v1/imports/documents/jobs/job-1/events',
      queuedAt: '2026-08-09T00:00:00Z',
    })
    vi.mocked(getDocumentImportJob).mockResolvedValue({
      jobId: 'job-1', documentId: 'document-1', documentVersionId: 'version-1', status: 'EXTRACTED',
      queuedAt: '2026-08-09T00:00:00Z', originalFilename: 'study.pdf', mimeType: 'application/pdf', byteSize: 18,
      sha256: 'a'.repeat(64), scanStatus: 'CLEAN', storageStatus: 'STORED', objectKey: 'private/job-1.pdf',
      storageEtag: 'etag', progressPercent: 100, pageCount: 4, extractedCharacterCount: 1400,
      maxCharacterCount: 2_000_000, timeoutSeconds: 30, attemptCount: 1, manualReviewRequired: false,
      publicationEligible: true, failureReason: null, textPreview: 'Extracted evidence', uploaderEmail: 'curator@ugnay.edu',
      startedAt: '2026-08-09T00:00:01Z', completedAt: '2026-08-09T00:00:02Z',
    })
    renderStudio()
    const user = userEvent.setup()
    await user.click(screen.getByRole('tab', { name: /Source PDF/i }))
    const file = new File(['%PDF-1.4 evidence'], 'study.pdf', { type: 'application/pdf' })
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')
    expect(input).not.toBeNull()
    fireEvent.change(input!, { target: { files: [file] } })
    fireEvent.submit(screen.getByRole('form', { name: /Upload private study PDF/i }))

    await waitFor(() => expect(uploadStudyDocument).toHaveBeenCalledWith(file, expect.anything()))
    expect(await screen.findByText('Extraction ready for curator publication review')).toBeInTheDocument()
    expect(getDocumentImportJob).toHaveBeenCalledWith('job-1')
  })
})
