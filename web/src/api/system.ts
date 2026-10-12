import { http, request } from '@/utils/request'
import type {
  DictData, DictOption, DictType, FileInfo, MenuNode, OperLog, PageResult, Role, User,
} from '@/types/system'

export interface PageParams {
  page: number
  size: number
}

export const userApi = {
  page: (params: PageParams & { username?: string; status?: number }) => http.get<PageResult<User>>('/api/users', params),
  create: (data: { username: string; password: string; nickname: string; phone?: string }) => http.post<number>('/api/users', data),
  update: (id: number, data: { nickname: string; phone?: string | null; status: number }) => http.put<void>(`/api/users/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/users/${id}`),
  resetPassword: (id: number, password: string) => http.post<void>(`/api/users/${id}/reset-password`, { password }),
  roleIds: (id: number) => http.get<number[]>(`/api/users/${id}/roles`),
  assignRoles: (id: number, ids: number[]) => http.put<void>(`/api/users/${id}/roles`, { ids }),
}

export interface RoleForm {
  code: string
  name: string
  sort: number
  status: number
  remark?: string | null
}

export const roleApi = {
  page: (params: PageParams & { code?: string; name?: string }) => http.get<PageResult<Role>>('/api/roles', params),
  options: () => http.get<Role[]>('/api/roles/options'),
  create: (data: RoleForm) => http.post<number>('/api/roles', data),
  update: (id: number, data: RoleForm) => http.put<void>(`/api/roles/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/roles/${id}`),
  menuIds: (id: number) => http.get<number[]>(`/api/roles/${id}/menus`),
  assignMenus: (id: number, ids: number[]) => http.put<void>(`/api/roles/${id}/menus`, { ids }),
}

export interface MenuForm {
  parentId: number
  name: string
  type: 1 | 2 | 3
  path?: string | null
  component?: string | null
  perms?: string | null
  icon?: string | null
  sort: number
  visible: boolean
  status: number
  /** 只对顶层目录有效：放在管理后台还是工作台 */
  adminOnly?: boolean
}

export const menuApi = {
  tree: () => http.get<MenuNode[]>('/api/menus/tree'),
  create: (data: MenuForm) => http.post<number>('/api/menus', data),
  update: (id: number, data: MenuForm) => http.put<void>(`/api/menus/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/menus/${id}`),
}

export interface DictTypeForm {
  code: string
  name: string
  remark?: string | null
  status: number
}

export interface DictDataForm {
  typeCode: string
  label: string
  value: string
  sort: number
  status: number
  remark?: string | null
}

export const dictApi = {
  typePage: (params: PageParams & { code?: string; name?: string }) => http.get<PageResult<DictType>>('/api/dict-types', params),
  createType: (data: DictTypeForm) => http.post<number>('/api/dict-types', data),
  updateType: (id: number, data: DictTypeForm) => http.put<void>(`/api/dict-types/${id}`, data),
  removeType: (id: number) => http.delete<void>(`/api/dict-types/${id}`),
  dataPage: (params: PageParams & { typeCode?: string; label?: string }) => http.get<PageResult<DictData>>('/api/dict-data', params),
  createData: (data: DictDataForm) => http.post<number>('/api/dict-data', data),
  updateData: (id: number, data: DictDataForm) => http.put<void>(`/api/dict-data/${id}`, data),
  removeData: (id: number) => http.delete<void>(`/api/dict-data/${id}`),
  options: (typeCode: string) => http.get<DictOption[]>(`/api/dict-data/by-type/${typeCode}`),
}

export const logApi = {
  page: (params: PageParams & { username?: string; module?: string; success?: boolean }) =>
    http.get<PageResult<OperLog>>('/api/oper-logs', params),
}

export const fileApi = {
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<FileInfo>({ url: '/api/files', method: 'post', data: form })
  },
}
