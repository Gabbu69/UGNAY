import { useDeferredValue, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, BookOpen, Database, Filter, LockKeyhole, Search, SlidersHorizontal } from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { ResearchConstellation } from '../components/ResearchConstellation'
import { CatalogueIngestionStudio } from '../components/CatalogueIngestionStudio'
import { AssessmentLegend, DimensionBar, ExplainabilityNote, PageHeader, StatusPill } from '../components/Primitives'

export default function ResearchAtlas() {
  const navigate = useNavigate()
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const studies = useMemo(() => data?.data.studies ?? [], [data?.data.studies])
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const [selectedId, setSelectedId] = useState(studies[0]?.id ?? 'study-1')
  const [relationOnly, setRelationOnly] = useState(false)
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const [ingestionOpen, setIngestionOpen] = useState(false)
  const isDemo = data?.source !== 'LIVE'
  const canIngest = data?.source === 'LIVE' && auth?.session.authenticated === true && auth.session.roles.includes('CURATOR')
  const generatedAt = data?.data.generatedAt
  const refreshDate = generatedAt ? new Intl.DateTimeFormat('en-PH', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(generatedAt)) : 'Unavailable'

  const filtered = useMemo(() => {
    const normalized = deferredQuery.trim().toLowerCase()
    return studies.filter((study) => {
      const matchesQuery = !normalized || [study.title, study.code, study.program, ...study.keywords].join(' ').toLowerCase().includes(normalized)
      return matchesQuery && (!relationOnly || study.problemSimilarity >= 65)
    })
  }, [deferredQuery, relationOnly, studies])
  const selected = filtered.find((study) => study.id === selectedId) ?? filtered[0] ?? studies[0]

  return (
    <div className="page atlas-page">
      <PageHeader
        eyebrow="Discover prior work"
        title="Research Atlas"
        description="See where a problem has already been studied—and where meaningful work is still unfinished."
        actions={<>{canIngest ? <button className="button button-secondary" onClick={() => setIngestionOpen(true)}><Database size={16} />Ingest research</button> : null}<button className="button button-primary" onClick={() => navigate('/intake')}>Start a problem intake <ArrowRight size={16} /></button></>}
        meta={<><StatusPill tone="teal">{studies.length} {isDemo ? 'pilot' : 'workspace'} studies indexed</StatusPill><span>Corpus snapshot · {refreshDate}</span></>}
      />

      <section className="atlas-search" aria-label="Search the research catalogue">
        <Search size={19} />
        <label className="sr-only" htmlFor="atlas-query">Search studies</label>
        <input id="atlas-query" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search a problem, community, method, or system…" />
        <button className={`filter-toggle ${relationOnly ? 'is-active' : ''}`} onClick={() => setRelationOnly((value) => !value)} aria-pressed={relationOnly}>
          <SlidersHorizontal size={16} /> Strong relationships
        </button>
        <button className="icon-button" aria-label="Additional catalogue filters are not active in this pilot" disabled title="Search and strong-relationship filters are available"><Filter size={18} /></button>
      </section>

      <div className="atlas-layout">
        <section className="atlas-map-panel panel-dark">
          <div className="panel-heading on-dark">
            <div><span>RELATIONSHIP FIELD</span><h2>How this problem connects</h2></div>
            <StatusPill tone="violet">Neighborhood · {filtered.length} {filtered.length === 1 ? 'study' : 'studies'}</StatusPill>
          </div>
          <ResearchConstellation studies={filtered} selectedId={selected?.id ?? ''} onSelect={setSelectedId} />
        </section>

        <aside className="selected-study paper-panel" aria-label="Selected study evidence" aria-live="polite">
          {selected ? (
            <>
              <div className="study-kicker"><span>{selected.code}</span><span>{selected.year}</span><StatusPill tone={selected.status === 'COMPLETED' ? 'teal' : 'amber'}>{selected.status}</StatusPill></div>
              <h2>{selected.title}</h2>
              <p className="authors">{selected.authors.join(' · ')}</p>
              <div className="study-tags">{selected.keywords.map((keyword) => <span key={keyword}>{keyword}</span>)}</div>
              <div className="similarity-block">
                <div className="similarity-heading"><strong>{selected.problemSimilarity}%</strong><AssessmentLegend score={selected.problemSimilarity} /></div>
                <DimensionBar label="Problem" value={selected.problemSimilarity} emphasis />
                <DimensionBar label="Objectives" value={selected.objectiveOverlap} />
                <DimensionBar label="Solution" value={selected.solutionSimilarity} />
                <small>Confidence {selected.confidence}% · comparable evidence 5/6 fields</small>
              </div>
              <blockquote>{selected.restricted ? <LockKeyhole size={16} /> : <BookOpen size={16} />}<p>{selected.restricted ? 'This excerpt is restricted. Its metadata influenced the score without exposing protected content.' : selected.excerpt}</p></blockquote>
              <ExplainabilityNote>{selected.matchReason}</ExplainabilityNote>
              {evidenceOpen ? <p className="full-evidence-copy">{selected.restricted ? 'The complete evidence remains restricted to authorized catalogue roles.' : selected.abstract}</p> : null}
              <button className="text-button" onClick={() => setEvidenceOpen((open) => !open)}>{evidenceOpen ? 'Close complete evidence' : 'Open complete evidence'} <ArrowRight size={14} /></button>
            </>
          ) : <div className="empty-state"><Search size={24} /><h2>No studies match</h2><p>Try a broader problem or clear the relationship filter.</p></div>}
        </aside>
      </div>

      <section className="ranked-section">
        <div className="section-heading"><div><span>RANKED EVIDENCE</span><h2>Closest studies</h2></div><p>Ranked across problem, objectives, solution, and context—not title alone.</p></div>
        <div className="ranked-list">
          {filtered.map((study, index) => (
            <button key={study.id} className={`ranked-row ${selected?.id === study.id ? 'is-selected' : ''}`} onClick={() => setSelectedId(study.id)}>
              <span className="rank-number">{String(index + 1).padStart(2, '0')}</span>
              <div className="rank-main"><small>{study.code} · {study.program}</small><strong>{study.title}</strong><p>{study.matchReason}</p></div>
              <div className="rank-score"><strong>{study.problemSimilarity}</strong><span>problem</span></div>
              <div className="rank-score secondary"><strong>{study.solutionSimilarity}</strong><span>solution</span></div>
              <ArrowRight className="rank-arrow" size={17} />
            </button>
          ))}
        </div>
      </section>
      <CatalogueIngestionStudio open={ingestionOpen} onOpenChange={setIngestionOpen} />
    </div>
  )
}
