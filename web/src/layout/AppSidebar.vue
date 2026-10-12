<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/utils/path'
import SideMenuItem from './SideMenuItem.vue'
import { useSidebar } from './useSidebar'

// 侧边栏：靛蓝斜纹布底，当前页用金黄明线缝出来。工作台多一个"搜索功能"
const props = defineProps<{ menus: MenuNode[]; area: 'workbench' | 'admin' }>()
const { collapsed: wideCollapsed, narrow, drawerOpen, toggle, closeDrawer } = useSidebar()
// 抽屉里一直是展开的样子
const collapsed = computed(() => wideCollapsed.value && !narrow.value)

const route = useRoute()
const router = useRouter()

// ---------- 搜索功能：按名字找页面，不用一层层展开菜单 ----------
const keyword = ref('')

interface PageEntry {
  name: string
  path: string
  group: string
}

function collectPages(menus: MenuNode[], parentPath: string, group: string): PageEntry[] {
  return menus.flatMap((menu) => {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2) return [{ name: menu.name, path: fullPath, group }]
    return collectPages(menu.children, fullPath, menu.name)
  })
}

const results = computed(() => {
  const word = keyword.value.trim()
  if (!word) return []
  return collectPages(props.menus, '', '').filter((page) => page.name.includes(word))
})

function go(path: string) {
  keyword.value = ''
  router.push(path)
}
</script>

<template>
  <div v-if="narrow && drawerOpen" class="backdrop" @click="closeDrawer" />
  <aside class="sidebar" :class="{ collapsed, drawer: narrow, open: drawerOpen }">
    <div class="brand">
      <span class="brand-cn">领猫</span>
      <template v-if="!collapsed">
        <span class="brand-en">SCM</span>
        <span v-if="area === 'admin'" class="brand-area">管理后台</span>
      </template>
    </div>

    <div v-if="area === 'workbench' && !collapsed" class="search">
      <el-input v-model="keyword" placeholder="搜索功能" clearable prefix-icon="Search" aria-label="搜索功能" />
    </div>

    <nav class="nav" :aria-label="area === 'admin' ? '管理后台菜单' : '功能菜单'">
      <template v-if="keyword.trim()">
        <ul v-if="results.length" class="results">
          <li v-for="page in results" :key="page.path">
            <button type="button" @click="go(page.path)">
              <span>{{ page.name }}</span>
              <small>{{ page.group }}</small>
            </button>
          </li>
        </ul>
        <p v-else class="no-result">没有叫"{{ keyword.trim() }}"的功能</p>
      </template>
      <el-menu v-else :default-active="route.path" :collapse="collapsed" :collapse-transition="false" router>
        <el-menu-item v-if="area === 'workbench'" index="/home">
          <el-icon><HomeFilled /></el-icon>
          <template #title>首页</template>
        </el-menu-item>
        <SideMenuItem v-for="menu in menus" :key="menu.id" :menu="menu" parent-path="" />
      </el-menu>
    </nav>

    <button v-if="!narrow" class="fold" type="button" :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="toggle">
      <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
      <span v-if="!collapsed">收起</span>
    </button>
  </aside>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: var(--lm-side-width);
  background: var(--lm-twill), var(--lm-indigo-900);
  color: var(--lm-side-text);
}

.sidebar.collapsed {
  width: var(--lm-side-width-collapsed);
}

/* 窄屏：抽屉，从左边滑出来 */
.sidebar.drawer {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 2000;
  width: 240px;
  transform: translateX(-100%);
  transition: transform 0.2s ease-out;
}

.sidebar.drawer.open {
  transform: none;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 1999;
  background: rgb(20 32 56 / 45%);
}

/* ---------- 品牌 ---------- */
.brand {
  display: flex;
  align-items: baseline;
  gap: 6px;
  height: 56px;
  padding: 0 20px;
  line-height: 56px;
  border-bottom: 1px solid rgb(255 255 255 / 6%);
}

.collapsed .brand {
  justify-content: center;
  padding: 0;
}

.brand-cn {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #fff;
}

.collapsed .brand-cn {
  font-size: 15px;
}

.brand-en {
  font-family: var(--lm-font-num);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--lm-stitch);
}

.brand-area {
  align-self: center;
  margin-left: auto;
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
  color: var(--lm-side-text);
  border: 1px dashed rgb(217 162 58 / 70%);
  border-radius: 3px;
}

/* ---------- 搜索 ---------- */
.search {
  padding: 12px 12px 4px;
}

.search :deep(.el-input__wrapper) {
  background: rgb(255 255 255 / 7%);
  box-shadow: none;
}

.search :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--lm-stitch) inset;
}

.search :deep(.el-input__inner) {
  color: #fff;
}

.search :deep(.el-input__inner::placeholder),
.search :deep(.el-input__prefix),
.search :deep(.el-input__suffix) {
  color: var(--lm-side-text-dim);
}

.results {
  margin: 0;
  padding: 4px 8px;
  list-style: none;
}

.results button {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  width: 100%;
  padding: 9px 12px;
  font: inherit;
  color: var(--lm-side-text);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 4px;
}

.results button:hover {
  color: #fff;
  background: rgb(255 255 255 / 7%);
}

.results small {
  font-size: 12px;
  color: var(--lm-side-text-dim);
}

.no-result {
  margin: 0;
  padding: 12px 20px;
  font-size: 13px;
  color: var(--lm-side-text-dim);
}

/* ---------- 菜单 ---------- */
.nav {
  flex: 1;
  overflow: hidden auto;
  padding: 6px 0 12px;
}

.nav :deep(.el-menu) {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--lm-side-text);
  --el-menu-hover-text-color: #fff;
  --el-menu-hover-bg-color: rgb(255 255 255 / 6%);
  --el-menu-active-color: #fff;
  --el-menu-item-height: 42px;
  --el-menu-sub-item-height: 40px;
  --el-menu-base-level-padding: 20px;
  --el-menu-level-padding: 16px;

  border-right: none;
}

.nav :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
}

/* 当前页：像一块缝在牛仔布上的布标，四周一圈金黄色明线 */
.nav :deep(.el-menu-item.is-active) {
  position: relative;
  font-weight: 600;
  background: rgb(255 255 255 / 7%);
}

.nav :deep(.el-menu-item.is-active::after) {
  position: absolute;
  inset: 5px 10px;
  pointer-events: none;
  content: '';
  border: 1px dashed var(--lm-stitch);
  border-radius: 4px;
}

.nav :deep(.el-menu-item:focus-visible),
.nav :deep(.el-sub-menu__title:focus-visible) {
  outline: 2px solid var(--lm-stitch);
  outline-offset: -2px;
}

/* ---------- 收起 ---------- */
.fold {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 22px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-side-text-dim);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-top: 1px solid rgb(255 255 255 / 6%);
}

.collapsed .fold {
  justify-content: center;
  padding: 0;
}

.fold:hover {
  color: #fff;
}
</style>

<style>
/* 侧边栏收起时，子菜单弹出层挂在 body 下面，scoped 样式管不到，单独写 */
.lm-side-popper .el-menu {
  --el-menu-bg-color: var(--lm-indigo-900);
  --el-menu-text-color: var(--lm-side-text);
  --el-menu-hover-text-color: #fff;
  --el-menu-hover-bg-color: rgb(255 255 255 / 8%);
  --el-menu-active-color: #fff;
}
</style>
