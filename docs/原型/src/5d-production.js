/* ============================================================
 * 生产与外发（M08）：生产单 = 外发加工单 + 成品采购单（甲方 11-14 拆成两个菜单，一张表，种类见 2-store.js PROD_KINDS）；
 * 进度节点、领料、异常、回货两种单一样；生产看板、手机报工两种一起看
 * ============================================================ */
SEARCH.push(() => db.productions.map((p) => ({ t: p.no + ' ' + p.style + ' ' + supName(p.factory), sub: prodKind(p), to: prodLink(p), i: 'factory' })))
// 单号和菜单对不上（CP 开头的从 /production 打开）跳到它自己的页面
redirect('/production/:no', ({ no }) => (production(no) && prodKind(production(no)) === '成品采购单' ? '/fp-purchases/' + no : ''))
redirect('/fp-purchases/:no', ({ no }) => (production(no) && prodKind(production(no)) === '外发加工单' ? '/production/' + no : ''))
const RISK_F = { late: '已逾期', node: '节点延期', near: '7 天内交货', issue: '有异常', upstream: '上游已变更' }
const riskMatch = (p, f) => (f === 'upstream' ? p.upstream && !p.upstream.done : prodRisk(p)[f])
const byRisk = (a, b) => prodRisk(b).score - prodRisk(a).score || expectDue(a).localeCompare(expectDue(b))
const KIT_CLS = { ok: 'green', part: 'amber', no: 'red', none: 'dim' }
// 异常责任方：和订单变更同一套（TRADE 关着时没有"客户"），默认"我方"（PRD §2.6、§6.7.4）；选填，上报时没定可以选"待定"，关闭时补上
const PARTIES = CHANGE_PARTIES
const PARTY0 = '我方'
const brandOf = (p) => style(p.style)?.brand || order(p.order)?.brand || ''
const TERMS_TPL = '次品率超过 3% 的部分按单价扣款；交期每延误一天扣货款 0.5%；回货附送货单，写明单号、颜色、尺码、数量；剩料按领料单清点退回。'

/* ---------- 生产单：改草稿、改节点计划、异常开始处理、回货作废 ---------- */
const PROD_FIELDS = [['company', '主体', companyName], ['factory', '加工厂', supName], ['mode', '加工方式'], ['price', '单价', fm], ['due', '交期', fd],
  ['merch', '生产跟单', userName], ['settle', '结算方式'], ['terms', '条款'], ['priceWhy', '改价原因'], ['note', '备注']]
function updateProduction(p, f) {
  if (p.status !== '草稿') fail('只有草稿能改；已提交的先撤回，或等审核人驳回')
  if (!canUseSupplier(f.factory, '生产')) fail(`${supName(f.factory)} 现在是「${sup(f.factory)?.status || '未选'}」，不能下${prodKind(p)}`)
  // 种类跟着单号走（JG / CP），改加工方式只能在同一种里换；要换种类作废这张，回需求订单重新排单
  if (f.mode && prodKind(f.mode) !== prodKind(p)) fail(`${prodKind(p)}的加工方式只能选 ${prodKindOf(p).modes.join('、')}`, ['要改成' + prodKind(f.mode) + '：作废这张，回需求订单重新排单'])
  if (!(+f.price > 0)) fail(prodKindOf(p).price + '要填')
  if (!f.due) fail('交期要填')
  const e = procPriceProblem({ factory: f.factory, style: p.style, mode: f.mode, price: +f.price, why: f.priceWhy }, p.date) // 和价格档案现价不一样要写改价原因
  if (e) fail(e)
  const ch = applyFields(p, { ...f, price: +f.price }, PROD_FIELDS)
  if (!ch.length) fail('没有改动')
  act(p, '改：' + ch.join('；'))
}
// 跟单员改节点计划（PRD §6.7.2）：rows = [{ ps, pf }]，和 p.nodes 一一对应
function updateNodePlan(p, rows) {
  if (!prodLive(p)) fail('审核下单以后、完成之前才能改节点计划')
  const bad = rows.map((r, i) => (!r.ps || !r.pf ? `${p.nodes[i].name}：计划开始、完成都要填` : r.pf < r.ps ? `${p.nodes[i].name}：计划完成不能早于计划开始` : '')).filter(Boolean)
  if (bad.length) fail('节点计划没改上', bad)
  const ch = []
  rows.forEach((r, i) => { const n = p.nodes[i]; if (n.ps !== r.ps || n.pf !== r.pf) { ch.push(`${n.name} ${fd(n.ps)}–${fd(n.pf)} → ${fd(r.ps)}–${fd(r.pf)}`); Object.assign(n, { ps: r.ps, pf: r.pf }) } })
  if (!ch.length) fail('没有改动')
  act(p, '改节点计划：' + ch.join('；'))
}
function startIssue(p, i) {
  if (i.status !== '待处理') fail(`这条异常是「${i.status}」`)
  Object.assign(i, { status: '处理中', handler: db.me, startAt: clock.today })
  act(p, '开始处理异常：' + i.type)
}
// 回货登记错了：作废这张，生产单的已回、次品减回去，状态重算；已经验货、入成品仓、对过账的不能作废
function voidReturn(r, reason) {
  if (r.status === '已作废') fail(r.no + ' 已经作废了')
  if (!reason?.trim()) fail('要写作废原因')
  const p = production(r.prod)
  if (!['已下单', '生产中', '部分回货', '已完成'].includes(p.status)) fail(`${prodKind(p)} ${p.no} 是「${p.status}」，回货不能作废`)
  const st = stmtOf(r.no) // 对账单的规则和出入库单同一条（2-store.js checkNotBilled）
  const why = [db.qcs?.some((q) => q.src === r.no) && '已经验过货', db.fgIn?.some((x) => x.ret === r.no) && '已经入了成品仓',
    st && `已经进了对账单 ${st.no}（${st.status}）${st.status === '草稿' ? '：先把这张草稿对账单作废' : '，对账单发出去了不能作废'}`].filter(Boolean)
  if (why.length) fail(`${r.no} 不能作废`, why)
  r.lines.forEach((x) => { const l = p.lines.find((y) => y.color === x.color && y.size === x.size); if (l) { l.ret -= x.qty; l.def -= x.def } })
  r.status = '已作废'
  act(r, '作废：' + reason.trim())
  const was = p.status
  p.status = prodRet(p) >= prodQty(p) * (1 - OVER) ? '已完成' : prodRet(p) ? '部分回货' : p.nodes.some((n) => n.as) ? '生产中' : '已下单'
  act(p, `回货 ${r.no} 作废（${reason.trim()}）${was !== p.status ? '，' + was + ' → ' + p.status : ''}`)
  const o = order(p.order)
  if (o?.status === '已完成' && p.status !== '已完成') { o.status = '生产中'; act(o, `回货 ${r.no} 作废，订单回到生产中`) }
}

