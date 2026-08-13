import { useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FileArchive, KeyRound, Link2, LoaderCircle, PackageCheck, Plus, Trash2, X } from 'lucide-react'
import {
  ApiProblem,
  getCompletionEvidenceReferences,
  getCompletionPackage,
  updateCompletionEvidence,
  type CompletionEvidenceInput,
} from '../lib/api'
import type { CompletionPackageRecord, EvidenceReferenceRecord, EvidenceReferenceType } from '../types/domain'

interface CompletionEvidenceStudioProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  source: 'LIVE' | 'DEMO' | 'UNAVAILABLE'
  roles: string[]
  onRecorded: (message: string) => void
}

interface EvidenceReferenceDraft {
  clientId: number
  type: EvidenceReferenceType
  label: string
  location: string
  storedDocumentId: string
  sha256: string
}

const referenceTypes: Array<{ value: EvidenceReferenceType; label: string }> = [
  { value: 'DOCUMENT', label: 'Document' },
  { value: 'URL', label: 'Web URL' },
  { value: 'REPOSITORY', label: 'Repository' },
  { value: 'OUTPUT', label: 'Research output' },
  { value: 'TEST_RUN', label: 'Test run' },
  { value: 'DATASET', label: 'Dataset' },
  { value: 'OTHER', label: 'Other evidence' },
]

let nextReferenceId = 1

function emptyReference(): EvidenceReferenceDraft {
  return { clientId: nextReferenceId++, type: 'DOCUMENT', label: '', location: '', storedDocumentId: '', sha256: '' }
}

function messageFor(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  if (error instanceof DOMException && error.name === 'AbortError') return 'The continuity service timed out. Reload and try again.'
  return error instanceof Error ? error.message : 'The completion evidence could not be saved.'
}

function lines(value: string) {
  return value.split('\n').map((line) => line.trim()).filter(Boolean)
}

function referenceLocation(reference: EvidenceReferenceRecord) {
  return reference.location || reference.storedDocumentId || 'Location unavailable'
}

