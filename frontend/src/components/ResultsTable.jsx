import { ChevronDown, ChevronRight, Copy, Download, Ellipsis, ExternalLink, ListFilter } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'

const ReactECharts = lazy(() => import('./EChartsView.jsx'))

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

function LogVolumeChart({ logs }) {
  const [rangeMinutes, setRangeMinutes] = useState(60)
  const now = Date.now()
  const bucketMinutes = rangeMinutes === 15 ? 1 : rangeMinutes === 60 ? 5 : 30
  const bucketCount = rangeMinutes / bucketMinutes
  const rangeStart = now - rangeMinutes * 60_000
  const counts = Array(bucketCount).fill(0)

  logs.forEach((log) => {
    const timestamp = new Date(log.timestamp).getTime()
    if (!Number.isFinite(timestamp) || timestamp < rangeStart || timestamp > now) return
    const bucketIndex = Math.min(bucketCount - 1, Math.floor((timestamp - rangeStart) / (bucketMinutes * 60_000)))
    counts[bucketIndex] += 1
  })

  const categories = counts.map((_, index) => {
    const date = new Date(rangeStart + index * bucketMinutes * 60_000)
    return new Intl.DateTimeFormat('en-US', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
  })
  const totalInRange = counts.reduce((total, count) => total + count, 0)
  const option = {
    animationDuration: 250,
    grid: { left: 42, right: 12, top: 16, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: '#1b2029',
      borderColor: '#343b47',
      textStyle: { color: '#e1e5ed', fontSize: 11 },
      formatter: (items) => `${items[0].axisValue}<br/><strong>${items[0].value}</strong> logs`,
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#303743' } },
      axisLabel: {
        color: '#778190',
        fontSize: 9,
        interval: Math.max(0, Math.ceil(bucketCount / 6) - 1),
      },
    },
    yAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      splitNumber: 3,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: '#778190', fontSize: 9 },
      splitLine: { lineStyle: { color: '#242a34', type: 'dashed' } },
    },
    series: [{
      name: 'Logs',
      type: 'bar',
      data: counts,
      barMaxWidth: 24,
      showBackground: true,
      backgroundStyle: { color: '#1a2029', borderRadius: [3, 3, 0, 0] },
      itemStyle: { color: '#9b82f3', borderRadius: [3, 3, 0, 0] },
      emphasis: { itemStyle: { color: '#b5a3ff' } },
    }],
  }

  return (
    <section className="log-volume-card" aria-label="Log volume histogram">
      <div className="log-volume-heading">
        <div>
          <h2>Log volume over time</h2>
          <span>{totalInRange.toLocaleString()} loaded events in the last {rangeMinutes} minutes</span>
        </div>
        <div className="log-volume-ranges" role="group" aria-label="Histogram time range">
          {[15, 60, 360].map((minutes) => (
            <button
              key={minutes}
              type="button"
              className={rangeMinutes === minutes ? 'log-volume-range active' : 'log-volume-range'}
              aria-pressed={rangeMinutes === minutes}
              onClick={() => setRangeMinutes(minutes)}
            >
              {minutes === 15 ? '15m' : minutes === 60 ? '1h' : '6h'}
            </button>
          ))}
        </div>
      </div>
      <Suspense fallback={<div className="log-volume-chart-loading" aria-label="Loading histogram" />}>
        <div role="img" aria-label={`Histogram of ${totalInRange} loaded log events over the last ${rangeMinutes} minutes`}>
          <ReactECharts option={option} notMerge lazyUpdate style={{ height: 190, width: '100%' }} />
        </div>
      </Suspense>
    </section>
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
      <LogVolumeChart logs={logs} />
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
