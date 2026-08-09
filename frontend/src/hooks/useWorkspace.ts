import { useQuery } from '@tanstack/react-query'
import { getWorkspace } from '../lib/api'
import { useParams } from 'react-router-dom'

export function useWorkspace() {
  const { projectId } = useParams()
  return useQuery({
    queryKey: ['workspace', projectId ?? 'default'],
    queryFn: () => getWorkspace(projectId),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  })
}
