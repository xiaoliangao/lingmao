<script setup lang="ts">
import BlankState from '@/components/BlankState.vue'
</script>

<template>
  <div class="not-found">
    <BlankState title="找不到这个页面" description="地址可能写错了，也可能你还没有这个页面的权限。需要的话找管理员开通。">
      <el-button type="primary" @click="$router.replace('/')">回首页</el-button>
    </BlankState>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
}
</style>
