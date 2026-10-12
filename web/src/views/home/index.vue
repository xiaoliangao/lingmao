<script setup lang="ts">
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { joinPath } from '@/utils/path'
import type { MenuNode } from '@/types/system'

// 工作台首页：问候、待办、我能用的功能。名字给 keep-alive 用（见 stores/tabs.ts）
defineOptions({ name: 'WorkbenchHome' })

const store = useUserStore()

const now = new Date()
const greeting = (() => {
  const hour = now.getHours()
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 13) return '中午好'
  if (hour >= 13 && hour < 18) return '下午好'
  return '晚上好'
})()
const today = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(now)

interface PageLink {
  name: string
  path: string
  icon: string | null
}

function pagesOf(menus: MenuNode[], parentPath: string): PageLink[] {
  return menus.flatMap((menu) => {
    const fullPath = joinPath(parentPath, menu.path ?? '')
    if (menu.type === 2) return [{ name: menu.name, path: fullPath, icon: menu.icon }]
    return pagesOf(menu.children, fullPath)
  })
}

/** 我的功能：按侧边栏的目录分组 */
const groups = computed(() =>
  store.workbenchMenus
    .map((menu) =>
      menu.type === 1
        ? { name: menu.name, pages: pagesOf(menu.children, menu.path ?? '') }
        : { name: '', pages: pagesOf([menu], '') },
    )
    .filter((group) => group.pages.length > 0),
)
</script>

<template>
  <div class="page home">
    <header class="hello">
      <h1>{{ greeting }}，{{ store.info?.nickname }}</h1>
      <p>{{ today }}</p>
    </header>

    <div class="columns">
      <section class="panel block">
        <h2>待办</h2>
        <p class="empty">
          没有要你处理的单据。<br />
          采购单、入库单上线以后，等你审核、等你入库的单据会列在这里。
        </p>
      </section>

      <section class="panel block">
        <h2>我的功能</h2>
        <div v-for="group in groups" :key="group.name" class="group">
          <h3 v-if="group.name">{{ group.name }}</h3>
          <ul>
            <li v-for="page in group.pages" :key="page.path">
              <router-link :to="page.path">
                <el-icon v-if="page.icon"><component :is="page.icon" /></el-icon>
                {{ page.name }}
              </router-link>
            </li>
          </ul>
        </div>
        <p v-if="groups.length === 0" class="empty">
          还没有给你开通功能。
          <template v-if="store.isAdmin">
            在<router-link to="/admin">管理后台</router-link>的角色管理里给角色分配菜单，再把角色分给用户。
          </template>
          <template v-else>找管理员给你分配。</template>
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hello {
  margin-bottom: 20px;
}

.hello h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.hello p {
  margin: 4px 0 0;
  color: var(--lm-muted);
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: 16px;
  align-items: start;
}

.block {
  padding: 18px 20px 20px;
}

h2 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
}

.empty {
  margin: 0;
  color: var(--lm-muted);
}

.empty a {
  color: var(--el-color-primary);
}

.group + .group {
  margin-top: 14px;
}

h3 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--lm-muted);
}

ul {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

li a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  color: var(--lm-ink-2);
  text-decoration: none;
  background: var(--lm-greige-2);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

li a:hover {
  color: var(--lm-indigo-600);
  border-color: var(--el-color-primary-light-7);
}

@media (width < 900px) {
  .columns {
    grid-template-columns: 1fr;
  }
}
</style>
