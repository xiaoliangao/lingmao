import { createRouter, createWebHistory, type RouteComponent, type RouteLocationNormalized, type RouteRecordRaw } from 'vue-router'
import AppLayout from '@/layout/AppLayout.vue'
import { APP_NAME } from '@/config'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/utils/path'
import { STANDALONE_PAGES, isStandalone } from './standalone'

/**
 * 两块区域，共用一个外框（layout/AppLayout.vue：左边侧边栏，右边页面）：
 * - 首页这一侧（/）：所有人登录后都到这里，左边是业务菜单，顶上是页签（开过的页面用 keep-alive 留着）
 * - 管理后台（/admin）：用户、角色、菜单、字典、日志，只有超级管理员有这块路由，没有页签
 * 两块的菜单都来自 /api/auth/me，按顶层目录的 adminOnly 分开
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/index.vue'), meta: { public: true, title: '登录' } },
  // 管理员给的密码还没改（RFC 018 §2）：登录后只能到这一页，改完重新登录。要登录，不是 public
  { path: '/change-password', component: () => import('@/views/login/change-password.vue'), meta: { title: '修改初始密码' } },
  {
    path: '/',
    name: 'workbench',
    component: AppLayout,
    props: { area: 'workbench' },
    redirect: '/home',
    children: [
      { path: '/home', component: () => import('@/views/home/index.vue'), meta: { title: '首页', cacheName: 'HomePage' } },
      // 上游已变更（docs/api/06 §1.2）：和首页一样所有人都有、不是菜单，内容由后端按负责人筛。
      // 用 glob 取：页面文件还没做时显示"还没上线"，不让打包报错。页面里写 defineOptions({ name: 'UpstreamPage' })
      {
        path: '/upstream',
        component: import.meta.glob('../views/home/upstream.vue')['../views/home/upstream.vue'] ?? (() => import('@/views/error/building.vue')),
        meta: { title: '上游已变更', cacheName: 'UpstreamPage' },
      },
      // 兜底：什么都匹配不上就是 404，放在首页这一侧的外框里，左边菜单照常能点（不开页签）。
      // vue-router 按路径的具体程度排优先级，后加的菜单路由照样排在它前面
      { path: '/:pathMatch(.*)*', component: () => import('@/views/error/404.vue'), meta: { title: '页面不存在' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })

/** views 下所有页面，按文件路径索引。菜单表里的 component 字段（如 system/user/index）对应这里的一个文件 */
const pages = import.meta.glob('../views/**/index.vue')
/** 菜单配好了、页面还没做的，显示"还没上线" */
const building = () => import('@/views/error/building.vue')

/**
 * 详情页（RFC 003 §18）：一条记录一个页签，/materials/12 和 /materials/15 各开一个、各留各的状态。
 * 加一个详情页 = 下面加一行 + 写那个 detail.vue（RFC 003 §18"加一个详情页"）
 * - parent：列表页菜单的地址。用户有这个菜单才挂详情路由（没有就是 404）；侧边栏高亮、分组都跟着列表页
 * - title：页签先叫这个，页面加载完调 useTabsStore().rename(route.path, '物料 ML0001') 换成具体的
 * - 地址是 parent/:id，id 只认数字。页面在 setup 里取一次 id，不要 watch 路由：
 *   keep-alive 留着的别的详情页也看得到路由变化（AppLayout 给每个详情页签套一层按地址起名的壳）
 * - path：地址不是 parent/:id 的（整页的新建、编辑表单 form.vue，RFC 005 §9）写在这里，同样一个地址一个页签
 */