/* ---------- 外发加工单、成品采购单列表：页面一样，各看各的种类 ---------- */
function prodListPage(kind) {
  return {
    setup() {
      const K = PROD_KINDS[kind]
      // cat：只看这个品类的款（旧链接带过来的）
      const s = reactive({ mine: false, tab: nav.query.s || '全部', f: nav.query.f || '', cat: nav.query.cat || '', q: '', flt: newFlt(), picked: [] })
      const ST = ['全部', '草稿', '待审核', '已下单', '生产中', '部分回货', '已完成', '已结案', '已作废']
      const scoped = computed(() => db.productions.filter((p) => prodKind(p) === kind && inScope(s.mine, p)))
      const tabs = computed(() => ST.map((k) => [k, k, k === '全部' ? scoped.value.length : scoped.value.filter((p) => p.status === k).length]))
      const rows = computed(() => scoped.value.filter((p) => (s.tab === '全部' || p.status === s.tab) && (!s.f || riskMatch(p, s.f)) && (!s.cat || style(p.style)?.cat === s.cat)
        && passFlt(s.flt, { date: expectDue(p), party: p.factory, merch: p.merch }) && matchQ(s.q, p.no, p.style, styleName(p.style), supName(p.factory), p.order)).slice().sort(byRisk))
      const XCOLS = [{ k: 'no', t: '单号', c: 'code' }, { k: 'order', t: '需求订单号', c: 'code' }, { k: 'style', t: '款号', c: 'code' }, { k: 'name', t: '款名', x: (p) => styleName(p.style) },
        { k: 'factory', t: '加工厂', x: (p) => supName(p.factory) }, { k: 'mode', t: '加工方式' }, { k: 'qty', t: '数量', x: prodQty }, { k: 'price', t: K.price, x: (p) => p.price }, { k: 'amt', t: '金额', x: prodAmt },
        { k: 'due', t: '交期', x: expectDue }, { k: 'ret', t: '已回货', x: prodRet }, { k: 'merch', t: '跟单', x: (p) => userName(p.merch) }, { k: 'status', t: '状态' }]
      const picks = computed(() => rows.value.filter((p) => s.picked.includes(p.no)))
      const batch = async (a) => { const ok = await batchAct('production', picks.value, a); s.picked = s.picked.filter((k) => !ok.includes(k)) }
      return { s, tabs, rows, RISK_F, XCOLS, picks, batch, kind }
    },
    template: `<div>
      <Head :t="kind"/>
      <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
      <div class="bar" data-c="筛选：交期、加工厂、跟单"><Filters v-model="s.flt" party="factory" merch label="交期"/>
        <Pick class="sel" style="height:28px" v-model="s.f"><option value="">全部风险</option><option v-for="(t, k) in RISK_F" :key="k" :value="k">{{ t }}</option></Pick>
        <span v-if="s.cat" class="fchip">{{ catOf(s.cat).name }}<button type="button" title="不按品类筛" @click="s.cat = ''"><Ico n="x" :s="11"/></button></span>
        <span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q"/></div>
      <div :data-c="kind + '列表'"><ProdTable :rows="rows" :kind="kind" v-model:picked="s.picked"/>
        <BatchBar :n="picks.length" @clear="s.picked = []"><button class="btn sm" @click="batch('提交')">提交</button><button class="btn sm" @click="batch('审核')">审核</button><ExportBtn class="sm" :rows="picks" :cols="XCOLS" :name="kind" label="导出选中"/></BatchBar></div>
    </div>`,
  }
}
route('/production', prodListPage('外发加工单'), { mod: 'production', t: '外发加工单' })
route('/fp-purchases', prodListPage('成品采购单'), { mod: 'fpurchases', t: '成品采购单' })

// 列表和看板共用；board：去掉金额、领料，加种类和异常两列（PRD §6.7.6）；kind：列表页是哪种单，空着的提示用
comp('ProdTable', {
  props: { rows: Array, board: Boolean, kind: String, picked: { type: Array, default: null } },
  emits: ['update:picked'],
  setup(p) {
    const kit = (r) => (prodLive(r) || r.status === '已完成' ? kitState(order(r.order)) : null)
    const openIs = (r) => r.issues.filter((i) => i.status !== '已关闭')
    const cols = computed(() => [
      { k: 'no', t: '单号', c: 'code' }, p.board && { k: 'kind', t: '种类', f: (r) => prodKindOf(r).short }, { k: 'style', t: '款式' }, { k: 'factory', t: '加工厂 · 方式' }, { k: 'qty', t: '数量', c: 'num', f: (r) => prodQty(r) },
      !p.board && { k: 'amt', t: '金额', c: 'num', f: (r) => fm(prodAmt(r)) }, { k: 'due', t: '交期' }, { k: 'nodes', t: '节点' }, { k: 'kit', t: '齐料' },
      !p.board && { k: 'issue', t: '领料', f: (r) => (prodLive(r) ? issueState(r) : '—') }, { k: 'ret', t: '回货' }, p.board && { k: 'exc', t: '异常' },
      { k: 'merch', t: '跟单', f: (r) => userName(r.merch) }, { k: 'status', t: '状态' },
    ].filter(Boolean))
    const empty = computed(() => `没有符合条件的${p.kind || '生产单'}。需求订单审核后在订单里「排单」生成${p.kind ? '，排单时选' + PROD_KINDS[p.kind].short : ''}；单号、款号、加工厂、订单号都能搜`)
    return { cols, kit, openIs, KIT_CLS, empty }
  },
  template: `<Tbl :rows="rows" link :picked="picked" @update:picked="$emit('update:picked', $event)" @row="p => go(prodLink(p))" :empty="empty" :cols="cols">
    <template #style="{ r }"><span class="row"><Thumb :code="r.style" :s="24"/><span class="code">{{ r.style }}</span></span></template>
    <template #factory="{ r }">{{ supName(r.factory) }}<span class="dim"> · {{ r.mode }}</span></template>
    <template #due="{ r }"><span class="row g4">{{ fd(expectDue(r)) }}<Days :d="expectDue(r)" :warn="7" :done="!prodLive(r)"/></span><span v-if="r.expect" class="dim" style="text-decoration:line-through;font-size:12px">{{ fd(r.due) }}</span></template>
    <template #nodes="{ r }"><Nodes :p="r"/></template>
    <template #kit="{ r }"><span v-if="kit(r)" :class="KIT_CLS[kit(r).k]">{{ kit(r).t }}</span><span v-else class="dim">—</span></template>
    <template #ret="{ r }"><Prog v-if="prodLive(r) || r.status === '已完成'" :a="prodRet(r)" :b="prodQty(r)"/><span v-else class="dim">—</span></template>
    <template #exc="{ r }"><span v-if="openIs(r).length" class="flag red"><Ico n="alert" :s="12"/>{{ openIs(r)[0].type }}<template v-if="openIs(r).length > 1"> 等 {{ openIs(r).length }} 个</template></span><span v-else class="dim">—</span></template>
    <template #status="{ r }"><span class="row"><St :s="r.status"/><Ico v-if="!board && prodRisk(r).issue" n="alert" :s="14" class="amber"/><Ico v-if="r.upstream && !r.upstream.done" n="diff" :s="14" class="amber"/></span></template>
  </Tbl>`,
})

