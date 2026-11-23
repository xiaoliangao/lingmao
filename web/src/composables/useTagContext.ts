import { onBeforeUnmount, onDeactivated, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { onSessionChanged, sessionChanged, sessionEpoch } from '@/utils/token'

/** 标签读取、弹窗确认和写入都属于打开时的身份/主体，不接受迟到回写。 */
export function useTagContext(onInvalidate: () => void) {
  const user = useUserStore()
  let generation = 0
  let disposed = false
  function invalidate() { generation++; onInvalidate() }
  // 权限刷新会替换 info 对象；只有身份/令牌/主体的值确实改变才丢弃当前输入。
  watch([() => user.info?.id, () => user.token, () => user.defaultCompanyId], invalidate, { flush: 'sync' })
  const stop = onSessionChanged(invalidate)
  onDeactivated(invalidate)
  onBeforeUnmount(() => { disposed = true; invalidate(); stop() })
  return {
    invalidate,
    capture() {
      const ownGeneration = generation, epoch = sessionEpoch()
      return () => !disposed && generation === ownGeneration && sessionEpoch() === epoch && !sessionChanged()
    },
  }
}
