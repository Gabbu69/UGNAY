import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getAuthSession,
  login,
  logout,
  type AuthSessionEnvelope,
} from '../lib/api'

export const authSessionQueryKey = ['auth', 'session'] as const
const workspaceQueryKey = ['workspace'] as const

export function useAuthSession() {
  return useQuery({
    queryKey: authSessionQueryKey,
    queryFn: getAuthSession,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  })
}

export function useLogin() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: login,
    onSuccess: async (session) => {
      queryClient.setQueryData<AuthSessionEnvelope>(authSessionQueryKey, { session, source: 'LIVE' })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: authSessionQueryKey }),
        queryClient.invalidateQueries({ queryKey: workspaceQueryKey }),
      ])
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      queryClient.setQueryData<AuthSessionEnvelope>(authSessionQueryKey, {
        session: { authenticated: false, email: null, roles: [] },
        source: 'LIVE',
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: authSessionQueryKey }),
        queryClient.invalidateQueries({ queryKey: workspaceQueryKey }),
      ])
    },
  })
}
