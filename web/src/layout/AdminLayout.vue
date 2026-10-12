<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
import { useSidebar } from './useSidebar'

// 管理后台：只有超级管理员有这块路由（见 router/index.ts）。顶栏左边显示现在在哪
const route = useRoute()
const store = useUserStore()
const { closeDrawer } = useSidebar()

watch(() => route.path, closeDrawer)
</script>

<template>
  <div class="shell">
    <AppSidebar area="admin" :menus="store.adminMenus" />
    <div class="shell-main">
      <AppHeader area="admin">
        <p class="crumb">
          <span v-if="route.meta.group" class="muted">{{ route.meta.group }}</span>
          <span v-if="route.meta.group" class="muted" aria-hidden="true">/</span>
          <span>{{ route.meta.title }}</span>
        </p>
      </AppHeader>
      <main class="shell-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 0 24px;
  font-size: 13px;
}
</style>
