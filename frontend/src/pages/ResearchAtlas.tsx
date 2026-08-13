import { useDeferredValue, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import {
  ArrowLeft, ArrowRight, BookOpen, Braces, Database, Filter, LibraryBig,
  LockKeyhole, Search, SlidersHorizontal,
} from 'lucide-react'
import { useWorkspace } from '../hooks/useWorkspace'
import { useAuthSession } from '../hooks/useAuthSession'
import { searchCatalogue, type CatalogueSearchPage, type CatalogueStudySummary } from '../lib/api'
import { CatalogueIngestionStudio } from '../components/CatalogueIngestionStudio'
import { PageHeader, StatusPill } from '../components/Primitives'

const PAGE_SIZE = 12

function catalogueUnavailable(value: string | null | undefined) {
  return !value || ['not specified', 'program not recorded', 'unavailable'].includes(value.trim().toLowerCase())
}

export default function ResearchAtlas() {
  const navigate = useNavigate()
  const { data } = useWorkspace()
  const { data: auth } = useAuthSession()
  const workspaceStudies = useMemo(() => data?.data.studies ?? [], [data?.data.studies])
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query.trim())
  const [selectedId, setSelectedId] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [department, setDepartment] = useState('')
  const [yearFrom, setYearFrom] = useState('')
  const [yearTo, setYearTo] = useState('')
  const [lifecycle, setLifecycle] = useState('')
  const [sort, setSort] = useState<'YEAR_DESC' | 'YEAR_ASC' | 'TITLE_ASC' | 'TITLE_DESC'>('YEAR_DESC')
  const [page, setPage] = useState(0)
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const [ingestionOpen, setIngestionOpen] = useState(false)
  const isLive = data?.source === 'LIVE' && auth?.session.authenticated === true
  const canIngest = isLive && auth?.session.roles.includes('CURATOR')

  const serverSearch = useQuery({
    queryKey: ['catalogue-search', deferredQuery, department, yearFrom, yearTo, lifecycle, sort, page],
    queryFn: () => searchCatalogue({
      q: deferredQuery || undefined,
      department: department || undefined,
      yearFrom: yearFrom ? Number(yearFrom) : undefined,
      yearTo: yearTo ? Number(yearTo) : undefined,
      lifecycle: lifecycle || undefined,
      sort,
      page,
      size: PAGE_SIZE,
    }),
    enabled: isLive,
  })

  const demoPage = useMemo<CatalogueSearchPage>(() => {
    const normalized = deferredQuery.toLowerCase()
    const from = yearFrom ? Number(yearFrom) : undefined
    const to = yearTo ? Number(yearTo) : undefined
    const mapped: CatalogueStudySummary[] = workspaceStudies.map((study) => ({
      id: study.id,
      institutionalCode: study.code,
      title: study.title,
      academicYear: study.year ? String(study.year) : null,
      completionYear: study.year || null,
      departmentCode: null,
      departmentName: null,
      program: catalogueUnavailable(study.program) ? null : study.program,
      lifecycleStatus: study.status,
      visibility: study.restricted ? 'RESTRICTED' : 'INTERNAL',
      abstractText: study.restricted ? null : study.abstract,
      problemStatement: null,
      methodology: null,
      keywords: study.restricted ? [] : study.keywords,
      resultsText: null,
      objectiveCount: 0,
    }))
    const filtered = mapped.filter((study) => {
      const haystack = [study.title, study.institutionalCode, study.program, ...study.keywords].filter(Boolean).join(' ').toLowerCase()
      return (!normalized || haystack.includes(normalized))
        && (!lifecycle || study.lifecycleStatus === lifecycle)
        && (!from || (study.completionYear != null && study.completionYear >= from))
        && (!to || (study.completionYear != null && study.completionYear <= to))
    }).sort((left, right) => {
      if (sort === 'TITLE_ASC' || sort === 'TITLE_DESC') return left.title.localeCompare(right.title) * (sort === 'TITLE_DESC' ? -1 : 1)
      const year = (left.completionYear ?? -1) - (right.completionYear ?? -1)
      return (year || left.title.localeCompare(right.title)) * (sort === 'YEAR_DESC' ? -1 : 1)
    })
    return { items: filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE), totalItems: filtered.length, page, pageSize: PAGE_SIZE, generatedAt: data?.data.generatedAt ?? '' }
  }, [data?.data.generatedAt, deferredQuery, lifecycle, page, sort, workspaceStudies, yearFrom, yearTo])

  const result = isLive ? serverSearch.data : demoPage
  const records = result?.items ?? []
  const selected = records.find((study) => study.id === selectedId)
  const pageCount = Math.max(1, Math.ceil((result?.totalItems ?? 0) / PAGE_SIZE))
  const refreshDate = result?.generatedAt ? new Intl.DateTimeFormat('en-PH', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(result.generatedAt)) : 'Unavailable'
  const setFilter = (action: () => void) => { action(); setPage(0); setSelectedId('') }

  return (
    <div className="page atlas-page catalogue-atlas-page">
      <PageHeader
        eyebrow="Discover prior work"
        title="Research Atlas"
        description="Search actual catalogue metadata with authorized server-side filters. Similarity is shown only after a real retrieval query or proposal context is evaluated."
        actions={<>{canIngest ? <button className="button button-secondary" onClick={() => setIngestionOpen(true)}><Database size={16} />Ingest research</button> : null}<button className="button button-primary" onClick={() => navigate('/intake')}>Start a problem intake <ArrowRight size={16} /></button></>}
        meta={<><StatusPill tone={isLive ? 'teal' : 'amber'}>{isLive ? 'Authorized live catalogue' : 'Offline pilot catalogue'}</StatusPill><span>Snapshot · {refreshDate}</span></>}
      />

      <section className="atlas-search catalogue-search" aria-label="Search the research catalogue">
        <Search size={19} />
        <label className="sr-only" htmlFor="atlas-query">Search studies</label>
        <input id="atlas-query" value={query} onChange={(event) => setFilter(() => setQuery(event.target.value))} placeholder="Search title, problem, method, or keyword…" />
        <button className={`filter-toggle ${filtersOpen ? 'is-active' : ''}`} onClick={() => setFiltersOpen((value) => !value)} aria-expanded={filtersOpen} aria-controls="catalogue-filters"><SlidersHorizontal size={16} /> Filters</button>
      </section>

      {filtersOpen ? <section id="catalogue-filters" className="catalogue-filter-panel paper-panel">
        <Filter size={18} />
        <label><span>Department code</span><input value={department} onChange={(event) => setFilter(() => setDepartment(event.target.value))} placeholder="e.g. CICS" /></label>
        <label><span>From year</span><input type="number" min="1900" max="2200" value={yearFrom} onChange={(event) => setFilter(() => setYearFrom(event.target.value))} /></label>
        <label><span>To year</span><input type="number" min="1900" max="2200" value={yearTo} onChange={(event) => setFilter(() => setYearTo(event.target.value))} /></label>
        <label><span>Lifecycle</span><select value={lifecycle} onChange={(event) => setFilter(() => setLifecycle(event.target.value))}><option value="">All states</option><option value="PUBLISHED">Published</option><option value="COMPLETED">Completed</option><option value="INCOMPLETE">Incomplete</option><option value="SUSPENDED">Suspended</option></select></label>
        <label><span>Sort</span><select value={sort} onChange={(event) => setFilter(() => setSort(event.target.value as typeof sort))}><option value="YEAR_DESC">Newest validated year</option><option value="YEAR_ASC">Oldest validated year</option><option value="TITLE_ASC">Title A–Z</option><option value="TITLE_DESC">Title Z–A</option></select></label>
      </section> : null}

      {serverSearch.isError ? <div className="atlas-api-error" role="alert"><LockKeyhole size={18} /><div><strong>The authorized catalogue could not be searched.</strong><span>{serverSearch.error instanceof Error ? serverSearch.error.message : 'Retry after the local API becomes available.'}</span></div></div> : null}

      <div className="catalogue-browser">
        <section className="catalogue-results paper-panel" aria-busy={serverSearch.isFetching}>
          <div className="catalogue-results-head"><div><span>CATALOGUE RECORDS</span><h2>{result?.totalItems ?? 0} {result?.totalItems === 1 ? 'study' : 'studies'} in scope</h2></div>{serverSearch.isFetching ? <span className="catalogue-loading">Searching…</span> : <StatusPill tone="neutral">Page {page + 1} of {pageCount}</StatusPill>}</div>
          <div className="catalogue-record-list">
            {records.map((study) => <button key={study.id} className={selectedId === study.id ? 'is-selected' : ''} onClick={() => setSelectedId(study.id)} aria-pressed={selectedId === study.id}>
              <span className="catalogue-record-icon">{study.visibility === 'RESTRICTED' ? <LockKeyhole size={16} /> : <LibraryBig size={16} />}</span>
              <span className="catalogue-record-copy"><small>{study.institutionalCode} · {study.departmentCode ?? 'Department unavailable'}</small><strong>{study.title}</strong><em>{study.program ?? 'Program unavailable'} · {study.completionYear ?? study.academicYear ?? 'Year unavailable'}</em><span>{study.keywords.slice(0, 4).map((keyword) => <i key={keyword}>{keyword}</i>)}</span></span>
              <StatusPill tone={study.lifecycleStatus === 'PUBLISHED' || study.lifecycleStatus === 'COMPLETED' ? 'teal' : 'amber'}>{study.lifecycleStatus}</StatusPill><ArrowRight size={16} />
            </button>)}
            {!records.length ? <div className="atlas-empty-state"><Search size={23} /><strong>No authorized records match these filters.</strong><span>Broaden the terms or clear a filter. UGNAY does not invent catalogue results.</span></div> : null}
          </div>
          <div className="catalogue-pagination"><button className="button button-secondary" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}><ArrowLeft size={15} />Previous</button><span>{result?.totalItems ? `${page * PAGE_SIZE + 1}–${Math.min((page + 1) * PAGE_SIZE, result.totalItems)} of ${result.totalItems}` : '0 records'}</span><button className="button button-secondary" disabled={page + 1 >= pageCount} onClick={() => setPage((value) => value + 1)}>Next<ArrowRight size={15} /></button></div>
        </section>

        <aside className="catalogue-evidence paper-panel" aria-label="Selected study evidence" aria-live="polite">
          {selected ? <>
            <div className="catalogue-evidence-kicker"><span>{selected.institutionalCode}</span><StatusPill tone="neutral">Catalogue fact</StatusPill></div>
            <h2>{selected.title}</h2>
            <dl><div><dt>Department</dt><dd>{selected.departmentName ?? selected.departmentCode ?? 'Unavailable'}</dd></div><div><dt>Program</dt><dd>{selected.program ?? 'Unavailable'}</dd></div><div><dt>Validated year</dt><dd>{selected.completionYear ?? 'Unavailable'}</dd></div><div><dt>Visibility</dt><dd>{selected.visibility}</dd></div><div><dt>Objectives</dt><dd>{selected.objectiveCount || 'Unavailable'}</dd></div><div><dt>Methodology</dt><dd>{selected.methodology ?? 'Unavailable'}</dd></div></dl>
            {selected.keywords.length ? <div className="catalogue-evidence-tags">{selected.keywords.map((keyword) => <span key={keyword}>{keyword}</span>)}</div> : null}
            <blockquote><BookOpen size={16} /><p>{selected.abstractText ?? (selected.visibility === 'RESTRICTED' ? 'Protected content is not serialized for this viewer.' : 'Abstract unavailable in source evidence.')}</p></blockquote>
            {evidenceOpen ? <div className="catalogue-complete-evidence"><section><span>PROBLEM</span><p>{selected.problemStatement ?? 'Unavailable'}</p></section><section><span>RESULTS</span><p>{selected.resultsText ?? 'Unavailable'}</p></section></div> : null}
            <button className="text-button" onClick={() => setEvidenceOpen((open) => !open)}>{evidenceOpen ? 'Hide research evidence' : 'View research evidence'} <ArrowRight size={14} /></button>
            <div className="no-score-notice"><Braces size={17} /><div><strong>No similarity score is attached to a catalogue search.</strong><span>Use a real text or proposal context in the Research Laboratory to compute and explain relevance.</span></div></div>
            <button className="button button-primary full-width" onClick={() => navigate('/research-lab/query')}><Braces size={16} />Open Query Interpreter</button>
          </> : <div className="atlas-empty-state"><Search size={24} /><strong>Select a catalogue record</strong><span>Authorized factual evidence will appear here.</span></div>}
        </aside>
      </div>
      <CatalogueIngestionStudio open={ingestionOpen} onOpenChange={setIngestionOpen} />
    </div>
  )
}
