import { useState } from 'react'
import { Clock3, Database, Play, RotateCcw, Search, Sparkles, Loader2 } from 'lucide-react'

const suggestions = [
  { label: 'Errors', query: 'level:ERROR' },
  { label: 'Billing API', query: 'service:billing-api' },
  { label: 'Slow requests', query: 'response_time:>1000' },
]

const timeRanges = [
  { label: 'Last 15 minutes', ms: 15 * 60 * 1000 },
  { label: 'Last 1 hour', ms: 60 * 60 * 1000 },
  { label: 'Last 6 hours', ms: 6 * 60 * 60 * 1000 },
  { label: 'Last 24 hours', ms: 24 * 60 * 60 * 1000 },
  { label: 'Last 7 days', ms: 7 * 24 * 60 * 60 * 1000 },
]

export default function SearchPanel({ query, onQueryChange, onSearch, timeRange, onTimeRangeChange, loading }) {
  const [range, setRange] = useState('Last 1 hour')
  const [showTimePicker, setShowTimePicker] = useState(false)

  function handleSubmit(event) {
    event.preventDefault()
    onSearch()
  }

  function handleTimeRangeSelect(ms) {
    const end = Date.now()
    const start = end - ms
    onTimeRangeChange({ start, end })
    setShowTimePicker(false)
  }

  function formatRangeLabel(start, end) {
    const diff = end - start
    const hours = diff / (60 * 60 * 1000)
    if (hours < 1) return `${diff / (60 * 1000)}m`
    if (hours < 24) return `${hours}h`
    return `${hours / 24}d`
  }

  return (
    <section className="search-section" aria-label="Log search">
      <div className="search-heading">
        <div>
          <div className="eyebrow"><span className="eyebrow-line" /> LOG ANALYTICS</div>
          <h1>Find the signal<span>.</span></h1>
          <p>Search, filter, and explore logs from across your stack.</p>
        </div>
        <button className="saved-query-button" type="button">
          <Database size={15} />
          <span>Saved queries</span>
          <span className="saved-query-number">4</span>
        </button>
      </div>

      <form className="query-card" onSubmit={handleSubmit}>
        <div className="query-toolbar">
          <div className="query-mode"><Search size={14} /><span>Query</span><span className="query-mode-divider" /><span className="query-language">LQL</span></div>
          <div className="relative">
            <button
              className="time-range-button"
              type="button"
              onClick={() => setShowTimePicker(!showTimePicker)}
            >
              <Clock3 size={14} />
              <span>{range}</span>
              <span className="time-range-chevron">⌄</span>
            </button>
            {showTimePicker && (
              <div className="time-range-dropdown">
                {timeRanges.map((tr) => (
                  <button
                    key={tr.label}
                    type="button"
                    className="time-range-option"
                    onClick={() => { setRange(tr.label); handleTimeRangeSelect(tr.ms); }}
                  >
                    {tr.label}
                  </button>
                ))}
                <div className="time-range-divider" />
                <button type="button" className="time-range-option">
                  Custom range...
                </button>
              </div>
            )}
          </div>
        </div>
        <div className="query-input-row">
          <Sparkles className="query-sparkle" size={17} />
          <label className="sr-only" htmlFor="log-query">Search log query</label>
          <input
            id="log-query"
            autoComplete="off"
            spellCheck="false"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="Try  level:ERROR AND service:billing-api"
            disabled={loading}
          />
          <kbd className="query-shortcut">⌘ ↵</kbd>
          <button className="run-query-button" type="submit" disabled={loading}>
            {loading ? <Loader2 className="w-4 h-4 animate-spin" fill="currentColor" /> : <Play size={14} fill="currentColor" />}
            {loading ? ' Searching...' : ' Run query'}
          </button>
        </div>
        <div className="query-footer">
          <div className="quick-filters">
            <span className="quick-label">QUICK FILTERS</span>
            {suggestions.map((suggestion) => (
              <button
                className="filter-chip"
                key={suggestion.label}
                onClick={() => {
                  onQueryChange(suggestion.query)
                  onSearch()
                }}
                type="button"
                disabled={loading}
              >
                <span className={`filter-chip-dot ${suggestion.label === 'Errors' ? 'dot-error' : suggestion.label === 'Billing API' ? 'dot-blue' : 'dot-amber'}`} />
                {suggestion.label}
              </button>
            ))}
          </div>
          <button className="clear-query" type="button" onClick={() => { onQueryChange(''); onSearch() }} disabled={loading}>
            <RotateCcw size={12} /> Clear
          </button>
        </div>
      </form>

      <div className="query-hint">
        <span className="hint-indicator" />
        <span>Press <kbd>⌘ K</kbd> to focus search</span>
        <span className="hint-separator">·</span>
        <span>Supports field filters, boolean operators, and full-text search</span>
        <button type="button" aria-label="Query help">Learn query syntax <span aria-hidden="true">↗</span></button>
      </div>
    </section>
  )
}
