export type ExportJobStatus = 'PENDING' | 'PROCESSING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'DEAD_LETTER' | 'CANCELLED' | 'EXPIRED'

export interface ExportJobView {
  id: string
  kind: 'STOCK_LOGS'
  status: ExportJobStatus
  rows: number
  attempts: number
  requestId: string
  createdAt: string
  expiresAt: string | null
  errorCode: string | null
  downloadUrl: string | null
}

export const EXPORT_JOB_STATUS_LABEL: Record<ExportJobStatus, string> = {
  PENDING: '等待处理',
  PROCESSING: '正在生成',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '文件已就绪',
  DEAD_LETTER: '生成失败',
  CANCELLED: '已取消',
  EXPIRED: '文件已过期',
}

export const isExportRunning = (status: ExportJobStatus) => ['PENDING', 'PROCESSING', 'RETRY_WAIT'].includes(status)
export const canRetryExport = (status: ExportJobStatus) => ['DEAD_LETTER', 'CANCELLED', 'EXPIRED'].includes(status)

const ERROR_MESSAGES = new Map<string, string>([
  ['INVALID_INPUT', '导出条件无效，请核对筛选条件后重新创建任务。'],
  ['PERMISSION_REVOKED', '导出权限已变更，请按当前权限重新创建任务，或联系管理员确认权限。'],
  ['ROW_LIMIT', '导出数据超过 100 万行，请缩小日期或仓库范围后重新创建任务。'],
  ['SIZE_LIMIT', '导出文件超过 250 MB，请缩小筛选范围后重新创建任务。'],
  ['DEADLINE', '文件生成超过 15 分钟，请缩小筛选范围后重新创建任务。'],
  ['DISK_FULL', '服务器存储空间不足，请联系管理员处理后重新生成。'],
  ['FILE_MISSING', '导出文件已不可用，请重新生成。'],
  ['CANCELLED', '导出任务已取消，可按原筛选条件重新生成。'],
  ['LEASE_LOST', '后台处理暂时中断，请稍后刷新状态；若仍失败，可重新生成。'],
  ['LEASE_EXPIRED', '后台处理未及时完成，请稍后刷新状态；若仍失败，可重新生成。'],
  ['SHUTDOWN', '后台服务正在重启，请稍后刷新任务状态。'],
  ['TEMPORARY_FAILURE', '后台服务暂时不可用，请稍后查看状态；若仍失败，可重新生成。'],
])

export function exportJobErrorMessage(code: string, requestId: string): string {
  return ERROR_MESSAGES.get(code) ?? (requestId
    ? `导出未完成，请联系管理员核查（追踪码：${requestId}）。`
    : '导出未完成，请刷新状态获取追踪码后联系管理员。')
}
