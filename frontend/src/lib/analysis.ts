import type { HealthDimension, RiskLevel, TraceEdge, TraceNode } from '../types/domain'

export function riskBand(score: number): RiskLevel {
  if (score >= 75) return 'CRITICAL'
  if (score >= 50) return 'HIGH'
  if (score >= 25) return 'MODERATE'
  return 'LOW'
}

export function healthLabel(score: number | null) {
  if (score === null) return 'UNASSESSED'
  if (score >= 85) return 'HEALTHY'
  if (score >= 70) return 'WATCH'
  if (score >= 50) return 'AT RISK'
  return 'CRITICAL'
}

export function overallHealth(dimensions: HealthDimension[]) {
  const assessed = dimensions.flatMap((dimension) => dimension.score === null ? [] : [dimension.score])
  return assessed.length ? Math.min(...assessed) : null
}

export function reachableImpact(startId: string, nodes: TraceNode[], edges: TraceEdge[]) {
  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const outgoing = new Map<string, TraceEdge[]>()
  for (const edge of edges) {
    const bucket = outgoing.get(edge.source) ?? []
    bucket.push(edge)
    outgoing.set(edge.source, bucket)
  }

  const queue: Array<{ id: string; path: string[] }> = [{ id: startId, path: [startId] }]
  const seen = new Set([startId])
  const impacted: Array<{ node: TraceNode; path: string[] }> = []

  while (queue.length) {
    const current = queue.shift()
    if (!current) break
    for (const edge of outgoing.get(current.id) ?? []) {
      if (seen.has(edge.target)) continue
      seen.add(edge.target)
      const path = [...current.path, edge.target]
      const node = nodeById.get(edge.target)
      if (node) impacted.push({ node, path })
      queue.push({ id: edge.target, path })
    }
  }
  return impacted
}

export function formatPercent(value: number) {
  return `${Math.round(value)}%`
}
