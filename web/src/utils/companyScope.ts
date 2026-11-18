/** API methods start their HTTP request synchronously; each request captures this value. */
let companyScope: string | undefined
export function currentCompanyScope(): string | undefined { return companyScope }
export function withCompanyScope<T>(companyId: string | undefined, run: () => T): T {
  const previous = companyScope
  companyScope = companyId
  try { return run() } finally { companyScope = previous }
}
/** Bind a page's loaded document/form, never the user's mutable default company. */
export function bindCompanyApi<T extends object>(api: T, companyId: () => string | null | undefined): T {
  return new Proxy(api, {
    get(target, key, receiver) {
      const value: unknown = Reflect.get(target, key, receiver)
      if (typeof value !== 'function') return value
      return (...args: unknown[]) => withCompanyScope(companyId() ?? undefined, () => Reflect.apply(value, target, args))
    },
  })
}
