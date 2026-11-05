<script setup lang="ts">
import BizErrorDialog from '@/components/BizErrorDialog.vue'
</script>

<template>
  <router-view />
  <!-- 全局只有一个：request.ts 收到带清单的错误时打开它 -->
  <BizErrorDialog />
</template>
