import { test, expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

// Real local Keycloak only. Never retain passwords/tokens in traces, screenshots or storageState.
test.use({ trace: 'off', screenshot: 'off', video: 'off' })
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
  expect(await response.json()).toEqual({ clusters: 20, nodes: 200, namespaces: 2000, pods: 100000 })
  await expect(page.getByRole('button', { name: '운영 관측', exact: true })).toBeVisible()
  expect((await page.request.get('/api/resources/POD/1', { headers: { Authorization: authorization } })).status()).toBe(200)
  expect((await page.request.get('/api/resources/POD/50001', { headers: { Authorization: authorization } })).status()).toBe(200)
  expect((await page.request.get('/api/operations/metrics', { headers: { Authorization: authorization } })).status()).toBe(200)
  await expect.poll(async () => (await (await page.request.get('/api/operations/alerts', { headers: { Authorization: authorization } })).json()).available,
    { timeout: 30000 }).toBe(true)
  await page.getByRole('button', { name: '운영 관측', exact: true }).click()
  await expect(page.locator('.alert-rules > li')).toHaveCount(3)
})

for (const [key, own, other] of [['a', 1, 50001], ['b', 50001, 1]] as const) {
  test(`user ${key} sees only assigned clusters including detail and history`, async ({ page }) => {
    const authorization = await login(page, key)
    const headers = { Authorization: authorization }
    const response = await page.request.get('/api/resources/summary', { headers })
    expect(await response.json()).toEqual({ clusters: 10, nodes: 100, namespaces: 1000, pods: 50000 })
    await expect(page.getByRole('button', { name: '운영 관측', exact: true })).toHaveCount(0)
    expect((await page.request.get(`/api/resources/POD/${own}`, { headers })).status()).toBe(200)
    for (const suffix of ['', '/path', '/events']) {
      expect((await page.request.get(`/api/resources/POD/${other}${suffix}`, { headers })).status()).toBe(404)
    }
    expect((await page.request.get('/api/operations/metrics', { headers })).status()).toBe(403)
    expect((await page.request.get('/api/operations/alerts', { headers })).status()).toBe(403)
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
