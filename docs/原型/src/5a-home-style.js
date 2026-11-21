/* ============================================================
 * 首页（待办）、款式（列表 / 详情 / 资料包）
 * 页面写法（其他页面照这个来）：
 *   列表页 = Head（标题 + 主按钮）→ .bar（Seg 状态 + Search）→ Tbl（点行进详情）
 *   详情页 = Head（返回 + 按状态出的按钮）→ .detail：.body（标题、页签、内容）+ .aside（属性、操作记录）
 *   区块加 data-c="名字"，意见圈位置时吸附到它
 * ============================================================ */

/* ---------- 首页按当前用户出（PRD §3.2、§6.9.2） ----------
 * 待办看权限和身份：待我审核 = 审批流走到的这一级是我（多级审批，2-store.js 的 APPROVE）；跟单、采购只看自己负责的单；管理层看全部。
 * 概况卡片看数据范围（isMine：制单人，或单据上的业务员 / 跟单员 / 采购员 / 设计师是自己） */
const homeBoss = () => me()?.role === '管理层'
const homeSees = (d) => !ownOnly() || isMine(d)
const homeMineOr = (uid) => homeBoss() || uid === db.me
const homeCanAudit = (m) => auditRoles(m).includes(me()?.role)
// 轮到我审的单；多级的标出第几级
const myAudits = (kind, list) => list.filter((d) => canApprove(kind, d))
const lvSub = (kind, d) => (lvText(kind, d) ? ' · ' + lvText(kind, d) : '')
// 供应商待审批（准入 + 关键信息改动）：自己提的自己不能批，不进自己的待办；新增风险给有审批权限的人和负责采购员
const homeSupAudits = () => supToApprove().filter((s) => (s.status === '准入审批中' ? s.applyBy : s.pending?.by) !== db.me)
const homeSupRisks = () => db.suppliers.filter((s) => s.riskNew && (homeCanAudit('供应商') || homeMineOr(s.buyer)))
// 上游变更待处理：给下游单据的负责人（生产单 = 跟单员，采购单 = 采购员，算料单 = 制单人）
const homeUpstream = () => upstreamPending().filter((d) => homeBoss() || (production(d.no) ? d.merch === db.me : d.no.startsWith('CG') ? d.buyer === db.me : creatorOf(d) === me()?.name))
const upKind = (d) => (production(d.no) ? [prodKind(d), prodKindOf(d).to + '/'] : d.no.startsWith('CG') ? ['采购单', '/purchases/'] : ['算料单', '/mrp/'])

TODOS.push(() => {
  const a = homeCanAudit
  const risk = db.productions.filter((p) => prodLive(p) && homeMineOr(p.merch)).map(prodRisk)
  const my = homeBoss() ? '' : '我跟单的'
  return [
    ...[['order', db.orders, '订单', '待审核的需求订单', '/orders?s=待审核', 'file'], ['change', db.changes, '订单', '待审核的变更', '/changes', 'diff'],
      ['purchase', db.purchases, '算料、采购单', '待审核的采购单', '/purchases?s=待审核', 'cart'],
      ...Object.entries(PROD_KINDS).map(([k, K]) => ['production', db.productions.filter((p) => prodKind(p) === k), '排单、生产单', '待审核的' + k, K.to + '?s=待审核', 'factory'])]
      .map(([k, list, m, t, to, i]) => { const n = myAudits(k, list).length; return (a(m) || n > 0) && { t, n, to, i } }),
    a('款式、资料包') && { t: '资料包草稿待生效', n: db.techpacks.filter((t) => t.status === '草稿').length, to: '/styles?tp=草稿', i: 'shirt' },
    a('物料出入库、盘点、库存') && { t: '物料入库单待审核', n: db.stockDocs.filter((d) => d.status === '草稿' && STOCK_TYPES[d.type] === 'RK').length, to: '/stock-in?tab=docs&s=草稿', i: 'inbox' },
    a('物料出入库、盘点、库存') && { t: '物料出库单待审核', n: db.stockDocs.filter((d) => d.status === '草稿' && STOCK_TYPES[d.type] === 'CK').length, to: '/stock-out?s=草稿', i: 'swap' },
    a('物料出入库、盘点、库存') && { t: '物料盘点单待审核', n: db.stockTakes.filter((d) => d.status === '待审核').length, to: '/stock-takes?s=待审核', i: 'clipboard' },
    a('供应商') && { t: '供应商待审批', n: homeSupAudits().length, to: '/suppliers?f=待审批', i: 'store' },
    { t: '供应商新增风险', n: homeSupRisks().length, to: '/suppliers?f=新增风险', i: 'alert', tone: 'red' },
    { t: '已审核还没排单的需求订单', n: db.orders.filter((o) => orderLive(o) && orderSched(o) === 0 && homeMineOr(o.merch)).length, to: '/orders?f=待排单', i: 'file' },
    // 生产风险两种单一起看：点进看板，带上数字卡
    { t: my + '生产单已逾期', n: risk.filter((r) => r.late).length, to: '/board?card=late', i: 'factory', tone: 'red' },
    { t: my + '生产单临期（7 天内交）', n: risk.filter((r) => r.near).length, to: '/board?card=near', i: 'factory', tone: 'amber' },
    { t: my + '生产单节点延期', n: risk.filter((r) => r.node).length, to: '/board?card=node', i: 'factory', tone: 'red' },
    { t: my + '生产单有未关闭的异常', n: risk.filter((r) => r.issue).length, to: '/board?card=issue', i: 'alert', tone: 'amber' },
    { t: '到料逾期', n: arrivals().filter((x) => x.risk === 'late' && homeMineOr(x.p.buyer)).length, to: '/stock-in?f=late', i: 'truck', tone: 'red' },
    { t: '上游订单已变更，待处理', n: homeUpstream().length, to: '/upstream', i: 'diff', tone: 'amber' },
    { t: '供应商证照 30 天内到期', n: expiringCerts().filter((x) => homeMineOr(x.s.buyer)).length, to: '/suppliers?f=证照', i: 'store' },
  ].filter(Boolean)
})

