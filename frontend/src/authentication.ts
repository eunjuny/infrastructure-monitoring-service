import Keycloak from 'keycloak-js'
import { api, type AuthenticationConfiguration } from './api'
import { configureSession, session } from './session'

export async function initializeAuthentication(config: AuthenticationConfiguration) {
  if (!config.enabled || !config.issuer.endsWith('/realms/infrastructure-monitoring')) {
    throw new Error('인증 서버 설정이 일치하지 않습니다.')
  }
  const issuer = new URL(config.issuer)
  if (issuer.protocol !== 'https:' && !(issuer.protocol === 'http:' && issuer.hostname === '127.0.0.1')) {
    throw new Error('인증 서버는 HTTPS 또는 로컬 개발 주소여야 합니다.')
  }
  const client = new Keycloak({ url: config.issuer.split('/realms/')[0], realm: 'infrastructure-monitoring', clientId: config.clientId })
  const ready = await client.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false, redirectUri: window.location.origin + '/' })
  if (!ready) throw new Error('로그인이 필요합니다.')
  let refreshing: Promise<boolean> | undefined
  configureSession(async () => {
    refreshing ??= client.updateToken(30).finally(() => { refreshing = undefined })
    await refreshing
    if (!client.token) throw new Error('로그인이 만료되었습니다. 다시 접속해 주세요.')
    return client.token
  }, () => client.logout({ redirectUri: window.location.origin + '/' }))
  const user = await api.me()
  Object.assign(session, user, { enabled: true })
}
