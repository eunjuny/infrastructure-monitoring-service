import { describe, expect, it, vi } from 'vitest'
import { api, ApiError, type Page, type SearchResult } from '../src/api'
import { useResourceList } from '../src/useResourceList'
import { page, result, deferred } from './fixtures'
const scope = { type: 'POD' as const, q: '', status: '' as const }
describe('bounded cursor window', () => {
  it('reuses unchanged row objects during refresh', async () => {
    vi.spyOn(api, 'list').mockImplementation(async () => page([result(1), result(2)]))
    const list = useResourceList(); await list.reset(scope)
    const before = [...list.rows.value]; await list.refresh()
    expect(list.rows.value[0]).toBe(before[0]); expect(list.rows.value[1]).toBe(before[1])
  })
  it('retains at most five 100-row pages and advances the discarded offset', async () => {
    const spy = vi.spyOn(api, 'list').mockImplementation(async (_, cursor) => {
      const start = cursor ? Number(cursor) : 0
      return page(Array.from({ length: 100 }, (_, i) => result(start + i + 1)), String(start + 100))
    })
    const list = useResourceList(); await list.reset(scope)
    for (let i = 0; i < 20; i++) await list.next()
    expect(spy).toHaveBeenCalledTimes(21)
    expect(list.rows.value).toHaveLength(500)
    expect(list.offset.value).toBe(1600)
    expect(list.rows.value[0]!.resource.id).toBe(1601)
    list.stop()
  })
  it('ignores late responses and aborts obsolete requests', async () => {
    const old = deferred<Page<SearchResult>>()
    const spy = vi.spyOn(api, 'list').mockReturnValueOnce(old.promise).mockResolvedValueOnce(page([result(2)]))
    const list = useResourceList(), first = list.reset(scope)
    await list.reset({ ...scope, status: 'ERROR' })
    expect(spy.mock.calls[0]![2]!.aborted).toBe(true)
    old.resolve(page([result(1)], 'old')); await first
    expect(list.rows.value.map(item => item.resource.id)).toEqual([2])
    expect(list.hasMore.value).toBe(false)
  })
  it('prevents overlapping page loads and stops at the final page', async () => {
    const pending = deferred<Page<SearchResult>>()
    const spy = vi.spyOn(api, 'list').mockResolvedValueOnce(page([result()], 'next')).mockReturnValueOnce(pending.promise)
    const list = useResourceList(); await list.reset(scope)
    const next = list.next(); await list.next()
    expect(spy).toHaveBeenCalledTimes(2)
    pending.resolve(page([result(2)])); await next; await list.next()
    expect(spy).toHaveBeenCalledTimes(2); expect(list.hasMore.value).toBe(false)
  })
  it('retains rows until refresh completes and atomically updates status', async () => {
    const pending = deferred<Page<SearchResult>>()
    vi.spyOn(api, 'list').mockResolvedValueOnce(page([result()])).mockReturnValueOnce(pending.promise)
    const list = useResourceList(); await list.reset(scope)
    const before = list.rows.value, refreshing = list.refresh()
    expect(list.rows.value).toBe(before)
    pending.resolve(page([{ resource: { ...result().resource, status: 'ERROR' }, path: [] }]))
    await refreshing
    expect(list.rows.value[0]!.resource.status).toBe('ERROR')
  })
  it('keeps visible data on failed refresh and supports retry', async () => {
    vi.spyOn(api, 'list').mockResolvedValueOnce(page([result()])).mockRejectedValueOnce(new ApiError(503)).mockResolvedValueOnce(page([result(2)]))
    const list = useResourceList(); await list.reset(scope); await list.refresh()
    expect(list.rows.value[0]!.resource.id).toBe(1); expect(list.error.value).toContain('DB')
    await list.refresh(); expect(list.rows.value[0]!.resource.id).toBe(2); expect(list.error.value).toBe('')
  })
  it('clears rows and cursors on a changed scope or pending search', async () => {
    vi.spyOn(api, 'list').mockResolvedValue(page([result()], 'next'))
    const list = useResourceList(); await list.reset(scope); list.stop(true)
    expect(list.rows.value).toEqual([]); expect(list.hasMore.value).toBe(false)
    await list.reset({ ...scope, q: 'worker' }); expect(list.offset.value).toBe(0)
  })
  it('refreshes an evicted window from its own starting cursor', async () => {
    const spy = vi.spyOn(api, 'list').mockImplementation(async (_, cursor) => {
      const id = cursor ? Number(cursor) : 1; return page([result(id)], String(id + 1))
    })
    const list = useResourceList(); await list.reset(scope)
    for (let i = 0; i < 5; i++) await list.next()
    await list.refresh()
    expect(spy.mock.calls[6]![1]).toBe('2')
    expect(list.rows.value.map(row => row.resource.id)).toEqual([2, 3, 4, 5, 6])
  })
})
