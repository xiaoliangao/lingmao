/* ============================================================
 * 外框：侧边栏（按业务流程分组）、⌘K 搜索、弹窗宿主，最后挂载。
 * ph = 分期（PRD §2）：一期 / 二期 / 待定（甲方表里没标黄的）；ref = 需求确认表编号
 * ============================================================ */
const NAV = [
  { items: [{ k: 'home', t: '首页', i: 'home', to: '/', ph: '一期', ref: '13.04 待办' }] },
  { g: '开发', items: [
    { k: 'plans', t: '企划', i: 'layers', to: '/plans', ph: '待定', ref: 'M02 · 02.01–02.02' },
    { k: 'samples', t: '样衣', i: 'scissors', to: '/samples', ph: '二期', ref: 'M03 · 03.01–03.04' },
    { k: 'styles', t: '款式', i: 'shirt', to: '/styles', ph: '一期', ref: 'M02 · 02.03–02.06，M04 资料包' },
    { k: 'costing', t: '核价', i: 'calc', to: '/costing', ph: '待定', ref: 'M05 · 05.01–05.06' },
  ] },
  { g: '订单', items: [
    { k: 'orders', t: '订单', i: 'file', to: '/orders', ph: '一期', ref: 'M06 · 06.01–06.04' },
    { k: 'changes', t: '变更', i: 'diff', to: '/changes', ph: '一期', ref: 'M06 · 06.06' },
  ] },
  { g: '物料', items: [
    { k: 'mrp', t: '算料', i: 'sigma', to: '/mrp', ph: '一期', ref: 'M07 · 07.01' },
    { k: 'purchases', t: '采购', i: 'cart', to: '/purchases', ph: '一期', ref: 'M07 · 07.02–07.03' },
    { k: 'arrivals', t: '到料', i: 'truck', to: '/arrivals', ph: '一期', ref: 'M07 · 07.04' },
    { k: 'stockdocs', t: '出入库', i: 'swap', to: '/stock-docs', ph: '一期', ref: 'M07 · 07.05、07.06、07.08' },
    { k: 'stock', t: '库存', i: 'package', to: '/stock', ph: '一期', ref: 'M07 · 07.07' },
  ] },
  { g: '生产', items: [
    { k: 'production', t: '加工单', i: 'factory', to: '/production', ph: '一期', ref: 'M08 · 08.01–08.06' },
    { k: 'board', t: '看板', i: 'grid', to: '/board', ph: '一期', ref: 'M08 · 08.08' },
    { k: 'mobile', t: '手机报工', i: 'phone', to: '/mobile', ph: '一期', ref: 'M08 · 08.05，M13 · 13.02' },
    { k: 'qc', t: '质检', i: 'shield', to: '/qc', ph: '待定', ref: 'M09 · 09.01–09.06' },
  ] },
  { g: '成品与结算', items: [
    { k: 'fg', t: '成品仓', i: 'warehouse', to: '/fg', ph: '待定', ref: 'M10 · 10.01–10.07' },
    { k: 'finance', t: '对账', i: 'receipt', to: '/finance', ph: '待定', ref: 'M11 · 11.01–11.08' },
  ] },
  { g: '资料', items: [
    { k: 'suppliers', t: '供应商', i: 'store', to: '/suppliers', ph: '一期', ref: 'M12 · 12.01–12.06' },
    { k: 'customers', t: '客户', i: 'user', to: '/customers', ph: '一期', ref: '6.1.8，12.03 商务条款' },
    { k: 'materials', t: '物料', i: 'box', to: '/materials', ph: '一期', ref: 'M01 · 01.03' },
    { k: 'basics', t: '基础', i: 'palette', to: '/basics', ph: '一期', ref: 'M01 · 01.01、01.04–01.07' },
  ] },
  { g: '系统', items: [
    { k: 'reports', t: '报表', i: 'chart', to: '/reports', ph: '二期', ref: 'M13 · 13.06–13.07' },
    { k: 'settings', t: '设置', i: 'sliders', to: '/settings', ph: '一期', ref: 'M13 · 13.01、13.03、13.04、13.08、13.10' },
  ] },
]

comp('Palette', {
  emits: ['close'],
  setup(_, { emit }) {
    useEsc(() => emit('close'))
    const q = ref(''), i = ref(0), inp = ref(null)
    const all = computed(() => [...NAV.flatMap((g) => g.items.map((it) => ({ t: it.t, sub: g.g || '', to: it.to, i: it.i }))), ...SEARCH.flatMap((f) => f())])
    const items = computed(() => {
      const ks = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
      return all.value.filter((x) => ks.every((k) => (x.t + ' ' + (x.sub || '')).toLowerCase().includes(k))).slice(0, 60)
    })
    watch(q, () => { i.value = 0 })
    onMounted(() => inp.value.focus())
    function pick(x) { if (x) { go(x.to); emit('close') } }
    function key(e) {
      if (e.key === 'ArrowDown') { i.value = Math.min(i.value + 1, items.value.length - 1); e.preventDefault() }
      if (e.key === 'ArrowUp') { i.value = Math.max(i.value - 1, 0); e.preventDefault() }
      if (e.key === 'Enter') pick(items.value[i.value])
    }
    return { q, i, inp, items, pick, key }
  },
  template: `<div class="mask" @mousedown.self="$emit('close')"><div class="pal">
    <input ref="inp" v-model="q" @keydown="key" placeholder="跳到页面、单号、款号、供应商……">
    <ul><li v-for="(x, n) in items" :key="x.to + n" :class="{ on: n === i }" @mouseenter="i = n" @click="pick(x)"><Ico :n="x.i || 'file'" :s="15"/>{{ x.t }}<span>{{ x.sub }}</span></li>
      <li v-if="!items.length" class="dim">没有找到</li></ul>
  </div></div>`,
})

