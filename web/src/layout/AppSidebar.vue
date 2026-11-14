<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { APP_MARK, APP_NAME } from '@/config'
import { useSidebar } from '@/composables/useSidebar'
import { useVersion } from '@/composables/useVersion'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/utils/path'
import PasswordDialog from './PasswordDialog.vue'

/**
 * 侧边栏（照原型的外框）：浅暖灰底，顶上系统名和收起按钮，下面"搜索功能"，再下面按目录分组的菜单；
 * 最底下是切换区域（管理后台 / 回首页）和当前用户（点开改密码、退出登录）。
 * 菜单只有两层：顶层目录是一组（组名可以点着收起来），目录下面的页面一个一个列出来；更深的目录摊平
 */
const props = defineProps<{ menus: MenuNode[]; area: 'workbench' | 'admin' }>()

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const tabs = useTabsStore()
const { open, narrow, toggle } = useSidebar()
const { version, title: versionTitle } = useVersion()

interface NavItem {
  name: string
  path: string
  icon: string | null
  /** 上一级目录的名字：搜索结果里显示 */
  group: string
}

interface NavGroup {
  key: string
  /** 空串：最上面不带组名的那一组（首页、直接挂在顶层的页面） */
  name: string
  items: NavItem[]
}

function pagesOf(menus: MenuNode[], parentPath: string, group: string): NavItem[] {
  return menus.flatMap((menu) => {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2) return [{ name: menu.name, path: fullPath, icon: menu.icon, group }]
    return pagesOf(menu.children, fullPath, menu.name)
  })
}

const groups = computed<NavGroup[]>(() => {
  const top: NavItem[] = props.area === 'workbench' ? [{ name: '首页', path: '/home', icon: 'House', group: '' }] : []
  const rest: NavGroup[] = []
  for (const menu of props.menus) {
    if (menu.type === 2) {
      top.push(...pagesOf([menu], '', ''))
    } else {
      const items = pagesOf(menu.children, joinPath('', menu.path ?? ''), menu.name)
      if (items.length) rest.push({ key: String(menu.id), name: menu.name, items })
    }
  }
  return top.length ? [{ key: 'top', name: '', items: top }, ...rest] : rest
})

const allItems = computed(() => groups.value.flatMap((group) => group.items))

/** 当前在哪一项：地址和它一样、或者在它下面（比如详情页 /purchase/orders/12 算"采购单"） */
const activePath = computed(() => {
  let best = ''
  for (const item of allItems.value) {
    const hit = route.path === item.path || route.path.startsWith(`${item.path}/`)
    if (hit && item.path.length > best.length) best = item.path
  }
  return best
})

// ---------- 组收起来：记在浏览器里 ----------
const GROUPS_KEY = 'lingmao-side-groups'

function readClosed(): Record<string, boolean> {
  try {
    return JSON.parse(localStorage.getItem(GROUPS_KEY) ?? '{}') as Record<string, boolean>
  } catch {
    return {}
  }
}

const closed = reactive(readClosed())

function toggleGroup(key: string) {
  closed[key] = !closed[key]
  try {
    localStorage.setItem(GROUPS_KEY, JSON.stringify(closed))
  } catch {
    // 存不了就只在这次打开里生效
  }
}

// ---------- 搜索功能：按名字找页面，不用一组组展开 ----------
const keyword = ref('')
const searchInput = ref<HTMLInputElement>()
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.userAgent)
const SHORTCUT = IS_MAC ? '⌘K' : 'Ctrl K'

const results = computed(() => {
  const word = keyword.value.trim()
  if (!word) return []
  return allItems.value.filter((item) => item.name.includes(word) || item.group.includes(word))
})

function go(path: string) {
  keyword.value = ''
  router.push(path)
}

function goFirst() {
  const first = results.value[0]
  if (first) go(first.path)
}

function clearSearch() {
  keyword.value = ''
  searchInput.value?.blur()
}

/** ⌘K / Ctrl+K：侧边栏收着就先拉出来，再把光标放进搜索框 */
async function focusSearch() {
  if (!open.value) toggle()
  await nextTick()
  searchInput.value?.focus()
  searchInput.value?.select()
}

defineExpose({ focusSearch })

// ---------- 当前用户 ----------
const passwordVisible = ref(false)
const nickname = computed(() => store.info?.nickname ?? '')
const initial = computed(() => nickname.value.trim().charAt(0))

async function onCommand(command: string) {
  if (command === 'password') {
    passwordVisible.value = true
  } else if (command === 'logout') {
    // 页签里有没保存的修改，一起说清楚：退出以后这些页面都没了
    const dirty = tabs.dirtyTitles()
    const unsaved = dirty.length ? `「${dirty.join('」「')}」有没保存的修改，退出以后就没了。` : ''
    await ElMessageBox.confirm(`${unsaved}退出后要重新输入用户名和密码才能进来。`, '退出登录', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      confirmButtonClass: dirty.length ? 'el-button--danger' : undefined,
    })
    await store.logout()
    tabs.reset()
    router.replace('/login')
  }
}
</script>

