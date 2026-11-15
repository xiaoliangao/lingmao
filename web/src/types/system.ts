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
  /** true：管理后台的菜单，只有超级管理员能用 */
  adminOnly: boolean
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

/** 角色的数据范围：ALL 全部 / SELF 本人（PRD §3.2）。有"全部"角色的人，权限里多一个 data:all */
export type DataScope = 'ALL' | 'SELF'

export interface Role {
  id: number
  code: string
  name: string
  sort: number
  status: number
  remark: string | null
  dataScope: DataScope
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

/** 字典选项。停用的也在（旧数据里存着它的值，显示时要翻成文字）；下拉里只列启用的，见 DictSelect */
export interface DictOption {
  label: string
  value: string
  /** 1 启用 0 停用 */
  status: number
}

/** 选人下拉的一项（GET /api/users/options） */
export interface UserOption {
  id: number
  username: string
  nickname: string
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
  /** 这次请求的错误编号，拿它在服务器上 grep 应用日志 */
  traceId: string | null
  createTime: string
}

/**
 * 上传成功返回的文件。url、thumbUrl 带签名（?e=…&s=…，一周左右过期）：原样拿去显示、原样传回后端保存，
 * 不要自己拼 /files/… 地址，也不要去掉参数
 */
export interface FileVO {
  url: string
  /** 长边 320px 的缩略图，只有 jpg、png 有，其他为 null。显示列表时用它，没有就用 url */
  thumbUrl: string | null
  /** 原来的文件名 */
  name: string
  /** 字节 */
  size: number
}

/** 后端的版本：发版时打进去的，本地开发时 version 是 "dev" */
export interface VersionInfo {
  version: string
  /** 打包时间 "yyyy-MM-dd HH:mm:ss" */
  buildTime: string | null
}

/** 数据库备份的情况，只有超级管理员能取 */
export interface BackupStatus {
  /** 服务器上配了备份没有。本地开发没配，首页什么都不显示 */
  configured: boolean
  latestFile: string | null
  /** 最近一次备份的时间 "yyyy-MM-dd HH:mm:ss"，一次都没备份过是 null */
  latestTime: string | null
  sizeBytes: number | null
  /** 最近一次备份超过 36 小时了（一次都没有也算） */
  stale: boolean
}
