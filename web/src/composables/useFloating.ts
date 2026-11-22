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
  let drag: { x: number; y: number; from: Pos; id: number } | null = null
  let moved = false

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
    const dx = event.clientX - drag.x
    const dy = event.clientY - drag.y
    if (!moved && Math.hypot(dx, dy) < THRESHOLD) return
    moved = true
    pos.right = drag.from.right - dx
    pos.bottom = drag.from.bottom - dy
    clamp()
  }

  function stop() {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
  }

  function onUp(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.id) return
    stop()
    drag = null
    if (moved) {
      save(key, pos)
      onDragEnd?.()
    }
  }

  /** 按下：左键、不是按在按钮、输入框、下拉上 */
  function start(event: PointerEvent) {
    if (event.button !== 0) return
    const target = event.target as HTMLElement | null
    if (target?.closest('button:not([data-drag]), input, textarea, a, .el-dropdown')) return
    drag = { x: event.clientX, y: event.clientY, from: { ...pos }, id: event.pointerId }
    moved = false
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
  }

  function dragged() {
    const was = moved
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
    stop()
  })

  return { pos, style, start, dragged, clamp }
}