const DETAIL_ROUTES: { parent: string; file: string; title: string; path?: string }[] = [
  { parent: '/materials', file: 'basic/material/detail', title: '物料' },
  { parent: '/suppliers', file: 'supplier/supplier/detail', title: '供应商' },
  { parent: '/styles', file: 'style/style/detail', title: '款式' },
  { parent: '/styles', path: '/styles/new', file: 'style/style/form', title: '新建款式' },
  { parent: '/styles', path: '/styles/:id(\\d+)/edit', file: 'style/style/form', title: '编辑款式' },
  // 第 3、4 批（docs/api 各模块文档的"页面"一节）
  { parent: '/orders', file: 'order/order/detail', title: '需求订单' },
  { parent: '/orders', path: '/orders/new', file: 'order/order/form', title: '新建需求订单' },
  { parent: '/orders', path: '/orders/:id(\\d+)/edit', file: 'order/order/form', title: '编辑需求订单' },
  // 翻单、补单（RFC 016 §2）：每张原单自己一个页签（keep-alive 和本地草稿都按地址分），不和"新建需求订单"挤在一起
  { parent: '/orders', path: '/orders/:id(\\d+)/repeat', file: 'order/order/form', title: '翻单' },
  { parent: '/orders', path: '/orders/:id(\\d+)/replenish', file: 'order/order/form', title: '补单' },
  { parent: '/order-changes', file: 'order/change/detail', title: '订单变更' },
  { parent: '/mrp', file: 'purchase/mrp/detail', title: '算料单' },
  { parent: '/purchases', file: 'purchase/order/detail', title: '采购单' },
  { parent: '/purchases', path: '/purchases/new', file: 'purchase/order/form', title: '新建采购单' },
  { parent: '/purchases', path: '/purchases/:id(\\d+)/edit', file: 'purchase/order/form', title: '编辑采购单' },
  { parent: '/stock', path: '/reorder', file: 'purchase/reorder/detail', title: '补货提醒' },
  { parent: '/stock-in', file: 'inventory/doc/detail', title: '物料入库单' },
  { parent: '/stock-out', file: 'inventory/doc/detail', title: '物料出库单' },
  { parent: '/stock-takes', file: 'inventory/count/detail', title: '物料盘点单' },
  { parent: '/production', file: 'production/order/detail', title: '外发加工单' },
  { parent: '/fp-purchases', file: 'production/order/detail', title: '成品采购单' },
  // 二期成品（docs/api/07 §1.1）
  { parent: '/fg-in', file: 'fg/doc/detail', title: '成品入库单' },
  { parent: '/fg-out', file: 'fg/doc/detail', title: '成品出库单' },
  { parent: '/statements', file: 'settle/statement/detail', title: '对账单' },
]
const detailPages = import.meta.glob('../views/**/{detail,form}.vue')
/** 加过的菜单页面：地址 → 所在目录名，挂详情路由时查 */
const menuGroups = new Map<string, string>()

function addDetailRoutes(parentName: string) {
  for (const detail of DETAIL_ROUTES) {
    const group = menuGroups.get(detail.parent)
    if (group === undefined) continue
    router.addRoute(parentName, {
      path: detail.path ?? `${detail.parent}/:id(\\d+)`,
      component: detailPages[`../views/${detail.file}.vue`] ?? building,
      meta: { title: detail.title, group, detail: true },
    })
  }
}

/** 给页面组件起一个唯一的名字（Menu<菜单 id>），keep-alive 按名字缓存、页签关了按名字清掉 */
function named(loader: () => Promise<unknown>, name: string): () => Promise<RouteComponent> {
  return () => loader().then((mod) => ({ ...(mod as { default: RouteComponent }).default, name }))
}

/** 单独成页的菜单（router/standalone.ts）：自己的外框，列表页和详情页 /:id 挂在它下面 */
function addStandaloneRoute(path: string, title: string, page: () => Promise<unknown>) {
  const detail = detailPages[`../views/${STANDALONE_PAGES[path]!.detail}.vue`] ?? building
  router.addRoute({
    path,
    component: () => import('@/layout/MobileLayout.vue'),
    children: [
      { path: '', component: page, meta: { title } },
      { path: ':id(\\d+)', component: detail, meta: { title } },
    ],
  })
}

