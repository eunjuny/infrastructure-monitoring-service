// Paste through DevTools Protocol Monitor as Page.addScriptToEvaluateOnNewDocument.
// Measurement helper only; this file is never imported by the application bundle.
(() => {
  if (location.origin !== 'http://127.0.0.1:5176' || window.__devtoolsEvidence) return
  const originalFetch = window.fetch
  let active = null
  let readyEnding = false
  let ready = false
  let requests = 0
  let pending = 0
  let sequence = 0
  performance.mark('evidence:document-start', { startTime: 0 })

  function start(current) {
    if (current.started !== null) return
    current.started = performance.now()
    performance.mark(current.startMark)
  }
  function afterPaint(callback) {
    requestAnimationFrame(() => requestAnimationFrame(callback))
  }
  function check() {
    const rows = [...document.querySelectorAll('.resource-row')]
    const names = [...document.querySelectorAll('.tree-name')]
    const idle = document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
    if (!ready && !readyEnding && idle && rows[0]?.querySelector('strong')?.textContent === 'cluster-01') {
      readyEnding = true
      afterPaint(() => {
        ready = true
        performance.mark('evidence:cluster-list-ready')
        performance.measure('01 First Cluster list', 'evidence:document-start', 'evidence:cluster-list-ready')
      })
    }
    const current = active
    if (!current || current.started === null || current.ending) return
    const requested = requests > current.requests
    if (['changed', 'poll'].includes(current.kind) && rows.length === 0) current.emptyListObserved = true
    const done = current.kind === 'expand' ? names.some(node => node.textContent === current.expected)
      : current.kind === 'collapse' ? !names.some(node => node.textContent === current.expected)
        : current.kind === 'search' ? requested && idle && rows.length === 1
          && rows[0].querySelector('strong')?.textContent === current.expected
          : current.kind === 'filter' ? requested && idle && rows.length > 0
            && rows.every(row => row.querySelector('.badge')?.textContent === current.expected)
            : ['refresh', 'changed', 'poll'].includes(current.kind) ? requested && pending === 0 && idle
              && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
              && (current.kind === 'refresh' || rows[0]?.querySelector('.badge')?.textContent === current.expected
                && document.querySelector('.detail-panel > .badge')?.textContent === current.expected)
              : false
    if (!done) return
    current.ending = true
    afterPaint(() => {
      performance.mark(current.endMark)
      performance.measure(current.name, {
        start: current.startMark, end: current.endMark,
        detail: { emptyListObserved: current.emptyListObserved, viewport: `${innerWidth}x${innerHeight}` },
      })
      current.completed = true
    })
  }
  function action(event) {
    // Opening a native select is not applying a filter; start at selection change.
    if (active?.kind === 'filter' && event.type !== 'change') return
    if (active && active.selector && active.kind !== 'search'
      && event.target instanceof Element && event.target.closest(active.selector)) start(active)
  }
  const observer = new MutationObserver(check)
  observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true })
  document.addEventListener('click', action, true)
  document.addEventListener('change', action, true)
  window.fetch = function (...args) {
    const input = args[0]
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, location.href)
    const api = url.origin === location.origin && url.pathname.startsWith('/api/')
    if (active?.kind === 'search' && url.pathname.endsWith('/search')
      && url.searchParams.get('q') === active.expected) start(active)
    if (active?.kind === 'poll' && api && url.pathname === '/api/resources') start(active)
    if (api) { pending++; requests++ }
    return originalFetch.apply(this, args).finally(() => {
      if (api) pending--
      check()
    })
  }
  window.__devtoolsEvidence = {
    arm(name, kind, expected = '', selector = '') {
      const id = ++sequence
      active = { name, kind, expected, selector, requests, started: null, ending: false,
        completed: false, emptyListObserved: false, startMark: `evidence:${id}:start`, endMark: `evidence:${id}:end` }
      return `Armed: ${name}`
    },
    dispose() {
      observer.disconnect()
      document.removeEventListener('click', action, true)
      document.removeEventListener('change', action, true)
      window.fetch = originalFetch
      delete window.__devtoolsEvidence
    },
  }
  check()
})()
