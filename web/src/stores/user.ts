import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import type { UserInfo } from '@/types/system'
import { clearToken, getToken, setToken, setSessionSubject } from '@/utils/token'
import { setFieldPermissionCheck } from '@/utils/fieldAccess'

/** 当前登录的人：token、基本信息、权限、菜单 */
export const useUserStore = defineStore('user', () => {
  const token = ref(getToken())
  const info = ref<UserInfo | null>(null)
  const perms = computed(() => new Set(info.value?.perms ?? []))
  /** 超级管理员：能进管理后台 */
  const isAdmin = computed(() => info.value?.roles.includes('admin') ?? false)
  /** 默认主体的 id：新建单据的主体默认填它，没有是 null（让人选） */
  const defaultCompanyId = computed(() => info.value?.defaultCompany?.id ?? null)
  /** 单据列表出不出"我的 / 全部"切换（本人范围的人不出）；没拿到的（假数据）按出 */
  const canSeeAll = computed(() => info.value?.canSeeAll ?? true)
  /** 业务菜单（首页这一侧）和管理后台的菜单，按顶层目录分开 */
  const workbenchMenus = computed(() => (info.value?.menus ?? []).filter((m) => !m.adminOnly))
  const adminMenus = computed(() => (info.value?.menus ?? []).filter((m) => m.adminOnly))

  async function login(username: string, password: string) {
    const newToken = await authApi.login(username, password)
    setToken(newToken)
    token.value = newToken
  }

  async function fetchMe() {
    info.value = await authApi.me()
    setSessionSubject(info.value.id)
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

  /** 只清本地、不调退出接口：改了密码以后这次登录在后端已经作废了，再调退出只会 401 */
  function forget() {
    clearToken()
    token.value = ''
    info.value = null
  }

  /** 有没有某个权限标识：按钮显不显示就看它 */
  function hasPerm(perm: string) {
    return perms.value.has(perm)
  }
  setFieldPermissionCheck(hasPerm)

  return { token, info, isAdmin, defaultCompanyId, canSeeAll, workbenchMenus, adminMenus, login, fetchMe, logout, forget, hasPerm }
})