/** 把菜单（目录、菜单）变成路由，挂在 parentName 那块区域下面。返回第一个页面的地址 */
function addMenuRoutes(parentName: string, menus: MenuNode[], parentPath = '', group = ''): string | undefined {
  let first: string | undefined
  for (const menu of menus) {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2 && menu.component && isStandalone(fullPath)) {
      addStandaloneRoute(fullPath, menu.name, pages[`../views/${menu.component}.vue`] ?? building)
      menuGroups.set(fullPath, group)
    } else if (menu.type === 2 && menu.component) {
      const cacheName = `Menu${menu.id}`
      const page = pages[`../views/${menu.component}.vue`] ?? building
      router.addRoute(parentName, {
        path: fullPath,
        name: `menu-${menu.id}`,
        component: named(page, cacheName),
        meta: { title: menu.name, group, cacheName },
      })
      menuGroups.set(fullPath, group)
      first ??= fullPath
    }
    const child = addMenuRoutes(parentName, menu.children, fullPath, menu.type === 1 ? menu.name : group)
    first ??= child
  }
  return first
}

let menuRoutesAdded = false

/**
 * 手机上（屏宽 < 600px）打开 / 时（登录后就是），有手机报工菜单的直接进手机报工（docs/api/05 §1.2），没有的进手机待办（RFC 019 §4），都没有照常去首页。
 * 守卫里改道以后，下一次跳转的 redirectedFrom 还是 /，已经在 /mobile 了就不再改，不然一直改道（vue-router 报 Infinite redirect）
 */
function phoneHome(to: RouteLocationNormalized): string | undefined {
  if (to.redirectedFrom?.path !== '/' || isStandalone(to.path) || !window.matchMedia('(max-width: 599px)').matches) return undefined
  // 跟单员进手机报工；没有手机报工的（审核的人、采购员）进手机待办（RFC 019 §4）
  return ['/mobile', '/mobile-todo'].find((p) => menuGroups.has(p))
}

/** 工厂账号能去的：手机报工的列表和单子 */
function isFactoryPath(path: string) {
  return path === '/mobile' || /^\/mobile\/\d+$/.test(path)
}

router.beforeEach(async (to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - ${APP_NAME}` : APP_NAME
  if (to.meta.public) return true
  const store = useUserStore()
  if (!store.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!menuRoutesAdded) {
    try {
      await store.fetchMe()
    } catch {
      // 401 时拦截器已经在跳登录页，其他错误也弹过消息了，这一次跳转取消
      return false
    }
    // 还在用管理员给的密码：只放改密码这一页（后端别的接口也都是 403）。菜单路由先不加，改完重新登录时整页重新加载再加
    if (store.info?.mustChangePassword) {
      return to.path === '/change-password' ? true : { path: '/change-password', replace: true }
    }
    // 工厂账号（RFC 019 §7）：没有菜单，只开手机报工（列本厂的单、报进度、登记实裁数），别的地址都回 /mobile。
    // 电脑上也是这一页（居中、最宽 560px）；后端别的接口对工厂账号一律 403
    if (store.info?.factory) {
      addStandaloneRoute('/mobile', '报工', pages['../views/production/mobile/index.vue'] ?? building)
      menuRoutesAdded = true
      return isFactoryPath(to.path) ? to.fullPath : { path: '/mobile', replace: true }
    }
    addMenuRoutes('workbench', store.workbenchMenus)
    addDetailRoutes('workbench')
    if (store.isAdmin) {
      // 打开 /admin 时跳到管理后台的第一个页面。redirect 写成函数，等下面加完菜单路由再取值
      let adminHome = '/home'
      router.addRoute({
        path: '/admin',
        name: 'admin',
        component: AppLayout,
        props: { area: 'admin' },
        redirect: () => adminHome,
        children: [],
      })
      adminHome = addMenuRoutes('admin', store.adminMenus) ?? adminHome
    }
    menuRoutesAdded = true
    // 这一次跳转是按加路由之前的表匹配的（菜单页会落到 404），按新表重新跳一次
    return phoneHome(to) ?? to.fullPath
  }
  if (store.info?.factory && !isFactoryPath(to.path)) return { path: '/mobile', replace: true }
  // 密码早就改过了还打开改初始密码这一页：回首页（自己改密码在侧边栏底下）
  if (to.path === '/change-password') return '/'
  return phoneHome(to) ?? true
})

export default router
