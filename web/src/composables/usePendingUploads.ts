import { getCurrentInstance, inject, onBeforeUnmount, onMounted, provide, ref, type ComponentInternalInstance, type InjectionKey } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useTabsStore } from '@/stores/tabs'
import { createPendingTasks } from '@/utils/pendingTasks'

function createScope() {
  const pending = ref(0)
  const generation = ref(0)
  const tasks = createPendingTasks((count, epoch) => { pending.value = count; generation.value = epoch })
  return { pending, generation, ...tasks }
}
type UploadScope = ReturnType<typeof createScope>
const KEY: InjectionKey<UploadScope> = Symbol('editor-uploads')
const owners = new WeakMap<ComponentInternalInstance, UploadScope>()

/** 一个编辑器的上传随其保存、放弃和卸载一起收口。 */
export function provideUploadScope(): UploadScope {
  const owner = getCurrentInstance()
  const existing = owner && owners.get(owner)
  if (existing) return existing
  const scope = createScope()
  if (owner) owners.set(owner, scope)
  provide(KEY, scope)
  const tabs = useTabsStore()
  const path = useRoute().path
  const untrack = tabs.trackDirty(path, () => scope.pending.value > 0)
  onBeforeRouteLeave(async () => {
    if (!scope.pending.value || tabs.has(path) || !tabs.isDirty(path)) return true
    try {
      await ElMessageBox.confirm('还有文件正在上传，离开会放弃本次附件。', '离开这一页', { confirmButtonText: '放弃上传并离开', cancelButtonText: '继续上传' })
      scope.discard()
      return true
    } catch { return false }
  })
  const warnUnload = (event: BeforeUnloadEvent) => { if (scope.pending.value) { event.preventDefault(); event.returnValue = '' } }
  onMounted(() => window.addEventListener('beforeunload', warnUnload))
  onBeforeUnmount(() => { untrack(); window.removeEventListener('beforeunload', warnUnload); scope.discard() })
  return scope
}
export function useUploadScope(): UploadScope {
  return inject(KEY, null) ?? provideUploadScope()
}
