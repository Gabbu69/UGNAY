import { motion } from 'motion/react'
import type { Study } from '../types/domain'

const positions = [
  { x: 50, y: 47 }, { x: 20, y: 23 }, { x: 79, y: 23 }, { x: 79, y: 73 }, { x: 21, y: 76 }, { x: 50, y: 88 },
]

export function ResearchConstellation({ studies, selectedId, onSelect }: { studies: Study[]; selectedId: string; onSelect: (id: string) => void }) {
  return (
    <div className="constellation" role="group" aria-label="Research relationship map">
      <svg className="constellation-lines" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
        {positions.slice(1, studies.length + 1).map((point, index) => (
          <motion.path
            key={studies[index].id}
            d={`M50 47 Q${(50 + point.x) / 2 + (index % 2 ? 5 : -5)} ${(47 + point.y) / 2} ${point.x} ${point.y}`}
            vectorEffect="non-scaling-stroke"
            initial={{ pathLength: 0, opacity: 0 }}
            animate={{ pathLength: 1, opacity: 0.8 }}
            transition={{ duration: 0.7, delay: index * 0.08 }}
          />
        ))}
      </svg>
      <div className="constellation-core">
        <span>NEW PROBLEM</span>
        <strong>Flood response<br />coordination</strong>
        <small>6 evidence fields</small>
      </div>
      {studies.slice(0, 5).map((study, index) => {
        const point = positions[index + 1]
        return (
          <button
            key={study.id}
            type="button"
            className={`constellation-node ${selectedId === study.id ? 'is-selected' : ''}`}
            style={{ left: `${point.x}%`, top: `${point.y}%`, '--strength': (study.problemSimilarity ?? 0) / 100 } as React.CSSProperties}
            onClick={() => onSelect(study.id)}
            aria-label={`${study.title}, ${study.problemSimilarity == null ? 'similarity unassessed' : `${study.problemSimilarity}% problem similarity`}`}
          >
            <span>{study.problemSimilarity ?? '—'}</span>
            <small>{study.code}</small>
          </button>
        )
      })}
      <div className="constellation-key"><span /><small>Similarity evidence</small><i /><small>Explicit lineage</small></div>
    </div>
  )
}
