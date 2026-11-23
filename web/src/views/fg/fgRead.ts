import { onBeforeUnmount, watch } from 'vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { onSessionChanged, sessionChanged, sessionEpoch, sessionIdentity } from '@/utils/token'

/** 同页换账号与其他页签失效都清理派生数据。 */
export function onFgIdentityChange(cancel: () => void) {
  const user = useUserStore()
  const scope = () => [sessionEpoch(), sessionIdentity(), user.info?.id, user.defaultCompanyId,
    JSON.stringify([...(user.info?.roles ?? [])].sort()), JSON.stringify([...(user.info?.perms ?? [])].sort())] as const
  let previous = scope()
  // 同身份续期和重新读取个人信息不改变表单归属，不能因此丢掉未保存输入。
  watch(() => { void user.token; return scope() }, () => {
    const next = scope()
    const changed = next.some((value, index) => value !== previous[index])
    previous = next
    if (changed) cancel()
  }, { flush: 'sync' })
  onBeforeUnmount(onSessionChanged(cancel))
}

/** 读取属于打开它时的身份；取消后即使适配器迟到也不能写回。 */
export function useFgRead(invalidated?: () => void) {
  const latest = useLatestRequest({ cancelOnDeactivate: true })
  const cancel = () => { latest.cancel(); invalidated?.() }
  onFgIdentityChange(cancel)
  return {
    cancel: latest.cancel,
    begin() {
      const read = latest.begin()
      const epoch = sessionEpoch()
      return { ...read, current: () => read.current() && !sessionChanged() && sessionEpoch() === epoch }
    },
  }
}

export function fgReadError(cause: unknown): string {
  if (cause instanceof ApiError && [403, 404].includes(cause.code)) return '无权限或数据已不可见'
  if (cause instanceof ApiError) return `${cause.message}${cause.traceId ? `（编号 ${cause.traceId}）` : ''}`
  return '暂时无法读取，请重试'
}

/** 每个查询最多 100 个 ID；只返回完整结果，调用方不能把失败批次解释成零。 */
export async function readFgBatches<T>(ids: readonly string[], fetch: (batch: string[]) => Promise<T[]>, signal: AbortSignal): Promise<T[]> {
  const unique = [...new Set(ids)]
  const rows: T[] = []
  for (let offset = 0; offset < unique.length; offset += 100) {
    signal.throwIfAborted()
    rows.push(...await fetch(unique.slice(offset, offset + 100)))
    signal.throwIfAborted()
  }
  return rows
}

/** 通用分页器接收字符串错误；先区分不可见目标，避免原始 ID 出现在列表错误中。 */
export async function readFgPage<T>(request: Promise<T>): Promise<T> {
  try { return await request } catch (cause) {
    if (cause instanceof ApiError) throw new ApiError(cause.code, fgReadError(cause), cause.data, cause.traceId, cause.transient, cause.retryAfter)
    throw cause
  }
}
