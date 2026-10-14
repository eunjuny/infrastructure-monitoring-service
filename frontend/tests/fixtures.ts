import type { Resource, ResourceType, SearchResult, Page } from '../src/api'
export function resource(id = 1, type: ResourceType = 'POD'): Resource {
  return { type, id, name: `${type.toLowerCase()}-${id}`, status: 'NORMAL', updatedAt: '2026-10-13T00:00:00Z',
    parent: type === 'CLUSTER' ? null : { type: type === 'NODE' ? 'CLUSTER' : type === 'NAMESPACE' ? 'NODE' : 'NAMESPACE', id: 1, name: 'parent-1' }, hasChildren: type !== 'POD' }
}
export const page = <T>(items: T[], nextCursor: string | null = null): Page<T> => ({ items, hasMore: Boolean(nextCursor), nextCursor })
export const result = (id = 1): SearchResult => ({ resource: resource(id), path: [] })
export const deferred = <T>() => {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
