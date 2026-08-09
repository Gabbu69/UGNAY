import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Copy, UserPlus, Users } from 'lucide-react'
import {
  ApiProblem, createInvitation, grantProjectMembership, listInvitations, listProjectMemberships, listUsers,
} from '../lib/api'

const roles = ['STUDENT', 'ADVISER', 'COORDINATOR', 'CURATOR']
const projectRoles = ['STUDENT', 'ADVISER', 'COORDINATOR']

export function CuratorAccessPanel({ projectId }: { projectId?: string }) {
  const queryClient = useQueryClient()
  const [copied, setCopied] = useState(false)
  const users = useQuery({ queryKey: ['admin-users'], queryFn: listUsers })
  const invitations = useQuery({ queryKey: ['admin-invitations'], queryFn: listInvitations })
  const memberships = useQuery({
    queryKey: ['project-memberships', projectId], queryFn: () => listProjectMemberships(projectId ?? ''), enabled: Boolean(projectId && projectId !== 'unavailable'),
  })
  const inviteMutation = useMutation({
    mutationFn: (input: { email: string; role: string }) => createInvitation(input.email, input.role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-invitations'] }),
  })
  const membershipMutation = useMutation({
    mutationFn: (input: { userId: string; role: string }) => grantProjectMembership(projectId ?? '', input.userId, input.role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['project-memberships', projectId] }),
  })
  const invite = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    setCopied(false)
    inviteMutation.mutate({ email: String(form.get('email')), role: String(form.get('role')) })
  }
  const grant = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    membershipMutation.mutate({ userId: String(form.get('userId')), role: String(form.get('role')) })
  }
  const error = inviteMutation.error ?? membershipMutation.error ?? users.error ?? invitations.error ?? memberships.error
  const errorText = error instanceof ApiProblem ? error.detail : error instanceof Error ? error.message : undefined

  return <details className="curator-access-panel">
    <summary><Users size={15} /><span><strong>Curator access desk</strong><small>Invitations and selected-project membership</small></span></summary>
    <div className="curator-access-body">
      {errorText ? <p className="account-error" role="alert">{errorText}</p> : null}
      <form onSubmit={invite}>
        <span>ISSUE INVITATION</span>
        <label>Email<input name="email" type="email" required placeholder="researcher@university.edu" /></label>
        <label>Account role<select name="role">{roles.map((role) => <option key={role}>{role}</option>)}</select></label>
        <button type="submit" disabled={inviteMutation.isPending}><UserPlus size={14} /> Create 72-hour invite</button>
      </form>
      {inviteMutation.data?.oneTimeToken ? <div className="invite-token" role="status"><span>ONE-TIME TOKEN — COPY NOW</span><code>{inviteMutation.data.oneTimeToken}</code><button type="button" onClick={async () => { await navigator.clipboard.writeText(inviteMutation.data?.oneTimeToken ?? ''); setCopied(true) }}>{copied ? <Check size={13} /> : <Copy size={13} />}{copied ? 'Copied' : 'Copy token'}</button></div> : null}
      {projectId && projectId !== 'unavailable' ? <form onSubmit={grant}>
        <span>GRANT SELECTED-PROJECT ACCESS</span>
        <label>Active account<select name="userId" required>{users.data?.map((user) => <option key={user.id} value={user.id}>{user.displayName} — {user.email}</option>)}</select></label>
        <label>Project role<select name="role">{projectRoles.map((role) => <option key={role}>{role}</option>)}</select></label>
        <button type="submit" disabled={membershipMutation.isPending}>Grant membership</button>
      </form> : null}
      <div className="curator-access-ledger">
        <span>SELECTED PROJECT · {memberships.data?.length ?? 0} GRANTS</span>
        {memberships.data?.slice(0, 5).map((membership) => <p key={`${membership.userId}-${membership.role}`}><b>{membership.displayName}</b><small>{membership.role} · {membership.email}</small></p>)}
        <span>RECENT INVITATIONS · {invitations.data?.length ?? 0}</span>
        {invitations.data?.slice(0, 3).map((invitation) => <p key={invitation.id}><b>{invitation.email}</b><small>{invitation.intendedRole} · {invitation.acceptedAt ? 'accepted' : 'pending'}</small></p>)}
      </div>
    </div>
  </details>
}
