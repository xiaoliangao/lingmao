/* ============================================================
 * 界面公共件：路由、图标、表格、弹窗、提示……
 * 页面文件用 route() 注册自己的路由，用 expose() 把函数给模板用，
 * 用 SEARCH / TODOS 把自己的东西挂进 ⌘K 搜索和首页待办。
 * ============================================================ */
const G = {} // 模板里能直接用的函数、数据（挂到 app.config.globalProperties）
const expose = (o) => Object.assign(G, o)
const COMPONENTS = {}
const comp = (name, def) => { COMPONENTS[name] = def; return def }

/* ---------- 路由 ---------- */
const ROUTES = []
// meta: { mod: 侧边栏模块 key, t: 标题（字符串或 params => 字符串） }
function route(pat, component, meta) {
  const keys = []
  const re = new RegExp('^' + pat.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)' }) + '$')
  ROUTES.push({ pat, re, keys, comp: component, ...meta })
}
const nav = reactive({ path: '/', query: {}, r: null, params: {}, seq: 0 })
function parseHash() {
  const [path, qs] = (location.hash.slice(1) || '/').split('?')
  nav.path = decodeURIComponent(path)
  nav.query = Object.fromEntries(new URLSearchParams(qs || ''))
  nav.r = null
  nav.params = {}
  for (const r of ROUTES) {
    const m = nav.path.match(r.re)
    if (m) { nav.r = r; nav.params = Object.fromEntries(r.keys.map((k, i) => [k, m[i + 1]])); break }
  }
  nav.seq++
}
window.addEventListener('hashchange', parseHash)
const go = (p) => { location.hash = '#' + p }
const routeTitle = () => { const r = nav.r; if (!r) return '没有这个页面'; return typeof r.t === 'function' ? r.t(nav.params) : r.t }

/* ---------- 提示、确认、出错 ---------- */
const ui = reactive({ toasts: [], err: null, ask: null })
function toast(msg, link, err) {
  const t = { id: Math.random(), msg, link, err }
  ui.toasts.push(t)
  setTimeout(() => { const i = ui.toasts.indexOf(t); if (i >= 0) ui.toasts.splice(i, 1) }, link ? 5000 : 2600)
}
function showErr(e) {
  if (!(e instanceof Err)) { console.error(e); ui.err = { msg: '出错了：' + e.message, list: [] }; return }
  if (e.list.length) ui.err = { msg: e.message, list: e.list }
  else toast(e.message, null, true)
}
// 包一层：动作出错统一弹出；成功返回结果，失败返回 undefined
function run(fn) { try { return fn() ?? true } catch (e) { showErr(e) } }
function ask(title, text, ok = '确定', danger = false) {
  return new Promise((resolve) => { ui.ask = { title, text, ok, danger, resolve } })
}
// 要人写一句话（驳回原因……）：确定返回写的字，取消返回 null；need = 不写不让确定
function askText(title, text, ph = '', ok = '确定', need = true) {
  return new Promise((resolve) => { ui.ask = { title, text, ok, input: { ph, v: '', need }, resolve } })
}
/* 单据详情页的动作按钮都走这里（PRD §5.1）：驳回先问原因；作废、结案、反审核、取消结案先说后果再确认；成功弹提示
 *   <button @click="docAct('purchase', po, a)">  done 可以是提示文字，或 (结果) => 文字 */
const ACT_ASK = {
  作废: ['作废后这张单只读，单号不再使用。', true],
  结案: ['结案后不能再入库、回货、生成下游单据，只能看。有审核权限的人可以取消结案。', false],
  反审核: ['回到草稿，改完要重新提交审核。', false],
  取消结案: ['回到结案前的状态，可以继续执行。', false],
}
async function docAct(kind, d, a, done) {
  let arg
  if (a === '驳回') { arg = await askText('驳回 ' + (d.no || ''), '写清楚哪里要改，制单人会在操作记录里看到。', '驳回原因'); if (arg == null) return }
  else if (ACT_ASK[a] && !(await ask(a + ' ' + (d.no || ''), ACT_ASK[a][0], a, ACT_ASK[a][1]))) return
  const r = run(() => doAction(kind, d, a, arg))
  // 多级审批没走完：这一级审过了，单据还是待审核
  if (r && a === '审核' && d.status === '待审核') toast(`${d.no} 这一级审过了，等${lvName(curLevel(kind, d))}审`)
  else if (r) toast(typeof done === 'function' ? done(r) : done || `${d.no || ''} ${a}了`)
  return r
}
const SEARCH = [] // () => [{ t, sub, to, i }]
const TODOS = [] //  () => [{ t, n, to, i, tone }]

/* Esc 关最上层的弹窗 */
const escStack = []
window.addEventListener('keydown', (e) => { if (e.key === 'Escape' && escStack.length) { e.preventDefault(); escStack[escStack.length - 1]() } })
function useEsc(fn) { onMounted(() => escStack.push(fn)); onUnmounted(() => { const i = escStack.indexOf(fn); if (i >= 0) escStack.splice(i, 1) }) }

