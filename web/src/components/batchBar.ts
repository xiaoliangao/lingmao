import type { RequestOptions } from '@/utils/request'

/** 批量操作条上的一个按钮：对勾选的每一条调一次单条接口（BatchBar） */
export interface BatchAction<T> {
  /** 按钮上的字，也是提示里的动词：'停用' → "停用 3 条""停用了 3 条" */
  label: string
  /**
   * 对一条调单条接口，如 (row, options) => materialApi.disable(row.id, options)。
   * options 是 { silent: true }，一定要传给接口：不然每条失败都弹一次提示，批量条最后还要再汇总一次
   */
  run: (row: T, options: RequestOptions) => Promise<unknown>
  /** 这一条现在能不能做（和详情页按钮同一套判断，如"停用的才能启用"）；不能的跳过、不调接口。不传 = 都试 */
  can?: (row: T) => boolean
  /** 有其中一个权限才显示这个按钮 */
  perm?: string | string[]
  /** 确认框里说后果，如"停用后新建单据里选不到，已有单据不受影响"；不传就只问做不做 */
  confirm?: string
  /** 删除这类做了回不去的：确认按钮是红的 */
  danger?: boolean
}

/** 勾选是 el-table 管的，批量条要它取消成功那几条的勾。只用到这两个方法，直接传 el-table 的 ref 就行 */
export interface BatchTable<T> {
  toggleRowSelection: (row: T, selected?: boolean) => void
  clearSelection: () => void
}

/** 一次批量做完的结果，done 事件带出去：页面一般拿它刷新列表 */
export interface BatchResult<T> {
  label: string
  succeeded: T[]
  failed: { row: T; message: string }[]
  /** can() 判断现在不能做、没调接口的 */
  skipped: T[]
}
