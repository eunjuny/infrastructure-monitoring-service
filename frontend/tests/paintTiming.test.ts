import { expect, it, vi } from 'vitest'
import { recordPaintTiming } from '../src/paintTiming'

function frames() {
  const callbacks: FrameRequestCallback[] = []
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => callbacks.push(callback))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  return () => callbacks.shift()!(0)
}
it('records the full supplied start-to-rendering interval after two frames and bounds entries', () => {
  const advance = frames(), measure = vi.fn(), clearMeasures = vi.fn()
  vi.stubGlobal('performance', { now: () => 200, measure, clearMeasures })
  const complete = vi.fn()
  recordPaintTiming('First Cluster list', 0, complete)
  advance(); expect(measure).not.toHaveBeenCalled()
  advance()
  expect(clearMeasures).toHaveBeenCalledWith('First Cluster list')
  expect(measure).toHaveBeenCalledWith('First Cluster list', { start: 0, end: 200 })
  expect(complete).toHaveBeenCalledOnce()
})
it('cancels pending measurement and follow-up work when disposed', () => {
  const advance = frames(), complete = vi.fn()
  const cancel = recordPaintTiming('First Cluster list', 0, complete)
  advance(); cancel(); advance()
  expect(complete).not.toHaveBeenCalled()
})
it('still runs deferred work in an environment without User Timing support', () => {
  const advance = frames(), complete = vi.fn()
  vi.stubGlobal('performance', { now: () => 10 })
  recordPaintTiming('First Cluster list', 0, complete)
  advance(); advance()
  expect(complete).toHaveBeenCalledOnce()
})
