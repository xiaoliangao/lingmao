import { computed, nextTick, onBeforeUnmount, onMounted, reactive, type Ref } from 'vue'

/**
 * 浮在页面上、按住能拖的东西（智能助手的图标和对话卡片）。
 * 位置按"离窗口右边、下边多远"记：窗口变大变小都贴着右下角；拖出窗口、窗口缩小后露不全的，拉回来（四边至少留 8px）。
 * 拖到哪记在本机（localStorage，存不了就只在这次打开里有效）。
 *
 *   const el = ref<HTMLElement>()
 *   const float = useFloating(el, 'lingmao-xxx-pos', { right: 24, bottom: 24 })
 *   <div ref="el" :style="float.style.value">
 *     <header @pointerdown="float.start">…</header>      按住这里拖；按在按钮、输入框上的不算
 *   </div>
 *   float.dragged()   刚才那一下拖动过没有（拖完松手会触发 click，拿它区分"点"和"拖"）；读一次就清掉
 *   float.clamp()     尺寸变了以后拉回窗口里
 *   float.pos         现在的位置（改了它再 clamp，比如"还原"回到放大前的地方）；onDragEnd 拖完一次调一下
 */
const MARGIN = 8
/** 按下后挪动超过几像素才算拖 */
const THRESHOLD = 4

interface Pos {
  right: number
  bottom: number
}

function read(key: string): Pos | null {
  try {
    const raw = JSON.parse(localStorage.getItem(key) ?? 'null') as Partial<Pos> | null
    if (raw && Number.isFinite(raw.right) && Number.isFinite(raw.bottom)) return { right: raw.right!, bottom: raw.bottom! }
  } catch {
    // 读不了、内容坏了：用默认位置
  }
  return null
}

function save(key: string, pos: Pos) {
  try {
    localStorage.setItem(key, JSON.stringify({ right: Math.round(pos.right), bottom: Math.round(pos.bottom) }))
  } catch {
    // 存不了就只在这次打开里有效
  }
}

export function useFloating(el: Ref<HTMLElement | undefined>, key: string, initial: Pos, onDragEnd?: () => void) {
  const pos = reactive<Pos>(read(key) ?? { ...initial })
  let drag: { x: number; y: number; from: Pos; id: number; handle: HTMLElement | null } | null = null
  let moved = false
  /** 拖完松手后把 moved 清掉：紧跟着的 click 读得到，之后用键盘按图标不会被当成"刚拖过" */
  let resetTimer: ReturnType<typeof setTimeout> | undefined

  function clamp() {
    const box = el.value?.getBoundingClientRect()
    if (!box) return
    const maxRight = Math.max(MARGIN, window.innerWidth - box.width - MARGIN)
    const maxBottom = Math.max(MARGIN, window.innerHeight - box.height - MARGIN)
    pos.right = Math.min(Math.max(pos.right, MARGIN), maxRight)
    pos.bottom = Math.min(Math.max(pos.bottom, MARGIN), maxBottom)
  }

  function onMove(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.id) return
    // 松手的事件没收到（切走窗口、右键菜单、在窗口外松手）：鼠标键已经不按着了，就算拖完，不再跟着指针跑
    if (event.pointerType === 'mouse' && (event.buttons & 1) === 0) return finish(false)
    const dx = event.clientX - drag.x
    const dy = event.clientY - drag.y
    if (!moved && Math.hypot(dx, dy) < THRESHOLD) return
    moved = true
    pos.right = drag.from.right - dx
    pos.bottom = drag.from.bottom - dy
    clamp()
  }

  function onUp(event: PointerEvent) {
    if (drag && event.pointerId === drag.id) finish(true)
  }

  function onCancel(event: Event) {
    if (!drag) return
    if (event instanceof PointerEvent && event.pointerId !== drag.id) return
    finish(false)
  }

  /**
   * 结束这次拖动。released：正常松手（后面跟着 click，moved 留到这一轮事件处理完）；
   * 否则是取消（切走窗口、触屏被浏览器接管、丢了指针捕获），不会有 click，moved 直接清掉
   */
  function finish(released: boolean) {
    if (!drag) return
    const { id, handle } = drag
    drag = null
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onCancel)
    window.removeEventListener('blur', onCancel)
    handle?.removeEventListener('lostpointercapture', onCancel)
    try {
      if (handle?.hasPointerCapture(id)) handle.releasePointerCapture(id)
    } catch {
      // 元素已经不在页面上了
    }
    if (moved) {
      save(key, pos)
      onDragEnd?.()
    }
    clearTimeout(resetTimer)
    if (!released) moved = false
    else if (moved) resetTimer = setTimeout(() => (moved = false), 0)
  }

  /** 按下：主指针的左键、不是按在按钮、输入框、下拉上 */
  function start(event: PointerEvent) {
    if (!event.isPrimary || event.button !== 0) return
    const target = event.target as HTMLElement | null
    if (target?.closest('button:not([data-drag]), input, textarea, a, .el-dropdown')) return
    // 上一次没收尾（同一个指针又按下了）：先收掉；别的手指按下的不管
    if (drag) {
      if (event.pointerId !== drag.id) return
      finish(false)
    }
    const handle = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
    drag = { x: event.clientX, y: event.clientY, from: { ...pos }, id: event.pointerId, handle }
    clearTimeout(resetTimer)
    moved = false
    // 捕获指针：拖出窗口、拖得太快指针离开元素，松手的事件也送到这里
    try {
      handle?.setPointerCapture(event.pointerId)
      handle?.addEventListener('lostpointercapture', onCancel)
    } catch {
      // 捕获不了也能拖，靠下面窗口上的监听
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onCancel)
    window.addEventListener('blur', onCancel)
  }

  function dragged() {
    const was = moved
    clearTimeout(resetTimer)
    moved = false
    return was
  }

  const style = computed(() => ({ right: `${pos.right}px`, bottom: `${pos.bottom}px` }))

  onMounted(() => {
    window.addEventListener('resize', clamp)
    void nextTick(clamp)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', clamp)
    finish(false)
    clearTimeout(resetTimer)
  })

  return { pos, style, start, dragged, clamp }
}
