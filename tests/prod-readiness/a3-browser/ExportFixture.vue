<script setup lang="ts">
import StockPage from '@/views/inventory/stock/index.vue'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'

const id = new URLSearchParams(location.search).get('owner') ?? '9007199254740993'
const user = useUserStore()
user.info = { id, username: 'synthetic', nickname: '导出回归', roles: [], perms: ['inventory:stock:list', 'inventory:stock:export'], menus: [], defaultCompany: { id: '1', name: '测试主体' } }
setSessionSubject(id)
</script>
<template><main class="export-fixture"><StockPage /></main></template>
<style scoped>.export-fixture { padding: 20px; min-height: 100vh; }</style>