<template>
  <div v-if="narrow && open" class="backdrop" @click="toggle" />
  <aside class="side" :class="{ shut: !open, float: narrow }" :inert="!open" :aria-label="area === 'admin' ? '管理后台导航' : '主导航'">
    <div class="side-inner">
      <div class="ws">
        <b class="mark" aria-hidden="true">{{ APP_MARK }}</b>
        <span class="name">{{ APP_NAME }}</span>
        <span v-if="area === 'admin'" class="area">管理后台</span>
        <button type="button" class="icon-btn" title="收起导航（[）" aria-label="收起导航" @click="toggle">
          <el-icon><Fold /></el-icon>
        </button>
      </div>

      <label class="find">
        <el-icon><Search /></el-icon>
        <input
          ref="searchInput"
          v-model="keyword"
          placeholder="搜索功能…"
          aria-label="搜索功能"
          @keydown.enter="goFirst"
          @keydown.esc="clearSearch"
        />
        <kbd v-if="!keyword">{{ SHORTCUT }}</kbd>
      </label>

      <nav class="nav">
        <template v-if="keyword.trim()">
          <button v-for="item in results" :key="item.path" type="button" class="nav-i" @click="go(item.path)">
            <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
            <span v-else class="no-icon" />
            <span class="label">{{ item.name }}</span>
            <small v-if="item.group">{{ item.group }}</small>
          </button>
          <p v-if="!results.length" class="none">没有叫"{{ keyword.trim() }}"的功能</p>
        </template>
        <template v-else>
          <div v-for="group in groups" :key="group.key" class="grp">
            <button
              v-if="group.name"
              type="button"
              class="grp-head"
              :class="{ shut: closed[group.key] }"
              :aria-expanded="!closed[group.key]"
              @click="toggleGroup(group.key)"
            >
              {{ group.name }}<el-icon><ArrowDown /></el-icon>
            </button>
            <template v-if="!group.name || !closed[group.key]">
              <router-link
                v-for="item in group.items"
                :key="item.path"
                :to="item.path"
                class="nav-i"
                :class="{ on: item.path === activePath }"
                :aria-current="item.path === activePath ? 'page' : undefined"
              >
                <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
                <span v-else class="no-icon" />
                <span class="label">{{ item.name }}</span>
              </router-link>
            </template>
          </div>
          <p v-if="!groups.length" class="none">暂无可用的功能</p>
        </template>
      </nav>

      <div class="side-foot">
        <router-link v-if="area === 'workbench' && store.isAdmin" to="/admin" class="nav-i util">
          <el-icon><Setting /></el-icon><span class="label">管理后台</span>
        </router-link>
        <router-link v-else-if="area === 'admin'" to="/home" class="nav-i util">
          <el-icon><Back /></el-icon><span class="label">回首页</span>
        </router-link>
        <el-dropdown class="me-wrap" trigger="click" placement="top-start" @command="onCommand">
          <button type="button" class="me" :aria-label="`${nickname}：改密码、退出登录`">
            <span class="avatar" aria-hidden="true">{{ initial }}</span>
            <span class="label">{{ nickname }}</span>
            <el-icon><ArrowUp /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="me-menu">
              <el-dropdown-item command="password" icon="Lock">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" icon="SwitchButton">退出登录</el-dropdown-item>
              <!-- 版本号只给人看，不是菜单项：报问题时报这个 -->
              <li v-if="version" class="version" role="presentation" :title="versionTitle">版本 {{ version }}</li>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
    <PasswordDialog v-model="passwordVisible" />
  </aside>
</template>

<style scoped>
.side {
  /* 侧边栏底比白底深，muted 在上面只有 4.46:1；侧边栏里的灰字统一换成深一点的 side-text-dim（4.8:1） */
  --lm-muted: var(--lm-side-text-dim);
  --lm-faint: var(--lm-side-text-dim);

  flex: none;
  width: var(--lm-side-width);
  overflow: hidden;
  color: var(--lm-side-text);
  background: var(--lm-greige);
  border-right: 1px solid var(--lm-line);
  transition:
    width 0.22s var(--lm-ease),
    opacity 0.18s var(--lm-ease),
    border-color 0.22s;
}

/* 收起：整个藏掉（原型），页头左边出一个"展开导航"按钮 */
.side.shut {
  width: 0;
  opacity: 0;
  pointer-events: none;
  border-right-color: transparent;
}

/* 窄屏：浮在页面上面 */
.side.float {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 2000;
  box-shadow: var(--lm-shadow-pop);
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 1999;
  background: var(--lm-mask);
}

/* 里面固定宽度：收起的动画里文字不会被挤得换行 */
.side-inner {
  display: flex;
  flex-direction: column;
  width: var(--lm-side-width);
  height: 100%;
}

