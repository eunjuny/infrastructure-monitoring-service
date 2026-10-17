import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

// Real local Keycloak only. Never retain passwords/tokens in traces, screenshots or storageState.
test.use({ trace: 'off', screenshot: 'off', video: 'off', locale: 'ko-KR' })
test.setTimeout(60000)
type Account = { username: string; password: string }
const baseURL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5176'
const keycloakURL = process.env.E2E_KEYCLOAK_URL || 'http://127.0.0.1:8183'
function credentials(key: string): Account {
  const path = process.env.E2E_AUTH_CI === '1' ? '../../.local/auth-ci/credentials.json' : '../../.local/auth/credentials.json'
  return JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8')).users[key]
}
async function login(page: Page, key: string) {
  const account = credentials(key)
  const me = page.waitForResponse(response => response.url().endsWith('/api/auth/me') && response.status() === 200)
  await page.goto(baseURL)
  await page.locator('#username').waitFor()
  const url = new URL(page.url())
  expect(url.origin).toBe(keycloakURL)
  expect(url.searchParams.get('code_challenge_method')).toBe('S256')
  expect(url.searchParams.get('response_type')).toBe('code')
  await page.locator('#username').fill(account.username)
  await page.locator('#password').fill(account.password)
  await page.locator('#kc-login').click()
  const response = await me
  const authorization = await response.request().headerValue('authorization')
  expect(Boolean(authorization)).toBe(true)
  await expect(page.getByRole('heading', { name: '인프라 리소스 모니터링' })).toBeVisible()
  await expect(page.locator('.session-info')).toContainText(account.username)
  return authorization!
}

