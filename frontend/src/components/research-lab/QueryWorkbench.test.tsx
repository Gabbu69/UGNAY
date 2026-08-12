import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  executeResearchQuery,
  getResearchGrammar,
  type GrammarDescription,
  type ResearchQueryResponse,
} from '../../lib/researchLabApi'
import { QueryWorkbench } from './QueryWorkbench'

vi.mock('../../lib/researchLabApi', async () => {
  const actual = await vi.importActual<typeof import('../../lib/researchLabApi')>('../../lib/researchLabApi')
  return {
    ...actual,
    executeResearchQuery: vi.fn(),
    getResearchGrammar: vi.fn(),
  }
})

const grammar: GrammarDescription = {
  version: 'ugnay-rql-1.0.0',
  ebnf: 'query ::= FIND target [WHERE expression] [USING algorithm] EOF',
  fields: ['TOPIC', 'YEAR', 'SIMILARITY'],
  comparators: ['=', '>=', 'CONTAINS'],
  algorithms: ['LEXICAL', 'TFIDF', 'SEMANTIC', 'HYBRID'],
  examples: ['FIND THESIS WHERE TOPIC CONTAINS "flood" USING HYBRID LIMIT 5'],
  limits: { sourceCharacters: 4096, tokens: 256, astDepth: 16, resultLimit: 100 },
  safety: 'Only allow-listed fields and bound parameters are executed.',
}

function renderWorkbench() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <QueryWorkbench />
    </QueryClientProvider>,
  )
}

function response(overrides: Partial<ResearchQueryResponse>): ResearchQueryResponse {
  return {
    languageVersion: 'ugnay-rql-1.0.0',
    valid: false,
    status: 'INVALID',
    traceIncluded: true,
    tokens: [],
    ast: null,
    validation: { valid: false, completedStage: 'PARSER' },
    interpretedAction: null,
    algorithmVersion: null,
    semanticProvider: null,
    assessmentStatus: 'UNASSESSED',
    warehouse: {
      status: 'UNAVAILABLE',
      snapshotId: null,
      asOf: null,
      explanation: 'Execution stopped before a warehouse snapshot was selected.',
    },
    diagnostics: [],
    results: [],
    latencyMillis: 2,
    ...overrides,
  }
}

describe('research query workbench', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders typed parser diagnostics and the processing trace without hiding source locations', async () => {
    vi.mocked(getResearchGrammar).mockResolvedValue(grammar)
    vi.mocked(executeResearchQuery).mockResolvedValue(response({
      tokens: [
        {
          type: 'FIND', lexeme: 'FIND', literal: null,
          span: { startOffset: 0, endOffset: 4, startLine: 1, startColumn: 1, endLine: 1, endColumn: 5 },
        },
        {
          type: 'YEAR', lexeme: 'YEAR', literal: null,
          span: { startOffset: 18, endOffset: 22, startLine: 1, startColumn: 19, endLine: 1, endColumn: 23 },
        },
      ],
      diagnostics: [{
        stage: 'PARSER',
        code: 'RQL-PARSE-EXPECTED-VALUE',
        message: 'Expected a string or number after comparator.',
        span: { startOffset: 25, endOffset: 25, startLine: 1, startColumn: 26, endLine: 1, endColumn: 26 },
        expected: ['STRING', 'NUMBER'],
      }],
    }))
    renderWorkbench()
    const user = userEvent.setup()

    const source = screen.getByLabelText('Research query')
    await user.clear(source)
    await user.type(source, 'FIND THESIS WHERE YEAR =')
    await user.click(screen.getByRole('button', { name: /Execute query/i }))

    expect(await screen.findByRole('region', { name: 'Query diagnostics' })).toBeInTheDocument()
    expect(screen.getByText(/PARSER.*RQL-PARSE-EXPECTED-VALUE/)).toBeInTheDocument()
    expect(screen.getByText('Expected a string or number after comparator.')).toBeInTheDocument()
    expect(screen.getByText(/Line 1, column 26.*Expected: STRING, NUMBER/)).toBeInTheDocument()
    expect(screen.getByText('No AST was emitted.')).toBeInTheDocument()
    expect(screen.getByText(/Execution stopped safely/)).toBeInTheDocument()

    const tokenTable = screen.getByRole('table')
    expect(within(tokenTable).getAllByText('FIND')).toHaveLength(2)
    expect(within(tokenTable).getAllByText('YEAR')).toHaveLength(2)
    await waitFor(() => expect(executeResearchQuery).toHaveBeenCalledWith('FIND THESIS WHERE YEAR =', true))
  })

  it('shows the typed AST, safe interpreted action, warehouse evidence, and ranked result', async () => {
    vi.mocked(getResearchGrammar).mockResolvedValue(grammar)
    vi.mocked(executeResearchQuery).mockResolvedValue(response({
      valid: true,
      status: 'EXECUTED',
      validation: { valid: true, completedStage: 'SEMANTIC' },
      algorithmVersion: 'HYBRID_V1_1',
      semanticProvider: 'LOCAL_E5',
      assessmentStatus: 'ASSESSED',
      warehouse: {
        status: 'PUBLISHED',
        snapshotId: 'warehouse-snapshot-8',
        asOf: '2026-08-11T12:00:00Z',
        explanation: 'Executed against the latest authorized immutable snapshot.',
      },
      ast: {
        kind: 'Query',
        children: [
          { kind: 'Target', value: 'THESIS', children: [] },
          { kind: 'Algorithm', value: 'HYBRID', children: [] },
        ],
      },
      interpretedAction: {
        target: 'THESIS',
        contextType: 'TEXT',
        contextAuthorized: true,
        algorithmVersion: 'HYBRID_V1_1',
        sort: 'RELEVANCE',
        direction: 'DESC',
        limit: 5,
        filterCount: 1,
        executor: 'SAFE_BOUND_CATALOGUE_EXECUTOR',
      },
      results: [{
        rank: 1,
        id: 'study-1',
        code: 'CICS-2025-014',
        title: 'Flood Preparedness Evidence Archive',
        academicYear: '2024-2025',
        year: 2025,
        department: 'CICS',
        lifecycleStatus: 'PUBLISHED',
        visibility: 'INTERNAL',
        abstractText: 'Reviewed evidence.',
        methodology: 'Design science',
        keywords: ['flood', 'offline'],
        researchAreas: ['DISASTER_RESILIENCE'],
        similarityScore: 84.5,
        scoreStatus: 'ASSESSED',
        components: { lexical: 80, tfIdf: 82, semantic: 88, controlledConcept: 75 },
        matchedTerms: ['flood'],
        explanations: ['Ranked by the selected frozen hybrid configuration.'],
        restricted: false,
      }],
      latencyMillis: 14,
    }))
    renderWorkbench()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /Execute query/i }))

    expect(await screen.findByRole('heading', { name: 'Flood Preparedness Evidence Archive' })).toBeInTheDocument()
    expect(screen.getAllByText('HYBRID_V1_1').length).toBeGreaterThan(0)
    expect(screen.getByText('SAFE_BOUND_CATALOGUE_EXECUTOR')).toBeInTheDocument()
    expect(screen.getByText('Query')).toBeInTheDocument()
    expect(screen.getAllByText('Target').length).toBeGreaterThan(0)
    expect(screen.getByText('84.5%')).toBeInTheDocument()
    expect(screen.getByText('Evidence only; no academic route was selected.')).toBeInTheDocument()
    expect(screen.getByText('Data as of').parentElement).toHaveTextContent('2026')
  })
})
