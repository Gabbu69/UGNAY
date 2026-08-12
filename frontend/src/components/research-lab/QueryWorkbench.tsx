import { useMemo, useState, type ReactNode } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  AlertCircle, ArrowRight, BookOpen, Braces, CheckCircle2, ChevronDown,
  ChevronRight, Clock3, Code2, Eye, EyeOff, Play, ShieldCheck, Sparkles,
} from 'lucide-react'
import { executeResearchQuery, getResearchGrammar, type AstNode, type ResearchQueryResponse } from '../../lib/researchLabApi'
import { StatusPill } from '../Primitives'

const fallbackExamples = [
  'FIND THESIS WHERE TOPIC = "agriculture" AND YEAR >= 2022 ORDER BY RELEVANCE',
  'FIND RELATED TO TEXT "offline flood warning for campuses" WHERE SIMILARITY > 70 USING HYBRID',
  'FIND THESIS WHERE METHODOLOGY CONTAINS "survey" USING TFIDF ORDER BY RELEVANCE LIMIT 10',
]

function score(value: number | null | undefined) {
  return value == null ? 'UNASSESSED' : `${value.toFixed(value % 1 === 0 ? 0 : 1)}%`
}

function AstTree({ node, level = 0 }: { node: AstNode; level?: number }) {
  return (
    <div className="ast-node" style={{ '--ast-level': level } as React.CSSProperties}>
      <div><ChevronRight size={13} /><b>{node.kind}</b>{node.value != null ? <code>{String(node.value)}</code> : null}</div>
      {node.children.map((child, index) => <AstTree key={`${child.kind}-${index}`} node={child} level={level + 1} />)}
    </div>
  )
}

function TracePanel({ title, icon, children, defaultOpen = false, badge }: {
  title: string
  icon: ReactNode
  children: ReactNode
  defaultOpen?: boolean
  badge?: ReactNode
}) {
  return (
    <details className="trace-panel" open={defaultOpen}>
      <summary>{icon}<strong>{title}</strong>{badge}<ChevronDown className="trace-chevron" size={16} /></summary>
      <div className="trace-panel-body">{children}</div>
    </details>
  )
}

