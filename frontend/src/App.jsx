import { useMemo, useState } from 'react'
import { Activity, AlertTriangle, ArrowDownToLine, ChevronDown, Clock3, Database, Radio, Server, Zap } from 'lucide-react'
import ResultsTable from './components/ResultsTable.jsx'
import SearchPanel from './components/SearchPanel.jsx'
import Sidebar from './components/Sidebar.jsx'
import StatCard from './components/StatCard.jsx'
import Topbar from './components/Topbar.jsx'
import { mockLogs } from './data/mockLogs.js'

const stats = [
  { label: 'Events ingested', value: '2.48M', detail: 'in the last hour', trend: '+12.8%', tone: 'violet', icon: ArrowDownToLine },
  { label: 'Error rate', value: '0.34%', detail: '8,432 error events', trend: '-0.12%', tone: 'red', icon: AlertTriangle },
  { label: 'Active services', value: '24', detail: 'across 3 environments', trend: '+2', tone: 'blue', icon: Server },
  { label: 'Avg. response time', value: '142ms', detail: 'across all services', trend: '-8.3%', tone: 'green', icon: Zap },
]

function matchesQuery(log, query) {
  const terms = query.match(/(?:[^\s"]+|"[^"]*")+/g) ?? []
  return terms.every((term) => {
    const normalized = term.replace(/^["']|["']$/g, '')
    if (/^AND$/i.test(normalized)) return true

    const fieldMatch = normalized.match(/^(level|service|message|response_time):(.+)$/i)
    if (!fieldMatch) {
      const searchable = `${log.level} ${log.service} ${log.message}`.toLowerCase()
      return searchable.includes(normalized.toLowerCase())
    }

    const [, field, rawValue] = fieldMatch
    const value = rawValue.replace(/^["']|["']$/g, '')
    if (field.toLowerCase() === 'response_time') {
      const comparison = value.match(/^([<>]=?)(\d+)$/)
      if (!comparison) return String(log.duration).includes(value)
      const [, operator, thresholdText] = comparison
      const threshold = Number(thresholdText)
      if (operator === '>') return log.duration > threshold
      if (operator === '>=') return log.duration >= threshold
      if (operator === '<') return log.duration < threshold
      return log.duration <= threshold
    }

    return String(log[field.toLowerCase()]).toLowerCase().includes(value.toLowerCase())
  })
}

export default function App() {
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [visibleCount, setVisibleCount] = useState(10)
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false)

  const filteredLogs = useMemo(
    () => mockLogs.filter((log) => matchesQuery(log, submittedQuery)),
    [submittedQuery],
  )
  const visibleLogs = filteredLogs.slice(0, visibleCount)

  function runSearch() {
    setSubmittedQuery(query.trim())
    setVisibleCount(10)
  }

  function loadMore() {
    setVisibleCount((count) => count + 10)
  }

  return (
    <div className={`app-shell${mobileSidebarOpen ? ' sidebar-open' : ''}`}>
      {mobileSidebarOpen && <button className="mobile-backdrop" type="button" aria-label="Close navigation" onClick={() => setMobileSidebarOpen(false)} />}
      <Sidebar />
      <main className="main-panel">
        <Topbar onMenuClick={() => setMobileSidebarOpen((open) => !open)} />
        <div className="workspace">
          <SearchPanel query={query} onQueryChange={setQuery} onSearch={runSearch} />

          <section className="stats-grid" aria-label="Ingestion overview">
            {stats.map((stat) => <StatCard key={stat.label} {...stat} />)}
          </section>

          <div className="results-toolbar">
            <div className="stream-status">
              <span className="stream-pulse"><Radio size={13} /></span>
              <span>Streaming from <strong>24 services</strong></span>
              <span className="stream-divider" />
              <span className="ingestion-status"><span /> Ingestion healthy</span>
            </div>
            <div className="results-toolbar-actions">
              <span className="environment-label">ENVIRONMENT</span>
              <button className="environment-button" type="button"><span className="environment-dot" /> Production <ChevronDown size={13} /></button>
              <button className="refresh-button" type="button" aria-label="Refresh logs"><Activity size={15} /></button>
            </div>
          </div>

          <ResultsTable
            logs={visibleLogs}
            totalLogs={filteredLogs.length}
            onLoadMore={loadMore}
          />
          <footer className="workspace-footer">
            <span><Database size={12} /> Query completed in <strong>38ms</strong></span>
            <span><Clock3 size={12} /> Data from the last 60 minutes <ChevronDown size={11} /></span>
          </footer>
        </div>
      </main>
    </div>
  )
}
