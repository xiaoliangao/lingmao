/** randomUUID 仅安全上下文可用；交付包允许内网 HTTP，回退仍使用浏览器安全随机数。 */
function clientActionUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  // UUID v4 的版本位与变体位，其余 122 位保持随机。
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 未知结果的重试使用调用方保留的完整请求；不会读取此后的表单变化。 */
export function newClientAction<T extends object>(payload: T, uuid: string = clientActionUuid()): T & { clientActionId: string } {
  return { ...structuredClone(payload), clientActionId: uuid }
}
/** 网络失败/服务端失败可能发生在提交之后；业务校验错误表示本次未成功。 */
export function unknownActionResult(code: number | undefined): boolean {
  return code == null || code < 0 || (code >= 500 && code < 600)
}
