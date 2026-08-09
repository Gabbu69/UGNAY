import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { PageHeader, ScoreRing } from './Primitives'

describe('interface primitives', () => {
  it('renders page context with a semantic heading', () => {
    render(<PageHeader eyebrow="Evidence" title="Research Atlas" description="Find prior work." />)
    expect(screen.getByRole('heading', { level: 1, name: 'Research Atlas' })).toBeInTheDocument()
    expect(screen.getByText('Find prior work.')).toBeInTheDocument()
  })

  it('does not turn an unassessed score into a healthy zero', () => {
    render(<ScoreRing score={null} label="UNASSESSED" />)
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText('UNASSESSED')).toBeInTheDocument()
  })
})