function QueryOutcome({ response }: { response: ResearchQueryResponse }) {
  return (
    <>
      <section className={`query-outcome outcome-${response.status.toLowerCase()}`} aria-live="polite">
        <div>
          {response.valid ? <CheckCircle2 size={20} /> : <AlertCircle size={20} />}
          <span><small>PROCESSING OUTCOME</small><strong>{response.status.replaceAll('_', ' ')}</strong></span>
        </div>
        <dl>
          <div><dt>Validation</dt><dd>{response.validation.completedStage}</dd></div>
          <div><dt>Algorithm</dt><dd>{response.algorithmVersion ?? 'Not selected'}</dd></div>
          <div><dt>Assessment</dt><dd>{response.assessmentStatus}</dd></div>
          <div><dt>Latency</dt><dd>{response.latencyMillis} ms</dd></div>
        </dl>
      </section>

      {response.diagnostics.length ? (
        <section className="query-diagnostics" aria-label="Query diagnostics">
          {response.diagnostics.map((diagnostic, index) => (
            <article key={`${diagnostic.code}-${index}`}>
              <AlertCircle size={17} />
              <div><span>{diagnostic.stage} · {diagnostic.code}</span><strong>{diagnostic.message}</strong>
                <small>Line {diagnostic.span.startLine}, column {diagnostic.span.startColumn}{diagnostic.expected.length ? ` · Expected: ${diagnostic.expected.join(', ')}` : ''}</small>
              </div>
            </article>
          ))}
        </section>
      ) : null}

      {response.valid ? (
        <section className="query-evidence-strip" aria-label="Execution evidence">
          <div><ShieldCheck size={17} /><span><b>Safe execution</b><small>{response.interpretedAction?.executor ?? 'No executor was invoked'}</small></span></div>
          <div><Clock3 size={17} /><span><b>Data as of</b><small>{response.warehouse.asOf ? new Date(response.warehouse.asOf).toLocaleString() : 'Authoritative live catalogue'}</small></span></div>
          <div><Sparkles size={17} /><span><b>Decision boundary</b><small>Evidence only; no academic route was selected.</small></span></div>
        </section>
      ) : null}

      {response.results.length ? (
        <section className="query-results" aria-labelledby="query-results-title">
          <div className="lab-section-heading"><div><span>AUTHORIZED RESULTS</span><h2 id="query-results-title">Ranked research evidence</h2></div><StatusPill tone="violet">{response.results.length} returned</StatusPill></div>
          <div className="query-result-list">
            {response.results.map((result) => (
              <article key={result.id} className={result.restricted ? 'is-restricted' : ''}>
                <div className="query-result-rank">{String(result.rank).padStart(2, '0')}</div>
                <div className="query-result-main">
                  <div className="query-result-meta"><span>{result.code ?? 'Protected record'}</span><span>{result.department ?? 'Department unavailable'}</span><span>{result.year ?? result.academicYear ?? 'Year unavailable'}</span></div>
                  <h3>{result.title}</h3>
                  {result.keywords.length ? <div className="query-result-tags">{result.keywords.slice(0, 6).map((term) => <span key={term}>{term}</span>)}</div> : null}
                  {result.explanations.map((explanation) => <p key={explanation}>{explanation}</p>)}
                  {result.matchedTerms.length ? <small>Matched terms · {result.matchedTerms.join(', ')}</small> : null}
                </div>
                <div className="query-result-score"><strong>{score(result.similarityScore)}</strong><span>{result.scoreStatus}</span>
                  <dl><div><dt>Lexical</dt><dd>{score(result.components.lexical)}</dd></div><div><dt>TF-IDF</dt><dd>{score(result.components.tfIdf)}</dd></div><div><dt>Semantic</dt><dd>{score(result.components.semantic)}</dd></div><div><dt>Concept</dt><dd>{score(result.components.controlledConcept)}</dd></div></dl>
                </div>
              </article>
            ))}
          </div>
        </section>
      ) : response.valid && response.status !== 'UNAVAILABLE' ? (
        <div className="lab-empty"><BookOpen size={22} /><div><strong>No authorized studies matched this plan.</strong><span>Broaden the filter or choose a different retrieval method. No result was fabricated.</span></div></div>
      ) : null}

      {response.traceIncluded ? (
        <section className="processing-trace" aria-labelledby="processing-trace-title">
          <div className="lab-section-heading"><div><span>DEFENSE TRACE</span><h2 id="processing-trace-title">Processing pipeline</h2></div><StatusPill tone={response.valid ? 'teal' : 'coral'}>{response.validation.valid ? 'Grammar valid' : `Stopped at ${response.validation.completedStage}`}</StatusPill></div>
          <div className="trace-grid">
            <TracePanel title="1 · Tokens" icon={<Code2 size={17} />} badge={<small>{response.tokens.length} tokens</small>} defaultOpen>
              {response.tokens.length ? <div className="table-scroll"><table className="lab-table compact"><thead><tr><th>Type</th><th>Lexeme</th><th>Location</th></tr></thead><tbody>{response.tokens.map((token, index) => <tr key={`${token.type}-${index}`}><td><code>{token.type}</code></td><td>{token.lexeme || 'EOF'}</td><td>{token.span.startLine}:{token.span.startColumn}</td></tr>)}</tbody></table></div> : <p>Token trace is unavailable because processing stopped before tokenization completed.</p>}
            </TracePanel>
            <TracePanel title="2 · AST" icon={<Braces size={17} />} badge={<small>Typed intermediate form</small>}>
              {response.ast ? <div className="ast-tree"><AstTree node={response.ast} /></div> : <p>No AST was emitted.</p>}
            </TracePanel>
            <TracePanel title="3 · Validation" icon={<ShieldCheck size={17} />} badge={<small>{response.validation.completedStage}</small>}>
              <p>{response.validation.valid ? 'Lexical, grammar, semantic, context, type, and limit checks passed.' : 'Execution stopped safely. Correct the diagnostic above and run the statement again.'}</p>
            </TracePanel>
            <TracePanel title="4 · Interpreted action" icon={<Play size={17} />} badge={<small>{response.interpretedAction?.target ?? 'Not emitted'}</small>}>
              {response.interpretedAction ? <dl className="action-definition"><div><dt>Target</dt><dd>{response.interpretedAction.target}</dd></div><div><dt>Context</dt><dd>{response.interpretedAction.contextType ?? 'None'}</dd></div><div><dt>Algorithm</dt><dd>{response.interpretedAction.algorithmVersion}</dd></div><div><dt>Sort</dt><dd>{response.interpretedAction.sort} {response.interpretedAction.direction}</dd></div><div><dt>Limit</dt><dd>{response.interpretedAction.limit}</dd></div><div><dt>Filters</dt><dd>{response.interpretedAction.filterCount}</dd></div></dl> : <p>No execution plan was emitted.</p>}
            </TracePanel>
          </div>
        </section>
      ) : null}
    </>
  )
}

