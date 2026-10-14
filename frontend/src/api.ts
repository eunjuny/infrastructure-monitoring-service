export const types = ['CLUSTER', 'NODE', 'NAMESPACE', 'POD'] as const
export type ResourceType = typeof types[number]
export type ResourceStatus = 'NORMAL' | 'WARNING' | 'ERROR'
export type Reference = { type: ResourceType; id: number; name: string }
export type Resource = Reference & { status: ResourceStatus; updatedAt: string; parent: Reference | null; hasChildren: boolean }
export type Detail = { resource: Resource; attributes: Record<string, unknown>; path: Reference[] }
export type Page<T> = { items: T[]; nextCursor: string | null; hasMore: boolean }
export type SearchResult = { resource: Resource; path: Reference[] }
export type Summary = { clusters: number; nodes: number; namespaces: number; pods: number }
export type Scope = { type: ResourceType | ''; parentId?: number; q: string; status: ResourceStatus | '' }
export const REQUEST_TIMEOUT_MS = 10000
export const keyOf = (r: Reference) => `${r.type}:${r.id}`
export const childType = (type: ResourceType): ResourceType | undefined => types[types.indexOf(type) + 1]
export class ApiError extends Error {
  constructor(public status: number) {
    super(status === 400 ? '조회 조건이 올바르지 않습니다. 조건을 초기화해 주세요.'
      : status === 404 ? '리소스가 삭제되었거나 존재하지 않습니다.'
      : status === 503 ? 'DB 조회가 지연되거나 서버가 준비되지 않았습니다. 다시 시도해 주세요.'
      : '데이터를 불러오지 못했습니다. 서버 연결을 확인해 주세요.')
  }
}
export class RequestTimeoutError extends Error {
  constructor() { super('서버 응답이 10초를 넘었습니다. 연결을 확인하고 다시 시도해 주세요.') }
}
async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const cancel = () => controller.abort(signal?.reason)
  if (signal?.aborted) cancel()
  else signal?.addEventListener('abort', cancel, { once: true })
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(`/api/resources${path}`, { signal: controller.signal, headers: { Accept: 'application/json' } })
    if (!response.ok) throw new ApiError(response.status)
    return await response.json() as T
  } catch (cause) {
    if (timedOut && !signal?.aborted) throw new RequestTimeoutError()
    throw cause
  } finally {
    clearTimeout(timeout)
    signal?.removeEventListener('abort', cancel)
  }
}
export const api = {
  summary: (signal?: AbortSignal) => get<Summary>('/summary', signal),
  detail: (r: Reference, signal?: AbortSignal) => get<Detail>(`/${r.type}/${r.id}`, signal),
  children: (r: Reference | null, cursor?: string | null, signal?: AbortSignal) => {
    const params = new URLSearchParams({ limit: '50' })
    if (cursor) params.set('cursor', cursor)
    return get<Page<Resource>>(`${r ? `/${r.type}/${r.id}/children` : ''}?${params}`, signal)
  },
  list: async (scope: Scope, cursor?: string | null, signal?: AbortSignal): Promise<Page<SearchResult>> => {
    const params = new URLSearchParams({ limit: '100' })
    if (scope.type) params.set('type', scope.type)
    if (scope.status) params.set('status', scope.status)
    if (cursor) params.set('cursor', cursor)
    if (scope.q.trim()) {
      params.set('q', scope.q.trim())
      // Global search has no parentId condition; results carry full ancestor paths.
      return get<Page<SearchResult>>(`/search?${params}`, signal)
    }
    if (scope.parentId !== undefined) params.set('parentId', String(scope.parentId))
    const page = await get<Page<Resource>>(`?${params}`, signal)
    return { ...page, items: page.items.map(resource => ({ resource, path: [] })) }
  },
}
export const errorMessage = (error: unknown) => error instanceof ApiError || error instanceof RequestTimeoutError ? error.message : '서버 연결을 확인하고 다시 시도해 주세요.'
