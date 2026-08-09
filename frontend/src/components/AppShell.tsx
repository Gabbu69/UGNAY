import { useState, type ElementType } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import {
  Activity, ArrowUpRight, Beaker, BookOpenText, ChevronDown, GitBranch,
  LayoutList, Menu, Network, PanelLeftClose, ScanSearch, UserRound, Waypoints, X,
} from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useAuthSession } from '../hooks/useAuthSession'
import { useWorkspace } from '../hooks/useWorkspace'
import { AccountDialog } from './AccountDialog'

interface NavigationItem {
  to: string
  label: string
  shortLabel: string
  icon: ElementType
  section: 'discover' | 'develop' | 'preserve'
  projectScoped?: boolean
}

const navigation: NavigationItem[] = [
  { to: '/atlas', label: 'Research Atlas', shortLabel: 'Atlas', icon: Network, section: 'discover' },
  { to: '/intake', label: 'Intake Studio', shortLabel: 'Intake', icon: BookOpenText, section: 'discover' },
  { to: '/decision', label: 'Decision Room', shortLabel: 'Decision', icon: ScanSearch, section: 'discover' },
  { to: '/alignment', label: 'Alignment Workspace', shortLabel: 'Alignment', icon: Waypoints, section: 'develop', projectScoped: true },
  { to: '/changes', label: 'Change Lab', shortLabel: 'Changes', icon: Beaker, section: 'develop', projectScoped: true },
  { to: '/continuity', label: 'Continuity Explorer', shortLabel: 'Continuity', icon: GitBranch, section: 'preserve', projectScoped: true },
  { to: '/reviews', label: 'Review Queue', shortLabel: 'Reviews', icon: LayoutList, section: 'preserve', projectScoped: true },
]

const sectionLabels = {
  discover: 'Discover & decide',
  develop: 'Align & verify',
  preserve: 'Preserve & continue',
}

function emailInitials(email: string) {
  const parts = email.split('@')[0]?.split(/[._-]/).filter(Boolean) ?? []
  if (parts.length > 1) return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase()
  return (parts[0]?.slice(0, 2) || 'UG').toUpperCase()
}

function roleLabel(role: string | undefined) {
  if (!role) return 'Authenticated account'
  return role.toLowerCase().replaceAll('_', ' ').replace(/(^|\s)\S/g, (letter) => letter.toUpperCase())
}

function UgnayMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <span />
      <span />
      <span />
      <svg viewBox="0 0 48 48" role="presentation">
        <path d="M13 14.5 24 8l11 6.5v13L24 34l-11-6.5z" />
        <path d="m13 27.5 11 12 11-12M24 8v26" />
      </svg>
    </div>
  )
}

function itemTarget(item: NavigationItem, projectId?: string) {
  if (!item.projectScoped || !projectId) return item.to
  return `/projects/${projectId}${item.to}`
}

