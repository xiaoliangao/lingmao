/** One shared asynchronous operation, cleared on both success and rejection. */
export function singleFlight<T, Args extends unknown[]>(run: (...args: Args) => Promise<T>): (...args: Args) => Promise<T> {
  let pending: Promise<T> | undefined
  return (...args) => {
    if (!pending) {
      pending = Promise.resolve().then(() => run(...args)).finally(() => { pending = undefined })
    }
    return pending
  }
}
