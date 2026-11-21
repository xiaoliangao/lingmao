import { http, request, upload } from '@/utils/request'
import type {
  BackupStatus, DataScope, DictData, DictOption, DictType, FileVO, MenuNode, OperLog, PageResult, Role, User, UserOption,
  VersionInfo,
} from '@/types/system'

export interface PageParams {
  page: number
  size: number
}

/** 用户的工作资料（RFC 003 §4.3） */
export interface UserProfileForm {
  defaultCompanyId?: string | null
  dept?: string | null
  post?: string | null
  /** 两位数字 01-99；编过款号以后不能改、清空 */
  designerNo?: string | null
  /** 能看的品类线，至少一条（RFC 013 §2） */
  productLines: string[]
}

export const userApi = {
  page: (params: PageParams & { username?: string; status?: number }) => http.get<PageResult<User>>('/api/users', params),
  create: (data: { username: string; password: string; nickname: string; phone?: string } & UserProfileForm) => http.post<string>('/api/users', data),
  /** 整体覆盖：没传的资料（部门、设计师编号……）会被清空，表单要把原来的值带上 */
  update: (id: string, data: { nickname: string; phone?: string | null; status: number } & UserProfileForm) =>
    http.put<void>(`/api/users/${id}`, data),
  remove: (id: string) => http.delete<void>(`/api/users/${id}`),
  resetPassword: (id: string, password: string) => http.post<void>(`/api/users/${id}/reset-password`, { password }),
  roleIds: (id: string) => http.get<string[]>(`/api/users/${id}/roles`),
  assignRoles: (id: string, ids: string[]) => http.put<void>(`/api/users/${id}/roles`, { ids }),
  /** 下拉框选人（UserSelect）：启用的账号，用户名或昵称包含关键字，最多 20 个。登录就能调，不要用户管理的权限 */
  search: (keyword: string) => http.get<UserOption[]>('/api/users/options', { keyword: keyword || undefined }),
}

export interface RoleForm {
  code: string
  name: string
  sort: number
  status: number
  remark?: string | null
  dataScope: DataScope
  /** 数据范围是 ALL 时后端存 false */
  auditAnyScope: boolean
}

export const roleApi = {
  page: (params: PageParams & { code?: string; name?: string }) => http.get<PageResult<Role>>('/api/roles', params),
  options: () => http.get<Role[]>('/api/roles/options'),
  create: (data: RoleForm) => http.post<string>('/api/roles', data),
  update: (id: string, data: RoleForm) => http.put<void>(`/api/roles/${id}`, data),
  remove: (id: string) => http.delete<void>(`/api/roles/${id}`),
  menuIds: (id: string) => http.get<string[]>(`/api/roles/${id}/menus`),
  assignMenus: (id: string, ids: string[]) => http.put<void>(`/api/roles/${id}/menus`, { ids }),
}

export interface MenuForm {
  parentId: string
  name: string
  type: 1 | 2 | 3
  path?: string | null
  component?: string | null
  perms?: string | null
  icon?: string | null
  sort: number
  visible: boolean
  status: number
  /** 只对顶层目录有效：放在管理后台还是业务菜单（首页这一侧） */
  adminOnly?: boolean
}

export const menuApi = {
  tree: () => http.get<MenuNode[]>('/api/menus/tree'),
  create: (data: MenuForm) => http.post<string>('/api/menus', data),
  update: (id: string, data: MenuForm) => http.put<void>(`/api/menus/${id}`, data),
  remove: (id: string) => http.delete<void>(`/api/menus/${id}`),
}

export interface DictTypeForm {
  code: string
  name: string
  remark?: string | null
  status: number
  numLabel?: string | null
}

export interface DictDataForm {
  typeCode: string
  label: string
  value: string
  sort: number
  status: number
  remark?: string | null
  numValue?: number | null
  /** 类型有 textLabel 时才存；有 textDict 的要是那个字典里的值 */
  textValue?: string | null
}

export const dictApi = {
  typePage: (params: PageParams & { code?: string; name?: string }) => http.get<PageResult<DictType>>('/api/dict-types', params),
  createType: (data: DictTypeForm) => http.post<string>('/api/dict-types', data),
  updateType: (id: string, data: DictTypeForm) => http.put<void>(`/api/dict-types/${id}`, data),
  removeType: (id: string) => http.delete<void>(`/api/dict-types/${id}`),
  dataPage: (params: PageParams & { typeCode?: string; label?: string }) => http.get<PageResult<DictData>>('/api/dict-data', params),
  createData: (data: DictDataForm) => http.post<string>('/api/dict-data', data),
  updateData: (id: string, data: DictDataForm) => http.put<void>(`/api/dict-data/${id}`, data),
  removeData: (id: string) => http.delete<void>(`/api/dict-data/${id}`),
  options: (typeCode: string) => http.get<DictOption[]>(`/api/dict-data/by-type/${typeCode}`),
}

export const logApi = {
  page: (params: PageParams & { username?: string; module?: string; success?: boolean }) =>
    http.get<PageResult<OperLog>>('/api/oper-logs', params),
}

export const fileApi = {
  upload: (file: File) => upload<FileVO>('/api/files', file),
}

export const versionApi = {
  /** 不用登录。取不到不弹消息：页面上不显示版本号就是了 */
  get: () => request<VersionInfo>({ url: '/api/version', silent: true }),
}

export const backupApi = {
  /** 只有超级管理员能调（别人 403）。取不到不弹消息：首页不显示备份情况就是了 */
  status: () => request<BackupStatus>({ url: '/api/admin/backup-status', silent: true }),
}
