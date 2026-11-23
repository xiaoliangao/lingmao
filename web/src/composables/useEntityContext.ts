import { onBeforeUnmount, watch } from 'vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUserStore } from '@/stores/user'
import { onSessionChanged, sessionChanged, sessionEpoch } from '@/utils/token'

export function useEntityContext(invalidate?: () => void) {
  const user = useUserStore()
  let generation = 0, disposed = false
  const cancel = () => { generation++; invalidate?.() }
  // 同一身份的 access token 刷新不换作用域；登录、退出、换账号与主体仍立即失效。
  watch([() => user.info?.id, () => { void user.token; return sessionEpoch() }, () => user.defaultCompanyId,
    () => JSON.stringify([...(user.info?.roles ?? [])].sort()), () => user.hasPerm('data:all')], cancel, { flush: 'sync' })
  onBeforeUnmount(onSessionChanged(cancel))
  onBeforeUnmount(() => { disposed = true; cancel() })
  return { cancel, capture() {
    const own = generation, epoch = sessionEpoch()
    return () => !disposed && generation === own && epoch === sessionEpoch() && !sessionChanged()
  } }
}
export function useEntityRead(invalidate?: () => void) {
  const latest = useLatestRequest({ cancelOnDeactivate: true })
  const context = useEntityContext(() => { latest.cancel(); invalidate?.() })
  return { cancel: latest.cancel, begin() {
    const read = latest.begin(), current = context.capture()
    return { ...read, current: () => read.current() && current() }
  } }
}
