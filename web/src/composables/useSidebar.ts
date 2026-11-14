import { computed, ref } from 'vue'

/**
 * 侧边栏开着还是收着。外框、侧边栏、页头（PageHeader 左边的"展开导航"按钮）共用一份：
 * - 宽屏：按 [ 键或侧边栏顶上的按钮收起，收起以后整个藏掉；开还是收记在浏览器里，下次打开还是这样
 * - 窄屏（900px 以下，手机、平板竖着）：侧边栏浮在页面上面，平时藏着，点页头的按钮拉出来，选完页面自动收回
 */
const KEY = 'lingmao-side-hidden'

function readHidden(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    // 隐私模式等情况下读不了 localStorage，默认开着
    return false
  }
}

function saveHidden(hidden: boolean) {
  try {
    localStorage.setItem(KEY, hidden ? '1' : '0')
  } catch {
    // 存不了就只在这次打开里生效
  }
}

const wideOpen = ref(!readHidden())

const narrowQuery = window.matchMedia('(max-width: 900px)')
const narrow = ref(narrowQuery.matches)
const drawerOpen = ref(false)
narrowQuery.addEventListener('change', (event) => {
  narrow.value = event.matches
  drawerOpen.value = false
})

export function useSidebar() {
  /** 现在看得见侧边栏没有 */
  const open = computed(() => (narrow.value ? drawerOpen.value : wideOpen.value))

  function toggle() {
    if (narrow.value) {
      drawerOpen.value = !drawerOpen.value
    } else {
      wideOpen.value = !wideOpen.value
      saveHidden(!wideOpen.value)
    }
  }

  function show() {
    if (!open.value) toggle()
  }

  /** 窄屏选完页面以后收回去；宽屏不动 */
  function closeDrawer() {
    drawerOpen.value = false
  }

  return { open, narrow, toggle, show, closeDrawer }
}
