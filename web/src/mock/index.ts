import type { InternalAxiosRequestConfig } from 'axios'
import { installMock, type R } from '@/utils/request'

/**
 * 前端先做、后端还没写时用的假接口（docs/api/README.md §6）。只在 pnpm dev:mock 时加载（main.ts），正式打包不带。
 * 没写 mock 的请求照常发给后端：登录、菜单、基础资料、供应商、款式这些用真的。假数据里的单据指向款式 1、物料 1-4、供应商 1-4……
 * 开发库是空的就先灌 dev/seed-demo.sql（不灌的话点款号是"这个款式不存在"）；这里改了 id，那个文件跟着改。
 *
 * 每个模块一个文件 mock/<模块>.ts，照 docs/api 的接口和 types 里的类型写，数据放在模块自己的内存里（刷新就回到初始）：
 *
 *   import { mock, MockError, page } from './index'
 *   const orders: OrderRow[] = [...]
 *   mock('get', '/api/orders', ({ query }) => page(orders, query))
 *   mock('get', '/api/orders/:id', ({ params }) => orders.find((o) => o.id === Number(params.id)) ?? fail(404, '单据不存在'))
 *   mock('post', '/api/orders/:id/submit', ({ params, body }) => { …; return null })
 *   mock('post', '/api/orders/:id/unaudit', () => fail(1005, '有下游单据，不能反审核', [someDocRef]))
 *   mock('get', '/api/orders/export', () => new Blob(['单号,状态\n'], { type: 'text/csv' }))     下载：返回 Blob，文件名用调用方给的
 *
 * 后端写好一个模块，就删掉它的 mock 文件（接口对不上的地方，以后端为准改前端，或者改后端，看 docs/api 里怎么写的）
 */
export interface MockRequest {
  /** 路径里的 :id 这些 */
  params: Record<string, string>
  /** ?a=1&status=A&status=B：重复的参数是数组 */
  query: Record<string, string | string[]>
  /** POST、PUT 的请求体（JSON 已经解析好）；上传是 FormData */
  body: unknown
}

type Handler = (req: MockRequest) => unknown

/** 抛它 = 后端回了业务错误（code 不是 0）：1000 一行消息，1001/1003/1004/1005 带清单 */
export class MockError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly data: unknown = null,
  ) {
    super(message)
  }
}

export function fail(code: number, message: string, data: unknown = null): never {
  throw new MockError(code, message, data)
}

const routes: { method: string; pattern: RegExp; keys: string[]; handler: Handler }[] = []

/** 登记一个假接口。path 写成 /api/orders/:id，:xxx 匹配一段 */
export function mock(method: 'get' | 'post' | 'put' | 'delete', path: string, handler: Handler) {
  const keys: string[] = []
  const pattern = new RegExp(
    '^' + path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/:(\w+)/g, (_, key: string) => (keys.push(key), '([^/]+)')) + '$',
  )
  routes.push({ method, pattern, keys, handler })
}

/** 分页：照后端 { list, total }，page 从 1 起 */
export function page<T>(rows: T[], query: Record<string, string | string[]>) {
  const pageNo = Number(query.page ?? 1)
  const size = Number(query.size ?? 20)
  return { list: rows.slice((pageNo - 1) * size, pageNo * size), total: rows.length }
}

/** 假装网络慢一点，加载状态看得出来 */
const delay = () => new Promise((resolve) => setTimeout(resolve, 150))

function parseQuery(config: InternalAxiosRequestConfig, url: URL): Record<string, string | string[]> {
  const query: Record<string, string | string[]> = {}
  const add = (key: string, value: unknown) => {
    if (value === undefined || value === null || value === '') return
    const text = String(value)
    const old = query[key]
    query[key] = old === undefined ? text : Array.isArray(old) ? [...old, text] : [old, text]
  }
  url.searchParams.forEach((value, key) => add(key, value))
  for (const [key, value] of Object.entries((config.params ?? {}) as Record<string, unknown>)) {
    if (Array.isArray(value)) value.forEach((v) => add(key, v))
    else add(key, value)
  }
  return query
}

installMock((config) => {
  const url = new URL(config.url ?? '', location.origin)
  const method = (config.method ?? 'get').toLowerCase()
  // 参数少的先比：/api/production-orders/schedule-quote（order.ts）要排在 /api/production-orders/:id（production.ts）前面，
  // 各模块文件是并行加载的，登记顺序靠不住。段数不同的路径本来就不会互相匹配，所以只按参数个数排就够了（sort 是稳定的）
  const ordered = [...routes].sort((a, b) => a.keys.length - b.keys.length)
  for (const route of ordered) {
    const matched = route.method === method && route.pattern.exec(url.pathname)
    if (!matched) continue
    const params = Object.fromEntries(route.keys.map((key, i) => [key, decodeURIComponent(matched[i + 1]!)]))
    const body = typeof config.data === 'string' ? JSON.parse(config.data || 'null') : config.data
    // 返回的数据过一遍 JSON，和走网络一样每次都是新对象：handler 直接把内存里的假数据交出去的话，页面拿到的是同一个对象，
    // 之后 handler 改它（开始处理、关闭）不经过 Vue，页面重新取回来又是同一个引用，Vue 认为没变、不重画，状态就卡在旧的
    return delay()
      .then(() => route.handler({ params, query: parseQuery(config, url), body }))
      .then((data): R<unknown> | Blob =>
        data instanceof Blob ? data : { code: 0, msg: 'ok', data: data == null ? null : JSON.parse(JSON.stringify(data)) },
      )
      .catch((e: unknown): R<unknown> => {
        if (e instanceof MockError) return { code: e.code, msg: e.message, data: e.data }
        console.error('[mock]', method.toUpperCase(), url.pathname, e)
        return { code: 500, msg: `mock 出错了：${String(e)}`, data: null }
      })
  }
  return undefined
})

/**
 * 加载各模块的假接口（mock/ 下除了本文件的 .ts），main.ts 在挂载应用前调。
 * 不用 eager 的 glob：那样会被提到本文件开头先执行，模块文件调 mock() 时 routes 还没初始化
 */
export async function loadMocks() {
  const modules = import.meta.glob(['./*.ts', '!./index.ts'])
  await Promise.all(Object.values(modules).map((load) => load()))
  console.info(`[mock] 已加载 ${routes.length} 个假接口；没写 mock 的请求照常发给后端`)
}
