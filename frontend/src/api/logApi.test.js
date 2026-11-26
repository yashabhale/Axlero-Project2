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
    service: 'billing api',
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
    service: 'billing api',
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

test('reports HTTP, invalid JSON, malformed response, and network failures', async (context) => {
  await context.test('HTTP error', async () => {
    globalThis.fetch = async () => jsonResponse({ error: 'Invalid level' }, 400)
    await assert.rejects(fetchLogs({}), /Invalid level/)
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

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}