function CompletionEvidenceForm({
  value,
  persistedReferences,
  referencesUnavailable,
  isPending,
  onSubmit,
}: {
  value: CompletionPackageRecord
  persistedReferences: EvidenceReferenceRecord[]
  referencesUnavailable: boolean
  isPending: boolean
  onSubmit: (input: CompletionEvidenceInput) => Promise<void>
}) {
  const [repositoryUrl, setRepositoryUrl] = useState(value.repositoryUrl ?? '')
  const [commitHash, setCommitHash] = useState(value.commitHash ?? '')
  const [setupInstructions, setSetupInstructions] = useState(value.setupInstructions ?? '')
  const [limitations, setLimitations] = useState(value.limitations.join('\n'))
  const [recommendations, setRecommendations] = useState(value.recommendations.join('\n'))
  const [unfinishedWork, setUnfinishedWork] = useState(value.unfinishedWork.join('\n'))
  const [references, setReferences] = useState<EvidenceReferenceDraft[]>([emptyReference()])
  const [validationError, setValidationError] = useState('')

  const updateReference = (clientId: number, patch: Partial<EvidenceReferenceDraft>) => {
    setReferences((current) => current.map((reference) => reference.clientId === clientId ? { ...reference, ...patch } : reference))
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setValidationError('')
    const evidenceReferences = references.map((reference) => ({
      type: reference.type,
      label: reference.label.trim(),
      ...(reference.location.trim() ? { location: reference.location.trim() } : {}),
      ...(reference.storedDocumentId.trim() ? { storedDocumentId: reference.storedDocumentId.trim() } : {}),
      ...(reference.sha256.trim() ? { sha256: reference.sha256.trim().toLowerCase() } : {}),
    }))
    if (evidenceReferences.some((reference) => !reference.label || (!reference.location && !reference.storedDocumentId))) {
      setValidationError('Each evidence reference needs a label and either a location or a stored document ID.')
      return
    }
    await onSubmit({
      repositoryUrl: repositoryUrl.trim(),
      commitHash: commitHash.trim(),
      setupInstructions: setupInstructions.trim(),
      limitations: lines(limitations),
      recommendations: lines(recommendations),
      unfinishedWork: lines(unfinishedWork),
      evidenceReferences,
    })
  }

  return (
    <form className="completion-evidence-form" onSubmit={submit}>
      <section className="completion-form-section">
        <div className="completion-section-title"><FileArchive size={17} /><div><span>01 / REPRODUCIBLE SOURCE</span><h3>Pin the exact handoff</h3></div></div>
        <div className="authoring-form-row"><label><span>Repository URL</span><input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} maxLength={700} placeholder="https://git.example.edu/project" required /></label><label><span>Commit or release tag</span><input value={commitHash} onChange={(event) => setCommitHash(event.target.value)} maxLength={80} placeholder="v1.0 / full commit hash" required /></label></div>
        <label><span>Setup and access instructions</span><textarea value={setupInstructions} onChange={(event) => setSetupInstructions(event.target.value)} rows={4} required /></label>
        <p className="completion-field-note">Repository and rights readiness are calculated by the server from persisted records. A typed reference is required; this form cannot assign its own readiness score.</p>
      </section>

      <section className="completion-form-section">
        <div className="completion-section-title"><PackageCheck size={17} /><div><span>02 / SERVER ASSESSMENT</span><h3>Readiness from verified traces</h3></div></div>
        <div className="criterion-editor-list criterion-assessment-list">{value.criteria.length ? value.criteria.map((criterion) => {
          const criterionValue = criterion.value
          const hasValue = criterionValue != null
          return <article key={criterion.key}><header><strong>{criterion.label}</strong><span>{hasValue ? `${Math.round(criterionValue * criterion.weight)}/${criterion.weight}` : criterion.state}</span></header>{hasValue ? <i role="meter" aria-label={`${criterion.label}: ${Math.round(criterionValue * 100)}%`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(criterionValue * 100)}><b style={{ width: `${criterionValue * 100}%` }} /></i> : null}<p>{criterion.explanation || 'No assessment explanation is available.'}</p><small>{criterion.source || 'Source unavailable'}{criterion.assessedAt ? ` · ${new Date(criterion.assessedAt).toLocaleString()}` : ''}</small></article>
        }) : <p className="completion-unassessed">UNASSESSED · No server-derived criteria are available.</p>}</div>
      </section>

      <section className="completion-form-section">
        <div className="completion-section-title"><Link2 size={17} /><div><span>03 / EVIDENCE REFERENCES</span><h3>Append inspectable evidence</h3></div></div>
        <p className="completion-field-note">Add a document, URL, repository, output, test run, or dataset reference. New references are appended to the project record; existing evidence is never silently replaced.</p>
        <div className="persisted-reference-list" aria-label="Persisted completion evidence references">
          {referencesUnavailable ? <p className="completion-unassessed">UNAVAILABLE · Persisted references could not be loaded.</p> : persistedReferences.length ? persistedReferences.map((reference) => <article key={reference.id}><div><span>{reference.type.replace('_', ' ')}</span><strong>{reference.label}</strong><small>{referenceLocation(reference)}</small></div><em>{reference.verificationState || 'UNASSESSED'}</em></article>) : <p className="completion-unassessed">UNASSESSED · No completion evidence references are persisted yet.</p>}
        </div>
        <div className="evidence-reference-editor">{references.map((reference, index) => <fieldset key={reference.clientId}><legend>New reference {index + 1}</legend><div className="authoring-form-row"><label><span>Evidence type</span><select value={reference.type} onChange={(event) => updateReference(reference.clientId, { type: event.target.value as EvidenceReferenceType })}>{referenceTypes.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label><span>Evidence label</span><input value={reference.label} onChange={(event) => updateReference(reference.clientId, { label: event.target.value })} maxLength={240} placeholder="What a reviewer will inspect" required /></label></div><div className="authoring-form-row"><label><span>Location or URL</span><input type={['URL', 'REPOSITORY'].includes(reference.type) ? 'url' : 'text'} value={reference.location} onChange={(event) => updateReference(reference.clientId, { location: event.target.value })} maxLength={2000} placeholder="URL, repository path, or preserved output location" /></label><label><span>Stored document ID</span><input value={reference.storedDocumentId} onChange={(event) => updateReference(reference.clientId, { storedDocumentId: event.target.value })} maxLength={80} placeholder="Use when the document is already in UGNAY" /></label></div><label><span>SHA-256 (optional)</span><input value={reference.sha256} onChange={(event) => updateReference(reference.clientId, { sha256: event.target.value })} pattern="[A-Fa-f0-9]{64}" maxLength={64} placeholder="64 hexadecimal characters" /></label><button type="button" className="text-button reference-remove" onClick={() => setReferences((current) => current.length === 1 ? [emptyReference()] : current.filter((item) => item.clientId !== reference.clientId))}><Trash2 size={14} /> {references.length === 1 ? 'Clear reference' : 'Remove reference'}</button></fieldset>)}</div>
        <button type="button" className="button button-ghost reference-add" onClick={() => setReferences((current) => [...current, emptyReference()])}><Plus size={15} /> Add another reference</button>
        {validationError ? <p className="authoring-error completion-inline-error" role="alert">{validationError}</p> : null}
      </section>

      <section className="completion-form-section">
        <div className="completion-section-title"><KeyRound size={17} /><div><span>04 / HONEST CONTINUITY</span><h3>Preserve what remains open</h3></div></div>
        <p className="completion-field-note">Use one structured item per line. These become successor context without rewriting the predecessor.</p>
        <label><span>Known limitations</span><textarea value={limitations} onChange={(event) => setLimitations(event.target.value)} rows={3} placeholder="One limitation per line" /></label>
        <label><span>Recommendations</span><textarea value={recommendations} onChange={(event) => setRecommendations(event.target.value)} rows={3} placeholder="One recommendation per line" /></label>
        <label><span>Unfinished work</span><textarea value={unfinishedWork} onChange={(event) => setUnfinishedWork(event.target.value)} rows={3} placeholder="One unfinished item per line" /></label>
      </section>

      <button className="button button-primary completion-submit" disabled={isPending}>{isPending ? <LoaderCircle className="is-spinning" size={16} /> : <PackageCheck size={16} />}{isPending ? 'Recalculating readiness...' : 'Append evidence and recalculate'}</button>
    </form>
  )
}

