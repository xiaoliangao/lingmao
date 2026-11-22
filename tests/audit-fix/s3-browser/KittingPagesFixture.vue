<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'

const route = useRoute()
const user = useUserStore()
user.info = {
  id: '9007199254740993', username: 'synthetic', nickname: '齐料页面回归', roles: [], menus: [],
  perms: ['order:order:list', 'production:order:list'], defaultCompany: { id: '1', name: '合成主体' },
}
setSessionSubject(user.info.id)
</script>

<template>
  <main class="kitting-pages-fixture">
    <aside class="fixture-caption">合成 HTTP 故障 · 实际页面 <output data-testid="route">{{ route.path }}</output></aside>
    <router-view :key="route.path" />
  </main>
</template>

<style scoped>
.kitting-pages-fixture { padding: 20px; min-height: 100vh; background: var(--lm-bg); }
.fixture-caption { display: flex; justify-content: space-between; color: var(--lm-text-secondary); margin-bottom: 16px; font-size: 12px; }
</style>
