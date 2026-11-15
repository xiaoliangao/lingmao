import { createRouter, createWebHistory, type RouteComponent, type RouteRecordRaw } from 'vue-router'
import AppLayout from '@/layout/AppLayout.vue'
import { APP_NAME } from '@/config'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/utils/path'

/**
 * 两块区域，共用一个外框（layout/AppLayout.vue：左边侧边栏，右边页面）：
 * - 首页这一侧（/）：所有人登录后都到这里，左边是业务菜单，顶上是页签（开过的页面用 keep-alive 留着）
 * - 管理后台（/admin）：用户、角色、菜单、字典、日志，只有超级管理员有这块路由，没有页签
 * 两块的菜单都来自 /api/auth/me，按顶层目录的 adminOnly 分开
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/index.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    name: 'workbench',
    component: AppLayout,
    props: { area: 'workbench' },
    redirect: '/home',
    children: [
      { path: '/home', component: () => import('@/views/home/index.vue'), meta: { title: '首页', cacheName: 'HomePage' } },
      // 供应商详情（RFC 004 §12）：几家供应商的页签共用这一个页面实例，页面按地址里的 id 重新加载
      {
        path: '/suppliers/:id(\\d+)',
        component: () => import('@/views/supplier/supplier/detail.vue'),
        meta: { title: '供应商详情', cacheName: 'SupplierDetail' },
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
 * 加一个详情页 = 下面加一行 + 写那个 detail.vue：
 *   { parent: '/suppliers', file: 'supplier/supplier/detail', title: '供应商' }
 * - parent：列表页菜单的地址。用户有这个菜单才挂详情路由（没有就是 404）；侧边栏高亮、分组都跟着列表页
 * - title：页签先叫这个，页面加载完调 useTabsStore().rename(route.path, '物料 ML0001') 换成具体的
 * - 地址是 parent/:id，id 只认数字。页面在 setup 里取一次 id，不要 watch 路由：
 *   keep-alive 留着的别的详情页也看得到路由变化（AppLayout 给每个详情页签套一层按地址起名的壳）
 */
const DETAIL_ROUTES: { parent: string; file: string; title: string }[] = [
  { parent: '/materials', file: 'basic/material/detail', title: '物料' },
]
const detailPages = import.meta.glob('../views/**/detail.vue')
/** 加过的菜单页面：地址 → 所在目录名，挂详情路由时查 */
const menuGroups = new Map<string, string>()

function addDetailRoutes(parentName: string) {
  for (const detail of DETAIL_ROUTES) {
    const group = menuGroups.get(detail.parent)
    if (group === undefined) continue
    router.addRoute(parentName, {
      path: `${detail.parent}/:id(\\d+)`,
      component: detailPages[`../views/${detail.file}.vue`] ?? building,
      meta: { title: detail.title, group, detail: true },
    })
  }
}

/** 给页面组件起一个唯一的名字（Menu<菜单 id>），keep-alive 按名字缓存、页签关了按名字清掉 */
function named(loader: () => Promise<unknown>, name: string): () => Promise<RouteComponent> {
  return () => loader().then((mod) => ({ ...(mod as { default: RouteComponent }).default, name }))
}

/** 把菜单（目录、菜单）变成路由，挂在 parentName 那块区域下面。返回第一个页面的地址 */
function addMenuRoutes(parentName: string, menus: MenuNode[], parentPath = '', group = ''): string | undefined {
  let first: string | undefined
  for (const menu of menus) {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2 && menu.component) {
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
    return to.fullPath
  }
  return true
})

export default router
