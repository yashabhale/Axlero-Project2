import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import {
  buildLogApiUrl,
  fetchLogs,
  mapLogApiResponse,
  parseLogSearch,
} from './logApi.js'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
})

test('builds the API URL with only supported, supplied parameters', () => {
  const url = new URL(buildLogApiUrl({
    level: 'ERROR',
    service: 'billing + api &%?#東京',
    startTime: 100,
    endTime: 200,
    limit: 50,
    offset: 10,
    ignored: 'value',
  }, 'https://logs.example/root/'))

  assert.equal(url.origin, 'https://logs.example')
  assert.equal(url.pathname, '/root/api/logs')
  assert.deepEqual(Object.fromEntries(url.searchParams), {
    level: 'ERROR',
    service: 'billing + api &%?#東京',
    startTime: '100',
    endTime: '200',
    limit: '50',
    offset: '10',
  })
})

test('parses supported level and quoted service filters', () => {
  assert.deepEqual(
    parseLogSearch('level:error AND service:"billing api"'),
    { level: 'ERROR', service: 'billing api' },
  )
  assert.deepEqual(
    parseLogSearch('service:"billing + platform & api" AND level:warn'),
    { service: 'billing + platform & api', level: 'WARN' },
  )
  assert.deepEqual(parseLogSearch(''), {})
})

test('rejects unsupported search syntax and invalid levels', () => {
  assert.throws(() => parseLogSearch('response_time:>1000'), /Only level and service/)
  assert.throws(() => parseLogSearch('level:TRACE'), /Level must be/)
  assert.throws(() => parseLogSearch('service:api AND service:worker'), /Only one service/)
})

test('fetches and maps the backend response to table records', async () => {
  let requestUrl
  let requestOptions
  globalThis.fetch = async (url, options) => {
    requestUrl = url
    requestOptions = options
    return jsonResponse({
      logs: [{
        timestamp: 1_730_000_000_000,
        level: 'ERROR',
        service: 'billing-api',
        message: 'Payment provider failed',
        responseTimeMs: 2481,
        tags: { traceId: 'trace-1', host: 'billing-1' },
      }],
      limit: 50,
      offset: 0,
      totalCount: 1,
    })
  }

  const controller = new AbortController()
  const result = await fetchLogs({
    level: 'ERROR',
    service: 'billing-api',
    startTime: 1_729_999_000_000,
    endTime: 1_730_000_000_000,
    limit: 50,
    offset: 0,
  }, { signal: controller.signal })

  assert.match(requestUrl, /^\/api\/logs\?/)
  assert.equal(requestOptions.signal, controller.signal)
  assert.equal(requestOptions.headers.Accept, 'application/json')
  assert.deepEqual(result, {
    logs: [{
      id: '1730000000000-billing-api-0',
      timestamp: 1_730_000_000_000,
      level: 'ERROR',
      service: 'billing-api',
      message: 'Payment provider failed',
      traceId: 'trace-1',
      host: 'billing-1',
      duration: 2481,
    }],
    limit: 50,
    offset: 0,
    totalCount: 1,
  })
})

test('maps missing backend trace and host tags to explicit placeholders', () => {
  const result = mapLogApiResponse({
    logs: [{
      timestamp: 1_730_000_000_000,
      level: 'INFO',
      service: 'api',
      message: 'Started',
      responseTimeMs: 10,
      tags: {},
    }],
    limit: 50,
    offset: 0,
    totalCount: 1,
  })

  assert.equal(result.logs[0].traceId, 'Not provided')
  assert.equal(result.logs[0].host, 'Not provided')
})

test('maps an empty backend result without treating it as malformed', () => {
  const result = mapLogApiResponse({
    logs: [],
    limit: 50,
    offset: 100,
    totalCount: 0,
  })

  assert.deepEqual(result, {
    logs: [],
    limit: 50,
    offset: 100,
    totalCount: 0,
  })
})

test('rejects missing logs and invalid pagination metadata', () => {
  const invalidResponses = [
    { limit: 50, offset: 0, totalCount: 0 },
    { logs: [], offset: 0, totalCount: 0 },
    { logs: [], limit: 50, totalCount: 0 },
    { logs: [], limit: 50, offset: 0 },
    { logs: [], limit: 0, offset: 0, totalCount: 0 },
    { logs: [], limit: 1001, offset: 0, totalCount: 0 },
    { logs: [], limit: 50, offset: -1, totalCount: 0 },
    { logs: [], limit: 50, offset: 1.5, totalCount: 0 },
    { logs: [], limit: 50, offset: 0, totalCount: -1 },
    { logs: [], limit: 50, offset: 0, totalCount: 1.5 },
    { logs: [], limit: 50, offset: 0, totalCount: Number.MAX_SAFE_INTEGER + 1 },
    { logs: [], limit: 1.5, offset: 0, totalCount: 0 },
    { logs: [], limit: 50, offset: 2_147_483_648, totalCount: 0 },
    { logs: [{}, {}], limit: 1, offset: 0, totalCount: 2 },
    { logs: [{}], limit: 1, offset: 1, totalCount: 1 },
  ]

  for (const payload of invalidResponses) {
    assert.throws(() => mapLogApiResponse(payload), /invalid response/)
  }
})