// 首页：概况卡片点进去就是筛好的列表；趋势图、品类环形图是报表看板（13.06，二期），前几周是演示用的历史数
// 排版：上面概况、快捷发起，下面两列一行一对（待办 | 待解决的意见、趋势 | 品类、交期 | 其他提醒），同一行一样高，长的在卡片里滚
const HIST_ORDER = [1180, 860, 1420, 990, 1630, 1210, 1880, 1350, 1560, 2120, 1740]
const HIST_RET = [640, 920, 780, 1150, 1020, 1310, 990, 1460, 1280, 1390, 1610]
const CAT_COLORS = ['#9a6b3e', '#5c856b', '#cfa355', '#7e909a', '#c26b58', '#8d7b68', '#79998a', '#b88655', '#9c958b', '#a87568']
// 按周汇总：最后一周到今天为止；rng 是每周的起止日期（点图跳列表用），lbl 是每周最后一天
const shortMd = (s) => `${+s.slice(5, 7)}/${+s.slice(8, 10)}`
function weekly(rows, dateOf, qtyOf, hist) {
  const weeks = hist.length + 1, out = [...hist, 0]
  const rng = Array.from({ length: weeks }, (_, i) => { const b = addDays(clock.today, -(weeks - 1 - i) * 7); return [addDays(b, -6), b] })
  rows.forEach((r) => { const k = weeks - 1 - Math.floor(diffDays(clock.today, dateOf(r)) / 7); if (k >= 0 && k < weeks) out[k] += qtyOf(r) })
  return { lbl: rng.map(([, b]) => shortMd(b)), rng, out }
}
route('/', {
  setup() {
    const hour = new Date().getHours()
    const hi = hour < 11 ? '上午好' : hour < 14 ? '中午好' : hour < 19 ? '下午好' : '晚上好'
    const d = toD(clock.today)
    const today = `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 星期${'日一二三四五六'[d.getDay()]}`
    const co = computed(() => db.companies.find((c) => c.code === myCompany())?.name || '')
    const who = computed(() => [me()?.dept, me()?.post || me()?.role].filter(Boolean).join(' '))
    const live = computed(() => db.productions.filter((p) => prodLive(p) && homeSees(p)))
    const risk = computed(() => live.value.map((p) => ({ p, r: prodRisk(p) })))
    const kpi = computed(() => {
      const os = db.orders.filter(homeSees), lo = os.filter(orderLive), smp = db.samples || []
      const arr = arrivals().filter((a) => homeSees(a.p))
      return {
        dev: { n: db.styles.filter((s) => s.status !== '停用').length, sub: [['打样中', smpCount('打样中'), '/samples?s=打样中'], ['资料包草稿', db.techpacks.filter((t) => t.status === '草稿').length, '/styles?tp=草稿'], ['样衣', smp.length, '/samples']] },
        ord: { n: sum(lo, orderQty), c: lo.length, sub: [['待审核', os.filter((o) => o.status === '待审核').length, '/orders?s=待审核'], ['待排单', lo.filter((o) => orderSched(o) === 0).length, '/orders?f=待排单'], ['变更待审', db.changes.filter((c) => c.status === '待审核' && os.some((o) => o.no === c.order)).length, '/changes']] },
        prd: { n: sum(live.value, prodQty), c: live.value.length, sub: [['已逾期', risk.value.filter((x) => x.r.late).length, '/board?card=late'], ['节点延期', risk.value.filter((x) => x.r.node).length, '/board?card=node'], ['未关异常', risk.value.filter((x) => x.r.issue).length, '/board?card=issue']] },
        mat: { n: arr.length, sub: [['到料逾期', arr.filter((a) => a.risk === 'late').length, '/stock-in?f=late'], ['采购待审', db.purchases.filter((p) => p.status === '待审核' && homeSees(p)).length, '/purchases?s=待审核'], ['算料待确认', db.mrps.filter((m) => m.status === '草稿' && homeSees(m)).length, '/mrp']] },
      }
    })
    const trend = computed(() => {
      const a = weekly(db.orders.filter((o) => o.status !== '已取消'), (o) => o.date, orderQty, HIST_ORDER)
      const b = weekly(liveReturns(), (r) => r.date, (r) => sum(r.lines, (l) => l.qty), HIST_RET)
      return { labels: a.lbl, rng: a.rng, tips: a.rng.map(([x, y]) => `${shortMd(x)} – ${shortMd(y)}`), series: [{ t: '下单件数', c: '#9a6b3e', v: a.out }, { t: '回货件数', c: '#5c856b', v: b.out }] }
    })
    const cats = computed(() => {
      const acc = {}
      live.value.forEach((p) => { const c = style(p.style)?.cat; acc[c] = (acc[c] || 0) + prodQty(p) - prodRet(p) })
      return Object.entries(acc).filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).map(([code, v], i) => ({ code, t: catOf(code).name, v, c: CAT_COLORS[i % CAT_COLORS.length] }))
    })
    // 待办：具体到单据，按 PRD §6.9.2 的"给谁"出
    const todo = computed(() => {
      const g = [], a = homeCanAudit
      const audit = [
        ...myAudits('order', db.orders).map((o) => ({ t: '需求订单 ' + o.no, sub: `${custOrDash(o)} · ${fq(orderQty(o))} 件 · 交期 ${o.due}${lvSub('order', o)}`, to: '/orders/' + o.no, img: orderStyles(o)[0] })),
        ...myAudits('change', db.changes).map((c) => ({ t: '变更单 ' + c.no, sub: `订单 ${c.order} · ${describeChange(c)}`, to: '/orders/' + c.order + '?tab=changes', img: order(c.order) && orderStyles(order(c.order))[0] })),
        ...myAudits('purchase', db.purchases).map((p) => ({ t: '采购单 ' + p.no, sub: `${supName(p.supplier)} · ${fm(poAmt(p))}${lvSub('purchase', p)}`, to: '/purchases/' + p.no, img: p.lines[0]?.sku })),
        ...myAudits('production', db.productions).map((p) => ({ t: prodKind(p) + ' ' + p.no, sub: `${supName(p.factory)} · ${fq(prodQty(p))} 件${lvSub('production', p)}`, to: prodLink(p), img: p.style })),
        ...(a('款式、资料包') ? db.techpacks.filter((t) => t.status === '草稿').map((t) => ({ t: `资料包 ${t.style} V${t.ver}`, sub: `${styleName(t.style)} · 草稿待生效${t.note ? ' · ' + t.note : ''}`, to: '/styles/' + t.style, img: t.style })) : []),
        ...(a('物料出入库、盘点、库存') ? db.stockDocs.filter((x) => x.status === '草稿').map((x) => ({ t: `${x.type} ${x.no}`, sub: `${whName(x.from || x.to)} · ${x.lines.length} 行 · 待审核过账`, to: stockNoLink(x.no), img: x.lines[0]?.sku })) : []),
        ...(a('物料出入库、盘点、库存') ? db.stockTakes.filter((x) => x.status === '待审核').map((x) => ({ t: `${x.purpose} ${x.no}`, sub: `${whName(x.wh)} · ${x.lines.length} 种物料 · 待审核过账`, to: stockNoLink(x.no), img: x.lines[0]?.sku })) : []),
        ...(a('供应商') ? homeSupAudits().map((s) => (s.status === '准入审批中'
          ? { t: '供应商准入 ' + s.short, sub: `${s.cats.join('、')} · ${userName(s.applyBy)} ${fd(s.applyAt)} 申请 · 企查查${lastRisk(s)?.result || '没查成功'}`, to: '/suppliers/' + s.code }
          : { t: '供应商改动 ' + s.short, sub: `${s.pending.items.map((i) => i.t).join('、')} · ${userName(s.pending.by)} ${fd(s.pending.at)} 提的`, to: '/suppliers/' + s.code })) : []),
      ]
      if (audit.length) g.push({ tag: '待我审核', tone: 'pending', items: audit })
      const mine = risk.value.filter(({ p }) => homeMineOr(p.merch))
      const late = mine.filter((x) => x.r.late || x.r.node).map(({ p, r }) => ({ t: `${p.no} ${styleName(p.style)}`, sub: `${supName(p.factory)} · ${r.late ? '交期 ' + fd(expectDue(p)) + ' 已过' : '节点「' + (curNode(p)?.name || '') + '」延期'}`, to: prodLink(p), img: p.style }))
      if (late.length) g.push({ tag: homeBoss() ? '生产延期' : '我跟单的：延期', tone: 'bad', items: late })
      const arr = arrivals().filter((x) => x.risk === 'late' && homeMineOr(x.p.buyer)).map((x) => ({ t: `${skuName(x.l.sku)} 还差 ${fq(rnd(x.l.qty - x.l.received, 3))} ${unitOf(x.l.sku)}`, sub: `${x.p.no} · ${supName(x.p.supplier)} · 应到 ${fd(x.date)}`, to: '/purchases/' + x.p.no, img: x.l.sku }))
      if (arr.length) g.push({ tag: '到料逾期', tone: 'bad', items: arr })
      const up = homeUpstream().map((x) => ({ t: upKind(x)[0] + ' ' + x.no, sub: `订单 ${x.upstream.order} 变更：${x.upstream.text}`, to: upKind(x)[1] + x.no, img: x.style || x.lines?.[0]?.sku || x.rows?.[0]?.sku }))
      if (up.length) g.push({ tag: '上游订单已变更', tone: 'pending', items: up })
      const sch = db.orders.filter((o) => orderLive(o) && orderSched(o) === 0 && homeMineOr(o.merch)).map((o) => ({ t: '需求订单 ' + o.no, sub: `${custOrDash(o)} · ${fq(orderQty(o))} 件 · 交期 ${o.due}`, to: '/orders/' + o.no, img: orderStyles(o)[0] }))
      if (sch.length) g.push({ tag: '待排单', tone: 'pending', items: sch })
      const iss = mine.flatMap(({ p }) => (p.issues || []).filter((x) => x.status !== '已关闭').map((x) => ({ t: `${x.type}：${x.desc}`, sub: `${p.no} · ${userName(x.by)} 上报于 ${fd(x.at)}`, to: prodLink(p), img: p.style })))
      if (iss.length) g.push({ tag: '异常待处理', tone: 'ring', items: iss })
      const cert = expiringCerts().filter((x) => homeMineOr(x.s.buyer)).map(({ s, c }) => ({ t: `${s.short} ${c.type}`, sub: `${c.until} 到期，还剩 ${daysLeft(c.until)} 天 · 负责人 ${userName(s.buyer)}`, to: '/suppliers/' + s.code }))
      if (cert.length) g.push({ tag: '证照将到期', tone: 'pending', items: cert })
      const rk = homeSupRisks().map((s) => ({ t: `${s.short} 企查查新增 ${riskNewItems(s).length} 条风险`, sub: riskNewItems(s).map((x) => RISK_LV[x.level] + ' · ' + x.type).join('、') + ` · ${fd(s.riskNew.at)} 复查 · 点进去看完点「已知悉」`, to: '/suppliers/' + s.code + '?tab=risk' }))
      if (rk.length) g.push({ tag: '供应商新增风险', tone: 'bad', items: rk })
      return g
    })
    const others = computed(() => TODOS.flatMap((f) => f()).filter((x) => x.n > 0))
    // 本周交期：今天起 7 天内要交的需求订单（承诺交期，没有用订单上的交期）和生产单（预计交期），逾期还没交的排在前面
    const week = computed(() => {
      const end = addDays(clock.today, 6)
      const os = db.orders.filter((o) => orderLive(o) && homeSees(o)).map((o) => ({ k: '需求订单', no: o.no, to: '/orders/' + o.no, img: orderStyles(o)[0], sub: `${custOrDash(o)} · ${orderStyles(o).map(styleName).join('、')}`, due: o.commit || o.due, a: orderRet(o), b: orderQty(o) }))
      const ps = live.value.map((p) => ({ k: prodKind(p), no: p.no, to: prodLink(p), img: p.style, sub: `${styleName(p.style)} · ${supName(p.factory)}`, due: expectDue(p), a: prodRet(p), b: prodQty(p) }))
      return [...os, ...ps].filter((x) => x.due && x.due <= end).sort((a, b) => a.due.localeCompare(b.due)).slice(0, 8)
    })
    // 原型批注里还没解决的，按页面分组：最近有新意见的页排前面
    const fbOpen = computed(() => {
      const m = new Map()
      fb.items.filter((x) => !x.done).forEach((x) => { if (!m.has(x.path)) m.set(x.path, { path: x.path, title: x.title, i: navItem(x.mod)?.i || 'file', items: [] }); m.get(x.path).items.push(x) })
      return [...m.values()].map((g) => ({ ...g, items: g.items.slice().reverse() })).sort((a, b) => b.items[0].at.localeCompare(a.items[0].at))
    })
    const fbOpenN = computed(() => sum(fbOpen.value, (g) => g.items.length))
    const fbDoneN = computed(() => fb.items.filter((x) => x.done).length)
    const pickWeek = (i) => go(`/orders?from=${trend.value.rng[i][0]}&to=${trend.value.rng[i][1]}`)
    const quick = [['新建需求订单', 'file', '/orders?new=1'], ['新建款式', 'shirt', '/styles?new=1'], ['算料', 'sigma', '/mrp'], ['待到料', 'truck', '/stock-in'], ['生产看板', 'grid', '/board'], ['库存', 'package', '/stock'], ['手机报工', 'phone', '/mobile'], ['批注汇总', 'list', '/feedback']]
    const inboxTab = ref('全部')
    const inboxTabs = computed(() => [['全部', '全部', todo.value.reduce((a, g) => a + g.items.length, 0)], ...todo.value.map((g) => [g.tag, g.tag, g.items.length])])
    const shownTodo = computed(() => (inboxTab.value === '全部' ? todo.value.flatMap((g) => g.items.slice(0, 4).map((x) => ({ ...x, tag: g.tag, tone: g.tone }))) : (todo.value.find((g) => g.tag === inboxTab.value)?.items || []).map((x) => ({ ...x, tag: inboxTab.value }))))
    return { hi, today, co, who, kpi, trend, cats, todo, others, week, quick, inboxTab, inboxTabs, shownTodo, fbOpen, fbOpenN, fbDoneN, pickWeek, fbGoto, fbResolve, fmtStamp }
  },
  template: `<div>
    <Head t="首页"><template #title><span class="head-sub">{{ today }}</span></template>
      <a class="btn" href="#/styles"><Ico n="shirt" :s="14"/>款式库</a><a class="btn pri" href="#/orders?new=1"><Ico n="plus" :s="14"/>新建订单</a></Head>
    <div class="dash-wrap">
      <section class="pulse" data-c="首页：核心指标">
        <div class="pulse-cell" data-c="首页：开发概况">
          <div class="pulse-top"><span>开发款式</span><Ico n="scissors" :s="14"/></div>
          <a class="pulse-hero" href="#/styles"><b>{{ kpi.dev.n }}</b><small>款在售在开发</small></a>
          <div class="pulse-subs"><a v-for="x in kpi.dev.sub" :key="x[0]" :href="'#' + x[2]"><span>{{ x[0] }}</span><b>{{ x[1] }}</b></a></div>
        </div>
        <div class="pulse-cell" data-c="首页：订单概况">
          <div class="pulse-top"><span>在手订单</span><Ico n="file" :s="14"/></div>
          <a class="pulse-hero" href="#/orders"><b>{{ fq(kpi.ord.n) }}</b><small>件 · {{ kpi.ord.c }} 单</small></a>
          <div class="pulse-subs"><a v-for="x in kpi.ord.sub" :key="x[0]" :href="'#' + x[2]"><span>{{ x[0] }}</span><b :class="{ amber: x[1] }">{{ x[1] }}</b></a></div>
        </div>
        <div class="pulse-cell" data-c="首页：生产概况">
          <div class="pulse-top"><span>在制生产</span><Ico n="factory" :s="14"/></div>
          <a class="pulse-hero" href="#/board"><b>{{ fq(kpi.prd.n) }}</b><small>件 · {{ kpi.prd.c }} 单</small></a>
          <div class="pulse-subs"><a v-for="x in kpi.prd.sub" :key="x[0]" :href="'#' + x[2]"><span>{{ x[0] }}</span><b :class="{ red: x[1] }">{{ x[1] }}</b></a></div>
        </div>
        <div class="pulse-cell" data-c="首页：物料概况">
          <div class="pulse-top"><span>在途物料</span><Ico n="truck" :s="14"/></div>
          <a class="pulse-hero" href="#/stock-in"><b>{{ kpi.mat.n }}</b><small>行在途</small></a>
          <div class="pulse-subs"><a v-for="x in kpi.mat.sub" :key="x[0]" :href="'#' + x[2]"><span>{{ x[0] }}</span><b :class="{ red: x[0] === '到料逾期' && x[1], amber: x[0] !== '到料逾期' && x[1] }">{{ x[1] }}</b></a></div>
        </div>
      </section>

      <nav class="quick dash-quick" data-c="快捷入口" aria-label="快捷发起"><a v-for="q in quick" :key="q[0]" :href="'#' + q[2]"><Ico :n="q[1]" :s="15"/><span class="ell">{{ q[0] }}</span></a></nav>

      <div class="dash-grid">
        <section class="panel cap" data-c="待办">
          <h3>待处理事项<span class="acts dim" style="font-size:12px">{{ shownTodo.length }} 项</span></h3>
          <div v-if="todo.length > 1" class="inbox-bar"><Seg v-model="inboxTab" :items="inboxTabs"/></div>
          <div class="pb">
            <div v-if="shownTodo.length" class="inbox-list">
              <a v-for="x in shownTodo" :key="x.to + x.t" class="todo-i" :href="'#' + x.to">
                <Thumb v-if="x.img" :code="x.img" :s="36"/>
                <div class="col"><span class="ell" style="font-weight:500">{{ x.t }}</span><small class="ell">{{ x.sub }}</small></div>
                <St v-if="inboxTab === '全部'" :s="x.tone === 'pending' ? '待处理' : x.tone === 'bad' ? '不合格' : '已审核'" :t="x.tag"/>
                <Ico n="right" :s="14" class="go-arr"/>
              </a>
            </div>
            <div v-else class="empty">{{ me().name }}暂无待处理单据</div>
          </div>
        </section>

        <section class="panel cap" data-c="首页：待解决的意见">
          <h3>待解决的意见<span class="acts dim" style="font-size:12px">{{ fbOpenN ? fbOpenN + ' 条 · ' + fbOpen.length + ' 个页面' : '' }}<a href="#/feedback">汇总</a></span></h3>
          <div class="pb">
            <div v-for="g in fbOpen" :key="g.path" class="fbq-g">
              <button type="button" class="fbq-page" title="去这一页" @click="fbGoto(g.items[0])"><Ico :n="g.i" :s="14"/><span class="ell">{{ g.title }}</span><i>{{ g.items.length }}</i></button>
              <div v-for="x in g.items" :key="x.id" class="fbq-i" role="button" tabindex="0" @click="fbGoto(x)" @keydown.enter="fbGoto(x)">
                <p>{{ x.text }}</p>
                <small><template v-if="x.anchor">{{ x.anchor }} · </template>{{ x.author || '未署名' }} {{ fmtStamp(x.at) }}</small>
                <button type="button" class="btn sm" title="改好了，标成已解决" @click.stop="fbResolve(x)"><Ico n="check" :s="13"/>解决</button>
              </div>
            </div>
            <div v-if="!fbOpenN" class="empty">{{ fbDoneN ? '意见都解决了（' + fbDoneN + ' 条）' : '还没有意见：在任意页面按 C，或点左下角「批注」' }}</div>
          </div>
        </section>

        <section class="panel" data-c="首页：下单与回货趋势">
          <h3>下单与回货<span class="acts dim" style="font-size:12px">近 12 周 · 件</span></h3>
          <div class="pb"><AreaChart :series="trend.series" :labels="trend.labels" :tips="trend.tips" :h="210" hint="点一下看这周下的订单" @pick="pickWeek"/></div>
        </section>

        <section class="panel" data-c="首页：在制品类">
          <h3>在制品类<span class="acts dim" style="font-size:12px">未回货件数</span></h3>
          <div class="pb dash-center"><Donut v-if="cats.length" :items="cats" unit=" 件" label="件在制" :s="150" hint="点一下在看板里看这个品类的生产单" @pick="x => go('/board?cat=' + x.code)"/><div v-else class="empty">暂无在制生产单</div></div>
        </section>

        <section class="panel" data-c="本周交期">
          <h3>近期交期<span class="acts dim" style="font-size:12px">7 天内及逾期</span></h3>
          <div class="pb" style="padding-top:6px;padding-bottom:6px">
            <div v-if="week.length" class="inbox-list">
              <a v-for="x in week" :key="x.no" class="todo-i" :href="'#' + x.to">
                <Thumb :code="x.img" :s="36"/>
                <div class="col"><span class="ell"><span class="pill" style="margin-right:6px">{{ x.k }}</span><span class="code" style="font-weight:500">{{ x.no }}</span></span><small class="ell">{{ x.sub }}</small></div>
                <Prog :a="x.a" :b="x.b"/>
                <span style="min-width:68px;text-align:right"><Days :d="x.due" :warn="7"/></span>
              </a>
            </div>
            <div v-else class="empty">近 7 天无待交付单据</div>
          </div>
        </section>

        <section class="panel" data-c="首页：其他提醒">
          <h3>其他提醒<span class="acts dim" style="font-size:12px">各模块要处理的数</span></h3>
          <div class="pb">
            <div v-if="others.length" class="chips"><a v-for="x in others" :key="x.t" class="chip" :href="'#' + x.to">{{ x.t }}<b :class="x.tone">{{ x.n }}</b></a></div>
            <div v-else class="empty">没有别的要处理</div>
          </div>
        </section>
      </div>
    </div>
  </div>`,
}, { mod: 'home', t: '首页' })