/* ---------- 生产单详情：外发加工单、成品采购单共用，标题、单价名、打印跟着种类 ---------- */
const PROD_DETAIL = {
  props: ['no'],
  setup(p) {
    const x = computed(() => production(p.no))
    const tab = ref('nodes'), dlg = ref(''), node = ref(null), closing = ref(null), viewing = ref(null), printing = ref(false), printApx = ref(true)
    const docs = computed(() => db.stockDocs.filter((d) => d.src === p.no))
    const rets = computed(() => db.returns.filter((r) => r.prod === p.no))
    const openIssues = computed(() => x.value.issues.filter((i) => i.status !== '已关闭'))
    const colors = computed(() => [...new Set(x.value.lines.map((l) => l.color))])
    const sizes = computed(() => sizesOf(style(x.value.style).sizeGroup).filter((z) => x.value.lines.some((l) => l.size === z)))
    const qtyMap = computed(() => Object.fromEntries(x.value.lines.map((l) => [l.color + '|' + l.size, l.qty])))
    const after = computed(() => !['草稿', '待审核', '已作废'].includes(x.value.status))
    const sub = (c, z) => { const l = x.value.lines.find((y) => y.color === c && y.size === z); return l && after.value ? `回 ${l.ret} · 次 ${l.def} · 未 ${Math.max(0, l.qty - l.ret)}` : '' }
    const a = (n) => docAct('production', x.value, n, n === '审核' ? () => `${x.value.no} 已下单，按交期倒排了 ${x.value.nodes.length} 个节点` : '')
    const report = (i) => { if (prodLive(x.value)) { node.value = i; dlg.value = 'report' } }
    const repText = (r) => `${r.af ? '完成' : '开始'}${r.qty ? ' ' + r.qty + ' 件' : ''}${r.photos ? '，照片 ' + r.photos + ' 张' : ''}${r.note ? '，' + r.note : ''}`
    async function del() {
      if (!(await ask('删除 ' + x.value.no, '草稿删掉就找不回来了；订单上这些件数回到"待排单"，可以重新排。', '删除', true))) return
      if (run(() => removeDraft(db.productions, x.value))) { go(prodKindOf(x.value).to); toast('删掉了') }
    }
    // 退料入库：工厂仓 → 物料仓，源单挂这张生产单，明细带出领过的料
    // 成品采购单只领我方采购的料（主唛、吊牌这类）；资料包里没有就不出「领料」按钮
    const canIssue = computed(() => prodKind(x.value) === '外发加工单' || issuePlan(x.value).length > 0)
    const backInit = computed(() => ({ src: x.value.no, partner: x.value.factory, from: factoryWh(x.value.factory)?.code, to: db.warehouses.find((w) => w.type === '物料仓' && w.status !== '停用')?.code,
      lines: issuePlan(x.value).filter((r) => r.issued > 0).map((r) => ({ sku: r.sku, owner: r.owner, qty: null, price: null, lot: '', rolls: null, note: '' })) }))
    const more = computed(() => [
      prodLive(x.value) && { t: '上报异常', fn: () => { dlg.value = 'issue' } },
      prodLive(x.value) && x.value.nodes.length && { t: '改节点计划', fn: () => { dlg.value = 'plan' } },
      after.value && docs.value.some((d) => d.type === '领料出库' && d.status === '已审核') && { t: '退料（剩料退回物料仓）', fn: () => { dlg.value = 'back' } },
      ...['撤回', '反审核', '结案', '取消结案'].filter((k) => can('production', x.value, k)).map((k) => ({ t: k, fn: () => a(k) })),
      { t: '打印（附工艺单）', fn: () => { printing.value = true } },
      x.value.status === '草稿' && { t: '删除', danger: true, fn: del },
      can('production', x.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') },
    ].filter(Boolean))
    function start(i) { if (run(() => startIssue(x.value, i))) toast('这条异常改成处理中') }
    function doClose() { const c = closing.value; if (run(() => closeIssue(x.value, c.i, c.measure.trim(), c.party))) { closing.value = null; toast('异常已关闭') } }
    async function voidRet() {
      const r = viewing.value
      const why = await askText('作废 ' + r.no, prodKind(x.value) + '的已回、次品按这张单减回去，状态重新算。已经验货、入成品仓、对过账的不能作废。', '作废原因，比如：数量登记错了，重新登记', '作废')
      if (why == null) return
      if (run(() => voidReturn(r, why))) toast(`${r.no} 作废了，${prodKind(x.value)}现在是「${x.value.status}」`)
    }
    const nodeCls = (n) => nodeState(n)
    return { x, tab, dlg, node, closing, viewing, printing, printApx, canIssue, docs, rets, openIssues, colors, sizes, qtyMap, after, sub, a, report, repText, backInit, more, start, doClose, voidRet, nodeCls, PARTIES }
  },
  template: `<div v-if="x">
    <Head :t="x.no" :back="[prodKind(x), prodKindOf(x).to]">
      <template v-if="x.status === '草稿'"><button class="btn" @click="dlg = 'edit'">编辑</button><button class="btn pri" @click="a('提交')">提交审核</button></template>
      <template v-if="mayAct('production', x, '审核')"><button class="btn" @click="a('驳回')">驳回</button><button class="btn pri" @click="a('审核')">审核下单</button></template><ApproveWait v-else kind="production" :d="x"/>
      <button v-if="canGenContract('production', x)" class="btn" @click="dlg = 'contract'">生成合同</button>
      <template v-if="prodLive(x)"><button v-if="canIssue" class="btn" @click="dlg = 'issueMat'">领料</button><button class="btn" @click="dlg = 'return'">登记回货</button><button class="btn pri" @click="report(null)">报进度</button></template>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <Upstream :d="x"/>
        <div v-for="i in openIssues" :key="i.id" class="banner red" data-c="异常提示"><Ico n="alert"/>{{ i.type }}：{{ i.desc }}<span v-if="i.newDate" class="mut">（预计交期 {{ fd(i.newDate) }}）</span><button class="btn sm" @click="tab = 'issues'">去处理</button></div>
        <div class="title">{{ supName(x.factory) }}<St :s="x.status"/></div>
        <div class="sub"><a class="code" :href="'#/styles/' + x.style">{{ x.style }}</a><span>{{ styleName(x.style) }}</span><span>{{ prodKind(x) }} · {{ x.mode }}</span><span>{{ prodQty(x) }} 件</span><span>交期 {{ fd(expectDue(x)) }}</span><Days v-if="prodLive(x)" :d="expectDue(x)" :warn="7"/></div>
        <Tabs v-model="tab" :items="[['nodes', '进度'], ['lines', '明细'], ['mat', '领料', docs.length], ['issues', '异常', x.issues.length], ['rets', '回货', rets.length]]"/>
        <section v-if="tab === 'nodes'" data-c="进度节点">
          <div v-if="!x.nodes.length" class="empty">审核下单后按交期往前倒排节点</div>
          <ul class="tl">
            <li v-for="(n, i) in x.nodes" :key="n.name" :class="nodeCls(n)" style="cursor:pointer" @click="report(i)">
              <span class="dot"></span><b>{{ n.name }}</b>
              <div class="col"><small>计划 {{ fd(n.ps) }} – {{ fd(n.pf) }}</small><small v-if="n.as" :class="{ red: nodeCls(n) === 'late' }">实际 {{ fd(n.as) }} – {{ n.af ? fd(n.af) : '进行中' }}<template v-if="n.qty"> · {{ n.qty }} 件</template></small>
                <small v-if="n.by" class="dim">{{ userName(n.by) }}{{ n.at ? ' ' + fd(n.at) + ' 汇报' : '' }}<template v-if="n.note"> · {{ n.note }}</template></small>
                <Pics v-if="n.photos && n.photos.length" :model-value="n.photos" readonly :s="40" :limit="4" style="margin-top:4px" @click.stop/>
                <details v-if="n.reports && n.reports.length > 1" class="tl-reps" @click.stop><summary>汇报记录 {{ n.reports.length }} 次</summary><div v-for="(r, k) in n.reports" :key="k">{{ fd(r.at) }} {{ userName(r.by) }}：{{ repText(r) }}</div></details></div>
              <span><span v-if="nodeCls(n) === 'late'" class="flag red">{{ n.af ? '晚了 ' + -daysLeft2(n.pf, n.af) + ' 天' : '延期' }}</span><span v-else-if="nodeCls(n) === 'warn'" class="flag amber">临期</span><Ico v-else-if="n.af" n="check" :s="14" class="green"/></span>
            </li>
          </ul>
        </section>
        <section v-if="tab === 'lines'" data-c="加工明细"><Matrix :st="x.style" :model-value="qtyMap" ro :colors="colors" :sub="sub"/></section>
        <section v-if="tab === 'mat'" data-c="领料">
          <Tbl inset flat :rows="issuePlan(x)" :empty="prodKind(x) === '成品采购单' ? '成品采购单的料工厂自己买；资料包里没有标「我方采购」的料（如主唛、吊牌），不用领料' : '资料包里没有要发给工厂的料'" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku), img: r => r.sku }, { k: 'code', t: 'SKU', c: 'code', f: r => r.sku }, ...(TRADE ? [{ k: 'owner', t: '物权', f: r => ownerName(r.owner) }] : []),
            { k: 'need', t: '应领', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'issued', t: '已领', c: 'num', f: r => fq(r.issued) }, { k: 'suggest', t: '还差', c: 'num' }, { k: 'avail', t: '物料仓库存', c: 'num', f: r => fq(r.avail) }]">
            <template #suggest="{ r }"><span :class="{ amber: r.suggest > 0 }">{{ r.suggest > 0 ? fq(r.suggest) : '领齐' }}</span></template></Tbl>
          <div class="sec"><h3>领料出库、退料入库单</h3><Tbl inset flat :rows="docs" link @row="d => go(stockNoLink(d.no))" empty="还没领过料" :cols="[{ k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'flow', t: '出仓 → 入仓', f: d => whName(d.from) + ' → ' + whName(d.to) }, { k: 'n', t: '物料', f: d => d.lines.length + ' 种' }, { k: 'status', t: '状态' }]"><template #status="{ r }"><St :s="r.status"/></template></Tbl></div>
        </section>
        <section v-if="tab === 'issues'" data-c="异常">
          <Tbl inset flat :rows="x.issues" empty="没有异常" :cols="[{ k: 'type', t: '类型' }, { k: 'desc', t: '描述' }, { k: 'at', t: '上报', f: i => fd(i.at) + ' ' + userName(i.by) }, { k: 'newDate', t: '新交期', f: i => fd(i.newDate) }, { k: 'party', t: '责任方', f: i => i.party || '待定' }, { k: 'hd', t: '处理人' }, { k: 'measure', t: '处理措施', f: i => i.measure || '—' }, { k: 'status', t: '状态' }]">
            <template #desc="{ r }"><span class="wrap">{{ r.desc }}</span><Pics v-if="r.photos && r.photos.length" :model-value="r.photos" readonly :s="36" :limit="3" style="margin-top:4px"/></template>
            <template #hd="{ r }"><template v-if="r.closedBy || r.handler">{{ userName(r.closedBy || r.handler) }}<div class="dim" style="font-size:12px">{{ r.closedAt ? fd(r.closedAt) + ' 关闭' : fd(r.startAt) + ' 开始' }}</div></template><span v-else class="dim">—</span></template>
            <template #status="{ r }"><span class="row"><St :s="r.status"/><button v-if="r.status === '待处理'" class="btn sm" @click="start(r)">开始处理</button><button v-if="r.status !== '已关闭'" class="btn sm" @click="closing = { i: r, measure: '', party: r.party || '' }">关闭</button></span></template>
          </Tbl>
          <button v-if="prodLive(x)" class="btn ghost" style="margin-top:10px" @click="dlg = 'issue'"><Ico n="plus" :s="14"/>上报异常</button>
        </section>
        <section v-if="tab === 'rets'" data-c="回货">
          <Tbl inset flat :rows="rets" link @row="r => viewing = r" empty="还没回货" :cols="[{ k: 'no', t: '回货单号', c: 'code' }, { k: 'date', t: '日期', f: r => fd(r.date) }, { k: 'qty', t: '回货', c: 'num', f: r => sum(r.lines, l => l.qty) }, { k: 'def', t: '次品', c: 'num', f: r => sum(r.lines, l => l.def) },
            { k: 'wh', t: '收货仓', f: r => whName(r.wh) }, { k: 'by', t: '登记人', f: r => r.by ? userName(r.by) : '—' }, { k: 'logistics', t: '物流单号', c: 'code', f: r => r.logistics || '—' }, { k: 'photos', t: '照片' }, { k: 'status', t: '状态' }]">
            <template #photos="{ r }"><Pics v-if="r.photos && r.photos.length" :model-value="r.photos" readonly :s="32" :limit="3" @click.stop/><span v-else class="dim">—</span></template>
            <template #status="{ r }"><St :s="r.status || '已登记'"/></template></Tbl>
        </section>
      </div>
      <aside class="aside" data-c="生产单属性">
        <Gallery :code="x.style" :colors="colors" :owner="style(x.style)"/>
        <dl class="props">
          <dt>需求订单</dt><dd><a class="code" :href="'#/orders/' + x.order">{{ x.order }}</a></dd>
          <dt>资料包</dt><dd>V{{ x.ver }}</dd>
          <dt>加工厂</dt><dd><a :href="'#/suppliers/' + x.factory">{{ sup(x.factory).name }}</a></dd>
          <dt>加工方式</dt><dd>{{ x.mode }}</dd>
          <dt>{{ prodKindOf(x).price }}</dt><dd>{{ fm(x.price) }} / 件<div v-if="x.priceWhy" class="amber" style="font-size:12px" title="和价格档案现价不一样，改价原因">改价：{{ x.priceWhy }}</div></dd>
          <dt>金额</dt><dd>{{ fm(prodAmt(x)) }}</dd>
          <dt>下单日期</dt><dd>{{ fd(x.date) }}</dd>
          <dt>交期</dt><dd>{{ fd(x.due) }}<span v-if="x.expect" class="amber"> → {{ fd(x.expect) }}</span></dd>
          <dt>生产跟单</dt><dd>{{ userName(x.merch) }}</dd>
          <dt>结算</dt><dd>{{ x.settle }}</dd>
          <template v-if="prodLive(x) || x.status === '已完成'">
            <dt>当前节点</dt><dd>{{ curNode(x) ? curNode(x).name : '都已完成' }}</dd>
            <dt>齐料</dt><dd>{{ kitState(order(x.order)).t }}</dd>
            <dt>领料</dt><dd>{{ issueState(x) }}</dd>
            <dt>回货</dt><dd>{{ prodRet(x) }} / {{ prodQty(x) }}<span v-if="prodDef(x)" class="dim"> · 次品 {{ prodDef(x) }}</span></dd>
          </template>
          <dt>条款</dt><dd class="wrap">{{ x.terms || '—' }}</dd>
          <DocMeta :d="x"/>
        </dl>
        <ApproveSteps kind="production" :d="x"/>
        <DocFiles :doc="x"/>
        <DocContracts kind="production" :d="x"/>
        <h4>操作记录</h4><Log :items="x.log"/>
      </aside>
    </div>
    <ProdReport v-if="dlg === 'report'" :p="x" :i="node" @close="dlg = ''"/>
    <ProdIssueMat v-if="dlg === 'issueMat'" :p="x" @close="dlg = ''" @done="tab = 'mat'"/>
    <ProdException v-if="dlg === 'issue'" :p="x" @close="dlg = ''" @done="tab = 'issues'"/>
    <ProdReturn v-if="dlg === 'return'" :p="x" @close="dlg = ''" @done="tab = 'rets'"/>
    <ProdEdit v-if="dlg === 'edit'" :p="x" @close="dlg = ''"/>
    <ProdPlan v-if="dlg === 'plan'" :p="x" @close="dlg = ''"/>
    <ContractGen v-if="dlg === 'contract'" kind="production" :d="x" @close="dlg = ''"/>
    <StockDocForm v-if="dlg === 'back'" type="退料入库" :init="backInit" @close="dlg = ''"/>
    <Modal v-if="closing" :t="'关闭异常：' + closing.i.type" :w="480" @close="closing = null">
      <div class="form">
        <label class="f full">处理措施<textarea class="inp" v-model="closing.measure" placeholder="比如：供应商补发 30 米，11-08 到；返工 20 件"></textarea></label>
        <label class="f">责任方<Pick class="sel" v-model="closing.party"><option value="">待定</option><option v-for="v in PARTIES" :key="v">{{ v }}</option></Pick></label>
      </div>
      <template #foot><button class="btn" @click="closing = null">取消</button><button class="btn pri" @click="doClose">关闭异常</button></template>
    </Modal>
    <Modal v-if="viewing" :t="'回货单 ' + viewing.no" :w="640" @close="viewing = null">
      <div class="sub" style="margin:0 0 10px"><span>{{ fd(viewing.date) }}</span><span>收货仓 {{ whName(viewing.wh) }}</span><span>登记 {{ viewing.by ? userName(viewing.by) : '—' }}</span><span v-if="viewing.logistics">物流 {{ viewing.logistics }}</span><St :s="viewing.status || '已登记'"/></div>
      <Tbl inset flat :rows="viewing.lines" :cols="[{ k: 'color', t: '颜色', f: l => colorName(l.color) + ' ' + l.color }, { k: 'size', t: '尺码' }, { k: 'qty', t: '回货', c: 'num' }, { k: 'def', t: '次品', c: 'num' }, { k: 'good', t: '良品', c: 'num', f: l => l.qty - l.def }, { k: 'note', t: '备注', f: l => l.note || '—' }]">
        <template #foot><tr><td>合计</td><td></td><td class="num">{{ sum(viewing.lines, l => l.qty) }}</td><td class="num">{{ sum(viewing.lines, l => l.def) }}</td><td class="num">{{ sum(viewing.lines, l => l.qty - l.def) }}</td><td></td></tr></template></Tbl>
      <Pics v-if="viewing.photos && viewing.photos.length" :model-value="viewing.photos" readonly :s="56" style="margin-top:10px"/>
      <div v-if="viewing.status === '已作废'" class="banner red" style="margin:10px 0 0"><Ico n="alert"/>{{ viewing.log[viewing.log.length - 1].who }} {{ viewing.log[viewing.log.length - 1].text }}</div>
      <template #foot><button v-if="viewing.status !== '已作废'" class="btn danger" @click="voidRet">作废这张回货单</button><span class="sp"></span><button class="btn" @click="viewing = null">关闭</button></template>
    </Modal>
    <PrintSheet v-if="printing" :t="prodKind(x)" :no="x.no" :company="x.company" @close="printing = false">
      <template #bar><label class="chk" :title="db.craftStd.files.length ? '设置 › 工艺标准里的文件，接在工艺单后面' : '设置 › 工艺标准里还没传文件'"><input type="checkbox" v-model="printApx">附工艺标准</label></template>
      <dl class="sheet-meta">
        <div><dt>加工厂</dt><dd>{{ sup(x.factory).name }}</dd></div><div><dt>联系人</dt><dd>{{ sup(x.factory).contact || '—' }} {{ sup(x.factory).phone || '' }}</dd></div><div><dt>加工方式</dt><dd>{{ x.mode }}</dd></div>
        <div><dt>款号</dt><dd>{{ x.style }} {{ styleName(x.style) }}</dd></div><div><dt>需求订单</dt><dd>{{ x.order }}</dd></div><div><dt>资料包</dt><dd>V{{ x.ver }}</dd></div>
        <div><dt>下单日期</dt><dd>{{ x.date }}</dd></div><div><dt>交期</dt><dd>{{ expectDue(x) }}</dd></div><div><dt>生产跟单</dt><dd>{{ userName(x.merch) }}</dd></div>
        <div><dt>{{ prodKindOf(x).price }}</dt><dd>{{ fm(x.price) }} / 件</dd></div><div><dt>数量</dt><dd>{{ prodQty(x) }} 件</dd></div><div><dt>金额</dt><dd>{{ fm(prodAmt(x)) }}（{{ x.settle }}）</dd></div>
      </dl>
      <table class="sheet-tbl"><thead><tr><th>颜色</th><th v-for="z in sizes" :key="z" class="num">{{ z }}</th><th class="num">合计</th></tr></thead>
        <tbody><tr v-for="c in colors" :key="c"><td>{{ colorName(c) }} {{ c }}</td><td v-for="z in sizes" :key="z" class="num">{{ qtyMap[c + '|' + z] || '' }}</td><td class="num">{{ sum(x.lines.filter(l => l.color === c), l => l.qty) }}</td></tr></tbody>
        <tfoot><tr><td>合计</td><td v-for="z in sizes" :key="z" class="num">{{ sum(x.lines.filter(l => l.size === z), l => l.qty) }}</td><td class="num">{{ prodQty(x) }}</td></tr></tfoot></table>
      <p class="sheet-note">条款：{{ x.terms || '—' }}<br>备注：{{ x.note || '—' }}</p>
      <div class="sheet-sign"><span>制单：{{ creatorOf(x) || '' }}</span><span>审核：</span><span>加工厂确认（签字盖章）：</span></div>
      <!-- 工艺单：这张单锁定的资料包版本，不带价格（甲方 11-14） -->
      <div v-if="tpOf(x.style, x.ver)" class="sheet-apx" data-c="打印：附工艺单"><TpPrintBody :tp="tpOf(x.style, x.ver)" :colors="colors" :appendix="printApx" bare/></div>
    </PrintSheet>
  </div><NotFound v-else/>`,
}
route('/production/:no', PROD_DETAIL, { mod: 'production', t: (p) => '外发加工单 ' + p.no })
route('/fp-purchases/:no', PROD_DETAIL, { mod: 'fpurchases', t: (p) => '成品采购单 ' + p.no })

comp('ProdEdit', {
  props: { p: Object },
  emits: ['close'],
  setup(props, { emit }) {
    const f = reactive(Object.fromEntries(PROD_FIELDS.map(([k]) => [k, props.p[k] ?? ''])))
    const factories = computed(() => db.suppliers.filter((s) => isFactory(s) && (s.status === '合作中' || s.code === f.factory)))
    const setFactory = () => { f.settle = sup(f.factory)?.settle || f.settle }
    const addTerms = () => { f.terms = f.terms ? f.terms + '；' + TERMS_TPL : TERMS_TPL }
    function ok() { if (run(() => updateProduction(props.p, { ...f }))) { emit('close'); toast('已保存') } }
    // 加工价现价（价格档案：工厂 × 款式 × 加工方式）；改得和它不一样要写改价原因
    const ref0 = computed(() => curProcPrice(f.factory, props.p.style, f.mode, props.p.date))
    const off = computed(() => ref0.value != null && f.price !== '' && f.price != null && !samePrice(ref0.value, f.price))
    return { f, factories, setFactory, addTerms, ok, ref0, off }
  },
  template: `<Modal :t="'改' + prodKind(p) + ' ' + p.no" :w="640" @close="$emit('close')">
    <div class="form" data-c="生产单单头">
      <label class="f">主体公司<CompanyPick v-model="f.company"/></label>
      <label class="f">加工厂<Pick class="sel" v-model="f.factory" @change="setFactory"><option v-for="s in factories" :key="s.code" :value="s.code" :disabled="s.status !== '合作中'">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></Pick></label>
      <label class="f">加工方式<Pick class="sel" v-model="f.mode" :disabled="prodKindOf(p).modes.length < 2" :title="'种类跟着单号走，' + prodKind(p) + '只能选 ' + prodKindOf(p).modes.join('、')"><option v-for="v in prodKindOf(p).modes" :key="v">{{ v }}</option></Pick></label>
      <label class="f">{{ prodKindOf(p).price }}（元 / 件）<input class="inp num" type="number" v-model.number="f.price"><small class="dim">{{ ref0 != null ? '档案现价 ' + fm(ref0) : '档案里没有这家 × ' + f.mode + ' 的价，审核时记进去' }}</small></label>
      <label class="f">交期<DatePick v-model="f.due"/></label>
      <label v-if="off || f.priceWhy" class="f full">改价原因<input class="inp" v-model="f.priceWhy" placeholder="单价和档案现价不一样，写清楚为什么；审核时记进价格档案"></label>
      <label class="f">生产跟单<Pick class="sel" v-model="f.merch"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></Pick></label>
      <label class="f">结算方式<Pick class="sel" v-model="f.settle"><option v-for="v in db.dicts.结算方式" :key="v">{{ v }}</option></Pick></label>
      <div class="f full">条款<textarea class="inp" v-model="f.terms" placeholder="次品扣款、延期扣款、送货要求"></textarea><a class="dim" style="font-size:12px;cursor:pointer;align-self:flex-start" @click="addTerms">填常用条款</a></div>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">保存</button></template>
  </Modal>`,
})

comp('ProdPlan', {
  props: { p: Object },
  emits: ['close'],
  setup(props, { emit }) {
    const rows = reactive(props.p.nodes.map((n) => ({ name: n.name, ps: n.ps, pf: n.pf, af: n.af })))
    function ok() { if (run(() => updateNodePlan(props.p, rows.map((r) => ({ ps: r.ps, pf: r.pf }))))) { emit('close'); toast('节点计划改好了') } }
    return { rows, ok }
  },
  template: `<Modal :t="'改节点计划 ' + p.no" :w="580" @close="$emit('close')">
    <table class="tbl inset flat" data-c="节点计划"><thead><tr><th>节点</th><th style="width:150px">计划开始</th><th style="width:150px">计划完成</th><th>实际完成</th></tr></thead>
      <tbody><tr v-for="r in rows" :key="r.name"><td>{{ r.name }}</td><td><DatePick cell v-model="r.ps"/></td><td><DatePick cell v-model="r.pf"/></td><td class="dim">{{ r.af ? fd(r.af) : '—' }}</td></tr></tbody></table>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">保存</button></template>
  </Modal>`,
})

/* 报进度：电脑（这个弹窗）和手机报工（/mobile）共用 reportInit 出初值、sendReport 提交，规则都在 2-store.js 的 reportNode。
 * 两个按钮：「只报开始」节点进行中；「报完成」记实际完成，完成日期没选就按今天。实际完成不预填，不会一提交就把节点报完成 */
const REPORT_TIP = '还在做点「只报开始」；做完了点「报完成」，完成日期不选就是今天'
function sendReport(p, i, f, kind, tail = '') {
  const w = run(() => reportNode(p, i, { ...f, kind }))
  if (w) toast(`${p.nodes[i].name}已报${kind}${typeof w === 'string' ? '，' + w : ''}${tail}`)
  return !!w
}
comp('ProdReport', {
  props: { p: Object, i: Number },
  emits: ['close'],
  setup(props, { emit }) {
    const cur = props.i ?? Math.max(0, props.p.nodes.findIndex((n) => !n.af))
    const f = reactive({ i: cur, ...reportInit(props.p.nodes[cur]) })
    watch(() => f.i, (i) => Object.assign(f, reportInit(props.p.nodes[i])))
    function ok(kind) { if (sendReport(props.p, f.i, f, kind)) emit('close') }
    return { f, ok, REPORT_TIP }
  },
  template: `<Modal :t="'报进度 ' + p.no" :w="520" @close="$emit('close')">
    <div class="chips" style="margin-bottom:14px" data-c="选节点"><button v-for="(n, k) in p.nodes" :key="n.name" class="chip" :class="{ on: f.i === k }" @click="f.i = k"><Ico v-if="n.af" n="check" :s="12" class="green"/>{{ n.name }}</button></div>
    <p class="dim" style="font-size:12px;margin:-6px 0 12px">计划 {{ fd(p.nodes[f.i].ps) }} – {{ fd(p.nodes[f.i].pf) }}<template v-if="p.nodes[f.i].at">；上次 {{ userName(p.nodes[f.i].by) }} {{ fd(p.nodes[f.i].at) }} 报的</template></p>
    <div class="form">
      <label class="f">实际开始<DatePick v-model="f.as"/></label>
      <label class="f">实际完成<DatePick v-model="f.af"/></label>
      <label class="f">数量（件）<input class="inp num" type="number" v-model.number="f.qty"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
      <div class="f full" data-c="报进度照片">照片<span v-if="p.nodes[f.i].photos && p.nodes[f.i].photos.length" class="dim" style="font-weight:400">（这个节点已经有 {{ p.nodes[f.i].photos.length }} 张，再传是追加）</span><Pics v-model="f.photos" :s="64" camera/></div>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button>
      <button class="btn" :disabled="!!p.nodes[f.i].af" :title="p.nodes[f.i].af ? '这个节点已经报过完成' : null" @click="ok('开始')">只报开始</button><button class="btn pri" @click="ok('完成')">报完成</button></template>
  </Modal>`,
})

comp('ProdIssueMat', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const whs = db.warehouses.filter((w) => w.type === '物料仓' && w.status !== '停用')
    const f = reactive({ from: (whs.find((w) => w.code === 'WL01') || whs[0])?.code, reason: '' })
    const have = (r) => stockQty(f.from, r.sku, r.owner)
    const rows = reactive(issuePlan(props.p).map((r) => ({ ...r, qty: 0 })))
    // 默认：从选的仓有多少领多少，不超过还差的
    const fill = () => rows.forEach((r) => { r.qty = Math.min(r.suggest, Math.max(0, have(r))) })
    fill()
    watch(() => f.from, fill)
    const isOver = (r) => r.qty > r.suggest + 0.0005
    const over = computed(() => rows.some(isOver))
    function ok() {
      if (over.value && !f.reason.trim()) return toast('超领要写原因', null, true)
      const d = run(() => issueMaterials(props.p, rows.map((r) => ({ sku: r.sku, owner: r.owner, qty: +r.qty || 0, reason: isOver(r) ? f.reason.trim() : '' })), f.from))
      if (d?.no) { emit('close'); emit('done'); toast(`${d.no} 已过账：${whName(d.from)} → ${whName(d.to)}`, ['打开', stockNoLink(d.no)]) }
    }
    return { whs, f, have, rows, over, ok }
  },
  template: `<Modal :t="'领料出库 ' + p.no" :w="780" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr);margin-bottom:12px">
      <label class="f">从哪个物料仓发<Pick class="sel" v-model="f.from"><option v-for="w in whs" :key="w.code" :value="w.code">{{ w.name }}</option></Pick></label>
      <div class="f">发到<span style="line-height:32px">{{ whName(factoryWh(p.factory)?.code) }}</span></div>
    </div>
    <table class="tbl inset flat" data-c="领料明细"><thead><tr><th>物料</th><th v-if="TRADE">物权</th><th class="num">应领</th><th class="num">已领</th><th class="num">还差</th><th class="num">{{ whName(f.from) }}库存</th><th class="num" style="width:110px">本次领</th></tr></thead>
      <tbody><tr v-for="r in rows" :key="r.sku + r.owner"><td><span class="row"><Thumb :code="r.sku" :s="30"/><span class="col"><span>{{ skuName(r.sku) }}</span><small class="code dim">{{ r.sku }}</small></span></span></td><td v-if="TRADE" :class="{ amber: r.owner !== '自有' }">{{ ownerName(r.owner) }}</td>
        <td class="num">{{ fq(r.need) }}</td><td class="num">{{ fq(r.issued) }}</td><td class="num">{{ fq(r.suggest) }} <span class="dim">{{ unitOf(r.sku) }}</span></td>
        <td class="num" :class="{ red: have(r) < r.qty }">{{ fq(have(r)) }}</td><td><input class="inp cell num" type="number" v-model.number="r.qty"></td></tr>
        <tr v-if="!rows.length"><td :colspan="TRADE ? 7 : 6" class="empty">{{ prodKind(p) === '成品采购单' ? '资料包里没有标「我方采购」的料，成品采购单不用领料' : '资料包里没有要发给工厂的料' }}</td></tr></tbody></table>
    <label v-if="over" class="f" style="margin-top:12px">超领原因<input class="inp" v-model="f.reason" placeholder="比如：裁剪损耗大，补 5 米"></label>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">领料并过账</button></template>
  </Modal>`,
})

