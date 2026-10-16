import { chromium } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import os from 'node:os'
import { performance } from 'node:perf_hooks'

// Local real PKCE only: no injected token, storageState, trace, screenshot or HAR.
const baseURL = 'http://127.0.0.1:5176/'
const repetitions = Number(process.env.PERF_REPETITIONS || 20)
const label = process.env.PERF_LABEL || 'auth-admin'
if (!Number.isInteger(repetitions) || repetitions < 3 || repetitions > 50 || !/^[a-z0-9-]{1,80}$/.test(label)) {
  throw new Error('Invalid measurement options')
}
const account = JSON.parse(await readFile(new URL('../../.local/auth/credentials.json', import.meta.url), 'utf8')).users.admin
const samples = Object.fromEntries(['loginSubmit', 'ssoNavigation', 'callbackReady', 'expandCold', 'collapse', 'expandWarm', 'search', 'filter', 'refreshReadOnly'].map(key => [key, []]))
const targets = { ssoNavigation: 500, callbackReady: 500, expandCold: 100, collapse: 100, expandWarm: 100, search: 300, filter: 200, refreshReadOnly: 200 }
const errors = [], checks = [], apiTimings = [], details = []
let browser

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
  await page.getByRole('button', { name: '전체 Pod', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
    && document.querySelector('.resource-row strong')?.textContent === 'worker-pod-000001')
}