test('administrator sees all resources and operations', async ({ page }) => {
  const authorization = await login(page, 'admin')
  const response = await page.request.get('/api/resources/summary', { headers: { Authorization: authorization } })
  expect(await response.json()).toEqual({ clusters: 20, nodes: 200, namespaces: 2000, pods: 150000 })
  await expect(page.getByRole('button', { name: '운영 관측', exact: true })).toBeVisible()
  expect((await page.request.get('/api/resources/POD/1', { headers: { Authorization: authorization } })).status()).toBe(200)
  expect((await page.request.get('/api/resources/POD/50001', { headers: { Authorization: authorization } })).status()).toBe(200)
  expect((await page.request.get('/api/operations/metrics', { headers: { Authorization: authorization } })).status()).toBe(200)
  await expect.poll(async () => (await (await page.request.get('/api/operations/alerts', { headers: { Authorization: authorization } })).json()).available,
    { timeout: 30000 }).toBe(true)
  await page.getByRole('button', { name: '운영 관측', exact: true }).click()
  await expect(page.locator('.alert-rules > li')).toHaveCount(3)
})

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`resource diagnostics show real scoped data at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await login(page, 'admin')
    await page.getByLabel('자동 갱신').uncheck()
    for (const type of ['Cluster', 'Node', 'Namespace', 'Pod']) {
      await page.getByRole('button', { name: `${type} 전체 목록 보기`, exact: true }).click()
      const row = page.locator('.resource-row').first()
      const firstName = type === 'Cluster' ? 'cluster-01' : type === 'Node' ? 'node-001' : type === 'Namespace' ? 'namespace-0001' : 'worker-pod-000001'
      await expect(row).toContainText(firstName)
      const received = page.waitForResponse(response => new URL(response.url()).pathname === `/api/resources/${type.toUpperCase()}/1` && response.status() === 200)
      await row.click()
      const detail = await (await received).json()
      const diagnostics = page.locator('.resource-diagnostics')
      await expect(diagnostics).toBeVisible()
      await expect(diagnostics).toContainText('Mock')
      const data = detail.attributes
      if (type === 'Cluster') {
        await expect(diagnostics).toContainText(`${data.readyNodeCount} / ${data.nodeCount}`)
        await expect(diagnostics).toContainText(`${data.problemPodCount} / 10000`)
      } else if (type === 'Node') {
        await expect(diagnostics).toContainText(data.ready ? 'Ready' : 'NotReady')
        await expect(diagnostics).toContainText(`${data.cpuCapacityCores} 코어`)
        await expect(diagnostics).toContainText(`${data.memoryCapacityMiB / 1024} GiB`)
      } else if (type === 'Namespace') {
        expect(data.podCount).toBe(100)
        await expect(diagnostics.locator('.diagnostic-summary dd').nth(1)).toHaveText(String(data.errorPodCount))
        await expect(diagnostics.locator('.diagnostic-summary dd').nth(2)).toHaveText(String(data.notReadyPodCount))
      } else {
        await expect(diagnostics).toContainText(data.phase)
        await expect(diagnostics).toContainText(`${data.readyContainerCount} / ${data.containerCount}`)
        await expect(diagnostics).toContainText(data.image)
        await expect(diagnostics).toContainText(data.workloadName)
        await page.screenshot({ path: `.validation/screenshots/resource-diagnostics-pod-${viewport.width}.png`, fullPage: true })
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
  })
}

test('administrator can persist a disabled recipient without SMTP and restore it', async ({ page }) => {
  const authorization = await login(page, 'admin')
  const headers = { Authorization: authorization }
  const response = await page.request.get('/api/operations/alert-email', { headers })
  expect(response.status()).toBe(200)
  const before = await response.json()
  expect(Object.keys(before).sort()).toEqual(['available', 'enabled', 'recipient', 'smtpConfigured'])
  await page.getByRole('button', { name: '운영 관측', exact: true }).click()
  await page.getByRole('button', { name: '관리자 메일', exact: true }).click()
  await expect(page.getByRole('heading', { name: '관리자 경보 메일' })).toBeVisible()
  expect((await page.getByLabel('발생·해소 알림 메일 받기').boundingBox())!.width).toBeLessThanOrEqual(20)
  if (!before.smtpConfigured && !before.enabled) {
    try {
      await expect(page.getByLabel('발생·해소 알림 메일 받기')).toBeDisabled()
      await page.getByLabel('수신 메일 주소').fill('admin@example.invalid')
      await page.getByRole('button', { name: '메일 설정 저장' }).click()
      await expect(page.getByText('설정이 저장되고 Alertmanager에 적용됐습니다.')).toBeVisible()
      const saved = await (await page.request.get('/api/operations/alert-email', { headers })).json()
      expect(saved.recipient).toBe('admin@example.invalid')
      expect(saved.enabled).toBe(false)
      expect((await page.request.put('/api/operations/alert-email', { headers,
        data: { recipient: 'admin@example.invalid', enabled: true } })).status()).toBe(400)
    } finally {
      const restored = await page.request.put('/api/operations/alert-email', { headers,
        data: { recipient: before.recipient, enabled: before.enabled } })
      expect(restored.status()).toBe(200)
    }
  }
})

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`submission console keeps real data, compact guidance and disclosures at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await login(page, 'admin')
    await expect(page.locator('.cards')).toContainText('150,000')
    await expect(page.locator('.window-statuses')).toContainText('조회된 100개 기준')
    await expect(page.locator('.console-footer')).toContainText('Mock 데이터 · 읽기 전용')
    await expect(page.getByText('서버에서 ID 순서대로 조회합니다.', { exact: true })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('button', { name: '운영 관측', exact: true }).click()
    await expect(page.locator('dl[aria-label="서비스 운영 지표"] > div')).toHaveCount(7)
    await expect(page.locator('dl[aria-label="서비스 운영 지표"]')).toContainText('수집 성공')
    await expect(page.locator('.simulator-metrics')).not.toHaveAttribute('open')
    await page.locator('.simulator-metrics > summary').focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('.simulator-metrics')).toHaveAttribute('open', '')
    await expect(page.locator('dl[aria-label="Mock 시뮬레이터 지표"] > div')).toHaveCount(3)
    await page.locator('.simulator-metrics > summary').press('Enter')
    await expect(page.locator('.simulator-metrics')).not.toHaveAttribute('open')
    await expect(page.locator('.alert-rules > li')).toHaveCount(3)
    await expect(page.locator('.operations-guidance')).not.toHaveAttribute('open')
    await expect(page.getByText('Mock Node 사용률과 다른 데이터입니다.', { exact: false })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

for (const [key, own, other] of [['a', 1, 50001], ['b', 50001, 1]] as const) {
  test(`user ${key} sees only assigned clusters including detail and history`, async ({ page }) => {
    const authorization = await login(page, key)
    const headers = { Authorization: authorization }
    const response = await page.request.get('/api/resources/summary', { headers })
    expect(await response.json()).toEqual({ clusters: 10, nodes: 100, namespaces: 1000, pods: key === 'a' ? 100000 : 50000 })
    expect((await page.request.get('/api/resources/POD/150000', { headers })).status()).toBe(key === 'a' ? 200 : 404)
    await expect(page.getByRole('button', { name: '운영 관측', exact: true })).toHaveCount(0)
    expect((await page.request.get(`/api/resources/POD/${own}`, { headers })).status()).toBe(200)
    for (const suffix of ['', '/path', '/events']) {
      expect((await page.request.get(`/api/resources/POD/${other}${suffix}`, { headers })).status()).toBe(404)
    }
    expect((await page.request.get('/api/operations/metrics', { headers })).status()).toBe(403)
    expect((await page.request.get('/api/operations/alerts', { headers })).status()).toBe(403)
    expect((await page.request.get('/api/operations/alert-email', { headers })).status()).toBe(403)
    expect((await page.request.put('/api/operations/alert-email', { headers,
      data: { recipient: 'admin@example.invalid', enabled: false } })).status()).toBe(403)
    expect((await page.request.get('/api/resources/summary')).status()).toBe(401)
    const persistedToken = await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)]
      .some(value => /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\./.test(value)))
    expect(persistedToken).toBe(false)
  })
}

