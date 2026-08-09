import { useState, type FormEvent } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { motion } from 'motion/react'
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  KeyRound,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  Network,
  ShieldCheck,
  X,
} from 'lucide-react'
import { useLogin, useLogout } from '../hooks/useAuthSession'
import { ApiProblem, type AuthSessionEnvelope } from '../lib/api'
import { CuratorAccessPanel } from './CuratorAccessPanel'

interface AccountDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  auth?: AuthSessionEnvelope
  authLoading: boolean
  workspaceSource: 'LIVE' | 'DEMO' | 'UNAVAILABLE'
  workspaceDepartment: string
  projectId?: string
}

function roleLabel(role: string) {
  return role
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase())
}

function errorMessage(error: unknown) {
  if (error instanceof ApiProblem) return error.detail
  if (error instanceof DOMException && error.name === 'AbortError') {
    return 'The session service took too long to respond. Please try again.'
  }
  return 'The session could not be opened. Confirm that the UGNAY server is running and try again.'
}

export function AccountDialog({
  open,
  onOpenChange,
  auth,
  authLoading,
  workspaceSource,
  workspaceDepartment,
  projectId,
}: AccountDialogProps) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const loginMutation = useLogin()
  const logoutMutation = useLogout()
  const session = auth?.session
  const authenticated = session?.authenticated === true
  const mutationError = loginMutation.error ?? logoutMutation.error

  const handleLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    loginMutation.reset()
    logoutMutation.reset()
    try {
      await loginMutation.mutateAsync({ email: email.trim(), password })
      setPassword('')
    } catch {
      // The mutation error is rendered in the dialog with an actionable message.
    }
  }

  const handleLogout = async () => {
    loginMutation.reset()
    logoutMutation.reset()
    try {
      await logoutMutation.mutateAsync()
    } catch {
      // The mutation error is rendered without dismissing the account panel.
    }
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="account-overlay" />
        <Dialog.Content className="account-dialog" aria-describedby="account-dialog-description">
          <Dialog.Close className="account-dialog-close" aria-label="Close account panel">
            <X size={18} />
          </Dialog.Close>

          <aside className="account-identity-rail" aria-label="Account identity and access context">
            <div className="account-orbit" aria-hidden="true">
              <span><Network size={22} /></span>
              <i /><i /><i />
            </div>
            <div className="account-rail-copy">
              <span>Identity &amp; evidence access</span>
              <h2>One account.<br />A preserved trail.</h2>
              <p>UGNAY keeps academic decisions, baselines, and overrides attached to the person authorized to make them.</p>
            </div>
            <div className="account-session-rule">
              <ShieldCheck size={16} />
              <p><strong>Same-origin session</strong><span>No browser-stored access tokens</span></p>
            </div>
          </aside>

          <section className="account-panel">
            <div className="account-panel-kicker">
              <span className={auth?.source === 'LIVE' ? 'is-connected' : ''} />
              {authLoading ? 'Checking session' : auth?.source === 'LIVE' ? 'Session service connected' : 'Session service unavailable'}
            </div>

            {authenticated ? (
              <motion.div
                key="authenticated"
                initial={{ opacity: 0, y: 5 }}
                animate={{ opacity: 1, y: 0 }}
                className="account-authenticated"
              >
                <CheckCircle2 className="account-success-mark" size={31} strokeWidth={1.5} />
                <Dialog.Title>Research access confirmed</Dialog.Title>
                <Dialog.Description className="account-description" id="account-dialog-description">
                  Your actions can now be attributed and audited within the permissions below.
                </Dialog.Description>

                <div className="account-identity-card">
                  <div className="account-monogram" aria-hidden="true">
                    {session.email?.slice(0, 2).toUpperCase() ?? 'UG'}
                  </div>
                  <div>
                    <span>Authenticated account</span>
                    <strong>{session.email}</strong>
                    <small>{workspaceDepartment}</small>
                  </div>
                </div>

                <div className="account-role-section">
                  <span>Granted roles</span>
                  <div>
                    {session.roles.map((role) => <b key={role}>{roleLabel(role)}</b>)}
                  </div>
                </div>

                {session.roles.includes('CURATOR') ? <CuratorAccessPanel projectId={projectId} /> : null}

                {workspaceSource === 'DEMO' ? (
                  <div className="account-mode-note is-pilot">
                    <AlertTriangle size={16} />
                    <p><strong>Pilot dataset active</strong><span>The visible fallback records are illustrative and are not persisted.</span></p>
                  </div>
                ) : (
                  <div className="account-mode-note">
                    <LockKeyhole size={16} />
                    <p><strong>Live API workspace</strong><span>Role and record-state rules are enforced by the server.</span></p>
                  </div>
                )}

                {mutationError ? <p className="account-error" role="alert">{errorMessage(mutationError)}</p> : null}

                <button className="account-signout" type="button" onClick={handleLogout} disabled={logoutMutation.isPending}>
                  {logoutMutation.isPending ? <LoaderCircle className="is-spinning" size={16} /> : <LogOut size={16} />}
                  {logoutMutation.isPending ? 'Closing session…' : 'Sign out of UGNAY'}
                </button>
              </motion.div>
            ) : (
              <motion.form
                key="anonymous"
                initial={{ opacity: 0, y: 5 }}
                animate={{ opacity: 1, y: 0 }}
                onSubmit={handleLogin}
                className="account-login-form"
              >
                <KeyRound className="account-key-mark" size={31} strokeWidth={1.5} />
                <Dialog.Title>Enter the evidence room</Dialog.Title>
                <Dialog.Description className="account-description" id="account-dialog-description">
                  Sign in with an invited university account to record decisions, approve baselines, or change protected research evidence.
                </Dialog.Description>

                {workspaceSource === 'DEMO' ? (
                  <div className="account-mode-note is-pilot">
                    <AlertTriangle size={16} />
                    <p><strong>You are viewing a pilot fallback</strong><span>Its sample records remain clearly separate from authenticated university data.</span></p>
                  </div>
                ) : null}

                <label className="account-field">
                  <span>University email</span>
                  <input
                    type="email"
                    name="email"
                    autoComplete="username"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                    placeholder="name@university.edu"
                    maxLength={254}
                    required
                    autoFocus
                  />
                </label>

                <label className="account-field">
                  <span>Password</span>
                  <input
                    type="password"
                    name="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="Enter your password"
                    maxLength={128}
                    required
                  />
                </label>

                {mutationError ? <p className="account-error" role="alert">{errorMessage(mutationError)}</p> : null}

                <button className="account-login-submit" type="submit" disabled={loginMutation.isPending || authLoading}>
                  <span>{loginMutation.isPending ? 'Opening secure session…' : 'Continue to UGNAY'}</span>
                  {loginMutation.isPending ? <LoaderCircle className="is-spinning" size={17} /> : <ArrowRight size={17} />}
                </button>

                <p className="account-invite-note">
                  <LockKeyhole size={13} />
                  UGNAY is invite-only. Account invitations are issued by a university curator.
                </p>
              </motion.form>
            )}
          </section>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
