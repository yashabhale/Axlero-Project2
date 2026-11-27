export const LOG_PAGE_SIZE = 50

export function createInitialLogExplorerState() {
  return {
    filters: {},
    rangeHours: 1,
    page: 0,
    refreshToken: 0,
    logs: [],
    totalCount: 0,
    loading: true,
    error: null,
    requestId: 0,
  }
}

export function logExplorerReducer(state, action) {
  switch (action.type) {
    case 'filters-changed': {
      const unchanged = state.filters.level === action.filters.level
        && state.filters.service === action.filters.service
      if (unchanged) return state
      return {
        ...state,
        filters: action.filters,
        page: 0,
        logs: [],
        totalCount: 0,
        loading: true,
        error: null,
      }
    }
    case 'filters-cleared':
      if (Object.keys(state.filters).length === 0 && state.rangeHours === 1 && state.page === 0) {
        return state
      }
      return {
        ...state,
        filters: {},
        rangeHours: 1,
        page: 0,
        logs: [],
        totalCount: 0,
        loading: true,
        error: null,
      }
    case 'range-changed':
      if (state.rangeHours === action.rangeHours) return state
      return {
        ...state,
        rangeHours: action.rangeHours,
        page: 0,
        logs: [],
        totalCount: 0,
        loading: true,
        error: null,
      }
    case 'page-changed': {
      const pageCount = Math.ceil(state.totalCount / LOG_PAGE_SIZE)
      const nextPage = Math.max(0, Math.min(action.page, Math.max(0, pageCount - 1)))
      if (state.page === nextPage) return state
      return {
        ...state,
        page: nextPage,
        logs: [],
        loading: true,
        error: null,
      }
    }
    case 'refresh':
      return {
        ...state,
        refreshToken: state.refreshToken + 1,
        logs: [],
        loading: true,
        error: null,
      }
    case 'request-started':
      if (action.requestId < state.requestId) return state
      return { ...state, requestId: action.requestId, loading: true, error: null }
    case 'request-succeeded': {
      if (action.requestId !== state.requestId) return state

      const lastPage = Math.max(0, Math.ceil(action.result.totalCount / LOG_PAGE_SIZE) - 1)
      if (state.page > lastPage) {
        return {
          ...state,
          page: lastPage,
          logs: [],
          totalCount: action.result.totalCount,
          loading: true,
          error: null,
        }
      }

      return {
        ...state,
        logs: action.result.logs,
        totalCount: action.result.totalCount,
        loading: false,
        error: null,
      }
    }
    case 'request-failed':
      if (action.requestId !== state.requestId) return state
      return {
        ...state,
        logs: [],
        totalCount: 0,
        loading: false,
        error: action.error,
      }
    default:
      return state
  }
}
