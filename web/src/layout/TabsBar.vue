<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { HOME_TAB, useTabsStore, type Tab } from '@/stores/tabs'

// 页签：开过的页面都排在这里，点一下切回去，页面原来的样子还在（keep-alive）。首页关不掉；
// 页面里有没保存的修改（useUnsavedChanges 登记的），关之前问一声。中键点页签也能关；
// 右键（或键盘的菜单键）弹出菜单：关闭当前、其他、左侧、右侧、所有，都是对右键点的那个页签说的
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

/** 要关的页签里有没保存修改的，先问一声；用户选"继续编辑"返回 false，一个都不关 */
async function confirmDiscard(list: Tab[]) {
  const dirty = list.filter((tab) => tabs.isDirty(tab.path))
  if (!dirty.length) return true
  try {
    await ElMessageBox.confirm(`${dirty.map((tab) => `「${tab.title}」`).join('')}有没保存的修改，关掉以后就没了。`, '关掉页签', {
      confirmButtonText: '不保存，关掉',
      cancelButtonText: '继续编辑',
      confirmButtonClass: 'el-button--danger',
    })
    return true
  } catch {
    return false
  }
}

async function close(tab: Tab) {
  if (tab.path === HOME_TAB.path || !(await confirmDiscard([tab]))) return
  const next = tabs.close(tab.path, route.path)
  if (next !== route.path) router.push(next)
}

// ---------- 右键菜单 ----------
type MenuCommand = 'current' | 'others' | 'left' | 'right' | 'all'

const MENU: { command: MenuCommand; label: string }[] = [
  { command: 'current', label: '关闭当前' },
  { command: 'others', label: '关闭其他' },
  { command: 'left', label: '关闭左侧' },
  { command: 'right', label: '关闭右侧' },
  { command: 'all', label: '关闭所有' },
]

/** 菜单的一项会关掉哪些页签。首页不算（关不掉），一个都没有的项灰掉 */
function targetsOf(tab: Tab, command: MenuCommand): Tab[] {
  const index = tabs.tabs.findIndex((t) => t.path === tab.path)
  let list: Tab[]
  switch (command) {
    case 'current':
      list = [tab]
      break
    case 'others':
      list = tabs.tabs.filter((t) => t.path !== tab.path)
      break
    case 'left':
      list = tabs.tabs.slice(0, index)
      break
    case 'right':
      list = tabs.tabs.slice(index + 1)
      break
    case 'all':
      list = tabs.tabs
  }
  return list.filter((t) => t.path !== HOME_TAB.path)
}

/** 开着的菜单：对哪个页签、放在哪（窗口坐标）、关菜单时焦点还给谁 */
const menu = ref<{ tab: Tab; x: number; y: number; trigger: HTMLElement | null } | null>(null)
const menuEl = ref<HTMLElement>()

async function openMenu(tab: Tab, event: MouseEvent) {
  const el = event.currentTarget as HTMLElement
  let { clientX: x, clientY: y } = event
  // 键盘的菜单键弹出来时没有鼠标位置，放在页签下面
  if (!x && !y) {
    const rect = el.getBoundingClientRect()
    x = rect.left
    y = rect.bottom
  }
  menu.value = { tab, x, y, trigger: el.querySelector('a') }
  await nextTick()
  const box = menuEl.value
  if (!menu.value || !box) return
  // 靠近窗口右边、下边时往回挪，别伸出窗口
  menu.value.x = Math.max(8, Math.min(x, window.innerWidth - box.offsetWidth - 8))
  menu.value.y = Math.max(8, Math.min(y, window.innerHeight - box.offsetHeight - 8))
  box.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
}

function closeMenu() {
  menu.value = null
}

// 换了页面（点了侧边栏、按了浏览器后退）菜单就收起
watch(() => route.path, closeMenu)

async function run(command: MenuCommand) {
  const tab = menu.value?.tab
  closeMenu()
  if (!tab) return
  if (command === 'current') return close(tab)
  const list = targetsOf(tab, command)
  if (!list.length || !(await confirmDiscard(list))) return
  const closingCurrent = list.some((t) => t.path === route.path)
  tabs.closeMany(list.map((t) => t.path))
  // 当前页也关了：关所有回首页，其他几种停在右键点的那个页签
  if (closingCurrent) router.push(command === 'all' ? HOME_TAB.path : tab.path)
}