/* ---------- 图标（线条，24 栅格） ---------- */
const ICONS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  layers: 'm12 2 10 5-10 5L2 7z M2 17l10 5 10-5 M2 12l10 5 10-5',
  route: 'M3 3h6v6H3z M15 15h6v6h-6z M9 6h5a3 3 0 0 1 3 3v6',
  scissors: 'M9 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M20 4 8.1 15.9 M14.5 14.5 20 20 M8.1 8.1 12 12',
  shirt: 'M20.4 3.5 16 2a4 4 0 0 1-8 0L3.6 3.5a2 2 0 0 0-1.3 2.2l.6 3.5a1 1 0 0 0 1 .8H6v10a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V10h2.1a1 1 0 0 0 1-.8l.6-3.5a2 2 0 0 0-1.3-2.2z',
  calc: 'M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M8 6h8 M8 11h.01 M12 11h.01 M16 11h.01 M8 15h.01 M12 15h.01 M16 15h.01 M8 19h.01 M12 19h.01 M16 19h.01',
  file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h5',
  diff: 'M12 3v14 M5 10h14 M5 21h14',
  sigma: 'M18 7V4H6l6 8-6 8h12v-3',
  cart: 'M3 3h2l2.4 12.2a1 1 0 0 0 1 .8h9.7a1 1 0 0 0 1-.8L21 7H6 M10 20a1 1 0 1 1-2 0 1 1 0 0 1 2 0z M19 20a1 1 0 1 1-2 0 1 1 0 0 1 2 0z',
  truck: 'M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2 M15 18H9 M19 18h2a1 1 0 0 0 1-1v-3.6a1 1 0 0 0-.2-.6l-3.5-4.4a1 1 0 0 0-.8-.4H14 M9 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0z M19 18a2 2 0 1 1-4 0 2 2 0 0 1 4 0z',
  swap: 'M8 3 4 7l4 4 M4 7h16 M16 21l4-4-4-4 M20 17H4',
  package: 'm7.5 4.3 9 5.1 M21 8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4a2 2 0 0 0 1-1.7z M3.3 7 12 12l8.7-5 M12 22V12',
  factory: 'M2 20a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8l-7 5V8l-7 5V4a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z M17 18h1 M12 18h1 M7 18h1',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M14 14h7v7h-7z M3 14h7v7H3z',
  phone: 'M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M12 18h.01',
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M9 12l2 2 4-4',
  warehouse: 'M22 8.4V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8.4a2 2 0 0 1 1.3-1.9l8-3.2a2 2 0 0 1 1.4 0l8 3.2A2 2 0 0 1 22 8.4z M6 18h12 M6 14h12 M6 22V10h12v12',
  receipt: 'M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1z M16 8H8 M16 12H8 M13 16H8',
  store: 'M3 9l1.5-5h15L21 9 M3 9h18v1a3 3 0 0 1-6 0 3 3 0 0 1-6 0 3 3 0 0 1-6 0z M5 13v8h14v-8 M10 21v-5h4v5',
  user: 'M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2 M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M22 21v-2a4 4 0 0 0-3-3.9 M16 3.1a4 4 0 0 1 0 7.8',
  box: 'M21 8 12 3 3 8v8l9 5 9-5z M3 8l9 5 9-5 M12 13v8',
  palette: 'M12 22a10 10 0 1 1 10-10c0 2.5-2 3-3.5 3H16a2 2 0 0 0-1.4 3.4A2 2 0 0 1 13 22z M7.5 10.5h.01 M10.5 7.5h.01 M14.5 7.5h.01 M17 11h.01',
  chart: 'M3 3v18h18 M8 17v-6 M13 17V7 M18 17v-4',
  sliders: 'M4 21v-7 M4 10V3 M12 21v-9 M12 8V3 M20 21v-5 M20 12V3 M2 14h4 M10 8h4 M18 16h4',
  msg: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  search: 'M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16z M21 21l-4.3-4.3',
  plus: 'M12 5v14 M5 12h14',
  more: 'M5 12h.01 M12 12h.01 M19 12h.01',
  right: 'm9 18 6-6-6-6',
  down: 'm6 9 6 6 6-6',
  left: 'm15 18-6-6 6-6',
  x: 'M18 6 6 18 M6 6l12 12',
  check: 'M20 6 9 17l-5-5',
  alert: 'M12 9v4 M12 17h.01 M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  pin: 'M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M7 10l5 5 5-5 M12 15V3',
  upload: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12',
  list: 'M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01',
  printer: 'M6 9V2h12v7 M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2 M6 14h12v8H6z',
  trash: 'M3 6h18 M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6 M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2',
  camera: 'M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z M15 13a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  flag: 'M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z M4 22v-7',
  bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9 M10.3 21a1.94 1.94 0 0 0 3.4 0',
  link: 'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7 M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7',
  pen: 'M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z',
  info: 'M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20z M12 16v-4 M12 8h.01',
}
comp('Ico', {
  props: { n: String, s: { type: Number, default: 16 } },
  setup: () => ({ ICONS }),
  template: `<svg :width="s" :height="s" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="ICONS[n]"/></svg>`,
})

