import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'
import { ApiError, enableCookieSessionRefresh, http } from '@/utils/request'
import { getToken, onSessionChanged, sessionChanged, sessionEpoch, sessionIdentity, setToken, startSessionSync } from '@/utils/token'
import { AUTH_COOKIE_LOCK } from '@/utils/authSession'

// Match production main.ts: initialize the real cookie-session handler and cross-tab sync.
// This probe uses the production single-flight transport, token store and login/logout actions.
enableCookieSessionRefresh()
const stopSync = startSessionSync()
setActivePinia(createPinia())
const user = useUserStore()
const protectedPath = '/api/companies/options'
const invalidAccess = 'a3-deliberately-invalid-access'
type Outcome = { ok: boolean; code: number }
const failed = (cause: unknown): Outcome => ({ ok: false, code: cause instanceof ApiError ? cause.code : -1 })
let epoch = ''
let identity = ''
let retainedAccess = ''
let notifications = 0
let releaseLock: (() => void) | undefined
let heldLock: Promise<unknown> | undefined
let burst: Outcome[] | null = null
const stopChanged = onSessionChanged(() => { notifications++ })

async function read(tag: string, index = 0): Promise<Outcome> {
  try {
    const value = await http.get<unknown>(protectedPath, { _a3_session: tag, index }, { silent: true })
    return { ok: Array.isArray(value), code: Array.isArray(value) ? 0 : -2 }
  } catch (cause) { return failed(cause) }
}
async function rawCheck(path: string, init: RequestInit): Promise<Outcome & { status: number }> {
  const response = await fetch(path, { ...init, credentials: 'include', cache: 'no-store' })
  const body: unknown = await response.json()
  const code = body && typeof body === 'object' && 'code' in body && typeof body.code === 'number' ? body.code : -2
  return { ok: response.ok && code === 0, code, status: response.status }
}
const probe = {
  ready: true,
  webLocks: typeof navigator.locks?.request === 'function',
  async login(credentials: { username: string; password: string }): Promise<Outcome> {
    try {
      await user.login(credentials.username, credentials.password)
      await user.fetchMe()
      return { ok: !!getToken() && sessionIdentity().startsWith('user:'), code: 0 }
    } catch (cause) { return failed(cause) }
  },
  markSession() { epoch = sessionEpoch(); identity = sessionIdentity(); retainedAccess = getToken() },
  state() {
    return {
      stale: sessionChanged(), hasAccess: !!getToken(), invalid: getToken() === invalidAccess,
      sameEpoch: sessionEpoch() === epoch, sameIdentity: sessionIdentity() === identity,
    }
  },
  notifications: () => notifications,
  invalidateAccess() { setToken(invalidAccess, true) },
  retainAccess() { retainedAccess = getToken() },
  async holdRefresh() {
    const acquired = new Promise<void>((resolve) => {
      heldLock = navigator.locks.request(AUTH_COOKIE_LOCK, { mode: 'exclusive' }, () =>
        new Promise<void>((release) => { releaseLock = release; resolve() }))
    })
    await acquired
  },
  async releaseRefresh() { releaseLock?.(); releaseLock = undefined; await heldLock; heldLock = undefined },
  async refreshWaiters() {
    const locks = await navigator.locks.query()
    return locks.pending?.filter((lock) => lock.name === AUTH_COOKIE_LOCK).length ?? 0
  },
  startBurst(tag: string) {
    burst = null
    void Promise.all(Array.from({ length: 8 }, (_, index) => read(tag, index))).then((results) => { burst = results })
  },
  burstResult: () => burst,
  read,
  async logout(): Promise<Outcome> {
    try { await user.logout(); return { ok: !getToken(), code: 0 } } catch (cause) { return failed(cause) }
  },
  async revokedAccess(): Promise<Outcome & { status: number }> {
    try {
      return await rawCheck(`${protectedPath}?_a3_session=revoked-access`, { headers: { Authorization: `Bearer ${retainedAccess}` } })
    } finally { retainedAccess = '' }
  },
  revokedRefresh: () => rawCheck('/api/auth/refresh?_a3_session=revoked-cookie', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }, body: '{}',
  }),
  cookiesInvisibleToScript() {
    // Check at the cookies' own path, so Path=/api/auth cannot masquerade as HttpOnly protection.
    const original = location.href
    try {
      history.replaceState(null, '', '/api/auth/refresh')
      return !document.cookie.split(';').some((entry) => /^\s*scm_(refresh|browser)=/.test(entry))
    } finally { history.replaceState(null, '', original) }
  },
}
;(window as unknown as { __realSession: typeof probe }).__realSession = probe
window.addEventListener('pagehide', () => { releaseLock?.(); stopSync(); stopChanged() }, { once: true })
