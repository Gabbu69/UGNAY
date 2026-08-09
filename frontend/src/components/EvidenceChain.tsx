import { Check, CircleDashed } from 'lucide-react'
import type { TraceItemType } from '../types/domain'

const chain: Array<{ key: TraceItemType | 'STUDIES' | 'DECISION' | 'PACKAGE'; label: string }> = [
  { key: 'PROBLEM', label: 'Problem' },
  { key: 'STUDIES', label: 'Studies' },
  { key: 'DECISION', label: 'Decision' },
  { key: 'OBJECTIVE', label: 'Objectives' },
  { key: 'REQUIREMENT', label: 'Requirements' },
  { key: 'FEATURE', label: 'Features' },
  { key: 'TEST_CASE', label: 'Tests' },
  { key: 'OUTPUT', label: 'Outputs' },
  { key: 'PACKAGE', label: 'Continuity' },
]

export function EvidenceChain({ active = 'REQUIREMENT' }: { active?: (typeof chain)[number]['key'] }) {
  const activeIndex = chain.findIndex((item) => item.key === active)
  return (
    <div className="evidence-chain" role="list" aria-label="Project evidence chain" tabIndex={0}>
      {chain.map((item, index) => {
        const done = index < activeIndex
        const current = index === activeIndex
        return (
          <div key={item.key} role="listitem" className={`${done ? 'is-done' : ''} ${current ? 'is-current' : ''}`}>
            <span>{done ? <Check size={12} /> : <CircleDashed size={12} />}</span>
            <small>{item.label}</small>
          </div>
        )
      })}
    </div>
  )
}
