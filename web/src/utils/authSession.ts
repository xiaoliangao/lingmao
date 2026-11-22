export type SessionMode = 'cookie' | 'access-only'

/** 固定 Cookie 名共享一个同源锁，覆盖响应接收及本地 Token 发布，不能按旧 epoch 各锁各的。 */
export const AUTH_COOKIE_LOCK = 'lingmao:auth-cookie'

export function getSessionMode(): SessionMode {
  return typeof navigator !== 'undefined' && typeof navigator.locks?.request === 'function' ? 'cookie' : 'access-only'
}

/** HTTP/IP 等没有 Web Locks 的入口仍可登录，但对应响应必须遵守 access-only 的零 Set-Cookie 契约。 */
export async function withAuthSession<T>(action: (mode: SessionMode) => Promise<T>): Promise<T> {
  if (getSessionMode() === 'access-only') return action('access-only')
  return navigator.locks.request(AUTH_COOKIE_LOCK, { mode: 'exclusive' }, () => action('cookie'))
}
