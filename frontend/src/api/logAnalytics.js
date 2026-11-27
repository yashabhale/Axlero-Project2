const LOG_LEVELS = new Set(['INFO', 'WARN', 'ERROR', 'DEBUG'])

export function calculateLogAnalytics(logs, totalCount) {
  const records = Array.isArray(logs) ? logs : null
  const entries = records ?? []
  const recordsAreObjects = entries.every(
    (record) => record !== null && typeof record === 'object' && !Array.isArray(record),
  )
  const levelsAreValid = recordsAreObjects
    && entries.length > 0
    && entries.every((record) => LOG_LEVELS.has(record.level))
  const durationsAreValid = recordsAreObjects
    && entries.length > 0
    && entries.every(
      (record) => Number.isFinite(record.duration) && record.duration >= 0,
    )
  const errorCount = levelsAreValid
    ? entries.filter((record) => record.level === 'ERROR').length
    : 0
  const averageResponseTime = durationsAreValid
    ? entries.reduce(
      (average, record, index) => average + (record.duration - average) / (index + 1),
      0,
    )
    : null

  return {
    matchingLogCount: Number.isSafeInteger(totalCount) && totalCount >= 0 ? totalCount : null,
    pageLogCount: records ? records.length : null,
    pageErrorRatePercent: levelsAreValid ? (errorCount / entries.length) * 100 : null,
    pageAverageResponseTimeMs: Number.isFinite(averageResponseTime) ? averageResponseTime : null,
  }
}

export function getLogAnalyticsDisplay(analytics, { loading, error }) {
  const state = loading ? 'loading' : error ? 'error' : 'ready'
  const formatCount = (value) => value === null ? 'Unavailable' : value.toLocaleString()
  const formatMetric = (value, formatter) => value === null ? 'Unavailable' : formatter(value)
  const pageDetail = state === 'loading'
    ? 'Loading backend data'
    : state === 'error'
      ? 'Unavailable · API error'
      : analytics.pageLogCount === 0
        ? 'No logs on this page'
        : 'Current page'

  if (state !== 'ready') {
    const value = state === 'loading' ? 'Loading…' : 'Unavailable'
    return {
      matchingLogCount: value,
      pageLogCount: value,
      pageErrorRate: value,
      pageAverageResponseTime: value,
      matchingLogDetail: pageDetail,
      pageDetail,
    }
  }

  return {
    matchingLogCount: formatCount(analytics.matchingLogCount),
    pageLogCount: formatCount(analytics.pageLogCount),
    pageErrorRate: formatMetric(analytics.pageErrorRatePercent, (value) => `${value.toFixed(1)}%`),
    pageAverageResponseTime: formatMetric(
      analytics.pageAverageResponseTimeMs,
      (value) => `${value.toLocaleString(undefined, { maximumFractionDigits: 1 })} ms`,
    ),
    matchingLogDetail: 'All pages · active filters and time range',
    pageDetail,
  }
}
