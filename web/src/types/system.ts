// 和后端返回的 JSON 一一对应。字段名、可能为 null 的地方都照后端写

export interface PageResult<T> {
  list: T[]
  total: number
}

/** /api/auth/me 返回的当前用户 */
export interface UserInfo {
  id: string
  username: string
  nickname: string
  roles: string[]
  perms: string[]
  menus: MenuNode[]
  /**
   * 默认主体（我方公司）：新建需求订单、采购单、盘点单时主体默认填它（用 user.defaultCompanyId）。
   * 用户上设的停用了、或者没设，启用的只有一个就是那个；不止一个时是 null，单据上自己选
   */
  defaultCompany: { id: string; name: string } | null
  /** 功能开关，只决定入口显示不显示（真正的拦截在后端）。ai：AI 功能统一开关（RFC 017 §3.7），改了要刷新页面才变 */
  features: { ai: boolean }
  /** 数据范围是"全部"（PRD §3.2）：只有这样的人，单据列表上才出"我的 / 全部"切换；"本人"范围的切了也只看得到自己的 */
  canSeeAll: boolean
  /**
   * 管理员给的密码还没改（RFC 018 §2）：true 时 perms、menus 是空的，业务接口全是 403，
   * 路由守卫只放 /change-password 这一页，改完重新登录
   */
  mustChangePassword: boolean
}

/**
 * 智能助手的短期只读令牌（RFC 017 §3.1）：交给助手服务，只能调返回数据的 GET 接口。
 * 30 分钟、不能刷新，过期了再换；AI 功能关掉、退出、改密码时提前失效
 */
export interface AssistantToken {
  token: string
  /** 过期时间，东八区 */
  expiresAt: string
}

export interface MenuNode {
  id: string
  parentId: string
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
  id: string
  username: string
  nickname: string
  phone: string | null
  status: number
  createTime: string
  /** 默认主体公司：新建单据时带出来 */
  defaultCompanyId: string | null
  /** 在哪几个部门（RFC 018 §1），path 是"产品部/设计组"这样的全路径 */
  depts: { id: string; name: string; path: string }[]
  post: string | null
  /** 设计师编号，两位数字 01-99，进款号；不是设计师的为空 */
  designerNo: string | null
  /** 编号已经用来编过款号，不能再改、清空 */
  designerNoLocked: boolean
  /** 能看的品类线（字典 product_line 的 value，按字典顺序）；超级管理员不看这个，看全部（RFC 013 §2） */
  productLines: string[]
  /** 角色（名称），列表直接显示 */
  roles: { id: string; name: string }[]
  /** 管理员给的密码还没改：导入、新建、重置密码后是 true，本人改了以后 false */
  pwdMustChange: boolean
}

/** 部门树的一个节点（GET /api/depts） */
export interface Dept {
  id: string
  parentId: string | null
  name: string
  sort: number
  /** "产品部/设计组" */
  path: string
  /** 直属的人数（不含下级部门的） */
  userCount: number
  children: Dept[]
}

/** 哪种编码（RFC 018 §4.2） */
export type CodeTarget = 'STYLE' | 'MATERIAL' | 'SUPPLIER'

/** 编码的一段（RFC 018 §4.4） */
export type CodeSegmentType =
  | 'TEXT' | 'STYLE_CLASS' | 'BRAND' | 'TOP_CATEGORY' | 'CATEGORY' | 'STYLE_YEAR' | 'DATE' | 'DESIGNER' | 'MAT_PARENT_CATEGORY' | 'MAT_CATEGORY' | 'SEQ'

export interface CodeSegment {
  type: CodeSegmentType
  /** 固定文字（TEXT）：1-8 位大写字母、数字、横杠 */
  text?: string | null
  /** 日期格式（DATE）：yy / yyMM / yyMMdd / yyyy */
  format?: string | null
  /** 流水位数（SEQ）：2-6 */
  width?: number | null
}

export interface CodeRule {
  id: string
  target: CodeTarget
  name: string
  /** 通用规则：没单独定规则的都按它编，不能删、不能选"按什么分" */
  general: boolean
  /** 按什么分：款号是大类（字典 style_class）的值，物料是物料类型（material_type）的值；通用规则是空的 */
  scopeValues: string[]
  segments: CodeSegment[]
  sort: number
  updateTime: string
}

/** 角色的数据范围：ALL 全部 / SELF 本人（PRD §3.2）。有"全部"角色的人，权限里多一个 data:all */
export type DataScope = 'ALL' | 'SELF'

export interface Role {
  id: string
  code: string
  name: string
  sort: number
  status: number
  remark: string | null
  dataScope: DataScope
  /** 审核不受数据范围限制（V49）：本人范围的角色勾上后，有审核权限的变更单、出入库单、盘点单待审时也看得到、审得了。全部范围的总是 false */
  auditAnyScope: boolean
  createTime: string
}

export interface DictType {
  id: string
  code: string
  name: string
  remark: string | null
  status: number
  createTime: string
  /** 数值列叫什么（"备料比 %""默认天数"）；空 = 这个字典不用数值 */
  numLabel: string | null
  /** 文字列叫什么（物料类型的"物料清单谁填"）；空 = 字典页不编辑文字。和 textDict 一样由迁移设，页面上改不了 */
  textLabel: string | null
  /** 文字从哪个字典里选（如 bom_owner）；空 = 随便填 */
  textDict: string | null
}

export interface DictData {
  id: string
  typeCode: string
  label: string
  value: string
  sort: number
  status: number
  remark: string | null
  createTime: string
  numValue: number | null
  /** 文字，含义看类型的 textLabel */
  textValue: string | null
  /** 内置项：程序按 value 认它，不能删、不能停用 */
  builtin: boolean
  /** 贸易代工用的值：代工没打开时下拉里没有它 */
  trade: boolean
}

/** 字典选项。停用的也在（旧数据里存着它的值，显示时要翻成文字）；下拉里只列启用的，见 DictSelect */
export interface DictOption {
  label: string
  value: string
  /** 1 启用 0 停用 */
  status: number
  /** 文字，含义看字典类型的 textLabel（物料类型的"物料清单谁填"：DEV / PROD）；大多数字典没有 */
  textValue?: string | null
}

/** 选人下拉的一项（GET /api/users/options） */
export interface UserOption {
  id: string
  username: string
  nickname: string
  /** 在哪几个部门（名字，"、"隔开），同名、花名相近的人好分；没挂部门是 null */
  depts: string | null
}

export interface OperLog {
  id: string
  userId: string | null
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

/** 管理后台 › AI 功能：统一开关（RFC 017 §3.7） */
export interface AiSwitch {
  /** 开着没有。设置被删了是 false；读库出错时接口报错 */
  enabled: boolean
  /** 上次谁改的（昵称，没有昵称是账号；账号删了也照样有）。改没改过看 updatedAt：装好以后没人改过是 null */
  updatedBy: string | null
  updatedAt: string | null
}
