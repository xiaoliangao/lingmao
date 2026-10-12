<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useSidebar } from './useSidebar'

// 顶栏：左边由各区域自己放（工作台放页签，管理后台放位置），右边是切换区域和当前用户
defineProps<{ area: 'workbench' | 'admin' }>()

const router = useRouter()
const store = useUserStore()
const { narrow, openDrawer } = useSidebar()

async function onCommand(command: string) {
  if (command === 'logout') {
    await ElMessageBox.confirm('退出后需要重新登录。', '退出登录', { confirmButtonText: '退出登录', type: 'warning' })
    await store.logout()
    router.replace('/login')
  }
}
</script>

<template>
  <header class="app-header">
    <button v-if="narrow" type="button" class="menu-button" aria-label="打开菜单" @click="openDrawer">
      <el-icon><Expand /></el-icon>
    </button>
    <div class="left">
      <slot />
    </div>
    <div class="right">
      <router-link v-if="area === 'workbench' && store.isAdmin" to="/admin" class="switch" aria-label="管理后台">
        <el-icon><Setting /></el-icon><span class="switch-text">管理后台</span>
      </router-link>
      <router-link v-if="area === 'admin'" to="/home" class="switch" aria-label="回到工作台">
        <el-icon><Back /></el-icon><span class="switch-text">回到工作台</span>
      </router-link>
      <el-dropdown trigger="click" @command="onCommand">
        <button type="button" class="user">
          {{ store.info?.nickname }}
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  flex-shrink: 0;
  align-items: stretch;
  justify-content: space-between;
  height: var(--lm-header-height);
  background: var(--lm-paper);
  border-bottom: 1px solid var(--lm-line);
}

.left {
  display: flex;
  flex: 1;
  align-items: stretch;
  min-width: 0;
}

.right {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  padding: 0 16px 0 8px;
}

.switch,
.user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink-2);
  text-decoration: none;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius);
}

.switch:hover,
.user:hover {
  color: var(--lm-indigo-600);
  background: var(--lm-indigo-50);
}

.menu-button {
  flex-shrink: 0;
  width: 48px;
  font-size: 18px;
  color: var(--lm-ink-2);
  cursor: pointer;
  background: none;
  border: 0;
  border-right: 1px solid var(--lm-line-soft);
}

/* 手机上只留图标，给页签腾地方 */
@media (max-width: 767px) {
  .switch-text {
    display: none;
  }

  .right {
    padding-right: 8px;
  }
}
</style>
