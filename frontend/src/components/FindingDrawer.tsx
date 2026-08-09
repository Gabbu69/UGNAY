import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ArrowRight, FileWarning, ShieldAlert, X } from 'lucide-react'
import { actOnFinding, ApiProblem, type FindingAction } from '../lib/api'
import type { Finding } from '../types/domain'
import { StatusPill } from './Primitives'

const severityTone = { INFO: 'teal', WARNING: 'amber', HIGH: 'amber', CRITICAL: 'coral' } as const

interface FindingDrawerProps {
  finding?: Finding
  open: boolean
  projectId: string
  roles: string[]
  onOpenChange: (open: boolean) => void
  onOpenArtifact: (itemCode: string) => void
}

export function FindingDrawer({ finding, open, projectId, roles, onOpenChange, onOpenArtifact }: FindingDrawerProps) {
  const queryClient = useQueryClient()
  const [validationError, setValidationError] = useState('')
  const canReview = roles.some((role) => role === 'ADVISER' || role === 'COORDINATOR')
  const canAccept = roles.includes('COORDINATOR')
  const mutation = useMutation({
    mutationFn: (input: { action: FindingAction; rationale: string; expiresAt?: string }) =>
      actOnFinding(projectId, finding?.id ?? '', input.action, input.rationale, input.expiresAt),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['workspace'] })
      setValidationError('')
      onOpenChange(false)
    },
  })
  const actionError = mutation.error instanceof ApiProblem ? mutation.error.detail : mutation.error?.message
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const action = ((event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value as FindingAction | undefined
    const rationale = String(data.get('rationale') ?? '').trim()
    const expiry = String(data.get('expiry') ?? '')
    if (!action) return
    if (rationale.length < 12) {
      setValidationError('Record at least 12 characters of reviewer rationale.')
      return
    }
    if (action === 'accept' && !expiry) {
      setValidationError('Accepted exceptions require an expiry date.')
      return
    }
    setValidationError('')
    mutation.mutate({ action, rationale, expiresAt: expiry ? new Date(`${expiry}T23:59:59Z`).toISOString() : undefined })
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="drawer-overlay" />
        <Dialog.Content className="finding-drawer">
          {finding ? <>
            <div className="drawer-head"><div className="finding-glyph"><ShieldAlert size={20} /></div><Dialog.Close className="icon-button" aria-label="Close finding"><X size={20} /></Dialog.Close></div>
            <div className="eyebrow"><span />Explainable finding · {finding.code}</div>
            <Dialog.Title>{finding.title}</Dialog.Title>
            <Dialog.Description>{finding.explanation}</Dialog.Description>
            <div className="drawer-tags"><StatusPill tone={severityTone[finding.severity]}>{finding.severity}</StatusPill><StatusPill>{finding.state}</StatusPill><code>{finding.rule}</code></div>
            <section className="evidence-stack"><h3><FileWarning size={16} /> Evidence used</h3>{finding.evidence.map((item) => <div key={item}><span />{item}</div>)}</section>
            <section className="next-action"><span>Next valid action</span><p>{finding.nextAction}</p></section>
            {canReview ? <form className="finding-action-form" onSubmit={submit}>
              <label>Reviewer rationale<textarea name="rationale" minLength={12} required placeholder="Explain why this condition is resolved, accepted temporarily, or must be reopened." /></label>
              {canAccept && (finding.state === 'OPEN' || finding.state === 'REOPENED') ? <label>Exception expiry<input name="expiry" type="date" min={new Date().toISOString().slice(0, 10)} /></label> : null}
              {validationError || actionError ? <p className="form-alert" role="alert">{validationError || actionError}</p> : null}
              <div className="drawer-actions">
                {finding.state === 'OPEN' || finding.state === 'REOPENED' ? <button type="submit" value="resolve" className="button button-secondary" disabled={mutation.isPending}>Resolve condition</button> : <button type="submit" value="reopen" className="button button-secondary" disabled={mutation.isPending}>Reopen finding</button>}
                {canAccept && (finding.state === 'OPEN' || finding.state === 'REOPENED') ? <button type="submit" value="accept" className="button button-primary" disabled={mutation.isPending}>Accept until expiry</button> : null}
              </div>
            </form> : <p className="permission-note">An adviser may resolve findings; only a coordinator may accept a time-bound exception.</p>}
            <button type="button" className="button button-primary full-width" onClick={() => onOpenArtifact(finding.itemCode)}>Open {finding.itemCode}<ArrowRight size={15} /></button>
            <p className="rule-disclaimer">UGNAY detected a traceability condition. A qualified reviewer—not the system—decides the academic response.</p>
          </> : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
