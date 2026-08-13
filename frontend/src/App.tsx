import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { useWorkspace } from './hooks/useWorkspace'

const ResearchAtlas = lazy(() => import('./pages/ResearchAtlas'))
const IntakeStudio = lazy(() => import('./pages/IntakeStudio'))
const DecisionRoom = lazy(() => import('./pages/DecisionRoom'))
const AlignmentWorkspace = lazy(() => import('./pages/AlignmentWorkspace'))
const ChangeLab = lazy(() => import('./pages/ChangeLab'))
const ContinuityExplorer = lazy(() => import('./pages/ContinuityExplorer'))
const ReviewQueue = lazy(() => import('./pages/ReviewQueue'))
const ResearchLaboratory = lazy(() => import('./pages/ResearchLaboratory'))

function PageFallback() {
  return (
    <div className="page-fallback" role="status" aria-live="polite">
      <span className="route-loader" />
      <p>Following the evidence trail…</p>
    </div>
  )
}

function ProjectRouteRedirect({ suffix }: { suffix: '/alignment' | '/changes' | '/continuity' | '/reviews' }) {
  const workspace = useWorkspace()
  const navigate = useNavigate()
  if (workspace.isPending) return <PageFallback />
  const projects = workspace.data?.source === 'LIVE' ? workspace.data.data.projects : []
  return <div className="page project-selection-page"><section className="paper-panel project-selection-card"><span>PROJECT-SCOPED WORKSPACE</span><h1>Select a persisted project</h1><p>This route does not choose the first accessible record. Select the exact project whose evidence you intend to inspect.</p><label><span>Accessible projects</span><select defaultValue="" disabled={!projects.length} onChange={(event) => { if (event.target.value) navigate(`/projects/${event.target.value}${suffix}`, { replace: true }) }}><option value="">{projects.length ? 'Choose a project...' : 'No accessible project available'}</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.code} - {project.title}</option>)}</select></label></section></div>
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/atlas" replace />} />
        <Route path="/atlas" element={<Suspense fallback={<PageFallback />}><ResearchAtlas /></Suspense>} />
        <Route path="/intake" element={<Suspense fallback={<PageFallback />}><IntakeStudio /></Suspense>} />
        <Route path="/decision" element={<Suspense fallback={<PageFallback />}><DecisionRoom /></Suspense>} />
        <Route path="/research-lab/query" element={<Suspense fallback={<PageFallback />}><ResearchLaboratory /></Suspense>} />
        <Route path="/research-lab/evaluation" element={<Suspense fallback={<PageFallback />}><ResearchLaboratory /></Suspense>} />
        <Route path="/research-lab/warehouse" element={<Suspense fallback={<PageFallback />}><ResearchLaboratory /></Suspense>} />
        <Route path="/alignment" element={<ProjectRouteRedirect suffix="/alignment" />} />
        <Route path="/changes" element={<ProjectRouteRedirect suffix="/changes" />} />
        <Route path="/continuity" element={<ProjectRouteRedirect suffix="/continuity" />} />
        <Route path="/reviews" element={<ProjectRouteRedirect suffix="/reviews" />} />
        <Route path="/projects/:projectId/alignment" element={<Suspense fallback={<PageFallback />}><AlignmentWorkspace /></Suspense>} />
        <Route path="/projects/:projectId/changes" element={<Suspense fallback={<PageFallback />}><ChangeLab /></Suspense>} />
        <Route path="/projects/:projectId/continuity" element={<Suspense fallback={<PageFallback />}><ContinuityExplorer /></Suspense>} />
        <Route path="/projects/:projectId/reviews" element={<Suspense fallback={<PageFallback />}><ReviewQueue /></Suspense>} />
        <Route path="*" element={<Navigate to="/atlas" replace />} />
      </Route>
    </Routes>
  )
}
