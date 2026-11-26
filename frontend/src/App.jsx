import { useEffect, useReducer, useRef, useState } from 'react'
import { Activity, AlertTriangle, ArrowDownToLine, ChevronDown, Clock3, Database, Radio, Server, Zap } from 'lucide-react'
import { fetchLogs, parseLogSearch } from './api/logApi.js'
import {
  createInitialLogExplorerState,
  logExplorerReducer,
  LOG_PAGE_SIZE,
} from './api/logExplorerReducer.js'
import ResultsTable from './components/ResultsTable.jsx'
import SearchPanel from './components/SearchPanel.jsx'
import Sidebar from './components/Sidebar.jsx'
import StatCard from './components/StatCard.jsx'
import Topbar from './components/Topbar.jsx'

const unavailableStats = [
  { label: 'Events ingested', value: 'N/A', detail: 'Not provided by log API', tone: 'violet', icon: ArrowDownToLine },
  { label: 'Error rate', value: 'N/A', detail: 'Not provided by log API', tone: 'red', icon: AlertTriangle },
  { label: 'Active services', value: 'N/A', detail: 'Not provided by log API', tone: 'blue', icon: Server },
  { label: 'Avg. response time', value: 'N/A', detail: 'Not provided by log API', tone: 'green', icon: Zap },
]

export default function App() {
  const [explorer, dispatch] = useReducer(
    logExplorerReducer,
    undefined,
    createInitialLogExplorerState,
  )
  const [query, setQuery] = useState('')
  const [queryError, setQueryError] = useState(null)
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false)
  const requestSequence = useRef(0)

  useEffect(() => {
    const controller = new AbortController()
    const requestId = ++requestSequence.current
    const endTime = Date.now()

    dispatch({ type: 'request-started', requestId })
    fetchLogs({
      ...explorer.filters,
      startTime: endTime - explorer.rangeHours * 60 * 60 * 1000,
      endTime,
      limit: LOG_PAGE_SIZE,
      offset: explorer.page * LOG_PAGE_SIZE,
    }, { signal: controller.signal })
      .then((result) => dispatch({ type: 'request-succeeded', requestId, result }))
      .catch((error) => {
        if (controller.signal.aborted || error?.name === 'AbortError') return
        dispatch({
          type: 'request-failed',
          requestId,
          error: error instanceof Error ? error.message : 'Unable to load logs.',
        })
      })

    return () => controller.abort()
  }, [explorer.filters, explorer.page, explorer.rangeHours, explorer.refreshToken])

  function runSearch(nextQuery = query) {
    setQuery(nextQuery)
    try {
      const filters = parseLogSearch(nextQuery)
      setQueryError(null)
      dispatch({ type: 'filters-changed', filters })
    } catch (error) {
      setQueryError(error instanceof Error ? error.message : 'Invalid search query.')
    }
  }

  const timeRangeLabel = `Last ${explorer.rangeHours} ${explorer.rangeHours === 1 ? 'hour' : 'hours'}`
  const apiStatus = explorer.error
    ? 'Backend unavailable'
    : explorer.loading
      ? 'Loading logs'
      : 'Backend connected'

  return (
    <div className={`app-shell${mobileSidebarOpen ? ' sidebar-open' : ''}`}>
      {mobileSidebarOpen && <button className="mobile-backdrop" type="button" aria-label="Close navigation" onClick={() => setMobileSidebarOpen(false)} />}
      <Sidebar />
      <main className="main-panel">
        <Topbar onMenuClick={() => setMobileSidebarOpen((open) => !open)} />
        <div className="workspace">
          <SearchPanel
            query={query}
            onQueryChange={setQuery}
            onSearch={runSearch}
            rangeHours={explorer.rangeHours}
            onRangeChange={(rangeHours) => dispatch({ type: 'range-changed', rangeHours })}
            validationError={queryError}
          />

          <section className="stats-grid" aria-label="Backend metrics unavailable">
            {unavailableStats.map((stat) => <StatCard key={stat.label} {...stat} />)}
          </section>

          <div className="results-toolbar">
            <div className="stream-status">
              <span className="stream-pulse"><Radio size={13} /></span>
              <span>Source: <strong>Backend API</strong></span>
              <span className="stream-divider" />
              <span className={`ingestion-status${explorer.error ? ' api-status-error' : explorer.loading ? ' api-status-pending' : ''}`}>
                <span /> {apiStatus}
              </span>
            </div>
            <div className="results-toolbar-actions">
              <span className="environment-label">DATA SOURCE</span>
              <button className="environment-button" type="button" disabled>
                <span className="environment-dot" /> Backend API <ChevronDown size={13} />
              </button>
              <button
                className="refresh-button"
                type="button"
                aria-label="Refresh logs"
                onClick={() => dispatch({ type: 'refresh' })}
              >
                <Activity size={15} />
              </button>
            </div>
          </div>

          <ResultsTable
            logs={explorer.logs}
            totalLogs={explorer.totalCount}
            page={explorer.page}
            pageSize={LOG_PAGE_SIZE}
            loading={explorer.loading}
            error={explorer.error}
            onRetry={() => dispatch({ type: 'refresh' })}
            onPageChange={(page) => dispatch({ type: 'page-changed', page })}
          />
          <footer className="workspace-footer">
            <span><Database size={12} /> Results from the backend API</span>
            <span><Clock3 size={12} /> {timeRangeLabel} <ChevronDown size={11} /></span>
          </footer>
        </div>
      </main>
    </div>
  )
}
