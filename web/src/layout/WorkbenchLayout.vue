<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useTabsStore } from '@/stores/tabs'
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
import TabsBar from './TabsBar.vue'
import { useSidebar } from './useSidebar'

// 工作台：所有人登录后都在这里。左边业务菜单，顶上页签，打开过的页面用 keep-alive 留着
const route = useRoute()
const store = useUserStore()
const tabs = useTabsStore()
const { closeDrawer } = useSidebar()

watch(
  () => route.path,
  () => {
    tabs.open(route)
    closeDrawer()
  },
  { immediate: true },
)
</script>

<template>
  <div class="shell">
    <AppSidebar area="workbench" :menus="store.workbenchMenus" />
    <div class="shell-main">
      <AppHeader area="workbench">
        <TabsBar />
      </AppHeader>
      <main class="shell-content">
        <router-view v-slot="{ Component }">
          <keep-alive :include="tabs.cacheNames" :max="12">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </main>
    </div>
  </div>
</template>