/* ---------- 状态标签：实线 = 定了，虚线 = 等人处理；靛蓝实底 = 进行中，绿 = 完成，红 = 拉黑 / 不合格 ---------- */
const ST_KIND = {
  dashed: ['草稿', '待准入', '停用', '无', '未开始', '待开始', '待推送', '未领料', '未到料', '未算料', '待对账', '未开票'],
  pending: ['待审核', '待评审', '待处理', '待检', '待确认', '已驳回', '需改版', '待派单', '待收款', '待付款', '准入审批中', '待签回'],
  ring: ['已审核', '已下单', '合作中', '启用', '已生效', '已确认', '开发中', '已发送', '已开票'],
  half: ['部分到货', '生产中', '部分回货', '打样中', '进行中', '处理中', '部分领料', '部分付款', '部分收款', '部分发货'],
  done: ['已完成', '已到齐', '已通过', '已转大货', '合格', '已付款', '已收款', '已推送', '已入库', '已发货', '已领齐', '齐料', '已对账', '已签回'],
  closed: ['已结案', '已关闭', '已作废版本'],
  x: ['已作废', '已取消', '黑名单', '已淘汰', '不合格', '让步接收'],
}
const stKind = (s) => Object.keys(ST_KIND).find((k) => ST_KIND[k].includes(s)) || 'ring'
// t="" 时只出一个小圆点（图卡角上用）
comp('St', {
  props: { s: String, t: String },
  setup: (p) => ({ k: computed(() => (['黑名单', '不合格'].includes(p.s) ? 'bad' : stKind(p.s))) }),
  template: `<span class="st" :class="['k-' + k, { dot: t === '' }]" :title="t === '' ? s : null">{{ t === '' ? '' : t ?? s }}</span>`,
})

/* ---------- 表格：cols = [{ k, t, c: 'num'|'code', w, f: row => 文本, img: row => 款号/SKU（前面带小图） }]；单元格可用 #k 插槽改写
 * 列表页（link、不是 inset）超过 20 条分页，每页 20 / 50 / 100（PRD §5.4） ---------- */
comp('Tbl', {
  props: { rows: Array, cols: Array, link: Boolean, empty: String, inset: Boolean, flat: Boolean, sel: Function },
  emits: ['row'],
  setup(p) {
    const size = ref(20), cur = ref(1)
    const paged = computed(() => p.link && !p.inset && p.rows.length > 20)
    const pages = computed(() => Math.max(1, Math.ceil(p.rows.length / size.value)))
    watch([() => p.rows.length, size], () => { if (cur.value > pages.value) cur.value = 1 })
    const shown = computed(() => (paged.value ? p.rows.slice((cur.value - 1) * size.value, cur.value * size.value) : p.rows))
    return { size, cur, paged, pages, shown }
  },
  template: `<table class="tbl" :class="{ inset, flat }">
    <caption v-if="link && !inset && rows.length">共 <b>{{ rows.length }}</b> 条<span v-if="paged" class="pager">
      <select class="sel" v-model.number="size"><option v-for="n in [20, 50, 100]" :key="n" :value="n">{{ n }} 条/页</option></select>
      <button class="btn sm" :disabled="cur <= 1" @click="cur--">上一页</button><span class="num">{{ cur }} / {{ pages }}</span><button class="btn sm" :disabled="cur >= pages" @click="cur++">下一页</button></span></caption>
    <thead><tr><th v-for="c in cols" :key="c.k" :class="c.c" :style="c.w ? { width: c.w } : null">{{ c.t }}</th></tr></thead>
    <tbody>
      <tr v-for="(r, i) in shown" :key="i" :class="{ link, sel: sel && sel(r) }" @click="link && $emit('row', r)">
        <td v-for="c in cols" :key="c.k" :class="c.c"><slot :name="c.k" :r="r" :i="i"><span v-if="c.img" class="row"><Thumb :code="c.img(r)" :s="32"/>{{ c.f ? c.f(r) : (r[c.k] ?? '—') }}</span><template v-else>{{ c.f ? c.f(r) : (r[c.k] ?? '—') }}</template></slot></td>
      </tr>
      <tr v-if="!rows.length"><td :colspan="cols.length" class="empty">{{ empty || '没有记录' }}</td></tr>
    </tbody>
    <tfoot v-if="$slots.foot"><slot name="foot"/></tfoot>
  </table>`,
})

