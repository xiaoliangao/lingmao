/* ============================================================
 * 外框：侧边栏（按业务流程分组）、⌘K 搜索、弹窗宿主，最后挂载。
 * ph = 分期（PRD §2）：一期 / 二期 / 待定（甲方表里没标黄的）；ref = 需求确认表编号
 * ============================================================ */
const NAV = [
  { items: [{ k: 'home', t: '首页', i: 'home', to: '/', ph: '一期', ref: '13.04 待办' }, { k: 'flow', t: '业务流程', i: 'route', to: '/flow', ref: '原型说明，不是系统功能' }] }, // 业务流程没有 ph：不是系统功能，不标分期
  { g: '开发', items: [
    { k: 'plans', t: '企划', i: 'layers', to: '/plans', ph: '待定', ref: 'M02 · 02.01–02.02' },
    { k: 'samples', t: '样衣', i: 'scissors', to: '/samples', ph: '二期', ref: 'M03 · 03.01–03.04' },
    { k: 'styles', t: '款式', i: 'shirt', to: '/styles', ph: '一期', ref: 'M02 · 02.03–02.06，M04 资料包' },
    { k: 'costing', t: '核价', i: 'calc', to: '/costing', ph: '一期', ref: 'M05 · 按款看成本（询价比价、订单毛利待定）' },
  ] },
  { g: '订单', items: [
    { k: 'orders', t: '需求订单', i: 'file', to: '/orders', ph: '一期', ref: 'M06 · 06.01–06.04（甲方 11-14 改名需求订单）' },
    { k: 'changes', t: '变更', i: 'diff', to: '/changes', ph: '一期', ref: 'M06 · 06.06' },
  ] },
  { g: '物料', items: [
    { k: 'mrp', t: '算料', i: 'sigma', to: '/mrp', ph: '一期', ref: 'M07 · 07.01' },
    { k: 'purchases', t: '采购', i: 'cart', to: '/purchases', ph: '一期', ref: 'M07 · 07.02–07.03' },
    // 甲方 11-14：「到料」「出入库」拆成入库单（含待到料）、出库单，盘点单单独成单
    { k: 'stockin', t: '物料入库单', i: 'inbox', to: '/stock-in', ph: '一期', ref: 'M07 · 07.04 到料、07.05–07.08 出入库（甲方 11-14 拆开）' },
    { k: 'stockout', t: '物料出库单', i: 'swap', to: '/stock-out', ph: '一期', ref: 'M07 · 07.05–07.08 出入库（甲方 11-14 拆开）' },
    { k: 'stocktake', t: '物料盘点单', i: 'clipboard', to: '/stock-takes', ph: '一期', ref: '甲方 11-14 新增' },
    { k: 'stock', t: '库存', i: 'package', to: '/stock', ph: '一期', ref: 'M07 · 07.07' },
  ] },
  { g: '生产', items: [
    // 甲方 11-14：加工单按加工方式拆成外发加工单（清加工、半包）、成品采购单（包工包料），一张表
    { k: 'production', t: '外发加工单', i: 'factory', to: '/production', ph: '一期', ref: 'M08 · 08.01–08.06' },
    { k: 'fpurchases', t: '成品采购单', i: 'cart', to: '/fp-purchases', ph: '一期', ref: 'M08 · 08.01–08.06（甲方 11-14 拆出包工包料）' },
    { k: 'board', t: '看板', i: 'grid', to: '/board', ph: '一期', ref: 'M08 · 08.08' },
    { k: 'mobile', t: '手机报工', i: 'phone', to: '/mobile', ph: '一期', ref: 'M08 · 08.05，M13 · 13.02' },
    { k: 'qc', t: '质检', i: 'shield', to: '/qc', ph: '待定', ref: 'M09 · 09.01–09.06' },
  ] },
  // 甲方 11-14：成品仓拆成入库单、出库单、库存三个菜单，排二期；对账单独一组，还是待定
  { g: '成品', items: [
    { k: 'fgin', t: '成品入库单', i: 'warehouse', to: '/fg-in', ph: '二期', ref: 'M10 · 10.01–10.07' },
    { k: 'fgout', t: '成品出库单', i: 'truck', to: '/fg-out', ph: '二期', ref: 'M10 · 10.01–10.07' },
    { k: 'fgstock', t: '成品库存', i: 'package', to: '/fg-stock', ph: '二期', ref: 'M10 · 10.01–10.07' },
  ] },
  { g: '对账结算', items: [
    { k: 'finance', t: '对账', i: 'receipt', to: '/finance', ph: '待定', ref: 'M11 · 11.01–11.08' },
  ] },
  { g: '资料', items: [
    { k: 'suppliers', t: '供应商', i: 'store', to: '/suppliers', ph: '一期', ref: 'M12 · 12.01–12.06' },
    ...(TRADE ? [{ k: 'customers', t: '客户', i: 'user', to: '/customers', ph: '一期', ref: '6.1.8，12.03 商务条款' }] : []), // 客户页面留着，开关关时菜单里没有
    { k: 'contracts', t: '合同', i: 'pen', to: '/contracts', ph: '待排期', ref: '甲方 11-11 新增' },
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
  template: `<div class="mask" @mousedown.self="$emit('close')"><div class="pal" role="dialog" aria-label="快捷跳转">
    <div class="pal-top"><Ico n="search" :s="16"/><input ref="inp" v-model="q" @keydown="key" placeholder="搜索页面、单号、款号、供应商…"><kbd>ESC</kbd></div>
    <ul><li v-for="(x, n) in items" :key="x.to + n" :class="{ on: n === i }" @mouseenter="i = n" @click="pick(x)"><Ico :n="x.i || 'file'" :s="15"/>{{ x.t }}<span>{{ x.sub }}</span></li>
      <li v-if="!items.length" class="dim">无匹配结果</li></ul>
    <div class="pal-foot"><span><kbd>↑↓</kbd> 选择</span><span><kbd>↵</kbd> 跳转</span><span><kbd>[</kbd> 收起侧栏</span><span><kbd>C</kbd> 批注</span></div>
  </div></div>`,
})

comp('Toasts', { template: `<div class="toasts"><div v-for="t in ui.toasts" :key="t.id" class="toast" :class="{ err: t.err }">{{ t.msg }}<a v-if="t.link" @click="go(t.link[1])">{{ t.link[0] }}</a></div></div>` })
comp('ErrBox', {
  template: `<Modal v-if="ui.err" :t="ui.err.msg" :w="480" @close="ui.err = null"><ul class="errs"><li v-for="x in ui.err.list" :key="x">{{ x }}</li></ul>
    <template #foot><button class="btn pri" @click="ui.err = null">知道了</button></template></Modal>`,
})
comp('AskBox', {
  setup() {
    // 带输入框的（askText）：确定返回写的字，取消返回 null
    const done = (v) => { const x = ui.ask; x.resolve(x.input ? (v ? x.input.v.trim() : null) : v); ui.ask = null }
    const blocked = computed(() => ui.ask?.input?.need && !ui.ask.input.v.trim())
    return { done, blocked }
  },
  template: `<Modal v-if="ui.ask" :t="ui.ask.title" :w="440" @close="done(false)"><p class="mut">{{ ui.ask.text }}</p>
    <textarea v-if="ui.ask.input" class="inp" rows="3" style="width:100%;margin-top:10px;height:auto;padding:8px" v-model="ui.ask.input.v" :placeholder="ui.ask.input.ph" data-c="原因"></textarea>
    <template #foot><button class="btn" @click="done(false)">取消</button><button class="btn pri" :disabled="blocked" :style="ui.ask.danger ? 'background:var(--red);border-color:var(--red)' : ''" @click="done(true)">{{ ui.ask.ok }}</button></template></Modal>`,
})
comp('NotFound', {
  template: `<div><Head t="页面不存在"/><div class="empty">未找到对应页面或单据<p style="margin-top:16px"><a class="btn" href="#/">回首页</a></p></div></div>`,
})

const navOf = (k) => NAV.flatMap((g) => g.items).find((x) => x.k === k)

const Shell = {
  setup() {
    const main = ref(null)
    const mod = computed(() => nav.r?.mod)
    const closed = reactive(JSON.parse((() => { try { return localStorage.getItem('scm-proto-nav') } catch { return null } })() || '{}'))
    const toggle = (g) => { closed[g] = !closed[g]; try { localStorage.setItem('scm-proto-nav', JSON.stringify(closed)) } catch { /* 无痕模式 */ } }
    const typing = (e) => /INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || e.target.isContentEditable
    function onKey(e) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); shellUi.pal = !shellUi.pal }
      else if (!typing(e) && !e.metaKey && !e.ctrlKey && e.key === '[' && !escStack.length) shellUi.side = !shellUi.side
      else if (!typing(e) && !e.metaKey && !e.ctrlKey && e.key.toLowerCase() === 'c' && !escStack.length) fbUi.open = !fbUi.open
      else if (!typing(e) && !e.metaKey && !e.ctrlKey && e.key.toLowerCase() === 'i' && !escStack.length) miUi.open = !miUi.open
    }
    onMounted(() => window.addEventListener('keydown', onKey))
    // 页签：点过的模块排在顶上，一个模块一个，记住这个模块最后停在哪一页；首页关不掉，最多留 10 个
    const tabs = reactive([{ k: 'home', to: '/' }])
    watch(() => nav.seq, () => {
      if (main.value) main.value.scrollTop = 0
      fbUi.focus = null
      const k = mod.value
      if (!k) return
      const qs = new URLSearchParams(nav.query).toString(), to = nav.path + (qs ? '?' + qs : '')
      const t = tabs.find((x) => x.k === k)
      t ? (t.to = to) : tabs.push({ k, to })
      if (tabs.length > 10) tabs.splice(tabs.findIndex((x, i) => i && x.k !== k), 1)
    }, { immediate: true })
    function closeTab(i) { const [t] = tabs.splice(i, 1); if (t.k === mod.value) go(tabs[Math.max(0, i - 1)].to) }
    const tabTitle = (k) => (k === 'feedback' ? '批注汇总' : navOf(k)?.t || k)
    const fbCount = computed(() => fb.items.filter((x) => !x.done).length || '') // 只数没解决的
    const todoCount = computed(() => sum(TODOS.flatMap((f) => f()), (x) => x.n))
    watch(() => db.me, () => toast(`已切换身份：${me().name}（${me().role} · ${roleOf(me().role).scope}）`))
    const hasInfo = computed(() => !!MOD_INFO[mod.value])
    return { shellUi, main, mod, closed, toggle, tabs, closeTab, tabTitle, fbCount, todoCount, miUi, hasInfo }
  },
  template: `<div class="app" :data-mod="mod" :data-route="nav.path">
    <aside class="side" :class="{ shut: !shellUi.side }" data-c="侧边栏" aria-label="主导航">
      <div class="ws"><b>织</b><span>织间服饰</span><button type="button" class="btn ghost icon sm ws-fold" title="收起导航 ([)" @click="shellUi.side = false"><Ico n="panel" :s="14"/></button></div>
      <button type="button" class="find" aria-label="全局搜索" @click="shellUi.pal = true"><Ico n="search" :s="14"/><span>搜索或跳转…</span><kbd>⌘K</kbd></button>
      <nav><div v-for="g in NAV" :key="g.g || 'top'" class="grp">
        <b v-if="g.g" :class="{ shut: closed[g.g] }" @click="toggle(g.g)">{{ g.g }}<Ico n="down" :s="12"/></b>
        <template v-if="!closed[g.g]"><a v-for="it in g.items" :key="it.k" class="nav-i" :class="{ on: it.k === mod }" :aria-current="it.k === mod ? 'page' : null" :data-nav="it.k" :href="'#' + it.to"><Ico :n="it.i" :s="15"/><span>{{ it.t }}</span><span v-if="it.k === 'home' && todoCount" class="n">{{ todoCount > 99 ? '99+' : todoCount }}</span></a></template>
      </div></nav>
      <div class="side-foot">
        <div class="side-util">
          <a class="nav-i" :class="{ on: fbUi.open }" role="button" tabindex="0" @click="fbUi.open = !fbUi.open"><Ico n="msg" :s="14"/><span>批注</span><span class="n">{{ fbCount }}</span></a>
          <a class="nav-i" :class="{ on: mod === 'feedback' }" href="#/feedback"><Ico n="list" :s="14"/><span>汇总</span></a>
        </div>
        <label class="me" data-c="切换身份"><i>{{ me().name[0] }}</i>
          <select v-model="db.me" aria-label="当前身份"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }} · {{ u.role }}</option></select></label>
      </div>
    </aside>
    <div class="work">
      <nav class="ptabs" data-c="页签" aria-label="打开过的页面"><a v-for="(t, i) in tabs" :key="t.k" class="ptab" :class="{ on: t.k === mod }" :href="'#' + t.to" @auxclick.prevent="i && closeTab(i)">{{ tabTitle(t.k) }}<span v-if="i" class="x" title="关掉" @click.prevent.stop="closeTab(i)"><Ico n="x" :s="11"/></span></a></nav>
      <main class="main" ref="main"><component v-if="nav.r" :is="nav.r.comp" :key="nav.seq" v-bind="nav.params" class="sheet" :class="{ dash: mod === 'home' }"/><NotFound v-else class="sheet"/></main>
    </div>
    <ModInfo v-if="miUi.open && hasInfo" :k="mod"/>
    <FeedbackPanel v-if="fbUi.open"/>
    <Pins v-if="fbUi.open"/>
    <Picker v-if="fbUi.picking"/>
    <Palette v-if="shellUi.pal" @close="shellUi.pal = false"/>
    <PicViewer v-if="viewer.list"/>
    <ErrBox/><AskBox/><Toasts/>
  </div>`,
}

const app = Vue.createApp(Shell)
Object.entries(COMPONENTS).forEach(([n, c]) => app.component(n, c))
Object.assign(app.config.globalProperties, G, { NAV, me, fb, fbUi })
Object.assign(window.SCM, { nav, go, me, NAV })
app.config.errorHandler = (e) => showErr(e)
document.body.insertAdjacentHTML('afterbegin', PIC_DEFS)
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
