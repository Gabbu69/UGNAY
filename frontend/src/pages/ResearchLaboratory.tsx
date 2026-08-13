import { lazy, Suspense } from 'react'
import { BarChart3, Braces, Database, FlaskConical, ShieldCheck } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import { PageHeader, StatusPill } from '../components/Primitives'

const QueryWorkbench = lazy(() => import('../components/research-lab/QueryWorkbench').then((module) => ({ default: module.QueryWorkbench })))
const EvaluationWorkbench = lazy(() => import('../components/research-lab/EvaluationWorkbench').then((module) => ({ default: module.EvaluationWorkbench })))
const WarehouseWorkbench = lazy(() => import('../components/research-lab/WarehouseWorkbench').then((module) => ({ default: module.WarehouseWorkbench })))

const tabs = [
  { path: '/research-lab/query', label: 'Query interpreter', short: 'Query', icon: Braces },
  { path: '/research-lab/evaluation', label: 'Algorithm evaluation', short: 'Evaluation', icon: BarChart3 },
  { path: '/research-lab/warehouse', label: 'Research warehouse', short: 'Warehouse', icon: Database },
]

export default function ResearchLaboratory() {
  const location = useLocation()
  const active = location.pathname.endsWith('/evaluation')
    ? 'evaluation'
    : location.pathname.endsWith('/warehouse') ? 'warehouse' : 'query'

  return (
    <div className="page research-lab-page">
      <PageHeader
        eyebrow="Reproducible research framework"
        title="Research Laboratory"
        description="Inspect how a safe research query becomes a retrieval plan, how mining algorithms compare on frozen evidence, and how historical records become authorized analytics."
        meta={<><StatusPill tone="teal">Human-controlled decisions</StatusPill><span>Interpreter · Warehouse · Retrieval · Evidence</span></>}
      />

      <section className="lab-framework" aria-label="Integrated UGNAY research framework">
        <div className="lab-framework-mark"><FlaskConical size={21} /><span>One evidence path</span></div>
        <ol>
          <li><Braces size={17} /><span><b>Interpret</b><small>Lexer to safe plan</small></span></li>
          <li><Database size={17} /><span><b>Supply</b><small>Authorized history</small></span></li>
          <li><BarChart3 size={17} /><span><b>Discover</b><small>Versioned retrieval</small></span></li>
          <li><ShieldCheck size={17} /><span><b>Review</b><small>Human judgment</small></span></li>
        </ol>
      </section>

      <nav className="lab-tabs" aria-label="Research Laboratory sections">
        {tabs.map(({ path, label, short, icon: Icon }) => {
          const selected = location.pathname === path
          return (
            <Link key={path} to={path} aria-current={selected ? 'page' : undefined} className={selected ? 'is-active' : ''}>
              <Icon size={17} /><span>{label}</span><small>{short}</small>
            </Link>
          )
        })}
      </nav>

      <div className="lab-workbench">
        <Suspense fallback={<div className="lab-empty" role="status"><FlaskConical size={22} /><div><strong>Loading the selected workbench...</strong><span>Only this research tool is loaded into memory.</span></div></div>}>
          {active === 'query' ? <QueryWorkbench /> : active === 'evaluation' ? <EvaluationWorkbench /> : <WarehouseWorkbench />}
        </Suspense>
      </div>
    </div>
  )
}
