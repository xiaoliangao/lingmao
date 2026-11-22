<script setup lang="ts">
import UpstreamPage from '@/views/home/upstream.vue'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
const user = useUserStore()
user.info = { id: '9007199254740993', username: 'synthetic', nickname: '上游回归', roles: [], perms: [], menus: [], defaultCompany: { id: '1', name: '合成主体' } }
setSessionSubject(user.info.id)
</script>
<template><main class="upstream-fixture"><UpstreamPage /></main></template>
<style scoped>.upstream-fixture { padding: 24px; min-height: 100vh; background: var(--lm-bg); }</style>