comp('ProdException', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const f = reactive({ type: '缺料', desc: '', impact: false, newDate: addDays(expectDue(props.p), 5), party: PARTY0, photos: [] })
    function ok() { if (run(() => reportIssue(props.p, { ...f, newDate: f.impact ? f.newDate : null }))) { emit('close'); emit('done'); toast('异常已上报') } }
    return { f, ok, PARTIES }
  },
  template: `<Modal :t="'上报异常 ' + p.no" :w="520" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<Pick class="sel" v-model="f.type"><option v-for="x in db.dicts.异常类型" :key="x">{{ x }}</option></Pick></label>
      <label class="f">责任方<Pick class="sel" v-model="f.party"><option value="">待定</option><option v-for="v in PARTIES" :key="v">{{ v }}</option></Pick></label>
      <label class="f full">描述<textarea class="inp" v-model="f.desc" placeholder="什么问题、多少件、哪个颜色尺码"></textarea></label>
      <div class="f full" data-c="异常照片">照片<span class="red" style="font-weight:400">（必须传：问题部位、到货短缺或现场，对责任时要用）</span><Pics v-model="f.photos" :s="64" camera/></div>
      <label class="chk"><input type="checkbox" v-model="f.impact">影响交期</label>
      <label v-if="f.impact" class="f">预计新交期<DatePick v-model="f.newDate"/></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">上报</button></template>
  </Modal>`,
})

