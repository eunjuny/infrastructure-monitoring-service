/** Bounded User Timing entries, visible in Chrome Performance without a DOM observer. */
export function recordPaintTiming(name: string, start: number, complete?: () => void) {
  let cancelled = false
  let frame = requestAnimationFrame(() => {
    frame = requestAnimationFrame(() => {
      if (cancelled) return
      if (typeof performance.measure === 'function') {
        performance.clearMeasures(name)
        performance.measure(name, { start, end: performance.now() })
      }
      complete?.()
    })
  })
  return () => { cancelled = true; cancelAnimationFrame(frame) }
}
