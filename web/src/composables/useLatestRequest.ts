import { onBeforeUnmount, onDeactivated } from 'vue'
import { createLatestRequest } from '@/utils/requestScope'

export function useLatestRequest(options: { cancelOnDeactivate?: boolean } = {}) {
  const latest = createLatestRequest()
  onBeforeUnmount(latest.cancel)
  // Cached pages may only load onMounted. Keep their current read alive unless the
  // caller explicitly owns an onActivated reload; closing/unmount always cancels.
  if (options.cancelOnDeactivate) onDeactivated(latest.cancel)
  return latest
}
