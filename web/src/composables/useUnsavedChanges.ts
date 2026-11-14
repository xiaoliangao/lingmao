import { computed, onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type Ref } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useTabsStore } from '@/stores/tabs'

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
export function useUnsavedChanges(source: MaybeRefOrGetter<unknown>, active?: Ref<boolean>) {
  const serialize = () => JSON.stringify(toValue(source) ?? null)
  const snapshot = ref(serialize())

  /** 有没保存的修改 */
  const dirty = computed(() => (active ? active.value : true) && serialize() !== snapshot.value)

  /** 记下现在的样子：之后再改才算有修改 */
  function markSaved() {
    snapshot.value = serialize()
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
        if (on) markSaved()
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
    confirmDiscard('有没保存的修改，关掉以后就没了。', '关掉对话框', '不保存，关掉').then(done, () => {})
  }

  // 整页的表单：按这一页的地址登记到页签里，关页签、离开首页这一侧时由那边来问
  // （对话框是盖住整页的，开着的时候点不到页签，不用登记）
  const tabs = useTabsStore()
  const path = useRoute().path
  if (!active) {
    const untrack = tabs.trackDirty(path, () => dirty.value)
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
    try {
      await confirmDiscard('有没保存的修改，离开以后就没了。', '离开这一页', '不保存，离开')
      return true
    } catch {
      return false
    }
  })

  // 关标签、刷新：浏览器只认 preventDefault，提示的字由浏览器自己定。
  // 页签里切走的页面（keep-alive）还挂着这个监听，里面没保存的照样会拦
  function onBeforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }

  onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

  return { dirty, markSaved, beforeClose }
}
