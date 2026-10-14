import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

test.beforeEach(async ({ page, request }) => {
  const response = await request.get('/api/resources/summary')
  expect(response.ok()).toBe(true)
  expect(await response.json()).toEqual({ clusters: 20, nodes: 200, namespaces: 2000, pods: 100000 })
  await page.goto('/')
  await expect(page.locator('.resource-row').first()).toBeVisible()
})

test('real seed counts, lazy hierarchy, parent list and typed details', async ({ page }) => {
  await expect(page.locator('.cards')).toContainText('100,000')
  await expect(page.locator('.tree-panel')).toContainText('cluster-01')
  await expect(page.locator('.tree-panel')).not.toContainText('node-001')
  await page.getByRole('button', { name: 'cluster-01 펼치기', exact: true }).click()
  await expect(page.locator('.tree-panel')).toContainText('node-001')
  await page.getByRole('button', { name: 'node-001 펼치기', exact: true }).click()
  await expect(page.locator('.tree-panel')).toContainText('namespace-0001')
  await page.getByRole('button', { name: 'namespace-0001 하위 목록 보기', exact: true }).click()
  await expect(page.locator('#list-heading')).toHaveText('namespace-0001 하위 목록')
  await page.locator('.resource-row').filter({ hasText: 'worker-pod-000001' }).click()
  await expect(page.locator('.detail-panel h3')).toHaveText('worker-pod-000001')
  await expect(page.locator('.attributes')).toContainText('재시작 횟수')
  await expect(page.locator('.breadcrumbs button')).toHaveCount(4)
  await page.locator('.breadcrumbs button').filter({ hasText: 'node-001' }).click()
  await expect(page.locator('.attributes')).toContainText('CPU 사용률 (%)')
  await expect(page.locator('.attributes')).toContainText('메모리 사용률 (%)')
})

test('database name search, full path reveal and combined status filter', async ({ page }) => {
  await page.locator('.filters input').fill('worker-pod-000001')
  await expect(page.locator('.resource-row')).toHaveCount(1)
  await expect(page.locator('.resource-row')).toContainText('cluster-01 / node-001 / namespace-0001 / worker-pod-000001')
  await page.locator('.resource-row').click()
  await page.getByRole('button', { name: '계층에서 위치 보기', exact: true }).click()
  await expect(page.locator('.tree-item.selected')).toContainText('worker-pod-000001')
  await page.locator('.filters input').fill('pod')
  await page.locator('.filters select').nth(1).selectOption('ERROR')
  await expect(page.locator('.resource-row').first()).toBeVisible()
  await expect.poll(async () => {
    const labels = await page.locator('.resource-row .badge').allTextContents()
    return labels.length > 0 && labels.every(label => label === '오류')
  }).toBe(true)
  const rows = await page.locator('.resource-row .badge').allTextContents()
  expect(rows.length).toBeGreaterThan(0)
  expect(rows.every(label => label === '오류')).toBe(true)
})

test('empty search and invalid requests return safe states', async ({ page, request }) => {
  await page.locator('.filters input').fill('not-existing-resource-unique')
  await expect(page.locator('.list-panel')).toContainText('조건에 맞는 리소스가 없습니다.')
  const invalid = await request.get('/api/resources?type=POD&limit=101')
  expect(invalid.status()).toBe(400)
  expect((await invalid.json()).code).toBe('INVALID_QUERY')
  const missing = await request.get('/api/resources/POD/999999999')
  expect(missing.status()).toBe(404)
})

test('sequential global Pod browsing keeps the 500-row cache and small DOM', async ({ page }) => {
  await page.getByLabel('자동 갱신').uncheck()
  for (let index = 0; index < 6; index++) {
    const response = page.waitForResponse(value => value.url().includes('/api/resources?') && value.url().includes('cursor=') && value.status() === 200)
    await page.getByRole('button', { name: '다음 100개', exact: true }).click()
    await response
    await expect(page.getByRole('button', { name: '다음 100개', exact: true })).toBeEnabled()
  }
  await expect(page.locator('.list-footer')).toContainText('201–700번째 · 보관 500개')
  expect(await page.locator('.resource-row').count()).toBeLessThanOrEqual(20)
  await page.getByRole('button', { name: '처음부터', exact: true }).click()
  await expect(page.locator('.list-footer')).toContainText('1–100번째 · 보관 100개')
})

