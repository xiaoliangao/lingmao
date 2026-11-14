<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { backupApi } from '@/api/system'
import BlankState from '@/components/BlankState.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useUserStore } from '@/stores/user'
import { formatDateTime, formatFileSize } from '@/utils/format'
import { joinPath } from '@/utils/path'
import type { BackupStatus, MenuNode } from '@/types/system'

// 首页：待办、我能用的功能；超级管理员多一行数据库备份的情况。名字给 keep-alive 用（见 stores/tabs.ts）
defineOptions({ name: 'HomePage' })

const store = useUserStore()

const now = new Date()
const greeting = (() => {
  const hour = now.getHours()
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 13) return '中午好'
  if (hour >= 13 && hour < 18) return '下午好'
  return '晚上好'
})()
const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const today = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${WEEKDAYS[now.getDay()]}`
const sub = computed(() => `${greeting}，${store.info?.nickname ?? ''} · ${today}`)

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

// ---------- 数据库备份：只有超级管理员看。停了要显眼，正常只留一行小字 ----------
const backup = ref<BackupStatus | null>(null)

// 页签切回首页时也重新取一次（第一次打开也会触发）
onActivated(async () => {
  if (!store.isAdmin) return
  try {
    backup.value = await backupApi.status()
  } catch {
    // 取不到不打扰，下次回到首页再取
  }
})

/** 备份停了：红色提示。没配备份（本地开发）或者备份正常时是空的 */
const backupAlert = computed(() => {
  const status = backup.value
  if (!status?.configured || !status.stale) return ''
  return status.latestTime
    ? `最近一次备份是 ${formatDateTime(status.latestTime)}，已超过 36 小时，请联系部署人员检查备份`
    : '还没有任何备份，请联系部署人员检查备份'
})

/** 备份正常：一行小字 */
const backupLine = computed(() => {
  const status = backup.value
  if (!status?.configured || status.stale || !status.latestTime) return ''
  const size = status.sizeBytes == null ? '' : `（${formatFileSize(status.sizeBytes)}）`
  return `最近备份：${formatDateTime(status.latestTime)}${size}`
})
</script>

<template>
  <div class="home">
    <PageHeader title="首页" :sub="sub" />

    <div class="wrap">
      <el-alert v-if="backupAlert" type="error" :title="backupAlert" :closable="false" show-icon />

      <div class="columns">
        <section class="panel">
          <h2 class="panel-head">待办</h2>
          <BlankState title="暂无待办" />
        </section>

        <section class="panel">
          <h2 class="panel-head">我的功能</h2>
          <div v-if="groups.length" class="panel-body">
            <div v-for="group in groups" :key="group.name" class="group">
              <h3 v-if="group.name">{{ group.name }}</h3>
              <div class="quick">
                <router-link v-for="page in group.pages" :key="page.path" :to="page.path">
                  <el-icon v-if="page.icon"><component :is="page.icon" /></el-icon>
                  <span>{{ page.name }}</span>
                </router-link>
              </div>
            </div>
          </div>
          <BlankState v-else title="还没有给你开通功能">
            <el-button v-if="store.isAdmin" @click="$router.push('/admin')">去管理后台分配</el-button>
          </BlankState>
        </section>
      </div>

      <p v-if="backupLine" class="backup-line">{{ backupLine }}</p>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100%;
  background: var(--lm-greige-2);
}

.wrap {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 22px 26px 42px;
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  gap: 18px;
  align-items: start;
}

.group + .group {
  margin-top: 14px;
}

h3 {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--lm-faint);
}

.quick {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(124px, 1fr));
  gap: 8px;
}

.quick a {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 9px 10px;
  font-size: 12.5px;
  color: var(--lm-muted);
  text-decoration: none;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
  transition: all 0.14s var(--lm-ease);
}

.quick a span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quick a .el-icon {
  flex: none;
  color: var(--lm-faint);
}

.quick a:hover {
  color: var(--lm-ink);
  background: var(--lm-paper);
  border-color: var(--lm-line-strong);
  box-shadow: var(--lm-shadow-sm);
}

.quick a:hover .el-icon {
  color: var(--lm-accent);
}

.backup-line {
  font-size: 12px;
  color: var(--lm-faint);
  text-align: right;
}

@media (max-width: 1200px) {
  .columns {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .wrap {
    padding: 16px;
  }
}
</style>
