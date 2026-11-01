import {
  Activity,
  Bell,
  ChevronDown,
  CircleHelp,
  Clock3,
  FileText,
  Layers3,
  Search,
  Settings2,
  ShieldCheck,
} from 'lucide-react'

const primaryNavigation = [
  { label: 'Overview', icon: Layers3 },
  { label: 'Log explorer', icon: Search, active: true },
  { label: 'Live tail', icon: Activity, badge: 'LIVE' },
  { label: 'Alerts', icon: Bell, count: '3' },
]

const workspaceNavigation = [
  { label: 'Saved searches', icon: FileText },
  { label: 'Dashboards', icon: Layers3 },
  { label: 'Manage sources', icon: Settings2 },
]

function NavigationLink({ item }) {
  const Icon = item.icon

  return (
    <button className={`nav-link${item.active ? ' nav-link-active' : ''}`} type="button">
      <Icon aria-hidden="true" size={17} strokeWidth={1.8} />
      <span>{item.label}</span>
      {item.badge && <span className="nav-live">{item.badge}</span>}
      {item.count && <span className="nav-count">{item.count}</span>}
    </button>
  )
}

export default function Sidebar() {
  return (
    <aside className="sidebar">
      <a className="brand" href="#" aria-label="LogStream home">
        <span className="brand-mark"><Activity size={19} strokeWidth={2.2} /></span>
        <span className="brand-name">logstream<span>.</span></span>
      </a>

      <button className="workspace-picker" type="button">
        <span className="workspace-avatar">A</span>
        <span className="workspace-copy">
          <span className="workspace-label">WORKSPACE</span>
          <span className="workspace-name">Acme Production</span>
        </span>
        <ChevronDown aria-hidden="true" size={15} />
      </button>

      <div className="sidebar-section">
        <div className="sidebar-section-label">OBSERVABILITY</div>
        <nav aria-label="Observability">
          {primaryNavigation.map((item) => <NavigationLink key={item.label} item={item} />)}
        </nav>
      </div>

      <div className="sidebar-section workspace-section">
        <div className="sidebar-section-label">WORKSPACE</div>
        <nav aria-label="Workspace">
          {workspaceNavigation.map((item) => <NavigationLink key={item.label} item={item} />)}
        </nav>
      </div>

      <div className="sidebar-bottom">
        <div className="usage-card">
          <div className="usage-heading"><span>Monthly ingestion</span><span>68%</span></div>
          <div className="usage-track"><span /></div>
          <div className="usage-caption">2.04 TB <span>of 3 TB</span></div>
          <button type="button">View usage <span aria-hidden="true">→</span></button>
        </div>
        <button className="nav-link sidebar-help" type="button">
          <CircleHelp aria-hidden="true" size={17} strokeWidth={1.8} />
          <span>Help &amp; support</span>
        </button>
        <button className="profile-button" type="button">
          <span className="profile-avatar">JD</span>
          <span className="profile-copy">
            <span className="profile-name">Jordan Davis</span>
            <span className="profile-email">jordan@acme.io</span>
          </span>
          <Settings2 aria-hidden="true" size={16} />
        </button>
      </div>
      <span className="sidebar-version"><ShieldCheck size={12} /> All systems operational</span>
      <span className="sidebar-clock"><Clock3 size={12} /> Data retention: 30 days</span>
    </aside>
  )
}
