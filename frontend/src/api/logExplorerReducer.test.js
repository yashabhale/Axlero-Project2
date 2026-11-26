import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  createInitialLogExplorerState,
  logExplorerReducer,
  LOG_PAGE_SIZE,
} from './logExplorerReducer.js'

test('tracks loading and empty success states', () => {
  let state = createInitialLogExplorerState()
  assert.equal(state.loading, true)

  state = logExplorerReducer(state, { type: 'request-started', requestId: 1 })
  state = logExplorerReducer(state, {
    type: 'request-succeeded',
    requestId: 1,
    result: { logs: [], totalCount: 0 },
  })

  assert.equal(state.loading, false)
  assert.equal(state.error, null)
  assert.deepEqual(state.logs, [])
  assert.equal(state.totalCount, 0)
})

test('filter and time-range changes reset pagination', () => {
  let state = createInitialLogExplorerState()
  state = { ...state, totalCount: LOG_PAGE_SIZE * 3, page: 2 }

  state = logExplorerReducer(state, {
    type: 'filters-changed',
    filters: { level: 'ERROR' },
  })
  assert.equal(state.page, 0)
  assert.equal(state.filters.level, 'ERROR')
  assert.equal(state.loading, true)

  state = { ...state, page: 1, totalCount: LOG_PAGE_SIZE * 2 }
  state = logExplorerReducer(state, { type: 'range-changed', rangeHours: 24 })
  assert.equal(state.page, 0)
  assert.equal(state.rangeHours, 24)
})

test('paginates within the available result pages', () => {
  let state = { ...createInitialLogExplorerState(), totalCount: LOG_PAGE_SIZE * 2 + 1 }
  state = logExplorerReducer(state, { type: 'page-changed', page: 1 })
  assert.equal(state.page, 1)

  state = logExplorerReducer(state, { type: 'page-changed', page: 99 })
  assert.equal(state.page, 2)

  state = logExplorerReducer(state, { type: 'page-changed', page: -1 })
  assert.equal(state.page, 0)
})

test('ignores stale requests after a newer request starts', () => {
  let state = createInitialLogExplorerState()
  state = logExplorerReducer(state, { type: 'request-started', requestId: 1 })
  state = logExplorerReducer(state, { type: 'request-started', requestId: 2 })

  const currentState = logExplorerReducer(state, {
    type: 'request-succeeded',
    requestId: 2,
    result: { logs: [{ id: 'new' }], totalCount: 1 },
  })
  const staleState = logExplorerReducer(currentState, {
    type: 'request-succeeded',
    requestId: 1,
    result: { logs: [{ id: 'stale' }], totalCount: 1 },
  })

  assert.strictEqual(staleState, currentState)
  assert.equal(staleState.logs[0].id, 'new')
})

test('stores request errors and clears prior results', () => {
  let state = {
    ...createInitialLogExplorerState(),
    logs: [{ id: 'previous' }],
    totalCount: 1,
    requestId: 3,
  }
  state = logExplorerReducer(state, {
    type: 'request-failed',
    requestId: 3,
    error: 'Backend unavailable',
  })

  assert.equal(state.loading, false)
  assert.equal(state.error, 'Backend unavailable')
  assert.deepEqual(state.logs, [])
  assert.equal(state.totalCount, 0)
})
