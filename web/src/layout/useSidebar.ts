import { ref } from 'vue'

/**
 * 侧边栏的状态，侧边栏和顶栏共用一份：
 * - 宽屏：可以收起成只剩图标，收起/展开记在浏览器里，下次打开还是这样
 * - 窄屏（手机，不到 768px）：侧边栏变成抽屉，平时藏着，点顶栏的菜单按钮拉出来，选完页面自动收回
 */
const KEY = 'lingmao-side-collapsed'

function readCollapsed(): boolean {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved !== null) return saved === '1'
  } catch {
    // 隐私模式等情况下读不了 localStorage，按屏幕宽度来
  }
  return window.innerWidth < 1100
}

const collapsed = ref(readCollapsed())

const narrowQuery = window.matchMedia('(max-width: 767px)')
const narrow = ref(narrowQuery.matches)
const drawerOpen = ref(false)
narrowQuery.addEventListener('change', (event) => {
  narrow.value = event.matches
  drawerOpen.value = false
})

export function useSidebar() {
  function toggle() {
    collapsed.value = !collapsed.value
    try {
      localStorage.setItem(KEY, collapsed.value ? '1' : '0')
    } catch {
      // 存不了就只在这次打开里生效
    }
  }

  function openDrawer() {
    drawerOpen.value = true
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  return { collapsed, narrow, drawerOpen, toggle, openDrawer, closeDrawer }
}
