<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import BlankState from '@/components/BlankState.vue'

// 菜单已经配好、页面还没做的功能，打开时显示这一页（见 router/index.ts 的 building）
</script>

<template>
  <div>
    <PageHeader />
    <BlankState :title="`${$route.meta.title ?? '这个功能'}还没上线，做好以后在这里打开`" />
  </div>
</template>
