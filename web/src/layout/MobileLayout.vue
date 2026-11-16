<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { APP_MARK, APP_NAME } from '@/config'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'

/**
 * 手机报工的外框（/mobile、/mobile/:id，router 里的 STANDALONE_PAGES）：单独一页，没有侧边栏、页签，
 * 顶上一窄条只有系统名、当前用户和退出。手机上登录后直接进这里；电脑上点侧边栏的"手机报工"在浏览器新标签里打开
 */
const router = useRouter()
const store = useUserStore()
const tabs = useTabsStore()

async function logout() {
  await ElMessageBox.confirm('退出后要重新输入用户名和密码才能进来。', '退出登录', {
    confirmButtonText: '退出登录',
    cancelButtonText: '取消',
  })
  await store.logout()
  tabs.reset()
  router.replace('/login')
}
</script>

<template>
  <div class="m-app">
    <header class="m-app-bar">
      <b class="mark" aria-hidden="true">{{ APP_MARK }}</b>
      <span class="name">{{ APP_NAME }}</span>
      <span class="who">{{ store.info?.nickname }}</span>
      <button type="button" class="logout" @click="logout">退出</button>
    </header>
    <router-view />
  </div>
</template>

<style scoped>
.m-app {
  min-height: 100dvh;
  background: var(--lm-greige-2);
}

.m-app-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 560px;
  height: 44px;
  margin: 0 auto;
  padding: 0 14px;
  font-size: 13px;
  border-bottom: 1px solid var(--lm-line-soft);
}

.mark {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1;
  color: var(--lm-paper);
  background: var(--lm-ink);
  border-radius: 6px;
}

.name {
  flex: 1;
  font-weight: 600;
}

.who {
  color: var(--lm-muted);
}

/* 手指点的地方高 44px 以上 */
.logout {
  height: 44px;
  padding: 0 4px 0 10px;
  font: inherit;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: 0;
}
</style>
