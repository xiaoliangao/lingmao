<script setup lang="ts">
import { useUserStore } from '@/stores/user'
import { getToken, setToken, setSessionSubject } from '@/utils/token'
import { TAG_PERMISSIONS } from '@/types/tag'
import type { MenuNode } from '@/types/system'
const user = useUserStore()
const all = [...new Set(Object.values(TAG_PERMISSIONS).flatMap((item) => [item.read, item.write])), 'biz:tag:list', 'biz:tag:manage']
const parameters = new URLSearchParams(location.search)
const requested = parameters.get('permissions')
const perms = requested === null ? all : requested ? requested.split(',') : []
const menu = (id: string, name: string, path: string, component: string): MenuNode => ({
  id, name, path, component, parentId: '0', type: 2, perms: null, icon: 'PriceTag', sort: 60, visible: true, status: 1, adminOnly: false, children: [],
})
user.info = { id: '9007199254740993', username: 'synthetic-public-tag', nickname: '合成标签回归', roles: [], perms,
  menus: [menu('1001', '公共标签库', '/tags', 'biz/tag/index'), menu('1002', '对象标签验证', '/tag-objects', 'tags-fixture')],
  defaultCompany: { id: '1', name: '合成主体甲' } }
setSessionSubject(user.info.id)
function identity() {
  setToken('synthetic-tag-next-identity'); user.token = getToken()
  if (user.info) user.info = { ...user.info, id: '9007199254740994' }
  setSessionSubject('9007199254740994')
}
function role(kind: string) {
  if (!user.info) return
  user.info = { ...user.info, perms: kind === 'none' ? [] : kind === 'read' ? all.filter((perm) => perm.endsWith(':list')) : all }
}
function company() {
  if (user.info) user.info = { ...user.info, defaultCompany: { id: '2', name: '合成主体乙' } }
}
</script>
<template>
  <router-view />
  <aside class="fixture-controls">
    <span>合成 HTTP · 实际 Vue 组件</span>
    <button type="button" data-testid="tag-identity" @click="identity">切换身份</button>
    <button type="button" data-testid="tag-readonly" @click="role('read')">取消写权限</button>
    <button type="button" data-testid="tag-no-permissions" @click="role('none')">取消全部权限</button>
    <button type="button" data-testid="tag-full-permissions" @click="role('all')">恢复权限</button>
    <button type="button" data-testid="tag-company" @click="company">切换主体</button>
  </aside>
</template>
<style scoped>
.fixture-controls { position: fixed; right: 4px; bottom: 2px; z-index: 4000; display: flex; gap: 8px; padding: 2px; background: var(--lm-paper); color: var(--lm-muted); font-size: 10px; }
.fixture-controls button { font: inherit; color: inherit; cursor: pointer; background: none; border: 0; }
</style>
