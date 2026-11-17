/** 未知结果的重试使用调用方保留的完整请求；不会读取此后的表单变化。 */
export function newClientAction<T extends object>(payload: T, uuid: string = crypto.randomUUID()): T & { clientActionId: string } {
  return { ...structuredClone(payload), clientActionId: uuid }
}
/** 网络失败/服务端失败可能发生在提交之后；业务校验错误表示本次未成功。 */
export function unknownActionResult(code: number | undefined): boolean {
  return code == null || code < 0 || (code >= 500 && code < 600)
}
