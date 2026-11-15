import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/** 顶上的一个页签：一个菜单页面一个 */
export interface Tab {
  path: string
  title: string
  /** 页面组件的名字（菜单页面是 Menu<菜单 id>），keep-alive 按它缓存：切走再切回来，筛选条件、翻到第几页、填了一半的表单都还在 */
  cacheName: string
}

export const HOME_TAB: Tab = { path: '/home', title: '首页', cacheName: 'HomePage' }

/**
 * 页签的缓存名。菜单页面是路由上的 Menu<菜单 id>；详情页（路由 meta.detail，见 router/index.ts 的 DETAIL_ROUTES）
 * 一条记录一个页签，同一个组件开好几份，按地址起名，AppLayout 给它套一层同名的壳让 keep-alive 分开留
 */
export function cacheNameOf(route: Pick<RouteLocationNormalizedLoaded, 'path' | 'meta'>): string | undefined {
  if (route.meta.detail) return `Detail:${route.path}`
  return route.meta.cacheName as string | undefined
}

/** 最多开这么多个页签，再多就关掉最早打开的那个（有没保存修改的不关） */
const MAX_TABS = 10

/** 打开过的页面。首页固定在最前面，不能关 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<Tab[]>([HOME_TAB])
  /** keep-alive 只留开着页签的页面：页签一关，页面就销毁 */
  const cacheNames = computed(() => tabs.value.map((tab) => tab.cacheName))

  // ---------- 页面里有没保存的修改：useUnsavedChanges 按页面地址登记进来，关页签、退出登录前问一声 ----------
  const dirtyChecks = new Map<string, Set<() => boolean>>()

  /** 登记一个"有没有修改"的检查，返回取消登记的函数（页面销毁时调） */
  function trackDirty(path: string, check: () => boolean) {
    const checks = dirtyChecks.get(path) ?? new Set()
    checks.add(check)
    dirtyChecks.set(path, checks)
    return () => {
      checks.delete(check)
      if (!checks.size) dirtyChecks.delete(path)
    }
  }

  function isDirty(path: string) {
    return [...(dirtyChecks.get(path) ?? [])].some((check) => check())
  }

  /** 有没保存修改的页签的标题 */
  function dirtyTitles() {
    return tabs.value.filter((tab) => isDirty(tab.path)).map((tab) => tab.title)
  }

  function has(path: string) {
    return tabs.value.some((tab) => tab.path === path)
  }

  /** 打开一个页面：已经开着就不重复加 */
  function open(route: RouteLocationNormalizedLoaded) {
    const cacheName = cacheNameOf(route)
    if (!cacheName || has(route.path)) return
    tabs.value.push({ path: route.path, title: (route.meta.title as string | undefined) ?? '', cacheName })
    if (tabs.value.length > MAX_TABS) {
      const oldest = tabs.value.findIndex((tab, i) => i > 0 && tab.path !== route.path && !isDirty(tab.path))
      if (oldest > 0) tabs.value.splice(oldest, 1)
    }
  }

  /** 关掉一个页签，返回关掉以后该显示哪一页（关的是当前页时，显示它左边那个） */
  function close(path: string, currentPath: string): string {
    const index = tabs.value.findIndex((tab) => tab.path === path)
    if (index <= 0) return currentPath
    tabs.value.splice(index, 1)
    // 关之前已经问过了（TabsBar），页面马上销毁，离开它的时候不用再问一遍
    dirtyChecks.delete(path)
    return path === currentPath ? tabs.value[index - 1]!.path : currentPath
  }

  /** 退出登录：页签清空，下一个人登录进来只有首页 */
  function reset() {
    tabs.value = [HOME_TAB]
  }

  /** 改页签上的字：详情页加载完换成"物料 ML0001"这种（打开时只知道是"物料"） */
  function rename(path: string, title: string) {
    const tab = tabs.value.find((t) => t.path === path)
    if (tab) tab.title = title
  }

  return { tabs, cacheNames, has, open, close, reset, rename, trackDirty, isDirty, dirtyTitles }
})
