import { useMemo, useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRight,
  CheckCircle2,
  FilePlus2,
  GitMerge,
  LoaderCircle,
  LockKeyhole,
  ShieldCheck,
  TestTube2,
  X,
} from 'lucide-react'
import {
  ApiProblem,
  approveProjectBaseline,
  createTraceItem,
  createTraceLink,
  getProjectTraceability,
  recordTestExecution,
  type TraceItemInput,
  type TraceLinkInput,
  type TestExecutionInput,
} from '../lib/api'
import type { TraceItemRecord, TraceItemType } from '../types/domain'

type AuthoringMode = 'item' | 'link' | 'test' | 'baseline'

interface EvidenceAuthoringStudioProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  source: 'LIVE' | 'DEMO' | 'UNAVAILABLE'
  roles: string[]
  onRecorded: (message: string) => void
}

const itemTypes: TraceItemType[] = ['OBJECTIVE', 'REQUIREMENT', 'FEATURE', 'TEST_CASE', 'OUTPUT']
const emptyTraceItems: TraceItemRecord[] = []

const relationshipRules: Record<TraceItemType, Array<{ type: string; target: TraceItemType; label: string }>> = {
  PROBLEM: [{ type: 'MOTIVATES', target: 'OBJECTIVE', label: 'motivates objective' }],
  OBJECTIVE: [{ type: 'DECOMPOSES_TO', target: 'REQUIREMENT', label: 'decomposes to requirement' }],
  REQUIREMENT: [
    { type: 'REALIZED_BY', target: 'FEATURE', label: 'realized by feature' },
    { type: 'VERIFIED_BY', target: 'TEST_CASE', label: 'verified by test case' },
  ],
  FEATURE: [
    { type: 'VERIFIED_BY', target: 'TEST_CASE', label: 'verified by test case' },
    { type: 'CONTRIBUTES_TO', target: 'OUTPUT', label: 'contributes to output' },
  ],
  TEST_CASE: [],
  OUTPUT: [],
}

function messageFor(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  if (error instanceof DOMException && error.name === 'AbortError') return 'The evidence service timed out. Reload the project and retry.'
  return error instanceof Error ? error.message : 'The evidence action could not be recorded.'
}

async function refreshEvidence(queryClient: ReturnType<typeof useQueryClient>, projectId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['workspace'] }),
    queryClient.invalidateQueries({ queryKey: ['project', projectId] }),
    queryClient.invalidateQueries({ queryKey: ['traceability', projectId] }),
    queryClient.invalidateQueries({ queryKey: ['completion-package', projectId] }),
  ])
}

function itemLabel(item: TraceItemRecord) {
  return `${item.key} - ${item.title}`
}

