import { ElMessage, ElMessageBox } from 'element-plus'
import { showBizError } from '@/composables/useBizError'
import { DOC_ACTION_CONFIRM, DOC_ACTION_LABEL, REASON_REQUIRED, type ApprovalView, type DocAction, type DocActionReq } from '@/types/biz'
import { ErrorCode, LIST_ERROR_CODES } from '@/utils/errorCodes'
import { ApiError, type RequestOptions } from '@/utils/request'
import { businessToday, addBusinessDays } from '@/utils/businessDate'

/**
 * 单据详情页共用的小东西（docs/api/README.md §4.2）：状态动作、版本冲突、日期默认值。
 * 删除确认、空串转 null 用 views/basic/common.ts 的 confirmRemove、orNull，这里不再写一遍。
 *
 *   async function act(action: DocAction) {
 *     const ok = await runDocAction(action, `采购单 ${d.no}`, d.version, (a, body, o) => purchaseApi.act(id, a, body, o), { reload: refresh })
 *     if (ok) await refresh()
 *   }
 *
 *   exec 的第三个参数是 { silent: true }，一定传给接口：出错由这里显示（提交的检查清单 1000 + string[] 弹表格）。
 *   保存草稿、别的带 version 的请求：catch (e) { if (reloadOnConflict(e, refresh)) return; throw e }
 *   自己发 silent 请求的（"存草稿并提交"）：catch (e) { if (e instanceof ApiError) showSilentError(e) }
 */

/** 今天，yyyy-MM-dd：按东八区算，不看浏览器时区（表单默认值用，后端照样会查） */
export const todayStr = businessToday
/** 某天（默认今天）加几天，yyyy-MM-dd：要求交期默认今天 + 60 天这种 */
export const addDaysStr = addBusinessDays

/** 按条件拼按钮清单：[can('void') && { … }, …].filter(isItem)，把 false 去掉、类型收窄 */
export function isItem<T>(item: T | false | null | undefined): item is T {
  return !!item
}

/** 弹框写原因（驳回、作废回货单这种必填的）。取消了返回 null */
export async function promptReason(title: string, placeholder = '写一句原因', confirmText = '确定', danger = true): Promise<string | null> {
  try {
    const res = await ElMessageBox.prompt('', title, {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      confirmButtonClass: danger ? 'el-button--danger' : '',
      inputType: 'textarea',
      inputPlaceholder: placeholder,
      inputValidator: (v) => (v && v.trim() ? true : '请写原因'),
    })
    return res.value.trim()
  } catch {
    return null
  }
}

/**
 * silent 请求出的错由调用方显示：带清单的弹表格（提交检查没过的 1000 + 一句一条的 string[]，02 §3.6；1001、1004、1005），
 * 别的弹一行消息
 */
export function showSilentError(error: ApiError) {
  const listed = error.code === ErrorCode.BIZ || LIST_ERROR_CODES.includes(error.code)
  if (listed && Array.isArray(error.data) && error.data.length > 0) {
    showBizError(error.code, error.message, error.data)
  } else {
    ElMessage.error(error.message || '请求失败')
  }
}

/**
 * 表单保存遇到 1002（状态变了、别人改过，version 对不上）：消息 request.ts 已经弹过了。
 * 页面上有没保存的输入，不能直接重取把它冲掉：问一声，点"放弃并载入最新"才调 reload。
 * 是 1002 返回 true（调用方就此打住），别的错误返回 false（调用方照常往外抛）。
 * 详情页的状态动作没有本地输入，runDocAction 里直接重取，不用它
 */
export function reloadOnConflict(error: unknown, reload?: () => unknown): boolean {
  if (!(error instanceof ApiError) || error.code !== ErrorCode.STATE_CHANGED) return false
  if (reload) {
    void ElMessageBox.confirm('服务器上的内容已变化。本地输入仍保留；载入最新会放弃本地未保存的修改。', '保存冲突', {
      confirmButtonText: '放弃并载入最新', cancelButtonText: '继续编辑本地草稿', type: 'warning',
    }).then(() => reload()).catch(() => {})
  }
  return true
}

/**
 * 点一个状态动作（submit / withdraw / reject / audit / unaudit / close / reopen / void）：
 * - 驳回：弹框写原因，必填（REASON_REQUIRED）
 * - 作废、结案、反审核、取消结案：照 DOC_ACTION_CONFIRM 说后果再确认，不要求写原因；note 是这张单据自己要多说的一句
 * - 别的直接做
 * 做成了提示一句、返回 true（调用方刷新详情）；取消了、出错了返回 false。
 * confirmText 换掉 DOC_ACTION_CONFIRM 的通用说法（出入库单没有"提交"，反审核不说"重新提交审核"）。
 * 多级审批的单据传 approval：审的不是最后一级时提示"这一级审过了，等谁审"，不说"已审核"（单据还是待审核）
 * 出错时显示原因（showSilentError），1002 时再调 reload 重新取详情，拿新的 version
 */
export async function runDocAction(
  action: DocAction,
  docLabel: string,
  version: number,
  exec: (act: DocAction, body: DocActionReq, options: RequestOptions) => Promise<unknown>,
  options: { note?: string; confirmText?: string; reload?: () => unknown; approval?: ApprovalView | null } = {},
): Promise<boolean> {
  const label = DOC_ACTION_LABEL[action]
  // 先记下来：审完 approval 就变了
  const next = action === 'audit' ? nextApprover(options.approval) : null
  let reason: string | null = null

  if (REASON_REQUIRED.includes(action)) {
    reason = await promptReason(`${label} ${docLabel}`, '写一句原因，制单人在操作记录里看得到', label, true)
    if (reason === null) return false
  } else {
    const confirm = DOC_ACTION_CONFIRM[action]
    if (confirm || options.note) {
      const text = [options.note, options.confirmText ?? confirm?.text].filter(Boolean).join('\n')
      try {
        await ElMessageBox.confirm(text, `${label} ${docLabel}`, {
          confirmButtonText: label,
          cancelButtonText: '取消',
          confirmButtonClass: confirm?.danger ? 'el-button--danger' : '',
        })
      } catch {
        return false
      }
    }
  }

  try {
    await exec(action, { version, reason }, { silent: true })
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    if (e.code === ErrorCode.STATE_CHANGED) void options.reload?.()
    return false
  }
  ElMessage.success(next ? `${docLabel} 这一级审过了，等${next}审` : `${docLabel} 已${label}`)
  return true
}

/** 多级审批里当前这一级之后还要谁审；当前就是最后一级、没在审是 null */
export function nextApprover(approval: ApprovalView | null | undefined): string | null {
  const levels = approval?.levels ?? []
  const current = levels.findIndex((l) => l.status === 'CURRENT')
  if (current < 0) return null
  return levels.slice(current + 1).find((l) => l.status === 'WAITING')?.approverLabel ?? null
}
