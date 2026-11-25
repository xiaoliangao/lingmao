import { http, request, upload, type RequestOptions } from '@/utils/request'
import type {
  AiSwitch, BackupStatus, CodeRule, CodeSegment, CodeTarget, DataScope, Dept, DictData, DictOption, DictType, FileVO, MenuNode, OperLog,
  PageResult, Role, User, UserOption, VersionInfo,
} from '@/types/system'

export interface PageParams {
  page: number
  size: number
}

/** 用户的工作资料（RFC 003 §4.3） */
export interface UserProfileForm {
  defaultCompanyId?: string | null
  /** 在哪几个部门（RFC 018 §1），最多 20 个；空 = 不挂部门 */
  deptIds: string[]
  post?: string | null
  /** 两位数字 01-99；编过款号以后不能改、清空 */
  designerNo?: string | null
  /** 能看的品类线，至少一条（RFC 013 §2） */
  productLines: string[]
}

export interface UserQuery extends PageParams {
  /** 查用户名、昵称、手机号 */
  keyword?: string
  status?: number
  /** 这个部门和它全部下级部门的人 */
  deptId?: string
  roleId?: string
}

export type UserUpdateForm = { nickname: string; phone?: string | null; status: number } & UserProfileForm

export const userApi = {
  page: (params: UserQuery) => http.get<PageResult<User>>('/api/users', params),
  create: (data: { username: string; password: string; nickname: string; phone?: string } & UserProfileForm) => http.post<string>('/api/users', data),
  /** 整体覆盖：没传的资料（部门、设计师编号……）会被清空，表单要把原来的值带上（批量改时用 userUpdateForm(row) 拼） */
  update: (id: string, data: UserUpdateForm, options?: RequestOptions) => http.put<void>(`/api/users/${id}`, data, options),
  remove: (id: string) => http.delete<void>(`/api/users/${id}`),
  resetPassword: (id: string, password: string) => http.post<void>(`/api/users/${id}/reset-password`, { password }),
  roleIds: (id: string, options?: RequestOptions) => http.get<string[]>(`/api/users/${id}/roles`, undefined, options),
  assignRoles: (id: string, ids: string[], options?: RequestOptions) => http.put<void>(`/api/users/${id}/roles`, { ids }, options),
  /**
   * 按领猫的用户列表导账号（RFC 018 §3），给 ImportDialog 用。初始密码放在表单字段 password 里传
   * （ImportDialog 的 fields），不进地址栏；所有导进来的人共用，第一次登录必须改
   */
  importUrl: '/api/users/import',
  importTemplateUrl: '/api/users/import-template',
  /** 下拉框选人（UserSelect）：启用的账号，用户名或昵称包含关键字，最多 20 个。登录就能调，不要用户管理的权限 */
  search: (keyword: string) => http.get<UserOption[]>('/api/users/options', { keyword: keyword || undefined }),
}

/** 整体覆盖的修改接口要带上全部资料：批量设品类线、启用、停用时，从列表的一行拼出来再改一项 */
export function userUpdateForm(user: User): UserUpdateForm {
  return {
    nickname: user.nickname,
    phone: user.phone,
    status: user.status,
    defaultCompanyId: user.defaultCompanyId,
    deptIds: user.depts.map((d) => d.id),
    post: user.post,
    designerNo: user.designerNo,
    productLines: [...user.productLines],
  }
}

export interface DeptForm {
  parentId: string | null
  name: string
  sort: number
}

/** 部门（RFC 018 §1）：看要用户查看权限，改要用户编辑权限 */
export const deptApi = {
  tree: () => http.get<Dept[]>('/api/depts'),
  create: (data: DeptForm) => http.post<string>('/api/depts', data),
  /** 能换上级；不能挪到自己或自己的下级下面，最多 5 级 */
  update: (id: string, data: DeptForm) => http.put<void>(`/api/depts/${id}`, data),
  /** 有下级、有人 → 1005 */
  remove: (id: string) => http.delete<void>(`/api/depts/${id}`),
}

export interface CodeRuleForm {
  target: CodeTarget
  name: string
  /** 通用规则传空数组 */
  scopeValues: string[]
  segments: CodeSegment[]
}

/** 编码规则（RFC 018 §4）：只有超级管理员能调 */
export const codeRuleApi = {
  list: (target: CodeTarget) => http.get<CodeRule[]>('/api/code-rules', { target }),
  create: (data: CodeRuleForm) => http.post<string>('/api/code-rules', data),
  update: (id: string, data: CodeRuleForm) => http.put<void>(`/api/code-rules/${id}`, data),
  /** 通用规则不能删 */
  remove: (id: string) => http.delete<void>(`/api/code-rules/${id}`),
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

export const aiSwitchApi = {
  /** 只有超级管理员能调（别人 403） */
  get: () => http.get<AiSwitch>('/api/admin/ai-switch'),
  /** 打开或关闭，立刻生效；操作日志里记改前、改后 */
  set: (enabled: boolean) => http.put<AiSwitch>('/api/admin/ai-switch', { enabled }),
}
