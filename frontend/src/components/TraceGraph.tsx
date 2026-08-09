import { useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import type { TraceEdge, TraceNode } from '../types/domain'

const nodeColors = {
  PROBLEM: '#d46a59',
  OBJECTIVE: '#a884e8',
  REQUIREMENT: '#2da89c',
  FEATURE: '#4c88bf',
  TEST_CASE: '#dda744',
  OUTPUT: '#7fa97a',
}

export function TraceGraph({ nodes, edges, selectedId, onSelect }: {
  nodes: TraceNode[]
  edges: TraceEdge[]
  selectedId?: string
  onSelect: (id: string) => void
}) {
  const hostRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!hostRef.current) return
    const cy = cytoscape({
      container: hostRef.current,
      elements: [
        ...nodes.map((node) => ({ data: { id: node.id, label: node.code, title: node.label, type: node.type, status: node.status } })),
        ...edges.map((edge) => ({ data: { id: edge.id, source: edge.source, target: edge.target, label: edge.relationship } })),
      ],
      layout: { name: 'breadthfirst', directed: true, spacingFactor: 1.15, padding: 36, circle: false },
      style: [
        {
          selector: 'node',
          style: {
            'background-color': (element) => nodeColors[element.data('type') as keyof typeof nodeColors],
            'border-color': '#f7f0e3',
            'border-width': 3,
            color: '#efe9dc',
            label: 'data(label)',
            'font-family': 'IBM Plex Mono',
            'font-size': 9,
            'font-weight': 500,
            'text-valign': 'bottom',
            'text-margin-y': 8,
            width: 29,
            height: 29,
          },
        },
        {
          selector: 'node[status = "STALE"], node[status = "MISSING"]',
          style: { 'border-color': '#f08a74', 'border-width': 5 },
        },
        {
          selector: 'node:selected',
          style: { 'overlay-color': '#67d5c7', 'overlay-opacity': 0.17, 'overlay-padding': 10, width: 35, height: 35 },
        },
        {
          selector: 'edge',
          style: {
            width: 1.4,
            'line-color': '#566875',
            'target-arrow-color': '#81939d',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            'arrow-scale': 0.7,
            opacity: 0.78,
          },
        },
      ],
      minZoom: 0.55,
      maxZoom: 2,
    })
    cy.on('tap', 'node', (event) => onSelect(event.target.id()))
    if (selectedId) cy.getElementById(selectedId).select()
    return () => cy.destroy()
  }, [edges, nodes, onSelect, selectedId])

  return (
    <div className="trace-graph-wrap">
      <div ref={hostRef} className="trace-graph" aria-hidden="true" />
      <p className="sr-only">Interactive trace graph containing {nodes.length} artifacts and {edges.length} relationships. Use the matrix view for a keyboard-accessible representation.</p>
    </div>
  )
}
