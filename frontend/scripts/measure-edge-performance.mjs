import { chromium } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import os from 'node:os'

const projectRoot = fileURLToPath(new URL('../../', import.meta.url))
const output = fileURLToPath(new URL('../.validation/performance/', import.meta.url))
const repetitions = Number(process.env.PERF_REPETITIONS || 20)
const label = process.env.PERF_LABEL || 'edge-check'
if (!Number.isInteger(repetitions) || repetitions < 3 || repetitions > 50 || !/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid measurement options')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, serviceWorkers: 'block' })
const page = await context.newPage(), session = await context.newCDPSession(page)
await session.send('Network.enable')
await session.send('Network.setCacheDisabled', { cacheDisabled: true })
await session.send('Emulation.setCPUThrottlingRate', { rate: 4 })
await session.send('Performance.enable')
const environment = { date: new Date().toISOString(), cpu: os.cpus()[0]?.model, physicalMemory: os.totalmem(),
  browser: browser.version(), node: process.version, cpuThrottle: 4, cacheDisabled: true, networkThrottle: 'none', headless: true,
  viewport: { width: 1440, height: 1000 }, repetitions, database: { clusters: 20, nodes: 200, namespaces: 2000, pods: 150000 } }
const records = [], errors = [], memory = [], invariants = []
let originalPod, lastMutation, restoration = 'not-needed'

function sql(query) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-U', 'monitoring', '-d', 'monitoring', '-v', 'ON_ERROR_STOP=1', '-q', '-A', '-t', '-c', query],
    { cwd: projectRoot, encoding: 'utf8' }).trim()
}
function validPod(snapshot) {
  if (!['NORMAL', 'WARNING', 'ERROR'].includes(snapshot.status) || !/^\d{4}-\d{2}-\d{2}T[\d:.]+(?:Z|[+-]\d{2}:\d{2})$/.test(snapshot.updatedAt)) throw new Error('Unexpected mock snapshot')
  return snapshot
}
function mutate(status) {
  lastMutation = validPod(JSON.parse(sql(`update infrastructure_pod set status='${status}', updated_at=clock_timestamp() where id=1 returning json_build_object('status',status,'updatedAt',updated_at);`)))
  return lastMutation
}
async function sampleMemory(stage) {
  const snapshot = await session.send('Performance.getMetrics')
  memory.push({ stage, jsHeapBytes: snapshot.metrics.find(metric => metric.name === 'JSHeapUsedSize')?.value,
    documentElements: await page.evaluate(() => document.getElementsByTagName('*').length),
    listRows: await page.locator('.resource-row').count(), treeRows: await page.locator('.tree-item').count(),
    window: await page.locator('.list-footer').textContent() })
}

await page.addInitScript(() => {
  const originalFetch = window.fetch.bind(window)
  const probe = window.__edgeProbe = {
    current: null,
    arm(kind, expected, mode) { this.current = { kind, expected, mode, started: null, elapsed: null, ending: false, response: null, requests: [], validationError: null } },
    check() {
      const current = this.current
      if (!current || current.started === null || current.elapsed !== null || current.ending || !current.response) return
      const rows = [...document.querySelectorAll('.resource-row')]
      const listReady = document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false'
      const ready = current.kind === 'search'
        ? listReady && (current.response.items.length === 0
          ? rows.length === 0 && document.querySelector('.empty-list') !== null
          : rows.length > 0 && rows[0].querySelector('strong')?.textContent === current.response.items[0].resource.name && rows.every(row => {
            const name = row.querySelector('strong')?.textContent || ''
            return name.toLowerCase().includes(current.expected.query.toLowerCase())
              && (!current.expected.type || row.querySelector('.type-cell')?.textContent.toUpperCase() === current.expected.type)
              && (!current.expected.status || row.querySelector('.badge')?.textContent === current.expected.status)
          }))
        : current.kind === 'tree'
          ? document.querySelector(`button[aria-label="${current.expected.name} 접기"]`)?.getAttribute('aria-expanded') === 'true'
            && [...document.querySelectorAll('.tree-name')].some(node => node.textContent === current.response.items[0]?.name)
            && !document.querySelector('.tree-panel [role="status"]')
          : current.kind === 'auto'
            ? listReady && document.querySelector('.refresh-button')?.textContent.trim() === '새로고침'
              && document.querySelector('.detail-panel > .badge')?.textContent === current.expected.status
              && rows.some(row => row.querySelector('strong')?.textContent === 'worker-pod-000001'
                && row.querySelector('.badge')?.textContent === current.expected.status
                && Date.parse(row.querySelector('time')?.getAttribute('datetime') || '') >= current.expected.updatedAt)
              && !document.querySelector('.tree-panel [role="status"]')
            : false
      if (!ready) return
      current.ending = true
      requestAnimationFrame(() => requestAnimationFrame(() => {
        if (this.current === current) current.elapsed = performance.now() - current.started
      }))
    },
  }
  window.fetch = async (...args) => {
    const current = probe.current
    const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href)
    const matching = current?.kind === 'search' ? url.pathname.endsWith('/search') && url.searchParams.get('q') === current.expected.query
      : current?.kind === 'tree' ? url.pathname === `/api/resources/CLUSTER/${current.expected.id}/children`
        : current?.kind === 'auto' ? url.pathname === '/api/resources' && url.searchParams.get('type') === 'POD' && !url.searchParams.has('cursor') : false
    if (matching && current.started === null && current.mode === 'request') current.started = performance.now()
    const request = current && current.started !== null && current.elapsed === null && url.pathname.startsWith('/api/resources')
      ? { path: url.pathname, started: performance.now(), ended: null, status: null } : null
    if (request) current.requests.push(request)
    const response = await originalFetch(...args)
    if (request) { request.ended = performance.now(); request.status = response.status }
    if (matching && probe.current === current) {
      const payload = await response.clone().json()
      current.response = payload
      if (!response.ok) current.validationError = `HTTP ${response.status}`
      if (current.kind === 'search' && payload.items?.some(item => !item.resource.name.toLowerCase().includes(current.expected.query.toLowerCase())
        || current.expected.type && item.resource.type !== current.expected.type
        || current.expected.statusCode && item.resource.status !== current.expected.statusCode)) current.validationError = 'Search response violates scope'
      probe.check()
    }
    return response
  }
  document.addEventListener('click', event => {
    const current = probe.current
    if (current?.kind === 'tree' && current.started === null && event.target instanceof Element
      && event.target.closest(`button[aria-label="${current.expected.name} 펼치기"]`)) current.started = performance.now()
  }, true)
  new MutationObserver(() => probe.check()).observe(document, { subtree: true, childList: true, attributes: true, characterData: true })
})

