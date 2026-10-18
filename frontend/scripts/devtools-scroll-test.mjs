// Paste into the local page Console; run separately from app interaction timing.
(() => {
  const viewport = document.querySelector('.list-panel .virtual-viewport')
  if (!viewport || location.origin !== 'http://127.0.0.1:5176') return
  const intervals = []
  const duration = 15000
  let started, previous
  function step(now) {
    started ??= now
    if (previous !== undefined) intervals.push(now - previous)
    previous = now
    const elapsed = now - started
    viewport.scrollTop = 100 + (1 + Math.sin(elapsed / 1000)) / 2
      * Math.max(1, viewport.scrollHeight - viewport.clientHeight - 1200)
    if (elapsed < duration) {
      requestAnimationFrame(step)
      return
    }
    const buckets = Array(15).fill(0)
    let time = 0
    for (const interval of intervals) {
      time += interval
      if (time < duration) buckets[Math.floor(time / 1000)]++
    }
    window.__scrollEvidence = {
      elapsed, frames: intervals.length, averageFPS: intervals.length * 1000 / elapsed,
      minOneSecondFPS: Math.min(...buckets), buckets,
      visibility: document.visibilityState,
    }
  }
  setTimeout(() => requestAnimationFrame(step), 1000)
})()
