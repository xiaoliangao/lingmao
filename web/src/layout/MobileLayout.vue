<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PasswordDialog from './PasswordDialog.vue'
import { APP_MARK, APP_NAME } from '@/config'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'

/**
 * 手机报工的外框（/mobile、/mobile/:id，router 里的 STANDALONE_PAGES）：单独一页，没有侧边栏、页签，
 * 顶上一窄条只有系统名、当前用户、改密码和退出。手机上登录后直接进这里；电脑上点侧边栏的"手机报工"在浏览器新标签里打开。
 * 工厂账号（RFC 019 §7）手机、电脑都只有这一页，没有侧边栏，改密码只能在这里。
 * 内部账号两页都有的（跟单、主管）在顶条下面切"报工 / 待办"，再给一个"电脑版"（去首页；手机上只有打开 / 才改道到这里）
 */
const router = useRouter()
const route = useRoute()
const store = useUserStore()
const tabs = useTabsStore()
const passwordVisible = ref(false)

const PAGES = [{ path: '/mobile', label: '报工' }, { path: '/mobile-todo', label: '待办' }]
const switchable = computed(() => {
  if (store.info?.factory) return []
  const added = new Set(router.getRoutes().map((r) => r.path))
  return PAGES.filter((p) => added.has(p.path))
})
const current = computed(() => PAGES.find((p) => route.path === p.path || route.path.startsWith(`${p.path}/`))?.path)

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
      <button type="button" class="logout" @click="passwordVisible = true">改密码</button>
      <button type="button" class="logout" @click="logout">退出</button>
    </header>
    <nav v-if="!store.info?.factory" class="m-switch" aria-label="切换页面">
      <template v-if="switchable.length > 1">
        <router-link v-for="p in switchable" :key="p.path" :to="p.path" class="m-switch-item" :class="{ on: current === p.path }">
          {{ p.label }}
        </router-link>
      </template>
      <router-link to="/home" class="m-switch-item desktop">电脑版</router-link>
    </nav>
    <router-view />
    <PasswordDialog v-model="passwordVisible" />
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

.m-switch {
  display: flex;
  gap: 4px;
  max-width: 560px;
  margin: 0 auto;
  padding: 0 10px;
  border-bottom: 1px solid var(--lm-line-soft);
}

/* 手指点的地方高 44px 以上 */
.m-switch-item {
  display: grid;
  place-items: center;
  min-width: 56px;
  height: 44px;
  padding: 0 8px;
  font-size: 14px;
  color: var(--lm-muted);
  text-decoration: none;
  border-bottom: 2px solid transparent;
}

.m-switch-item.on {
  font-weight: 600;
  color: var(--lm-ink);
  border-bottom-color: var(--lm-accent);
}

.m-switch-item.desktop {
  margin-left: auto;
  font-size: 13px;
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
