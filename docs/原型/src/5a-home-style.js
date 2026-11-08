/* ============================================================
 * 首页（待办）、款式（列表 / 详情 / 资料包）
 * 页面写法（其他页面照这个来）：
 *   列表页 = Head（标题 + 主按钮）→ .bar（Seg 状态 + Search）→ Tbl（点行进详情）
 *   详情页 = Head（返回 + 按状态出的按钮）→ .detail：.body（标题、页签、内容）+ .aside（属性、操作记录）
 *   区块加 data-c="名字"，意见圈位置时吸附到它
 * ============================================================ */

/* ---------- 首页 ---------- */
TODOS.push(() => {
  const live = db.productions.filter(prodLive)
  const risk = live.map(prodRisk)
  const up = upstreamPending()
  return [
    { t: '待审核的订单', n: db.orders.filter((o) => o.status === '待审核').length, to: '/orders?s=待审核', i: 'file' },
    { t: '待审核的变更', n: db.changes.filter((c) => c.status === '待审核').length, to: '/changes', i: 'diff' },
    { t: '待审核的采购单', n: db.purchases.filter((p) => p.status === '待审核').length, to: '/purchases?s=待审核', i: 'cart' },
    { t: '待审核的加工单', n: db.productions.filter((p) => p.status === '待审核').length, to: '/production?s=待审核', i: 'factory' },
    { t: '资料包草稿待生效', n: db.techpacks.filter((t) => t.status === '草稿').length, to: '/styles?tp=草稿', i: 'shirt' },
    { t: '已审核还没排单的订单', n: db.orders.filter((o) => orderLive(o) && orderSched(o) === 0).length, to: '/orders?f=待排单', i: 'file' },
    { t: '加工单已逾期', n: risk.filter((r) => r.late).length, to: '/production?f=late', i: 'factory', tone: 'red' },
    { t: '节点延期', n: risk.filter((r) => r.node).length, to: '/production?f=node', i: 'factory', tone: 'red' },
    { t: '有未关闭的异常', n: risk.filter((r) => r.issue).length, to: '/production?f=issue', i: 'alert', tone: 'amber' },
    { t: '7 天内交货', n: risk.filter((r) => r.near).length, to: '/production?f=near', i: 'factory', tone: 'amber' },
    { t: '到料逾期', n: arrivals().filter((a) => a.risk === 'late').length, to: '/arrivals?f=late', i: 'truck', tone: 'red' },
    { t: '上游订单已变更，待处理', n: up.length, to: up[0] ? (up[0].no.startsWith('JG') ? '/production/' : up[0].no.startsWith('SL') ? '/mrp/' : '/purchases/') + up[0].no : '/', i: 'diff', tone: 'amber' },
    { t: '供应商证照 30 天内到期', n: expiringCerts().length, to: '/suppliers?f=证照', i: 'store' },
  ]
})

