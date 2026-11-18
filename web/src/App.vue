<script setup lang="ts">
import PendingActions from '@/components/PendingActions.vue'
import RecoveryBanner from '@/components/RecoveryBanner.vue'
import ModuleBoundary from '@/components/ModuleBoundary.vue'
import BizErrorDialog from '@/components/BizErrorDialog.vue'
</script>

<template>
  <!-- 首页这一侧（/）和管理后台（/admin）用的是同一个外框组件，按区域给 key，切换区域时各建各的，页签缓存不串 -->
  <router-view v-slot="{ Component, route }">
    <component :is="Component" :key="route.matched[0]?.path" />
  </router-view>
  <!-- 全局只有一个：request.ts 收到带清单的错误时打开它 -->
  <ModuleBoundary><BizErrorDialog /></ModuleBoundary>
  <RecoveryBanner />
  <ModuleBoundary><PendingActions /></ModuleBoundary>
</template>
