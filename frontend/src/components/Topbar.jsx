import { Bell, ChevronDown, Command, Menu, Plus, Search, Sparkles } from 'lucide-react'

export default function Topbar({ onMenuClick }) {
  return (
    <header className="topbar">
      <button className="mobile-menu icon-button" type="button" onClick={onMenuClick} aria-label="Open navigation">
        <Menu size={18} />
      </button>
      <div className="breadcrumbs">
        <span>Observability</span>
        <span className="breadcrumb-divider">/</span>
        <strong>Log explorer</strong>
      </div>
      <div className="topbar-actions">
        <button className="global-search" type="button">
          <Search size={15} />
          <span>Search anything...</span>
          <kbd><Command size={11} /> K</kbd>
        </button>
        <button className="icon-button notification-button" type="button" aria-label="Notifications">
          <Bell size={17} />
          <span className="notification-dot" />
        </button>
        <button className="whats-new-button" type="button">
          <Sparkles size={14} />
          <span>What&apos;s new</span>
        </button>
        <button className="invite-button" type="button">
          <Plus size={15} />
          <span>Invite team</span>
        </button>
        <button className="header-avatar" type="button" aria-label="Account menu">JD<ChevronDown size={12} /></button>
      </div>
    </header>
  )
}