async function arm(kind, expected, mode = 'request') {
  await page.evaluate(({ kind, expected, mode }) => window.__edgeProbe.arm(kind, expected, mode), { kind, expected, mode })
}
async function finish(name, iteration, target) {
  await page.waitForFunction(() => window.__edgeProbe.current?.elapsed !== null, null, { timeout: 20000 })
  const result = await page.evaluate(() => window.__edgeProbe.current)
  if (result.validationError || result.requests.some(request => request.status !== 200)) throw new Error(result.validationError || 'Request failed')
  records.push({ name, iteration, target, elapsed: result.elapsed, requestCount: result.requests.length,
    serverResponseRows: result.response.items?.length, requests: result.requests,
    detectionDelay: result.kind === 'auto' ? result.started - result.expected.committed : undefined })
  await page.evaluate(() => { window.__edgeProbe.current = null })
}
async function waitList() {
  await page.waitForFunction(() => document.querySelector('.list-body')?.getAttribute('aria-busy') === 'false')
}
async function globalPods() {
  await page.getByRole('button', { name: '전체 Pod', exact: true }).click()
  await waitList()
}
async function search(query, type, statusCode, iteration, name) {
  await globalPods()
  await page.locator('.filters select').nth(1).selectOption(statusCode || '')
  await waitList()
  if (type === '') {
    await page.locator('.filters input').fill('scope-primer')
    await page.locator('.filters select').nth(0).selectOption('')
    await waitList()
  }
  const status = statusCode === 'ERROR' ? '오류' : ''
  await arm('search', { query, type, status, statusCode })
  await page.locator('.filters input').fill(query)
  await finish(name, iteration, 300)
}