/* ---------- 上游订单已变更、待处理的下游单据（首页待办点进来） ---------- */
route('/upstream', {
  setup() {
    const rows = computed(() => homeUpstream().map((d) => ({ d, kind: upKind(d)[0], to: upKind(d)[1] + d.no, who: production(d.no) ? userName(d.merch) : d.no.startsWith('CG') ? userName(d.buyer) : creatorOf(d) || '—' })))
    return { rows }
  },
  template: `<div>
    <Head t="上游订单已变更，待处理" :back="['首页', '/']"/>
    <div class="page" data-c="上游变更待处理">
      <Tbl :rows="rows" link @row="x => go(x.to)" empty="暂无待处理的上游变更" :cols="[
        { k: 'kind', t: '单据' }, { k: 'no', t: '单号', c: 'code', f: x => x.d.no }, { k: 'order', t: '上游订单', c: 'code', f: x => x.d.upstream.order },
        { k: 'at', t: '变更时间', f: x => fd(x.d.upstream.at) }, { k: 'text', t: '变更内容', c: 'wrap', f: x => x.d.upstream.text }, { k: 'who', t: '负责人' }, { k: 'act', t: '' }]">
        <template #act="{ r }"><button class="btn sm" @click.stop="r.d.upstream.done = true; toast(r.d.no + ' 标成已处理')">已处理</button></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'home', t: '上游变更待处理' })

/* ---------- 款式的业务规则（PRD §6.2） ---------- */
const seasonText = (x) => x.year + ' ' + (db.seasons.find((z) => z.code === x.season)?.name || x.season)
const sgName = (id) => db.sizeGroups.find((g) => g.id === id)?.name || id
const styleUpdated = (s) => s.log?.[s.log.length - 1]?.at || ''
const styleOrders = (code) => db.orders.filter((o) => o.lines.some((l) => l.style === code))
// 列表按资料包筛：已生效 / 草稿（含已生效款的修订草稿）/ 无
const tpHas = (code, k) => (k === '无' ? !tpsOf(code).length : tpsOf(code).some((t) => t.status === k))
// 编辑款式能改的字段：款号保存后不能改；尺码组单独管（有订单后不能换）；颜色在"颜色尺码"页签加
const STYLE_FIELDS = [
  ['name', '名称'], ['alias', '花名'], ['brand', '品牌'], ['cat', '品类', (v) => catOf(v).name], ['year', '年份'], ['season', '季节', (v) => db.seasons.find((z) => z.code === v)?.name || v],
  ['wave', '波段'], ['tag', '风格'], ['series', '系列'], ['gender', '性别'], ['designer', '设计师', userName], ['patterner', '版师', userName],
  ['source', '来源'], ...(TRADE ? [['customer', '客户', custName], ['custNo', '客户款号']] : []), ['mainMat', '主力面料', (v) => mat(v)?.name || v],
  ['product', '品名'], ['comp', '成分'], ['standard', '执行标准'], ['safety', '安全类别'], ['tagPrice', '吊牌价', fm], ['cost', '参考成本价', fm],
  ['factory', '默认加工厂', supName], ['launch', '上市日期'], ['note', '备注'],
]
function updateStyle(s, f) {
  if (!f.name?.trim()) fail('款式名称要填')
  if (!f.brand || !f.cat || !f.year || !f.season || !f.source) fail('品牌、品类、年份、季节、来源都要填')
  const rows = []
  if (f.sizeGroup !== s.sizeGroup) {
    const used = styleOrders(s.code)
    if (used.length) fail('已经有订单用这个款，尺码组不能换', used.map((o) => `${o.no}（${o.status}）`))
    rows.push({ t: '尺码组', from: sgName(s.sizeGroup), to: sgName(f.sizeGroup) + '（SKU 按新尺码重新生成）' })
  }
  rows.push(...fieldRows(s, f, STYLE_FIELDS, ['cost']))
  applyFields(s, f, STYLE_FIELDS)
  if (!rows.length) fail('没有改动')
  if (f.sizeGroup !== s.sizeGroup) {
    s.sizeGroup = f.sizeGroup
    s.skus = s.colors.flatMap((c) => sizesOf(f.sizeGroup).map((z) => ({ code: `${s.code}-${c}-${z}`, color: c, size: z, status: '启用' })))
  }
  actDiff(s, '修改', rows)
}
// 款式 SKU：条码、单独的吊牌价（大码加价）、启用 / 停用
function setStyleSku(s, k, f) {
  if (f.barcode) {
    const dup = db.styles.flatMap((x) => x.skus).find((x) => x !== k && x.barcode === f.barcode)
    if (dup) fail(`条码 ${f.barcode} 已经用在 ${dup.code} 上`)
  }
  if (f.tagPrice != null && !(f.tagPrice >= 0)) fail('吊牌价不对')
  const ch = applyFields(k, f, [['barcode', '条码'], ['tagPrice', '吊牌价', fm], ['status', '状态']])
  if (ch.length) act(s, `${k.code} ${ch.join('；')}`)
}
// 被订单用过的 SKU 不能删，只能停用（PRD §6.2 规则 2、验收 2）
function removeStyleSku(s, k) {
  const used = db.orders.filter((o) => o.lines.some((l) => l.style === s.code && l.color === k.color && l.size === k.size))
  if (used.length) fail(`${k.code} 已有订单使用，只能停用`, used.map((o) => `${o.no}（${o.status}）`))
  s.skus.splice(s.skus.indexOf(k), 1)
  if (!s.skus.some((x) => x.color === k.color)) s.colors.splice(s.colors.indexOf(k.color), 1)
  act(s, '删除 SKU ' + k.code)
}
// 下单前查 SKU：删掉的、停用的颜色尺码不能再下订单；能下返回 ''
const skuBlocked = (st, c, z) => { const k = style(st)?.skus.find((x) => x.color === c && x.size === z); return !k ? `${st}-${c}-${z} 已删除` : k.status === '停用' ? `${k.code} 已停用` : '' }
function addStyleColors(s, cs) {
  if (!cs.length) fail('先选要加的颜色')
  const n0 = s.skus.length
  cs.forEach((c) => addStyleColor(s, c, true))
  act(s, `加颜色 ${cs.map(colorName).join('、')}，新增 ${s.skus.length - n0} 个 SKU`)
}
// 只有草稿、没下过订单的款能删（PRD §5.6），它的资料包一起删
function removeStyle(s) {
  if (s.status !== '草稿') fail(`${s.code} 是「${s.status}」，只有草稿款能删；不用了请停用`)
  const used = styleOrders(s.code)
  if (used.length) fail('已经有订单用这个款，不能删', used.map((o) => o.no))
  if (s.sample) fail(`${s.code} 是样衣 ${s.sample} 转大货来的，不能删`)
  db.techpacks.filter((t) => t.style === s.code).forEach((t) => db.techpacks.splice(db.techpacks.indexOf(t), 1))
  db.styles.splice(db.styles.indexOf(s), 1)
}
// 拖动排序（BOM 行、工序行）
function moveItem(list, from, to) {
  if (from == null || to == null || from === to) return
  const [x] = list.splice(from, 1)
  list.splice(to, 0, x)
}

/* ---------- 款式列表 ---------- */
SEARCH.push(() => db.styles.map((s) => ({ t: s.code + ' ' + s.name + (s.alias ? ' ' + s.alias : ''), sub: '款式', to: '/styles/' + s.code, i: 'shirt' })))
// 导出：比列表多，款式档案的字段都带上
const STYLE_XCOLS = [
  { t: '款号', c: 'code', k: 'code' }, { t: '款式名称', k: 'name' }, { t: '花名', k: 'alias' }, { t: '大类', f: (x) => (x.big ? x.big + ' ' + (bigCatOf(x.big)?.name || '') : '') }, { t: '品牌', k: 'brand' }, { t: '品类', f: (x) => catOf(x.cat).name },
  { t: '年份', k: 'year' }, { t: '季节', f: (x) => db.seasons.find((z) => z.code === x.season)?.name }, { t: '波段', k: 'wave' }, { t: '风格', k: 'tag' }, { t: '系列', k: 'series' }, { t: '性别', k: 'gender' },
  { t: '来源', k: 'source' }, ...(TRADE ? [{ t: '客户', f: (x) => (x.customer ? custName(x.customer) : '') }, { t: '客户款号', c: 'code', k: 'custNo' }] : []),
  { t: '颜色', f: (x) => x.colors.map(colorName).join(',') }, { t: '尺码组', f: (x) => sgName(x.sizeGroup) }, { t: 'SKU 数', f: (x) => x.skus.length },
  { t: '资料包', f: (x) => (activeTp(x.code) ? 'V' + activeTp(x.code).ver + ' 已生效' : tpState(x.code)) }, { t: '吊牌价', k: 'tagPrice' }, { t: '参考成本价', k: 'cost' },
  { t: '默认加工厂', f: (x) => (x.factory ? supName(x.factory) : '') }, { t: '设计师', f: (x) => (x.designer ? userName(x.designer) : '') }, { t: '版师', f: (x) => (x.patterner ? userName(x.patterner) : '') },
  { t: '主力面料', c: 'code', k: 'mainMat' }, { t: '品名', k: 'product' }, { t: '成分', k: 'comp' }, { t: '执行标准', k: 'standard' }, { t: '安全类别', k: 'safety' },
  { t: '上市日期', k: 'launch' }, { t: '状态', k: 'status' }, { t: '更新时间', f: styleUpdated }, { t: '备注', k: 'note' },
]

route('/styles', {
  setup() {
    const s = reactive({ tab: '全部', q: '', view: 'grid', tp: nav.query.tp || '', cat: '', ys: '', wave: '', designer: '', from: '', to: '', add: !!nav.query.new, imp: false, picked: [] })
    const counts = computed(() => Object.fromEntries(['草稿', '启用', '停用'].map((k) => [k, db.styles.filter((x) => x.status === k).length])))
    const tabs = computed(() => [['全部', '全部', db.styles.length], ...['启用', '草稿', '停用'].map((k) => [k, k, counts.value[k]])])
    const yss = computed(() => [...new Set(db.styles.map((x) => x.year + '|' + x.season))].sort((a, b) => b.localeCompare(a)))
    const ysText = (k) => { const [y, c] = k.split('|'); return seasonText({ year: y, season: c }) }
    const designers = computed(() => [...new Set(db.styles.map((x) => x.designer).filter(Boolean))])
    const rows = computed(() => db.styles.filter((x) => (s.tab === '全部' || x.status === s.tab) && (!s.tp || tpHas(x.code, s.tp))
      && (!s.cat || x.cat === s.cat) && (!s.ys || x.year + '|' + x.season === s.ys) && (!s.wave || x.wave === s.wave) && (!s.designer || x.designer === s.designer)
      && (!s.from || styleUpdated(x) >= s.from) && (!s.to || styleUpdated(x) <= s.to)
      && matchQ(s.q, x.code, x.name, x.alias || '', TRADE ? x.custNo || '' : '', x.product || '')).slice().reverse())
    const any = computed(() => s.tp || s.cat || s.ys || s.wave || s.designer || s.from || s.to)
    const clear = () => Object.assign(s, { tp: '', cat: '', ys: '', wave: '', designer: '', from: '', to: '' })
    const picks = computed(() => rows.value.filter((x) => s.picked.includes(x.code)))
    const batch = async (a) => { const ok = await batchAct('style', picks.value, a); s.picked = s.picked.filter((k) => !ok.includes(k)) }
    return { s, tabs, rows, yss, ysText, designers, any, clear, STYLE_XCOLS, picks, batch }
  },
  template: `<div>
    <Head t="款式">
      <button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入</button>
      <ExportBtn :rows="rows" :cols="STYLE_XCOLS" name="款式"/>
      <button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建款式</button>
    </Head>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.tab" :items="tabs"/>
      <span class="flt" data-c="筛选：品类、季节、波段、设计师、资料包、更新日期">
        <Pick class="sel" v-model="s.cat"><option value="">全部品类</option><option v-for="c in db.categories" :key="c.code" :value="c.code">{{ c.name }}{{ c.status === '停用' ? '（停用）' : '' }}</option></Pick>
        <Pick class="sel" v-model="s.ys"><option value="">全部季节</option><option v-for="k in yss" :key="k" :value="k">{{ ysText(k) }}</option></Pick>
        <Pick class="sel" v-model="s.wave"><option value="">全部波段</option><option v-for="x in db.dicts.波段" :key="x">{{ x }}</option></Pick>
        <Pick class="sel" v-model="s.designer"><option value="">全部设计师</option><option v-for="u in designers" :key="u" :value="u">{{ userName(u) }}</option></Pick>
        <Pick class="sel" v-model="s.tp"><option value="">资料包：全部</option><option value="已生效">资料包已生效</option><option value="草稿">有草稿待生效</option><option value="无">还没有资料包</option></Pick>
        <DateRange v-model:from="s.from" v-model:to="s.to" prefix="更新" compact/>
        <button v-if="any" class="btn ghost sm" @click="clear">清空</button>
      </span>
      <span class="sp"></span>
      <Seg v-model="s.view" :items="[['grid', '图卡'], ['list', '列表']]"/>
      <Search v-model="s.q" :ph="TRADE ? '款号、名称、花名、客户款号，多个用空格隔开' : '款号、名称、花名、品名，多个用空格隔开'"/>
    </div>
    <div v-if="s.view === 'grid'" class="cards" data-c="款式图卡">
      <div v-for="x in rows" :key="x.code" class="card" @click="go('/styles/' + x.code)">
        <div class="pic"><Thumb :code="x.code" :s="176"/><St :s="x.status"/></div>
        <div class="meta">
          <div class="row"><span class="code">{{ x.code }}</span><span style="flex:1"></span><span class="dim" style="font-size:12px">{{ fm(x.tagPrice) }}</span></div>
          <div class="ell">{{ x.name }}<span v-if="x.alias" class="dim"> · {{ x.alias }}</span></div>
          <div class="row g4"><Sw v-for="c in x.colors" :key="c" :c="c" :n="false"/><span class="dim" style="margin-left:4px;font-size:12px">{{ seasonText(x) }}</span></div>
        </div>
      </div>
      <div v-if="!rows.length" class="empty" style="grid-column:1/-1">没有符合条件的款式，换个筛选条件试试</div>
    </div>
    <div v-else data-c="款式列表">
      <Tbl :rows="rows" link v-model:picked="s.picked" @row="x => go('/styles/' + x.code)" empty="没有符合条件的款式，换个筛选条件试试" :cols="[
        { k: 'code', t: '款号' }, { k: 'name', t: '名称', f: x => x.alias ? x.name + '（' + x.alias + '）' : x.name }, { k: 'cat', t: '品类', f: x => catOf(x.cat).name },
        { k: 'season', t: '年份季节', f: x => seasonText(x) + ' · ' + (x.wave || '—') },
        { k: 'cs', t: '颜色 × 尺码' }, { k: 'tp', t: '资料包' }, { k: 'tagPrice', t: '吊牌价', c: 'num', f: x => fm(x.tagPrice) },
        { k: 'designer', t: '设计师', f: x => userName(x.designer) }, { k: 'status', t: '状态' }, { k: 'upd', t: '更新时间', f: x => fd(styleUpdated(x)) }]">
        <template #code="{ r }"><span class="row"><Thumb :code="r.code" :s="26"/><span class="code">{{ r.code }}</span></span></template>
        <template #cs="{ r }"><span class="row g4"><Sw v-for="c in r.colors" :key="c" :c="c" :n="false"/><span class="dim">{{ r.colors.length }} × {{ sizesOf(r.sizeGroup).length }}</span></span></template>
        <template #tp="{ r }"><span class="row g4"><St :s="tpState(r.code)" :t="activeTp(r.code) ? 'V' + activeTp(r.code).ver : tpState(r.code)"/><span v-if="activeTp(r.code) && tpHas(r.code, '草稿')" class="dim" style="font-size:12px">有修订草稿</span></span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
      <BatchBar :n="picks.length" @clear="s.picked = []"><button class="btn sm" @click="batch('停用')">停用</button><button class="btn sm" @click="batch('恢复')">恢复</button><ExportBtn class="sm" :rows="picks" :cols="STYLE_XCOLS" name="款式" label="导出选中"/></BatchBar>
    </div>
    <StyleForm v-if="s.add" @close="s.add = false"/>
    <ImportBox v-if="s.imp" kind="styles" @close="s.imp = false"/>
  </div>`,
}, { mod: 'styles', t: '款式' })

/* ---------- 款式导入（PRD §10）：一行一个款，颜色用逗号隔开，尺码组填名称；有一行不对整批不导 ---------- */
IMPORTS.styles = {
  title: '导入款式', name: '款式',
  note: '一行一个款，颜色用逗号隔开，按「颜色 × 尺码组」自动生成 SKU。导进来是草稿：资料包建好、核对后再启用。',
  cols: [
    { k: 'code', t: '款号', tip: '不填按规则自动编（大类 + 导入当天 yyMMdd + 设计师编号 + 2 位流水，如 FS2611080701），设计师要有编号；领猫里的老款号照填，系统查重', ex: '27CF009', text: true, w: 14 },
    { k: 'name', t: '款式名称', req: true, ex: '双排扣短风衣', w: 18 },
    { k: 'alias', t: '花名', tip: '选填：款式的俗称，工艺单表头上有', ex: '小短风', w: 12 },
    { k: 'big', t: '大类', tip: '款号头两位：编码或名称（TS 通勤裤、FS 非通勤裤、XZ 鞋子、BH 百货），要是启用的；不填 = 第一个启用的大类', opts: () => liveBigCats().map((x) => x.code), ex: 'FS', w: 8 },
    { k: 'cat', t: '品类', req: true, tip: '品类名称或 1 位编码，要是启用的品类', ex: '风衣' },
    { k: 'year', t: '年份', req: true, int: true, tip: '4 位年份', ex: 2027, w: 8 },
    { k: 'season', t: '季节', req: true, tip: '春 / 夏 / 秋 / 冬，或编码 C / X / Q / D', ex: '春', w: 8 },
    { k: 'brand', t: '品牌', req: true, opts: () => db.dicts.品牌, ex: '织间' },
    { k: 'colors', t: '颜色', req: true, tip: '颜色名称或编码，多个用逗号隔开；要是启用的颜色', ex: '卡其,黑色', w: 18 },
    { k: 'sizeGroup', t: '尺码组', req: true, tip: '尺码组名称，要是启用的', opts: () => db.sizeGroups.filter((g) => g.status !== '停用').map((g) => g.name), ex: '女装字母码' },
    { k: 'source', t: '来源', opts: () => db.dicts.款式来源, tip: '不填 = 自主开发', ex: '自主开发' },
    ...(TRADE ? [{ k: 'customer', t: '客户', tip: '客户简称或编码；客户来样时填', ex: '华东某服饰' }, { k: 'custNo', t: '客户款号', text: true, ex: 'HD-F2701' }] : []),
    { k: 'wave', t: '波段', opts: () => db.dicts.波段, ex: '第 1 波' },
    { k: 'tag', t: '风格', opts: () => db.dicts.风格, ex: '通勤' },
    { k: 'series', t: '系列', opts: () => db.dicts.系列 },
    { k: 'gender', t: '性别', opts: () => db.dicts.性别, tip: '不填 = 女', ex: '女', w: 8 },
    { k: 'designer', t: '设计师', tip: '用户姓名；不填 = 导入的人。没填款号时用他的设计师编号编款号', ex: '许然' },
    { k: 'patterner', t: '版师', tip: '用户姓名', ex: '沈冬' },
    { k: 'tagPrice', t: '吊牌价', num: true, tip: '元', ex: 799, w: 8 },
    { k: 'cost', t: '参考成本价', num: true, tip: '元，一期手填', ex: 300, w: 10 },
    { k: 'factory', t: '默认加工厂', tip: '供应商简称或编码，要是合作中的成衣厂、加工厂', ex: '锦程制衣' },
    { k: 'mainMat', t: '主力面料', text: true, tip: '物料编码', ex: 'ML0012' },
    { k: 'product', t: '品名', tip: '吊牌上的品名', ex: '风衣' },
    { k: 'comp', t: '成分', ex: '面料：100% 棉；里料：100% 聚酯纤维', w: 24 },
    { k: 'standard', t: '执行标准', opts: () => db.dicts.执行标准, w: 24 },
    { k: 'safety', t: '安全类别', opts: () => db.dicts.安全类别, ex: 'B 类' },
    { k: 'launch', t: '上市日期', date: true, ex: '2027-03-01' },
    { k: 'note', t: '备注' },
  ],
  example: () => [
    { name: '双排扣短风衣', big: 'FS', cat: '风衣', year: 2027, season: '春', brand: db.dicts.品牌[0], colors: '卡其,黑色', sizeGroup: '女装字母码', source: '自主开发', wave: '第 1 波', tag: '通勤', designer: '许然', patterner: '沈冬', tagPrice: 799, cost: 300, factory: '锦程制衣', mainMat: 'ML0012', product: '风衣', comp: '面料：100% 棉；里料：100% 聚酯纤维', safety: 'B 类', launch: '2027-03-01' },
    { code: '27CK020', name: '高腰阔腿裤', cat: '裤装', year: 2027, season: '春', brand: db.dicts.品牌[0], colors: '黑色,藏青', sizeGroup: '女装字母码', ...ifTrade({ source: '客户来样', customer: db.customers[0]?.short, custNo: 'HD-K2701' }, { source: '改款' }), tagPrice: 399, factory: '华美服装厂' },
  ],
  check(rows, bad) {
    const plan = [], used = new Set(db.styles.map((x) => x.code)), inFile = new Map(), rowOf = new Map()
    const err = (r, c, m) => { r._bad = true; bad(r, c, m) }
    // 手填的款号先查重、占上，自动编号时跳过它们
    rows.forEach((r) => {
      if (!r.code) return
      const c = r.code.trim().toUpperCase()
      if (!/^[A-Z0-9-]{3,20}$/.test(c)) err(r, '款号', `「${r.code}」只能用字母、数字和 -，3-20 位`)
      else if (inFile.has(c)) err(r, '款号', `和第 ${inFile.get(c)} 行的款号重复`)
      else if (used.has(c)) err(r, '款号', `${c} 已经有了`)
      inFile.set(c, r._r)
    })
    rows.forEach((r) => {
      const big = r.big ? liveBigCats().find((x) => x.code === String(r.big).trim().toUpperCase() || x.name === String(r.big).trim()) : liveBigCats()[0]
      if (!big) err(r, '大类', r.big ? `没有启用的大类「${r.big}」，有：${liveBigCats().map((x) => x.code + ' ' + x.name).join('、')}` : '没有启用的大类，先到 基础 › 款号大类 里加')
      const cat = liveCats().find((c) => c.code === r.cat.toUpperCase() || c.name === r.cat)
      if (!cat) err(r, '品类', `没有启用的品类「${r.cat}」，有：${liveCats().map((c) => c.name).join('、')}`)
      const season = db.seasons.find((x) => x.code === r.season.toUpperCase() || x.name === r.season || x.name + '季' === r.season)
      if (!season) err(r, '季节', `「${r.season}」不对，填 春 / 夏 / 秋 / 冬`)
      if (r.year && (r.year < 2000 || r.year > 2099)) err(r, '年份', `「${r.year}」不对，填 4 位年份`)
      const cs = r.colors.split(/[,，、;；\s]+/).filter(Boolean).map((t) => ({ t, c: liveColors().find((x) => x.code === t.toUpperCase() || x.name === t) }))
      cs.filter((x) => !x.c).forEach((x) => err(r, '颜色', `没有启用的颜色「${x.t}」`))
      const colors = [...new Set(cs.filter((x) => x.c).map((x) => x.c.code))]
      if (colors.length < cs.filter((x) => x.c).length) err(r, '颜色', '颜色填重复了')
      const g = db.sizeGroups.find((x) => x.name === r.sizeGroup && x.status !== '停用')
      const customer = r.customer ? db.customers.find((c) => c.status === '启用' && [c.code, c.short, c.name].includes(r.customer)) : null
      if (r.customer && !customer) err(r, '客户', `没有启用的客户「${r.customer}」`)
      const user = (k, t) => { if (!r[k]) return ''; const u = db.users.find((x) => x.name === r[k]); if (!u) err(r, t, `没有用户「${r[k]}」`); return u?.id || '' }
      const designer = user('designer', '设计师'), patterner = user('patterner', '版师')
      const factory = r.factory ? db.suppliers.find((x) => isFactory(x) && [x.code, x.short, x.name].includes(r.factory)) : null
      if (r.factory && !factory) err(r, '默认加工厂', `没有成衣厂、加工厂「${r.factory}」`)
      else if (factory && factory.status !== '合作中') err(r, '默认加工厂', `${factory.short} 现在是「${factory.status}」，不能当默认加工厂`)
      const mm = r.mainMat ? mat(r.mainMat.trim().toUpperCase()) : null
      if (r.mainMat && !mm) err(r, '主力面料', `没有物料 ${r.mainMat}`)
      else if (mm?.status === '停用') err(r, '主力面料', `${mm.code} ${mm.name} 已停用`)
      const dz = db.users.find((u) => u.id === (designer || db.me))
      if (!r.code && dz && !dz.dno) err(r, '款号', `没填款号要自动编，但${r.designer ? '设计师' : '导入的人'} ${dz.name} 还没有设计师编号：去设置 › 用户里给 ${dz.name} 填设计师编号，或者手填款号`)
      if (r._bad) return
      rowOf.set(plan.length, r)
      plan.push({ code: r.code ? r.code.trim().toUpperCase() : '', name: r.name, alias: r.alias || '', big: big.code, cat: cat.code, year: r.year, season: season.code, brand: r.brand, colors, sizeGroup: g.id,
        source: r.source || '自主开发', customer: customer?.code || '', custNo: r.custNo, wave: r.wave, tag: r.tag, series: r.series, gender: r.gender || '女',
        designer: designer || db.me, patterner, tagPrice: r.tagPrice, cost: r.cost, factory: factory?.code || '', mainMat: mm?.code || '',
        product: r.product, comp: r.comp, standard: r.standard, safety: r.safety, launch: r.launch, note: r.note })
    })
    // 没填款号的按规则编（大类 + 今天 + 设计师编号 + 2 位流水）：接着已有的、文件里手填的最大流水往后排，满 99 报错
    const taken = new Set([...used, ...inFile.keys()])
    plan.auto = new Set()
    plan.forEach((f, i) => {
      if (f.code) return
      const prefix = f.big + clock.today.slice(2).replace(/-/g, '') + db.users.find((u) => u.id === f.designer).dno
      const n = Math.max(0, ...[...taken].filter((c) => c.startsWith(prefix) && c.length === prefix.length + 2).map((c) => +c.slice(prefix.length) || 0))
      if (n >= 99) return err(rowOf.get(i), '款号', `${prefix}01-99 今天编满了，这一行要手填款号`)
      f.code = prefix + String(n + 1).padStart(2, '0')
      taken.add(f.code)
      plan.auto.add(f.code)
    })
    return plan
  },
  save(plan) {
    const made = plan.map((f) => { const s = createStyle(f); if (plan.auto?.has(s.code)) lockDno(s.designer); act(s, 'Excel 导入'); return s })
    return { text: `建了 ${made.length} 个款式草稿、${sum(made, (s) => s.skus.length)} 个 SKU：${made.slice(0, 6).map((s) => s.code).join('、')}${made.length > 6 ? ' 等' : ''}。建好资料包后再启用`, to: made.length === 1 ? '/styles/' + made[0].code : '/styles' }
  },
}

/* ---------- 新建 / 编辑款式（同一张表单；款号保存后不能改，尺码组有订单后不能换） ---------- */
// 设计师默认：自己有设计师编号就是自己，不然取第一个有编号的人（款号要用编号）
const defaultDesigner = () => (me()?.dno ? db.me : db.users.find((u) => u.dno)?.id || db.me)
comp('StyleForm', {
  // from：复制成新款（RFC 012 §5.4）时的老款，资料、颜色、图片带过来，尺码组能换、款号重新编
  props: { s: Object, from: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const y = +clock.today.slice(0, 4)
    const keys = [...STYLE_FIELDS.map(([k]) => k), 'sizeGroup']
    const src = p.s || p.from
    const f = reactive(src
      ? { ...Object.fromEntries(keys.map((k) => [k, src[k] ?? (['tagPrice', 'cost'].includes(k) ? null : '')])),
          ...(p.from ? { code: '', big: p.from.big || liveBigCats()[0]?.code || '', colors: p.from.colors.filter((c) => liveColors().some((x) => x.code === c)),
            images: [...(p.from.images || [])], files: [...(p.from.files || [])] } : {}) }
      : { code: '', name: '', alias: '', brand: db.dicts.品牌[0], cat: liveCats()[0]?.code, year: y + 1, season: 'C', wave: db.dicts.波段[0], tag: db.dicts.风格[0], series: '', gender: '女',
          big: liveBigCats()[0]?.code || '', designer: defaultDesigner(), patterner: '', source: '自主开发', customer: '', custNo: '', sizeGroup: db.sizeGroups.find((g) => g.status !== '停用')?.id, colors: [],
          mainMat: '', product: '', comp: '', standard: '', safety: '', tagPrice: null, cost: null, factory: db.suppliers.find((x) => isFactory(x) && x.status === '合作中')?.code || '',
          launch: '', note: '', images: [], files: [] })
    // 自动款号：大类 + 今天 + 设计师编号 + 流水；编不出来（没大类、设计师没编号、当天满了）就提示，手填款号照样能建
    const auto = computed(() => { try { return { code: nextStyleCode(f.big, f.designer) } } catch (e) { return { err: e.message, list: e.list || [] } } })
    const bigs = computed(() => liveBigCats(f.big))
    const sgLock = computed(() => (p.s ? styleOrders(p.s.code).length : 0))
    const years = computed(() => [...new Set([y - 1, y, y + 1, y + 2, +f.year].filter(Boolean))].sort())
    const cats = computed(() => db.categories.filter((c) => c.status !== '停用' || c.code === f.cat))
    const groups = computed(() => db.sizeGroups.filter((g) => g.status !== '停用' || g.id === f.sizeGroup))
    const factories = computed(() => db.suppliers.filter((x) => isFactory(x) && (x.status === '合作中' || x.code === f.factory)))
    const custs = computed(() => db.customers.filter((c) => c.status === '启用' || c.code === f.customer))
    const fabrics = computed(() => db.materials.filter((m) => ['面料', '里料'].includes(m.type) && (m.status === '启用' || m.code === f.mainMat)))
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    function save() {
      if (p.s) { if (run(() => updateStyle(p.s, f))) { emit('close'); toast(p.s.code + ' 已保存') } return }
      const form = { ...f, colors: [...f.colors], images: [...f.images], files: [...f.files] }
      const st = run(() => (p.from ? copyStyle(p.from.code, form) : createStyle(form)))
      if (st) { emit('close'); go('/styles/' + st.code); toast(p.from ? `从 ${p.from.code} 复制出 ${st.code}，生效的资料包带成了草稿` : `${st.code} 已建，生成 ${st.skus.length} 个 SKU`) }
    }
    return { f, auto, bigs, sgLock, years, cats, groups, factories, custs, fabrics, toggle, save }
  },
  template: `<Modal :t="s ? '编辑款式 ' + s.code : from ? '复制成新款（从 ' + from.code + '）' : '新建款式'" :w="780" @close="$emit('close')">
    <div class="form" style="grid-template-columns:1fr 1fr 1fr">
      <h4 class="fh">基本</h4>
      <label class="f">款号<input v-if="s" class="inp code" :value="s.code" disabled title="款号保存后不能改"><input v-else class="inp code" v-model="f.code" :placeholder="auto.code ? '不填自动编：' + auto.code : '编不出来，要手填'" title="可以手填，系统查重；保存后不能改"></label>
      <label class="f">款式名称<input class="inp" v-model="f.name" placeholder="如：女式风衣"></label>
      <label class="f" data-c="花名">花名<input class="inp" v-model="f.alias" placeholder="选填，款式的俗称"></label>
      <label class="f">大类<Pick v-if="!s" class="sel" v-model="f.big"><option v-for="x in bigs" :key="x.code" :value="x.code">{{ x.code }} {{ x.name }}</option></Pick>
        <input v-else class="inp" :value="s.big ? s.big + ' ' + (bigCatOf(s.big)?.name || '') : '—'" disabled title="大类进了款号，保存后不改"></label>
      <label class="f">设计师<Pick class="sel" v-model="f.designer"><option value="">—</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}{{ u.dno ? ' · ' + u.dno : '' }}</option></Pick></label>
      <p v-if="!s && !f.code.trim() && !auto.code" class="f dim" style="margin:0;font-size:12px;align-self:end" data-c="款号规则">
        <span class="amber">{{ auto.err }}<template v-if="auto.list[0]">：<a href="#/settings">{{ auto.list[0] }}</a></template></span></p>
      <span v-else></span>
      <label class="f">品牌<Pick class="sel" v-model="f.brand"><option v-for="x in db.dicts.品牌" :key="x">{{ x }}</option></Pick></label>
      <label class="f">品类<Pick class="sel" v-model="f.cat"><option v-for="c in cats" :key="c.code" :value="c.code">{{ c.path }} / {{ c.name }}{{ c.status === '停用' ? '（停用）' : '' }}</option></Pick></label>
      <label class="f">来源<Pick class="sel" v-model="f.source"><option v-for="x in db.dicts.款式来源" :key="x">{{ x }}</option></Pick></label>
      <label class="f">年份<Pick class="sel" v-model.number="f.year"><option v-for="x in years" :key="x" :value="x">{{ x }}</option></Pick></label>
      <label class="f">季节<Pick class="sel" v-model="f.season"><option v-for="x in db.seasons" :key="x.code" :value="x.code">{{ x.name }}</option></Pick></label>
      <label class="f">波段<Pick class="sel" v-model="f.wave"><option value="">—</option><option v-for="x in db.dicts.波段" :key="x">{{ x }}</option></Pick></label>
      <label class="f">风格<Pick class="sel" v-model="f.tag"><option value="">—</option><option v-for="x in db.dicts.风格" :key="x">{{ x }}</option></Pick></label>
      <label class="f">系列<Pick class="sel" v-model="f.series"><option value="">—</option><option v-for="x in db.dicts.系列" :key="x">{{ x }}</option></Pick></label>
      <label class="f">性别<Pick class="sel" v-model="f.gender"><option value="">—</option><option v-for="x in db.dicts.性别" :key="x">{{ x }}</option></Pick></label>
      <label class="f">版师<Pick class="sel" v-model="f.patterner"><option value="">—</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></Pick></label>
      <template v-if="TRADE"><label class="f">客户<Pick class="sel" v-model="f.customer"><option value="">—</option><option v-for="c in custs" :key="c.code" :value="c.code">{{ c.short }}</option></Pick></label>
        <label class="f">客户款号<input class="inp code" v-model="f.custNo" placeholder="客户来样时填"></label></template>
      <label class="f">主力面料<Pick class="sel" v-model="f.mainMat"><option value="">—</option><option v-for="m in fabrics" :key="m.code" :value="m.code">{{ m.code }} {{ m.name }}</option></Pick></label>
      <h4 class="fh">尺码、价格、生产</h4>
      <label class="f">尺码组<Pick class="sel" v-model="f.sizeGroup" :disabled="!!sgLock"><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }} {{ g.sizes.join(' ') }}</option></Pick>
        <small v-if="sgLock" class="dim">已关联 {{ sgLock }} 张订单，不可更换</small><small v-else-if="from" class="dim">可以换；资料包草稿里新尺码的用量要补</small></label>
      <label class="f">吊牌价<input class="inp num" type="number" v-model.number="f.tagPrice"></label>
      <label class="f">参考成本价<input class="inp num" type="number" v-model.number="f.cost" title="一期手填；资料包生效后另有测算价"></label>
      <label class="f">默认加工厂<Pick class="sel" v-model="f.factory"><option value="">—</option><option v-for="x in factories" :key="x.code" :value="x.code">{{ x.short }}</option></Pick></label>
      <label class="f">上市日期<DatePick v-model="f.launch"/></label>
      <span></span>
      <h4 class="fh">吊牌</h4>
      <label class="f">品名<input class="inp" v-model="f.product" placeholder="吊牌上的品名，如 风衣"></label>
      <label class="f">执行标准<Pick class="sel" v-model="f.standard"><option value="">—</option><option v-for="x in db.dicts.执行标准" :key="x">{{ x }}</option></Pick></label>
      <label class="f">安全类别<Pick class="sel" v-model="f.safety"><option value="">—</option><option v-for="x in db.dicts.安全类别" :key="x">{{ x }}</option></Pick></label>
      <label class="f full">成分<input class="inp" v-model="f.comp" placeholder="如 面料：100% 棉；里料：100% 聚酯纤维"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
      <template v-if="!s">
        <div class="f full">颜色<div class="chips"><button v-for="c in liveColors()" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
        <div class="f full" data-c="款式图片">款式图片<Pics v-model="f.images" :colors="f.colors"/></div>
        <div class="f full" data-c="款式附件">附件（图稿、设计说明）<Pics v-model="f.files" files :s="56" :max="20"/></div>
      </template>
    </div>
    <template #foot><span class="dim" v-if="!s && f.colors.length">{{ f.colors.length }} × {{ sizesOf(f.sizeGroup).length }} = {{ f.colors.length * sizesOf(f.sizeGroup).length }} 个 SKU</span><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ s ? '保存' : '建款式' }}</button></template>
  </Modal>`,
})

/* ---------- 款式详情 ---------- */
route('/styles/:code', {
  props: ['code'],
  setup(p) {
    const s = computed(() => style(p.code))
    const tab = ref(nav.query.tab || 'tp')
    const editing = ref(false)
    const orders = computed(() => styleOrders(p.code))
    async function stop() { if (await ask('停用 ' + p.code, '停用后不能再下新订单；已有订单照常生产、回货。', '停用', true)) run(() => doAction('style', s.value, '停用')) }
    async function del() {
      if (!(await ask('删除款式 ' + p.code, '草稿款删除后找不回来，它的资料包也一起删掉。', '删除', true))) return
      if (run(() => removeStyle(s.value))) { go('/styles'); toast('删除了 ' + p.code) }
    }
    const more = computed(() => s.value ? [
      can('style', s.value, '停用') && { t: '停用', danger: true, fn: stop },
      can('style', s.value, '恢复') && { t: '恢复启用', fn: () => run(() => doAction('style', s.value, '恢复')) },
      s.value.status === '草稿' && { t: '删除', danger: true, fn: del },
      { t: '复制成新款', fn: () => (copying.value = true) },
    ].filter(Boolean) : [])
    const copying = ref(false)
    const unmatched = computed(() => (s.value ? unmatchedColors(s.value) : []))
    const tpc = computed(() => activeTp(p.code))
    const made = computed(() => s.value?.log?.[0])
    return { s, tab, editing, copying, orders, more, unmatched, tpc, made }
  },
  template: `<div v-if="s">
    <Head :t="s.code" :back="['款式', '/styles']">
      <button class="btn" @click="editing = true">编辑</button>
      <button v-if="can('style', s, '启用')" class="btn pri" @click="run(() => doAction('style', s, '启用'))">启用</button>
      <a v-if="s.status === '启用' && activeTp(s.code)" class="btn pri" :href="'#/orders?new=' + s.code"><Ico n="plus" :s="14"/>下订单</a>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ s.name }}<St :s="s.status"/></div>
        <div class="sub"><span>{{ catOf(s.cat).path }} / {{ catOf(s.cat).name }}</span><span>{{ seasonText(s) }} · {{ s.wave || '—' }}</span><span class="row g4"><Sw v-for="c in s.colors" :key="c" :c="c" :n="false"/></span></div>
        <div v-if="unmatched.length" class="banner" data-c="未配料提示"><Ico n="alert"/>新颜色 {{ unmatched.map(colorName).join('、') }} 还没配料，订单不能下这个颜色<button class="btn sm" @click="tab = 'tp'">去资料包</button></div>
        <Tabs v-model="tab" :items="[['tp', '资料包'], ['sku', '颜色尺码', s.skus.length], ['orders', '需求订单', orders.length], ['price', '价格']]"/>
        <TechPack v-if="tab === 'tp'" :code="s.code"/>
        <StylePrice v-if="tab === 'price'" :code="s.code"/>
        <StyleSkus v-if="tab === 'sku'" :s="s"/>
        <section v-if="tab === 'orders'" data-c="款式的订单">
          <Tbl inset flat :rows="orders" link @row="o => go('/orders/' + o.no)" empty="这个款还没下过订单" :cols="[
            { k: 'no', t: '订单号', c: 'code' }, { k: 'customer', t: TRADE ? '客户' : '类型', f: custOrDash },
            { k: 'qty', t: '本款数量', c: 'num', f: o => sum(o.lines.filter(l => l.style === s.code), l => l.qty) },
            { k: 'ver', t: '资料包', f: o => { const v = o.lines.find(l => l.style === s.code)?.ver; return v ? 'V' + v : '—' } },
            { k: 'due', t: DUE_T, f: o => fd(o.due) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </section>
      </div>
      <aside class="aside" data-c="款式属性">
        <Gallery :code="s.code" :colors="s.colors" :owner="s" edit/>
        <dl class="props">
          <dt>花名</dt><dd>{{ s.alias || '—' }}</dd>
          <dt>品牌</dt><dd>{{ s.brand || '—' }}</dd>
          <dt>大类</dt><dd>{{ s.big ? s.big + ' ' + (bigCatOf(s.big)?.name || '') : '—' }}</dd>
          <dt>品类</dt><dd>{{ catOf(s.cat).name }}</dd>
          <dt>年份季节</dt><dd>{{ seasonText(s) }} · {{ s.wave || '—' }}</dd>
          <dt>风格</dt><dd>{{ s.tag || '—' }}</dd>
          <dt>系列</dt><dd>{{ s.series || '—' }}</dd>
          <dt>性别</dt><dd>{{ s.gender || '—' }}</dd>
          <dt>来源</dt><dd>{{ s.source }}<template v-if="TRADE && s.customer"> · {{ custName(s.customer) }}</template></dd>
          <template v-if="TRADE"><dt>客户款号</dt><dd class="code">{{ s.custNo || '—' }}</dd></template>
          <template v-if="s.sample"><dt>样衣</dt><dd><a class="code" :href="'#/samples/' + s.sample">{{ s.sample }}</a></dd></template>
          <dt>设计师</dt><dd>{{ userName(s.designer) }}</dd>
          <dt>版师</dt><dd>{{ userName(s.patterner) }}</dd>
          <dt>尺码组</dt><dd>{{ sgName(s.sizeGroup) }} {{ sizesOf(s.sizeGroup).join(' ') }}</dd>
          <dt>主力面料</dt><dd><a v-if="s.mainMat" :href="'#/materials/' + s.mainMat"><span class="code">{{ s.mainMat }}</span> {{ mat(s.mainMat)?.name }}</a><template v-else>—</template></dd>
          <dt>吊牌价</dt><dd>{{ fm(s.tagPrice) }}</dd>
          <dt>参考成本价</dt><dd>{{ fm(s.cost) }}</dd>
          <template v-if="canSeeCost()"><dt>资料包核价</dt><dd><a v-if="tpc" :href="'#/styles/' + s.code + '?tab=tp&sub=cost&tpv=' + tpc.id">{{ fm(tpCost(tpc).total) + ' · ' + tpName(tpc) }}</a><template v-else>资料包还没生效</template></dd></template>
          <dt>默认加工厂</dt><dd>{{ s.factory ? supName(s.factory) : '—' }}</dd>
          <dt>上市日期</dt><dd>{{ s.launch ? fd(s.launch) : '—' }}</dd>
          <dt>品名</dt><dd>{{ s.product || '—' }}</dd>
          <dt>成分</dt><dd class="wrap">{{ s.comp || '—' }}</dd>
          <dt>执行标准</dt><dd class="wrap">{{ s.standard || '—' }}</dd>
          <dt>安全类别</dt><dd>{{ s.safety || '—' }}</dd>
          <dt>制单</dt><dd>{{ made ? made.who + ' · ' + fd(made.at) : '—' }}</dd>
          <dt>更新</dt><dd>{{ fd(styleUpdated(s)) }}</dd>
          <dt>备注</dt><dd><NoteEdit :d="s"/></dd>
        </dl>
        <DocFiles :doc="s"/>
        <h4>操作记录</h4><Log :items="s.log"/>
      </aside>
    </div>
    <StyleForm v-if="editing" :s="s" @close="editing = false"/>
    <StyleForm v-if="copying" :from="s" @close="copying = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'styles', t: (p) => '款式 ' + p.code })

/* 颜色尺码页签：SKU 的条码、吊牌价、停用 / 删除；加颜色先预览要生成哪些 SKU，再点生成（PRD §6.2 规则 1、2） */
comp('StyleSkus', {
  props: { s: Object },
  setup(p) {
    const pick = ref([])
    const avail = computed(() => liveColors().filter((c) => !p.s.colors.includes(c.code)))
    const preview = computed(() => pick.value.flatMap((c) => sizesOf(p.s.sizeGroup).map((z) => `${p.s.code}-${c}-${z}`)))
    const toggle = (c) => { const i = pick.value.indexOf(c); i >= 0 ? pick.value.splice(i, 1) : pick.value.push(c) }
    function gen() {
      const cs = [...pick.value], n = preview.value.length
      if (run(() => addStyleColors(p.s, cs))) { toast(`加了 ${cs.map(colorName).join('、')}，新增 ${n} 个 SKU`); pick.value = [] }
    }
    const usedQty = (k) => sum(db.orders.filter((o) => o.status !== '已取消').flatMap((o) => o.lines.filter((l) => l.style === p.s.code && l.color === k.color && l.size === k.size)), (l) => l.qty)
    // 改条码、吊牌价：没改成（条码重复……）就把输入框改回去
    function setF(k, field, v, el) { if (!run(() => setStyleSku(p.s, k, { [field]: v })) && el) el.value = k[field] ?? '' }
    async function del(k) { if (await ask('删除 SKU ' + k.code, '删除后这个颜色尺码不能再下单。只有没下过订单的 SKU 能删，下过的只能停用。', '删除', true)) if (run(() => removeStyleSku(p.s, k))) toast('删除了 ' + k.code) }
    return { pick, avail, preview, toggle, gen, usedQty, setF, del }
  },
  template: `<section data-c="颜色尺码">
    <table class="tbl inset flat" data-c="款式 SKU">
      <thead><tr><th>SKU</th><th>颜色</th><th>尺码</th><th>条码</th><th class="num">吊牌价</th><th class="num">已下单</th><th>状态</th><th></th></tr></thead>
      <tbody>
        <tr v-for="k in s.skus" :key="k.code" :class="{ off: k.status === '停用' }">
          <td class="code">{{ k.code }}</td><td><Sw :c="k.color"/></td><td>{{ k.size }}</td>
          <td><input class="inp cell code" style="width:150px" :value="k.barcode || ''" placeholder="手填或导入" @change="setF(k, 'barcode', $event.target.value.trim(), $event.target)"></td>
          <td class="num"><input class="inp cell num" style="width:90px" type="number" :value="k.tagPrice ?? ''" :placeholder="s.tagPrice ?? ''" @change="setF(k, 'tagPrice', $event.target.value === '' ? null : +$event.target.value, $event.target)"></td>
          <td class="num">{{ usedQty(k) || '—' }}</td>
          <td><St :s="k.status || '启用'"/></td>
          <td class="num"><span class="row g4" style="justify-content:flex-end"><button class="btn ghost sm" @click="setF(k, 'status', k.status === '停用' ? '启用' : '停用')">{{ k.status === '停用' ? '启用' : '停用' }}</button><button class="btn ghost sm danger" @click="del(k)">删除</button></span></td>
        </tr>
        <tr v-if="!s.skus.length"><td colspan="8" class="empty">还没有 SKU：在下面选颜色生成</td></tr>
      </tbody>
    </table>
    <div class="sec" data-c="加颜色"><h3>添加颜色</h3>
      <div class="chips"><button v-for="c in avail" :key="c.code" class="chip" :class="{ on: pick.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button><span v-if="!avail.length" class="dim">启用的颜色都加过了</span></div>
      <div v-if="pick.length" class="addprev" data-c="加颜色：预览">
        <div class="dim" style="margin-bottom:6px">{{ pick.map(colorName).join('、') }} × {{ sgName(s.sizeGroup) }}（{{ sizesOf(s.sizeGroup).join(' ') }}），会新增 {{ preview.length }} 个 SKU：</div>
        <div class="chips"><span v-for="x in preview" :key="x" class="pill code">{{ x }}</span></div>
        <div class="row" style="margin-top:10px"><button class="btn pri" @click="gen">生成 {{ preview.length }} 个 SKU</button><button class="btn ghost" @click="pick = []">不加了</button></div>
      </div>
    </div>
  </section>`,
})

/* ---------- 资料包（PRD §6.4，甲方 11-18 改，RFC 011）：版本 + 三个页签 —— 物料清单（BOM、按颜色展开，能从甲方的 BOM 表导入）、工艺单（上传的 Excel）、核价
 * 只有生产 BOM 一种（甲方 11-18 答 Q24：研发不填物料清单，转大货后建标准资料包）
 * 定位到某一版的某个页签：/styles/款号?tab=tp&sub=bom|files|cost&tpv=资料包 id（侧边栏「核价」列表点一行就这样跳过来）
 * 草稿能改，已生效的只读，要改点「修订」出新版本；核价页签要有看成本的权限（设置 › 角色权限「成本（核价）」） ---------- */
const TP_TABS = [['bom', '物料清单'], ['files', '工艺单'], ['cost', '核价']]
comp('TechPack', {
  props: { code: String },
  setup(p) {
    const s = computed(() => style(p.code))
    // 版本新的在前；key = 资料包 id。默认看草稿，没有草稿看生效的
    const tps = computed(() => tpsOf(p.code))
    const pickKey = () => { const l = tps.value; return (l.find((t) => t.status === '草稿') || l.find((t) => t.status === '已生效') || l[0])?.id }
    const key = ref(tps.value.some((t) => t.id === nav.query.tpv) ? nav.query.tpv : pickKey())
    const tp = computed(() => tps.value.find((t) => t.id === key.value))
    watch(tps, (l) => { if (!tp.value && l.length) key.value = pickKey() })
    // 老数据补齐：工艺单、其他费用补空表
    watch(tp, (t) => { if (t) { t.files ||= []; t.costs ||= [] } }, { immediate: true })
    const edit = computed(() => tp.value?.status === '草稿')
    const view = ref(TP_TABS.some(([k]) => k === nav.query.sub) ? nav.query.sub : 'bom')
    const tabs = computed(() => TP_TABS.filter(([k]) => k !== 'cost' || canSeeCost()).map(([k, t]) => [k, t, k === 'bom' ? tp.value.bom.length : k === 'files' ? tp.value.files.filter((f) => f.cat !== '导入的 BOM 表').length : null]))
    const bomView = ref('list'), byColor = ref(s.value.colors[0]), open = ref(null), adding = ref(false), editing = ref(null), importing = ref(false)
    const sizes = computed(() => sizesOf(s.value.sizeGroup))
    const verText = (t) => tpName(t) + ({ 已生效: ' 生效中', 草稿: ' 草稿', 已作废: ' 已作废' }[t.status] || '')
    const useText = (r) => (r.um === '统一' ? fq(r.use) : `${fq(r.uses[sizes.value[0]])}–${fq(r.uses[sizes.value[sizes.value.length - 1]])}`)
    // 一键填写（RFC 012 §5.5）：勾几行，底部填一个数，一起填用量或损耗；分码的行每个码都填这个数
    const picked = ref([]), fillVal = ref(null)
    watch(key, () => { picked.value = [] })
    const togglePick = (r) => { const i = picked.value.indexOf(r); i >= 0 ? picked.value.splice(i, 1) : picked.value.push(r) }
    function fillPicked(what) {
      const v = fillVal.value
      if (v === '' || v == null || !(v >= 0) || (what === '损耗' && v > 100)) return toast(what === '损耗' ? '损耗填 0 到 100' : '先填一个不小于 0 的数')
      picked.value.forEach((r) => { if (what === '损耗') r.loss = v; else if (r.um === '分码') sizes.value.forEach((z) => (r.uses[z] = v)); else r.use = v })
      toast(`${picked.value.length} 行的${what}填成了 ${v}`)
      picked.value = []
    }
    function revise() { const t = run(() => reviseTp(tp.value)); if (t?.ver) { key.value = t.id; toast(`修订出 ${tpName(t)}，物料清单、工艺单、核价都带过来了，改完点「生效」`) } }
    // 没有工艺单也能生效，问一句（RFC 011 §2.1）
    async function publish() {
      if (!tp.value.files.some((f) => f.cat === '工艺单') && !(await ask(`生效 ${tpName(tp.value)}`, '还没有工艺单，发工厂时没有文件可发。', '还是生效'))) { view.value = 'files'; return }
      if (!tp.value.bom.some((r) => bomOwnerOf(r) === '生产') && !(await ask(`生效 ${tpName(tp.value)}`, '辅料（生产填）还没填。生效以后要补得修订。', '还是生效'))) return
      if (run(() => publishTp(tp.value))) toast(`${tpName(tp.value)} 已生效，可以下需求订单了`)
    }
    function create() { const t = createTechpack(p.code, [], null, ''); key.value = t.id; view.value = 'bom'; adding.value = true }
    async function dropDraft() {
      const t = tp.value
      if (!(await ask(`删除草稿 ${tpName(t)}`, '这一版改的内容都不要了，删除后找不回来；已生效的版本不受影响。', '删除', true))) return
      if (run(() => removeTpDraft(t))) { key.value = pickKey(); toast(`删除了草稿 ${tpName(t)}`) }
    }
    const matSkus = (r) => mat(r.mat).skus.filter((k) => k.status !== '停用' || Object.values(r.map || {}).includes(k.code))
    const supsFor = (r) => db.suppliers.filter((x) => x.status === '合作中' || x.code === r.supplier)
    const setPrice = (r, v) => { r.price = v === '' ? null : +v }
    // BOM 图：鼠标指到图上的号或表里的行，两边一起亮，右边显示这行料
    const hl = ref(0)
    const hover = (i) => { if (i != null) hl.value = i }
    const cur = computed(() => { const r = tp.value?.bom[hl.value] || tp.value?.bom[0]; return r && { i: tp.value.bom.indexOf(r), r, sku: rowSku(r, byColor.value) } })
    // 拖动排序：BOM 行（只有草稿能拖）
    const drag = reactive({ list: null, from: null, over: null })
    const dragStart = (list, i) => Object.assign(drag, { list, from: i, over: null })
    const dragOver = (list, i) => { if (drag.list === list) drag.over = i }
    function dropAt(list, i) { if (drag.list === list) moveItem(list, drag.from, i); Object.assign(drag, { list: null, from: null, over: null }); open.value = null }

    // 核价：和侧边栏「核价」列表同一个算法（tpCost）
    const k = computed(() => tp.value && tpCost(tp.value))
    // 成品采购参考价（甲方 11-14）：默认工厂 × 包工包料 在价格档案里的现价，含料；只看看，不算进单件成本，没有就不出这一行
    const cpRef = computed(() => (s.value.factory ? curProcPrice(s.value.factory, s.value.code, '包工包料') : null))
    const costGroups = computed(() => ['面料', '辅料'].map((g) => ({ g, rows: k.value.rows.filter((x) => x.kind === g), sum: g === '面料' ? k.value.fabric : k.value.trim })))
    const ratio = computed(() => (s.value.tagPrice && k.value.total ? rnd(s.value.tagPrice / k.value.total, 2) : null))
    const addCost = () => tp.value.costs.push({ name: '', amt: null, note: '' })
    const setAmt = (c, v) => { c.amt = v === '' ? null : +v }
    // 加工费：填一个数，旁边备注写清是什么价（Q50），空着算 0
    const setLabor = (v) => { tp.value.labor = v === '' ? null : +v }
    const rowThumb = (r) => r.sku || Object.values(r.map || {})[0] || r.mat
    return { s, tps, key, tp, edit, view, tabs, bomView, byColor, open, adding, editing, importing, sizes, verText, useText, revise, publish, create, dropDraft, matSkus, supsFor, setPrice,
      hl, hover, cur, drag, dragStart, dragOver, dropAt, k, cpRef, costGroups, ratio, addCost, setAmt, setLabor, rowThumb, picked, fillVal, togglePick, fillPicked }
  },
  template: `<div>
    <div v-if="!tps.length" class="empty">还没有资料包：生产 BOM 生效后才能下订单
      <p style="margin-top:14px;justify-content:center" class="row" data-c="建资料包"><button class="btn pri" @click="create">建资料包</button></p></div>
    <template v-else-if="tp">
      <div class="row tp-bar" data-c="资料包版本">
        <Seg v-model="key" :items="tps.map(t => [t.id, verText(t)])"/>
        <span style="flex:1"></span>
        <button v-if="edit" class="btn pri" @click="publish">生效</button>
        <button v-if="tp.status === '已生效'" class="btn" title="复制出新版本草稿：物料清单、工艺单、核价一起带过去" @click="revise">修订</button>
        <button v-if="edit" class="btn ghost danger" @click="dropDraft">删除草稿</button>
      </div>
      <div class="tpmeta" data-c="资料包版本信息">
        <St :s="tp.status"/>
        <span v-if="tp.publishedAt">生效：{{ userName(tp.publishedBy) }} · {{ fd(tp.publishedAt) }}</span>
        <span v-else-if="edit" class="dim">还没生效</span>
        <span class="row g4">排版利用率 <input v-if="edit" class="inp num" style="width:64px;height:26px" type="number" v-model.number="tp.util" placeholder="—"><template v-else>{{ tp.util != null && tp.util !== '' ? tp.util : '—' }}</template>%</span>
        <a v-if="canSeeCost()" @click="view = 'cost'">单件成本 {{ fm(k.total) }}</a>
      </div>
      <div v-if="edit" style="margin-bottom:12px"><input class="inp" style="width:100%" v-model="tp.note" :placeholder="tp.ver > 1 ? '本版说明（V2 起必填）：这版改了什么' : '本版说明：这版改了什么'"></div>
      <p v-else-if="tp.note" class="mut" style="margin-bottom:12px">{{ tp.note }}</p>

      <div class="tp-tabs" data-c="资料包页签">
        <Seg v-model="view" :items="tabs"/>
        <span style="flex:1"></span>
        <template v-if="view === 'bom'">
          <Seg v-model="bomView" :items="[['list', 'BOM'], ['color', '按颜色']]"/>
          <button v-if="edit" class="btn" @click="adding = true"><Ico n="plus" :s="14"/>加一行</button>
          <button v-if="edit" class="btn" title="选甲方的 BOM 明细表，认出来的物料加进物料清单" @click="importing = true"><Ico n="upload" :s="14"/>从 BOM 表导入</button>
        </template>
      </div>

      <template v-if="view === 'bom'">
        <div v-if="cur" class="bomtop" data-c="BOM 图">
          <BomFigure :code="s.code" :color="byColor" :rows="tp.bom" :hl="cur.i" @hover="hover"/>
          <div class="matcard">
            <div class="row g4"><button v-for="c in s.colors" :key="c" class="gthumb" :class="{ on: c === byColor }" :title="colorName(c)" @click="byColor = c"><Thumb :code="s.code" :color="c" :s="34"/></button><span class="mut" style="margin-left:6px">{{ colorName(byColor) }}</span></div>
            <div class="row" style="gap:14px;align-items:flex-start"><Thumb :code="cur.sku || cur.r.mat" :s="96"/>
              <div class="col" style="gap:4px;min-width:0"><b style="font-size:15px">{{ cur.i + 1 }} · {{ cur.r.part }}</b><span>{{ cur.sku ? skuName(cur.sku) : mat(cur.r.mat).name }}</span><span v-if="!cur.sku" class="flag red">{{ colorName(byColor) }}还没配料</span><span v-else class="code dim">{{ cur.sku }}</span></div></div>
            <dl class="props"><dt>用量</dt><dd>{{ useText(cur.r) }} {{ mat(cur.r.mat).unit }} · 损耗 {{ cur.r.loss }}%</dd><dt>来源</dt><dd :class="{ amber: cur.r.src === '客供' }">{{ cur.r.src }}</dd>
              <dt>供应商</dt><dd>{{ supName(cur.r.supplier || mat(cur.r.mat).supplier) }}</dd><dt>参考单价</dt><dd>{{ fm(cur.r.price ?? rowRefPrice(cur.r)) }}/{{ mat(cur.r.mat).purUnit }}</dd><template v-if="cur.r.note"><dt>备注</dt><dd>{{ cur.r.note }}</dd></template></dl>
          </div>
        </div>

        <div v-if="bomView === 'list'" data-c="资料包：物料清单">
          <table class="tbl inset flat">
            <thead><tr><th v-if="edit" style="width:40px"></th><th style="width:28px">#</th><th>部位</th><th>物料</th><th>配色</th><th class="num">用量</th><th class="num">损耗</th><th>来源</th><th>供应商</th><th class="num">参考单价</th><th class="num">单件成本</th><th v-if="edit"></th></tr></thead>
            <tbody>
              <template v-for="g in BOM_OWNER_GROUPS" :key="g.owner">
              <tr class="bomgrp"><td :colspan="edit ? 12 : 10"><b>{{ g.title }}</b> <span class="dim">{{ tp.bom.filter((r) => bomOwnerOf(r) === g.owner).length }} 行</span></td></tr>
              <template v-for="(r, i) in tp.bom" :key="g.owner + i"><template v-if="bomOwnerOf(r) === g.owner">
                <tr class="link" :class="{ sel: cur && cur.i === i, dragover: drag.list === tp.bom && drag.over === i }" :draggable="edit" @dragstart="dragStart(tp.bom, i)" @dragover.prevent="dragOver(tp.bom, i)" @drop.prevent="dropAt(tp.bom, i)" @dragend="drag.list = null" @mouseenter="hover(i)" @click="open = open === i ? null : i">
                  <td v-if="edit" class="grip" title="按住拖动，调整顺序；勾上几行可以一起填用量、损耗"><input type="checkbox" data-c="一键填写" :checked="picked.includes(r)" @click.stop="togglePick(r)"> ⋮⋮</td>
                  <td class="dim">{{ i + 1 }}</td><td>{{ r.part }}</td>
                  <td><div class="row"><Thumb :code="rowThumb(r)" :s="30"/>{{ mat(r.mat).name }}</div></td>
                  <td><span v-if="r.mode === '固定'">{{ skuOf(r.sku)?.color ? colorName(skuOf(r.sku).color) : '—' }}{{ skuOf(r.sku)?.spec ? ' ' + skuOf(r.sku).spec : '' }}</span>
                    <span v-else class="row g4"><span class="dim">随款色</span><template v-for="c in s.colors" :key="c"><Sw v-if="r.map[c]" :c="skuOf(r.map[c])?.color" :n="false"/><span v-else class="flag red">缺</span></template></span></td>
                  <td class="num">{{ useText(r) }} <span class="dim">{{ mat(r.mat).unit }}</span></td>
                  <td class="num">{{ r.loss }}%</td>
                  <td><span :class="{ amber: r.src === '客供', dim: r.src === '厂购' }">{{ r.src }}</span></td>
                  <td>{{ r.src === '厂购' ? '—' : supName(r.supplier || mat(r.mat).supplier) }}</td>
                  <td class="num">{{ fm(r.price ?? rowRefPrice(r)) }}<span class="dim">/{{ mat(r.mat).purUnit }}</span><span v-if="r.price != null" class="dim" title="这一行改过单价">*</span></td>
                  <td class="num">{{ r.src === '自购' ? fm(rowCost(r, sizes, baseSizeOf(s.sizeGroup))) : '—' }}</td>
                  <td v-if="edit" class="num"><span class="row g4" style="justify-content:flex-end"><button class="btn ghost sm" @click.stop="editing = r">改</button><button class="btn ghost icon sm" title="删除这一行" @click.stop="tp.bom.splice(i, 1); open = null"><Ico n="trash" :s="13"/></button></span></td>
                </tr>
                <tr v-if="open === i"><td :colspan="edit ? 2 : 1"></td><td :colspan="edit ? 10 : 9" style="padding:10px 0 14px;white-space:normal">
                  <div class="row" style="gap:28px;align-items:flex-start;flex-wrap:wrap">
                    <div v-if="r.mode === '随款色'"><div class="dim" style="font-size:12px;margin-bottom:6px">款色 → 物料颜色</div>
                      <div v-for="c in s.colors" :key="c" class="row" style="height:30px"><Sw :c="c" style="width:56px"/><Ico n="right" :s="12" class="dim"/>
                        <Pick v-if="edit" class="sel" style="height:26px" v-model="r.map[c]"><option :value="undefined">未配</option><option v-for="k in matSkus(r)" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></Pick>
                        <span v-else>{{ r.map[c] ? skuName(r.map[c]) : '未配' }}</span></div></div>
                    <div v-if="r.um === '分码'"><div class="dim" style="font-size:12px;margin-bottom:6px">分码用量（{{ mat(r.mat).unit }}/件）</div>
                      <table class="mx"><tr><th v-for="z in sizes" :key="z">{{ z }}</th></tr><tr><td v-for="z in sizes" :key="z"><input v-if="edit" class="inp cell num" style="width:56px" v-model.number="r.uses[z]"><template v-else>{{ fq(r.uses[z]) }}</template></td></tr></table></div>
                    <div v-if="edit" class="form" style="grid-template-columns:repeat(3,130px)">
                      <label v-if="r.um === '统一'" class="f">用量（{{ mat(r.mat).unit }}）<input class="inp num" v-model.number="r.use"></label>
                      <label class="f">损耗 %<input class="inp num" v-model.number="r.loss"></label>
                      <label class="f">来源<Pick class="sel" v-model="r.src"><option v-for="x in db.dicts.物料来源" :key="x">{{ x }}</option></Pick></label>
                      <label class="f">供应商<Pick class="sel" v-model="r.supplier"><option value="">按物料默认</option><option v-for="x in supsFor(r)" :key="x.code" :value="x.code">{{ x.short }}</option></Pick></label>
                      <label class="f">参考单价（元/{{ mat(r.mat).purUnit }}）<input class="inp num" type="number" :value="r.price ?? ''" :placeholder="rowRefPrice(r)" @change="setPrice(r, $event.target.value)"></label>
                      <label class="f">备注<input class="inp" v-model="r.note"></label>
                    </div>
                    <div v-else-if="r.note" class="mut">备注：{{ r.note }}</div>
                  </div>
                </td></tr>
              </template></template>
              <tr v-if="!tp.bom.some((r) => bomOwnerOf(r) === g.owner)"><td :colspan="edit ? 12 : 10" class="empty">{{ g.owner === '研发' ? (edit ? '还没有主料和工艺：点「加一行」，或者从甲方的 BOM 表导入' : '这一版没有主料和工艺') : (edit ? '还没有辅料：生产跟单来填' : '还没有辅料') }}</td></tr>
              </template>
            </tbody>
          </table>
          <BatchBar v-if="edit" :n="picked.length" @clear="picked = []"><input class="inp num" style="width:80px;height:26px" v-model.number="fillVal" placeholder="填一个数" aria-label="要填的数">
            <button class="btn sm" @click="fillPicked('用量')">填用量</button><button class="btn sm" @click="fillPicked('损耗')">填损耗</button></BatchBar>
          <div v-if="canSeeCost()" class="row" style="justify-content:flex-end;gap:20px;margin-top:12px" data-c="单件成本">
            <span class="dim" style="font-size:12px">基码 {{ baseSizeOf(s.sizeGroup) }}</span>
            <span class="mut">物料 {{ fm(k.mat) }}</span><span class="mut">加工 {{ fm(k.labor) }}</span><span v-if="k.other" class="mut">其他 {{ fm(k.other) }}</span><a @click="view = 'cost'"><b>单件 {{ fm(k.total) }}</b></a>
          </div>
        </div>

        <div v-if="bomView === 'color'" data-c="按颜色展开">
          <table class="tbl inset flat"><thead><tr><th>部位</th><th>物料 SKU</th><th>物料</th><th>单位</th><th v-for="z in sizes" :key="z" class="num">{{ z }}</th><th class="num">损耗</th><th>来源</th><th>备注</th></tr></thead>
            <tbody><tr v-for="(r, i) in tp.bom" :key="i" :class="{ sel: cur && cur.i === i }" @mouseenter="hover(i)">
              <td><span class="dim" style="margin-right:6px">{{ i + 1 }}</span>{{ r.part }}</td>
              <td class="code">{{ rowSku(r, byColor) || '—' }}</td>
              <td><span class="row"><Thumb :code="rowSku(r, byColor) || r.mat" :s="28"/>{{ rowSku(r, byColor) ? skuName(rowSku(r, byColor)) : '未配料' }}</span></td>
              <td>{{ mat(r.mat).unit }}</td>
              <td v-for="z in sizes" :key="z" class="num">{{ fq(rowUse(r, z)) }}</td><td class="num">{{ r.loss }}%</td><td class="mut">{{ r.src }}</td><td class="mut">{{ r.note || '' }}</td></tr>
              <tr v-if="!tp.bom.length"><td :colspan="sizes.length + 7" class="empty">还没有物料</td></tr></tbody></table>
        </div>
      </template>

      <TpFiles v-if="view === 'files'" :tp="tp"/>

      <div v-if="view === 'cost'" data-c="资料包：核价">
        <div v-if="!canSeeCost()" class="empty">核价要有看成本的权限：找管理员在 设置 › 角色权限 里开「成本（核价）」</div>
        <template v-else>
          <div class="cost-top" data-c="核价：单件成本">
            <div class="cost-k"><small>单件成本（{{ k.base }} 码）</small><b>{{ fm(k.total) }}</b></div>
            <div class="cost-k"><small>吊牌价</small><b>{{ fm(s.tagPrice) }}</b></div>
            <div class="cost-k"><small>倍率（吊牌价 ÷ 单件成本）</small><b>{{ ratio == null ? '—' : ratio }}</b></div>
            <a class="cost-more" :href="'#/costing/' + s.code" title="待定模块，甲方确认后再做">询价比价、订单毛利（待定）<Ico n="right" :s="13"/></a>
          </div>
          <table class="tbl inset flat cost-tbl" data-c="核价：明细">
            <thead><tr><th>部位 / 项目</th><th>物料</th><th>来源</th><th class="num">用量（{{ k.base }} 码）</th><th class="num">损耗</th><th class="num">单价</th><th class="num">单件成本</th></tr></thead>
            <tbody>
              <template v-for="g in costGroups" :key="g.g">
                <tr class="cg"><td colspan="6">{{ g.g }}</td><td class="num">{{ fm(g.sum) }}</td></tr>
                <tr v-for="x in g.rows" :key="x.i" :class="{ off: !x.counted }">
                  <td>{{ x.r.part }}</td>
                  <td><span class="row"><Thumb :code="rowThumb(x.r)" :s="24"/>{{ mat(x.r.mat).name }}</span></td>
                  <td>{{ x.r.src }}</td>
                  <td class="num">{{ fq(x.use) }} <span class="dim">{{ mat(x.r.mat).unit }}</span></td>
                  <td class="num">{{ x.r.loss }}%</td>
                  <td class="num">{{ fm(x.price) }}<span class="dim">/{{ mat(x.r.mat).purUnit }}</span></td>
                  <td class="num"><template v-if="x.counted">{{ fm(x.cost) }}</template><span v-else class="dim">{{ x.r.src === '厂购' ? '含在加工价里' : '客户出' }}</span></td>
                </tr>
                <tr v-if="!g.rows.length"><td colspan="7" class="dim">物料清单里没有{{ g.g }}</td></tr>
              </template>
              <tr class="cg"><td colspan="6">加工费</td><td class="num">{{ fm(k.labor) }}</td></tr>
              <tr data-c="核价：加工费"><td>加工费（元/件）</td>
                <td colspan="5"><input v-if="edit" class="inp cell" style="width:100%;max-width:320px" v-model="tp.laborNote" maxlength="255" placeholder="备注，如：清加工 38，半包 45"><span v-else class="mut">{{ tp.laborNote || (tp.labor == null ? '没填，按 0 算' : '') }}</span></td>
                <td class="num"><input v-if="edit" class="inp cell num" style="width:80px" type="number" :value="tp.labor ?? ''" placeholder="元/件" @change="setLabor($event.target.value)"><template v-else>{{ fm(k.labor) }}</template></td></tr>
              <tr v-if="cpRef != null" data-c="核价：成品采购参考价"><td>成品采购参考价</td><td colspan="5" class="mut">价格档案 · {{ supName(s.factory) }} 包工包料现价，含料；只作参考，不计入单件成本</td><td class="num mut">{{ fm(cpRef) }}</td></tr>
              <tr class="cg"><td colspan="6"><span class="row" style="gap:10px">其他费用<button v-if="edit" class="btn sm" @click="addCost"><Ico n="plus" :s="12"/>加一项</button></span></td><td class="num">{{ fm(k.other) }}</td></tr>
              <tr v-for="(c, i) in tp.costs" :key="'c' + i">
                <template v-if="edit">
                  <td><input class="inp cell" style="width:140px" v-model="c.name" placeholder="如：印绣花、运费"></td>
                  <td colspan="5"><span class="row"><input class="inp cell" style="flex:1" v-model="c.note" placeholder="备注"><button class="btn ghost icon sm" title="删除这一项" @click="tp.costs.splice(i, 1)"><Ico n="trash" :s="13"/></button></span></td>
                  <td class="num"><input class="inp cell num" style="width:80px" type="number" :value="c.amt ?? ''" placeholder="元/件" @change="setAmt(c, $event.target.value)"></td>
                </template>
                <template v-else><td>{{ c.name || '—' }}</td><td colspan="5" class="mut">{{ c.note || '' }}</td><td class="num">{{ fm(c.amt) }}</td></template>
              </tr>
              <tr v-if="!tp.costs.length"><td colspan="7" class="dim">{{ edit ? '印绣花外加工、运费、包装这类按件摊的钱，点「加一项」' : '没有其他费用' }}</td></tr>
            </tbody>
            <tfoot><tr><td colspan="6">单件成本 = 面料 + 辅料 + 加工费 + 其他费用</td><td class="num">{{ fm(k.total) }}</td></tr></tfoot>
          </table>
        </template>
      </div>

      <div class="sec" data-c="资料包记录"><h3>{{ tpName(tp) }} 的记录</h3><Log :items="tp.log"/></div>
    </template>
    <BomRowForm v-if="adding && tp" :tp="tp" @close="adding = false"/>
    <BomRowForm v-if="editing && tp" :tp="tp" :row="editing" @close="editing = null"/>
    <TpImport v-if="importing && tp" :tp="tp" @close="importing = false"/>
  </div>`,
})

/* 加一行 / 改一行 BOM：部位、物料、颜色方式、SKU、用量方式、用量、损耗（默认 0）、来源、供应商、参考单价、备注 */
comp('BomRowForm', {
  props: { tp: Object, row: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const s = style(p.tp.style)
    const sizes = sizesOf(s.sizeGroup)
    const f = reactive({ part: '大身', mat: '', mode: '随款色', sku: '', map: {}, um: '统一', use: 1, uses: {}, loss: 0, src: '自购', supplier: '', price: null, note: '', ...(p.row ? JSON.parse(JSON.stringify(p.row)) : {}) })
    f.uses = Object.fromEntries(sizes.map((z) => [z, f.uses?.[z] ?? null]))
    if (!f.map) f.map = {}
    const mats = computed(() => db.materials.filter((x) => x.status === '启用' || x.code === f.mat))
    const m = computed(() => mat(f.mat))
    const skus = computed(() => (m.value?.skus || []).filter((k) => k.status !== '停用' || k.code === f.sku || Object.values(f.map).includes(k.code)))
    const sups = computed(() => db.suppliers.filter((x) => x.status === '合作中' || x.code === f.supplier))
    // 换物料：SKU、配色、供应商、单价按新物料重新带
    function resetMat() {
      if (!m.value) return
      const ks = m.value.skus.filter((k) => k.status !== '停用')
      f.sku = ks[0]?.code || ''
      f.map = Object.fromEntries(s.colors.map((c) => [c, ks.find((k) => k.color === c)?.code]).filter(([, v]) => v))
      f.mode = ks.length > 1 ? '随款色' : '固定'
      f.supplier = m.value.supplier || ''
      f.price = null
    }
    if (!p.row) { f.mat = mats.value[0]?.code; resetMat() }
    watch(() => f.mat, resetMat)
    function save() {
      const ok = run(() => {
        if (!f.part || !f.mat) fail('部位、物料都要选')
        if (f.mode === '固定' && !f.sku) fail('要选物料 SKU')
        if (!(f.loss >= 0)) fail('损耗率不对，不损耗填 0')
      })
      if (!ok) return
      const r = JSON.parse(JSON.stringify({ ...f, price: f.price === '' ? null : f.price }))
      if (r.mode === '固定') delete r.map; else delete r.sku
      if (p.row) { Object.keys(p.row).forEach((k) => delete p.row[k]); Object.assign(p.row, r) } else p.tp.bom.push(r)
      emit('close')
    }
    // 一键填写（RFC 012 §5.5）：填好基码，铺到所有码，再改不一样的
    function sameAll() {
      const b = f.uses[baseSizeOf(s.sizeGroup)]
      if (b == null || b === '') return toast(`先填基码 ${baseSizeOf(s.sizeGroup)} 的用量`)
      sizes.forEach((z) => (f.uses[z] = b))
    }
    return { s, sizes, f, mats, m, skus, sups, save, sameAll }
  },
  template: `<Modal :t="row ? '改第 ' + (tp.bom.indexOf(row) + 1) + ' 行：' + row.part : '加一行物料'" :w="660" @close="$emit('close')">
    <div class="form">
      <label class="f">部位<Pick class="sel" v-model="f.part"><option v-for="x in db.dicts.BOM部位" :key="x">{{ x }}</option></Pick></label>
      <label class="f">物料<Pick class="sel" v-model="f.mat"><option v-for="x in mats" :key="x.code" :value="x.code">{{ x.code }} {{ x.name }}{{ x.status === '停用' ? '（停用）' : '' }}</option></Pick></label>
      <div class="f">颜色方式<Seg v-model="f.mode" :items="['固定', '随款色']"/></div>
      <label v-if="f.mode === '固定'" class="f">物料 SKU<Pick class="sel" v-model="f.sku"><option v-for="k in skus" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></Pick></label>
      <div v-else class="f full">款色 → 物料 SKU<div class="chips"><label v-for="c in s.colors" :key="c" class="row g4"><Sw :c="c"/><Pick class="sel" style="height:26px" v-model="f.map[c]"><option :value="undefined">未配</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></Pick></label></div></div>
      <div class="f">用量方式<Seg v-model="f.um" :items="['统一', '分码']"/></div>
      <label v-if="f.um === '统一'" class="f">单件用量（{{ m?.unit }}）<input class="inp num" v-model.number="f.use"></label>
      <div v-else class="f full">分码用量（{{ m?.unit }}/件）<div class="row"><label v-for="z in sizes" :key="z" class="f" style="width:72px">{{ z }}<input class="inp num" v-model.number="f.uses[z]"></label>
        <button type="button" class="btn ghost sm" style="align-self:end" data-c="一键填写" @click="sameAll">各码都填成基码的用量</button></div></div>
      <label class="f">损耗 %<input class="inp num" type="number" v-model.number="f.loss"></label>
      <label class="f">物料来源<Pick class="sel" v-model="f.src"><option v-for="x in db.dicts.物料来源" :key="x">{{ x }}</option></Pick></label>
      <label class="f">供应商<Pick class="sel" v-model="f.supplier"><option value="">按物料默认</option><option v-for="x in sups" :key="x.code" :value="x.code">{{ x.short }}</option></Pick></label>
      <label class="f">参考单价（元/{{ m?.purUnit }}）<input class="inp num" type="number" v-model.number="f.price" :placeholder="m ? '带出 ' + rowRefPrice(f) : ''"></label>
      <label class="f full">备注<input class="inp" v-model="f.note" placeholder="如：注意对格"></label>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ row ? '保存' : '加上' }}</button></template>
  </Modal>`,
})

/* ---------- 资料包的「工艺单」页签（RFC 011 §2，甲方 11-18）：三组文件 —— 工艺单、纸样和其他、导入的 BOM 表（导入时自动挂上，有才出这一组）
 * 工艺单就是甲方现在用的 Excel：传上来挂在这一版上，能预览、能下载，系统不拆里面的内容；发工厂就下载这份发微信。
 * 草稿能传、排序、删；生效后只读，要换就修订。原型预览只显示 .xlsx 第一张表的文字（真系统按 Excel 的样子画，见 XlsPreview） ---------- */
comp('TpFiles', {
  props: { tp: Object },
  setup(p) {
    const edit = computed(() => p.tp.status === '草稿')
    const groups = computed(() => TP_FILE_GROUPS.map((g) => ({ ...g, files: p.tp.files.filter((f) => f.cat === g.cat) })).filter((g) => g.cat !== '导入的 BOM 表' || g.files.length))
    const viewing = ref(null)
    // 先过类型、大小、个数（addTpFile 查），过了再在后台读内容：.xlsx 读第一张表给预览用，10MB 以内的存下来给下载用
    function take(cat, list) {
      for (const file of [...list]) {
        const f = run(() => addTpFile(p.tp, cat, { name: file.name, size: file.size }))
        if (!f) continue
        const live = p.tp.files.find((x) => x.id === f.id)
        if (/\.xlsx$/i.test(file.name)) xlsxRead(file).then((grid) => { live.grid = grid }).catch(() => {})
        if (file.size <= 10485760) readFile(file).then((x) => { live.src = x.src }).catch(() => {})
      }
    }
    const canPreview = (f) => !!f.grid || /\.(jpe?g|png|webp)$/i.test(f.name) && !!f.src
    function preview(f) { if (f.grid) viewing.value = f; else viewPics([f]) }
    function download(f) {
      if (f.src) { const a = document.createElement('a'); a.href = f.src; a.download = f.name; a.click() }
      else if (f.grid) saveBlob(xlsxWrite([{ name: 'Sheet1', rows: f.grid }]), f.name)
      else toast('原型里没存这个文件的内容（太大），真系统能下载', null, true)
    }
    function move(f, d) {
      const same = p.tp.files.filter((x) => x.cat === f.cat), other = same[same.indexOf(f) + d]
      if (!other) return
      const a = p.tp.files.indexOf(f), b = p.tp.files.indexOf(other)
      p.tp.files.splice(a, 1, other); p.tp.files.splice(b, 1, f)
    }
    async function remove(f) {
      if (await ask(`删除 ${f.name}`, '从这一版资料包上拿掉这个文件；已经发出去的不受影响。', '删除', true)) p.tp.files.splice(p.tp.files.indexOf(f), 1)
    }
    return { edit, groups, viewing, take, canPreview, preview, download, move, remove, fsize, extOf }
  },
  template: `<div data-c="资料包：工艺单">
    <section v-for="g in groups" :key="g.cat" class="sec tpf" :data-c="'工艺单：' + g.cat">
      <h3>{{ g.cat }}<span class="dim" style="font-weight:400;font-size:12px">{{ g.files.length }} 个</span>
        <span class="acts"><label v-if="edit && g.cat !== '导入的 BOM 表'" class="btn sm"><Ico n="upload" :s="13"/>上传<input type="file" multiple hidden :accept="g.types.map(t => '.' + t).join(',')" @change="take(g.cat, $event.target.files); $event.target.value = ''"></label></span></h3>
      <div v-if="!g.files.length" class="empty" style="padding:18px">{{ edit ? (g.cat === '工艺单' ? '还没有工艺单：点「上传」，选甲方现在用的工艺单 Excel' : '还没有' + g.cat) : '这一版没有' + g.cat }}</div>
      <table v-else class="tbl inset flat">
        <tbody><tr v-for="(f, i) in g.files" :key="f.id">
          <td class="dim" style="width:28px">{{ i + 1 }}</td>
          <td><span class="row g4"><span class="ext">{{ extOf(f.name) }}</span>{{ f.name }}</span></td>
          <td class="num dim" style="width:80px">{{ f.size ? fsize(f.size) : '' }}</td>
          <td class="num" style="width:230px"><span class="row g4" style="justify-content:flex-end">
            <button v-if="canPreview(f)" class="btn ghost sm" @click="preview(f)">预览</button>
            <button class="btn ghost sm" @click="download(f)">下载</button>
            <template v-if="edit">
              <button class="btn ghost icon sm" title="上移" :disabled="!i" @click="move(f, -1)"><Ico n="up" :s="13"/></button>
              <button class="btn ghost icon sm" title="下移" :disabled="i === g.files.length - 1" @click="move(f, 1)"><Ico n="down" :s="13"/></button>
              <button class="btn ghost icon sm" title="删除这个文件" @click="remove(f)"><Ico n="trash" :s="13"/></button>
            </template></span></td>
        </tr></tbody>
      </table>
      <p v-if="edit && g.cat !== '导入的 BOM 表'" class="dim" style="font-size:12px;margin:6px 0 0">能传 {{ g.types.join('、') }}；单个最大 10MB{{ g.types.includes('prj') ? '（纸样 prj、plt 100MB）' : '' }}，最多 20 个</p>
    </section>
    <XlsPreview v-if="viewing" :f="viewing" @close="viewing = null"/>
  </div>`,
})

/* 在页面里看一份 Excel（RFC 011 §2.3）：原型只有第一张表的文字；真系统后端读成表格数据，每张 sheet 一个页签，合并单元格、粗体、底色、表里的图片都画出来 */
comp('XlsPreview', {
  props: { f: Object },
  emits: ['close'],
  setup(p) {
    const cols = computed(() => Math.max(1, ...p.f.grid.map((r) => r.length)))
    return { cols, colL }
  },
  template: `<Modal :t="f.name" :w="960" @close="$emit('close')">
    <div class="xls" data-c="Excel 预览"><table><thead><tr><th></th><th v-for="c in cols" :key="c">{{ colL(c - 1) }}</th></tr></thead>
      <tbody><tr v-for="(r, i) in f.grid" :key="i"><th>{{ i + 1 }}</th><td v-for="c in cols" :key="c">{{ r[c - 1] ?? '' }}</td></tr></tbody></table></div>
    <p class="dim" style="font-size:12px;margin:8px 0 0">原型只显示第一张表的文字；真系统照 Excel 的样子画：每张 sheet 一个页签，合并单元格、粗体、底色、表里的图片都在。</p>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">关闭</button></template>
  </Modal>`,
})

/* ---------- 从甲方的 BOM 表导入物料清单（RFC 011 §4.3）：选表 → 认（parseBomSheet）→ 预览里改部位、用量、损耗、单价，
 * 没认出来的挑物料或者这行不导（甲方 11-18 答 Q49：导入只认不建，新物料先到物料库建）→ 加进物料清单（替换或加在后面），加工费、运费带进核价，导入的表挂到工艺单页签。
 * 加进去只是改了这一版草稿，和手填一样。原型只认 .xlsx 的第一张表（真系统 .xls 也认、几张 sheet 按款号自动挑）；没有现成的表点「用示例表试一下」 ---------- */
comp('TpImport', {
  props: { tp: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const st = reactive({ file: null, grid: null, res: null, err: '', picks: {}, skip: {}, mode: '替换' })
    // 挑了物料后重新认：物料没变的行留着人改过的部位、用量
    function parse(keep) {
      st.err = ''
      const old = keep && st.res ? new Map(st.res.rows.map((x) => [x.line, x])) : new Map()
      try {
        const r = parseBomSheet(p.tp, st.grid, st.picks)
        r.rows = r.rows.map((x) => { const o = old.get(x.line); return o?.row && x.row && o.row.mat === x.row.mat ? o : x })
        st.res = r
      } catch (e) { st.res = null; st.err = e.message }
    }
    const reset = () => Object.assign(st, { picks: {}, skip: {}, mode: p.tp.bom.length ? '替换' : '加在后面' })
    async function pick(e) {
      const file = e.target.files[0]
      e.target.value = ''
      if (!file) return
      if (!/\.xlsx$/i.test(file.name)) { st.err = /\.xls$/i.test(file.name) ? '原型只认 .xlsx：在 Excel 里另存为 .xlsx 再选（真系统 .xls 也认）' : '只收 Excel 文件（.xlsx）'; return }
      try { st.grid = await xlsxRead(file); st.file = { name: file.name, size: file.size }; reset(); parse() } catch (x) { st.err = x.message }
    }
    function demo() { st.grid = demoBomGrid(p.tp.style); st.file = { name: `${p.tp.style} BOM（示例）.xlsx`, size: 9216 }; reset(); parse() }
    const downloadDemo = () => saveBlob(xlsxWrite([{ name: p.tp.style, rows: demoBomGrid(p.tp.style) }]), `${p.tp.style} BOM 示例.xlsx`)
    const mats = computed(() => db.materials.filter((m) => m.status === '启用'))
    function choose(x, code) { if (code) { st.picks[x.line] = code; parse(true) } }
    const live = computed(() => (st.res?.rows || []).filter((x) => !st.skip[x.line]))
    const blocked = computed(() => live.value.filter((x) => !x.row).length)
    function apply() {
      const res = canSeeCost() ? st.res : { ...st.res, labor: null, fees: [] }
      const n = run(() => applyBomImport(p.tp, res, live.value, p.tp.bom.length ? st.mode : '加在后面', { ...st.file, grid: st.grid }))
      if (!n) return
      emit('close')
      toast(`加了 ${n} 行物料` + (res.labor != null ? `，加工费 ${fm(res.labor)} 填进了核价` : '') + (res.fees.length ? `，${res.fees.map((f) => f.name).join('、')}加进了其他费用` : '') + '；表挂在工艺单页签上')
    }
    return { st, pick, demo, downloadDemo, mats, choose, live, blocked, apply }
  },
  template: `<Modal t="从 BOM 表导入物料清单" :w="1120" @close="$emit('close')">
    <div class="row" style="gap:10px;flex-wrap:wrap;margin-bottom:10px">
      <label class="btn" :class="{ pri: !st.file }"><Ico n="upload" :s="14"/>{{ st.file ? '换一个文件' : '选 BOM 表（.xlsx）' }}<input type="file" accept=".xlsx,.xls" hidden @change="pick"></label>
      <button class="btn" @click="demo">用示例表试一下</button>
      <a style="font-size:12.5px" @click="downloadDemo">下载示例表</a>
      <span v-if="st.file" class="mut">{{ st.file.name }}</span>
    </div>
    <div v-if="st.err" class="banner red" style="margin:0 0 10px"><Ico n="alert"/>{{ st.err }}</div>
    <p v-if="!st.res && !st.err" class="mut">选甲方的 BOM 明细表。按表头认列（物料名称、规格、颜色、单价、单位、单件用量、损耗），按名称和规格对物料库；认不出的挑物料或者这行不导（物料库里没有的先到物料库建）。表下面的加工费、运费一起带进核价。</p>
    <template v-if="st.res">
      <p v-if="st.res.title" class="mut" style="margin:0 0 8px">表头：{{ st.res.title }}</p>
      <div class="xscroll"><table class="tbl inset flat">
        <thead><tr><th style="width:36px">行</th><th>表里写的</th><th>认成</th><th>部位</th><th class="num">用量</th><th class="num">损耗 %</th><th v-if="canSeeCost()" class="num">单价</th><th>状态</th><th>提示</th><th></th></tr></thead>
        <tbody><tr v-for="x in st.res.rows" :key="x.line" :class="{ off: st.skip[x.line] }">
          <td class="dim">{{ x.line }}</td>
          <td>{{ [x.src.name, x.src.spec, x.src.color].filter(Boolean).join(' · ') }}<div class="dim" style="font-size:11.5px">{{ x.src.use }} {{ x.src.unit }}<template v-if="canSeeCost() && x.src.price"> · ¥{{ x.src.price }}</template></div></td>
          <td><template v-if="x.row"><span class="code">{{ x.row.mat }}</span> {{ mat(x.row.mat).name }}<div class="dim" style="font-size:11.5px">{{ x.row.mode === '固定' ? (x.row.sku || '没配料') : '随款色 · 配上 ' + Object.keys(x.row.map).length + ' 个颜色' }}</div></template>
            <Pick v-else-if="!st.skip[x.line]" class="sel" style="height:26px;width:150px" :model-value="''" @update:model-value="v => choose(x, v)"><option value="">挑物料</option><option v-for="m in mats" :key="m.code" :value="m.code">{{ m.code }} {{ m.name }}</option></Pick>
            <span v-else class="dim">—</span></td>
          <td><Pick v-if="x.row" class="sel" style="height:26px;width:88px" v-model="x.row.part"><option value="">选部位</option><option v-for="v in db.dicts.BOM部位" :key="v">{{ v }}</option></Pick></td>
          <td class="num"><input v-if="x.row" class="inp cell num" style="width:70px" type="number" v-model.number="x.row.use"></td>
          <td class="num"><input v-if="x.row" class="inp cell num" style="width:56px" type="number" v-model.number="x.row.loss"></td>
          <td v-if="canSeeCost()" class="num"><input v-if="x.row" class="inp cell num" style="width:72px" type="number" v-model.number="x.row.price"></td>
          <td><St :s="st.skip[x.line] ? '不导' : x.status"/></td>
          <td class="wrap" style="font-size:12px;min-width:150px;max-width:200px"><div v-for="n in x.notes" :key="n" class="amber">{{ n }}</div></td>
          <td class="num"><button class="btn ghost sm" @click="st.skip[x.line] = !st.skip[x.line]">{{ st.skip[x.line] ? '要导' : '不导' }}</button></td>
        </tr></tbody>
      </table></div>
      <p v-if="canSeeCost() && (st.res.labor != null || st.res.fees.length)" class="mut" style="margin:8px 0 0">一起带进核价：<span v-if="st.res.labor != null">加工费 {{ fm(st.res.labor) }}</span><span v-for="f in st.res.fees" :key="f.name" style="margin-left:12px">{{ f.name }} {{ fm(f.amt) }}</span></p>
    </template>
    <template #foot>
      <span v-if="blocked" class="amber" style="font-size:12.5px">还有 {{ blocked }} 行没认出来：挑物料或者点「不导」；物料库里没有的先到物料库建</span>
      <span class="sp"></span>
      <Seg v-if="st.res && tp.bom.length" v-model="st.mode" :items="[['替换', '替换现有 ' + tp.bom.length + ' 行'], ['加在后面', '加在后面']]"/>
      <button class="btn" @click="$emit('close')">取消</button>
      <button class="btn pri" :disabled="!live.length || blocked > 0" @click="apply">加进物料清单（{{ live.length }} 行）</button>
    </template>
  </Modal>`,
})

/* 外发加工单、成品采购单上的「工艺单」：这张单锁定的资料包版本传的文件（导入的 BOM 表不列），下载发给工厂 */
comp('TpCraftFiles', {
  props: { tp: Object },
  emits: ['close'],
  setup(p) {
    const files = computed(() => (p.tp?.files || []).filter((f) => f.cat !== '导入的 BOM 表'))
    const viewing = ref(null)
    function download(f) {
      if (f.src) { const a = document.createElement('a'); a.href = f.src; a.download = f.name; a.click() }
      else if (f.grid) saveBlob(xlsxWrite([{ name: 'Sheet1', rows: f.grid }]), f.name)
      else toast('原型里没存这个文件的内容（太大），真系统能下载', null, true)
    }
    return { files, viewing, download }
  },
  template: `<Modal :t="'工艺单 · ' + (tp ? tpName(tp) : '')" :w="560" @close="$emit('close')">
    <p class="mut" style="margin:0 0 10px">这张单锁定的资料包版本上传的文件，下载以后发给工厂。</p>
    <div v-if="!files.length" class="empty">这一版资料包没有传工艺单：到款式的资料包里传</div>
    <table v-else class="tbl inset flat"><tbody><tr v-for="f in files" :key="f.id">
      <td class="dim" style="width:90px">{{ f.cat }}</td><td>{{ f.name }}</td>
      <td class="num"><span class="row g4" style="justify-content:flex-end"><button v-if="f.grid" class="btn ghost sm" @click="viewing = f">预览</button><button class="btn ghost sm" @click="download(f)">下载</button></span></td>
    </tr></tbody></table>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">关闭</button></template>
    <XlsPreview v-if="viewing" :f="viewing" @close="viewing = null"/>
  </Modal>`,
})

expose({
  homeBoss, homeSees, homeCanAudit, homeUpstream, upKind, seasonText, sgName, styleUpdated, styleOrders, tpHas, updateStyle, setStyleSku, removeStyleSku,
  skuBlocked, addStyleColors, removeStyle, moveItem, nextStyleCode, rowRefPrice, removeTpDraft, tpCost, TP_FILE_GROUPS,
})
