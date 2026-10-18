import { chromium } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import { performance } from 'node:perf_hooks'
import { execFileSync } from 'node:child_process'

// Local real PKCE only: no injected token, storageState, trace, screenshot or HAR.
const baseURL = 'http://127.0.0.1:5176/'
const repetitions = Number(process.env.PERF_REPETITIONS || 20)
const label = process.env.PERF_LABEL || 'auth-admin'
const browserChannel = process.env.PERF_BROWSER === 'chrome' ? 'chrome' : undefined
const full = process.env.PERF_FULL === '1'
if (process.env.PERF_BROWSER && !browserChannel) throw new Error('Use chrome or the default bundled Chromium')
if (!Number.isInteger(repetitions) || repetitions < 3 || repetitions > 50 || !/^[a-z0-9-]{1,80}$/.test(label)) {
  throw new Error('Invalid measurement options')
}
const account = JSON.parse(await readFile(new URL('../../.local/auth/credentials.json', import.meta.url), 'utf8')).users.admin
const samples = Object.fromEntries(['loginSubmit', 'ssoNavigation', 'callbackReady', 'expandCold', 'collapse', 'expandWarm', 'search', 'filter', 'refreshReadOnly', ...(full ? ['refreshChanged', 'pollChanged'] : [])].map(key => [key, []]))
const targets = { ssoNavigation: 500, callbackReady: 500, expandCold: 100, collapse: 100, expandWarm: 100, search: 300, filter: 200, refreshReadOnly: 200, refreshChanged: 200, pollChanged: 200 }
const errors = [], checks = [], apiTimings = [], apiFailures = [], details = []
let originalPod, lastWrittenPod, scrollResult, podRestored = null
let browser

