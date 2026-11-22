<script setup lang="ts">
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'

const user = useUserStore()
user.info = {
  id: '9007199254740993', username: 'synthetic', nickname: '有界清单回归', roles: [], menus: [],
  perms: ['purchase:order:list', 'purchase:order:add', 'inventory:stock:list', 'inventory:doc:add', 'techpack:cost:query'],
  defaultCompany: { id: '1', name: '合成主体' },
}
setSessionSubject(user.info.id)
</script>

<template><main class="bounded-lists-fixture"><router-view /></main></template>
<style scoped>.bounded-lists-fixture { padding: 24px; min-height: 100vh; background: var(--lm-bg); }</style>
