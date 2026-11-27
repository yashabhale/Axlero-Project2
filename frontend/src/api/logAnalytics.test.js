import assert from 'node:assert/strict'
import { test } from 'node:test'
import { calculateLogAnalytics, getLogAnalyticsDisplay } from './logAnalytics.js'

test('keeps the filtered all-pages count separate from current-page metrics', () => {
  const analytics = calculateLogAnalytics([
    { level: 'ERROR', duration: 25 },
    { level: 'INFO', duration: 75 },
  ], 120)

  assert.deepEqual(analytics, {
    matchingLogCount: 120,
    pageLogCount: 2,
    pageErrorRatePercent: 50,
    pageAverageResponseTimeMs: 50,
  })
})

test('returns empty-page values without inventing page rates or averages', () => {
  const analytics = calculateLogAnalytics([], 0)

  assert.deepEqual(analytics, {
    matchingLogCount: 0,
    pageLogCount: 0,
    pageErrorRatePercent: null,
    pageAverageResponseTimeMs: null,
  })
})

test('marks metrics unavailable when API values are malformed', () => {
  const analytics = calculateLogAnalytics([
    null,
    { level: 'ERROR', duration: 20 },
    { level: 'TRACE', duration: Number.NaN },
  ], Number.MAX_SAFE_INTEGER + 1)

  assert.deepEqual(analytics, {
    matchingLogCount: null,
    pageLogCount: 3,
    pageErrorRatePercent: null,
    pageAverageResponseTimeMs: null,
  })
})

test('keeps other analytics when a record is missing its duration', () => {
  const analytics = calculateLogAnalytics([
    { level: 'ERROR' },
  ], 10)

  assert.deepEqual(analytics, {
    matchingLogCount: 10,
    pageLogCount: 1,
    pageErrorRatePercent: 100,
    pageAverageResponseTimeMs: null,
  })
})

test('keeps other analytics when a record is missing its level', () => {
  const analytics = calculateLogAnalytics([
    { duration: 40 },
  ], 10)

  assert.deepEqual(analytics, {
    matchingLogCount: 10,
    pageLogCount: 1,
    pageErrorRatePercent: null,
    pageAverageResponseTimeMs: 40,
  })
})

test('handles a non-array log response safely', () => {
  const analytics = calculateLogAnalytics(null, 10)

  assert.deepEqual(analytics, {
    matchingLogCount: 10,
    pageLogCount: null,
    pageErrorRatePercent: null,
    pageAverageResponseTimeMs: null,
  })
})

test('shows loading and API error states instead of stale analytics', () => {
  const analytics = calculateLogAnalytics([{ level: 'ERROR', duration: 10 }], 1)

  assert.deepEqual(getLogAnalyticsDisplay(analytics, { loading: true, error: null }), {
    matchingLogCount: 'Loading…',
    pageLogCount: 'Loading…',
    pageErrorRate: 'Loading…',
    pageAverageResponseTime: 'Loading…',
    matchingLogDetail: 'Loading backend data',
    pageDetail: 'Loading backend data',
  })

  assert.deepEqual(getLogAnalyticsDisplay(analytics, { loading: false, error: 'API failed' }), {
    matchingLogCount: 'Unavailable',
    pageLogCount: 'Unavailable',
    pageErrorRate: 'Unavailable',
    pageAverageResponseTime: 'Unavailable',
    matchingLogDetail: 'Unavailable · API error',
    pageDetail: 'Unavailable · API error',
  })
})

test('labels empty results without presenting page metrics as zero rates', () => {
  const analytics = calculateLogAnalytics([], 0)
  const display = getLogAnalyticsDisplay(analytics, { loading: false, error: null })

  assert.equal(display.matchingLogCount, '0')
  assert.equal(display.pageLogCount, '0')
  assert.equal(display.pageErrorRate, 'Unavailable')
  assert.equal(display.pageAverageResponseTime, 'Unavailable')
  assert.equal(display.matchingLogDetail, 'All pages · active filters and time range')
  assert.equal(display.pageDetail, 'No logs on this page')
})
