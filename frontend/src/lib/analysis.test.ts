import { describe, expect, it } from 'vitest'
import { healthLabel, overallHealth, reachableImpact, riskBand } from './analysis'
import type { TraceEdge, TraceNode } from '../types/domain'

const nodes: TraceNode[] = [
  { id: 'a', code: 'OBJ-1', label: 'Objective', type: 'OBJECTIVE', status: 'APPROVED' },
  { id: 'b', code: 'REQ-1', label: 'Requirement', type: 'REQUIREMENT', status: 'APPROVED' },
  { id: 'c', code: 'TEST-1', label: 'Test', type: 'TEST_CASE', status: 'PASSING' },
]

const cycleEdges: TraceEdge[] = [
  { id: 'e1', source: 'a', target: 'b', relationship: 'DERIVES' },
  { id: 'e2', source: 'b', target: 'c', relationship: 'VERIFIES' },
  { id: 'e3', source: 'c', target: 'a', relationship: 'PRODUCES' },
]

describe('analysis rules', () => {
  it('uses the documented risk boundaries', () => {
    expect(riskBand(24)).toBe('LOW')
    expect(riskBand(25)).toBe('MODERATE')
    expect(riskBand(50)).toBe('HIGH')
    expect(riskBand(75)).toBe('CRITICAL')
  })

  it('keeps missing health explicitly unassessed', () => {
    expect(healthLabel(null)).toBe('UNASSESSED')
    expect(overallHealth([{ id: 'scope', label: 'Scope', state: 'UNASSESSED', score: null, delta: 0, detail: '' }])).toBeNull()
    expect(overallHealth([{ id: 'scope', label: 'Scope', state: 'UNASSESSED', score: 99, delta: 0, detail: '' }])).toBeNull()
  })

  it('uses the weakest assessed dimension as overall health', () => {
    expect(overallHealth([
      { id: 'a', label: 'Alignment', state: 'ASSESSED', score: 88, delta: 0, detail: '' },
      { id: 'v', label: 'Verification', state: 'ASSESSED', score: 58, delta: 0, detail: '' },
    ])).toBe(58)
  })

  it('traverses impact paths once even when the graph contains a cycle', () => {
    const impact = reachableImpact('a', nodes, cycleEdges)
    expect(impact.map(({ node }) => node.id)).toEqual(['b', 'c'])
    expect(impact[1].path).toEqual(['a', 'b', 'c'])
  })
})
