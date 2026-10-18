import { reactive } from 'vue'

export const session = reactive({ enabled: false, identityReady: false, identityError: false, name: '', administrator: false, clusters: [] as number[] })
let provider: (() => Promise<string>) | undefined
let signOut: (() => Promise<void>) | undefined
export function configureSession(token: () => Promise<string>, logout: () => Promise<void>) {
  provider = token
  signOut = logout
}
export function tokenProvider() { return provider }
export async function logoutSession() {
  const logout = signOut
  provider = undefined
  signOut = undefined
  Object.assign(session, { identityReady: false, identityError: false, name: '', administrator: false, clusters: [] })
  await logout?.()
}