// 二期、待定模块的页头标出分期（侧边栏 NAV 上的 ph），甲方一眼看出哪些这次不上
const PHASE_TIP = { 二期: '12 月每周发版时再做（PRD §2.2）', 待定: '甲方表里没标黄，等主线跑起来、甲方确认要不要做再排（PRD §2.5）', 待排期: '甲方 11-11 新提的，排期还没定' }
comp('Head', {
  props: { t: String, back: Array },
  setup: () => ({ ph: computed(() => { const it = navOf(nav.r?.mod); return it?.ph && it.ph !== '一期' ? it : null }), PHASE_TIP }),
  template: `<header class="head" data-c="页头"><div class="crumb">
      <template v-if="back"><a :href="'#' + back[1]">{{ back[0] }}</a><Ico n="right" :s="14"/></template>
      <b>{{ t }}</b><Phase v-if="ph" :p="ph.ph" :t="ph.ref + '：' + PHASE_TIP[ph.ph] + '。这里先给甲方看个样子'"/><slot name="title"/>
    </div><div class="acts"><slot/></div></header>`,
})

const normItems = (items) => items.map((x) => (Array.isArray(x) ? { v: x[0], t: x[1] ?? x[0], n: x[2] } : typeof x === 'object' ? x : { v: x, t: x }))
comp('Seg', {
  props: { modelValue: null, items: Array },
  emits: ['update:modelValue'],
  setup: (p) => ({ list: computed(() => normItems(p.items)) }),
  template: `<div class="seg"><button v-for="it in list" :key="it.v" :class="{ on: it.v === modelValue }" @click="$emit('update:modelValue', it.v)">{{ it.t }}<i v-if="it.n != null">{{ it.n }}</i></button></div>`,
})
// 页签项可以是对象 { v, t, n, ph }：ph 是分期（二期 / 待排期……），标题旁边出分期小标
comp('Tabs', {
  props: { modelValue: null, items: Array },
  emits: ['update:modelValue'],
  setup: (p) => ({ list: computed(() => normItems(p.items)), PHASE_TIP }),
  template: `<div class="tabs"><button v-for="it in list" :key="it.v" :class="{ on: it.v === modelValue }" @click="$emit('update:modelValue', it.v)">{{ it.t }}<Phase v-if="it.ph" :p="it.ph" :t="it.ph + '：' + (PHASE_TIP[it.ph] || '')"/><i v-if="it.n">{{ it.n }}</i></button></div>`,
})
comp('Search', {
  props: { modelValue: String, ph: { type: String, default: '搜索' } },
  emits: ['update:modelValue'],
  template: `<label class="search" :title="ph"><Ico n="search" :s="14"/><input class="inp" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" :placeholder="ph"></label>`,
})

comp('Modal', {
  props: { t: String, w: { type: Number, default: 560 } },
  emits: ['close'],
  setup(p, { emit }) { useEsc(() => emit('close')) },
  template: `<Teleport to="body"><div class="mask" @mousedown.self="$emit('close')"><div class="modal" :style="{ width: w + 'px' }" role="dialog" :data-c="'弹窗：' + t">
    <header>{{ t }}<button class="btn ghost icon" @click="$emit('close')"><Ico n="x"/></button></header>
    <section><slot/></section>
    <footer v-if="$slots.foot"><slot name="foot"/></footer>
  </div></div></Teleport>`,
})

comp('More', {
  props: { items: Array },
  setup() {
    const open = ref(false)
    const close = () => { open.value = false }
    onMounted(() => document.addEventListener('click', close))
    onUnmounted(() => document.removeEventListener('click', close))
    return { open }
  },
  template: `<span style="position:relative" v-if="items.length"><button class="btn icon" @click.stop="open = !open"><Ico n="more"/></button>
    <div v-if="open" class="pop" style="right:0;top:32px"><button v-for="it in items" :key="it.t" :class="{ danger: it.danger }" @click="open = false; it.fn()">{{ it.t }}</button></div></span>`,
})

