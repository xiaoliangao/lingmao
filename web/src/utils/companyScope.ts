/** 接口函数同步发出请求，每个请求在那一刻取这个值。 */
let companyScope: string | undefined
export function currentCompanyScope(): string | undefined { return companyScope }
export function withCompanyScope<T>(companyId: string | undefined, run: () => T): T {
  const previous = companyScope
  companyScope = companyId
  try { return run() } finally { companyScope = previous }
}
/** 绑定页面加载的单据、表单的主体，不用用户可变的默认主体。 */
export function bindCompanyApi<T extends object>(api: T, companyId: () => string | null | undefined): T {
  return new Proxy(api, {
    get(target, key, receiver) {
      const value: unknown = Reflect.get(target, key, receiver)
      if (typeof value !== 'function') return value
      return (...args: unknown[]) => withCompanyScope(companyId() ?? undefined, () => Reflect.apply(value, target, args))
    },
  })
}
