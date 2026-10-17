import { chromium } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import os from 'node:os'

const baseURL = 'http://127.0.0.1:5174'
const projectRoot = fileURLToPath(new URL('../../', import.meta.url))
const output = fileURLToPath(new URL('../.validation/performance/', import.meta.url))
const repetitions = Number(process.env.PERF_REPETITIONS || 20)
const label = process.env.PERF_LABEL || 'baseline'
const initialOnly = process.env.PERF_INITIAL_ONLY === '1'
if (!Number.isInteger(repetitions) || repetitions < 3 || repetitions > 50 || !/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid measurement options')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const measurements = { initial: [], expandCold: [], expandWarm: [], collapse: [], search: [], filter: [], refresh: [], refreshFivePages: [] }
const initialDetails = []
const errors = []
const browserSession = await browser.newBrowserCDPSession()
const environment = {
  date: new Date().toISOString(), cpu: os.cpus()[0]?.model, platform: process.platform,
  physicalMemory: os.totalmem(),
  browser: browser.version(), node: process.version, cpuThrottle: 4, cacheDisabled: true,
  networkThrottle: 'none', viewport: { width: 1440, height: 1000 }, repetitions,
}

async function createPage() {
  const context = await browser.newContext({ viewport: environment.viewport, serviceWorkers: 'block' })
  const page = await context.newPage(), session = await context.newCDPSession(page)
  await session.send('Network.enable')
  await session.send('Network.setCacheDisabled', { cacheDisabled: true })
  await session.send('Emulation.setCPUThrottlingRate', { rate: 4 })
  await session.send('Performance.enable')
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    window.__performanceProbe = {
      active: null,
      rootReady: null,
      rootEnding: false,
      longTasks: [],
      arm(kind, expected, mode, eventSelector) {
        this.active = { kind, expected, mode, eventSelector, started: mode === 'navigation' ? 0 : null, elapsed: null, ending: false }
      },
      check() {
        if (this.rootReady === null && !this.rootEnding && [...document.querySelectorAll('.tree-name')].some(node => node.textContent === 'cluster-01')) {
          this.rootEnding = true
          requestAnimationFrame(() => requestAnimationFrame(() => { this.rootReady = performance.now() }))
        }
        const current = this.active
        if (!current || current.started === null || current.elapsed !== null || current.ending) return
        const rows = [...document.querySelectorAll('.resource-row')]
        const nodes = [...document.querySelectorAll('.tree-name')]
        const ready = current.kind === 'initial' ? nodes.some(node => node.textContent === 'cluster-01') && rows.length > 0 && document.querySelector('.cards')?.textContent.includes('150,000')
          : current.kind === 'expand' ? nodes.some(node => node.textContent === current.expected)
          : current.kind === 'collapse' ? !nodes.some(node => node.textContent === current.expected)
          : current.kind === 'search' ? rows.length === 1 && rows[0].querySelector('strong')?.textContent === current.expected && document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
          : current.kind === 'filter' ? rows.length > 0 && rows.every(row => row.querySelector('.badge')?.textContent === current.expected) && document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
          : current.kind === 'refresh' ? document.querySelector('.detail-panel > .badge')?.textContent === current.expected && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
          : false
        if (!ready) return
        current.ending = true
        requestAnimationFrame(() => requestAnimationFrame(() => {
          if (this.active === current) current.elapsed = performance.now() - current.started
        }))
      },
    }
    new PerformanceObserver(entries => window.__performanceProbe.longTasks.push(...entries.getEntries().map(entry => ({ start: entry.startTime, duration: entry.duration })))).observe({ type: 'longtask', buffered: true })
    window.fetch = (...args) => {
      const current = window.__performanceProbe.active
      const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href)
      if (current?.mode === 'request' && current.started === null && url.pathname.endsWith('/search') && url.searchParams.get('q') === current.expected) current.started = performance.now()
      return originalFetch(...args)
    }
    for (const eventType of ['click', 'change']) document.addEventListener(eventType, event => {
      const current = window.__performanceProbe.active
      if (current?.mode === 'event' && current.started === null && event.target instanceof Element && event.target.closest(current.eventSelector)) current.started = performance.now()
    }, true)
    new MutationObserver(() => window.__performanceProbe.check()).observe(document, { subtree: true, childList: true, attributes: true, characterData: true })
    window.__performanceProbe.arm('initial', '', 'navigation', '')
  })
  return { context, page, session }
}

async function result(page) {
  await page.waitForFunction(() => window.__performanceProbe.active?.elapsed !== null, null, { timeout: 10000 })
  return page.evaluate(() => Number(window.__performanceProbe.active.elapsed.toFixed(2)))
}
async function arm(page, kind, expected, mode, selector = '') {
  await page.evaluate(({ kind, expected, mode, selector }) => window.__performanceProbe.arm(kind, expected, mode, selector), { kind, expected, mode, selector })
}
async function reset(page) {
  await page.getByRole('button', { name: '전체 Pod', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false' && document.querySelector('.resource-row strong')?.textContent === 'worker-pod-000001')
}
function mutatePod(nextStatus) {
  execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-U', 'monitoring', '-d', 'monitoring', '-v', 'ON_ERROR_STOP=1', '-c',
    `update infrastructure_pod set status='${nextStatus}', updated_at=clock_timestamp() where id=1;`], { cwd: projectRoot, stdio: 'pipe' })
}