try {
  browser = await chromium.launch({ headless: true })
  for (let iteration = 0; iteration < repetitions; iteration++) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' })
    const page = await context.newPage()
    const cdp = await context.newCDPSession(page)
    await cdp.send('Network.enable')
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
    let stage = 'login', latestSummary, latestUser, invalidApi = 0
    const started = new WeakMap(), iterationTimings = []
    page.on('request', request => started.set(request, performance.now()))
    page.on('requestfinished', request => {
      const url = new URL(request.url())
      if (url.origin !== new URL(baseURL).origin || !url.pathname.startsWith('/api/')) return
      // Only fixed endpoint categories and duration: never URL query, headers, body or JWT.
      const category = url.pathname.endsWith('/summary') ? 'summary' : url.pathname.endsWith('/search') ? 'search'
        : url.pathname === '/api/auth/me' ? 'identity' : url.pathname === '/api/auth/config' ? 'config'
        : url.pathname.endsWith('/children') ? 'children' : url.pathname === '/api/resources' ? 'list' : 'other'
      iterationTimings.push({ category, duration: Number((performance.now() - started.get(request)).toFixed(2)) })
    })
    const responseTasks = []
    page.on('response', response => {
      const url = new URL(response.url())
      if (url.origin !== new URL(baseURL).origin || !url.pathname.startsWith('/api/')) return
      if (response.status() >= 400) invalidApi++
      if (url.pathname === '/api/resources/summary' || url.pathname === '/api/auth/me') {
        responseTasks.push(response.json().then(body => {
          if (url.pathname.endsWith('/summary')) latestSummary = body
          else latestUser = body
        }).catch(() => { invalidApi++ }))
      }
    })
    await page.addInitScript(() => {
      if (location.origin !== 'http://127.0.0.1:5176') return
      const probe = window.__authPerformance = {
        readyMs: null, readyEnding: false, active: null, pending: 0, requests: 0, longTasks: [],
        arm(kind, expected, selector) {
          this.active = { kind, expected, selector, started: null, elapsed: null, ending: false, requests: this.requests }
        },
        check() {
          const rows = [...document.querySelectorAll('.resource-row')]
          const nodes = [...document.querySelectorAll('.tree-name')]
          const idle = document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
          if (this.readyMs === null && !this.readyEnding && idle && rows.length > 0
            && nodes.some(node => node.textContent === 'cluster-01')
            && document.querySelector('.cards')?.textContent.includes('100,000')
            && document.querySelector('.session-info')?.textContent.includes('전체 관리자')) {
            this.readyEnding = true
            requestAnimationFrame(() => requestAnimationFrame(() => { this.readyMs = performance.now() }))
          }
          const current = this.active
          if (!current || current.started === null || current.elapsed !== null || current.ending) return
          const requested = this.requests > current.requests
          const done = current.kind === 'expand' ? nodes.some(node => node.textContent === current.expected)
            : current.kind === 'collapse' ? !nodes.some(node => node.textContent === current.expected)
            : current.kind === 'search' ? requested && idle && rows.length === 1 && rows[0].querySelector('strong')?.textContent === current.expected
            : current.kind === 'filter' ? requested && idle && rows.length > 0 && rows.every(row => row.querySelector('.badge')?.textContent === current.expected)
            : current.kind === 'refresh' ? requested && this.pending === 0 && idle && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
            : false
          if (!done) return
          current.ending = true
          requestAnimationFrame(() => requestAnimationFrame(() => {
            if (this.active === current) current.elapsed = Number((performance.now() - current.started).toFixed(2))
          }))
        },
      }
      new PerformanceObserver(entries => probe.longTasks.push(...entries.getEntries().map(entry => ({ start: entry.startTime, duration: entry.duration })))).observe({ type: 'longtask', buffered: true })
      const original = window.fetch.bind(window)
      window.fetch = (...args) => {
        const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href)
        const api = url.origin === location.origin && url.pathname.startsWith('/api/')
        const current = probe.active
        if (current?.kind === 'search' && current.started === null && url.pathname.endsWith('/search') && url.searchParams.get('q') === current.expected) current.started = performance.now()
        if (api) { probe.pending++; probe.requests++ }
        return original(...args).finally(() => { if (api) probe.pending--; probe.check() })
      }
      for (const type of ['click', 'change']) document.addEventListener(type, event => {
        const current = probe.active
        if (current && current.kind !== 'search' && current.started === null && event.target instanceof Element && event.target.closest(current.selector)) current.started = performance.now()
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
      await Promise.all(responseTasks)
      const valid = latestSummary?.clusters === 20 && latestSummary?.nodes === 200 && latestSummary?.namespaces === 2000
        && latestSummary?.pods === 100000 && latestUser?.administrator === true
      if (!valid) throw new Error('AdminDatasetContract')
      await page.getByRole('button', { name: '운영 관측', exact: true }).waitFor()
      await page.getByLabel('자동 갱신').uncheck()
      stage = 'exploration'
      samples.expandCold.push(await measure(page, 'expand', 'node-001', 'button[aria-label="cluster-01 펼치기"]', () => page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()))
      samples.collapse.push(await measure(page, 'collapse', 'node-001', 'button[aria-label="cluster-01 접기"]', () => page.getByRole('button', { name: 'cluster-01 접기', exact: true }).click()))
      samples.expandWarm.push(await measure(page, 'expand', 'node-001', 'button[aria-label="cluster-01 펼치기"]', () => page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()))
      samples.search.push(await measure(page, 'search', 'worker-pod-000001', '', () => page.locator('.filters input').fill('worker-pod-000001')))
      await reset(page)
      samples.filter.push(await measure(page, 'filter', '오류', '.filters select:last-of-type', () => page.locator('.filters select').nth(1).selectOption('ERROR')))
      await reset(page)
      samples.refreshReadOnly.push(await measure(page, 'refresh', '', '.refresh-button', () => page.getByRole('button', { name: '새로고침', exact: true }).click()))
      if (invalidApi) throw new Error('ApiFailure')
      checks.push({ iteration, administrator: true, counts: latestSummary, invalidApi })
    } catch (error) {
      // Error messages/call logs can contain credential form values or callback URLs.
      errors.push({ iteration, stage, category: error.name === 'TimeoutError' ? 'TimeoutError' : 'ValidationOrBrowserError' })
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
      runtime: 'auth-compose-nginx', serverAlreadyRunning: true, account: 'ADMIN', dataset: { clusters: 20, nodes: 200, namespaces: 2000, pods: 100000 } },
    protocol: { loginSubmit: 'automated submit through real PKCE to ready; excludes human typing; includes automation overhead',
      ssoNavigation: 'navigation through existing SSO cookie redirect, code exchange and ready; includes automation overhead',
      callbackReady: 'final callback document navigation start to root, first Pod page, counts and ADMIN identity plus two animation frames; not complete SSO navigation',
      refreshReadOnly: 'real request group and DOM ready; does not mutate DB or prove changed-value propagation',
      apiSummary: 'browser request-to-finished timings including queue/network/body; not pure SQL or JWT verification time' },
    summary, measurements: samples, apiSummary, checks, details, errors,
  }
  const output = new URL('../.validation/performance/', import.meta.url)
  await mkdir(output, { recursive: true })
  await writeFile(new URL(`${label}.json`, output), JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' })
  process.stdout.write(JSON.stringify({ summary, apiSummary, verifiedIterations: checks.length, errors }, null, 2) + '\n')
  if (errors.length || checks.length !== repetitions || Object.values(summary).some(item => item.count !== repetitions || item.overTarget > 0)) process.exitCode = 1
} catch {
  process.stderr.write('Authentication performance setup failed; sensitive diagnostics were not retained.\n')
  process.exitCode = 1
} finally {
  await browser?.close()
}
