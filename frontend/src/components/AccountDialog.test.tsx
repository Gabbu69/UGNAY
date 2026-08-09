import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AccountDialog } from './AccountDialog'
import { ApiProblem, login, logout, type AuthSessionEnvelope } from '../lib/api'

vi.mock('../lib/api', async () => {
  const actual = await vi.importActual<typeof import('../lib/api')>('../lib/api')
  return { ...actual, login: vi.fn(), logout: vi.fn() }
})

const anonymousAuth: AuthSessionEnvelope = {
  session: { authenticated: false, email: null, roles: [] },
  source: 'LIVE',
}

const authenticatedAuth: AuthSessionEnvelope = {
  session: { authenticated: true, email: 'amara.reyes@ugnay.edu', roles: ['ADVISER', 'CURATOR'] },
  source: 'LIVE',
}

function renderDialog(auth: AuthSessionEnvelope, workspaceSource: 'LIVE' | 'DEMO' = 'LIVE') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <AccountDialog
        open
        onOpenChange={vi.fn()}
        auth={auth}
        authLoading={false}
        workspaceSource={workspaceSource}
        workspaceDepartment="College of Information Sciences"
      />
    </QueryClientProvider>,
  )
}

describe('account dialog', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('submits invite-only credentials and explains rejected authentication', async () => {
    vi.mocked(login).mockRejectedValue(new ApiProblem(401, 'Invalid email or password.'))
    const user = userEvent.setup()
    renderDialog(anonymousAuth)

    await user.type(screen.getByLabelText('University email'), 'student@ugnay.edu')
    await user.type(screen.getByLabelText('Password'), 'incorrect-password')
    await user.click(screen.getByRole('button', { name: 'Continue to UGNAY' }))

    expect(vi.mocked(login).mock.calls[0]?.[0]).toEqual({ email: 'student@ugnay.edu', password: 'incorrect-password' })
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.')
    expect(screen.getByText(/UGNAY is invite-only/i)).toBeInTheDocument()
  })

  it('exposes pending login state without allowing duplicate submissions', async () => {
    let resolveLogin: ((value: { authenticated: true; email: string; roles: string[] }) => void) | undefined
    vi.mocked(login).mockImplementation(() => new Promise((resolve) => { resolveLogin = resolve }))
    const user = userEvent.setup()
    renderDialog(anonymousAuth, 'DEMO')

    await user.type(screen.getByLabelText('University email'), 'student@ugnay.edu')
    await user.type(screen.getByLabelText('Password'), 'correct-password')
    await user.click(screen.getByRole('button', { name: 'Continue to UGNAY' }))

    const pendingButton = screen.getByRole('button', { name: 'Opening secure session…' })
    expect(pendingButton).toBeDisabled()
    expect(screen.getByText('You are viewing a pilot fallback')).toBeInTheDocument()
    resolveLogin?.({ authenticated: true, email: 'student@ugnay.edu', roles: ['STUDENT'] })
    await waitFor(() => expect(pendingButton).not.toBeDisabled())
  })

  it('shows granted roles and closes an authenticated session', async () => {
    vi.mocked(logout).mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderDialog(authenticatedAuth)

    expect(screen.getByText('amara.reyes@ugnay.edu')).toBeInTheDocument()
    expect(screen.getByText('Adviser')).toBeInTheDocument()
    expect(screen.getByText('Curator')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sign out of UGNAY' }))
    expect(logout).toHaveBeenCalledOnce()
  })
})