/** 菜单里上下键换项、Esc 收起（焦点回到页签） */
function onMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    const trigger = menu.value?.trigger
    closeMenu()
    trigger?.focus()
    return
  }
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  event.preventDefault()
  const items = [...(menuEl.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  const index = items.indexOf(document.activeElement as HTMLButtonElement)
  const step = event.key === 'ArrowDown' ? 1 : -1
  items[(index + step + items.length) % items.length]?.focus()
}
</script>

<template>
  <nav class="ptabs" aria-label="打开的页面">
    <div
      v-for="tab in tabs.tabs"
      :key="tab.path"
      class="ptab"
      :class="{ on: route.path === tab.path }"
      @auxclick.middle.prevent="close(tab)"
      @contextmenu.prevent="openMenu(tab, $event)"
    >
      <router-link :to="tab.path" :aria-current="route.path === tab.path ? 'page' : undefined">{{ tab.title }}</router-link>
      <button v-if="tab.path !== HOME_TAB.path" type="button" class="x" :aria-label="`关掉页签：${tab.title}`" title="关掉" @click="close(tab)">
        <el-icon><Close /></el-icon>
      </button>
    </div>
  </nav>

  <!-- 菜单下面垫一层透明的：点菜单外面、再右键、滚轮都先把菜单收起 -->
  <Teleport to="body">
    <div v-if="menu" class="tab-menu-mask" @pointerdown.self="closeMenu" @contextmenu.prevent="closeMenu" @wheel="closeMenu">
      <ul
        ref="menuEl"
        class="tab-menu"
        role="menu"
        :aria-label="`页签「${menu.tab.title}」`"
        :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
        @keydown="onMenuKeydown"
      >
        <li v-for="item in MENU" :key="item.command" role="none">
          <button type="button" role="menuitem" :disabled="!targetsOf(menu.tab, item.command).length" @click="run(item.command)">
            {{ item.label }}
          </button>
        </li>
      </ul>
    </div>
  </Teleport>
</template>

<style scoped>
/* 样子照原型的 .ptabs / .ptab：页签条是侧边栏的暖灰，当前页签白底带边框，和下面的页面连成一片 */
.ptabs {
  display: flex;
  flex: none;
  align-items: flex-end;
  gap: 2px;
  height: 38px;
  padding: 0 12px;
  overflow-x: auto;
  scrollbar-width: none;
  background: var(--lm-greige);
  border-bottom: 1px solid var(--lm-line);
}

.ptabs::-webkit-scrollbar {
  display: none;
}

.ptab {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  height: 30px;
  margin-bottom: 1px;
  padding: 0 8px 0 0;
  font-size: 12.5px;
  color: var(--lm-ink-2);
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: 7px;
  transition:
    background 0.12s,
    color 0.12s;
}

/* 整个页签都能点：字的左右留白放在链接上 */
.ptab a {
  display: flex;
  align-items: center;
  align-self: stretch;
  padding-left: 12px;
  color: inherit;
  text-decoration: none;
  border-radius: inherit;
}

/* 首页没有 ×，右边留白和左边一样 */
.ptab:not(:has(.x)) {
  padding-right: 12px;
}

.ptab:hover {
  color: var(--lm-ink);
  background: var(--lm-hover);
}

.ptab.on {
  height: 33px;
  margin-bottom: -1px;
  font-weight: 600;
  color: var(--lm-ink);
  background: var(--lm-paper);
  border-color: var(--lm-line);
  border-bottom-color: var(--lm-paper);
  border-radius: 8px 8px 0 0;
}

/* 关掉的 ×：悬停、当前页签、键盘移到这个页签上时才显示 */
.x {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  padding: 0;
  font-size: 11px;
  color: var(--lm-muted);
  visibility: hidden;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 4px;
}

.ptab:hover .x,
.ptab.on .x,
.ptab:focus-within .x {
  visibility: visible;
}

.x:hover {
  color: var(--lm-ink);
  background: var(--lm-sunken);
}

/* 右键菜单：样子和 Element Plus 的下拉菜单一样（styles/element.css 的 .el-dropdown-menu） */
.tab-menu-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
}

.tab-menu {
  position: fixed;
  min-width: 112px;
  margin: 0;
  padding: 5px;
  list-style: none;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-pop);
}

.tab-menu button {
  display: block;
  width: 100%;
  height: 31px;
  padding: 0 10px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius-sm);
}

.tab-menu button:not(:disabled):hover,
.tab-menu button:focus-visible {
  background: var(--lm-hover);
  outline: none;
}

.tab-menu button:disabled {
  color: var(--lm-disabled);
  cursor: not-allowed;
}
</style>
