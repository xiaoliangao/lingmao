function invalidRedirectText(value: string): boolean {
  return [...value].some((char) => char === '\\' || char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127)
}
/** 登录后跳回哪：只认本站的绝对路径（/ 开头，不是 // 开头，没有反斜杠和控制字符），别的一律回首页，防止被带去外站 */
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