comp('Toasts', { template: `<div class="toasts"><div v-for="t in ui.toasts" :key="t.id" class="toast" :class="{ err: t.err }">{{ t.msg }}<a v-if="t.link" @click="go(t.link[1])">{{ t.link[0] }}</a></div></div>` })
comp('ErrBox', {
  template: `<Modal v-if="ui.err" :t="ui.err.msg" :w="480" @close="ui.err = null"><ul class="errs"><li v-for="x in ui.err.list" :key="x">{{ x }}</li></ul>
    <template #foot><button class="btn pri" @click="ui.err = null">知道了</button></template></Modal>`,
})
comp('AskBox', {
  setup() { const done = (v) => { ui.ask.resolve(v); ui.ask = null }; return { done } },
  template: `<Modal v-if="ui.ask" :t="ui.ask.title" :w="440" @close="done(false)"><p class="mut">{{ ui.ask.text }}</p>
    <template #foot><button class="btn" @click="done(false)">取消</button><button class="btn pri" :style="ui.ask.danger ? 'background:var(--red);border-color:var(--red)' : ''" @click="done(true)">{{ ui.ask.ok }}</button></template></Modal>`,
})
comp('NotFound', {
  template: `<div><Head t="没有这个页面"/><div class="empty">链接不对，或者这张单是刷新前新建的（原型刷新后数据回到初始）。<p style="margin-top:16px"><a class="btn" href="#/">回首页</a></p></div></div>`,
})

const Shell = {
  setup() {
    const pal = reactive({ open: false })
    const main = ref(null)
    const mod = computed(() => nav.r?.mod)
    const closed = reactive(JSON.parse((() => { try { return localStorage.getItem('scm-proto-nav') } catch { return null } })() || '{}'))
    const toggle = (g) => { closed[g] = !closed[g]; try { localStorage.setItem('scm-proto-nav', JSON.stringify(closed)) } catch { /* 无痕模式 */ } }
    const typing = (e) => /INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || e.target.isContentEditable
    function onKey(e) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); pal.open = !pal.open }
      else if (!typing(e) && !e.metaKey && !e.ctrlKey && e.key.toLowerCase() === 'c' && !escStack.length) fbUi.open = !fbUi.open
    }
    onMounted(() => window.addEventListener('keydown', onKey))
    watch(() => nav.seq, () => { if (main.value) main.value.scrollTop = 0; fbUi.focus = null })
    const fbCount = computed(() => fb.items.length || '')
    return { pal, main, mod, closed, toggle, fbCount }
  },
  template: `<div class="app">
    <aside class="side" data-c="侧边栏">
      <div class="ws"><i>织</i><span>织间服饰</span></div>
      <button class="find" @click="pal.open = true"><Ico n="search" :s="14"/><span>搜索</span><kbd>⌘K</kbd></button>
      <nav><div v-for="g in NAV" :key="g.g || 'top'" class="grp">
        <b v-if="g.g" @click="toggle(g.g)">{{ g.g }}</b>
        <template v-if="!closed[g.g]"><a v-for="it in g.items" :key="it.k" class="nav-i" :class="{ on: it.k === mod }" :href="'#' + it.to"><Ico :n="it.i"/><span>{{ it.t }}</span></a></template>
      </div></nav>
      <div class="side-foot">
        <a class="nav-i" :class="{ on: fbUi.open }" @click="fbUi.open = !fbUi.open"><Ico n="msg"/><span>意见</span><span class="n">{{ fbCount }}</span></a>
        <a class="nav-i" :class="{ on: mod === 'feedback' }" href="#/feedback"><Ico n="list"/><span>意见汇总</span></a>
      </div>
    </aside>
    <main class="main" ref="main"><component v-if="nav.r" :is="nav.r.comp" :key="nav.seq" v-bind="nav.params"/><NotFound v-else/></main>
    <FeedbackPanel v-if="fbUi.open"/>
    <Pins v-if="fbUi.open"/>
    <Picker v-if="fbUi.picking"/>
    <Palette v-if="pal.open" @close="pal.open = false"/>
    <ErrBox/><AskBox/><Toasts/>
  </div>`,
}

const app = Vue.createApp(Shell)
Object.entries(COMPONENTS).forEach(([n, c]) => app.component(n, c))
Object.assign(app.config.globalProperties, G, { NAV, me, fb, fbUi })
app.config.errorHandler = (e) => showErr(e)
parseHash()
app.mount('#app')

if (/selftest/.test(location.search)) {
  const r = selftest()
  window.__selftest = r
  const pass = r.filter((x) => x.ok).length
  const el = document.createElement('pre')
  el.id = 'selftest'
  el.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:99;max-height:60vh;overflow:auto;background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:12px;font:12px/1.6 var(--mono);box-shadow:var(--shadow)'
  el.textContent = `自检 ${pass}/${r.length}\n` + r.map((x) => (x.ok ? '✓ ' : '✗ ') + x.name + (x.ok ? '' : '  → ' + JSON.stringify(x.got))).join('\n')
  document.body.appendChild(el)
}
