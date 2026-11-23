<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getToken, setSessionSubject, setToken } from '@/utils/token'
import type { MenuNode } from '@/types/system'
const user = useUserStore()
const route = useRoute()
const price = new URLSearchParams(location.search).get('price') !== 'no'
const menu = (id: string, name: string, path: string): MenuNode => ({ id, name, path, parentId: '0', type: 2, component: null,
  perms: null, icon: 'Box', sort: 0, visible: true, status: 1, adminOnly: false, children: [] })
user.info = { id: '9007199254740993', username: 'synthetic-fg', nickname: '成品仓回归', roles: [],
  perms: ['fg:doc:list', 'fg:doc:add', 'fg:doc:edit', 'fg:doc:audit', 'fg:doc:export', 'fg:stock:list', 'fg:stock:export',
    ...(price ? ['order:price:query'] : [])],
  menus: [menu('9001', '成品入库单', '/fg-in'), menu('9002', '成品出库单', '/fg-out'), menu('9003', '成品库存', '/fg-stock')],
  defaultCompany: { id: '1', name: '合成主体甲' },
}
setSessionSubject(user.info.id)
function nextIdentity() {
  setToken('synthetic-fg-next-session'); user.token = getToken()
  if (user.info) user.info = { ...user.info, id: '9007199254740994', username: 'synthetic-next' }
  setSessionSubject('9007199254740994')
}
</script>
<template>
  <router-view />
  <aside class="fixture-status">
    <span>合成 HTTP · 实际组件</span><output data-testid="route">{{ route.fullPath }}</output>
    <button type="button" data-testid="next-identity" @click="nextIdentity">切换测试身份</button>
  </aside>
</template>
<style scoped>
.fixture-status { position: fixed; z-index: 4000; right: 6px; bottom: 2px; display: flex; gap: 10px; font-size: 10px; color: #667085; background: #fff; }
.fixture-status button { border: 0; background: transparent; color: inherit; font-size: inherit; }
</style>