/* ---------- 色块、日期、进度（款图在 3b-pics.js） ---------- */
comp('Sw', {
  props: { c: String, n: { type: Boolean, default: true } },
  template: `<span class="sw"><i :style="{ background: colorOf(c).hex }"></i><template v-if="n">{{ colorName(c) }}</template></span>`,
})
comp('Days', {
  props: { d: String, warn: { type: Number, default: 3 }, done: Boolean },
  setup: (p) => ({ left: computed(() => (p.d ? daysLeft(p.d) : null)) }),
  template: `<span v-if="done || left == null" class="dim">—</span>
    <span v-else-if="left < 0" class="flag red">逾期 {{ -left }} 天</span>
    <span v-else-if="left === 0" class="flag amber">今天</span>
    <span v-else-if="left <= warn" class="flag amber">剩 {{ left }} 天</span>
    <span v-else class="dim">{{ left }} 天</span>`,
})
comp('Prog', {
  props: { a: Number, b: Number },
  setup: (p) => ({ pct: computed(() => fpct(p.a, p.b)) }),
  template: `<span class="prog" :class="{ done: pct >= 100 }"><b><i :style="{ width: pct + '%' }"></i></b>{{ pct }}%</span>`,
})
/* ---------- 图表（首页、报表）：面积图 series = [{ t, c: 颜色, v: [数] }]，labels = x 轴；环形图 items = [{ t, v, c }] ---------- */
const smooth = (pts) => pts.reduce((d, [x, y], i) => {
  if (!i) return `M${x},${y}`
  const [x0, y0] = pts[i - 1], cx = (x0 + x) / 2
  return d + `C${cx},${y0} ${cx},${y} ${x},${y}`
}, '')
comp('AreaChart', {
  props: { series: Array, labels: Array, h: { type: Number, default: 200 } },
  setup(p) {
    const W = 600, L = 40, R = 8, T = 10, B = 22
    const g = computed(() => {
      const max = Math.max(1, ...p.series.flatMap((s) => s.v))
      const step = 10 ** Math.floor(Math.log10(max)), top = Math.ceil(max / step) * step
      const n = p.labels.length, H = p.h
      const x = (i) => L + ((W - L - R) * i) / Math.max(1, n - 1), y = (v) => T + (H - T - B) * (1 - v / top)
      const ticks = [0, 1, 2, 3, 4].map((k) => ({ v: (top * k) / 4, y: y((top * k) / 4) }))
      const lines = p.series.map((s) => { const pts = s.v.map((v, i) => [x(i), y(v)]); const d = smooth(pts); return { ...s, d, area: d + `L${x(n - 1)},${y(0)}L${x(0)},${y(0)}Z` } })
      const every = Math.ceil(n / 8)
      return { H, ticks, lines, xs: p.labels.map((t, i) => ({ t, x: x(i), show: i % every === 0 || i === n - 1 })) }
    })
    const fmtK = (v) => (v >= 10000 ? rnd(v / 10000, 1) + '万' : fq(v))
    return { g, W, L, fmtK }
  },
  template: `<div class="chart"><svg :viewBox="'0 0 ' + W + ' ' + g.H" width="100%" :height="g.H" preserveAspectRatio="none">
      <g v-for="t in g.ticks" :key="t.v"><line :x1="L" :x2="W - 8" :y1="t.y" :y2="t.y" stroke="var(--line)" stroke-dasharray="3 3"/><text :x="L - 6" :y="t.y + 3.5" text-anchor="end">{{ fmtK(t.v) }}</text></g>
      <g v-for="s in g.lines" :key="s.t"><path :d="s.area" :fill="s.c" fill-opacity=".14"/><path :d="s.d" fill="none" :stroke="s.c" stroke-width="2"/></g>
      <text v-for="x in g.xs.filter(z => z.show)" :key="x.t" :x="x.x" :y="g.H - 6" text-anchor="middle">{{ x.t }}</text>
    </svg><div class="legend"><span v-for="s in series" :key="s.t"><i :style="{ background: s.c }"></i>{{ s.t }}</span></div></div>`,
})
comp('Donut', {
  props: { items: Array, s: { type: Number, default: 150 }, unit: { type: String, default: '' } },
  setup(p) {
    const segs = computed(() => {
      const tot = sum(p.items, (x) => x.v) || 1, C = 2 * Math.PI * 40
      let acc = 0
      return { tot, list: p.items.map((x) => { const len = (x.v / tot) * C; const s = { ...x, dash: `${Math.max(0, len - 1.2)} ${C}`, off: -acc, pct: Math.round((x.v / tot) * 100) }; acc += len; return s }) }
    })
    return { segs }
  },
  template: `<div class="row" style="gap:20px;align-items:center"><svg :width="s" :height="s" viewBox="0 0 100 100" style="transform:rotate(-90deg)">
      <circle cx="50" cy="50" r="40" fill="none" stroke="var(--bg-sunken)" stroke-width="14"/>
      <circle v-for="x in segs.list" :key="x.t" cx="50" cy="50" r="40" fill="none" :stroke="x.c" stroke-width="14" :stroke-dasharray="x.dash" :stroke-dashoffset="x.off"/></svg>
    <div class="col" style="gap:7px;flex:1;min-width:0;font-size:12.5px"><div v-for="x in segs.list" :key="x.t" class="row"><i :style="{ background: x.c, width: '10px', height: '10px', borderRadius: '2px', display: 'inline-block' }"></i><span class="ell" style="flex:1">{{ x.t }}</span><span class="num mut">{{ fq(x.v) }}{{ unit }}</span><span class="num dim" style="width:34px">{{ x.pct }}%</span></div></div></div>`,
})
comp('Nodes', {
  props: { p: Object },
  template: `<span class="nodes" :title="p.nodes.map(n => n.name).join(' → ')"><i v-for="n in p.nodes" :key="n.name" :class="nodeState(n) === 'todo' ? '' : nodeState(n)"></i><span v-if="!p.nodes.length" class="dim">—</span></span>`,
})

