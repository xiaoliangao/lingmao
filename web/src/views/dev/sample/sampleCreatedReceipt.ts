import type { SampleCreated } from '@/types/sample'
import { sessionEpoch } from '@/utils/token'

// 仅承接刚成功的新建动作到首次详情读取；不持久化，也不据此重发写操作。
let pending: { value: SampleCreated; epoch: string } | null = null
export function rememberSampleCreated(value: SampleCreated) {
  pending = { value: { ...value }, epoch: sessionEpoch() }
}
export function takeSampleCreated(id: string, companyId: string | null): SampleCreated | null {
  const own = pending
  if (!own) return null
  if (own.epoch !== sessionEpoch()) { pending = null; return null }
  if (own.value.id !== id || own.value.companyId !== companyId) return null
  pending = null
  return own.value
}
