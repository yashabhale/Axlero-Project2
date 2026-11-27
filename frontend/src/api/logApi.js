const LOG_LEVELS = new Set(['INFO', 'WARN', 'ERROR', 'DEBUG'])
const MAX_LOG_LIMIT = 1_000
const MAX_LOG_OFFSET = 2_147_483_647

const configuredBaseUrl =
  typeof import.meta.env?.VITE_API_BASE_URL === 'string'
    ? import.meta.env.VITE_API_BASE_URL
    : ''

export function buildLogApiUrl(filters = {}, baseUrl = configuredBaseUrl) {
  const searchParams = new URLSearchParams()
  for (const name of ['level', 'service', 'startTime', 'endTime', 'limit', 'offset']) {
    const value = filters[name]
    if (value !== undefined && value !== null) {
      searchParams.set(name, String(value))
    }
  }

  const endpoint = `${baseUrl.replace(/\/+$/, '')}/api/logs`
  const query = searchParams.toString()
  return query ? `${endpoint}?${query}` : endpoint
}

export function parseLogSearch(query) {
  const terms = query.match(/(?:level|service):(?:"[^"]*"|'[^']*'|\S+)|\S+/gi) ?? []
  const filters = {}

  for (const term of terms) {
    if (/^AND$/i.test(term)) {
      continue
    }

    const match = term.match(/^(level|service):(?:"([^"]*)"|'([^']*)'|(.+))$/i)
    if (!match) {
      throw new Error('Only level and service filters are supported by the log API.')
    }

    const field = match[1].toLowerCase()
    const value = (match[2] ?? match[3] ?? match[4]).trim()
    if (!value) {
      throw new Error(`${field} filter must not be empty.`)
    }
    if (Object.hasOwn(filters, field)) {
      throw new Error(`Only one ${field} filter can be used at a time.`)
    }

    if (field === 'level') {
      const level = value.toUpperCase()
      if (!LOG_LEVELS.has(level)) {
        throw new Error('Level must be INFO, WARN, ERROR, or DEBUG.')
      }
      filters.level = level
    } else {
      filters.service = value
    }
  }

  return filters
}

export function mapLogApiResponse(payload) {
  if (
    !payload
    || !Array.isArray(payload.logs)
    || !Number.isInteger(payload.limit)
    || payload.limit <= 0
    || payload.limit > MAX_LOG_LIMIT
    || !Number.isInteger(payload.offset)
    || payload.offset < 0
    || payload.offset > MAX_LOG_OFFSET
    || !Number.isSafeInteger(payload.totalCount)
    || payload.totalCount < 0
    || payload.logs.length > payload.limit
    || (payload.logs.length > 0 && payload.offset + payload.logs.length > payload.totalCount)
  ) {
    throw new Error('The log API returned an invalid response.')
  }

  const logs = payload.logs.map((entry, index) => {
    const tags = entry?.tags
    const timestamp = entry?.timestamp
    const date = new Date(timestamp)
    if (
      !Number.isFinite(timestamp)
      || timestamp <= 0
      || !Number.isFinite(date.getTime())
      || !LOG_LEVELS.has(entry.level)
      || typeof entry.service !== 'string'
      || !entry.service.trim()
      || typeof entry.message !== 'string'
      || !entry.message.trim()
      || !Number.isFinite(entry.responseTimeMs)
      || entry.responseTimeMs < 0
      || !tags
      || typeof tags !== 'object'
      || Array.isArray(tags)
      || !Object.values(tags).every((value) => typeof value === 'string')
    ) {
      throw new Error('The log API returned an invalid log record.')
    }

    return {
      id: `${timestamp}-${entry.service}-${payload.offset + index}`,
      timestamp,
      level: entry.level,
      service: entry.service,
      message: entry.message,
      traceId: tags.traceId || 'Not provided',
      host: tags.host || 'Not provided',
      duration: entry.responseTimeMs,
    }
  })

  return {
    logs,
    limit: payload.limit,
    offset: payload.offset,
    totalCount: payload.totalCount,
  }
}

export async function fetchLogs(filters, { signal } = {}) {
  const url = buildLogApiUrl(filters)
  let response
  try {
    response = await fetch(url, {
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw error
    }
    throw new Error('Unable to reach the log API. Check the backend connection.')
  }

  let payload
  try {
    payload = await response.json()
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw error
    }
    const message = response.ok
      ? 'The log API returned invalid JSON.'
      : `The log API returned HTTP ${response.status} with an invalid JSON response.`
    throw new Error(message)
  }

  if (!response.ok) {
    if (response.status >= 500) {
      throw new Error('The log API encountered an internal error. Please try again.')
    }

    const message = typeof payload?.error === 'string'
      && payload.error.trim()
      ? payload.error
      : `The log API request failed with HTTP ${response.status}.`
    throw new Error(message)
  }

  return mapLogApiResponse(payload)
}