test('cursor cannot be reused across actual Keycloak users', async ({ browser }) => {
  const a = await browser.newPage(), b = await browser.newPage()
  try {
    const authorizationA = await login(a, 'a'), authorizationB = await login(b, 'b')
    const first = await a.request.get('/api/resources?type=POD&limit=1', { headers: { Authorization: authorizationA } })
    const cursor = (await first.json()).nextCursor
    const response = await b.request.get('/api/resources?type=POD&limit=1&cursor=' + encodeURIComponent(cursor),
      { headers: { Authorization: authorizationB } })
    expect(response.status()).toBe(400)
  } finally { await a.close(); await b.close() }
})

test('logout returns to login without retaining the resource screen', async ({ page }) => {
  await login(page, 'a')
  await page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(page.locator('#username')).toBeVisible()
  expect(new URL(page.url()).origin).toBe(keycloakURL)
})

test('existing SSO session reloads without a second top-level application navigation', async ({ page }) => {
  await login(page, 'admin')
  let redirects = 0
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame() && new URL(frame.url()).origin === keycloakURL) redirects++
  })
  await page.reload()
  await expect(page.locator('.session-info')).toContainText('전체 관리자')
  await expect(page.locator('.cards')).toContainText('150,000')
  expect(redirects).toBe(0)
})

for (const viewport of [{ width: 1440, height: 1000 }, { width: 320, height: 740 }]) {
  test(`branded login preserves accessible inputs and layout at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await page.goto(baseURL)
    await expect(page.locator('#username')).toBeVisible()
    await expect(page.locator('link[href*="infra-monitor.css"]')).toHaveCount(1)
    await expect(page.locator('#kc-header-wrapper')).toHaveText('Infra Monitor')
    await expect(page.locator('#kc-page-title')).toHaveText('서비스 로그인')
    await expect(page.locator('#kc-login')).toHaveCSS('background-color', 'rgb(40, 100, 220)')
    await expect(page.locator('#username')).toHaveCSS('border-radius', '6px')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.locator('#username').focus()
    await expect(page.locator('#username')).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.locator('#password')).toBeFocused()
    // Only an empty login form is captured; credentials/tokens are never present in these images.
    await page.screenshot({ path: `.validation/screenshots/keycloak-login-${viewport.width}.png`, fullPage: true })
  })
}

test('branded login keeps server errors visible and supports keyboard resubmission', async ({ page }) => {
  const account = credentials('a')
  await page.goto(baseURL)
  await page.locator('#username').fill(account.username)
  await page.locator('#password').fill('invalid-test-input')
  await page.locator('#password').press('Enter')
  await expect(page.locator('#input-error')).toBeVisible()
  await expect(page.locator('#password')).toHaveAttribute('aria-invalid', 'true')
  const accepted = page.waitForResponse(response => response.url().endsWith('/api/auth/me') && response.status() === 200)
  await page.locator('#password').fill(account.password)
  await page.locator('#password').press('Enter')
  await accepted
  await expect(page.locator('.session-info')).toContainText(account.username)
})