try {
  const summary = await (await page.request.get('http://127.0.0.1:5174/api/resources/summary')).json()
  if (JSON.stringify(summary) !== JSON.stringify(environment.database)) throw new Error('Expected isolated 100k mock database')
  originalPod = validPod(JSON.parse(sql("select json_build_object('status',status,'updatedAt',updated_at) from infrastructure_pod where id=1;")))
  await page.goto('http://127.0.0.1:5174/')
  await page.locator('.resource-row').first().waitFor()
  await page.getByLabel('자동 갱신').uncheck()
  for (let iteration = 0; iteration < repetitions; iteration++) {
    for (const scenario of [
      { query: 'w', type: 'POD', name: 'one-character-common' },
      { query: 'z', type: 'POD', name: 'one-character-missing' },
      { query: 'pod', type: 'POD', name: 'common-substring' },
      { query: '0', type: '', name: 'all-types-common' },
      { query: 'node', type: '', status: 'ERROR', name: 'all-types-status' },
    ]) {
      try { await search(scenario.query, scenario.type, scenario.status, iteration, scenario.name) }
      catch (error) { errors.push({ iteration, scenario: scenario.name, message: String(error) }); await page.evaluate(() => { window.__edgeProbe.current = null }) }
    }
    process.stdout.write(`Search edges ${iteration + 1}/${repetitions}\n`)
  }
  await sampleMemory('after-searches')
  await globalPods()
  for (let iteration = 0; iteration < repetitions; iteration++) {
    await page.getByRole('button', { name: '처음', exact: true }).click()
    await page.waitForFunction(() => !document.querySelector('.tree-panel [role="status"]'))
    for (let cluster = 1; cluster <= 8; cluster++) {
      const name = `cluster-${String(cluster).padStart(2, '0')}`
      await page.locator('.tree-panel .virtual-viewport').evaluate((element, index) => { element.scrollTop = (index - 1) * 11 * 44 }, cluster)
      await page.getByRole('button', { name: `${name} 펼치기`, exact: true }).waitFor()
      await arm('tree', { name, id: cluster }, 'event')
      await page.getByRole('button', { name: `${name} 펼치기`, exact: true }).click()
      try { await finish('eight-parents-expand', iteration, 100) }
      catch (error) { errors.push({ iteration, scenario: name, message: String(error) }); await page.evaluate(() => { window.__edgeProbe.current = null }) }
    }
    process.stdout.write(`Eight-parent tree ${iteration + 1}/${repetitions}\n`)
  }
  await page.locator('.tree-panel .virtual-viewport').evaluate(element => { element.scrollTop = 8 * 11 * 44 })
  await page.getByRole('button', { name: 'cluster-09 펼치기', exact: true }).click()
  await page.waitForFunction(() => !document.querySelector('.tree-panel [role="status"]'))
  await page.locator('.tree-panel .virtual-viewport').evaluate(element => { element.scrollTop = 0 })
  await page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).waitFor()
  invariants.push({ name: 'ninth-parent-evicts-oldest-expansion', passed: await page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).getAttribute('aria-expanded') === 'false' })
  for (let index = 0; index < 4; index++) { await page.getByRole('button', { name: '다음 100개', exact: true }).click(); await waitList() }
  await page.locator('.resource-row').filter({ hasText: 'worker-pod-000001' }).click()
  await page.locator('.detail-panel h3').waitFor()
  await sampleMemory('eight-parents-500-rows-detail')
  for (let iteration = 0; iteration < repetitions; iteration++) {
    const visible = await page.locator('.detail-panel > .badge').textContent()
    const next = visible === '오류' ? 'NORMAL' : 'ERROR'
    const mutation = mutate(next)
    const committed = await page.evaluate(() => performance.now())
    await arm('auto', { status: next === 'ERROR' ? '오류' : '정상', updatedAt: Date.parse(mutation.updatedAt), committed })
    await page.getByLabel('자동 갱신').check()
    try { await finish('automatic-refresh-max-window', iteration, 200) }
    catch (error) { errors.push({ iteration, scenario: 'automatic-refresh', message: String(error) }); await page.evaluate(() => { window.__edgeProbe.current = null }) }
    await page.getByLabel('자동 갱신').uncheck()
    process.stdout.write(`Automatic refresh ${iteration + 1}/${repetitions}\n`)
  }
  await sampleMemory('after-automatic-refreshes')
} catch (error) {
  errors.push({ scenario: 'measurement-setup', message: String(error) })
} finally {
  if (originalPod && lastMutation) {
    try {
      const restored = sql(`update infrastructure_pod set status='${originalPod.status}', updated_at='${originalPod.updatedAt}'::timestamptz where id=1 and updated_at='${lastMutation.updatedAt}'::timestamptz returning id;`)
      restoration = restored === '1' ? 'restored-if-unchanged' : 'skipped-because-data-changed'
    } catch (error) { restoration = 'failed'; errors.push({ scenario: 'mock-restoration', message: String(error) }) }
  }
  const summary = Object.fromEntries([...new Set(records.map(record => record.name))].map(name => {
    const values = records.filter(record => record.name === name), sorted = values.map(record => record.elapsed).sort((left, right) => left - right), middle = Math.floor(sorted.length / 2)
    return [name, { count: sorted.length, median: sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2,
      p95: sorted[Math.ceil(sorted.length * .95) - 1], max: sorted.at(-1), target: values[0].target,
      overTarget: values.filter(record => record.elapsed > record.target).length,
      requestCounts: [...new Set(values.map(record => record.requestCount))] }]
  }))
  const report = { environment, summary, records, errors, invariants, memory, restoration, taskManager: { verified: false, reason: 'This headless harness does not inspect Chrome Task Manager; native UI verification is separate' } }
  await writeFile(`${output}/${label}.json`, JSON.stringify(report, null, 2))
  process.stdout.write(JSON.stringify({ summary, errors, invariants, memory, restoration }, null, 2) + '\n')
  await browser.close()
  if (errors.length || invariants.some(item => !item.passed)) process.exitCode = 1
}
