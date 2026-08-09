import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'

const ResearchAtlas = lazy(() => import('./pages/ResearchAtlas'))
const IntakeStudio = lazy(() => import('./pages/IntakeStudio'))
const DecisionRoom = lazy(() => import('./pages/DecisionRoom'))
const AlignmentWorkspace = lazy(() => import('./pages/AlignmentWorkspace'))
const ChangeLab = lazy(() => import('./pages/ChangeLab'))
const ContinuityExplorer = lazy(() => import('./pages/ContinuityExplorer'))
const ReviewQueue = lazy(() => import('./pages/ReviewQueue'))

function PageFallback() {
  return (
    <div className="page-fallback" role="status" aria-live="polite">
      <span className="route-loader" />
      <p>Following the evidence trail…</p>
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/atlas" replace />} />
        <Route path="/atlas" element={<Suspense fallback={<PageFallback />}><ResearchAtlas /></Suspense>} />
        <Route path="/intake" element={<Suspense fallback={<PageFallback />}><IntakeStudio /></Suspense>} />
        <Route path="/decision" element={<Suspense fallback={<PageFallback />}><DecisionRoom /></Suspense>} />
        <Route path="/alignment" element={<Suspense fallback={<PageFallback />}><AlignmentWorkspace /></Suspense>} />
        <Route path="/changes" element={<Suspense fallback={<PageFallback />}><ChangeLab /></Suspense>} />
        <Route path="/continuity" element={<Suspense fallback={<PageFallback />}><ContinuityExplorer /></Suspense>} />
        <Route path="/reviews" element={<Suspense fallback={<PageFallback />}><ReviewQueue /></Suspense>} />
        <Route path="/projects/:projectId/alignment" element={<Suspense fallback={<PageFallback />}><AlignmentWorkspace /></Suspense>} />
        <Route path="/projects/:projectId/changes" element={<Suspense fallback={<PageFallback />}><ChangeLab /></Suspense>} />
        <Route path="/projects/:projectId/continuity" element={<Suspense fallback={<PageFallback />}><ContinuityExplorer /></Suspense>} />
        <Route path="/projects/:projectId/reviews" element={<Suspense fallback={<PageFallback />}><ReviewQueue /></Suspense>} />
        <Route path="*" element={<Navigate to="/atlas" replace />} />
      </Route>
    </Routes>
  )
}
