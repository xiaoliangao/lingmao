/** A synchronous scope: API wrappers must start requests before returning their Promise. */
export interface ReadScope { signal: AbortSignal }
let scope: ReadScope | undefined
export function withReadScope<T>(value: ReadScope, run: () => T): T {
  const previous = scope
  scope = value
  try { return run() } finally { scope = previous }
}
export function currentReadScope(): ReadScope | undefined { return scope }

/** Aborts transport and rejects stale results even when an adapter ignores AbortSignal. */
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