test('committed DB changes appear automatically without losing selected detail or scroll', async ({ page, request }) => {
  await page.locator('.resource-row').filter({ hasText: 'worker-pod-000001' }).click()
  await expect(page.locator('.detail-panel h3')).toHaveText('worker-pod-000001')
  const snapshot = await (await request.get('/api/resources/POD/1')).json()
  const nextStatus = snapshot.resource.status === 'ERROR' ? 'NORMAL' : 'ERROR'
  const label = nextStatus === 'ERROR' ? '오류' : '정상'
  const viewport = page.locator('.list-panel .virtual-viewport')
  await viewport.evaluate(element => { element.scrollTop = 1200 })
  const scroll = await viewport.evaluate(element => element.scrollTop)
  expect(scroll).toBe(1200)
  execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-U', 'monitoring', '-d', 'monitoring', '-v', 'ON_ERROR_STOP=1', '-c',
    `update infrastructure_pod set status='${nextStatus}', updated_at=clock_timestamp() where id=1;`],
    { cwd: fileURLToPath(new URL('../../', import.meta.url)), stdio: 'pipe' })
  await expect(page.locator('.detail-panel > .badge')).toHaveText(label)
  await expect(page.locator('.detail-panel h3')).toHaveText('worker-pod-000001')
  expect(await viewport.evaluate(element => element.scrollTop)).toBe(scroll)
  await page.screenshot({ path: '.validation/screenshots/monitoring-live.png', fullPage: true })
})

test('console shortcuts, metric meters and detail tabs work against real data', async ({ page }) => {
  await page.locator('.resource-navigation button').filter({ hasText: 'Node' }).click()
  await expect(page.locator('.resource-row').first()).toContainText('node-001')
  await page.locator('.resource-row').first().click()
  await expect(page.locator('meter')).toHaveCount(2)
  expect(await page.locator('meter').first().getAttribute('value')).not.toBeNull()
  await page.getByRole('tab', { name: '관계·경로', exact: true }).click()
  await expect(page.locator('#path-panel')).toBeVisible()
  await expect(page.locator('.relationship-cards button')).toHaveCount(2)
  await page.getByRole('tab', { name: '관계·경로', exact: true }).press('ArrowLeft')
  await expect(page.getByRole('tab', { name: '개요', exact: true })).toBeFocused()
  await page.screenshot({ path: '.validation/screenshots/console-node-detail.png', fullPage: true })
  await page.getByRole('button', { name: '상세 닫기', exact: true }).click()
  await expect(page.locator('.detail-panel')).toHaveCount(0)
  await page.keyboard.press('/')
  await expect(page.locator('.filters input')).toBeFocused()
  await page.screenshot({ path: '.validation/screenshots/console-desktop.png', fullPage: true })
})

test('mobile layout keeps navigation and detail usable without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.resource-navigation')).toBeVisible()
  await expect(page.locator('.tree-panel')).not.toBeVisible()
  await page.getByRole('button', { name: '계층 펼치기', exact: true }).click()
  await expect(page.locator('.tree-panel')).toBeVisible()
  await page.getByRole('button', { name: '계층 접기', exact: true }).click()
  const overflows = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflows).toBe(false)
  await page.locator('.resource-row').first().click()
  await expect(page.locator('.detail-panel h3')).toHaveText('worker-pod-000001')
  await page.getByRole('button', { name: '상세 닫기', exact: true }).click()
  await expect(page.locator('.detail-panel')).toHaveCount(0)
  await page.screenshot({ path: '.validation/screenshots/console-mobile.png', fullPage: true })
})
