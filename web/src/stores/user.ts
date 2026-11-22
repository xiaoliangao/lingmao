import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import type { UserInfo } from '@/types/system'
import { clearToken, getToken, setToken, setSessionSubject, sessionEpoch, sessionChanged } from '@/utils/token'
import { ApiError } from '@/utils/request'
import { withAuthSession } from '@/utils/authSession'
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
  /** 业务菜单（首页这一侧）和管理后台的菜单，按顶层目录分开 */
  const workbenchMenus = computed(() => (info.value?.menus ?? []).filter((m) => !m.adminOnly))
  const adminMenus = computed(() => (info.value?.menus ?? []).filter((m) => m.adminOnly))

  async function login(username: string, password: string) {
    await withAuthSession(async (mode) => {
      const newToken = await authApi.login(username, password, mode)
      setToken(newToken)
      token.value = newToken
      info.value = null
    })
  }

  async function fetchMe() {
    const epoch = sessionEpoch()
    const next = await authApi.me()
    if (sessionChanged() || sessionEpoch() !== epoch) throw new ApiError(-1, '登录身份已变化，请重新获取当前账号资料')
    info.value = next
    setSessionSubject(next.id)
  }

  async function logout() {
    const epoch = sessionEpoch()
    await withAuthSession(async (mode) => {
      // 等锁期间别的页签可能已登录；旧退出不能拿新 Cookie 再发一次。
      if (sessionChanged() || sessionEpoch() !== epoch) throw new ApiError(409, '登录身份已变化，本次退出已取消')
      let superseded: boolean
      try {
        await authApi.logout(mode)
      } finally {
        superseded = sessionChanged() || sessionEpoch() !== epoch
        if (!superseded) {
          clearToken()
          token.value = ''
          info.value = null
        }
      }
      if (superseded) throw new ApiError(-1, '登录身份已变化，本次退出不影响新账号')
    })
  }

  /** 有没有某个权限标识：按钮显不显示就看它 */
  function hasPerm(perm: string) {
    return perms.value.has(perm)
  }
  setFieldPermissionCheck(hasPerm)

  return { token, info, isAdmin, defaultCompanyId, workbenchMenus, adminMenus, login, fetchMe, logout, hasPerm }
})