comp('ProdReturn', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const qty = ref(Object.fromEntries(props.p.lines.map((l) => [l.color + '|' + l.size, Math.max(0, l.qty - l.ret)])))
    const def = ref({}), note = reactive({})
    const fgs = db.warehouses.filter((w) => w.type === '成品仓' && w.status !== '停用')
    const f = reactive({ date: clock.today, wh: (fgs.find((w) => w.code === 'CP01') || fgs[0])?.code, logistics: '', photos: [] })
    const colors = [...new Set(props.p.lines.map((l) => l.color))]
    const sub = (c, z) => { const l = props.p.lines.find((y) => y.color === c && y.size === z); return l ? `加工 ${l.qty} · 已回 ${l.ret}` : '' }
    const got = computed(() => props.p.lines.filter((l) => +qty.value[l.color + '|' + l.size] > 0))
    function ok() {
      const lines = Object.fromEntries(Object.keys({ ...qty.value, ...def.value }).map((k) => [k, { qty: qty.value[k] || 0, def: def.value[k] || 0, note: (note[k] || '').trim() }]))
      const r = run(() => registerReturn(props.p, { ...f, lines }))
      if (r?.no) { emit('close'); emit('done'); toast(`${r.no} 已登记：${sum(r.lines, (l) => l.qty)} 件${props.p.status === '已完成' ? '，' + prodKind(props.p) + '完成' : ''}`) }
    }
    return { qty, def, note, fgs, f, colors, sub, got, ok }
  },
  template: `<Modal :t="'登记回货 ' + p.no" :w="760" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr);margin-bottom:14px">
      <label class="f">回货日期<DatePick v-model="f.date" :max="clock.today"/></label>
      <label class="f">收货仓<Pick class="sel" v-model="f.wh"><option v-for="w in fgs" :key="w.code" :value="w.code">{{ w.name }}</option></Pick></label>
      <label class="f">物流单号<input class="inp" v-model="f.logistics"></label></div>
    <div class="sec" style="margin-top:0" data-c="回货数"><h3>回货数（含次品）</h3><Matrix :st="p.style" v-model="qty" :colors="colors" :sub="sub"/></div>
    <div class="sec" data-c="次品数"><h3>其中次品</h3><Matrix :st="p.style" v-model="def" :colors="colors"/></div>
    <details v-if="got.length" class="sec ret-notes" data-c="回货备注"><summary>分色分码备注</summary>
      <table class="tbl inset flat"><tbody><tr v-for="l in got" :key="l.color + l.size"><td style="width:150px">{{ colorName(l.color) }} · {{ l.size }}</td><td class="num" style="width:70px">{{ qty[l.color + '|' + l.size] }} 件</td><td><input class="inp cell" v-model="note[l.color + '|' + l.size]"></td></tr></tbody></table></details>
    <div class="sec" data-c="回货照片"><h3>照片</h3><Pics v-model="f.photos" :s="64"/></div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">登记</button></template>
  </Modal>`,
})

