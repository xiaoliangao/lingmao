import { getCurrentInstance, computed, onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTabsStore } from '@/stores/tabs'
import { provideUploadScope } from './usePendingUploads'
import { useUserStore } from '@/stores/user'
import { readDraft, saveDraft, restoreDraftValue } from '@/utils/draft'
import { writeStorage } from '@/utils/safeStorage'
import { sessionChanged, sessionEpoch } from '@/utils/token'
import { trackUnsavedForm } from '@/utils/uiHealth'

/**
 * 表单有没保存的修改时，丢掉之前问一声（"有没保存的修改，……以后就没了"，点"继续编辑"就留着）：
 * - 首页这一侧开着页签的页面：切到别的页签不问（页面还在，切回来接着填）；
 *   关这个页签、去管理后台、退出登录时问（TabsBar、AppLayout、侧边栏会查这里登记的修改）
 * - 管理后台、没开页签的页面：点菜单、浏览器后退离开这一页时问
 * - 关浏览器标签、刷新：浏览器自己的"离开此网站？"
 * - 对话框里的表单：点 ×、按 Esc、点遮罩关对话框时问；点"取消"按钮是明确不要了，不问
 *
 * 对话框里的表单——打开对话框那一刻记下表单的样子，之后改了就算有修改，关掉就不算了：
 *   const guard = useUnsavedChanges(form, dialogVisible)
 *   <el-dialog v-model="dialogVisible" :before-close="guard.beforeClose">
 *   （先给表单填好值，再 dialogVisible = true；保存成功后照常 dialogVisible = false）
 *
 * 整页的表单（详情页、新建页）——加载完、保存完调 markSaved()：
 *   const guard = useUnsavedChanges(form)
 *   onMounted(async () => { Object.assign(form, await orderApi.get(id)); guard.markSaved() })
 *   async function save() { await orderApi.update(id, form); guard.markSaved() }
 *
 * source 可以是 reactive 对象、ref（如勾选的 id 数组）或 getter；按 JSON 比较，表单里放普通的值就行
 */