test('rejects missing or malformed log fields with a safe error', () => {
  const validEntry = {
    timestamp: 1_730_000_000_000,
    level: 'INFO',
    service: 'api',
    message: 'Started',
    responseTimeMs: 10,
    tags: {},
  }
  const invalidEntries = [
    null,
    { ...validEntry, timestamp: undefined },
    { ...validEntry, level: undefined },
    { ...validEntry, service: undefined },
    { ...validEntry, service: '   ' },
    { ...validEntry, message: undefined },
    { ...validEntry, message: '' },
    { ...validEntry, responseTimeMs: undefined },
    { ...validEntry, responseTimeMs: -1 },
    { ...validEntry, tags: undefined },
    { ...validEntry, tags: [] },
    { ...validEntry, tags: { environment: 42 } },
  ]

  for (const entry of invalidEntries) {
    assert.throws(() => mapLogApiResponse({
      logs: [entry],
      limit: 50,
      offset: 0,
      totalCount: 1,
    }), /invalid log record/)
  }
})

test('rejects timestamp zero because the backend requires a positive timestamp', () => {
  assert.throws(() => mapLogApiResponse({
    logs: [{
      timestamp: 0,
      level: 'INFO',
      service: 'api',
      message: 'Started',
      responseTimeMs: 10,
      tags: {},
    }],
    limit: 50,
    offset: 0,
    totalCount: 1,
  }), /invalid log record/)
})

test('reports HTTP, invalid JSON, malformed response, and network failures', async (context) => {
  await context.test('HTTP 400 returns its validation message', async () => {
    globalThis.fetch = async () => jsonResponse({ error: 'Invalid level' }, 400)
    await assert.rejects(fetchLogs({}), /Invalid level/)
  })

  await context.test('HTTP 404 returns its not-found message', async () => {
    globalThis.fetch = async () => jsonResponse({ error: 'Not found' }, 404)
    await assert.rejects(fetchLogs({}), /Not found/)
  })

  await context.test('HTTP 500 hides internal response details', async () => {
    globalThis.fetch = async () => jsonResponse({
      error: 'java.lang.IllegalStateException: private database path C:\\private\\logs.db',
    }, 500)
    await assert.rejects(fetchLogs({}), (error) => {
      assert.equal(error.message, 'The log API encountered an internal error. Please try again.')
      assert.doesNotMatch(error.message, /IllegalStateException|private|logs\.db/)
      return true
    })
  })

  await context.test('HTTP 500 with invalid JSON hides parser details', async () => {
    globalThis.fetch = async () => ({
      ok: false,
      status: 500,
      json: async () => { throw new SyntaxError('private database details') },
    })
    await assert.rejects(fetchLogs({}), (error) => {
      assert.equal(error.message, 'The log API returned HTTP 500 with an invalid JSON response.')
      assert.doesNotMatch(error.message, /private database details/)
      return true
    })
  })

  await context.test('invalid JSON', async () => {
    globalThis.fetch = async () => ({
      ok: true,
      status: 200,
      json: async () => { throw new SyntaxError('invalid json') },
    })
    await assert.rejects(fetchLogs({}), /returned invalid JSON/)
  })

  await context.test('invalid response shape', async () => {
    globalThis.fetch = async () => jsonResponse({ logs: 'not-an-array' })
    await assert.rejects(fetchLogs({}), /invalid response/)
  })

  await context.test('network failure', async () => {
    globalThis.fetch = async () => { throw new TypeError('connection refused') }
    await assert.rejects(fetchLogs({}), /Unable to reach the log API/)
  })
})

test('preserves a fetch AbortError and forwards the cancellation signal', async () => {
  const controller = new AbortController()
  const abortError = new Error('request aborted')
  abortError.name = 'AbortError'
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.signal, controller.signal)
    controller.abort()
    throw abortError
  }

  await assert.rejects(fetchLogs({}, { signal: controller.signal }), (error) => error === abortError)
})

test('preserves abort errors while reading the response body', async () => {
  const abortError = new Error('request aborted')
  abortError.name = 'AbortError'
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    json: async () => { throw abortError },
  })

  await assert.rejects(fetchLogs({}), (error) => error === abortError)
})

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}
