import { ApiError } from '@/utils/request'
export { useEntityContext as useSampleContext, useEntityRead as useSampleRead } from '@/composables/useEntityContext'

export function sampleError(cause: unknown) {
  if (cause instanceof ApiError && [403, 404].includes(cause.code)) return '权限已变化或记录不可见，请核对主体后重新读取'
  if (cause instanceof ApiError) return `${cause.message}${cause.traceId ? `（编号 ${cause.traceId}）` : ''}`
  return '暂时无法完成，请重试'
}
