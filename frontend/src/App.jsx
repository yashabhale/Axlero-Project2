import { useMemo, useState, useEffect } from 'react'
import { Activity, AlertTriangle, ArrowDownToLine, ChevronDown, Clock3, Database, Radio, Server, Zap, Bell, Search, BarChart2 } from 'lucide-react'
import ResultsTable from './components/ResultsTable.jsx'
import SearchPanel from './components/SearchPanel.jsx'
import Sidebar from './components/Sidebar.jsx'
import StatCard from './components/StatCard.jsx'
import Topbar from './components/Topbar.jsx'
import EChartsView from './components/EChartsView.jsx'
import AlertsPage from './pages/AlertsPage.jsx'
import { useSearch } from './hooks/useSearch.js'
import { useHistogram } from './hooks/useHistogram.js'
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
  const [activePage, setActivePage] = useState('search') // 'search' | 'alerts'
  const [timeRange, setTimeRange] = useState({ start: Date.now() - 3600000, end: Date.now() })

  // Use real backend search
  const {
    results,
    loading,
    error,
    searchParams,
    executeSearch,
    loadMore,
    setQuery: setSearchQuery,
    setTimeRange: setSearchTimeRange,
    hasMore,
    totalHits,
  } = useSearch()

  // Use real backend histogram
  const { data: histogramData, loading: histogramLoading, fetchHistogram } = useHistogram()

  // Fetch histogram when time range changes
  useEffect(() => {
    fetchHistogram(timeRange.start, timeRange.end, 60000)
  }, [timeRange.start, timeRange.end, fetchHistogram])

  // Sync local query with search hook
  useEffect(() => {
    if (submittedQuery !== searchParams.query) {
      setSearchQuery(submittedQuery)
    }
  }, [submittedQuery, searchParams.query, setSearchQuery])

  const filteredLogs = useMemo(
    () => mockLogs.filter((log) => matchesQuery(log, submittedQuery)),
    [submittedQuery],
  )
  const visibleLogs = results?.hits ? results.hits.map(hit => ({
    id: hit.id,
    timestamp: hit.timestamp,
    level: hit.level,
    service: hit.service,
    message: hit.message,
    duration: hit.responseTime,
    tags: hit.tags,
  })) : filteredLogs.slice(0, visibleCount)

  function runSearch() {
    setSubmittedQuery(query.trim())
    setVisibleCount(10)
    executeSearch({ query: query.trim(), page: 0 })
  }

  function handleLoadMore() {
    if (hasMore) {
      loadMore()
    } else {
      setVisibleCount((count) => count + 10)
    }
  }

  function handleTimeRangeChange(newRange) {
    setTimeRange(newRange)
    setSearchTimeRange(newRange.start, newRange.end)
  }

  const navigation = [
    { id: 'search', label: 'Log Search', icon: Search },
    { id: 'alerts', label: 'Alerts', icon: Bell },
    { id: 'analytics', label: 'Analytics', icon: BarChart2 },
  ]

  return (
    <div className={`app-shell${mobileSidebarOpen ? ' sidebar-open' : ''}`}>
      {mobileSidebarOpen && <button className="mobile-backdrop" type="button" aria-label="Close navigation" onClick={() => setMobileSidebarOpen(false)} />}
      <Sidebar navigation={navigation} activePage={activePage} onNavigate={setActivePage} />
      <main className="main-panel">
        <Topbar onMenuClick={() => setMobileSidebarOpen((open) => !open)} />
        <div className="workspace">
          {activePage === 'search' && (
            <>
              <SearchPanel
                query={query}
                onQueryChange={setQuery}
                onSearch={runSearch}
                timeRange={timeRange}
                onTimeRangeChange={handleTimeRangeChange}
                loading={loading}
              />

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
                  <button className="refresh-button" type="button" aria-label="Refresh logs" onClick={runSearch}><Activity size={15} /></button>
                </div>
              </div>

              <ResultsTable
                logs={visibleLogs}
                totalLogs={totalHits || filteredLogs.length}
                onLoadMore={handleLoadMore}
                loading={loading}
              />

              <div className="mt-6">
                <EChartsView
                  data={histogramData?.buckets || []}
                  loading={histogramLoading}
                  height={300}
                />
              </div>

              <footer className="workspace-footer">
                <span><Database size={12} /> Query completed in <strong>{loading ? '...' : '38ms'}</strong></span>
                <span><Clock3 size={12} /> Data from the last 60 minutes <ChevronDown size={11} /></span>
              </footer>
            </>
          )}

          {activePage === 'alerts' && <AlertsPage />}

          {activePage === 'analytics' && (
            <div className="p-6 text-center text-gray-400">
              <BarChart2 className="w-16 h-16 mx-auto mb-4 opacity-30" />
              <h2 className="text-xl font-semibold mb-2">Analytics Dashboard</h2>
              <p>Advanced analytics and visualizations coming soon...</p>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
