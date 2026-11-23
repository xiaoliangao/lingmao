<script setup lang="ts">
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
const user = useUserStore()
user.info = {
  id: '9007199254740993', username: 'synthetic-inventory-numeric', nickname: '合成数值边界验收',
  roles: [], perms: ['inventory:doc:list', 'techpack:cost:query'], menus: [],
  defaultCompany: { id: '1', name: '合成主体甲' },
}
setSessionSubject(user.info.id)
</script>
<template><router-view /></template>
