import { onBeforeUnmount, onDeactivated } from 'vue'
import { createLatestRequest } from '@/utils/requestScope'

export function useLatestRequest(options: { cancelOnDeactivate?: boolean } = {}) {
  const latest = createLatestRequest()
  onBeforeUnmount(latest.cancel)
  // 缓存的页面可能只在 onMounted 加载。除非调用方自己在 onActivated 重新加载，
  // 切走时不取消当前读取；关闭、卸载一律取消。
  if (options.cancelOnDeactivate) onDeactivated(latest.cancel)
  return latest
}
