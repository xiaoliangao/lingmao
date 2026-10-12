import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import type { UserInfo } from '@/types/system'
import { clearToken, getToken, setToken } from '@/utils/token'

/** 当前登录的人：token、基本信息、权限、菜单 */
export const useUserStore = defineStore('user', () => {
  const token = ref(getToken())
  const info = ref<UserInfo | null>(null)
  const perms = computed(() => new Set(info.value?.perms ?? []))
  /** 超级管理员：能进管理后台 */
  const isAdmin = computed(() => info.value?.roles.includes('admin') ?? false)
  /** 工作台的菜单（业务功能）和管理后台的菜单，按顶层目录分开 */
  const workbenchMenus = computed(() => (info.value?.menus ?? []).filter((m) => !m.adminOnly))
  const adminMenus = computed(() => (info.value?.menus ?? []).filter((m) => m.adminOnly))

  async function login(username: string, password: string) {
    const newToken = await authApi.login(username, password)
    setToken(newToken)
    token.value = newToken
  }

  async function fetchMe() {
    info.value = await authApi.me()
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      clearToken()
      token.value = ''
      info.value = null
    }
  }

  /** 有没有某个权限标识：按钮显不显示就看它 */
  function hasPerm(perm: string) {
    return perms.value.has(perm)
  }

  return { token, info, isAdmin, workbenchMenus, adminMenus, login, fetchMe, logout, hasPerm }
})