function SideNavigation({ onNavigate, projectId }: { onNavigate?: () => void; projectId?: string }) {
  return (
    <nav className="side-navigation" aria-label="UGNAY workspaces">
      {(Object.keys(sectionLabels) as Array<keyof typeof sectionLabels>).map((section) => (
        <div className="nav-section" key={section}>
          <p>{sectionLabels[section]}</p>
          {navigation.filter((item) => item.section === section).map((item) => {
            const { to, label, icon: Icon } = item
            return <NavLink key={to} to={itemTarget(item, projectId)} onClick={onNavigate} className={({ isActive }) => isActive ? 'nav-link is-active' : 'nav-link'}>
              <Icon size={17} strokeWidth={1.8} />
              <span>{label}</span>
              <i aria-hidden="true" />
            </NavLink>
          })}
        </div>
      ))}
    </nav>
  )
}

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const { projectId } = useParams()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [accountOpen, setAccountOpen] = useState(false)
  const workspaceQuery = useWorkspace()
  const authQuery = useAuthSession()
  const workspace = workspaceQuery.data?.data
  const auth = authQuery.data
  const session = auth?.session
  const isAuthenticated = session?.authenticated === true
  const accountName = isAuthenticated ? session.email ?? 'Authenticated account' : auth?.source === 'LIVE' ? 'Sign in' : 'Pilot access'
  const accountDetail = isAuthenticated ? roleLabel(session.roles[0]) : auth?.source === 'LIVE' ? 'Invite-only account' : 'Session unavailable'
  const accountInitials = isAuthenticated && session.email ? emailInitials(session.email) : auth?.source === 'LIVE' ? 'SI' : 'PA'
  const current = navigation.find((item) => location.pathname === item.to || location.pathname.endsWith(item.to))
  const selectedProjectId = projectId ?? workspace?.project.id
  const linkedArtifacts = workspace ? new Set(workspace.traceEdges.flatMap((edge) => [edge.source, edge.target])).size : 0
  const traceArtifactCount = workspace?.traceNodes.length ?? 0
  const selectProject = (nextProjectId: string) => {
    const suffix = current?.projectScoped ? current.to : '/alignment'
    navigate(`/projects/${nextProjectId}${suffix}`)
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">Skip to main content</a>
      <aside className="sidebar" aria-label="Primary workspace navigation">
        <div className="brand-lockup">
          <UgnayMark />
          <div>
            <strong>UGNAY</strong>
            <span>Research continuity</span>
          </div>
        </div>
        <SideNavigation projectId={selectedProjectId} />
        <div className="sidebar-foot">
          <div className="signal-orbit" aria-hidden="true"><span /><i /></div>
          <div>
            <small>Evidence chain</small>
            <strong>{traceArtifactCount ? `${linkedArtifacts} of ${traceArtifactCount} linked` : 'UNASSESSED'}</strong>
          </div>
          <ArrowUpRight size={15} />
        </div>
      </aside>

      <AnimatePresence>
        {mobileOpen ? (
          <>
            <motion.button
              className="mobile-scrim"
              aria-label="Close navigation"
              onClick={() => setMobileOpen(false)}
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            />
            <motion.aside
              className="mobile-drawer"
              initial={{ x: '-100%' }} animate={{ x: 0 }} exit={{ x: '-100%' }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
            >
              <div className="brand-lockup">
                <UgnayMark />
                <div><strong>UGNAY</strong><span>Research continuity</span></div>
                <button className="icon-button drawer-close" onClick={() => setMobileOpen(false)} aria-label="Close navigation"><X size={20} /></button>
              </div>
              <SideNavigation projectId={selectedProjectId} onNavigate={() => setMobileOpen(false)} />
              <button
                className="mobile-account-button"
                type="button"
                onClick={() => {
                  setMobileOpen(false)
                  setAccountOpen(true)
                }}
              >
                <UserRound size={17} />
                <span><strong>{accountName}</strong><small>{accountDetail}</small></span>
                <ArrowUpRight size={14} />
              </button>
            </motion.aside>
          </>
        ) : null}
      </AnimatePresence>

      <div className="app-frame">
        <header className="topbar">
          <div className="topbar-leading">
            <button className="icon-button menu-button" onClick={() => setMobileOpen(true)} aria-label="Open navigation"><Menu size={20} /></button>
            <PanelLeftClose className="topbar-thread-icon" size={17} aria-hidden="true" />
            <span>{current?.shortLabel ?? 'Workspace'}</span>
            <i />
            <label className="project-switcher">
              <span>{workspace?.project.code ?? 'UNASSESSED'}</span>
              <strong>{workspace?.project.title ?? 'Loading project...'}</strong>
              <select aria-label="Select research project" value={selectedProjectId ?? ''}
                onChange={(event) => selectProject(event.target.value)} disabled={!workspace?.projects.length}>
                {workspace?.projects.map((project) => <option key={project.id} value={project.id}>{project.code} - {project.title}</option>)}
              </select>
              <ChevronDown size={15} />
            </label>
          </div>
          <div className="topbar-trailing">
            <div className={`source-badge ${workspaceQuery.data?.source === 'LIVE' ? 'is-live' : ''}`} title="Workspace data source">
              <span /> {workspaceQuery.data?.source === 'LIVE' ? 'Live API' : 'Data unavailable'}
            </div>
            <button className="avatar-button" type="button" onClick={() => setAccountOpen(true)} aria-label="Open account panel">
              <span>{accountInitials}</span>
              <div><strong>{accountName}</strong><small>{accountDetail}</small></div>
              <ChevronDown size={14} />
            </button>
          </div>
        </header>

        <main id="main-content" className="main-canvas">
          <AnimatePresence mode="wait">
            <motion.div
              className="route-stage"
              key={location.pathname}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>

        <nav className="mobile-tabs" aria-label="Primary mobile navigation">
          {navigation.slice(0, 5).map((item) => {
            const { to, shortLabel, icon: Icon } = item
            return <NavLink key={to} to={itemTarget(item, selectedProjectId)} className={({ isActive }) => isActive ? 'is-active' : ''}>
              <Icon size={18} />
              <span>{shortLabel}</span>
            </NavLink>
          })}
          <button onClick={() => setMobileOpen(true)}><Activity size={18} /><span>More</span></button>
        </nav>
      </div>

      <AccountDialog
        open={accountOpen}
        onOpenChange={setAccountOpen}
        auth={auth}
        authLoading={authQuery.isPending}
        workspaceSource={workspaceQuery.data?.source ?? 'UNAVAILABLE'}
        workspaceDepartment={workspace?.currentUser.department ?? 'University research workspace'}
        projectId={selectedProjectId}
      />
    </div>
  )
}
