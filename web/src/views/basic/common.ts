import { ElMessage, ElMessageBox } from 'element-plus'

/** 基础资料各页共用的小东西：状态分段、启用停用、删除确认 */

/** 状态分段：'' = 全部 */
export const STATUS_OPTIONS = [
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
  { label: '全部', value: '' },
]

export type StatusFilter = '' | 0 | 1

/** 分段的值 → 接口的 status 参数（'' = 全部，不传） */
export function statusParam(status: StatusFilter | undefined): 0 | 1 | undefined {
  return status === '' ? undefined : status
}

/** 删除确认：说清楚后果，按钮就写"删除" */
export function confirmRemove(title: string, message: string) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
}

/**
 * 启用、停用一条主数据：停用前问一声（说后果），启用不问。做完提示一句，返回 true；取消了返回 false
 * @param consequence 停用的后果，如"停用后新建物料选不到这个颜色，已有的不受影响"
 */
export async function toggleStatus(
  row: { status: number },
  name: string,
  api: { enable: (id: number) => Promise<void>; disable: (id: number) => Promise<void> },
  id: number,
  consequence: string,
): Promise<boolean> {
  if (row.status === 1) {
    try {
      await ElMessageBox.confirm(consequence, `停用${name}`, {
        confirmButtonText: '停用',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      })
    } catch {
      return false
    }
    await api.disable(id)
    ElMessage.success(`已停用${name}`)
  } else {
    await api.enable(id)
    ElMessage.success(`已启用${name}`)
  }
  return true
}

/** 空串、只有空格 → null（保存是整体覆盖，没填的发 null） */
export function orNull(text: string | null | undefined): string | null {
  const trimmed = text?.trim()
  return trimmed ? trimmed : null
}
