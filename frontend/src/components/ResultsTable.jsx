import { ChevronDown, ChevronRight, Copy, Download, Ellipsis, ExternalLink, ListFilter } from 'lucide-react'
import { useState } from 'react'

function formatTimestamp(timestamp) {
  const date = new Date(timestamp)
  return new Intl.DateTimeFormat('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

function formatRelativeTime(timestamp) {
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(timestamp).getTime()) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  return `${Math.floor(seconds / 60)}m ago`
}

function LogRow({ log, expanded, onToggle }) {
  const [copied, setCopied] = useState(false)

  async function copyMessage() {
    try {
      await navigator.clipboard.writeText(log.message)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch (error) {
      console.error('Unable to copy log message', error)
    }
  }

  return (
    <>
      <tr className={`log-row${expanded ? ' log-row-expanded' : ''}`} onClick={onToggle}>
        <td className="expand-cell">
          <button type="button" className="row-expand-button" aria-label={expanded ? 'Collapse log details' : 'Expand log details'}>
            {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          </button>
        </td>
        <td className="timestamp-cell">
          <span>{formatTimestamp(log.timestamp)}</span>
          <span className="timestamp-relative">{formatRelativeTime(log.timestamp)}</span>
        </td>
        <td><span className={`level-pill level-${log.level.toLowerCase()}`}><span />{log.level}</span></td>
        <td><span className="service-name">{log.service}</span></td>
        <td className="message-cell"><span>{log.message}</span></td>
        <td className="row-actions-cell">
          <button
            type="button"
            className="row-action-button"
            aria-label="Copy log message"
            title={copied ? 'Copied' : 'Copy message'}
            onClick={(event) => { event.stopPropagation(); void copyMessage() }}
          >
            <Copy size={13} />
          </button>
          <button type="button" className="row-action-button" aria-label="More log actions" onClick={(event) => event.stopPropagation()}>
            <Ellipsis size={15} />
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="detail-row">
          <td />
          <td colSpan="5">
            <div className="log-details">
              <div className="detail-message">
                <span className="detail-label">FULL MESSAGE</span>
                <span>{log.message}</span>
              </div>
              <div className="detail-meta">
                <div><span className="detail-label">TRACE ID</span><code>{log.traceId}</code></div>
                <div><span className="detail-label">HOST</span><code>{log.host}</code></div>
                <div><span className="detail-label">RESPONSE TIME</span><code>{log.duration} ms</code></div>
                <button type="button" className="trace-link">View trace <ExternalLink size={12} /></button>
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  )
}

export default function ResultsTable({ logs, totalLogs, onLoadMore }) {
  const [expandedId, setExpandedId] = useState(null)

  function exportLogs() {
    const blob = new Blob([JSON.stringify(logs, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'logstream-results.json'
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <section className="results-section">
      <div className="results-heading">
        <div className="results-title-group">
          <h2>Log events</h2>
          <span className="results-count">{logs.length.toLocaleString()} <span>of</span> {totalLogs.toLocaleString()}</span>
          <span className="results-live"><span /> LIVE</span>
        </div>
        <div className="results-actions">
          <span className="results-refresh">Updated just now</span>
          <button className="table-tool-button" type="button"><ListFilter size={14} /><span>Columns</span><ChevronDown size={12} /></button>
          <button className="table-tool-button export-button" type="button" onClick={exportLogs}><Download size={14} /><span>Export</span></button>
        </div>
      </div>

      <div className="table-container">
        <table className="logs-table">
          <thead>
            <tr>
              <th className="expand-cell"><span className="sr-only">Expand</span></th>
              <th className="timestamp-heading">TIMESTAMP <span>↓</span></th>
              <th>LEVEL</th>
              <th>SERVICE</th>
              <th>MESSAGE</th>
              <th className="row-actions-cell" />
            </tr>
          </thead>
          <tbody>
            {logs.map((log) => (
              <LogRow
                key={log.id}
                log={log}
                expanded={expandedId === log.id}
                onToggle={() => setExpandedId((current) => current === log.id ? null : log.id)}
              />
            ))}
            {logs.length === 0 && (
              <tr>
                <td colSpan="6" className="empty-state">
                  <div className="empty-icon"><ListFilter size={20} /></div>
                  <strong>No logs match this query</strong>
                  <span>Try adjusting your search or clearing the active filters.</span>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {logs.length > 0 && (
        <div className="table-footer">
          <span>Showing <strong>{logs.length}</strong> of <strong>{totalLogs.toLocaleString()}</strong> events</span>
          <div className="pagination">
            <button type="button" disabled aria-label="Previous page">‹</button>
            <span className="page-current">1</span>
            <button type="button" disabled aria-label="Next page">›</button>
            <span className="page-size">Rows per page: <strong>50</strong><ChevronDown size={12} /></span>
          </div>
          <button className="load-more-button" type="button" onClick={onLoadMore}>Load more events <span>↓</span></button>
        </div>
      )}
    </section>
  )
}
