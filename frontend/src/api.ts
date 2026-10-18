import { tokenProvider } from './session'

export const types = ['CLUSTER', 'NODE', 'NAMESPACE', 'POD'] as const
export type ResourceType = typeof types[number]
export type ResourceStatus = 'NORMAL' | 'WARNING' | 'ERROR'
export type Reference = { type: ResourceType; id: number; name: string }
export type Resource = Reference & { status: ResourceStatus; updatedAt: string; parent: Reference | null; hasChildren: boolean }
export type ResourceAttributes = Record<string, unknown> & {
  diagnosticSource?: string; nodeCount?: number; readyNodeCount?: number; podCount?: number; problemPodCount?: number
  errorPodCount?: number; notReadyPodCount?: number; ready?: boolean; memoryPressure?: boolean; diskPressure?: boolean
  cpuCapacityCores?: number; memoryCapacityMiB?: number; conditionReason?: string
  phase?: string; containerCount?: number; readyContainerCount?: number; containerState?: string
  failureReason?: string | null; lastTerminationReason?: string | null; image?: string; workloadKind?: string; workloadName?: string
  cpuAllocatableCores?: number; cpuRequestedCores?: number; cpuUsageCores?: number
  memoryAllocatableMiB?: number; memoryRequestedMiB?: number; memoryUsageMiB?: number
  gpuDevices?: { name: string; model: string; memoryMiB: number; utilizationPercent: number; temperatureCelsius: number; allocatedPods: number; updatedAt: string }[]
  containers?: { name: string; image: string; state: string; ready: boolean; restartCount: number; cpuRequestMillicores: number; cpuUsageMillicores: number; memoryRequestMiB: number; memoryUsageMiB: number }[]
}
export type Detail = { resource: Resource; attributes: ResourceAttributes; path: Reference[] }
export type Page<T> = { items: T[]; nextCursor: string | null; hasMore: boolean }
export type SearchResult = { resource: Resource; path: Reference[] }
export type Summary = { clusters: number; nodes: number; namespaces: number; pods: number }
export type AuthenticationConfiguration = { enabled: boolean; issuer: string; clientId: string }
export type CurrentUser = { name: string; administrator: boolean; clusters: number[] }
export type ChangeEvent = { id: number; occurredAt: string; previousStatus: ResourceStatus; nextStatus: ResourceStatus; source: string; beforeAttributes: Record<string, unknown>; afterAttributes: Record<string, unknown> }
export type EventPage = { items: ChangeEvent[]; nextBeforeId: number | null; hasMore: boolean; retentionHours: number; maximumEvents: number }
export type KubernetesEvent = { id: number; type: 'NORMAL' | 'WARNING'; reason: string; message: string; firstSeenAt: string; lastSeenAt: string; occurrenceCount: number }
export type Operations = { available: boolean; source: string; fetchedAt: string; values: Record<string, number | null>; series: Record<string, { timestamp: number; value: number | null }[]>; message: string }
export type AlertRule = { name: string; title: string; severity: 'critical' | 'warning'; state: 'inactive' | 'pending' | 'firing'; activeAt: string | null; value: number | null; condition: string; response: string }
export type AlertSnapshot = { available: boolean; source: string; fetchedAt: string; rules: AlertRule[]; message: string }
export type AlertEmail = { available: boolean; recipient: string; enabled: boolean; smtpConfigured: boolean }
export type Scope = { type: ResourceType | ''; parentId?: number; clusterId?: number; namespaceId?: number; q: string; status: ResourceStatus | '' }
export const REQUEST_TIMEOUT_MS = 10000
export const keyOf = (r: Reference) => `${r.type}:${r.id}`
export const childType = (type: ResourceType): ResourceType | undefined => types[types.indexOf(type) + 1]
export class ApiError extends Error {
  constructor(public status: number) {
    super(status === 401 ? '로그인이 만료되었거나 필요합니다. 다시 로그인해 주세요.'
      : status === 403 ? '이 기능에 접근할 권한이 없습니다.'
      : status === 400 ? '조회 조건이 올바르지 않습니다. 조건을 초기화해 주세요.'
      : status === 404 ? '리소스가 삭제되었거나 존재하지 않습니다.'
      : status === 503 ? 'DB 조회가 지연되거나 서버가 준비되지 않았습니다. 다시 시도해 주세요.'
      : '데이터를 불러오지 못했습니다. 서버 연결을 확인해 주세요.')
  }
}
export class RequestTimeoutError extends Error {
  constructor() { super('서버 응답이 10초를 넘었습니다. 연결을 확인하고 다시 시도해 주세요.') }
}
async function get<T>(path: string, signal?: AbortSignal, base = '/api/resources', body?: unknown): Promise<T> {
  const controller = new AbortController()
  let timedOut = false
  const cancel = () => controller.abort(signal?.reason)
  if (signal?.aborted) cancel()
  else signal?.addEventListener('abort', cancel, { once: true })
  const timeout = setTimeout(() => { timedOut = true; controller.abort() }, REQUEST_TIMEOUT_MS)
  try {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const provider = tokenProvider()
    if (provider) {
      const token = await new Promise<string>((resolve, reject) => {
        const abort = () => reject(controller.signal.reason)
        if (controller.signal.aborted) { abort(); return }
        controller.signal.addEventListener('abort', abort, { once: true })
        provider().then(resolve, reject).finally(() => controller.signal.removeEventListener('abort', abort))
      })
      headers.Authorization = `Bearer ${token}`
    }
    controller.signal.throwIfAborted()
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(`${base}${path}`, { signal: controller.signal, headers,
      ...(body === undefined ? {} : { method: 'PUT', body: JSON.stringify(body) }) })
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
  authConfig: () => get<AuthenticationConfiguration>('/config', undefined, '/api/auth'),
  me: () => get<CurrentUser>('/me', undefined, '/api/auth'),
  operations: (signal?: AbortSignal) => get<Operations>('/metrics', signal, '/api/operations'),
  alerts: (signal?: AbortSignal) => get<AlertSnapshot>('/alerts', signal, '/api/operations'),
  alertEmail: (signal?: AbortSignal) => get<AlertEmail>('/alert-email', signal, '/api/operations'),
  saveAlertEmail: (recipient: string, enabled: boolean, signal?: AbortSignal) =>
    get<AlertEmail>('/alert-email', signal, '/api/operations', { recipient, enabled }),
  events: (resource: Reference, beforeId?: number | null, signal?: AbortSignal) => get<EventPage>(`/${resource.type}/${resource.id}/events?limit=20${beforeId ? `&beforeId=${beforeId}` : ''}`, signal),
  kubernetesEvents: (resource: Reference, signal?: AbortSignal) => get<KubernetesEvent[]>(`/${resource.type}/${resource.id}/kubernetes-events?limit=20`, signal),
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
    if (scope.clusterId !== undefined) params.set('clusterId', String(scope.clusterId))
    if (scope.namespaceId !== undefined) params.set('namespaceId', String(scope.namespaceId))
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