// 首页：按角色看的仪表盘。数字卡片点进去就是筛好的列表；图表的前几周是演示用的历史数
const HIST_ORDER = [1180, 860, 1420, 990, 1630, 1210, 1880, 1350, 1560, 2120, 1740]
const HIST_RET = [640, 920, 780, 1150, 1020, 1310, 990, 1460, 1280, 1390, 1610]
const CAT_COLORS = ['#2d4e8c', '#d9a23a', '#2e7350', '#b4392b', '#6c83ae', '#8a909c', '#b49a6a', '#5b6475', '#96b9a8', '#c9b37e']
function weekly(rows, dateOf, qtyOf, hist) {
  const end = toD(clock.today), weeks = hist.length + 1
  const out = hist.map((v) => v), lbl = []
  for (let i = 0; i < weeks; i++) { const d = new Date(end); d.setDate(d.getDate() - (weeks - 1 - i) * 7); lbl.push(`${d.getMonth() + 1}/${d.getDate()}`) }
  out.push(0)
  rows.forEach((r) => { const k = weeks - 1 - Math.floor(diffDays(clock.today, dateOf(r)) / 7); if (k >= 0 && k < weeks) out[k] += qtyOf(r) })
  return { lbl, out }
}
route('/', {
  setup() {
    const hour = new Date().getHours()
    const hi = hour < 11 ? '上午好' : hour < 14 ? '中午好' : hour < 19 ? '下午好' : '晚上好'
    const d = toD(clock.today)
    const today = `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 星期${'日一二三四五六'[d.getDay()]}`
    const live = computed(() => db.productions.filter(prodLive))
    const risk = computed(() => live.value.map((p) => ({ p, r: prodRisk(p) })))
    const kpi = computed(() => {
      const lo = db.orders.filter(orderLive), smp = db.samples || []
      const arr = arrivals()
      return {
        dev: { n: db.styles.filter((s) => s.status !== '停用').length, sub: [['打样中', smp.filter((x) => x.status === '开发中').length, '/samples'], ['资料包草稿', db.techpacks.filter((t) => t.status === '草稿').length, '/styles?tp=草稿'], ['样衣', smp.length, '/samples']] },
        ord: { n: sum(lo, orderQty), c: lo.length, sub: [['待审核', db.orders.filter((o) => o.status === '待审核').length, '/orders?s=待审核'], ['待排单', lo.filter((o) => orderSched(o) === 0).length, '/orders?f=待排单'], ['变更待审', db.changes.filter((c) => c.status === '待审核').length, '/changes']] },
        prd: { n: sum(live.value, prodQty), c: live.value.length, sub: [['已逾期', risk.value.filter((x) => x.r.late).length, '/production?f=late'], ['节点延期', risk.value.filter((x) => x.r.node).length, '/production?f=node'], ['未关异常', risk.value.filter((x) => x.r.issue).length, '/production?f=issue']] },
        mat: { n: arr.length, sub: [['到料逾期', arr.filter((a) => a.risk === 'late').length, '/arrivals?f=late'], ['采购待审', db.purchases.filter((p) => p.status === '待审核').length, '/purchases?s=待审核'], ['算料待确认', db.mrps.filter((m) => m.status === '草稿').length, '/mrp']] },
      }
    })
    const trend = computed(() => {
      const a = weekly(db.orders.filter((o) => o.status !== '已取消'), (o) => o.date, orderQty, HIST_ORDER)
      const b = weekly(db.returns, (r) => r.date, (r) => sum(r.lines, (l) => l.qty), HIST_RET)
      return { labels: a.lbl, series: [{ t: '下单件数', c: '#2d4e8c', v: a.out }, { t: '回货件数', c: '#d9a23a', v: b.out }] }
    })
    const cats = computed(() => {
      const acc = {}
      live.value.forEach((p) => { const c = catOf(style(p.style)?.cat).name; acc[c] = (acc[c] || 0) + prodQty(p) - prodRet(p) })
      return Object.entries(acc).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).map(([t, v], i) => ({ t, v, c: CAT_COLORS[i % CAT_COLORS.length] }))
    })
    // 待办：具体到单据
    const todo = computed(() => {
      const g = []
      const audit = [
        ...db.orders.filter((o) => o.status === '待审核').map((o) => ({ t: '订单 ' + o.no, sub: `${custOrDash(o)} · ${fq(orderQty(o))} 件 · 交期 ${o.due}`, to: '/orders/' + o.no, img: orderStyles(o)[0] })),
        ...db.purchases.filter((p) => p.status === '待审核').map((p) => ({ t: '采购单 ' + p.no, sub: `${supName(p.supplier)} · ¥${fm(poAmt(p)).replace('¥', '')}`, to: '/purchases/' + p.no, img: p.lines[0]?.sku })),
        ...db.productions.filter((p) => p.status === '待审核').map((p) => ({ t: '加工单 ' + p.no, sub: `${supName(p.factory)} · ${fq(prodQty(p))} 件`, to: '/production/' + p.no, img: p.style })),
        ...db.changes.filter((c) => c.status === '待审核').map((c) => ({ t: '变更单 ' + c.no, sub: `订单 ${c.order}`, to: '/orders/' + c.order + '?tab=changes', img: order(c.order) && orderStyles(order(c.order))[0] })),
      ]
      if (audit.length) g.push({ tag: '待审核', tone: 'pending', items: audit })
      const late = risk.value.filter((x) => x.r.late || x.r.node).map(({ p, r }) => ({ t: `${p.no} ${styleName(p.style)}`, sub: `${supName(p.factory)} · ${r.late ? '交期 ' + fd(expectDue(p)) + ' 已过' : '节点「' + (curNode(p)?.name || '') + '」延期'}`, to: '/production/' + p.no, img: p.style }))
      if (late.length) g.push({ tag: '生产延期', tone: 'bad', items: late })
      const arr = arrivals().filter((a) => a.risk === 'late').map((a) => ({ t: `${skuName(a.l.sku)} 还差 ${fq(rnd(a.l.qty - a.l.received, 3))} ${unitOf(a.l.sku)}`, sub: `${a.p.no} · ${supName(a.p.supplier)} · 应到 ${fd(a.date)}`, to: '/purchases/' + a.p.no, img: a.l.sku }))
      if (arr.length) g.push({ tag: '到料逾期', tone: 'bad', items: arr })
      const sch = db.orders.filter((o) => orderLive(o) && orderSched(o) === 0).map((o) => ({ t: '订单 ' + o.no, sub: `${custOrDash(o)} · ${fq(orderQty(o))} 件 · 交期 ${o.due}`, to: '/orders/' + o.no, img: orderStyles(o)[0] }))
      if (sch.length) g.push({ tag: '待排单', tone: 'pending', items: sch })
      const iss = live.value.flatMap((p) => (p.issues || []).filter((x) => x.status === '待处理').map((x) => ({ t: `${x.type}：${x.desc}`, sub: `${p.no} · ${userName(x.by)} 上报于 ${fd(x.at)}`, to: '/production/' + p.no, img: p.style })))
      if (iss.length) g.push({ tag: '异常待处理', tone: 'ring', items: iss })
      return g
    })
    const others = computed(() => TODOS.flatMap((f) => f()).filter((x) => x.n > 0))
    const doing = computed(() => live.value.slice().sort((a, b) => expectDue(a).localeCompare(expectDue(b))).slice(0, 6))
    const quick = [['新建订单', 'file', '/orders?new=1'], ['新建款式', 'shirt', '/styles'], ['算料', 'sigma', '/mrp'], ['到料跟踪', 'truck', '/arrivals'], ['生产看板', 'grid', '/board'], ['库存', 'package', '/stock'], ['手机报工', 'phone', '/mobile'], ['意见汇总', 'list', '/feedback']]
    return { hi, today, kpi, trend, cats, todo, others, doing, quick }
  },
  template: `<div class="dash">
    <div class="dash-hi"><div><h1>{{ hi }}，{{ me().name }}</h1><p>{{ today }} · {{ me().role }} · {{ db.companies[0].name }}</p></div>
      <div class="acts"><a class="btn" href="#/styles"><Ico n="shirt" :s="14"/>款式库</a><a class="btn pri" href="#/orders?new=1"><Ico n="plus" :s="14"/>新建订单</a></div></div>
    <div class="dgrid">
      <section class="panel" style="grid-column:span 3" data-c="首页：开发概况"><h3><span class="ico"><Ico n="scissors" :s="15"/></span>开发</h3>
        <div class="pb kpi"><a class="big" href="#/styles"><b>{{ kpi.dev.n }}</b><small>款在售在开发</small></a><a v-for="x in kpi.dev.sub" :key="x[0]" :href="'#' + x[2]"><b>{{ x[1] }}</b><span>{{ x[0] }}</span></a></div></section>
      <section class="panel" style="grid-column:span 3" data-c="首页：订单概况"><h3><span class="ico"><Ico n="file" :s="15"/></span>订单</h3>
        <div class="pb kpi"><a class="big" href="#/orders"><b>{{ fq(kpi.ord.n) }}</b><small>件在手 · {{ kpi.ord.c }} 张单</small></a><a v-for="x in kpi.ord.sub" :key="x[0]" :href="'#' + x[2]"><b :class="{ amber: x[1] }">{{ x[1] }}</b><span>{{ x[0] }}</span></a></div></section>
      <section class="panel" style="grid-column:span 3" data-c="首页：生产概况"><h3><span class="ico"><Ico n="factory" :s="15"/></span>生产</h3>
        <div class="pb kpi"><a class="big" href="#/board"><b>{{ fq(kpi.prd.n) }}</b><small>件在制 · {{ kpi.prd.c }} 张加工单</small></a><a v-for="x in kpi.prd.sub" :key="x[0]" :href="'#' + x[2]"><b :class="{ red: x[1] }">{{ x[1] }}</b><span>{{ x[0] }}</span></a></div></section>
      <section class="panel" style="grid-column:span 3" data-c="首页：物料概况"><h3><span class="ico"><Ico n="truck" :s="15"/></span>物料</h3>
        <div class="pb kpi"><a class="big" href="#/arrivals"><b>{{ kpi.mat.n }}</b><small>行物料在途</small></a><a v-for="x in kpi.mat.sub" :key="x[0]" :href="'#' + x[2]"><b :class="{ red: x[0] === '到料逾期' && x[1], amber: x[0] !== '到料逾期' && x[1] }">{{ x[1] }}</b><span>{{ x[0] }}</span></a></div></section>

      <section class="panel" style="grid-column:span 8" data-c="首页：下单与回货趋势"><h3>下单与回货<span class="acts dim" style="font-size:12px">近 12 周 · 件</span></h3>
        <div class="pb"><AreaChart :series="trend.series" :labels="trend.labels" :h="230"/></div></section>
      <section class="panel" style="grid-column:span 4" data-c="首页：在制品类"><h3>在制品类<span class="acts dim" style="font-size:12px">未回货件数</span></h3>
        <div class="pb" style="display:grid;place-items:center"><Donut :items="cats" unit=" 件" :s="140"/><div v-if="!cats.length" class="empty">没有在做的加工单</div></div></section>

      <section class="panel" style="grid-column:span 7" data-c="待办"><h3>待办<span class="acts"><span class="pill">{{ todo.reduce((a, g) => a + g.items.length, 0) }} 件</span></span></h3>
        <div class="pb">
          <div v-for="g in todo" :key="g.tag" class="todo-g"><header><St :s="g.tone === 'pending' ? '待处理' : g.tone === 'bad' ? '不合格' : '已审核'" :t="g.tag"/>{{ g.items.length }}</header>
            <a v-for="x in g.items.slice(0, 4)" :key="x.to + x.t" class="todo-i" :href="'#' + x.to"><Thumb v-if="x.img" :code="x.img" :s="34"/><div class="col"><span class="ell">{{ x.t }}</span><small class="ell">{{ x.sub }}</small></div><Ico n="right" :s="14" class="dim"/></a></div>
          <div v-if="!todo.length" class="empty">手上没有要处理的单据</div>
          <div v-if="others.length" class="chips" style="margin-top:12px"><a v-for="x in others" :key="x.t" class="chip" :href="'#' + x.to">{{ x.t }}<b :class="x.tone">{{ x.n }}</b></a></div>
        </div></section>
      <div style="grid-column:span 5;display:flex;flex-direction:column;gap:12px">
        <section class="panel" data-c="本周交期"><h3>在做的加工单<span class="acts"><a class="btn sm ghost" href="#/board">看板<Ico n="right" :s="12"/></a></span></h3>
          <div class="pb" style="padding-top:4px;padding-bottom:4px">
            <a v-for="p in doing" :key="p.no" class="row" style="padding:9px 0;border-bottom:1px solid var(--line)" :href="'#/production/' + p.no">
              <Thumb :code="p.style" :s="38"/><div class="col" style="flex:1;min-width:0"><span class="ell"><span class="code">{{ p.no }}</span></span><small class="dim ell">{{ styleName(p.style) }} · {{ supName(p.factory) }}</small></div>
              <Prog :a="prodRet(p)" :b="prodQty(p)"/><span style="width:72px;text-align:right"><Days :d="expectDue(p)" :warn="7"/></span></a>
            <div v-if="!doing.length" class="empty">没有在做的加工单</div>
          </div></section>
        <section class="panel" data-c="快捷入口"><h3>快捷入口</h3>
          <div class="pb quick"><a v-for="q in quick" :key="q[0]" :href="'#' + q[2]"><i><Ico :n="q[1]" :s="18"/></i>{{ q[0] }}</a></div></section>
      </div>
    </div>
  </div>`,
}, { mod: 'home', t: '首页' })

