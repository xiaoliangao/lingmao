import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import Layout from '@/layout/index.vue'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types/system'

/** 不需要权限的固定页面 */
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/index.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    name: 'layout',
    component: Layout,
    redirect: '/dashboard',
    children: [{ path: 'dashboard', component: () => import('@/views/dashboard/index.vue'), meta: { title: '首页' } }],
  },
  // 兜底：什么都匹配不上就是 404。vue-router 按路径的具体程度排优先级，后加的菜单路由照样排在它前面
  { path: '/:pathMatch(.*)*', component: () => import('@/views/error/404.vue'), meta: { title: '页面不存在' } },
]

const router = createRouter({ history: createWebHistory(), routes })

/** views 下所有页面，按文件路径索引。菜单表里的 component 字段（如 system/user/index）对应这里的一个文件 */
const pages = import.meta.glob('../views/**/index.vue')

/** 把后端返回的菜单（目录、菜单）变成路由，挂在 layout 下面 */
function addMenuRoutes(menus: MenuNode[], parentPath = '') {
  for (const menu of menus) {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2 && menu.component) {
      const page = pages[`../views/${menu.component}.vue`]
      if (page) {
        router.addRoute('layout', { path: fullPath.slice(1), name: `menu-${menu.id}`, component: page, meta: { title: menu.name } })
      }
    }
    addMenuRoutes(menu.children, fullPath)
  }
}

export function joinPath(parent: string, path: string) {
  if (path.startsWith('/')) return path
  return `${parent.replace(/\/$/, '')}/${path}`
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
    await store.fetchMe()
    addMenuRoutes(store.info?.menus ?? [])
    menuRoutesAdded = true
    // 这一次跳转是按加路由之前的表匹配的（菜单页会落到 404），按新表重新跳一次
    return to.fullPath
  }
  return true
})

export default router
