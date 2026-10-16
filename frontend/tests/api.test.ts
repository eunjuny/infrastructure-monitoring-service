import { describe, expect, it, vi } from 'vitest'
import { api, ApiError, RequestTimeoutError, REQUEST_TIMEOUT_MS } from '../src/api'
import { resource, page } from './fixtures'
function mock(body: unknown, status = 200) {
  const fetch = vi.fn().mockResolvedValue({ ok: status === 200, status, json: async () => body })
  vi.stubGlobal('fetch', fetch)
  return fetch
}
describe('API request contract', () => {
  it('uses typed cursor/parent/status parameters for browsing', async () => {
    const fetch = mock(page([resource(1)]))
    const value = await api.list({ type: 'POD', q: '', status: 'ERROR', parentId: 7 }, 'cursor+/=')
    const url = new URL(fetch.mock.calls[0]![0], 'https://test.invalid')
    expect(url.pathname).toBe('/api/resources')
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '100', type: 'POD', status: 'ERROR', cursor: 'cursor+/=', parentId: '7' })
    expect(value.items[0]!.resource.id).toBe(1)
  })
  it('omits parent and optional type for global search, preserves Unicode and wildcards as input', async () => {
    const fetch = mock(page([]))
    await api.list({ type: '', q: '  한글 %_!  ', status: '', parentId: 99 })
    const url = new URL(fetch.mock.calls[0]![0], 'https://test.invalid')
    expect(url.pathname).toBe('/api/resources/search')
    expect(Object.fromEntries(url.searchParams)).toEqual({ limit: '100', q: '한글 %_!' })
  })
  it('queries lazy child pages with limit 50 and forwards cancellation', async () => {
    const fetch = mock(page([])), controller = new AbortController()
    await api.children(resource(2, 'NODE'), null, controller.signal)
    expect(fetch).toHaveBeenCalledWith('/api/resources/NODE/2/children?limit=50', expect.objectContaining({ signal: expect.any(AbortSignal) }))
  })
  it('uses explicit summary/detail endpoints', async () => {
    const fetch = mock({})
    await api.summary(); await api.detail(resource(25))
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/resources/summary', '/api/resources/POD/25'])
  })
  it.each([400, 401, 403, 404, 503])('maps HTTP %i without leaking response bodies', async status => {
    mock({ detail: 'internal SQL secret' }, status)
    await expect(api.summary()).rejects.toBeInstanceOf(ApiError)
    await expect(api.summary()).rejects.not.toThrow('internal SQL secret')
  })
  it('aborts a stalled request after ten seconds and cleans its timer', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn().mockImplementation((_, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))
    vi.stubGlobal('fetch', fetch)
    const response = api.summary()
    const rejected = expect(response).rejects.toBeInstanceOf(RequestTimeoutError)
    await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS)
    await rejected
    expect(fetch.mock.calls[0]![1].signal.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
  })
  it('preserves user cancellation without reclassifying it as a timeout', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })))
    const controller = new AbortController(), request = api.summary(controller.signal)
    const rejected = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort(); await rejected
    expect(vi.getTimerCount()).toBe(0)
  })
  it('cleans deadlines after successful and failed HTTP responses', async () => {
    vi.useFakeTimers(); mock({})
    await api.summary(); expect(vi.getTimerCount()).toBe(0)
    mock({}, 503); await expect(api.summary()).rejects.toBeInstanceOf(ApiError)
    expect(vi.getTimerCount()).toBe(0)
  })
})
