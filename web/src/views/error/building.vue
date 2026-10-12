<script setup lang="ts">
import BlankState from '@/components/BlankState.vue'

// 菜单已经配好、页面还没做的功能，打开时显示这一页（见 router/index.ts 的 building）
</script>

<template>
  <div class="page">
    <BlankState :title="`${$route.meta.title ?? '这个功能'}还没上线`" description="菜单已经开通，页面做好以后就能在这里打开。" />
  </div>
</template>
