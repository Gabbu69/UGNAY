import { useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2, FileArchive, KeyRound, LoaderCircle, PackageCheck, X } from 'lucide-react'
import {
  ApiProblem,
  getCompletionPackage,
  updateCompletionEvidence,
  type CompletionEvidenceInput,
} from '../lib/api'
import type { CompletionPackageRecord } from '../types/domain'

interface CompletionEvidenceStudioProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  source: 'LIVE' | 'DEMO' | 'UNAVAILABLE'
  roles: string[]
  onRecorded: (message: string) => void
}

function messageFor(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  if (error instanceof DOMException && error.name === 'AbortError') return 'The continuity service timed out. Reload and try again.'
  return error instanceof Error ? error.message : 'The completion evidence could not be saved.'
}

function lines(value: string) {
  return value.split('\n').map((line) => line.trim()).filter(Boolean)
}

function CompletionEvidenceForm({
  value,
  isPending,
  onSubmit,
}: {
  value: CompletionPackageRecord
  isPending: boolean
  onSubmit: (input: CompletionEvidenceInput) => Promise<void>
}) {
  const [rightsConfirmed, setRightsConfirmed] = useState(value.codeDataRightsConfirmed)
  const [repositoryUrl, setRepositoryUrl] = useState(value.repositoryUrl ?? '')
  const [commitHash, setCommitHash] = useState(value.commitHash ?? '')
  const [setupInstructions, setSetupInstructions] = useState(value.setupInstructions ?? '')
  const [limitations, setLimitations] = useState(value.limitations.join('\n'))
  const [recommendations, setRecommendations] = useState(value.recommendations.join('\n'))
  const [unfinishedWork, setUnfinishedWork] = useState(value.unfinishedWork.join('\n'))
  const [criteria, setCriteria] = useState(() => value.criteria.map((criterion) => ({
    ...criterion,
    percentage: Math.round(criterion.completion * 100),
  })))

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    await onSubmit({
      codeDataRightsConfirmed: rightsConfirmed,
      repositoryUrl: repositoryUrl.trim(),
      commitHash: commitHash.trim(),
      setupInstructions: setupInstructions.trim(),
      limitations: lines(limitations),
      recommendations: lines(recommendations),
      unfinishedWork: lines(unfinishedWork),
      criteria: criteria.map((criterion) => ({
        key: criterion.key,
        completion: criterion.percentage / 100,
        explanation: criterion.explanation.trim(),
      })),
    })
  }

  return (
    <form className="completion-evidence-form" onSubmit={submit}>
      <section className="completion-form-section">
        <div className="completion-section-title"><FileArchive size={17} /><div><span>01 / REPRODUCIBLE SOURCE</span><h3>Pin the exact handoff</h3></div></div>
        <div className="authoring-form-row"><label><span>Repository URL</span><input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} maxLength={700} placeholder="https://git.example.edu/project" required /></label><label><span>Commit or release tag</span><input value={commitHash} onChange={(event) => setCommitHash(event.target.value)} maxLength={80} placeholder="v1.0 / full commit hash" required /></label></div>
        <label><span>Setup and access instructions</span><textarea value={setupInstructions} onChange={(event) => setSetupInstructions(event.target.value)} rows={4} required /></label>
        <label className="authoring-check rights-confirmation"><input type="checkbox" checked={rightsConfirmed} onChange={(event) => setRightsConfirmed(event.target.checked)} /><span><b>Code and data rights are confirmed</b><small>Future authorized students can obtain the repository and required datasets under the recorded conditions.</small></span></label>
      </section>

      <section className="completion-form-section">
        <div className="completion-section-title"><PackageCheck size={17} /><div><span>02 / READINESS EVIDENCE</span><h3>Explain every criterion</h3></div></div>
        <div className="criterion-editor-list">{criteria.map((criterion, index) => <fieldset key={criterion.key}><legend>{criterion.label}<small>{criterion.weight} points</small></legend><div className="criterion-meter-row"><input aria-label={`${criterion.label} completion percentage`} type="range" min="0" max="100" step="5" value={criterion.percentage} onChange={(event) => setCriteria((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, percentage: Number(event.target.value) } : item))} /><output>{criterion.percentage}%</output></div><textarea aria-label={`${criterion.label} evidence explanation`} value={criterion.explanation} onChange={(event) => setCriteria((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, explanation: event.target.value } : item))} rows={2} placeholder="Name the preserved evidence and its location." required /></fieldset>)}</div>
      </section>

      <section className="completion-form-section">
        <div className="completion-section-title"><KeyRound size={17} /><div><span>03 / HONEST CONTINUITY</span><h3>Preserve what remains open</h3></div></div>
        <p className="completion-field-note">Use one structured item per line. These become successor context without rewriting the predecessor.</p>
        <label><span>Known limitations</span><textarea value={limitations} onChange={(event) => setLimitations(event.target.value)} rows={3} placeholder="One limitation per line" /></label>
        <label><span>Recommendations</span><textarea value={recommendations} onChange={(event) => setRecommendations(event.target.value)} rows={3} placeholder="One recommendation per line" /></label>
        <label><span>Unfinished work</span><textarea value={unfinishedWork} onChange={(event) => setUnfinishedWork(event.target.value)} rows={3} placeholder="One unfinished item per line" /></label>
      </section>

      <button className="button button-primary completion-submit" disabled={isPending}>{isPending ? <LoaderCircle className="is-spinning" size={16} /> : <PackageCheck size={16} />}{isPending ? 'Recalculating readiness...' : 'Save handoff evidence'}</button>
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
  const mutation = useMutation({
    mutationFn: (input: CompletionEvidenceInput) => updateCompletionEvidence(projectId, input),
    onSuccess: async (result) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['workspace'] }),
        queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['traceability', projectId] }),
        queryClient.invalidateQueries({ queryKey: ['completion-package', projectId] }),
      ])
      const message = `Continuity package saved at ${Math.round(result.artifact.readinessScore)}% readiness.`
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
          <Dialog.Description id="completion-evidence-description">Preserve reproducible source, explicit rights, known limits, and weighted handoff evidence before completion is assessed.</Dialog.Description>

          {!isLiveAuthor ? <div className="authoring-readonly" role="status"><KeyRound size={21} /><div><strong>Live authoring is locked</strong><p>Sign in with a project authoring role. Demo continuity records remain read-only.</p></div></div> : null}
          {isLiveAuthor && packageQuery.isLoading ? <div className="authoring-loading" role="status"><LoaderCircle className="is-spinning" size={18} />Loading the current continuity package...</div> : null}
          {isLiveAuthor && packageQuery.isError ? <p className="authoring-error" role="alert">{messageFor(packageQuery.error)}</p> : null}
          {packageQuery.data ? <CompletionEvidenceForm key={`${packageQuery.data.id}-${packageQuery.data.readinessScore}`} value={packageQuery.data} isPending={mutation.isPending} onSubmit={submit} /> : null}
          {mutation.error ? <p className="authoring-error" role="alert">{messageFor(mutation.error)}</p> : null}
          {recorded ? <div className="authoring-confirmed" role="status"><CheckCircle2 size={18} /><div><strong>Server record confirmed</strong><span>{recorded}</span></div></div> : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
