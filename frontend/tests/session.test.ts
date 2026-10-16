import { afterEach, expect, it, vi } from 'vitest'
import { api, REQUEST_TIMEOUT_MS, RequestTimeoutError } from '../src/api'
import { configureSession, logoutSession, session, tokenProvider } from '../src/session'

afterEach(async () => { await logoutSession(); session.enabled = false })

it('adds only the in-memory provider token to API requests', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
  vi.stubGlobal('fetch', fetch)
  configureSession(async () => 'fixture-access-token', async () => {})
  await api.summary()
  expect(fetch).toHaveBeenCalledWith('/api/resources/summary', expect.objectContaining({
    headers: { Accept: 'application/json', Authorization: 'Bearer fixture-access-token' },
  }))
  expect(localStorage.length).toBe(0)
  expect(sessionStorage.length).toBe(0)
})

it('does not make an anonymous request when token refresh fails', async () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  configureSession(async () => { throw new Error('authentication unavailable') }, async () => {})
  await expect(api.summary()).rejects.toThrow('authentication unavailable')
  expect(fetch).not.toHaveBeenCalled()
})

it('includes stalled token refresh within the existing request deadline', async () => {
  vi.useFakeTimers()
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  configureSession(() => new Promise<string>(() => {}), async () => {})
  const rejected = expect(api.summary()).rejects.toBeInstanceOf(RequestTimeoutError)
  await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS)
  await rejected
  expect(fetch).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
})

it('preserves user cancellation during token refresh', async () => {
  const controller = new AbortController()
  configureSession(() => new Promise<string>(() => {}), async () => {})
  const rejected = expect(api.summary(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
  controller.abort()
  await rejected
})

it('clears the provider and role state on logout without falling back to local mode', async () => {
  const logout = vi.fn().mockResolvedValue(undefined)
  session.enabled = true; session.name = 'fixture-admin'; session.administrator = true; session.clusters = [1]
  configureSession(async () => 'fixture-access-token', logout)
  await logoutSession()
  expect(tokenProvider()).toBeUndefined()
  expect(session).toMatchObject({ enabled: true, name: '', administrator: false, clusters: [] })
  expect(logout).toHaveBeenCalledOnce()
})
