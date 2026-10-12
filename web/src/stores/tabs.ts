import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

/** 工作台顶上的一个页签 */
export interface Tab {
  path: string
  title: string
  /** 页面组件的名字，keep-alive 按它缓存：切走再切回来，筛选条件、翻到第几页都还在 */
  cacheName: string
}

export const HOME_TAB: Tab = { path: '/home', title: '首页', cacheName: 'WorkbenchHome' }

/** 工作台打开过的页面。首页固定在最前面，不能关 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<Tab[]>([HOME_TAB])
  const cacheNames = computed(() => tabs.value.map((tab) => tab.cacheName))

  /** 打开一个页面：已经开着就不重复加 */
  function open(route: RouteLocationNormalizedLoaded) {
    const cacheName = route.meta.cacheName as string | undefined
    if (!cacheName || tabs.value.some((tab) => tab.path === route.path)) return
    tabs.value.push({ path: route.path, title: (route.meta.title as string | undefined) ?? '', cacheName })
  }

  /** 关掉一个页签，返回关掉以后该显示哪一页（关的是当前页时，显示它左边那个） */
  function close(path: string, currentPath: string): string {
    const index = tabs.value.findIndex((tab) => tab.path === path)
    if (index <= 0) return currentPath
    tabs.value.splice(index, 1)
    return path === currentPath ? tabs.value[index - 1]!.path : currentPath
  }

  return { tabs, cacheNames, open, close }
})
