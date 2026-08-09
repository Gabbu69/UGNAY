import { useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowRight, BookOpenCheck, CheckCircle2, Database, FileText, FileUp, LoaderCircle, LockKeyhole, X } from 'lucide-react'
import {
  ApiProblem,
  getDocumentImportJob,
  importStudyMetadata,
  uploadStudyDocument,
  type StudyMetadataInput,
} from '../lib/api'

const terminalStates = new Set(['EXTRACTED', 'CHARACTER_LIMIT_REACHED', 'TIMED_OUT', 'INTERRUPTED', 'FAILED', 'FAILED_STORAGE', 'ORPHAN_REVIEW'])

const currentAcademicYear = () => {
  const now = new Date()
  const start = now.getMonth() >= 6 ? now.getFullYear() : now.getFullYear() - 1
  return `${start}-${start + 1}`
}

const emptyMetadata = (): StudyMetadataInput => ({
  institutionalCode: '',
  title: '',
  academicYear: currentAcademicYear(),
  abstractText: '',
  problemStatement: '',
  objectives: [],
  keywords: [],
  methodology: '',
  features: '',
  stakeholders: '',
  siteContext: '',
})

function messageFor(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  if (error instanceof DOMException && error.name === 'AbortError') return 'The university service did not respond before the safe upload timeout.'
  return error instanceof Error ? error.message : 'The evidence could not be recorded.'
}

