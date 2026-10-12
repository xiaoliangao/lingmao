<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import SideMenuItem from './SideMenuItem.vue'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const title = computed(() => (route.meta.title as string | undefined) ?? '')

async function logout() {
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  await store.logout()
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">领猫 SCM</div>
      <el-menu :default-active="route.path" router background-color="#001529" text-color="#bfcbd9" active-text-color="#fff">
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon>
          <span>首页</span>
        </el-menu-item>
        <SideMenuItem v-for="menu in store.info?.menus ?? []" :key="menu.id" :menu="menu" parent-path="" />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ title }}</span>
        <el-dropdown @command="logout">
          <span class="user">
            {{ store.info?.nickname }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #001529;
  overflow-y: auto;
}

.aside .el-menu {
  border-right: none;
}

.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.title {
  font-size: 16px;
}

.user {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
}

.main {
  padding: 0;
}
</style>
