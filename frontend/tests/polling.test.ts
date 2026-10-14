import { afterEach, describe, expect, it, vi } from 'vitest'
import { usePolling } from '../src/usePolling'
import { deferred } from './fixtures'
let polling: ReturnType<typeof usePolling> | undefined
afterEach(() => { polling?.dispose(); polling = undefined })
function setup() {
  vi.useFakeTimers()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
  const refresh = vi.fn().mockResolvedValue(true), ready = vi.fn().mockReturnValue(true), cancel = vi.fn()
  polling = usePolling(refresh, ready, cancel); polling.start()
  return { refresh, ready, cancel }
}
describe('bounded foreground polling', () => {
  it('starts after five seconds and reschedules after completion', async () => {
    const { refresh } = setup()
    await vi.advanceTimersByTimeAsync(4999); expect(refresh).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1); expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5000); expect(refresh).toHaveBeenCalledTimes(2)
  })
  it('never overlaps a slow request group', async () => {
    const { refresh } = setup(), pending = deferred<boolean>()
    refresh.mockReturnValueOnce(pending.promise)
    await vi.advanceTimersByTimeAsync(25000); expect(refresh).toHaveBeenCalledTimes(1)
    pending.resolve(true); await vi.advanceTimersByTimeAsync(5000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })
  it('waits for initial loading or a filter request', async () => {
    const { refresh, ready } = setup(); ready.mockReturnValue(false)
    await vi.advanceTimersByTimeAsync(5000); expect(refresh).not.toHaveBeenCalled()
    ready.mockReturnValue(true); await vi.advanceTimersByTimeAsync(500)
    expect(refresh).toHaveBeenCalledTimes(1)
  })
  it('backs off to sixty seconds and recovers after success', async () => {
    const { refresh } = setup(); refresh.mockResolvedValue(false)
    for (const delay of [5000, 10000, 20000, 40000, 60000]) await vi.advanceTimersByTimeAsync(delay)
    expect(polling!.delay.value).toBe(60000)
    refresh.mockResolvedValue(true); await vi.advanceTimersByTimeAsync(60000)
    expect(polling!.delay.value).toBe(5000); expect(polling!.failures.value).toBe(0)
  })
  it('pauses hidden tabs and resumes without duplicate timers', async () => {
    const { refresh } = setup()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60000); expect(refresh).not.toHaveBeenCalled(); expect(polling!.paused.value).toBe(true)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange')); document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(5000); expect(refresh).toHaveBeenCalledTimes(1)
  })
  it('cancels offline polling without counting the cancellation as a failure', async () => {
    const { refresh, cancel } = setup(), pending = deferred<boolean>()
    refresh.mockReturnValueOnce(pending.promise); await vi.advanceTimersByTimeAsync(5000)
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false); window.dispatchEvent(new Event('offline'))
    expect(cancel).toHaveBeenCalledTimes(1)
    pending.resolve(false); await vi.advanceTimersByTimeAsync(60000)
    expect(refresh).toHaveBeenCalledTimes(1); expect(polling!.failures.value).toBe(0)
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true); window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(5000); expect(refresh).toHaveBeenCalledTimes(2)
  })
  it('supports disabling and re-enabling polling', async () => {
    const { refresh } = setup(); polling!.setEnabled(false)
    await vi.advanceTimersByTimeAsync(60000); expect(refresh).not.toHaveBeenCalled()
    polling!.setEnabled(true); await vi.advanceTimersByTimeAsync(5000); expect(refresh).toHaveBeenCalledTimes(1)
  })
  it('removes timers and listeners on disposal', async () => {
    const { refresh } = setup(); polling!.dispose()
    window.dispatchEvent(new Event('online')); document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60000); expect(refresh).not.toHaveBeenCalled()
  })
  it('converts unexpected rejection into a bounded retry', async () => {
    const { refresh } = setup(); refresh.mockRejectedValueOnce(new Error('failure'))
    await vi.advanceTimersByTimeAsync(5000)
    expect(polling!.failures.value).toBe(1); expect(polling!.delay.value).toBe(10000)
  })
})
