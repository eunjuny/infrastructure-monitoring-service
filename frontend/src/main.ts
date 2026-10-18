import { createApp } from 'vue'
import App from './App.vue'
import { api } from './api'
import './style.css'

async function start() {
  let loadIdentity: (() => Promise<void>) | undefined
  if (import.meta.env.VITE_AUTH_ENABLED === 'true') {
    try {
      // Independent public config I/O can overlap the auth chunk download/parse.
      const [{ initializeAuthentication, loadAuthenticatedIdentity }, config] = await Promise.all([import('./authentication'), api.authConfig()])
      await initializeAuthentication(config)
      loadIdentity = loadAuthenticatedIdentity
    } catch {
      // Never mount the resource screen or fall back to local mode on auth failure.
      const root = document.getElementById('app')!
      const message = document.createElement('p')
      message.setAttribute('role', 'alert')
      message.textContent = '로그인을 완료하지 못했습니다. 인증 서버와 연결 상태를 확인해 주세요.'
      const retry = document.createElement('button')
      retry.textContent = '로그인 다시 시도'
      retry.onclick = () => window.location.reload()
      root.replaceChildren(message, retry)
      return
    }
  }
  createApp(App, {
    onInitialListSettled() {
      // Identity metadata must not compete with the first list request/paint.
      // Every resource API is still authorized by the server from its JWT.
      const load = loadIdentity
      loadIdentity = undefined
      if (load) void load().catch(() => undefined)
    },
  }).mount('#app')
}
void start()
