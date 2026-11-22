import { ref } from 'vue'
import { kittingApi } from '@/api/purchase'
import type { OrderKitting } from '@/types/purchase'
import { ApiError } from '@/utils/request'
import { useLatestRequest } from './useLatestRequest'

export type KittingError = 'unavailable' | 'failed' | null

/** 齐料是独立读取：新页取消旧请求，失败不能被解释成服务端的 NONE（未算料）。 */
export function useOrderKitting() {
  const byId = ref<Record<string, OrderKitting>>({})
  const loading = ref(false)
  const error = ref<KittingError>(null)
  const latest = useLatestRequest()

  async function load(orderIds: readonly string[]) {
    const read = latest.begin()
    const ids = [...new Set(orderIds)]
    byId.value = {}
    error.value = null
    loading.value = ids.length > 0
    if (!ids.length) return
    try {
      const rows = await read.run(() => kittingApi.byOrders(ids))
      if (!read.current()) return
      byId.value = Object.fromEntries(rows.map((row) => [row.orderId, row]))
      if (ids.some((id) => !byId.value[id])) error.value = 'failed'
    } catch (cause) {
      if (read.current()) error.value = cause instanceof ApiError && [403, 404].includes(cause.code) ? 'unavailable' : 'failed'
    } finally {
      if (read.current()) loading.value = false
    }
  }

  function cancel() { latest.cancel(); loading.value = false }
  return { byId, loading, error, load, cancel }
}
