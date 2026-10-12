// 和后端返回的 JSON 一一对应。字段名、可能为 null 的地方都照后端写

export interface PageResult<T> {
  list: T[]
  total: number
}

/** /api/auth/me 返回的当前用户 */
export interface UserInfo {
  id: number
  username: string
  nickname: string
  roles: string[]
  perms: string[]
  menus: MenuNode[]
}

export interface MenuNode {
  id: number
  parentId: number
  name: string
  type: 1 | 2 | 3
  path: string | null
  component: string | null
  perms: string | null
  icon: string | null
  sort: number
  visible: boolean
  status: number
  children: MenuNode[]
}

export interface User {
  id: number
  username: string
  nickname: string
  phone: string | null
  status: number
  createTime: string
}

export interface Role {
  id: number
  code: string
  name: string
  sort: number
  status: number
  remark: string | null
  createTime: string
}

export interface DictType {
  id: number
  code: string
  name: string
  remark: string | null
  status: number
  createTime: string
}

export interface DictData {
  id: number
  typeCode: string
  label: string
  value: string
  sort: number
  status: number
  remark: string | null
  createTime: string
}

export interface DictOption {
  label: string
  value: string
}

export interface OperLog {
  id: number
  userId: number | null
  username: string | null
  module: string
  action: string
  method: string
  requestMethod: string | null
  requestUri: string | null
  params: string | null
  success: boolean
  errorMsg: string | null
  costMs: number
  ip: string | null
  createTime: string
}

export interface FileInfo {
  url: string
  name: string
  size: number
}
