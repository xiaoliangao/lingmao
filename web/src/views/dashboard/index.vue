<script setup lang="ts">
import { useUserStore } from '@/stores/user'

const store = useUserStore()
</script>

<template>
  <div class="page">
    <div class="page-card">
      <h3>你好，{{ store.info?.nickname }}</h3>
      <p>角色：{{ store.info?.roles.join('、') || '（没有角色）' }}</p>
      <p>业务模块（物料、采购、库存）等需求确定后上线。</p>
    </div>
  </div>
</template>
