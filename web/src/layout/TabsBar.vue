<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { HOME_TAB, useTabsStore } from '@/stores/tabs'

// 工作台的页签：开过的页面都在这里，点一下切回去，页面状态还在
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

function close(path: string) {
  const next = tabs.close(path, route.path)
  if (next !== route.path) router.push(next)
}
</script>

<template>
  <nav class="tabs" aria-label="打开的页面">
    <div v-for="tab in tabs.tabs" :key="tab.path" class="tab" :class="{ active: route.path === tab.path }">
      <router-link :to="tab.path" :aria-current="route.path === tab.path ? 'page' : undefined">{{ tab.title }}</router-link>
      <button v-if="tab.path !== HOME_TAB.path" type="button" class="close" :aria-label="`关闭${tab.title}`" @click="close(tab.path)">
        <el-icon><Close /></el-icon>
      </button>
    </div>
  </nav>
</template>

<style scoped>
.tabs {
  display: flex;
  align-items: stretch;
  overflow-x: auto;
  scrollbar-width: none;
}

.tab {
  position: relative;
  display: flex;
  flex-shrink: 0;
  align-items: center;
  border-right: 1px solid var(--lm-line-soft);
}

.tab a {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 16px;
  font-size: 13px;
  color: var(--lm-muted);
  text-decoration: none;
}

.tab:has(.close) a {
  padding-right: 4px;
}

.tab a:hover {
  color: var(--lm-ink);
}

.tab.active a {
  font-weight: 600;
  color: var(--lm-ink);
}

/* 当前页签底下一条靛蓝 */
.tab.active::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  content: '';
  background: var(--lm-indigo-600);
}

.close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: 10px;
  padding: 0;
  font-size: 12px;
  color: var(--lm-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 3px;
}

.close:hover {
  color: var(--lm-ink);
  background: var(--lm-greige);
}
</style>