let retained
try {
  for (let iteration = 0; iteration < repetitions; iteration++) {
    const state = await createPage(), { page } = state
    try {
      await page.goto(baseURL, { waitUntil: 'domcontentloaded' })
      measurements.initial.push(await result(page))
      initialDetails.push(await page.evaluate(() => ({
        rootReady: window.__performanceProbe.rootReady,
        longTasks: window.__performanceProbe.longTasks,
        resources: performance.getEntriesByType('resource').map(entry => ({ name: new URL(entry.name).pathname, duration: entry.duration, start: entry.startTime, responseEnd: entry.responseEnd, transfer: entry.transferSize })),
        domContentLoaded: performance.getEntriesByType('navigation')[0].domContentLoadedEventEnd,
      })))
      if (initialOnly) {
        await state.context.close()
        process.stdout.write(`Initial ${iteration + 1}/${repetitions}: ${measurements.initial.at(-1)}ms\n`)
        continue
      }
      await page.getByLabel('자동 갱신').uncheck()
      await arm(page, 'expand', 'node-001', 'event', 'button[aria-label="cluster-01 펼치기"]')
      await page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()
      measurements.expandCold.push(await result(page))
      await arm(page, 'collapse', 'node-001', 'event', 'button[aria-label="cluster-01 접기"]')
      await page.getByRole('button', { name: 'cluster-01 접기', exact: true }).click()
      measurements.collapse.push(await result(page))
      await arm(page, 'expand', 'node-001', 'event', 'button[aria-label="cluster-01 펼치기"]')
      await page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()
      measurements.expandWarm.push(await result(page))
      await arm(page, 'search', 'worker-pod-000001', 'request')
      await page.locator('.filters input').fill('worker-pod-000001')
      measurements.search.push(await result(page))
      await reset(page)
      await arm(page, 'filter', '오류', 'event', '.filters select:last-of-type')
      await page.locator('.filters select').nth(1).selectOption('ERROR')
      measurements.filter.push(await result(page))
      await reset(page)
      await page.locator('.resource-row').first().click()
      await page.locator('.detail-panel h3').waitFor()
      const nextStatus = (await page.locator('.detail-panel > .badge').textContent()) === '오류' ? 'NORMAL' : 'ERROR'
      mutatePod(nextStatus)
      await arm(page, 'refresh', nextStatus === 'ERROR' ? '오류' : '정상', 'event', '.refresh-button')
      await page.getByRole('button', { name: '새로고침', exact: true }).click()
      measurements.refresh.push(await result(page))
      if (iteration === repetitions - 1) { retained = state; continue }
    } catch (error) { errors.push({ iteration, message: String(error) }) }
    await state.context.close()
    process.stdout.write(`Measured ${iteration + 1}/${repetitions}\n`)
  }
  if (initialOnly) {
    const values = [...measurements.initial].sort((a, b) => a - b), middle = Math.floor(values.length / 2)
    const initial = { count: values.length, median: values.length % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2,
      p95: values[Math.ceil(values.length * .95) - 1], max: values.at(-1), target: 500, overTarget: values.filter(value => value > 500).length }
    const report = { environment, initialOnly: true, summary: { initial }, measurements: { initial: measurements.initial }, initialDetails, errors }
    await writeFile(`${output}/${label}.json`, JSON.stringify(report, null, 2))
    process.stdout.write(JSON.stringify({ initialOnly: true, summary: report.summary, errors }, null, 2) + '\n')
    if (initial.overTarget || errors.length) process.exitCode = 1
  } else {
  if (!retained) throw new Error('No successful measurement context')
  const { page, session } = retained
  for (let index = 0; index < 4; index++) {
    await page.getByRole('button', { name: '다음 100개', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false')
  }
  for (let iteration = 0; iteration < repetitions; iteration++) {
    const nextStatus = (await page.locator('.detail-panel > .badge').textContent()) === '오류' ? 'NORMAL' : 'ERROR'
    mutatePod(nextStatus)
    await arm(page, 'refresh', nextStatus === 'ERROR' ? '오류' : '정상', 'event', '.refresh-button')
    await page.getByRole('button', { name: '새로고침', exact: true }).click()
    measurements.refreshFivePages.push(await result(page))
  }
  const traceEvents = []
  session.on('Tracing.dataCollected', event => traceEvents.push(...event.value))
  await session.send('Tracing.start', { categories: 'devtools.timeline,disabled-by-default-devtools.timeline,blink.user_timing,cc,benchmark', transferMode: 'ReportEvents' })
  const scrolling = await page.evaluate(() => new Promise(resolve => {
    performance.mark('measurement-scroll-start')
    const viewport = document.querySelector('.list-panel .virtual-viewport')
    const gaps = [], first = performance.now(); let last = null
    function frame(timestamp) {
      if (last !== null) gaps.push(timestamp - last)
      last = timestamp; viewport.scrollTop += 100
      if (timestamp - first < 4000) requestAnimationFrame(frame)
      else { performance.mark('measurement-scroll-end'); resolve({ duration: timestamp - first, gaps, rows: document.querySelectorAll('.resource-row').length, nodes: document.getElementsByTagName('*').length, offsetText: document.querySelector('.list-footer').textContent }) }
    }
    requestAnimationFrame(frame)
  }))
  const ended = new Promise(resolve => session.once('Tracing.tracingComplete', resolve))
  await session.send('Tracing.end'); await ended
  const memoryMetrics = await session.send('Performance.getMetrics')
  const processes = (await browserSession.send('SystemInfo.getProcessInfo')).processInfo.filter(process => process.type === 'renderer')
  const rendererRSS = processes.map(process => ({ pid: process.id, rssKiB: Number(execFileSync('ps', ['-p', String(process.id), '-o', 'rss='], { encoding: 'utf8' }).trim()) }))
  const memoryHistory = []
  for (let index = 0; index <= 100; index++) {
    if (index % 25 === 0) {
      const current = await session.send('Performance.getMetrics')
      const renderer = (await browserSession.send('SystemInfo.getProcessInfo')).processInfo.find(process => process.type === 'renderer')
      memoryHistory.push({ pages: index,
        heapUsed: current.metrics.find(metric => metric.name === 'JSHeapUsedSize')?.value,
        rendererRSSKiB: renderer ? Number(execFileSync('ps', ['-p', String(renderer.id), '-o', 'rss='], { encoding: 'utf8' }).trim()) : null,
        rows: await page.locator('.resource-row').count(), footer: await page.locator('.list-footer').textContent(),
      })
    }
    if (index < 100) {
      await page.getByRole('button', { name: '다음 100개', exact: true }).click()
      await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false')
    }
  }
  await session.send('HeapProfiler.collectGarbage')
  const collected = await session.send('Performance.getMetrics')
  const summarize = (values, target) => {
    const sorted = [...values].sort((left, right) => left - right)
    const middle = Math.floor(sorted.length / 2)
    const median = sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
    return { count: values.length, median, p95: sorted[Math.ceil(sorted.length * .95) - 1], max: sorted.at(-1), target, overTarget: values.filter(value => value > target).length }
  }
  const summary = Object.fromEntries(Object.entries(measurements).map(([name, values]) => [name, summarize(values, name === 'initial' ? 500 : name === 'search' ? 300 : ['filter', 'refresh', 'refreshFivePages'].includes(name) ? 200 : 100)]))
  const frameNames = [...new Set(traceEvents.filter(event => /DrawFrame|DroppedFrame|BeginFrame/.test(event.name)).map(event => event.name))]
  const scrollStart = traceEvents.find(event => event.name === 'measurement-scroll-start')?.ts ?? 0
  const scrollEnd = traceEvents.find(event => event.name === 'measurement-scroll-end')?.ts ?? Infinity
  const drawFrames = traceEvents.filter(event => ['DrawFrame', 'DirectRenderer::DrawFrame'].includes(event.name) && event.ts >= scrollStart && event.ts <= scrollEnd)
  const compositorFPS = drawFrames.length > 1 ? (drawFrames.length - 1) * 1000000 / (drawFrames.at(-1).ts - drawFrames[0].ts) : null
  const report = { environment, summary, measurements, initialDetails, errors,
    scroll: { ...scrolling, rafFPS: scrolling.gaps.length / (scrolling.gaps.reduce((sum, value) => sum + value, 0) / 1000), compositorFPS, framesAbove33ms: scrolling.gaps.filter(gap => gap > 33.4).length, traceFrameNames: frameNames, drawFrames: drawFrames.length },
    memory: { cdp: Object.fromEntries(memoryMetrics.metrics.filter(metric => /JSHeap|Nodes|Documents|LayoutCount/.test(metric.name)).map(metric => [metric.name, metric.value])), rendererRSS, history: memoryHistory,
      heapAfterExplicitGC: collected.metrics.find(metric => metric.name === 'JSHeapUsedSize')?.value },
  }
  await writeFile(`${output}/${label}.json`, JSON.stringify(report, null, 2))
  await writeFile(`${output}/${label}-trace.json`, JSON.stringify({ traceEvents }))
  process.stdout.write(JSON.stringify({ summary, errors, scroll: report.scroll.rafFPS, memory: report.memory, traceFrameNames: frameNames }, null, 2) + '\n')
  }
} finally { await browser.close() }
