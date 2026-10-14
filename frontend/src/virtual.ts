// Fixed-height rows only: DOM size depends on the viewport, not dataset size.
export function virtualRange(count: number, scrollTop: number, height: number, rowHeight: number, overscan = 5) {
  const start = Math.max(0, Math.min(count, Math.floor(Math.max(0, scrollTop) / rowHeight) - overscan))
  const end = Math.min(count, Math.ceil((Math.max(0, scrollTop) + height) / rowHeight) + overscan)
  return { start, end: Math.max(start, end), top: start * rowHeight, total: count * rowHeight }
}
