import { createRouter, createWebHistory, type RouteComponent, type RouteRecordRaw } from 'vue-router'
import WorkbenchLayout from '@/layout/WorkbenchLayout.vue'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/utils/path'

/**
 * 两块区域：
 * - 工作台（/）：所有人登录后都到这里，左边是业务菜单，顶上是页签
 * - 管理后台（/admin）：用户、角色、菜单、字典、日志，只有超级管理员有这块路由
 * 两块的菜单都来自 /api/auth/me，按顶层目录的 adminOnly 分开
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/index.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    name: 'workbench',
    component: WorkbenchLayout,
    redirect: '/home',
    children: [
      {
        path: '/home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '首页', cacheName: 'WorkbenchHome' },
      },
    ],
  },
  // 兜底：什么都匹配不上就是 404。vue-router 按路径的具体程度排优先级，后加的菜单路由照样排在它前面
  { path: '/:pathMatch(.*)*', component: () => import('@/views/error/404.vue'), meta: { title: '页面不存在' } },
]

const router = createRouter({ history: createWebHistory(), routes })

/** views 下所有页面，按文件路径索引。菜单表里的 component 字段（如 system/user/index）对应这里的一个文件 */
const pages = import.meta.glob('../views/**/index.vue')
/** 菜单配好了、页面还没做的，显示"还没上线" */
const building = () => import('@/views/error/building.vue')

/** 给页面组件起一个唯一的名字，keep-alive 按名字缓存、按名字清掉 */
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
      first ??= fullPath
    }
    const child = addMenuRoutes(parentName, menu.children, fullPath, menu.type === 1 ? menu.name : group)
    first ??= child
  }
  return first
}

let menuRoutesAdded = false

router.beforeEach(async (to) => {
  document.title = `${(to.meta.title as string | undefined) ?? ''} - 领猫 SCM`
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
    if (store.isAdmin) {
      // 打开 /admin 时跳到管理后台的第一个页面。redirect 写成函数，等下面加完菜单路由再取值
      let adminHome = '/home'
      router.addRoute({
        path: '/admin',
        name: 'admin',
        component: () => import('@/layout/AdminLayout.vue'),
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
