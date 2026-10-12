<script setup lang="ts">
import type { MenuNode } from '@/types/system'
import { joinPath } from '@/router'

// 递归组件：目录渲染成可展开的子菜单，菜单渲染成一项
const props = defineProps<{ menu: MenuNode; parentPath: string }>()
const fullPath = joinPath(props.parentPath, props.menu.path ?? '')
</script>

<template>
  <el-sub-menu v-if="menu.type === 1" :index="fullPath">
    <template #title>
      <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
      <span>{{ menu.name }}</span>
    </template>
    <SideMenuItem v-for="child in menu.children" :key="child.id" :menu="child" :parent-path="fullPath" />
  </el-sub-menu>
  <el-menu-item v-else :index="fullPath">
    <el-icon v-if="menu.icon"><component :is="menu.icon" /></el-icon>
    <span>{{ menu.name }}</span>
  </el-menu-item>
</template>
