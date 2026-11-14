<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import BlankState from '@/components/BlankState.vue'

// 地址写错了，或者没有这个页面的权限。挂在首页这一侧的外框里，左边菜单照常能点
</script>

<template>
  <div>
    <PageHeader title="页面不存在" />
    <BlankState title="找不到这个页面：地址可能写错了，也可能还没给你开通，需要的话找管理员">
      <el-button @click="$router.replace('/home')">回首页</el-button>
    </BlankState>
  </div>
</template>
