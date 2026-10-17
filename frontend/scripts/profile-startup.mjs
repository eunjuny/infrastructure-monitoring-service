import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const label = process.env.PERF_LABEL || 'startup-profile'
if (!/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid profile label')
const output = fileURLToPath(new URL('../.validation/performance/', import.meta.url))
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' })
  const page = await context.newPage(), session = await context.newCDPSession(page)
  await session.send('Network.enable')
  await session.send('Network.setCacheDisabled', { cacheDisabled: true })
  await session.send('Emulation.setCPUThrottlingRate', { rate: 4 })
  await session.send('Profiler.enable')
  const traceEvents = []
  session.on('Tracing.dataCollected', event => traceEvents.push(...event.value))
  await session.send('Tracing.start', { categories: 'devtools.timeline,disabled-by-default-devtools.timeline,v8,v8.execute,blink.user_timing', transferMode: 'ReportEvents' })
  await page.addInitScript(() => {
    const data = window.__startupDiagnostic = { intl: [], longTasks: [], ready: null }
    const DateFormatter = Intl.DateTimeFormat
    Intl.DateTimeFormat = new Proxy(DateFormatter, {
      construct(target, args) {
        const start = performance.now(), instance = Reflect.construct(target, args)
        data.intl.push({ kind: 'DateTimeFormat.construct', start, duration: performance.now() - start })
        return new Proxy(instance, { get(object, key) {
          const value = Reflect.get(object, key, object)
          if (key !== 'format') return value
          return (...args) => {
            const start = performance.now(), result = value(...args)
            data.intl.push({ kind: 'DateTimeFormat.format', start, duration: performance.now() - start })
            return result
          }
        } })
      },
    })
    const numberFormat = Number.prototype.toLocaleString
    Number.prototype.toLocaleString = function (...args) {
      const start = performance.now(), result = numberFormat.apply(this, args)
      data.intl.push({ kind: 'Number.toLocaleString', start, duration: performance.now() - start })
      return result
    }
    new PerformanceObserver(list => data.longTasks.push(...list.getEntries().map(entry => ({ start: entry.startTime, duration: entry.duration })))).observe({ type: 'longtask', buffered: true })
    const observer = new MutationObserver(() => {
      if (data.ready !== null || !document.querySelector('.tree-name') || !document.querySelector('.resource-row') || !document.querySelector('.cards')?.textContent.includes('150,000')) return
      data.ready = -1
      requestAnimationFrame(() => requestAnimationFrame(() => { data.ready = performance.now() }))
    })
    observer.observe(document, { subtree: true, childList: true, attributes: true, characterData: true })
  })
  await session.send('Profiler.start')
  await page.goto('http://127.0.0.1:5174/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction(() => window.__startupDiagnostic.ready > 0)
  const { profile } = await session.send('Profiler.stop')
  const ended = new Promise(resolve => session.once('Tracing.tracingComplete', resolve))
  await session.send('Tracing.end'); await ended
  await session.send('DOM.enable'); await session.send('CSS.enable')
  const { root } = await session.send('DOM.getDocument')
  const fonts = []
  for (const selector of ['.brand', 'h1', '.resource-row strong']) {
    const { nodeId } = await session.send('DOM.querySelector', { nodeId: root.nodeId, selector })
    if (nodeId) fonts.push({ selector, ...(await session.send('CSS.getPlatformFontsForNode', { nodeId })) })
  }
  const data = await page.evaluate(() => ({ ...window.__startupDiagnostic, resources: performance.getEntriesByType('resource').map(entry => ({ path: new URL(entry.name).pathname, start: entry.startTime, duration: entry.duration })) }))
  const nodes = new Map(profile.nodes.map(node => [node.id, node])), self = new Map()
  profile.samples?.forEach((id, index) => self.set(id, (self.get(id) || 0) + (profile.timeDeltas[index] || 0)))
  const top = [...self].map(([id, microseconds]) => ({ function: nodes.get(id).callFrame.functionName, url: nodes.get(id).callFrame.url, line: nodes.get(id).callFrame.lineNumber, ms: microseconds / 1000 }))
    .sort((a, b) => b.ms - a.ms).slice(0, 20)
  await writeFile(`${output}/${label}.cpuprofile`, JSON.stringify(profile))
  await writeFile(`${output}/${label}-trace.json`, JSON.stringify({ traceEvents }))
  const longestEvents = traceEvents.filter(event => event.ph === 'X' && event.dur > 1000).sort((a, b) => b.dur - a.dur).slice(0, 25)
    .map(event => ({ name: event.name, ms: event.dur / 1000 }))
  await writeFile(`${output}/${label}.json`, JSON.stringify({ diagnostic: true, data, top, longestEvents, fonts }, null, 2))
  process.stdout.write(JSON.stringify({ diagnostic: true, ready: data.ready, longTasks: data.longTasks, slowIntl: data.intl.filter(entry => entry.duration > 1), longestEvents, fonts, top }, null, 2) + '\n')
} finally { await browser.close() }
