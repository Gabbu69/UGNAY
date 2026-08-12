import { useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowRight, BookOpenCheck, CheckCircle2, Database, FileText, FileUp, LoaderCircle, LockKeyhole, X } from 'lucide-react'
import {
  ApiProblem,
  getDocumentImportJob,
  importStudyMetadata,
  publishExtractedStudy,
  uploadStudyDocument,
  type StudyMetadataInput,
} from '../lib/api'

const terminalStates = new Set(['EXTRACTED', 'CHARACTER_LIMIT_REACHED', 'TIMED_OUT', 'INTERRUPTED', 'FAILED', 'FAILED_STORAGE', 'ORPHAN_REVIEW'])

const emptyMetadata = (): StudyMetadataInput => ({
  institutionalCode: '',
  title: '',
  academicYear: '',
  abstractText: '',
  problemStatement: '',
  objectives: [],
  keywords: [],
  methodology: '',
  features: '',
  stakeholders: '',
  siteContext: '',
  department: '',
  program: '',
  authors: [],
  doi: '',
  repositoryIdentifier: '',
  dataSources: '',
  technology: '',
  intendedUsers: '',
  resultsText: '',
  researchAreas: [],
  visibility: 'RESTRICTED',
  lifecycleStatus: 'INCOMPLETE',
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
  const [authorText, setAuthorText] = useState('')
  const [researchAreaText, setResearchAreaText] = useState('')
  const [file, setFile] = useState<File>()
  const [jobId, setJobId] = useState('')
  const [metadataRecorded, setMetadataRecorded] = useState('')
  const [publicationJobId, setPublicationJobId] = useState('')

  const metadataMutation = useMutation({
    mutationFn: importStudyMetadata,
    onSuccess: async (_, recorded) => {
      setMetadataRecorded(recorded.title)
      setMetadata(emptyMetadata())
      setObjectiveText('')
      setKeywordText('')
      setAuthorText('')
      setResearchAreaText('')
      await queryClient.invalidateQueries({ queryKey: ['workspace'] })
      await queryClient.invalidateQueries({ queryKey: ['catalogue-search'] })
    },
  })
  const publishMutation = useMutation({
    mutationFn: (input: StudyMetadataInput) => publishExtractedStudy(publicationJobId, input),
    onSuccess: async (_, recorded) => {
      setMetadataRecorded(recorded.title)
      setMetadata(emptyMetadata())
      setObjectiveText('')
      setKeywordText('')
      setAuthorText('')
      setResearchAreaText('')
      setPublicationJobId('')
      await queryClient.invalidateQueries({ queryKey: ['workspace'] })
      await queryClient.invalidateQueries({ queryKey: ['catalogue-search'] })
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
    const authors = authorText.split(/[,\r\n]/).map((value) => value.trim()).filter(Boolean)
    const researchAreas = researchAreaText.split(/[,\r\n]/).map((value) => value.trim()).filter(Boolean)
    const reviewed = { ...metadata, objectives, keywords, authors, researchAreas }
    if (publicationJobId) await publishMutation.mutateAsync(reviewed).catch(() => undefined)
    else await metadataMutation.mutateAsync(reviewed).catch(() => undefined)
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
  const recording = metadataMutation.isPending || publishMutation.isPending
  const recordError = metadataMutation.error ?? publishMutation.error

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
            <button type="button" role="tab" aria-selected={mode === 'metadata'} className={mode === 'metadata' ? 'is-active' : ''} onClick={() => { setMode('metadata'); setPublicationJobId('') }}><BookOpenCheck size={15} />Study metadata</button>
            <button type="button" role="tab" aria-selected={mode === 'document'} className={mode === 'document' ? 'is-active' : ''} onClick={() => setMode('document')}><FileUp size={15} />Source PDF</button>
          </div>

          {mode === 'metadata' ? (
            <form className="authoring-form ingestion-form" onSubmit={submitMetadata} aria-label="Import reviewed study metadata">
              <div className="authoring-form-intro"><span>01 / REVIEWED RECORD</span><p>{publicationJobId ? `Publish reviewed metadata and link immutable extraction job ${publicationJobId.slice(0, 8)}. Nothing extracted is published automatically.` : 'Record the fields used by discovery and route analysis. Separate lines preserve objective boundaries.'}</p></div>
              {publicationJobId ? <div className="baseline-preview"><FileText size={17} /><div><strong>Linking a reviewed source PDF</strong><span>Publication remains a curator action; enter only evidence verified from the document.</span></div></div> : null}
              <div className="authoring-form-row">
                <label><span>Institutional code</span><input value={metadata.institutionalCode} onChange={(event) => setMetadata((value) => ({ ...value, institutionalCode: event.target.value }))} maxLength={80} placeholder="CIS-2025-018" required /></label>
                <label><span>Academic year</span><input value={metadata.academicYear} onChange={(event) => setMetadata((value) => ({ ...value, academicYear: event.target.value }))} maxLength={24} placeholder="2025-2026 (leave blank if unavailable)" /></label>
              </div>
              <div className="authoring-form-row">
                <label><span>Department code or name</span><input value={metadata.department ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, department: event.target.value }))} maxLength={160} placeholder="CICS" /></label>
                <label><span>Program {publicationJobId ? '' : '· optional'}</span><input value={metadata.program ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, program: event.target.value }))} maxLength={180} required={Boolean(publicationJobId)} /></label>
              </div>
              <div className="authoring-form-row">
                <label><span>Lifecycle status</span><select value={metadata.lifecycleStatus} onChange={(event) => setMetadata((value) => ({ ...value, lifecycleStatus: event.target.value as StudyMetadataInput['lifecycleStatus'] }))}><option value="PUBLISHED">Published</option><option value="COMPLETED">Completed</option><option value="INCOMPLETE">Incomplete</option><option value="SUSPENDED">Suspended</option><option value="ARCHIVED">Archived</option></select></label>
                <label><span>Visibility</span><select value={metadata.visibility} onChange={(event) => setMetadata((value) => ({ ...value, visibility: event.target.value as StudyMetadataInput['visibility'] }))}><option value="CAMPUS">Campus</option><option value="PUBLIC">Public</option><option value="RESTRICTED">Restricted</option><option value="EMBARGOED">Embargoed</option></select></label>
              </div>
              <label><span>Study title</span><input value={metadata.title} onChange={(event) => setMetadata((value) => ({ ...value, title: event.target.value }))} maxLength={500} required /></label>
              <label><span>Authors · comma or line separated {publicationJobId ? '' : '· optional'}</span><textarea value={authorText} onChange={(event) => setAuthorText(event.target.value)} rows={2} required={Boolean(publicationJobId)} /></label>
              <div className="authoring-form-row">
                <label><span>DOI · optional</span><input value={metadata.doi ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, doi: event.target.value }))} maxLength={255} /></label>
                <label><span>Repository identifier · optional</span><input value={metadata.repositoryIdentifier ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, repositoryIdentifier: event.target.value }))} maxLength={255} /></label>
              </div>
              <label><span>Abstract</span><textarea value={metadata.abstractText} onChange={(event) => setMetadata((value) => ({ ...value, abstractText: event.target.value }))} rows={4} required /></label>
              <label><span>Problem statement</span><textarea value={metadata.problemStatement} onChange={(event) => setMetadata((value) => ({ ...value, problemStatement: event.target.value }))} rows={4} required /></label>
              <label><span>Objectives · one per line</span><textarea value={objectiveText} onChange={(event) => setObjectiveText(event.target.value)} rows={4} placeholder={'Measure…\nDesign…\nEvaluate…'} required /></label>
              <label><span>Controlled keywords · comma separated</span><input value={keywordText} onChange={(event) => setKeywordText(event.target.value)} placeholder="flood, offline, barangay response" required /></label>
              <label><span>Curator-reviewed research areas · comma separated · optional</span><input value={researchAreaText} onChange={(event) => setResearchAreaText(event.target.value)} placeholder="Disaster informatics, Offline systems" /></label>
              <div className="authoring-form-row">
                <label><span>Methodology</span><textarea value={metadata.methodology} onChange={(event) => setMetadata((value) => ({ ...value, methodology: event.target.value }))} rows={3} required /></label>
                <label><span>Features / deliverables</span><textarea value={metadata.features} onChange={(event) => setMetadata((value) => ({ ...value, features: event.target.value }))} rows={3} required /></label>
              </div>
              <div className="authoring-form-row">
                <label><span>Stakeholders and intended users</span><textarea value={metadata.stakeholders} onChange={(event) => setMetadata((value) => ({ ...value, stakeholders: event.target.value }))} rows={3} required /></label>
                <label><span>Site and operating context</span><textarea value={metadata.siteContext} onChange={(event) => setMetadata((value) => ({ ...value, siteContext: event.target.value }))} rows={3} required /></label>
              </div>
              <div className="authoring-form-row">
                <label><span>Data sources · optional</span><textarea value={metadata.dataSources ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, dataSources: event.target.value }))} rows={3} /></label>
                <label><span>Technology · optional</span><textarea value={metadata.technology ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, technology: event.target.value }))} rows={3} /></label>
              </div>
              <label><span>Intended users · optional</span><textarea value={metadata.intendedUsers ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, intendedUsers: event.target.value }))} rows={2} /></label>
              <label><span>Results / findings · optional; never inferred</span><textarea value={metadata.resultsText ?? ''} onChange={(event) => setMetadata((value) => ({ ...value, resultsText: event.target.value }))} rows={4} /></label>
              <button className="button button-primary authoring-submit" disabled={recording}>{recording ? <LoaderCircle className="is-spinning" size={15} /> : <Database size={15} />}{recording ? 'Recording reviewed study…' : publicationJobId ? 'Publish and link reviewed study' : 'Record reviewed study'}<ArrowRight size={15} /></button>
              {recordError ? <p className="ingestion-inline-error" role="alert"><AlertTriangle size={15} />{messageFor(recordError)}</p> : null}
              {metadataRecorded ? <div className="authoring-confirmed ingestion-confirmed" role="status"><CheckCircle2 size={18} /><div><strong>Catalogue record persisted</strong><span>{metadataRecorded} is now discoverable in the live Research Atlas; any selected source PDF remains linked as immutable evidence.</span></div></div> : null}
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
                {job && jobTerminal ? <><div className={jobSuccessful ? 'ingestion-outcome is-ready' : 'ingestion-outcome is-review'}>{jobSuccessful ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}<div><strong>{jobSuccessful ? 'Extraction ready for curator publication review' : 'Manual curator review required'}</strong><span>{job.failureReason ?? (jobSuccessful ? 'Bounded text extraction finished. Publication remains a separate human decision.' : 'This source was preserved but cannot enter search automatically.')}</span></div></div>{jobSuccessful ? <button type="button" className="button button-dark full-width ingestion-review-button" onClick={() => { setPublicationJobId(job.jobId); setMode('metadata'); setMetadataRecorded('') }}><BookOpenCheck size={15} />Review metadata and link this PDF<ArrowRight size={15} /></button> : null}</> : <p className="ingestion-polling"><LoaderCircle className="is-spinning" size={14} />Polling the durable status record. Closing this panel does not cancel the job.</p>}
              </section> : null}
            </form>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
