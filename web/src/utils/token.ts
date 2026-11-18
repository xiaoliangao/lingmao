import { readStorage, writeStorage } from './safeStorage'
import { clientActionUuid } from './clientAction'

const KEY = 'lingmao-token'
interface Session { token: string; epoch: string; subject?: string }
function parse(raw: string | null): Session {
  if (!raw) return { token: '', epoch: '' }
  try {
    const value: unknown = JSON.parse(raw)
    if (value && typeof value === 'object' && 'token' in value && 'epoch' in value && typeof value.token === 'string' && typeof value.epoch === 'string') return { token: value.token, epoch: value.epoch, subject: 'subject' in value && typeof value.subject === 'string' ? value.subject : undefined }
  } catch { /* Before the upgrade the token itself was stored. */ }
  return { token: raw, epoch: `legacy:${raw.slice(-16)}` }
}
let session = parse(readStorage(KEY))
let stale = false
let channel: BroadcastChannel | undefined
const listeners = new Set<() => void>()
function receive(next: Session) {
  if (next.epoch === session.epoch) { session = next; return }
  if (!stale) { stale = true; for (const listener of listeners) listener() }
}
export function startSessionSync(): () => void {
  const storage = (event: StorageEvent) => { if (event.key === KEY) receive(parse(event.newValue)) }
  window.addEventListener('storage', storage)
  try {
    channel = new BroadcastChannel('lingmao-session')
    channel.onmessage = (event: MessageEvent<unknown>) => {
      if (typeof event.data === 'string') receive(parse(event.data))
    }
  } catch { /* The storage event and per-request check remain available. */ }
  return () => { window.removeEventListener('storage', storage); channel?.close(); channel = undefined }
}
export function onSessionChanged(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
/** 页面用自己的会话。别的页签不能悄悄改掉一次写操作的身份。 */
export function getToken(): string { return stale ? '' : session.token }
export function sessionEpoch(): string { return session.epoch }
/** 只用来区分本机存储归谁；权限一律由服务端判断。 */
export function sessionIdentity(): string { return session.subject ? `user:${session.subject}` : `session:${session.epoch}` }
export function setSessionSubject(subject: string) {
  session = { ...session, subject }
  writeStorage(KEY, JSON.stringify(session))
}
export function sessionChanged(): boolean {
  const stored = readStorage(KEY)
  if (stored !== null) receive(parse(stored))
  return stale
}
export function setToken(token: string, refresh = false) {
  session = { token, epoch: refresh && session.epoch ? session.epoch : clientActionUuid(), subject: refresh ? session.subject : undefined }
  stale = false
  const raw = JSON.stringify(session)
  writeStorage(KEY, raw)
  channel?.postMessage(raw)
}
export function clearToken() {
  session = { token: '', epoch: clientActionUuid() }
  stale = false
  const raw = JSON.stringify(session)
  writeStorage(KEY, raw)
  channel?.postMessage(raw)
}