export function QueryWorkbench() {
  const grammar = useQuery({ queryKey: ['research-query-grammar'], queryFn: getResearchGrammar, staleTime: Infinity })
  const examples = grammar.data?.examples ?? fallbackExamples
  const [source, setSource] = useState(examples[0] ?? fallbackExamples[0])
  const [includeTrace, setIncludeTrace] = useState(true)
  const execute = useMutation({ mutationFn: () => executeResearchQuery(source, includeTrace) })
  const characters = source.length
  const limit = grammar.data?.limits.sourceCharacters ?? 4096
  const syntaxHint = useMemo(() => grammar.data?.ebnf.split('\n')[0]?.trim() ?? 'query ::= FIND target ... EOF', [grammar.data])

  return (
    <div className="query-workbench">
      <div className="lab-intro-row">
        <div><span>COMPILER & INTERPRETER</span><h2>Write a bounded research query</h2><p>UGNAY tokenizes, parses, validates, plans, and interprets only the documented language. It does not accept SQL or bypass permissions.</p></div>
        <div className="lab-version-card"><Code2 size={18} /><span><small>LANGUAGE VERSION</small><strong>{grammar.data?.version ?? 'Loading grammar…'}</strong><code>{syntaxHint}</code></span></div>
      </div>

      <section className="query-console paper-panel">
        <div className="query-console-head"><div><span className="terminal-dot teal" /><span className="terminal-dot amber" /><span className="terminal-dot violet" /><strong>UGNAY Research Query Language</strong></div><span>Bound parameters · 5 s limit · max {limit} characters</span></div>
        <label htmlFor="research-query-source">Research query</label>
        <textarea id="research-query-source" value={source} onChange={(event) => setSource(event.target.value)} spellCheck={false} maxLength={limit} aria-describedby="query-character-count" />
        <div className="query-console-actions">
          <label className="trace-toggle"><input type="checkbox" checked={includeTrace} onChange={(event) => setIncludeTrace(event.target.checked)} />{includeTrace ? <Eye size={16} /> : <EyeOff size={16} />} Include processing trace</label>
          <span id="query-character-count" className={characters > limit * .9 ? 'is-near-limit' : ''}>{characters} / {limit}</span>
          <button className="button button-primary" type="button" disabled={!source.trim() || execute.isPending} onClick={() => execute.mutate()}>{execute.isPending ? <span className="button-spinner" /> : <Play size={16} />}{execute.isPending ? 'Interpreting…' : 'Execute query'}<ArrowRight size={15} /></button>
        </div>
      </section>

      <section className="query-examples" aria-label="Research query examples">
        <span>TRY AN EXAMPLE</span>
        <div>{examples.map((example, index) => <button type="button" key={example} onClick={() => setSource(example)}><b>{String(index + 1).padStart(2, '0')}</b><code>{example}</code></button>)}</div>
      </section>

      {execute.isError ? <div className="lab-error" role="alert"><AlertCircle size={18} /><div><strong>The interpreter API could not be reached.</strong><span>{execute.error instanceof Error ? execute.error.message : 'Please retry after the local service is available.'}</span></div></div> : null}
      {execute.data ? <QueryOutcome response={execute.data} /> : (
        <div className="lab-empty lab-empty-start"><Braces size={24} /><div><strong>Ready to inspect the full interpreter path.</strong><span>Run a statement to view diagnostics, tokens, AST, safe action, algorithm evidence, and authorized results.</span></div></div>
      )}

      <details className="grammar-reference">
        <summary><BookOpen size={16} /><strong>Language grammar and safety contract</strong><ChevronDown size={16} /></summary>
        <div><pre>{grammar.data?.ebnf ?? 'Loading the authoritative grammar…'}</pre><p>{grammar.data?.safety ?? 'The language is interpreted through allow-listed fields, operators, and bound parameters.'}</p></div>
      </details>
    </div>
  )
}