/* ---------- 款式列表 ---------- */
SEARCH.push(() => db.styles.map((s) => ({ t: s.code + ' ' + s.name, sub: '款式', to: '/styles/' + s.code, i: 'shirt' })))

route('/styles', {
  setup() {
    const s = reactive({ tab: '全部', q: '', view: 'grid', tp: nav.query.tp || '', add: false })
    const counts = computed(() => Object.fromEntries(['草稿', '启用', '停用'].map((k) => [k, db.styles.filter((x) => x.status === k).length])))
    const tabs = computed(() => [['全部', '全部', db.styles.length], ...['启用', '草稿', '停用'].map((k) => [k, k, counts.value[k]])])
    const rows = computed(() => db.styles.filter((x) => (s.tab === '全部' || x.status === s.tab) && (!s.tp || tpState(x.code) === s.tp) && (!s.q || (x.code + x.name).toLowerCase().includes(s.q.toLowerCase()))).slice().reverse())
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="款式"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建款式</button></Head>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.tab" :items="tabs"/>
      <button v-if="s.tp" class="chip on" @click="s.tp = ''">资料包{{ s.tp }}<Ico n="x" :s="12"/></button>
      <span class="sp"></span>
      <Seg v-model="s.view" :items="[['grid', '图卡'], ['list', '列表']]"/>
      <Search v-model="s.q"/>
    </div>
    <div v-if="s.view === 'grid'" class="cards" data-c="款式图卡">
      <div v-for="x in rows" :key="x.code" class="card" @click="go('/styles/' + x.code)">
        <div class="pic"><Thumb :code="x.code" :s="176"/><St :s="x.status"/></div>
        <div class="meta">
          <div class="row"><span class="code">{{ x.code }}</span><span style="flex:1"></span><span class="dim" style="font-size:12px">{{ fm(x.tagPrice) }}</span></div>
          <div>{{ x.name }}</div>
          <div class="row g4"><Sw v-for="c in x.colors" :key="c" :c="c" :n="false"/><span class="dim" style="margin-left:4px;font-size:12px">{{ x.year }} {{ db.seasons.find(z => z.code === x.season).name }}</span></div>
        </div>
      </div>
      <div v-if="!rows.length" class="empty" style="grid-column:1/-1">没有符合条件的款式</div>
    </div>
    <div v-else data-c="款式列表">
      <Tbl :rows="rows" link @row="x => go('/styles/' + x.code)" empty="没有符合条件的款式" :cols="[
        { k: 'code', t: '款号' }, { k: 'name', t: '名称' }, { k: 'cat', t: '品类', f: x => catOf(x.cat).name },
        { k: 'season', t: '季节', f: x => x.year + ' ' + db.seasons.find(z => z.code === x.season).name + ' · ' + x.wave },
        { k: 'cs', t: '颜色 × 尺码' }, { k: 'tp', t: '资料包' }, { k: 'tagPrice', t: '吊牌价', c: 'num', f: x => fm(x.tagPrice) },
        { k: 'designer', t: '设计师', f: x => userName(x.designer) }, { k: 'status', t: '状态' }]">
        <template #code="{ r }"><span class="row"><Thumb :code="r.code" :s="26"/><span class="code">{{ r.code }}</span></span></template>
        <template #cs="{ r }"><span class="row g4"><Sw v-for="c in r.colors" :key="c" :c="c" :n="false"/><span class="dim">{{ r.colors.length }} × {{ sizesOf(r.sizeGroup).length }}</span></span></template>
        <template #tp="{ r }"><St :s="tpState(r.code)" :t="activeTp(r.code) ? 'V' + activeTp(r.code).ver : tpState(r.code)"/></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <StyleNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'styles', t: '款式' })

comp('StyleNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ name: '', cat: 'F', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', sizeGroup: 'W5', colors: [], tagPrice: null, factory: 'GYS0004', designer: db.me, source: '自主开发', images: [] })
    const code = computed(() => { const p = String(f.year).slice(2) + f.season + f.cat; return p + String(db.styles.filter((s) => s.code.startsWith(p)).length + 1).padStart(3, '0') })
    const factories = computed(() => db.suppliers.filter((s) => isFactory(s) && s.status === '合作中'))
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    function save() { const s = run(() => createStyle({ ...f, colors: [...f.colors], images: [...f.images] })); if (s) { emit('close'); go('/styles/' + s.code); toast(`${s.code} 已建，生成 ${s.skus.length} 个 SKU`) } }
    return { f, code, factories, toggle, save }
  },
  template: `<Modal t="新建款式" :w="640" @close="$emit('close')">
    <div class="form">
      <label class="f full">名称<input class="inp" v-model="f.name" placeholder="如：女式风衣"></label>
      <label class="f">品类<select class="sel" v-model="f.cat"><option v-for="c in db.categories" :key="c.code" :value="c.code">{{ c.path }} / {{ c.name }}</option></select></label>
      <label class="f">款号<input class="inp code" :value="code" disabled></label>
      <label class="f">年份<select class="sel" v-model.number="f.year"><option>2026</option><option>2027</option></select></label>
      <label class="f">季节<select class="sel" v-model="f.season"><option v-for="x in db.seasons" :key="x.code" :value="x.code">{{ x.name }}</option></select></label>
      <label class="f">波段<select class="sel" v-model="f.wave"><option v-for="x in db.dicts.波段" :key="x">{{ x }}</option></select></label>
      <label class="f">尺码组<select class="sel" v-model="f.sizeGroup"><option v-for="g in db.sizeGroups" :key="g.id" :value="g.id">{{ g.name }} {{ g.sizes.join(' ') }}</option></select></label>
      <div class="f full">颜色<div class="chips"><button v-for="c in db.colors" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
      <label class="f">吊牌价<input class="inp num" type="number" v-model.number="f.tagPrice"></label>
      <label class="f">默认加工厂<select class="sel" v-model="f.factory"><option v-for="s in factories" :key="s.code" :value="s.code">{{ s.short }}</option></select></label>
      <div class="f full" data-c="款式图片">款式图片<Pics v-model="f.images" :colors="f.colors"/></div>
    </div>
    <template #foot><span class="dim" v-if="f.colors.length">{{ f.colors.length }} × {{ sizesOf(f.sizeGroup).length }} = {{ f.colors.length * sizesOf(f.sizeGroup).length }} 个 SKU</span><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建款式</button></template>
  </Modal>`,
})