export interface DraftRestoreContext { current: () => boolean }
interface DraftOptions {
  restore?: (value: unknown, context: DraftRestoreContext) => void | Promise<void>
  ready?: () => boolean
  /** 异步加载的对象使用身份之外的稳定范围；null 表示尚未确定对象，暂不读写草稿。 */
  scope?: () => string | null
}
export function useUnsavedChanges(source: MaybeRefOrGetter<unknown>, active?: Ref<boolean>, options: DraftOptions = {}) {
  const uploads = provideUploadScope()
  const serialize = () => JSON.stringify(toValue(source) ?? null)
  const snapshot = ref(serialize())
  const route = useRoute()
  const instance = getCurrentInstance()
  const component = (instance?.type as { __name?: string }).__name ?? 'form'
  // 同一个组件里有几个守卫时分开记，重新加载后键不变。
  const owner = instance as (typeof instance & { draftCount?: number })
  const index = owner ? (owner.draftCount = (owner.draftCount ?? 0) + 1) : 1
  const user = useUserStore()
  const initialIdentity = user.info?.id ?? 'anonymous'
  const formPath = route.path
  const draftKey = computed(() => {
    const scope = options.scope?.()
    if (options.scope && !scope) return null
    const identity = options.scope ? user.info?.id ?? 'anonymous' : initialIdentity
    return `lingmao-draft:${location.host}:${identity}:${formPath}:${component}:${index}${scope ? `:${encodeURIComponent(scope)}` : ''}`
  })
  let previousDraft = draftKey.value ? readDraft(draftKey.value) : null
  let offered = false
  let writtenHere = false
  let generation = 0
  let disposed = false
  let draftTimer: ReturnType<typeof setTimeout> | undefined
  watch(draftKey, (key) => {
    clearTimeout(draftTimer)
    generation++
    previousDraft = key ? readDraft(key) : null
    offered = false
    writtenHere = false
    snapshot.value = serialize()
  }, { flush: 'sync' })
  function captureContext() {
    const key = draftKey.value, ownGeneration = generation, epoch = sessionEpoch()
    return () => !disposed && ownGeneration === generation && key === draftKey.value && epoch === sessionEpoch() && !sessionChanged() && (!active || active.value)
  }
  async function offerDraft(loaded = false) {
    const key = draftKey.value, draft = previousDraft, currentScope = captureContext()
    if (!key || offered || !draft || !currentScope() || (options.ready && !options.ready())) return
    const current = serialize()
    if (current !== draft.baseline) {
      if (loaded) { offered = true; ElMessage.warning('服务器内容已变化，旧草稿已保留，请核对后再编辑') }
      return
    }
    offered = true
    try {
      await ElMessageBox.confirm('找到这份表单未保存的草稿，是否恢复？', '恢复草稿', { confirmButtonText: '恢复草稿', cancelButtonText: '放弃草稿', distinguishCancelAndClose: true })
      if (!currentScope() || serialize() !== draft.baseline) return
      if (options.restore) await options.restore(draft.data, { current: currentScope })
      else restoreDraftValue(toValue(source), draft.data)
    } catch (reason) { if (reason === 'cancel' && currentScope()) writeStorage(key, null) }
  }
  function persistDraft() {
    const key = draftKey.value
    if (!key || !dirty.value || sessionChanged() || (options.ready && !options.ready())) return
    writtenHere = saveDraft(key, snapshot.value, toValue(source)) || writtenHere
  }
  watch(serialize, () => { clearTimeout(draftTimer); draftTimer = setTimeout(persistDraft, 750) })
  onMounted(() => { void offerDraft() })
  if (options.ready) watch(options.ready, (ready) => { if (ready) void offerDraft(true) })
  onBeforeUnmount(() => { clearTimeout(draftTimer); persistDraft(); disposed = true; generation++ })

  /** 有没保存的修改 */
  const dirty = computed(() => (active ? active.value : true) && (serialize() !== snapshot.value || uploads.pending.value > 0))
  onBeforeUnmount(trackUnsavedForm(() => dirty.value))

  function discardDraft() {
    clearTimeout(draftTimer)
    snapshot.value = serialize()
    if (draftKey.value) writeStorage(draftKey.value, null)
    previousDraft = null
    writtenHere = false
    offered = true
  }

  /** 记下现在的样子：之后再改才算有修改 */
  function markSaved(loaded = true) {
    snapshot.value = serialize()
    clearTimeout(draftTimer)
    if (writtenHere && draftKey.value) { writeStorage(draftKey.value, null); writtenHere = false; previousDraft = null }
    else void offerDraft(loaded)
  }

  const confirmDiscard = (message: string, title: string, confirmText: string) =>
    ElMessageBox.confirm(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: '继续编辑',
      confirmButtonClass: 'el-button--danger',
    })

  // 对话框打开的那一刻记一次（sync：赋值 dialogVisible = true 时马上记，这时表单已经填好了）
  if (active) {
    watch(
      active,
      (on) => {
        generation++
        if (on) markSaved(false)
        else { discardDraft(); uploads.discard() }
      },
      { flush: 'sync' },
    )
  }

  /** el-dialog 的 before-close：有修改先问，确定不要了才关 */
  function beforeClose(done: () => void) {
    if (!dirty.value) {
      done()
      return
    }
    const current = captureContext()
    confirmDiscard('有没保存的修改，关掉以后就没了（包括正在上传的文件）。', '关掉对话框', '不保存，关掉').then(() => {
      if (!current()) return
      discardDraft(); uploads.discard(); done()
    }, () => {})
  }

  // 整页的表单：按这一页的地址登记到页签里，关页签、离开首页这一侧时由那边来问
  // （对话框是盖住整页的，开着的时候点不到页签，不用登记）
  const tabs = useTabsStore()
  const path = useRoute().path
  if (!active) {
    const untrack = tabs.trackDirty(path, () => dirty.value, discardDraft)
    onBeforeUnmount(untrack)
  }

  onBeforeRouteLeave(async () => {
    if (!dirty.value) return true
    if (!active) {
      // 开着页签：切走以后页面还在，不问
      if (tabs.has(path)) return true
      // 页签刚关掉：关的时候已经问过了
      if (!tabs.isDirty(path)) return true
    }
    const current = captureContext()
    try {
      await confirmDiscard('有没保存的修改，离开以后就没了。', '离开这一页', '不保存，离开')
      if (!current()) return false
      discardDraft()
      uploads.discard()
      return true
    } catch {
      return false
    }
  })

  // 关标签、刷新：浏览器只认 preventDefault，提示的字由浏览器自己定。
  // 页签里切走的页面（keep-alive）还挂着这个监听，里面没保存的照样会拦
  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    persistDraft()
    event.preventDefault()
    event.returnValue = ''
  }

  onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

  async function waitForUploads() {
    const ok = await uploads.wait()
    if (!ok) ElMessage.warning('有文件未完成上传，本次未保存。请重试上传后再保存')
    return ok
  }
  function retryDraft() { offered = false; void offerDraft(true) }
  return { dirty, markSaved, beforeClose, discardDraft, retryDraft, pendingUploads: uploads.pending, waitForUploads, discardUploads: uploads.discard }
}
