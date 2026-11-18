<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRoute, type RouteLocationNormalizedLoaded } from 'vue-router'
import { useSidebar } from '@/composables/useSidebar'
import { cacheNameOf, useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import ModuleBoundary from '@/components/ModuleBoundary.vue'
import AppSidebar from './AppSidebar.vue'
import TabsBar from './TabsBar.vue'

/**
 * 外框：左边侧边栏，右边当前页面，每页自己顶上一行 PageHeader。
 * - 首页这一侧（/，所有人）：右边顶上多一条页签，开过的菜单页面用 keep-alive 留着，切回来还是原来的样子
 * - 管理后台（/admin，只有超级管理员）：没有页签，点哪页显示哪页
 *
 * 快捷键：[ 收起 / 展开侧边栏；⌘K（Windows 上 Ctrl+K）打开 / 关掉快捷跳转（侧边栏的"搜索功能"）
 */
const props = defineProps<{ area: 'workbench' | 'admin' }>()

const route = useRoute()
const store = useUserStore()
const tabs = useTabsStore()
const { toggle, closeDrawer } = useSidebar()
const withTabs = computed(() => props.area === 'workbench')
const menus = computed(() => (props.area === 'admin' ? store.adminMenus : store.workbenchMenus))

const main = ref<HTMLElement>()
const sidebar = ref<InstanceType<typeof AppSidebar>>()

// 每个页签记住滚到哪了：切回来接着看；新打开的页面从顶上开始
const scrollTops = new Map<string, number>()

// 详情页（/materials/12）一条记录一个页签，用的是同一个组件。keep-alive 按 key 留页面、按组件名清（include），
// 同名的分不开，所以给每个详情页签套一层按地址起名的壳（名字 = tabs.cacheNameOf，"Detail:/materials/12"，key 是地址），
// 关了页签按名字清掉
const shells = new Map<string, Component>()

function shellOf(target: RouteLocationNormalizedLoaded): Component {
  const name = cacheNameOf(target) ?? `Page:${target.path}`
  let shell = shells.get(name)
  if (!shell) {
    shell = defineComponent({ name, setup: (_, { slots }) => () => h(ModuleBoundary, null, slots) })
    shells.set(name, shell)
  }
  return shell
}

watch(
  () => tabs.cacheNames,
  (names) => {
    for (const name of shells.keys()) if (!names.includes(name)) shells.delete(name)
  },
)

watch(
  () => route.path,
  async (path, oldPath) => {
    if (withTabs.value) tabs.open(route)
    closeDrawer()
    if (oldPath === undefined) return
    if (main.value) scrollTops.set(oldPath, main.value.scrollTop)
    await nextTick()
    main.value?.scrollTo({ top: tabs.has(path) ? (scrollTops.get(path) ?? 0) : 0 })
  },
  { immediate: true },
)

// 离开首页这一侧（去管理后台、退出登录）：页签里的页面都要销毁，有没保存的先问
onBeforeRouteLeave(async () => {
  if (!withTabs.value) return true
  const titles = tabs.dirtyTitles()
  if (!titles.length) return true
  try {
    await ElMessageBox.confirm(`「${titles.join('」「')}」有没保存的修改，离开以后就没了。`, '离开这一页', {
      confirmButtonText: '不保存，离开',
      cancelButtonText: '继续编辑',
      confirmButtonClass: 'el-button--danger',
    })
    tabs.reset()
    return true
  } catch {
    return false
  }
})

/** 正在打字：单键快捷键不能抢输入框里的字 */
function typing(target: EventTarget | null) {
  const el = target as HTMLElement | null
  return !!el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable)
}

/** 开着对话框、确认框（它们的遮罩是 .el-overlay，关了是 display: none） */
function overlayOpen() {
  return [...document.querySelectorAll('.el-overlay')].some((el) => el.getClientRects().length > 0)
}

function onKey(event: KeyboardEvent) {
  if (event.isComposing) return
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    sidebar.value?.toggleSearch()
    return
  }
  const bracket = event.key === '[' || event.key === '【' || (event.code === 'BracketLeft' && !event.shiftKey)
  if (bracket && !event.metaKey && !event.ctrlKey && !event.altKey && !typing(event.target) && !overlayOpen()) {
    event.preventDefault()
    toggle()
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="shell">
    <AppSidebar ref="sidebar" :area="area" :menus="menus" />
    <div class="shell-work">
      <TabsBar v-if="withTabs" />
      <main ref="main" class="shell-main">
        <router-view v-slot="{ Component, route: current }">
          <!--
            菜单页那支的 key 不能省：v-if / v-else 会给两支各配一个固定的 key，keep-alive 按 key 缓存，所有菜单页就挤在同一格里了。
            这条注释不能挪进 keep-alive 里：开发模式下 Vue 会把两支之间的注释并进 v-else 那支，那支就成了"注释 + 组件"两个节点，
            keep-alive 认不出组件、一个都不缓存（也不报警告），切页签时菜单页每次重建；打包后注释被去掉，所以只有 pnpm dev 时出问题
          -->
          <keep-alive v-if="withTabs" :include="tabs.cacheNames">
            <component :is="shellOf(current)" :key="current.path">
              <component :is="Component" />
            </component>
          </keep-alive>
          <ModuleBoundary v-else :key="current.path"><component :is="Component" /></ModuleBoundary>
        </router-view>
      </main>
    </div>
  </div>
</template>
