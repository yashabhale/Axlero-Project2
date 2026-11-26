import { Clock3, Database, Play, RotateCcw, Search, Sparkles } from 'lucide-react'

const suggestions = [
  { label: 'Errors', query: 'level:ERROR' },
  { label: 'Billing API', query: 'service:billing-api' },
  { label: 'Auth service', query: 'service:auth-service' },
]

export default function SearchPanel({
  query,
  onQueryChange,
  onSearch,
  rangeHours,
  onRangeChange,
  validationError,
}) {
  function handleSubmit(event) {
    event.preventDefault()
    onSearch(query)
  }

  return (
    <section className="search-section" aria-label="Log search">
      <div className="search-heading">
        <div>
          <div className="eyebrow"><span className="eyebrow-line" /> LOG ANALYTICS</div>
          <h1>Find the signal<span>.</span></h1>
          <p>Search and explore logs returned by the backend.</p>
        </div>
        <button className="saved-query-button" type="button">
          <Database size={15} />
          <span>Saved queries (demo)</span>
        </button>
      </div>

      <form className="query-card" onSubmit={handleSubmit}>
        <div className="query-toolbar">
          <div className="query-mode"><Search size={14} /><span>Query</span><span className="query-mode-divider" /><span className="query-language">FILTERS</span></div>
          <button
            className="time-range-button"
            type="button"
            onClick={() => onRangeChange(rangeHours === 1 ? 24 : 1)}
          >
            <Clock3 size={14} />
            <span>Last {rangeHours} {rangeHours === 1 ? 'hour' : 'hours'}</span>
            <span className="time-range-chevron">⌄</span>
          </button>
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
          />
          <kbd className="query-shortcut">⌘ ↵</kbd>
          <button className="run-query-button" type="submit"><Play size={14} fill="currentColor" /> Run query</button>
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
                  onSearch(suggestion.query)
                }}
                type="button"
              >
                <span className={`filter-chip-dot ${suggestion.label === 'Errors' ? 'dot-error' : suggestion.label === 'Billing API' ? 'dot-blue' : 'dot-amber'}`} />
                {suggestion.label}
              </button>
            ))}
          </div>
          <button className="clear-query" type="button" onClick={() => { onQueryChange(''); onSearch('') }}>
            <RotateCcw size={12} /> Clear
          </button>
        </div>
      </form>
      {validationError && <p className="query-validation-error" role="alert">{validationError}</p>}

      <div className="query-hint">
        <span className="hint-indicator" />
        <span>Press <kbd>⌘ K</kbd> to focus search</span>
        <span className="hint-separator">·</span>
        <span>Supports level and service filters joined with AND</span>
        <button type="button" aria-label="Query help">Learn query syntax <span aria-hidden="true">↗</span></button>
      </div>
    </section>
  )
}
