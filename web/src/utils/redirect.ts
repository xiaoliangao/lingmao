function invalidRedirectText(value: string): boolean {
  return [...value].some((char) => char === '\\' || char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127)
}
/** Only an internal absolute path can be used after login. Query arrays are not paths. */
export function safeRedirect(value: unknown, origin: string): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return '/'
  try {
    const decoded = decodeURIComponent(value)
    if (invalidRedirectText(value) || invalidRedirectText(decoded) || decoded.startsWith('//')) return '/'
    const target = new URL(value, origin)
    if (target.origin !== origin || !['http:', 'https:'].includes(target.protocol)) return '/'
    return target.pathname + target.search + target.hash
  } catch {
    return '/'
  }
}