/* ---------- 系统名 ---------- */
.ws {
  display: flex;
  flex: none;
  align-items: center;
  gap: 7px;
  height: 52px;
  padding: 0 10px 0 14px;
}

.mark {
  display: grid;
  flex: none;
  place-items: center;
  width: 22px;
  height: 22px;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1;
  color: var(--lm-paper);
  background: var(--lm-ink);
  border-radius: 6px;
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--lm-ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.area {
  flex: none;
  padding: 0 5px;
  font-size: 10.5px;
  line-height: 18px;
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border: 1px solid var(--lm-accent-line);
  border-radius: 5px;
}

.icon-btn {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 25px;
  height: 25px;
  padding: 0;
  font-size: 14px;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius-sm);
  opacity: 0.75;
}

.icon-btn:hover {
  color: var(--lm-ink);
  background: var(--lm-sunken);
  opacity: 1;
}

/* ---------- 搜索功能 ---------- */
.find {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: 32px;
  margin: 0 10px 10px;
  padding: 0 8px 0 10px;
  font-size: 12.5px;
  color: var(--lm-faint);
  cursor: text;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-sm);
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.find:hover {
  border-color: var(--lm-line-strong);
}

.find:focus-within {
  border-color: var(--lm-accent);
  box-shadow: var(--lm-focus-ring);
}

.find input {
  flex: 1;
  min-width: 0;
  padding: 0;
  font: inherit;
  color: var(--lm-ink);
  background: none;
  border: 0;
  outline: none;
}

.find input::placeholder {
  color: var(--lm-faint);
}

/* ---------- 菜单 ---------- */
.nav {
  flex: 1;
  min-height: 0;
  padding: 0 8px 8px;
  overflow-y: auto;
  scrollbar-width: none;
}

.nav::-webkit-scrollbar {
  display: none;
}

.grp + .grp {
  margin-top: 8px;
}

.grp-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 22px;
  padding: 0 8px;
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--lm-faint);
  cursor: pointer;
  user-select: none;
  background: none;
  border: 0;
}

.grp-head:hover {
  color: var(--lm-muted);
}

.grp-head .el-icon {
  margin-left: auto;
  font-size: 11px;
  opacity: 0.65;
  transition: transform 0.16s var(--lm-ease);
}

.grp-head.shut .el-icon {
  transform: rotate(-90deg);
}

.nav-i {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 29px;
  padding: 0 9px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-side-text);
  text-align: left;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 7px;
  transition:
    background 0.12s,
    color 0.12s;
}

.nav-i .el-icon,
.no-icon {
  flex: none;
  width: 15px;
  font-size: 15px;
  color: var(--lm-faint);
  transition: color 0.12s;
}

.nav-i .label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-i small {
  font-size: 11.5px;
  color: var(--lm-faint);
}

.nav-i:hover {
  color: var(--lm-ink);
  background: color-mix(in srgb, var(--lm-ink) 4.5%, transparent);
}

.nav-i:hover .el-icon {
  color: var(--lm-muted);
}

/* 当前页：一块白，细边，图标变主色 */
.nav-i.on {
  font-weight: 600;
  color: var(--lm-ink);
  background: var(--lm-paper);
  box-shadow:
    0 1px 2px color-mix(in srgb, var(--lm-ink) 6%, transparent),
    0 0 0 1px var(--lm-line);
}

.nav-i.on .el-icon {
  color: var(--lm-accent);
}

.nav-i:focus-visible,
.grp-head:focus-visible {
  outline: 2px solid var(--lm-accent);
  outline-offset: -2px;
}

.none {
  margin: 0;
  padding: 10px 9px;
  font-size: 12.5px;
  color: var(--lm-faint);
}

/* ---------- 底部：切换区域、当前用户 ---------- */
.side-foot {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 6px;
  padding: 8px 8px 10px;
  border-top: 1px solid var(--lm-line);
}

.util {
  height: 28px;
  font-size: 12.5px;
}

.me-wrap {
  display: block;
  width: 100%;
}

.me {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 8px;
  font: inherit;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-sm);
  transition: border-color 0.14s;
}

.me:hover {
  border-color: var(--lm-line-strong);
}

.me .avatar {
  display: grid;
  flex: none;
  place-items: center;
  width: 22px;
  height: 22px;
  font-size: 11px;
  font-style: normal;
  font-weight: 600;
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border: 1px solid var(--lm-accent-line);
  border-radius: 50%;
}

.me .label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.me .el-icon {
  font-size: 12px;
  color: var(--lm-faint);
}
</style>

<style>
/* 用户菜单挂在 body 下面，scoped 样式管不到 */
.me-menu {
  min-width: 180px;
}

.me-menu .version {
  margin-top: 5px;
  padding: 7px 10px 2px;
  font-size: 11.5px;
  color: var(--lm-faint);
  white-space: nowrap;
  list-style: none;
  border-top: 1px solid var(--lm-line-soft);
}
</style>