export function CatalogueIngestionStudio({ open, onOpenChange }: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<'metadata' | 'document'>('metadata')
  const [metadata, setMetadata] = useState(emptyMetadata)
  const [objectiveText, setObjectiveText] = useState('')
  const [keywordText, setKeywordText] = useState('')
  const [file, setFile] = useState<File>()
  const [jobId, setJobId] = useState('')
  const [metadataRecorded, setMetadataRecorded] = useState('')

  const metadataMutation = useMutation({
    mutationFn: importStudyMetadata,
    onSuccess: async (_, recorded) => {
      setMetadataRecorded(recorded.title)
      setMetadata(emptyMetadata())
      setObjectiveText('')
      setKeywordText('')
      await queryClient.invalidateQueries({ queryKey: ['workspace'] })
    },
  })
  const uploadMutation = useMutation({
    mutationFn: uploadStudyDocument,
    onSuccess: (accepted) => setJobId(accepted.jobId),
  })
  const jobQuery = useQuery({
    queryKey: ['document-import', jobId],
    queryFn: () => getDocumentImportJob(jobId),
    enabled: Boolean(jobId),
    refetchInterval: (query) => terminalStates.has(query.state.data?.status ?? '') ? false : 1_200,
  })

  const submitMetadata = async (event: FormEvent) => {
    event.preventDefault()
    setMetadataRecorded('')
    const objectives = objectiveText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean)
    const keywords = keywordText.split(/[,\r\n]/).map((value) => value.trim()).filter(Boolean)
    await metadataMutation.mutateAsync({ ...metadata, objectives, keywords }).catch(() => undefined)
  }
  const submitDocument = async (event: FormEvent) => {
    event.preventDefault()
    if (!file) return
    setJobId('')
    await uploadMutation.mutateAsync(file).catch(() => undefined)
  }
  const job = jobQuery.data
  const jobTerminal = terminalStates.has(job?.status ?? '')
  const jobSuccessful = job?.status === 'EXTRACTED' && job.publicationEligible

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="drawer-overlay" />
        <Dialog.Content className="catalogue-ingestion-drawer" aria-describedby="catalogue-ingestion-description">
          <header className="authoring-head">
            <div className="authoring-mark"><Database size={20} /></div>
            <div><span>CATALOGUE INGESTION STUDIO</span><Dialog.Title>Preserve research as evidence</Dialog.Title></div>
            <Dialog.Close className="icon-button" aria-label="Close catalogue ingestion studio"><X size={19} /></Dialog.Close>
          </header>
          <Dialog.Description id="catalogue-ingestion-description">
            Curator-only intake keeps reviewed metadata and private source documents distinct. Extraction never publishes a study by itself.
          </Dialog.Description>

          <div className="ingestion-principle"><LockKeyhole size={18} /><div><strong>Private by default</strong><span>PDFs are signature-checked, scanned, hashed, and stored under a randomized key before bounded extraction begins.</span></div></div>

          <div className="authoring-tabs ingestion-tabs" role="tablist" aria-label="Catalogue evidence type">
            <button type="button" role="tab" aria-selected={mode === 'metadata'} className={mode === 'metadata' ? 'is-active' : ''} onClick={() => setMode('metadata')}><BookOpenCheck size={15} />Study metadata</button>
            <button type="button" role="tab" aria-selected={mode === 'document'} className={mode === 'document' ? 'is-active' : ''} onClick={() => setMode('document')}><FileUp size={15} />Source PDF</button>
          </div>

          {mode === 'metadata' ? (
            <form className="authoring-form ingestion-form" onSubmit={submitMetadata} aria-label="Import reviewed study metadata">
              <div className="authoring-form-intro"><span>01 / REVIEWED RECORD</span><p>Record the fields used by discovery and route analysis. Separate lines preserve objective boundaries.</p></div>
              <div className="authoring-form-row">
                <label><span>Institutional code</span><input value={metadata.institutionalCode} onChange={(event) => setMetadata((value) => ({ ...value, institutionalCode: event.target.value }))} maxLength={80} placeholder="CIS-2025-018" required /></label>
                <label><span>Academic year</span><input value={metadata.academicYear} onChange={(event) => setMetadata((value) => ({ ...value, academicYear: event.target.value }))} maxLength={24} placeholder="2025-2026" required /></label>
              </div>
              <label><span>Study title</span><input value={metadata.title} onChange={(event) => setMetadata((value) => ({ ...value, title: event.target.value }))} maxLength={500} required /></label>
              <label><span>Abstract</span><textarea value={metadata.abstractText} onChange={(event) => setMetadata((value) => ({ ...value, abstractText: event.target.value }))} rows={4} required /></label>
              <label><span>Problem statement</span><textarea value={metadata.problemStatement} onChange={(event) => setMetadata((value) => ({ ...value, problemStatement: event.target.value }))} rows={4} required /></label>
              <label><span>Objectives · one per line</span><textarea value={objectiveText} onChange={(event) => setObjectiveText(event.target.value)} rows={4} placeholder={'Measure…\nDesign…\nEvaluate…'} required /></label>
              <label><span>Controlled keywords · comma separated</span><input value={keywordText} onChange={(event) => setKeywordText(event.target.value)} placeholder="flood, offline, barangay response" required /></label>
              <div className="authoring-form-row">
                <label><span>Methodology</span><textarea value={metadata.methodology} onChange={(event) => setMetadata((value) => ({ ...value, methodology: event.target.value }))} rows={3} required /></label>
                <label><span>Features / deliverables</span><textarea value={metadata.features} onChange={(event) => setMetadata((value) => ({ ...value, features: event.target.value }))} rows={3} required /></label>
              </div>
              <div className="authoring-form-row">
                <label><span>Stakeholders and intended users</span><textarea value={metadata.stakeholders} onChange={(event) => setMetadata((value) => ({ ...value, stakeholders: event.target.value }))} rows={3} required /></label>
                <label><span>Site and operating context</span><textarea value={metadata.siteContext} onChange={(event) => setMetadata((value) => ({ ...value, siteContext: event.target.value }))} rows={3} required /></label>
              </div>
              <button className="button button-primary authoring-submit" disabled={metadataMutation.isPending}>{metadataMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <Database size={15} />}{metadataMutation.isPending ? 'Recording reviewed study…' : 'Record reviewed study'}<ArrowRight size={15} /></button>
              {metadataMutation.isError ? <p className="ingestion-inline-error" role="alert"><AlertTriangle size={15} />{messageFor(metadataMutation.error)}</p> : null}
              {metadataRecorded ? <div className="authoring-confirmed ingestion-confirmed" role="status"><CheckCircle2 size={18} /><div><strong>Catalogue record persisted</strong><span>{metadataRecorded} is now discoverable in the live Research Atlas. A source PDF may be uploaded separately.</span></div></div> : null}
            </form>
          ) : (
            <form className="authoring-form ingestion-form" onSubmit={submitDocument} aria-label="Upload private study PDF">
              <div className="authoring-form-intro"><span>02 / PRIVATE SOURCE</span><p>The request waits only for validation, a clean malware verdict, private storage, and durable queueing—not text extraction.</p></div>
              <label className="ingestion-file-field">
                <FileText size={25} />
                <span>{file ? file.name : 'Choose a genuine PDF up to 25 MB'}</span>
                <small>{file ? `${(file.size / 1_048_576).toFixed(2)} MB · ready for validation` : 'Scanned-image PDFs are routed to manual metadata review; OCR is outside v1.'}</small>
                <input type="file" accept="application/pdf,.pdf" onChange={(event) => { setFile(event.target.files?.[0]); uploadMutation.reset(); setJobId('') }} required />
              </label>
              <button className="button button-primary authoring-submit" disabled={!file || uploadMutation.isPending}>{uploadMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <FileUp size={15} />}{uploadMutation.isPending ? 'Scanning and securing…' : 'Secure and queue extraction'}<ArrowRight size={15} /></button>
              {uploadMutation.isError ? <p className="ingestion-inline-error" role="alert"><AlertTriangle size={15} />{messageFor(uploadMutation.error)}</p> : null}

              {jobId ? <section className="ingestion-job" aria-live="polite" aria-label="Persistent extraction status">
                <div className="ingestion-job-head"><div><span>DURABLE EXTRACTION JOB</span><strong>{job?.status ?? 'QUEUED'}</strong></div><code>{jobId.slice(0, 8)}</code></div>
                <progress max="100" value={job?.progressPercent ?? 0}>{job?.progressPercent ?? 0}%</progress>
                <div className="ingestion-job-metrics"><span><b>{job?.progressPercent ?? 0}%</b> progress</span><span><b>{job?.pageCount ?? 0}</b> pages</span><span><b>{job?.extractedCharacterCount ?? 0}</b> characters</span></div>
                {jobQuery.isError ? <p className="ingestion-inline-error" role="alert"><AlertTriangle size={15} />{messageFor(jobQuery.error)}</p> : null}
                {job && jobTerminal ? <div className={jobSuccessful ? 'ingestion-outcome is-ready' : 'ingestion-outcome is-review'}>{jobSuccessful ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}<div><strong>{jobSuccessful ? 'Extraction ready for curator publication review' : 'Manual curator review required'}</strong><span>{job.failureReason ?? (jobSuccessful ? 'Bounded text extraction finished. Publication remains a separate human decision.' : 'This source was preserved but cannot enter search automatically.')}</span></div></div> : <p className="ingestion-polling"><LoaderCircle className="is-spinning" size={14} />Polling the durable status record. Closing this panel does not cancel the job.</p>}
              </section> : null}
            </form>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
