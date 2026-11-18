/** 同步作用域：接口函数要在返回 Promise 之前发出请求。 */
export interface ReadScope { signal: AbortSignal }
let scope: ReadScope | undefined
export function withReadScope<T>(value: ReadScope, run: () => T): T {
  const previous = scope
  scope = value
  try { return run() } finally { scope = previous }
}
export function currentReadScope(): ReadScope | undefined { return scope }

/** 中止请求，并丢掉过期的结果（即使适配器不理 AbortSignal）。 */
export function createLatestRequest() {
  let controller: AbortController | undefined
  let sequence = 0
  return {
    begin() {
      controller?.abort()
      controller = new AbortController()
      const own = controller
      const generation = ++sequence
      return {
        signal: own.signal,
        current: () => generation === sequence && !own.signal.aborted,
        run: <T>(request: () => T): T => withReadScope({ signal: own.signal }, request),
      }
    },
    cancel() { ++sequence; controller?.abort() },
  }
}