/* ---------- 生产看板 ---------- */
route('/board', {
  setup() {
    // 首页点数字、品类环形图进来：card = 哪张数字卡，cat = 哪个品类的款
    const s = reactive({ card: nav.query.card || '', view: '按单', brand: '', cat: nav.query.cat || '', kind: '', flt: newFlt() })
    // 按工厂视图不按加工厂筛（点一行再筛进去）
    const base = computed(() => db.productions.filter(prodLive).filter((p) => passFlt(s.flt, { date: expectDue(p), merch: p.merch }) && (!s.brand || brandOf(p) === s.brand) && (!s.cat || style(p.style)?.cat === s.cat) && (!s.kind || prodKind(p) === s.kind)))
    const live = computed(() => base.value.filter((p) => !s.flt.party || p.factory === s.flt.party))
    const month = clock.today.slice(0, 7)
    const brands = computed(() => [...new Set([...(db.dicts.品牌 || []), ...db.productions.map(brandOf).filter(Boolean)])])
    const cards = computed(() => {
      const L = live.value
      const run = L.filter((p) => ['生产中', '部分回货'].includes(p.status))
      const rets = db.returns.filter((r) => r.status !== '已作废' && r.date.startsWith(month) && (!s.flt.party || r.factory === s.flt.party))
      return [
        { k: 'run', t: '在制', n: run.length, m: sum(run, (p) => prodQty(p) - prodRet(p)) + ' 件', f: (p) => ['生产中', '部分回货'].includes(p.status) },
        { k: 'wait', t: '待开工', n: L.filter((p) => p.status === '已下单').length, f: (p) => p.status === '已下单' },
        { k: 'near', t: '7 天内交货', n: L.filter((p) => prodRisk(p).near).length, f: (p) => prodRisk(p).near },
        { k: 'late', t: '已逾期', n: L.filter((p) => prodRisk(p).late).length, f: (p) => prodRisk(p).late },
        { k: 'node', t: '节点延期', n: L.filter((p) => prodRisk(p).node).length, f: (p) => prodRisk(p).node },
        { k: 'issue', t: '有异常', n: L.filter((p) => prodRisk(p).issue).length, f: (p) => prodRisk(p).issue },
        { k: 'done', t: '本月回货', n: sum(rets, (r) => sum(r.lines, (l) => l.qty)), m: '件', f: null },
      ]
    })
    const rows = computed(() => { const c = cards.value.find((x) => x.k === s.card); return live.value.filter((p) => !c?.f || c.f(p)).slice().sort(byRisk) })
    const factories = computed(() => db.suppliers.filter(isFactory).map((f) => { const ps = base.value.filter((p) => p.factory === f.code); return { f, n: ps.length, qty: sum(ps, (p) => prodQty(p) - prodRet(p)), late: ps.filter((p) => prodRisk(p).late).length } }).filter((x) => x.n || x.f.status === '合作中'))
    const maxQty = computed(() => Math.max(1, ...factories.value.map((x) => x.qty)))
    return { s, brands, cards, rows, factories, maxQty }
  },
  template: `<div>
    <Head t="看板"/>
    <div class="bar" data-c="筛选：交期、加工厂、跟单、品牌"><Filters v-model="s.flt" party="factory" merch label="交期"/>
      <Pick class="sel" style="height:28px" v-model="s.brand"><option value="">全部品牌</option><option v-for="b in brands" :key="b" :value="b">{{ b }}</option></Pick>
      <Pick class="sel" style="height:28px" v-model="s.kind" data-c="看板：按种类"><option value="">外发加工、成品采购</option><option v-for="(k, n) in PROD_KINDS" :key="n" :value="n">只看{{ n }}</option></Pick>
      <span v-if="s.cat" class="fchip">{{ catOf(s.cat).name }}<button type="button" title="不按品类筛" @click="s.cat = ''"><Ico n="x" :s="11"/></button></span></div>
    <div class="stats" data-c="数字卡">
      <div v-for="c in cards" :key="c.k" class="stat" :class="{ on: s.card === c.k }" @click="c.f && (s.card = s.card === c.k ? '' : c.k)"><span>{{ c.t }}</span><b :class="{ red: (c.k === 'late' || c.k === 'issue') && c.n, amber: (c.k === 'near' || c.k === 'node') && c.n }">{{ c.n.toLocaleString() }}<small v-if="c.m">{{ c.m }}</small></b></div>
    </div>
    <div class="bar"><Seg v-model="s.view" :items="['按单', '按工厂']"/></div>
    <div v-if="s.view === '按单'" data-c="看板列表"><ProdTable :rows="rows" board/></div>
    <div v-else data-c="按工厂">
      <Tbl :rows="factories" link @row="x => { s.flt.party = x.f.code; s.view = '按单' }" :cols="[{ k: 'f', t: '加工厂', f: x => x.f.short }, { k: 'cat', t: '类型', f: x => x.f.cats.join(' ') }, { k: 'n', t: '在制单数', c: 'num' }, { k: 'qty', t: '在制件数', c: 'num' }, { k: 'bar', t: '', w: '30%' }, { k: 'late', t: '逾期单', c: 'num' }]">
        <template #bar="{ r }"><span class="hbar"><b :style="{ width: (r.qty / maxQty * 100) + '%' }"></b></span></template>
        <template #late="{ r }"><span :class="{ red: r.late }">{{ r.late }}</span></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'board', t: '看板' })

/* ---------- 手机报工（跟单员；工厂视角要外部账号，三期） ---------- */
route('/mobile', {
  setup() {
    const s = reactive({ who: '跟单员', factory: 'GYS0004', no: null, node: null, issue: false, f: { as: '', af: '', qty: null, note: '', photos: [] },
      e: { type: '缺料', desc: '', impact: false, newDate: '', party: PARTY0, photos: [] } })
    const list = computed(() => db.productions.filter(prodLive).filter((p) => (s.who === '跟单员' ? p.merch === 'u1' : p.factory === s.factory)).slice().sort(byRisk))
    const p = computed(() => production(s.no))
    const openIs = computed(() => (p.value ? p.value.issues.filter((i) => i.status !== '已关闭') : []))
    // 和电脑上的「报进度」同一套：reportInit 出初值（实际完成不预填），sendReport → reportNode
    function pickNode(i) { s.node = i; Object.assign(s.f, reportInit(p.value.nodes[i])) }
    function submit(kind) { if (sendReport(p.value, s.node, s.f, kind, '，电脑上马上能看到')) s.node = null }
    // 上报异常（PRD §6.7.3 第 3 条）：和电脑上同一套规则，照片必拍
    function openIssue() { Object.assign(s.e, { type: '缺料', desc: '', impact: false, newDate: addDays(expectDue(p.value), 5), party: PARTY0, photos: [] }); s.issue = true }
    function submitIssue() { if (run(() => reportIssue(p.value, { ...s.e, newDate: s.e.impact ? s.e.newDate : null }))) { toast('异常已上报，电脑上马上能看到'); s.issue = false } }
    const back = () => { if (s.node != null) s.node = null; else if (s.issue) s.issue = false; else s.no = null }
    return { s, list, p, openIs, pickNode, submit, openIssue, submitIssue, back, PARTIES, REPORT_TIP }
  },
  template: `<div>
    <Head t="手机报工"><Seg v-model="s.who" :items="['跟单员', '工厂']"/><select v-if="s.who === '工厂'" class="sel" style="height:28px" v-model="s.factory"><option v-for="f in db.suppliers.filter(f => isFactory(f) && f.status === '合作中')" :key="f.code" :value="f.code">{{ f.short }}</option></select></Head>
    <div class="phone" data-c="手机">
      <div class="scr">
        <template v-if="!p">
          <div class="ph">{{ s.who === '跟单员' ? '我的跟单' : supName(s.factory) }}<span style="flex:1"></span><span class="dim" style="font-size:12px;font-weight:400">{{ s.who === '跟单员' ? '陈晓' : '外部账号' }}</span></div>
          <div v-for="x in list" :key="x.no" class="pi" @click="s.no = x.no">
            <Thumb :code="x.style" :s="52"/>
            <div class="col" style="flex:1;min-width:0"><div class="row"><b class="code">{{ x.style }}</b><span style="flex:1"></span><span class="dim" style="font-size:12px">交期 {{ fd(expectDue(x)) }}</span><Days :d="expectDue(x)" :warn="7"/></div>
              <span class="mut">{{ supName(x.factory) }} · {{ prodQty(x) }} 件</span><span class="row"><Nodes :p="x"/><span class="dim" style="font-size:12px">{{ curNode(x)?.name || '待入仓' }}</span><Ico v-if="prodRisk(x).issue" n="alert" :s="13" class="red"/></span></div>
          </div>
          <div v-if="!list.length" class="empty">没有在做的单</div>
        </template>
        <template v-else>
          <div class="ph"><button class="btn ghost icon" @click="back"><Ico n="left"/></button>{{ s.issue ? '上报异常' : s.node == null ? p.style : p.nodes[s.node].name }}</div>
          <div v-if="s.issue" class="col m-form" data-c="手机上报异常">
            <label class="f">类型<select class="sel" v-model="s.e.type"><option v-for="x in db.dicts.异常类型" :key="x">{{ x }}</option></select></label>
            <label class="f">描述<textarea class="inp" v-model="s.e.desc" placeholder="什么问题、多少件、哪个颜色尺码"></textarea></label>
            <div class="f">照片<span class="red" style="font-weight:400">（必须拍）</span><Pics v-model="s.e.photos" :s="60" camera bare/></div>
            <label class="f">责任方<select class="sel" v-model="s.e.party"><option value="">待定</option><option v-for="v in PARTIES" :key="v">{{ v }}</option></select></label>
            <label class="chk"><input type="checkbox" v-model="s.e.impact">影响交期</label>
            <label v-if="s.e.impact" class="f">预计新交期<DatePick v-model="s.e.newDate"/></label>
            <button class="btn pri" style="height:44px" @click="submitIssue">上报</button>
          </div>
          <template v-else-if="s.node == null">
            <div style="padding:14px 16px" class="col"><span>{{ styleName(p.style) }} · {{ supName(p.factory) }}</span><span class="mut">{{ prodQty(p) }} 件 · 交期 {{ fd(expectDue(p)) }}</span></div>
            <div class="nodes-big"><button v-for="(n, i) in p.nodes" :key="n.name" :class="[nodeState(n) === 'late' ? 'late' : n.af ? 'done' : curNode(p) === n ? 'on' : '']" @click="pickNode(i)">{{ n.name }}<small>{{ n.af ? '完成 ' + fd(n.af) : n.as ? '进行中' : '计划 ' + fd(n.pf) }}</small></button></div>
            <div v-if="openIs.length" class="m-issues" data-c="未关闭异常"><div v-for="i in openIs" :key="i.id"><b class="red">{{ i.type }}</b> {{ i.desc }}<span class="dim">　{{ i.status }}</span></div></div>
            <div class="col" style="padding:0 16px 16px;gap:8px"><button class="btn" style="width:100%;height:40px" @click="openIssue"><Ico n="alert" :s="14"/>上报异常</button><a class="btn" style="width:100%" :href="'#' + prodLink(p)">在电脑上打开</a></div>
          </template>
          <div v-else class="col m-form">
            <label class="f">实际开始<DatePick v-model="s.f.as"/></label>
            <label class="f">实际完成<DatePick v-model="s.f.af"/></label>
            <label class="f">数量（件）<input class="inp num" type="number" v-model.number="s.f.qty"></label>
            <div class="f">照片<Pics v-model="s.f.photos" :s="60" camera bare/></div>
            <label class="f">备注<textarea class="inp" v-model="s.f.note"></textarea></label>
            <div class="row g8" data-c="手机报工按钮"><button class="btn" style="flex:1;height:44px" :disabled="!!p.nodes[s.node].af" @click="submit('开始')">只报开始</button><button class="btn pri" style="flex:1;height:44px" @click="submit('完成')">报完成</button></div>
          </div>
        </template>
      </div>
    </div>
  </div>`,
}, { mod: 'mobile', t: '手机报工' })

expose({ reportNode, issueMaterials, closeIssue, factoryWh, daysLeft2: (a, b) => diffDays(a, b), updateProduction, updateNodePlan, startIssue, voidReturn })
