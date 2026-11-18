/** 同时只跑一个的异步操作，成功、失败都清掉。 */
export function singleFlight<T, Args extends unknown[]>(run: (...args: Args) => Promise<T>): (...args: Args) => Promise<T> {
  let pending: Promise<T> | undefined
  return (...args) => {
    if (!pending) {
      pending = Promise.resolve().then(() => run(...args)).finally(() => { pending = undefined })
    }
    return pending
  }
}