/* ---------- 颜色 × 尺码矩阵：modelValue = { 'KQ|S': 80 }；ro 只读；sub(c, z) 每格下面的小字 ---------- */
comp('Matrix', {
  props: { st: String, modelValue: Object, ro: Boolean, colors: Array, sub: Function },
  emits: ['update:modelValue'],
  setup(p, { emit }) {
    const s = computed(() => style(p.st))
    const cs = computed(() => p.colors || s.value.colors)
    const zs = computed(() => sizesOf(s.value.sizeGroup))
    const v = (c, z) => p.modelValue[c + '|' + z] || 0
    // 同一拍里连着改几格（粘贴）时 props 还没更新，所以从上次发出的值接着改
    let cur = p.modelValue
    watch(() => p.modelValue, (x) => { cur = x })
    const set = (c, z, x) => { cur = { ...cur, [c + '|' + z]: Math.max(0, parseInt(x) || 0) }; emit('update:modelValue', cur) }
    // 从 Excel 粘一块数字进来：从这一格往右、往下填
    function paste(c, z, e) {
      const rows = e.clipboardData.getData('text').trim().split(/\r?\n/).map((r) => r.split('\t'))
      if (rows.length === 1 && rows[0].length === 1) return
      e.preventDefault()
      const ci = cs.value.indexOf(c), zi = zs.value.indexOf(z)
      rows.forEach((r, i) => r.forEach((x, j) => { const cc = cs.value[ci + i], zz = zs.value[zi + j]; if (cc && zz) set(cc, zz, x) }))
    }
    const rowT = (c) => sum(zs.value, (z) => v(c, z))
    const colT = (z) => sum(cs.value, (c) => v(c, z))
    const all = computed(() => sum(cs.value, rowT))
    // 停用或删掉的 SKU 不能再填数；已经填了的照样显示，可以清掉
    const off = (c, z) => !s.value.skus.some((k) => k.color === c && k.size === z && k.status !== '停用')
    return { cs, zs, v, set, paste, rowT, colT, all, off }
  },
  template: `<table class="mx"><thead><tr><th></th><th v-for="z in zs" :key="z">{{ z }}</th><th class="tot">合计</th></tr></thead>
    <tbody><tr v-for="c in cs" :key="c"><td><Sw :c="c"/></td>
      <td v-for="z in zs" :key="z"><input v-if="!ro" class="inp cell num" :value="v(c, z) || ''" :disabled="off(c, z) && !v(c, z)" :title="off(c, z) ? '这个 SKU 停用或删掉了，不能下单' : null" @input="set(c, z, $event.target.value)" @paste="paste(c, z, $event)"><template v-else>{{ v(c, z) || '·' }}</template><small v-if="sub">{{ sub(c, z) }}</small></td>
      <td class="tot">{{ rowT(c) }}</td></tr></tbody>
    <tfoot><tr><td class="dim">合计</td><td v-for="z in zs" :key="z" class="tot">{{ colT(z) }}</td><td class="tot">{{ all }}</td></tr></tfoot></table>`,
})

/* 单据右侧"操作记录" */
comp('Log', {
  props: { items: Array },
  template: `<ul class="log"><li v-for="(x, i) in [...(items || [])].reverse()" :key="i"><span>{{ fd(x.at) }}</span><div>{{ x.who }} {{ x.text }}</div></li><li v-if="!items || !items.length"><div class="dim">还没有记录</div></li></ul>`,
})
/* 审批进度（多级审批，PRD §5.1）：待审核的都显示，好知道在等谁；审完了只有多级的留着看 */
comp('ApproveSteps', {
  props: { kind: String, d: Object },
  setup: (p) => ({ flow: computed(() => (p.d.status === '待审核' || p.d.flow?.length > 1 ? flowOf(p.kind, p.d) : [])), cur: computed(() => (p.d.status === '待审核' ? (p.d.approved || []).length : -1)) }),
  template: `<template v-if="flow.length"><h4>审批</h4>
    <ol class="apv" data-c="审批进度"><li v-for="(lv, i) in flow" :key="i" :class="{ done: d.approved?.[i], cur: i === cur }"><i></i>
      <div><b>第 {{ i + 1 }} 级 · {{ lvName(lv) }}</b><small v-if="lv.over" class="dim">金额超过 {{ fm(lv.over) }}</small>
        <small v-if="d.approved?.[i]">{{ userName(d.approved[i].who) }} {{ fd(d.approved[i].at) }} 审过</small><small v-else-if="i === cur" class="amber">等审批</small></div></li></ol></template>`,
})
/* 待审核、但我不是这一级的审批人：按钮那里写在等谁 */
comp('ApproveWait', {
  props: { kind: String, d: Object },
  template: `<span v-if="curLevel(kind, d)" class="wait" data-c="等审批">等{{ lvName(curLevel(kind, d)) }}审<template v-if="lvText(kind, d)">，{{ lvText(kind, d) }}</template></span>`,
})
expose({ APPROVE, mayAct, canApprove, curLevel, flowOf, lvName, lvText })
/* 上游订单变更提示 */
comp('Upstream', {
  props: { d: Object },
  template: `<div v-if="d.upstream && !d.upstream.done" class="banner" data-c="上游变更提示"><Ico n="alert"/><span>上游订单 <a class="code" :href="'#/orders/' + d.upstream.order">{{ d.upstream.order }}</a> 于 {{ fd(d.upstream.at) }} 变更：{{ d.upstream.text }}</span><button class="btn sm" @click="d.upstream.done = true">已处理</button></div>`,
})