export function EvidenceAuthoringStudio({
  open,
  onOpenChange,
  projectId,
  source,
  roles,
  onRecorded,
}: EvidenceAuthoringStudioProps) {
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<AuthoringMode>('item')
  const [item, setItem] = useState<TraceItemInput>({
    key: '', type: 'REQUIREMENT', title: '', description: '', priority: 'MUST', acceptanceCriteria: '', verificationMethod: '',
  })
  const [link, setLink] = useState<TraceLinkInput>({ sourceId: '', targetId: '', relationshipType: '', rationale: '' })
  const [execution, setExecution] = useState<TestExecutionInput>({ testItemId: '', status: 'PASSED', buildIdentifier: '', evidenceConfirmed: false })
  const [baselineRationale, setBaselineRationale] = useState('')
  const [recorded, setRecorded] = useState('')
  const isLiveAuthor = source === 'LIVE' && roles.some((role) => ['STUDENT', 'ADVISER', 'COORDINATOR'].includes(role))
  const isCoordinator = isLiveAuthor && roles.includes('COORDINATOR')

  const traceQuery = useQuery({
    queryKey: ['traceability', projectId],
    queryFn: () => getProjectTraceability(projectId),
    enabled: open && isLiveAuthor && Boolean(projectId),
    staleTime: 10_000,
  })
  const items = traceQuery.data?.items ?? emptyTraceItems
  const selectedSource = items.find((candidate) => candidate.id === link.sourceId)
  const relationshipOptions = selectedSource ? relationshipRules[selectedSource.type] : []
  const selectedRule = relationshipOptions.find((rule) => rule.type === link.relationshipType)
  const targetOptions = selectedRule
    ? items.filter((candidate) => candidate.type === selectedRule.target && candidate.id !== selectedSource?.id)
    : []
  const approvedTests = useMemo(
    () => items.filter((candidate) => candidate.type === 'TEST_CASE' && candidate.lifecycleStatus === 'APPROVED'),
    [items],
  )

  const finish = async (message: string) => {
    await refreshEvidence(queryClient, projectId)
    setRecorded(message)
    onRecorded(message)
  }

  const itemMutation = useMutation({
    mutationFn: (value: TraceItemInput) => createTraceItem(projectId, value),
    onSuccess: () => finish('Draft artifact recorded; alignment findings were recalculated from the working chain.'),
  })
  const linkMutation = useMutation({
    mutationFn: (value: TraceLinkInput) => createTraceLink(projectId, value),
    onSuccess: () => finish('Typed relationship recorded and validated within this project.'),
  })
  const executionMutation = useMutation({
    mutationFn: (value: TestExecutionInput) => recordTestExecution(projectId, value),
    onSuccess: () => finish('Test execution recorded against the current approved test revision.'),
  })
  const baselineMutation = useMutation({
    mutationFn: (rationale: string) => approveProjectBaseline(projectId, rationale),
    onSuccess: () => finish('Coordinator approval created a new immutable baseline.'),
  })

  const activeMutation = mode === 'item' ? itemMutation : mode === 'link' ? linkMutation : mode === 'test' ? executionMutation : baselineMutation
  const activeError = activeMutation.error

  const chooseMode = (nextMode: AuthoringMode) => {
    setMode(nextMode)
    setRecorded('')
    itemMutation.reset()
    linkMutation.reset()
    executionMutation.reset()
    baselineMutation.reset()
  }

  const submitItem = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setRecorded('')
    const priority = item.type === 'REQUIREMENT' || item.type === 'TEST_CASE' ? item.priority : null
    try {
      await itemMutation.mutateAsync({ ...item, key: item.key.trim().toUpperCase(), priority })
      setItem((current) => ({ ...current, key: '', title: '', description: '', acceptanceCriteria: '', verificationMethod: '' }))
    } catch {
      // Server validation is shown in the evidence drawer.
    }
  }

  const submitLink = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setRecorded('')
    try {
      await linkMutation.mutateAsync(link)
      setLink({ sourceId: '', targetId: '', relationshipType: '', rationale: '' })
    } catch {
      // Server validation is shown in the evidence drawer.
    }
  }

  const submitExecution = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setRecorded('')
    try {
      await executionMutation.mutateAsync(execution)
      setExecution((current) => ({ ...current, buildIdentifier: '', evidenceConfirmed: false }))
    } catch {
      // Server validation is shown in the evidence drawer.
    }
  }

  const submitBaseline = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setRecorded('')
    try {
      await baselineMutation.mutateAsync(baselineRationale.trim())
      setBaselineRationale('')
    } catch {
      // Hard baseline blockers are rendered as server evidence, not hidden.
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="drawer-overlay" />
        <Dialog.Content className="evidence-authoring-drawer" aria-describedby="evidence-authoring-description">
          <header className="authoring-head">
            <div className="authoring-mark"><GitMerge size={20} /></div>
            <div><span>EVIDENCE AUTHORING STUDIO</span><Dialog.Title>Extend the chain with intent</Dialog.Title></div>
            <Dialog.Close className="icon-button" aria-label="Close evidence authoring studio"><X size={19} /></Dialog.Close>
          </header>
          <Dialog.Description id="evidence-authoring-description">
            Record evidence in the working chain. UGNAY validates direction, recalculates findings, and keeps approval separate.
          </Dialog.Description>

          {!isLiveAuthor ? (
            <div className="authoring-readonly" role="status"><LockKeyhole size={21} /><div><strong>Read-only evidence view</strong><p>Sign in with a student, adviser, or coordinator role against the live workspace to author evidence.</p></div></div>
          ) : (
            <>
              <div className="authoring-tabs" role="tablist" aria-label="Evidence action">
                <button type="button" role="tab" aria-selected={mode === 'item'} className={mode === 'item' ? 'is-active' : ''} onClick={() => chooseMode('item')}><FilePlus2 size={15} />Artifact</button>
                <button type="button" role="tab" aria-selected={mode === 'link'} className={mode === 'link' ? 'is-active' : ''} onClick={() => chooseMode('link')}><GitMerge size={15} />Relationship</button>
                <button type="button" role="tab" aria-selected={mode === 'test'} className={mode === 'test' ? 'is-active' : ''} onClick={() => chooseMode('test')}><TestTube2 size={15} />Execution</button>
                <button type="button" role="tab" aria-selected={mode === 'baseline'} className={mode === 'baseline' ? 'is-active' : ''} disabled={!isCoordinator} title={isCoordinator ? undefined : 'Coordinator permission required'} onClick={() => chooseMode('baseline')}><ShieldCheck size={15} />Baseline</button>
              </div>

              {traceQuery.isLoading ? <div className="authoring-loading" role="status"><LoaderCircle className="is-spinning" size={18} />Loading current evidence chain...</div> : null}
              {traceQuery.isError ? <p className="authoring-error" role="alert">{messageFor(traceQuery.error)}</p> : null}

              {mode === 'item' ? (
                <form className="authoring-form" onSubmit={submitItem} aria-label="Create trace artifact">
                  <div className="authoring-form-intro"><span>01 / WORKING ARTIFACT</span><p>Draft first. A coordinator later decides whether the complete chain is baseline-ready.</p></div>
                  <div className="authoring-form-row">
                    <label><span>Evidence type</span><select value={item.type} onChange={(event) => {
                      const type = event.target.value as TraceItemType
                      setItem((current) => ({ ...current, type, priority: type === 'REQUIREMENT' ? 'MUST' : type === 'TEST_CASE' ? 'MANDATORY' : null }))
                    }}>{itemTypes.map((type) => <option key={type} value={type}>{type.replace('_', ' ')}</option>)}</select></label>
                    <label><span>Trace key</span><input value={item.key} onChange={(event) => setItem((current) => ({ ...current, key: event.target.value }))} placeholder="REQ-04" pattern="[A-Za-z][A-Za-z0-9-]{1,63}" required /></label>
                  </div>
                  <label><span>Precise title</span><input value={item.title} onChange={(event) => setItem((current) => ({ ...current, title: event.target.value }))} maxLength={500} required /></label>
                  <label><span>Research or system rationale</span><textarea value={item.description} onChange={(event) => setItem((current) => ({ ...current, description: event.target.value }))} minLength={10} rows={3} required /></label>
                  {item.type === 'REQUIREMENT' || item.type === 'TEST_CASE' ? <label><span>Priority</span><select value={item.priority ?? ''} onChange={(event) => setItem((current) => ({ ...current, priority: event.target.value }))}>{(item.type === 'REQUIREMENT' ? ['MUST', 'SHOULD', 'COULD'] : ['MANDATORY', 'OPTIONAL']).map((priority) => <option key={priority}>{priority}</option>)}</select></label> : null}
                  {item.type === 'REQUIREMENT' ? <div className="authoring-form-row"><label><span>Measurable acceptance criteria</span><textarea value={item.acceptanceCriteria ?? ''} onChange={(event) => setItem((current) => ({ ...current, acceptanceCriteria: event.target.value }))} rows={3} required /></label><label><span>Verification method</span><textarea value={item.verificationMethod ?? ''} onChange={(event) => setItem((current) => ({ ...current, verificationMethod: event.target.value }))} rows={3} required /></label></div> : null}
                  <button className="button button-primary authoring-submit" disabled={itemMutation.isPending}>{itemMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <FilePlus2 size={15} />}{itemMutation.isPending ? 'Recording...' : 'Record draft artifact'}<ArrowRight size={15} /></button>
                </form>
              ) : null}

              {mode === 'link' ? (
                <form className="authoring-form" onSubmit={submitLink} aria-label="Create typed trace relationship">
                  <div className="authoring-form-intro"><span>02 / JUSTIFIED RELATIONSHIP</span><p>Only academically valid directions appear. The server rejects duplicates, cycles, and cross-project links.</p></div>
                  <label><span>Evidence source</span><select value={link.sourceId} onChange={(event) => setLink({ sourceId: event.target.value, targetId: '', relationshipType: '', rationale: link.rationale })} required><option value="">Choose a source artifact</option>{items.filter((candidate) => relationshipRules[candidate.type].length > 0).map((candidate) => <option key={candidate.id} value={candidate.id}>{itemLabel(candidate)}</option>)}</select></label>
                  <div className="authoring-form-row"><label><span>Relationship meaning</span><select value={link.relationshipType} onChange={(event) => setLink((current) => ({ ...current, relationshipType: event.target.value, targetId: '' }))} disabled={!selectedSource} required><option value="">Choose a typed direction</option>{relationshipOptions.map((rule) => <option key={rule.type} value={rule.type}>{rule.label}</option>)}</select></label><label><span>Evidence target</span><select value={link.targetId} onChange={(event) => setLink((current) => ({ ...current, targetId: event.target.value }))} disabled={!selectedRule} required><option value="">Choose a compatible target</option>{targetOptions.map((candidate) => <option key={candidate.id} value={candidate.id}>{itemLabel(candidate)}</option>)}</select></label></div>
                  <label><span>Why this relationship is valid</span><textarea value={link.rationale} onChange={(event) => setLink((current) => ({ ...current, rationale: event.target.value }))} minLength={10} rows={3} required /></label>
                  <button className="button button-primary authoring-submit" disabled={linkMutation.isPending || !traceQuery.data}>{linkMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <GitMerge size={15} />}{linkMutation.isPending ? 'Validating...' : 'Validate and record link'}<ArrowRight size={15} /></button>
                </form>
              ) : null}

              {mode === 'test' ? (
                <form className="authoring-form" onSubmit={submitExecution} aria-label="Record test execution">
                  <div className="authoring-form-intro"><span>03 / VERSION-BOUND VERIFICATION</span><p>A new result supersedes older executions for the same approved test. Evidence becomes stale when its dependency changes.</p></div>
                  <label><span>Approved test case</span><select value={execution.testItemId} onChange={(event) => setExecution((current) => ({ ...current, testItemId: event.target.value }))} required><option value="">Choose an approved test</option>{approvedTests.map((candidate) => <option key={candidate.id} value={candidate.id}>{itemLabel(candidate)}</option>)}</select></label>
                  <div className="authoring-form-row"><label><span>Observed result</span><select value={execution.status} onChange={(event) => setExecution((current) => ({ ...current, status: event.target.value as TestExecutionInput['status'] }))}><option>PASSED</option><option>FAILED</option><option>BLOCKED</option></select></label><label><span>Build / release identifier</span><input value={execution.buildIdentifier} onChange={(event) => setExecution((current) => ({ ...current, buildIdentifier: event.target.value }))} maxLength={160} placeholder="v1.0-rc3 / 4f61ac2" required /></label></div>
                  <label className="authoring-check"><input type="checkbox" checked={execution.evidenceConfirmed} onChange={(event) => setExecution((current) => ({ ...current, evidenceConfirmed: event.target.checked }))} /><span><b>Execution evidence confirmed</b><small>Logs, screenshots, report, or equivalent evidence is preserved and accessible.</small></span></label>
                  {approvedTests.length === 0 && traceQuery.isSuccess ? <p className="authoring-note">No approved test case is available. Add and link a test, then obtain a baseline approval before recording its execution.</p> : null}
                  <button className="button button-primary authoring-submit" disabled={executionMutation.isPending || approvedTests.length === 0}>{executionMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <TestTube2 size={15} />}{executionMutation.isPending ? 'Recording...' : 'Record observed execution'}<ArrowRight size={15} /></button>
                </form>
              ) : null}

              {mode === 'baseline' ? (
                <form className="authoring-form" onSubmit={submitBaseline} aria-label="Approve evidence baseline">
                  <div className="authoring-form-intro"><span>04 / HUMAN CONTROL POINT</span><p>Approval freezes a new immutable baseline only after every hard readiness and chain rule passes.</p></div>
                  <div className="baseline-preview"><ShieldCheck size={20} /><div><strong>Candidate baseline {Number(traceQuery.data?.baselineNumber ?? 0) + 1}</strong><span>{items.length} artifacts and {traceQuery.data?.links.length ?? 0} active relationships will be validated.</span></div></div>
                  <label><span>Coordinator approval rationale</span><textarea value={baselineRationale} onChange={(event) => setBaselineRationale(event.target.value)} minLength={20} rows={4} placeholder="Explain why this evidence chain is academically ready to freeze..." required /></label>
                  <button className="button button-primary authoring-submit" disabled={baselineMutation.isPending || !isCoordinator}>{baselineMutation.isPending ? <LoaderCircle className="is-spinning" size={15} /> : <ShieldCheck size={15} />}{baselineMutation.isPending ? 'Checking hard gates...' : 'Approve immutable baseline'}<ArrowRight size={15} /></button>
                </form>
              ) : null}

              {activeError ? <p className="authoring-error" role="alert">{messageFor(activeError)}</p> : null}
              {recorded ? <div className="authoring-confirmed" role="status"><CheckCircle2 size={18} /><div><strong>Server record confirmed</strong><span>{recorded}</span></div></div> : null}
            </>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
