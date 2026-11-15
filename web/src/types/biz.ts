// biz 域（单据公共能力）的返回，和后端 module/biz/dto 一一对应。字段名、可能为 null 的地方都照后端写

/**
 * 操作记录的一行（DocLogVO）。action、fromStatus、toStatus 是英文名，页面按单据类型翻成中文（DocLogList）；
 * 状态没变的动作（改资料、换图）两个状态都是 null
 */
export interface DocLog {
  id: number
  action: string
  fromStatus: string | null
  toStatus: string | null
  remark: string | null
  operatorId: number | null
  /** 操作人的昵称，人删了也照样有；系统做的（没有操作人）是 null */
  operatorName: string | null
  /** 2026-11-15 10:30:00，东八区 */
  createTime: string
}