expose({
  db, nav, go, ui, fd, fq, fm, fpct, sum, rnd, daysLeft, addDays, clock, TRADE, DUE_T,
  colorOf, colorName, sizesOf, baseSizeOf, catOf, sup, supName, cust, custName, ownerName, wh, whName, mat, matOfSku, skuOf, skuName, skuPrice, unitOf, me,
  style, styleName, userName, order, purchase, production, stockDoc, mrp, stockQty,
  can, actionsOf, tpsOf, activeTp, tpOf, tpState, rowSku, rowUse, rowCost, tpMaterialCost, tpLabor, unmatchedColors,
  orderQty, orderAmt, orderStyles, orderSched, orderRet, scheduledQty, returnedQty, kitState, prodsOf, mrpsOf, describeChange,
  poQty, poAmt, poRecv, prodQty, prodRet, prodDef, prodAmt, prodRisk, expectDue, curNode, nodeState, issueState, stockDir,
  run, toast, ask, doAction, addStyleColor, orderLive, prodLive, isFactory, canUseSupplier, arrivals, issuePlan, toD,
})

/* ---------- 列表筛选（PRD §5.4）：关键字一次查多个（空格隔开，任一命中）、日期范围、往来方、跟单员 ----------
 *   const s = reactive({ q: '', flt: newFlt() })
 *   <Filters v-model="s.flt" party="customer|supplier|factory" merch/>
 *   rows.filter(x => matchQ(s.q, x.no, x.style) && passFlt(s.flt, { date: x.date, party: x.customer, merch: x.merch })) */
const matchQ = (q, ...texts) => { const ks = (q || '').trim().toLowerCase().split(/\s+/).filter(Boolean); if (!ks.length) return true; const t = texts.join(' ').toLowerCase(); return ks.some((k) => t.includes(k)) }
const newFlt = () => reactive({ from: '', to: '', party: '', merch: '' })
const passFlt = (f, x) => (!f.from || (x.date || '') >= f.from) && (!f.to || (x.date || '') <= f.to) && (!f.party || x.party === f.party) && (!f.merch || x.merch === f.merch)
// 数据范围（PRD §3.2）：本人 = 自己是制单人、业务员、跟单员、采购员或设计师的单据；角色是"全部"的也能切到"我的"
const MINE_KEYS = ['sales', 'merch', 'buyer', 'designer']
const isMine = (d) => !!d && (creatorOf(d) === me()?.name || MINE_KEYS.some((k) => d[k] === db.me))
const ownOnly = () => roleOf(me()?.role).scope === '本人'
const inScope = (mine, d, ...also) => !(ownOnly() || mine) || [d, ...also].some(isMine)
comp('MineToggle', {
  props: { modelValue: Boolean },
  emits: ['update:modelValue'],
  setup: () => ({ ownOnly }),
  template: `<span v-if="ownOnly()" class="pill" data-c="数据范围" title="你的角色数据范围是「本人」：只列出你是制单人、业务员、跟单员或采购员的单据（系统设置 → 角色权限里改）">只看我的</span>
    <Seg v-else data-c="数据范围" :modelValue="modelValue ? '我的' : '全部'" @update:modelValue="(v) => $emit('update:modelValue', v === '我的')" :items="['全部', '我的']"/>`,
})
comp('Filters', {
  props: { modelValue: Object, party: String, merch: Boolean, label: { type: String, default: '日期' } },
  setup(p) {
    const parties = computed(() => (p.party === 'customer' ? db.customers.map((c) => [c.code, c.short]) : db.suppliers.filter((s) => p.party !== 'factory' || isFactory(s)).map((s) => [s.code, s.short])))
    const any = computed(() => Object.values(p.modelValue).some(Boolean))
    const clear = () => Object.assign(p.modelValue, { from: '', to: '', party: '', merch: '' })
    return { parties, any, clear }
  },
  template: `<span class="flt" data-c="筛选：日期、往来方、跟单">
    <span class="dim">{{ label }}</span><input class="inp" type="date" v-model="modelValue.from" :title="label + '从'"><span class="dim">–</span><input class="inp" type="date" v-model="modelValue.to" :title="label + '到'">
    <select v-if="party" class="sel" v-model="modelValue.party"><option value="">{{ party === 'customer' ? '全部客户' : party === 'factory' ? '全部加工厂' : '全部供应商' }}</option><option v-for="x in parties" :key="x[0]" :value="x[0]">{{ x[1] }}</option></select>
    <select v-if="merch" class="sel" v-model="modelValue.merch"><option value="">全部跟单员</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select>
    <button v-if="any" class="btn ghost sm" @click="clear">清空</button></span>`,
})