export function CompletionEvidenceStudio({
  open,
  onOpenChange,
  projectId,
  source,
  roles,
  onRecorded,
}: CompletionEvidenceStudioProps) {
  const queryClient = useQueryClient()
  const [recorded, setRecorded] = useState('')
  const isLiveAuthor = source === 'LIVE' && roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const packageQuery = useQuery({
    queryKey: ['completion-package', projectId],
    queryFn: () => getCompletionPackage(projectId),
    enabled: open && isLiveAuthor && Boolean(projectId),
    staleTime: 10_000,
  })
  const referencesQuery = useQuery({
    queryKey: ['completion-evidence-references', projectId],
    queryFn: () => getCompletionEvidenceReferences(projectId),
    enabled: open && isLiveAuthor && Boolean(projectId),
    staleTime: 10_000,
  })
  const mutation = useMutation({
    mutationFn: (input: CompletionEvidenceInput) => updateCompletionEvidence(projectId, input),
    onSuccess: async (result) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['workspace'] }),
        queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['traceability', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['completion-package', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['completion-evidence-references', projectId] }),
      ])
      const message = result.artifact.readinessScore == null
        ? `Continuity evidence saved; readiness remains ${result.artifact.readinessState}.`
        : `Continuity evidence saved at ${Math.round(result.artifact.readinessScore)}% server-derived readiness.`
      setRecorded(message)
      onRecorded(message)
    },
  })

  const submit = async (input: CompletionEvidenceInput) => {
    setRecorded('')
    try {
      await mutation.mutateAsync(input)
    } catch {
      // The server's exact readiness or validation error remains visible in the drawer.
    }
  }

  const isLoading = packageQuery.isLoading || referencesQuery.isLoading

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="drawer-overlay" />
        <Dialog.Content className="completion-evidence-drawer" aria-describedby="completion-evidence-description">
          <header className="authoring-head">
            <div className="authoring-mark"><PackageCheck size={20} /></div>
            <div><span>CONTINUITY PACKAGE STUDIO</span><Dialog.Title>Leave a project others can resume</Dialog.Title></div>
            <Dialog.Close className="icon-button" aria-label="Close continuity package studio"><X size={19} /></Dialog.Close>
          </header>
          <Dialog.Description id="completion-evidence-description">Append inspectable references and honest open work. The server calculates readiness from persisted evidence and traces.</Dialog.Description>

          {!isLiveAuthor ? <div className="authoring-readonly" role="status"><KeyRound size={21} /><div><strong>Live authoring is locked</strong><p>Sign in with a project authoring role. Demo continuity records remain read-only.</p></div></div> : null}
          {isLiveAuthor && isLoading ? <div className="authoring-loading" role="status"><LoaderCircle className="is-spinning" size={18} />Loading the current continuity evidence...</div> : null}
          {isLiveAuthor && packageQuery.isError ? <p className="authoring-error" role="alert">{messageFor(packageQuery.error)}</p> : null}
          {packageQuery.data ? <CompletionEvidenceForm key={`${packageQuery.data.id}-${packageQuery.data.readinessScore}-${referencesQuery.data?.length ?? 0}`} value={packageQuery.data} persistedReferences={referencesQuery.data ?? []} referencesUnavailable={referencesQuery.isError} isPending={mutation.isPending} onSubmit={submit} /> : null}
          {mutation.error ? <p className="authoring-error" role="alert">{messageFor(mutation.error)}</p> : null}
          {recorded ? <div className="authoring-confirmed" role="status"><CheckCircle2 size={18} /><div><strong>Server record confirmed</strong><span>{recorded}</span></div></div> : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