/* ---------- 款式详情 ---------- */
route('/styles/:code', {
  props: ['code'],
  setup(p) {
    const s = computed(() => style(p.code))
    const tab = ref('tp')
    const orders = computed(() => db.orders.filter((o) => o.lines.some((l) => l.style === p.code)))
    const more = computed(() => s.value ? [
      can('style', s.value, '停用') && { t: '停用', danger: true, fn: () => run(() => doAction('style', s.value, '停用')) },
      can('style', s.value, '恢复') && { t: '恢复启用', fn: () => run(() => doAction('style', s.value, '恢复')) },
    ].filter(Boolean) : [])
    const addColor = (c) => { addStyleColor(s.value, c); toast(`加了 ${colorName(c)}，新增 ${sizesOf(s.value.sizeGroup).length} 个 SKU`) }
    const unmatched = computed(() => (s.value ? unmatchedColors(s.value) : []))
    const season = (x) => x.year + ' ' + db.seasons.find((z) => z.code === x.season).name
    return { s, tab, orders, more, addColor, unmatched, season }
  },
  template: `<div v-if="s">
    <Head :t="s.code" :back="['款式', '/styles']">
      <button v-if="can('style', s, '启用')" class="btn pri" @click="run(() => doAction('style', s, '启用'))">启用</button>
      <a v-if="s.status === '启用' && activeTp(s.code)" class="btn pri" :href="'#/orders?new=' + s.code"><Ico n="plus" :s="14"/>下订单</a>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ s.name }}<St :s="s.status"/></div>
        <div class="sub"><span>{{ catOf(s.cat).path }} / {{ catOf(s.cat).name }}</span><span>{{ season(s) }} · {{ s.wave }}</span><span class="row g4"><Sw v-for="c in s.colors" :key="c" :c="c" :n="false"/></span></div>
        <div v-if="unmatched.length" class="banner" data-c="未配料提示"><Ico n="alert"/>新颜色 {{ unmatched.map(colorName).join('、') }} 还没配料，订单不能下这个颜色<button class="btn sm" @click="tab = 'tp'">去资料包</button></div>
        <Tabs v-model="tab" :items="[['tp', '资料包'], ['sku', '颜色尺码', s.skus.length], ['orders', '订单', orders.length]]"/>
        <TechPack v-if="tab === 'tp'" :code="s.code"/>
        <section v-if="tab === 'sku'" data-c="颜色尺码">
          <table class="mx"><thead><tr><th></th><th v-for="z in sizesOf(s.sizeGroup)" :key="z">{{ z }}</th></tr></thead>
            <tbody><tr v-for="c in s.colors" :key="c"><td><Sw :c="c"/></td><td v-for="z in sizesOf(s.sizeGroup)" :key="z" class="code dim">{{ s.code }}-{{ c }}-{{ z }}</td></tr></tbody></table>
          <div class="sec"><h3>加颜色</h3><div class="chips"><button v-for="c in db.colors.filter(c => !s.colors.includes(c.code))" :key="c.code" class="chip" @click="addColor(c.code)"><Ico n="plus" :s="12"/><Sw :c="c.code"/></button></div></div>
        </section>
        <section v-if="tab === 'orders'" data-c="款式的订单">
          <Tbl inset flat :rows="orders" link @row="o => go('/orders/' + o.no)" empty="这个款还没下过订单" :cols="[
            { k: 'no', t: '订单号', c: 'code' }, { k: 'customer', t: '客户', f: o => o.customer ? custName(o.customer) : o.type },
            { k: 'qty', t: '本款数量', c: 'num', f: o => sum(o.lines.filter(l => l.style === s.code), l => l.qty) },
            { k: 'ver', t: '资料包', f: o => { const v = o.lines.find(l => l.style === s.code)?.ver; return v ? 'V' + v : '—' } },
            { k: 'due', t: '客户交期', f: o => fd(o.due) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </section>
      </div>
      <aside class="aside" data-c="款式属性">
        <Gallery :code="s.code" :colors="s.colors" :owner="s" edit/>
        <dl class="props">
          <dt>品牌</dt><dd>{{ s.brand }}</dd>
          <dt>风格</dt><dd>{{ s.tag }}</dd>
          <dt>来源</dt><dd>{{ s.source }}<template v-if="s.customer"> · {{ custName(s.customer) }}</template></dd>
          <dt v-if="s.custNo">客户款号</dt><dd v-if="s.custNo" class="code">{{ s.custNo }}</dd>
          <dt v-if="s.sample">样衣</dt><dd v-if="s.sample"><a class="code" :href="'#/samples/' + s.sample">{{ s.sample }}</a></dd>
          <dt>尺码组</dt><dd>{{ db.sizeGroups.find(g => g.id === s.sizeGroup).name }} {{ sizesOf(s.sizeGroup).join(' ') }}</dd>
          <dt>吊牌价</dt><dd>{{ fm(s.tagPrice) }}</dd>
          <dt>参考成本</dt><dd>{{ activeTp(s.code) ? fm(tpMaterialCost(activeTp(s.code)) + tpLabor(activeTp(s.code))) : fm(s.cost) }}</dd>
          <dt>默认加工厂</dt><dd>{{ supName(s.factory) }}</dd>
          <dt>设计师</dt><dd>{{ userName(s.designer) }}</dd>
        </dl>
        <DocFiles :doc="s"/>
        <h4>操作记录</h4><Log :items="s.log"/>
      </aside>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'styles', t: (p) => '款式 ' + p.code })

/* ---------- 资料包：版本、BOM（正背面款式图）、按颜色展开、尺寸表、工艺 ---------- */
comp('TechPack', {
  props: { code: String },
  setup(p) {
    const s = computed(() => style(p.code))
    const tps = computed(() => tpsOf(p.code))
    const ver = ref(tps.value.find((t) => t.status === '草稿')?.ver || activeTp(p.code)?.ver || tps.value[0]?.ver)
    const tp = computed(() => tpOf(p.code, ver.value))
    const edit = computed(() => tp.value?.status === '草稿')
    const view = ref('bom'), byColor = ref(s.value.colors[0]), open = ref(null), adding = ref(false)
    const sizes = computed(() => sizesOf(s.value.sizeGroup))
    const useText = (r) => (r.um === '统一' ? fq(r.use) : `${fq(r.uses[sizes.value[0]])}–${fq(r.uses[sizes.value[sizes.value.length - 1]])}`)
    function revise() { const t = run(() => reviseTp(tp.value)); if (t?.ver) { ver.value = t.ver; toast(`修订出 V${t.ver}，改完点「生效」`) } }
    function publish() { if (run(() => publishTp(tp.value))) toast(`V${tp.value.ver} 已生效`) }
    function create() { const t = createTechpack(p.code, [], [], ''); ver.value = t.ver; adding.value = true }
    const matSkus = (r) => mat(r.mat).skus
    // BOM 图：鼠标指到图上的号或表里的行，两边一起亮，右边显示这行料
    const hl = ref(0)
    const hover = (i) => { if (i != null) hl.value = i }
    const cur = computed(() => { const r = tp.value?.bom[hl.value] || tp.value?.bom[0]; return r && { i: tp.value.bom.indexOf(r), r, sku: rowSku(r, byColor.value) } })
    // 尺寸表：改基码或档差，其他码按档差重算；单独改别的码只改那一格
    const sp = ref(0)
    const base = computed(() => tp.value.spec.base)
    const fcm = (v) => (v == null || v === '' || Number.isNaN(v) ? '—' : String(rnd(+v, 1)))
    function setVal(r, z, v) { if (z === base.value) gradeRow(r, sizes.value, z, +v); else r.vals[z] = v === '' ? null : +v }
    function setStep(r, v) { r.step = +v || 0; gradeRow(r, sizes.value, base.value, r.vals[base.value] || 0) }
    function addSpec() { tp.value.spec.rows.push({ pt: '', how: '', tol: 0.5, step: 0, vals: {} }); sp.value = tp.value.spec.rows.length - 1 }
    const stepText = (r) => { const d = [...new Set(sizes.value.slice(1).map((z, i) => rnd((r.vals[z] ?? 0) - (r.vals[sizes.value[i]] ?? 0), 1)))]; return d.join(' / ') }
    return { s, tps, ver, tp, edit, view, byColor, open, adding, sizes, useText, revise, publish, create, matSkus, hl, hover, cur, sp, base, fcm, setVal, setStep, addSpec, stepText, specLetter }
  },
  template: `<div>
    <div v-if="!tps.length" class="empty">这个款还没有资料包<p style="margin-top:14px"><button class="btn pri" @click="create">建资料包</button></p></div>
    <template v-else>
      <div class="row" style="margin-bottom:14px" data-c="资料包版本">
        <Seg v-model="ver" :items="tps.map(t => [t.ver, 'V' + t.ver + (t.status === '已生效' ? ' 生效中' : t.status === '草稿' ? ' 草稿' : '')])"/>
        <span style="flex:1"></span>
        <Seg v-model="view" :items="[['bom', 'BOM'], ['color', '按颜色'], ['spec', '尺寸表'], ['ops', '工艺']]"/>
        <button v-if="edit && view === 'spec'" class="btn" @click="addSpec"><Ico n="plus" :s="14"/>加部位</button>
        <button v-else-if="edit && view !== 'ops'" class="btn" @click="adding = true"><Ico n="plus" :s="14"/>加一行</button>
        <button v-if="edit" class="btn pri" @click="publish">生效</button>
        <button v-if="tp.status === '已生效'" class="btn" @click="revise">修订</button>
        <button class="btn ghost icon" title="打印资料包" @click="toast('原型：这里会出可打印的资料包（正背面款式图、颜色尺码、按颜色展开的 BOM、尺寸表、工艺单）')"><Ico n="printer"/></button>
      </div>
      <div v-if="edit" style="margin-bottom:12px"><input class="inp" style="width:100%" v-model="tp.note" placeholder="本版说明：这版改了什么"></div>
      <p v-else-if="tp.note" class="mut" style="margin-bottom:12px">{{ tp.note }}</p>

      <div v-if="(view === 'bom' || view === 'color') && cur" class="bomtop" data-c="BOM 图">
        <BomFigure :code="s.code" :color="byColor" :rows="tp.bom" :hl="cur.i" @hover="hover"/>
        <div class="matcard">
          <div class="row g4"><button v-for="c in s.colors" :key="c" class="gthumb" :class="{ on: c === byColor }" :title="colorName(c)" @click="byColor = c"><Thumb :code="s.code" :color="c" :s="34"/></button><span class="mut" style="margin-left:6px">{{ colorName(byColor) }}</span></div>
          <div class="row" style="gap:14px;align-items:flex-start"><Thumb :code="cur.sku || cur.r.mat" :s="96"/>
            <div class="col" style="gap:4px;min-width:0"><b style="font-size:15px">{{ cur.i + 1 }} · {{ cur.r.part }}</b><span>{{ cur.sku ? skuName(cur.sku) : mat(cur.r.mat).name }}</span><span v-if="!cur.sku" class="flag red">{{ colorName(byColor) }}还没配料</span><span v-else class="code dim">{{ cur.sku }}</span></div></div>
          <dl class="props"><dt>用量</dt><dd>{{ useText(cur.r) }} {{ mat(cur.r.mat).unit }} · 损耗 {{ cur.r.loss }}%</dd><dt>来源</dt><dd :class="{ amber: cur.r.src === '客供' }">{{ cur.r.src }}</dd>
            <dt>供应商</dt><dd>{{ supName(cur.r.supplier || mat(cur.r.mat).supplier) }}</dd><template v-if="cur.r.note"><dt>备注</dt><dd>{{ cur.r.note }}</dd></template></dl>
        </div>
      </div>

      <div v-if="view === 'bom'" data-c="BOM">
        <table class="tbl inset flat">
          <thead><tr><th style="width:28px">#</th><th>部位</th><th>物料</th><th>配色</th><th class="num">用量</th><th class="num">损耗</th><th>来源</th><th class="num">单件成本</th><th v-if="edit"></th></tr></thead>
          <tbody>
            <template v-for="(r, i) in tp.bom" :key="i">
              <tr class="link" :class="{ sel: cur && cur.i === i }" @mouseenter="hover(i)" @click="open = open === i ? null : i">
                <td class="dim">{{ i + 1 }}</td><td>{{ r.part }}</td>
                <td><div class="row"><Thumb :code="r.sku || Object.values(r.map || {})[0] || r.mat" :s="30"/>{{ mat(r.mat).name }}</div></td>
                <td><span v-if="r.mode === '固定'">{{ skuOf(r.sku)?.color ? colorName(skuOf(r.sku).color) : '—' }}{{ skuOf(r.sku)?.spec ? ' ' + skuOf(r.sku).spec : '' }}</span>
                  <span v-else class="row g4"><span class="dim">随款色</span><template v-for="c in s.colors" :key="c"><Sw v-if="r.map[c]" :c="skuOf(r.map[c])?.color" :n="false"/><span v-else class="flag red">缺</span></template></span></td>
                <td class="num">{{ useText(r) }} <span class="dim">{{ mat(r.mat).unit }}</span></td>
                <td class="num">{{ r.loss }}%</td>
                <td><span :class="{ amber: r.src === '客供', dim: r.src === '工厂自备' }">{{ r.src }}</span></td>
                <td class="num">{{ r.src === '我方采购' ? fm(rowCost(r, sizes)) : '—' }}</td>
                <td v-if="edit" class="num"><button class="btn ghost icon sm" @click.stop="tp.bom.splice(i, 1)"><Ico n="trash" :s="13"/></button></td>
              </tr>
              <tr v-if="open === i"><td></td><td :colspan="edit ? 8 : 7" style="padding:10px 0 14px;white-space:normal">
                <div class="row" style="gap:28px;align-items:flex-start;flex-wrap:wrap">
                  <div v-if="r.mode === '随款色'"><div class="dim" style="font-size:12px;margin-bottom:6px">款色 → 物料颜色</div>
                    <div v-for="c in s.colors" :key="c" class="row" style="height:30px"><Sw :c="c" style="width:56px"/><Ico n="right" :s="12" class="dim"/>
                      <select v-if="edit" class="sel" style="height:26px" v-model="r.map[c]"><option :value="undefined">未配</option><option v-for="k in matSkus(r)" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></select>
                      <span v-else>{{ r.map[c] ? skuName(r.map[c]) : '未配' }}</span></div></div>
                  <div v-if="r.um === '分码'"><div class="dim" style="font-size:12px;margin-bottom:6px">分码用量（{{ mat(r.mat).unit }}/件）</div>
                    <table class="mx"><tr><th v-for="z in sizes" :key="z">{{ z }}</th></tr><tr><td v-for="z in sizes" :key="z"><input v-if="edit" class="inp cell num" style="width:56px" v-model.number="r.uses[z]"><template v-else>{{ fq(r.uses[z]) }}</template></td></tr></table></div>
                  <div v-if="edit" class="form" style="grid-template-columns:repeat(3,110px)">
                    <label v-if="r.um === '统一'" class="f">用量<input class="inp num" v-model.number="r.use"></label>
                    <label class="f">损耗 %<input class="inp num" v-model.number="r.loss"></label>
                    <label class="f">来源<select class="sel" v-model="r.src"><option>我方采购</option><option>客供</option><option>工厂自备</option></select></label>
                  </div>
                  <div v-if="r.note" class="mut">备注：{{ r.note }}</div>
                </div>
              </td></tr>
            </template>
            <tr v-if="!tp.bom.length"><td colspan="9" class="empty">还没有物料，点「加一行」</td></tr>
          </tbody>
        </table>
        <div class="row" style="justify-content:flex-end;gap:20px;margin-top:12px" data-c="单件成本">
          <span class="mut">物料 {{ fm(tpMaterialCost(tp)) }}</span><span class="mut">加工 {{ fm(tpLabor(tp)) }}</span><b>单件 {{ fm(tpMaterialCost(tp) + tpLabor(tp)) }}</b>
        </div>
      </div>

      <div v-if="view === 'color'" data-c="按颜色展开">
        <table class="tbl inset flat"><thead><tr><th>部位</th><th>物料</th><th v-for="z in sizes" :key="z" class="num">{{ z }}</th><th>来源</th></tr></thead>
          <tbody><tr v-for="(r, i) in tp.bom" :key="i" :class="{ sel: cur && cur.i === i }" @mouseenter="hover(i)"><td><span class="dim" style="margin-right:6px">{{ i + 1 }}</span>{{ r.part }}</td><td><span class="row"><Thumb :code="rowSku(r, byColor) || r.mat" :s="28"/>{{ rowSku(r, byColor) ? skuName(rowSku(r, byColor)) : '未配料' }}</span></td>
            <td v-for="z in sizes" :key="z" class="num">{{ fq(rowUse(r, z)) }}</td><td class="mut">{{ r.src }}</td></tr></tbody></table>
      </div>

      <div v-if="view === 'spec'" class="spectop" :class="{ wide: edit }">
        <div class="figbox" data-c="量法图"><SpecFigure :code="s.code" :color="byColor" :rows="tp.spec.rows" :hl="sp" :s="250" @hover="i => sp = i"/>
          <ul v-if="edit" class="tips"><li>单位 cm，基码 {{ base }}（压底色那列）</li><li>改基码或档差：其他码按档差重新放码</li><li>单独改某个码：只改那一格，档差那列会显示不均匀</li><li>部位名和图上的线对得上时，图上会画出量法</li></ul>
          <span v-else class="dim" style="font-size:12px">单位 cm · 基码 {{ base }}</span></div>
        <div class="spec" data-c="尺寸表">
          <table class="tbl inset flat">
            <thead><tr><th style="width:30px"></th><th>部位 / 量法</th><th v-for="z in sizes" :key="z" class="num" :class="{ base: z === base }">{{ z }}</th><th v-if="sizes.length > 1" class="num">档差</th><th class="num">公差 ±</th><th v-if="edit"></th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in tp.spec.rows" :key="i" :class="{ sel: sp === i }" @mouseenter="sp = i">
                <td><span class="mk-l" :class="{ on: sp === i }" style="padding:0 3px"><i>{{ specLetter(i) }}</i></span></td>
                <td v-if="edit" style="padding-top:5px;padding-bottom:5px"><input class="inp cell txt" v-model="r.pt" placeholder="部位"><input class="inp cell txt" v-model="r.how" placeholder="怎么量"></td>
                <td v-else>{{ r.pt }}<div class="how">{{ r.how }}</div></td>
                <td v-for="z in sizes" :key="z" class="num" :class="{ base: z === base }"><input v-if="edit" class="inp cell num" :value="r.vals[z]" @change="setVal(r, z, $event.target.value)"><template v-else>{{ fcm(r.vals[z]) }}</template></td>
                <td v-if="sizes.length > 1" class="num"><input v-if="edit" class="inp cell num" :value="r.step" @change="setStep(r, $event.target.value)"><span v-else class="mut">{{ stepText(r) }}</span></td>
                <td class="num"><input v-if="edit" class="inp cell num" v-model.number="r.tol"><template v-else>{{ fcm(r.tol) }}</template></td>
                <td v-if="edit" class="num"><button class="btn ghost icon sm" title="删除这一行" @click="tp.spec.rows.splice(i, 1)"><Ico n="trash" :s="13"/></button></td>
              </tr>
              <tr v-if="!tp.spec.rows.length"><td :colspan="sizes.length + 5" class="empty">还没有量体部位，点「加部位」</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="view === 'ops'" data-c="工艺单">
        <Tbl inset flat :rows="tp.ops" :cols="[{ k: 'cat', t: '类别' }, { k: 'name', t: '工序' }, { k: 'pics', t: '图示' }, { k: 'sec', t: '工时（秒）', c: 'num' }, { k: 'price', t: '工价', c: 'num', f: o => fm(o.price) }]" empty="还没有工序">
          <template #pics="{ r }"><Pics v-if="edit" v-model="r.pics" :s="36" :max="3" bare/><Pics v-else-if="r.pics && r.pics.length" :model-value="r.pics" readonly :s="36"/><span v-else class="dim">—</span></template>
          <template #foot><tr><td>合计</td><td></td><td></td><td class="num">{{ sum(tp.ops, o => o.sec) }}</td><td class="num">{{ fm(tpLabor(tp)) }}</td></tr></template>
        </Tbl>
      </div>
    </template>
    <BomRowNew v-if="adding" :tp="tp" @close="adding = false"/>
  </div>`,
})

comp('BomRowNew', {
  props: { tp: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const s = style(p.tp.style)
    const sizes = sizesOf(s.sizeGroup)
    const f = reactive({ part: '大身', mat: 'ML0012', mode: '随款色', sku: '', map: {}, um: '统一', use: 1, uses: Object.fromEntries(sizes.map((z) => [z, null])), loss: 3, src: '我方采购', note: '' })
    const m = computed(() => mat(f.mat))
    watch(() => f.mat, () => {
      const skus = m.value.skus
      f.sku = skus[0].code
      f.map = Object.fromEntries(s.colors.map((c) => [c, skus.find((k) => k.color === c)?.code]).filter(([, v]) => v))
      f.mode = skus.length > 1 ? '随款色' : '固定'
    }, { immediate: true })
    function save() {
      const r = { ...JSON.parse(JSON.stringify(f)), supplier: m.value.supplier }
      if (r.mode === '固定') delete r.map; else delete r.sku
      p.tp.bom.push(r)
      emit('close')
    }
    return { s, sizes, f, m, save }
  },
  template: `<Modal t="加一行物料" :w="620" @close="$emit('close')">
    <div class="form">
      <label class="f">部位<select class="sel" v-model="f.part"><option v-for="x in db.dicts.BOM部位" :key="x">{{ x }}</option></select></label>
      <label class="f">物料<select class="sel" v-model="f.mat"><option v-for="x in db.materials.filter(x => x.status === '启用')" :key="x.code" :value="x.code">{{ x.code }} {{ x.name }}</option></select></label>
      <div class="f">配色<Seg v-model="f.mode" :items="['固定', '随款色']"/></div>
      <label v-if="f.mode === '固定'" class="f">物料 SKU<select class="sel" v-model="f.sku"><option v-for="k in m.skus" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></select></label>
      <div v-else class="f full">款色 → 物料颜色<div class="chips"><label v-for="c in s.colors" :key="c" class="row g4"><Sw :c="c"/><select class="sel" style="height:26px" v-model="f.map[c]"><option :value="undefined">未配</option><option v-for="k in m.skus" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></select></label></div></div>
      <div class="f">用量<Seg v-model="f.um" :items="['统一', '分码']"/></div>
      <label v-if="f.um === '统一'" class="f">单件用量（{{ m.unit }}）<input class="inp num" v-model.number="f.use"></label>
      <div v-else class="f full">分码用量（{{ m.unit }}/件）<div class="row"><label v-for="z in sizes" :key="z" class="f" style="width:72px">{{ z }}<input class="inp num" v-model.number="f.uses[z]"></label></div></div>
      <label class="f">损耗 %<input class="inp num" v-model.number="f.loss"></label>
      <label class="f">来源<select class="sel" v-model="f.src"><option>我方采购</option><option>客供</option><option>工厂自备</option></select></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">加上</button></template>
  </Modal>`,
})