/* ---------- 单据单头的公共字段（PRD §5.1）：主体公司、制单、审核、备注；放在 <dl class="props"> 里 ---------- */
const companyName = (c) => db.companies.find((x) => x.code === c)?.short || '—'
comp('NoteEdit', {
  props: { d: Object },
  setup(p) {
    const s = reactive({ on: false, v: '' })
    const open = () => { s.v = p.d.note || ''; s.on = true }
    const save = () => { const v = s.v.trim(); if (v !== (p.d.note || '')) { p.d.note = v; act(p.d, v ? '改备注：' + v : '清空备注') } s.on = false }
    return { s, open, save }
  },
  template: `<span v-if="!s.on" class="row g4" style="align-items:flex-start"><span class="wrap">{{ d.note || '—' }}</span><a class="dim" style="cursor:pointer;font-size:12px;flex:none" @click="open">{{ d.note ? '改' : '写备注' }}</a></span>
    <span v-else class="row g4"><input class="inp" style="height:26px" v-model="s.v" @keydown.enter="save" @keydown.esc.stop="s.on = false"><button class="btn sm" @click="save">好</button></span>`,
})
comp('DocMeta', {
  props: { d: Object },
  setup(p) {
    const made = computed(() => p.d.log?.[0])
    const aud = computed(() => !['草稿', '待审核'].includes(p.d.status) && [...(p.d.log || [])].reverse().find((x) => /^(审核|生效|确认)/.test(x.text)))
    return { made, aud }
  },
  template: `<dt>主体公司</dt><dd>{{ companyName(d.company) }}</dd>
    <dt>制单</dt><dd>{{ made ? made.who + ' · ' + fd(made.at) : '—' }}</dd>
    <dt>审核</dt><dd>{{ aud ? aud.who + ' · ' + fd(aud.at) : '—' }}</dd>
    <dt>备注</dt><dd><NoteEdit :d="d"/></dd>`,
})
// 主体公司下拉：只列启用的（已选的停用主体也留着，免得旧单据显示空）
comp('CompanyPick', {
  props: { modelValue: String },
  emits: ['update:modelValue'],
  template: `<select class="sel" :value="modelValue" @change="$emit('update:modelValue', $event.target.value)"><option v-for="c in db.companies.filter(x => x.status === '启用' || x.code === modelValue)" :key="c.code" :value="c.code">{{ c.short }}</option></select>`,
})
// 分期小标：页面上放了二期 / 待定的内容时标出来，免得甲方以为一期都有（PRD §2.2、§2.5）
comp('Phase', {
  props: { p: { type: String, default: '二期' }, t: String },
  template: `<span class="phase" :title="t || (p + '再做，这里先给甲方看个样子')">{{ p }}</span>`,
})

/* ---------- 打印预览（采购单 §6.6.2、加工单 §6.7.1、资料包 §6.4.4）：抬头用主体公司的名称、地址、电话；点"打印"只打这张纸
 *   <PrintSheet v-if="printing" t="采购单" :no="po.no" :company="po.company" @close="printing = false"> 单头 dl.sheet-meta、明细 table.sheet-tbl、签字 .sheet-sign </PrintSheet> */
comp('PrintSheet', {
  props: { t: String, no: String, company: String },
  emits: ['close'],
  setup(p, { emit }) {
    useEsc(() => emit('close'))
    const co = computed(() => db.companies.find((c) => c.code === p.company) || db.companies[0])
    onMounted(() => document.body.classList.add('printing'))
    onUnmounted(() => document.body.classList.remove('printing'))
    return { co, print: () => window.print() }
  },
  template: `<Teleport to="body"><div class="print-root" :data-c="'打印预览：' + t">
    <div class="print-bar"><b>打印预览</b><span class="dim">{{ t }} {{ no }}</span><span class="sp"></span><button class="btn" @click="$emit('close')">关闭</button><button class="btn pri" @click="print"><Ico n="printer" :s="14"/>打印</button></div>
    <article class="psheet">
      <header class="sheet-head"><div><b>{{ co.name }}</b><small>{{ co.addr || '—' }}　电话 {{ co.phone || '—' }}</small></div><div class="sheet-t">{{ t }}<small class="code">{{ no }}</small></div></header>
      <slot/>
    </article>
  </div></Teleport>`,
})
expose({ matchQ, newFlt, passFlt, companyName, docAct, askText, myCompany, removeDraft, creatorOf, isMine, inScope, ownOnly })