function sql(statement) {
  return execFileSync('docker', ['compose', '--env-file', '.local/auth/compose.env', '-f', 'compose.auth.yaml',
    'exec', '-T', 'db', 'psql', '-U', 'infrastructure_admin', '-d', 'monitoring', '-tA', '-v', 'ON_ERROR_STOP=1', '-c', statement],
  { cwd: new URL('../../', import.meta.url), encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim().split('\n').find(line => line.startsWith('{'))
}
function changePod() {
  const current = JSON.parse(sql("SELECT json_build_object('status',status,'updatedAt',updated_at) FROM infrastructure_pod WHERE id=1"))
  originalPod ??= current
  const status = current.status === 'ERROR' ? 'NORMAL' : 'ERROR'
  const row = sql(`UPDATE infrastructure_pod SET status='${status}',updated_at=clock_timestamp() WHERE id=1 AND updated_at='${current.updatedAt}'::timestamptz RETURNING json_build_object('status',status,'updatedAt',updated_at)`)
  if (!row) throw new Error('ConcurrentFixtureChange')
  lastWrittenPod = JSON.parse(row)
  return status === 'ERROR' ? '오류' : '정상'
}

async function extended(page, cdp) {
  for (let iteration = 0; iteration < 3; iteration++) {
    const expected = changePod()
    await page.evaluate(expected => window.__authPerformance.arm('poll', expected, ''), expected)
    await page.getByLabel('자동 갱신').check()
    await page.waitForFunction(() => typeof window.__authPerformance.active?.elapsed === 'number', null, { timeout: 15000 })
    const active = await page.evaluate(() => window.__authPerformance.active)
    samples.pollChanged.push(active.elapsed)
    if (active.emptyListObserved) throw new Error('RefreshFlicker')
    await page.getByLabel('자동 갱신').uncheck()
  }
  for (let index = 0; index < 4; index++) {
    await page.getByRole('button', { name: '다음 100개', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false')
  }
  const events = []
  const collect = event => events.push(...event.value)
  cdp.on('Tracing.dataCollected', collect)
  await cdp.send('Tracing.start', { categories: 'devtools.timeline,disabled-by-default-devtools.timeline,blink.user_timing,cc,benchmark', transferMode: 'ReportEvents' })
  const scroll = await page.evaluate(() => new Promise(resolve => {
    performance.mark('acceptance-scroll-start')
    const viewport = document.querySelector('.list-panel .virtual-viewport')
    const started = performance.now(), gaps = [], oneSecondFrames = Array(10).fill(0)
    let last
    const frame = timestamp => {
      if (last !== undefined) {
        gaps.push(timestamp - last)
        const window = Math.min(9, Math.floor((timestamp - started) / 1000))
        oneSecondFrames[window]++
      }
      last = timestamp
      viewport.scrollTop += 65
      if (timestamp - started < 10000) requestAnimationFrame(frame)
      else {
        performance.mark('acceptance-scroll-end')
        resolve({ duration: timestamp - started, gaps, oneSecondFrames, visibleRows: document.querySelectorAll('.resource-row').length })
      }
    }
    requestAnimationFrame(frame)
  }))
  const ended = new Promise(resolve => cdp.once('Tracing.tracingComplete', resolve))
  await cdp.send('Tracing.end'); await ended
  cdp.off('Tracing.dataCollected', collect)
  const from = events.find(event => event.name === 'acceptance-scroll-start')?.ts
  const to = events.find(event => event.name === 'acceptance-scroll-end')?.ts
  const draws = events.filter(event => ['DrawFrame', 'DirectRenderer::DrawFrame'].includes(event.name) && event.ts >= from && event.ts <= to)
  return { ...scroll, traceDrawFrames: draws.length,
    compositorFPS: draws.length > 1 ? (draws.length - 1) * 1e6 / (draws.at(-1).ts - draws[0].ts) : null,
    rafFPS: scroll.gaps.length * 1000 / scroll.gaps.reduce((sum, gap) => sum + gap, 0),
    minOneSecondRafFPS: scroll.oneSecondFrames.length ? Math.min(...scroll.oneSecondFrames) : null,
    framesOver18ms: scroll.gaps.filter(gap => gap > 18).length,
    note: 'Supplementary 10-second CDP/RAF frame timing, not a native Chrome FPS-meter or Task Manager memory result' }
}

function summarize(values, target) {
  const sorted = [...values].sort((a, b) => a - b)
  return { count: sorted.length, median: sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2 : null,
    p95: sorted[Math.ceil(sorted.length * .95) - 1] ?? null, max: sorted.at(-1) ?? null,
    ...(target === undefined ? {} : { target, overTarget: sorted.filter(value => value > target).length }) }
}
async function ready(page) {
  await page.waitForFunction(() => typeof window.__authPerformance?.readyMs === 'number', null, { timeout: 20000 })
  return page.evaluate(() => ({ readyMs: window.__authPerformance.readyMs, longTasks: window.__authPerformance.longTasks }))
}
async function measure(page, kind, expected, selector, action) {
  await page.evaluate(({ kind, expected, selector }) => window.__authPerformance.arm(kind, expected, selector), { kind, expected, selector })
  await action()
  await page.waitForFunction(() => typeof window.__authPerformance?.active?.elapsed === 'number', null, { timeout: 15000 })
  return page.evaluate(() => window.__authPerformance.active.elapsed)
}
async function reset(page) {
  await page.getByRole('button', { name: 'Pod 전체 목록 보기', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
    && document.querySelector('.resource-row strong')?.textContent === 'worker-pod-000001')
}

try {
  browser = await chromium.launch({ headless: true, ...(browserChannel ? { channel: browserChannel } : {}) })
  for (let iteration = 0; iteration < repetitions; iteration++) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' })
    const page = await context.newPage()
    const cdp = await context.newCDPSession(page)
    await cdp.send('Network.enable')
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
    let stage = 'login', invalidApi = 0
    const started = new WeakMap(), iterationTimings = []
    page.on('request', request => started.set(request, performance.now()))
    page.on('requestfinished', request => {
      const url = new URL(request.url())
      if (url.origin !== new URL(baseURL).origin || !url.pathname.startsWith('/api/')) return
      // Only fixed endpoint categories and duration: never URL query, headers, body or JWT.
      const category = url.pathname.endsWith('/summary') ? 'summary' : url.pathname.endsWith('/search') ? 'search'
        : url.pathname === '/api/auth/me' ? 'identity' : url.pathname === '/api/auth/config' ? 'config'
        : url.pathname.endsWith('/children') ? 'children' : url.pathname === '/api/resources' ? 'list' : 'other'
      const timing = request.timing()
      const duration = performance.now() - started.get(request)
      const rounded = value => value >= 0 ? Number(value.toFixed(2)) : null
      iterationTimings.push({ iteration, stage, category, duration: rounded(duration),
        browserRequestMs: rounded(timing.responseEnd),
        responseWaitMs: rounded(timing.responseStart - timing.requestStart),
        responseBodyMs: rounded(timing.responseEnd - timing.responseStart),
        observerDeliveryMs: timing.responseEnd >= 0 ? rounded(Math.max(0, duration - timing.responseEnd)) : null,
      })
    })
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.origin !== new URL(baseURL).origin || !url.pathname.startsWith('/api/')) return
      if (response.status() >= 400) {
        invalidApi++
        const category = url.pathname === '/api/auth/config' ? 'auth-config'
          : url.pathname === '/api/auth/me' ? 'identity'
            : url.pathname === '/api/resources/summary' ? 'summary'
              : url.pathname === '/api/resources/search' ? 'search'
                : url.pathname === '/api/resources' ? 'list'
                  : url.pathname.startsWith('/api/operations/') ? 'operations' : 'resource'
        apiFailures.push({ iteration, stage, category, status: response.status() })
      }
    })
    await page.addInitScript(() => {
      if (location.origin !== 'http://127.0.0.1:5176') return
      performance.mark('acceptance:app-document-start')
      const probe = window.__authPerformance = {
        readyMs: null, readyEnding: false, active: null, pending: 0, requests: 0, longTasks: [], sequence: 0,
        start(current) {
          if (current.started !== null) return
          current.started = performance.now()
          current.startMark = `acceptance:${current.id}:start`
          performance.mark(current.startMark)
        },
        arm(kind, expected, selector) {
          this.active = { id: `${kind}-${++this.sequence}`, kind, expected, selector, started: null, elapsed: null,
            ending: false, requests: this.requests, emptyListObserved: false }
        },
        check() {
          const rows = [...document.querySelectorAll('.resource-row')]
          const nodes = [...document.querySelectorAll('.tree-name')]
          const idle = document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
          if (this.readyMs === null && !this.readyEnding && idle && rows.length > 0
            && rows[0]?.querySelector('strong')?.textContent === 'cluster-01') {
            this.readyEnding = true
            requestAnimationFrame(() => requestAnimationFrame(() => {
              this.readyMs = performance.now()
              performance.mark('acceptance:cluster-list-ready')
              if (performance.getEntriesByName('acceptance:app-document-start', 'mark').length) {
                performance.measure('acceptance:app-document-to-cluster-list', 'acceptance:app-document-start', 'acceptance:cluster-list-ready')
              }
            }))
          }
          const current = this.active
          if (!current || current.started === null || current.elapsed !== null || current.ending) return
          const requested = this.requests > current.requests
          if ((current.kind === 'changed' || current.kind === 'poll') && !rows.length) current.emptyListObserved = true
          const done = current.kind === 'expand' ? nodes.some(node => node.textContent === current.expected)
            : current.kind === 'collapse' ? !nodes.some(node => node.textContent === current.expected)
            : current.kind === 'search' ? requested && idle && rows.length === 1 && rows[0].querySelector('strong')?.textContent === current.expected
            : current.kind === 'filter' ? requested && idle && rows.length > 0 && rows.every(row => row.querySelector('.badge')?.textContent === current.expected)
            : current.kind === 'refresh' ? requested && this.pending === 0 && idle && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
            : ['changed', 'poll'].includes(current.kind) ? requested && this.pending === 0 && idle
              && rows[0]?.querySelector('.badge')?.textContent === current.expected
              && document.querySelector('.detail-panel > .badge')?.textContent === current.expected
              && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
            : false
          if (!done) return
          current.ending = true
          requestAnimationFrame(() => requestAnimationFrame(() => {
            if (this.active === current) {
              current.elapsed = Number((performance.now() - current.started).toFixed(2))
              current.endMark = `acceptance:${current.id}:end`
              current.measureName = `acceptance:${current.id}`
              performance.mark(current.endMark)
              performance.measure(current.measureName, current.startMark, current.endMark)
            }
          }))
        },
      }
      new PerformanceObserver(entries => probe.longTasks.push(...entries.getEntries().map(entry => ({ start: entry.startTime, duration: entry.duration })))).observe({ type: 'longtask', buffered: true })
      const original = window.fetch.bind(window)
      window.fetch = (...args) => {
        const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href)
        const api = url.origin === location.origin && url.pathname.startsWith('/api/')
        const current = probe.active
        if (current?.kind === 'search' && current.started === null && url.pathname.endsWith('/search') && url.searchParams.get('q') === current.expected) probe.start(current)
        if (current?.kind === 'poll' && current.started === null && api && url.pathname === '/api/resources') probe.start(current)
        if (api) { probe.pending++; probe.requests++ }
        return original(...args).finally(() => { if (api) probe.pending--; probe.check() })
      }
      for (const type of ['click', 'change']) document.addEventListener(type, event => {
        const current = probe.active
        if (current && current.kind !== 'search' && current.selector && current.started === null
          && event.target instanceof Element && event.target.closest(current.selector)) probe.start(current)
      }, true)
      new MutationObserver(() => probe.check()).observe(document, { subtree: true, childList: true, attributes: true, characterData: true })
    })
    try {
      await page.goto(baseURL)
      await page.locator('#username').waitFor()
      const loginURL = new URL(page.url())
      if (loginURL.origin !== 'http://127.0.0.1:8183' || loginURL.searchParams.get('code_challenge_method') !== 'S256') throw new Error('LoginContract')
      await page.locator('#username').fill(account.username)
      await page.locator('#password').fill(account.password)
      const submitted = performance.now()
      await page.locator('#kc-login').click()
      const loginReady = await ready(page)
      samples.loginSubmit.push(Number((performance.now() - submitted).toFixed(2)))
      details.push({ iteration, phase: 'password-login-callback', ...loginReady })
      stage = 'sso-navigation'
      const navigation = performance.now()
      await page.goto(baseURL)
      const ssoReady = await ready(page)
      samples.ssoNavigation.push(Number((performance.now() - navigation).toFixed(2)))
      samples.callbackReady.push(Number(ssoReady.readyMs.toFixed(2)))
      details.push({ iteration, phase: 'sso-callback', ...ssoReady })
      stage = 'identity-contract'
      await page.waitForFunction(() => document.querySelector('.session-info')?.textContent.includes('전체 관리자'))
      await page.waitForFunction(() => document.querySelector('.cards')?.textContent.includes('150,000'))
      const visibleCounts = (await page.locator('.cards dd span').allTextContents()).map(value => value.trim())
      const visibleIdentity = await page.locator('.session-info').innerText()
      const valid = visibleCounts.join('|') === '20|200|2,000|150,000' && visibleIdentity.includes('전체 관리자')
      if (!valid) throw new Error('AdminDatasetContract')
      await page.getByRole('button', { name: '운영 관측', exact: true }).waitFor()
      await page.getByLabel('자동 갱신').uncheck()
      stage = 'expandCold'
      samples.expandCold.push(await measure(page, 'expand', 'node-001', 'button[aria-label="cluster-01 펼치기"]', () => page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()))
      stage = 'collapse'
      samples.collapse.push(await measure(page, 'collapse', 'node-001', 'button[aria-label="cluster-01 접기"]', () => page.getByRole('button', { name: 'cluster-01 접기', exact: true }).click()))
      stage = 'expandWarm'
      samples.expandWarm.push(await measure(page, 'expand', 'node-001', 'button[aria-label="cluster-01 펼치기"]', () => page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()))
      stage = 'search'
      await page.getByRole('button', { name: 'Pod 전체 목록 보기', exact: true }).click()
      await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
        && document.querySelector('.resource-row strong')?.textContent === 'worker-pod-000001')
      samples.search.push(await measure(page, 'search', 'worker-pod-000001', '', () => page.locator('.filters input').fill('worker-pod-000001')))
      stage = 'reset'
      await reset(page)
      stage = 'filter'
      samples.filter.push(await measure(page, 'filter', '오류', '.filters select:last-of-type', () => page.locator('.filters select').nth(1).selectOption('ERROR')))
      stage = 'reset'
      await reset(page)
      stage = 'refresh'
      samples.refreshReadOnly.push(await measure(page, 'refresh', '', '.refresh-button', () => page.getByRole('button', { name: '새로고침', exact: true }).click()))
      if (full) {
        await page.locator('.resource-row').first().click()
        await page.locator('.detail-panel > .badge').waitFor()
        const expected = changePod()
        samples.refreshChanged.push(await measure(page, 'changed', expected, '.refresh-button',
          () => page.getByRole('button', { name: '새로고침', exact: true }).click()))
        if (await page.evaluate(() => window.__authPerformance.active.emptyListObserved)) throw new Error('RefreshFlicker')
        if (iteration === repetitions - 1) scrollResult = await extended(page, cdp)
      }
      details.push({ iteration, phase: 'interactions', longTasks: await page.evaluate(() => window.__authPerformance.longTasks),
        userTiming: await page.evaluate(() => performance.getEntriesByType('measure')
          .filter(entry => entry.name.startsWith('acceptance:'))
          .map(entry => ({ name: entry.name, start: Number(entry.startTime.toFixed(2)), duration: Number(entry.duration.toFixed(2)) }))) })
      if (invalidApi) throw new Error('ApiFailure')
      checks.push({ iteration, administrator: true, counts: visibleCounts, invalidApi })
    } catch (error) {
      // Error messages/call logs can contain credential form values or callback URLs.
      const safeChecks = ['AdminDatasetContract', 'ApiFailure', 'ConcurrentFixtureChange', 'RefreshFlicker']
      errors.push({ iteration, stage, category: error.name === 'TimeoutError' ? 'TimeoutError'
        : safeChecks.includes(error.message) ? error.message : 'ValidationOrBrowserError' })
    } finally {
      apiTimings.push(...iterationTimings)
      await context.close()
    }
    process.stdout.write(`Admin auth measurement ${iteration + 1}/${repetitions}\n`)
  }
  const summary = Object.fromEntries(Object.entries(samples).map(([key, values]) => [key, summarize(values, targets[key])]))
  const apiSummary = Object.fromEntries([...new Set(apiTimings.map(item => item.category))].map(category => [category, summarize(apiTimings.filter(item => item.category === category).map(item => item.duration))]))
  const report = {
    environment: { date: new Date().toISOString(), cpu: os.cpus()[0]?.model, physicalMemory: os.totalmem(), platform: process.platform,
      node: process.version, browser: browser.version(), cpuThrottle: 4, cacheDisabled: true, networkThrottle: 'none', viewport: { width: 1440, height: 1000 }, repetitions,
      runtime: 'auth-compose-nginx', serverAlreadyRunning: true, account: 'ADMIN', dataset: { clusters: 20, nodes: 200, namespaces: 2000, pods: 150000 } },
    protocol: { loginSubmit: 'automated submit through real PKCE to ready; excludes human typing; includes automation overhead',
    ssoNavigation: 'navigation through existing SSO session, silent check or supported redirect fallback, code exchange and first interactive Cluster list row plus two animation frames; includes automation overhead',
      callbackReady: 'final callback document navigation start to first interactive Cluster list row plus two animation frames; excludes unrelated summary completion and identity label rendering',
      refreshReadOnly: 'real request group and DOM ready; does not mutate DB or prove changed-value propagation',
      apiSummary: 'request-to-observer timings; browserRequestMs is browser network timing; responseWaitMs includes network, proxy and server; observerDeliveryMs is estimated reporting delay, never pure server or SQL time' },
    summary, measurements: samples, apiSummary, apiTimings, apiFailures, checks, details, errors, scroll: scrollResult,
  }
  const output = new URL('../.validation/performance/', import.meta.url)
  await mkdir(output, { recursive: true })
  await writeFile(new URL(`${label}.json`, output), JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  process.stdout.write(JSON.stringify({ summary, apiSummary, verifiedIterations: checks.length, errors }, null, 2) + '\n')
  if (errors.length || checks.length !== repetitions || Object.entries(summary).some(([key, item]) => item.count !== (key === 'pollChanged' ? 3 : repetitions) || item.overTarget > 0)
      || (full && (scrollResult?.compositorFPS == null || scrollResult.compositorFPS < 55 || scrollResult.minOneSecondRafFPS == null))) process.exitCode = 1
} catch {
  process.stderr.write('Authentication performance setup failed; sensitive diagnostics were not retained.\n')
  process.exitCode = 1
} finally {
  if (originalPod && lastWrittenPod) {
    try {
      podRestored = Boolean(sql(`UPDATE infrastructure_pod SET status='${originalPod.status}',updated_at='${originalPod.updatedAt}'::timestamptz
        WHERE id=1 AND status='${lastWrittenPod.status}' AND updated_at='${lastWrittenPod.updatedAt}'::timestamptz RETURNING json_build_object('restored',true)`))
      process.stdout.write(`Fixture restoration: ${podRestored ? 'restored' : 'concurrent change preserved'}\n`)
      await writeFile(new URL(`../.validation/performance/${label}-restoration.json`, import.meta.url), JSON.stringify({ podRestored }) + '\n', { mode: 0o600, flag: 'wx' })
    } catch { process.stderr.write('Fixture restoration requires local review; no diagnostics emitted.\n'); process.exitCode = 1 }
  }
  await browser?.close()
}
