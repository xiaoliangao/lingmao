/* ============================================================
 * 资料：供应商、客户、物料、基础（颜色尺码、品类、仓库、主体、字典）；系统：设置
 * 改数据的函数在上面，页面在下面。供应商的准入 / 停用 / 拉黑走 2-store.js 的 FLOWS.supplier。
 * ============================================================ */

/* ---------- 供应商 ---------- */
// 证照：最早到期的那份；过期红、30 天内黄
function certInfo(s) {
  if (!s.certs.length) return { t: '—', tone: 'dim' }
  const left = Math.min(...s.certs.filter((c) => c.until).map((c) => daysLeft(c.until)))
  if (left < 0) return { t: '有证照已过期', tone: 'red' }
  if (left <= 30) return { t: left + ' 天后到期', tone: 'amber' }
  return { t: s.certs.length + ' 份', tone: 'dim' }
}
function addCert(s, f) {
  if (!f.type || !f.until) fail('类型、有效期都要填')
  if (!f.file) fail('要传证照的照片或扫描件')
  s.certs.push({ type: f.type, until: f.until, file: f.file })
  act(s, '上传' + f.type + '，有效期至 ' + f.until)
}
function unblockSupplier(s, reason) {
  if (!reason) fail('要写解除原因')
  move('supplier', s, '解除黑名单', reason)
  s.blackReason = ''
}
function supplierDocs(code) {
  return [
    ...db.purchases.filter((p) => p.supplier === code).map((p) => ({ no: p.no, k: '采购单', date: p.date, qty: fq(poQty(p)), amt: poAmt(p), status: p.status, to: '/purchases/' + p.no })),
    ...db.productions.filter((p) => p.factory === code).map((p) => ({ no: p.no, k: '加工单', date: p.date, qty: prodQty(p) + ' 件', amt: prodAmt(p), status: p.status, to: '/production/' + p.no })),
    ...db.stockDocs.filter((d) => d.partner === code).map((d) => ({ no: d.no, k: d.type, date: d.date, qty: d.lines.length + ' 行', amt: null, status: d.status, to: '/stock-docs/' + d.no })),
    ...db.returns.filter((r) => r.factory === code).map((r) => ({ no: r.no, k: '回货', date: r.date, qty: sum(r.lines, (x) => x.qty) + ' 件', amt: null, status: '', to: '/production/' + r.prod })),
  ].sort((a, b) => b.date.localeCompare(a.date))
}
// 绩效（12.04 待定）：准时 = 入库 / 回货日期不晚于交期
function supplierPerf(code) {
  const ins = db.stockDocs.filter((d) => d.type === '采购入库' && d.partner === code && d.status === '已审核')
  const insOk = ins.filter((d) => { const p = purchase(d.src); return !p || d.date <= p.due })
  const rets = db.returns.filter((r) => r.factory === code)
  const retsOk = rets.filter((r) => r.date <= (production(r.prod)?.due || r.date))
  const n = ins.length + rets.length
  const prods = db.productions.filter((p) => p.factory === code && p.status !== '已作废')
  const pos = db.purchases.filter((p) => p.supplier === code && p.status !== '已作废')
  const ret = sum(prods, prodRet), def = sum(prods, prodDef)
  return {
    n, onTime: n ? fpct(insOk.length + retsOk.length, n) : null,
    late: arrivals().filter((a) => a.p.supplier === code && a.risk === 'late').length + prods.filter((p) => prodRisk(p).late).length,
    ret, def, defRate: ret ? rnd((def / ret) * 100, 1) : null,
    amt: rnd(sum(pos, poAmt) + sum(prods, prodAmt), 2), docs: pos.length + prods.length,
    issues: sum(prods, (p) => p.issues.length),
  }
}
// 询价记录（12.06 待定）：先用采购单上的成交价
const priceHistory = (code) => db.purchases.filter((p) => p.supplier === code && p.status !== '已作废')
  .flatMap((p) => p.lines.map((l) => ({ date: p.date, no: p.no, sku: l.sku, price: l.price, qty: l.qty, unit: matOfSku(l.sku)?.purUnit })))
  .sort((a, b) => b.date.localeCompare(a.date))

/* ---------- 客户 ---------- */
function createCustomer(f) {
  if (!f.name || !f.short) fail('全称、简称都要填')
  const code = 'KH' + String(db.customers.length + 1).padStart(4, '0')
  const c = add(db.customers, { contact: '', phone: '', settle: '月结', term: 30, ...f, code, status: '启用', terms: { price: '', deduct: '', rebate: '' }, log: [] })
  act(c, '新建')
  return c
}
const customerStock = (code) => Object.entries(db.stock).map(([k, q]) => { const [w, sku, o] = k.split('|'); return { w, sku, o, q } }).filter((x) => x.o === code && x.q > 0)

/* ---------- 物料 ---------- */
const MAT_TYPES = ['面料', '里料', '辅料', '包材', '标签吊牌']
const matCat = (c) => db.materialCategories.find((x) => x.code === c) || { code: c, name: c, parent: '' }
const nextMatCode = (cat) => cat + String(Math.max(0, ...db.materials.filter((m) => m.code.startsWith(cat)).map((m) => +m.code.slice(cat.length) || 0)) + 1).padStart(4, '0')
const dupMaterial = (f) => db.materials.find((m) => m.cat === f.cat && m.name.trim() === f.name.trim())
function createMaterial(f) {
  if (!f.name) fail('物料名称要填')
  if (!(f.factor > 0)) fail('换算系数要大于 0')
  const code = nextMatCode(f.cat)
  const m = add(db.materials, { purUnit: f.unit, factor: 1, pack: 1, moq: 0, lead: 7, status: '启用', ...f, code, colors: undefined, skus: [], log: [] })
  if (f.colors?.length) addMaterialSkus(m, f.colors, '', true)
  else m.skus.push({ code })
  act(m, '新建，' + m.skus.length + ' 个 SKU')
  return m
}
function addMaterialSkus(m, colors, spec, silent) {
  const made = []
  colors.forEach((c) => {
    const code = [m.code, c, spec].filter(Boolean).join('-')
    if (m.skus.some((k) => k.code === code)) return
    m.skus.push({ code, color: c, spec: spec || undefined })
    made.push(code)
  })
  if (!made.length) fail('这些颜色的 SKU 已经有了')
  if (!silent) act(m, '加 SKU ' + made.join('、'))
  return made
}
function setMaterialStatus(m, st) { m.status = st; act(m, st) }
// 库存：type 不传 = 所有仓
const skuStock = (sku, type) => rnd(sum(Object.entries(db.stock).filter(([k]) => { const [w, s] = k.split('|'); return s === sku && (!type || wh(w)?.type === type) }), ([, v]) => v), 3)
const matStock = (m) => rnd(sum(m.skus, (k) => skuStock(k.code)), 3)
const matUsedBy = (code) => db.techpacks.filter((t) => t.status === '已生效').flatMap((t) => t.bom.filter((r) => r.mat === code).map((r) => ({ style: t.style, ver: t.ver, part: r.part, src: r.src })))

/* ---------- 基础 ---------- */
function addColor(f) {
  const code = (f.code || '').trim().toUpperCase()
  if (!/^[A-Z0-9]{2,3}$/.test(code)) fail('颜色编码是 2-3 位字母或数字')
  if (!f.name) fail('颜色名称要填')
  if (db.colors.some((c) => c.code === code)) fail('编码 ' + code + ' 已经有了')
  if (db.colors.some((c) => c.name === f.name)) fail('颜色 ' + f.name + ' 已经有了')
  db.colors.push({ code, name: f.name, family: f.family, hex: f.hex })
}
const sizeGroupUsed = (id) => db.styles.filter((s) => s.sizeGroup === id).length
function appendSize(g, z) {
  z = (z || '').trim().toUpperCase()
  if (!z) fail('尺码要填')
  if (g.sizes.includes(z)) fail(g.name + ' 里已经有 ' + z)
  g.sizes.push(z)
}
function removeSize(g, z) {
  if (sizeGroupUsed(g.id)) fail('已经有款式用这个尺码组，只能在末尾追加尺码')
  g.sizes.splice(g.sizes.indexOf(z), 1)
}

/* ---------- 设置 ---------- */
const ROLES = ['开发', '业务', '生产跟单', '采购', '仓管', '财务', '管理层']
const PERM_CYCLE = ['—', '看', '改', '审']
Object.assign(db, {
  // PRD §3.1 预置角色模板：模块 × 角色
  perms: [
    { m: '基础资料', v: ['改', '看', '看', '改', '看', '看', '看'] },
    { m: '供应商', v: ['看', '看', '看', '改', '看', '看', '审'] },
    { m: '款式、资料包', v: ['审', '看', '看', '看', '看', '看', '看'] },
    { m: '订单', v: ['看', '改', '看', '看', '—', '看', '审'] },
    { m: '排单、加工单', v: ['看', '看', '改', '看', '看', '看', '审'] },
    { m: '算料、采购单', v: ['—', '看', '看', '改', '看', '看', '审'] },
    { m: '出入库、库存', v: ['—', '—', '看', '看', '审', '看', '看'] },
    { m: '进度、异常、回货', v: ['—', '看', '改', '看', '看', '—', '看'] },
  ],
  approvalRules: [
    { doc: '采购单', over: 50000, role: '管理层' },
    { doc: '加工单', over: 100000, role: '管理层' },
  ],
  notify: { 站内待办: true, 钉钉: false, 企业微信: false, 飞书: false, 邮件: false },
  notifyEvents: { 待我审核: true, 加工单逾期: true, 节点延期: true, 到料逾期: true, 上游订单变更: true, 证照到期: false },
  integration: { jst: '', fin: '' },
})
const AUDITS = [
  { t: '订单', p: 'order:audit', m: '订单', n: () => db.orders.filter((o) => o.status === '待审核').length },
  { t: '订单变更', p: 'change:audit', m: '订单', n: () => db.changes.filter((c) => c.status === '待审核').length },
  { t: '资料包生效', p: 'techpack:publish', m: '款式、资料包', n: () => db.techpacks.filter((t) => t.status === '草稿').length },
  { t: '采购单', p: 'purchase:audit', m: '算料、采购单', n: () => db.purchases.filter((p) => p.status === '待审核').length },
  { t: '加工单', p: 'production:audit', m: '排单、加工单', n: () => db.productions.filter((p) => p.status === '待审核').length },
  { t: '出入库单', p: 'stock:audit', m: '出入库、库存', n: () => db.stockDocs.filter((d) => d.status === '草稿').length },
  { t: '供应商准入、拉黑', p: 'supplier:admit', m: '供应商', n: () => db.suppliers.filter((s) => s.status === '待准入').length },
]
const auditRoles = (m) => { const row = db.perms.find((x) => x.m === m); return ROLES.filter((r, i) => row?.v[i] === '审') }
function cyclePerm(row, i) { row.v[i] = PERM_CYCLE[(PERM_CYCLE.indexOf(row.v[i]) + 1) % PERM_CYCLE.length] }
// 操作日志：各单据的记录汇总，按时间倒序
function allLogs() {
  const src = [
    ['订单', db.orders, (d) => d.no, (d) => '/orders/' + d.no],
    ['变更', db.changes, (d) => d.no, (d) => '/orders/' + d.order],
    ['算料', db.mrps, (d) => d.no, (d) => '/mrp/' + d.no],
    ['采购', db.purchases, (d) => d.no, (d) => '/purchases/' + d.no],
    ['出入库', db.stockDocs, (d) => d.no, (d) => '/stock-docs/' + d.no],
    ['加工单', db.productions, (d) => d.no, (d) => '/production/' + d.no],
    ['回货', db.returns, (d) => d.no, (d) => '/production/' + d.prod],
    ['供应商', db.suppliers, (d) => d.code, (d) => '/suppliers/' + d.code],
    ['客户', db.customers, (d) => d.code, (d) => '/customers/' + d.code],
    ['物料', db.materials, (d) => d.code, (d) => '/materials/' + d.code],
    ['款式', db.styles, (d) => d.code, (d) => '/styles/' + d.code],
    ['资料包', db.techpacks, (d) => d.style + ' V' + d.ver, (d) => '/styles/' + d.style],
  ]
  let n = 0
  return src.flatMap(([k, list, id, to]) => list.flatMap((d) => (d.log || []).map((x) => ({ ...x, k, id: id(d), to: to(d), n: n++ }))))
    .sort((a, b) => b.at.localeCompare(a.at) || b.n - a.n)
}

SEARCH.push(() => [
  ...db.suppliers.map((s) => ({ t: s.code + ' ' + s.short, sub: '供应商 · ' + s.cats.join('、'), to: '/suppliers/' + s.code, i: 'store' })),
  ...db.customers.map((c) => ({ t: c.code + ' ' + c.short, sub: '客户', to: '/customers/' + c.code, i: 'user' })),
  ...db.materials.map((m) => ({ t: m.code + ' ' + m.name, sub: '物料', to: '/materials/' + m.code, i: 'box' })),
])

expose({
  createSupplier, admitSupplier, blacklistSupplier, unblockSupplier, openDocsOfSupplier, expiringCerts, certInfo, addCert, supplierDocs, supplierPerf, priceHistory,
  createCustomer, customerStock, MAT_TYPES, matCat, nextMatCode, dupMaterial, createMaterial, addMaterialSkus, setMaterialStatus, skuStock, matStock, matUsedBy,
  addColor, sizeGroupUsed, appendSize, removeSize, ROLES, AUDITS, auditRoles, cyclePerm, allLogs, factoryWh,
})

/* ============================================================
 * 页面
 * ============================================================ */
const SUP_CATS = ['面料商', '辅料商', '成衣厂', '加工厂', '外协', '包材商']

route('/suppliers', {
  setup() {
    const s = reactive({ cat: '全部', st: '', q: '', cert: nav.query.f === '证照', add: false })
    const tabs = computed(() => [['全部', '全部', db.suppliers.length], ...SUP_CATS.map((c) => [c, c, db.suppliers.filter((x) => x.cats.includes(c)).length])])
    const soon = computed(() => new Set(expiringCerts().map((x) => x.s.code)))
    const rows = computed(() => db.suppliers.filter((x) => (s.cat === '全部' || x.cats.includes(s.cat)) && (!s.st || x.status === s.st) && (!s.cert || soon.value.has(x.code))
      && (!s.q || (x.code + x.short + x.name + x.contact).toLowerCase().includes(s.q.toLowerCase()))))
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="供应商"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建供应商</button></Head>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.cat" :items="tabs"/>
      <button v-if="s.cert" class="chip on" @click="s.cert = false">证照 30 天内到期<Ico n="x" :s="12"/></button>
      <span class="sp"></span>
      <select class="sel" v-model="s.st"><option value="">全部状态</option><option v-for="x in ['合作中', '待准入', '停用', '黑名单']" :key="x">{{ x }}</option></select>
      <Search v-model="s.q"/>
    </div>
    <div data-c="供应商列表">
      <Tbl :rows="rows" link @row="x => go('/suppliers/' + x.code)" empty="没有符合条件的供应商" :cols="[
        { k: 'code', t: '编码', c: 'code' }, { k: 'short', t: '简称' }, { k: 'cats', t: '分类', f: x => x.cats.join('、') }, { k: 'level', t: '等级' },
        { k: 'status', t: '状态' }, { k: 'contact', t: '联系人' }, { k: 'settle', t: '结算', f: x => x.settle + (x.term ? ' ' + x.term + ' 天' : '') },
        { k: 'buyer', t: '负责人', f: x => userName(x.buyer) }, { k: 'cert', t: '证照' }]">
        <template #short="{ r }"><span>{{ r.short }}</span> <span class="dim">{{ r.name }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
        <template #cert="{ r }"><span :class="certInfo(r).tone">{{ certInfo(r).t }}</span></template>
      </Tbl>
    </div>
    <SupplierNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'suppliers', t: '供应商' })

comp('SupplierNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ name: '', short: '', cats: [], level: 'B', contact: '', phone: '', settle: '月结', term: 30, tax: '13%', buyer: 'u2' })
    const toggle = (c) => { const i = f.cats.indexOf(c); i >= 0 ? f.cats.splice(i, 1) : f.cats.push(c) }
    function save() {
      const s = run(() => createSupplier({ ...f, cats: [...f.cats] }))
      if (!s) return
      emit('close')
      go('/suppliers/' + s.code)
      const w = factoryWh(s.code)
      toast(s.short + ' 已建，待准入' + (w ? '；自动建了工厂仓「' + w.name + '」' : ''))
    }
    return { f, toggle, save, SUP_CATS }
  },
  template: `<Modal t="新建供应商" :w="600" @close="$emit('close')">
    <div class="form">
      <label class="f full">全称<input class="inp" v-model="f.name"></label>
      <label class="f">简称<input class="inp" v-model="f.short"></label>
      <label class="f">等级<select class="sel" v-model="f.level"><option v-for="x in ['A', 'B', 'C', 'D']" :key="x">{{ x }}</option></select></label>
      <div class="f full">分类<div class="chips"><button v-for="c in SUP_CATS" :key="c" class="chip" :class="{ on: f.cats.includes(c) }" @click="toggle(c)">{{ c }}</button></div></div>
      <label class="f">联系人<input class="inp" v-model="f.contact"></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
      <label class="f">结算方式<select class="sel" v-model="f.settle"><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></select></label>
      <label class="f">账期（天）<input class="inp num" type="number" v-model.number="f.term"></label>
      <label class="f">税率<select class="sel" v-model="f.tax"><option v-for="x in db.dicts.税率" :key="x">{{ x }}</option></select></label>
      <label class="f">负责人<select class="sel" v-model="f.buyer"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }} · {{ u.role }}</option></select></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建供应商</button></template>
  </Modal>`,
})

route('/suppliers/:code', {
  props: ['code'],
  setup(p) {
    const s = computed(() => sup(p.code))
    const tab = ref('certs')
    const box = ref(null) // { mode: '拉黑' | '解除黑名单' }
    const certAdd = ref(false)
    const docs = computed(() => supplierDocs(p.code))
    const perf = computed(() => supplierPerf(p.code))
    const prices = computed(() => priceHistory(p.code))
    function admit() {
      const r = run(() => doAction('supplier', s.value, '准入'))
      if (r) toast(typeof r === 'string' ? '已准入；' + r : '已准入，现在能选进采购单、加工单')
    }
    async function disable() {
      if (await ask('停用 ' + s.value.short, '停用后不能再选进新的采购单、加工单；已有单据照常入库、回货。', '停用', true)) run(() => doAction('supplier', s.value, '停用'))
    }
    const more = computed(() => s.value ? [
      can('supplier', s.value, '停用') && { t: '停用', fn: disable },
      can('supplier', s.value, '拉黑') && { t: '拉黑', danger: true, fn: () => { box.value = { mode: '拉黑' } } },
    ].filter(Boolean) : [])
    return { s, tab, box, certAdd, docs, perf, prices, admit, more }
  },
  template: `<div v-if="s">
    <Head :t="s.short" :back="['供应商', '/suppliers']">
      <button v-if="can('supplier', s, '准入')" class="btn pri" @click="admit">准入</button>
      <button v-if="can('supplier', s, '启用')" class="btn pri" @click="run(() => doAction('supplier', s, '启用'))">恢复合作</button>
      <button v-if="can('supplier', s, '解除黑名单')" class="btn" @click="box = { mode: '解除黑名单' }">解除黑名单</button>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ s.short }}<St :s="s.status"/></div>
        <div class="sub"><span>{{ s.name }}</span><span>{{ s.cats.join('、') }}</span><span>{{ s.level }} 级</span></div>
        <div v-if="s.status === '黑名单'" class="banner red" data-c="黑名单原因"><Ico n="alert"/>{{ s.blackReason }}</div>
        <Tabs v-model="tab" :items="[['certs', '资质', s.certs.length], ['docs', '往来单据', docs.length], ['perf', '绩效'], ['price', '询价记录', prices.length]]"/>

        <section v-if="tab === 'certs'" data-c="资质">
          <Tbl inset flat :rows="s.certs" empty="还没传证照" :cols="[{ k: 'type', t: '类型' }, { k: 'until', t: '有效期至', f: c => c.until }, { k: 'left', t: '' }, { k: 'file', t: '文件' }]">
            <template #left="{ r }"><Days :d="r.until" :warn="30"/></template>
            <template #file="{ r }"><Pics v-if="r.file" :model-value="[r.file]" readonly :s="36"/><span v-else class="dim">没传</span></template>
          </Tbl>
          <button class="btn" style="margin-top:12px" @click="certAdd = true"><Ico n="plus" :s="14"/>加一份证照</button>
        </section>

        <section v-if="tab === 'docs'" data-c="往来单据">
          <Tbl inset flat :rows="docs" link @row="d => go(d.to)" empty="和这家还没有往来单据" :cols="[
            { k: 'no', t: '单号', c: 'code' }, { k: 'k', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) },
            { k: 'qty', t: '数量', c: 'num' }, { k: 'amt', t: '金额', c: 'num', f: d => d.amt == null ? '—' : fm(d.amt) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St v-if="r.status" :s="r.status"/><span v-else class="dim">—</span></template>
          </Tbl>
        </section>

        <section v-if="tab === 'perf'" data-c="绩效">
          <div class="stats" style="border:1px solid var(--line);border-radius:10px;overflow:hidden">
            <div class="stat"><span>准时交货</span><b>{{ perf.onTime == null ? '—' : perf.onTime + '%' }}<small>{{ perf.n }} 次</small></b></div>
            <div class="stat"><span>逾期未交</span><b :class="{ red: perf.late }">{{ perf.late }}</b></div>
            <div class="stat"><span>回货次品率</span><b>{{ perf.defRate == null ? '—' : perf.defRate + '%' }}<small v-if="perf.ret">{{ perf.def }} / {{ perf.ret }}</small></b></div>
            <div class="stat"><span>异常</span><b>{{ perf.issues }}</b></div>
            <div class="stat"><span>往来金额</span><b>¥{{ Math.round(perf.amt).toLocaleString('zh-CN') }}<small>{{ perf.docs }} 单</small></b></div>
          </div>
        </section>

        <section v-if="tab === 'price'" data-c="询价记录">
          <Tbl inset flat :rows="prices" link @row="x => go('/purchases/' + x.no)" empty="还没从这家买过东西" :cols="[
            { k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'no', t: '采购单', c: 'code' },
            { k: 'qty', t: '数量', c: 'num', f: x => fq(x.qty) + ' ' + x.unit }, { k: 'price', t: '单价', c: 'num', f: x => fm(x.price) }]"/>
        </section>
      </div>
      <aside class="aside" data-c="供应商属性">
        <dl class="props">
          <dt>编码</dt><dd class="code">{{ s.code }}</dd>
          <dt>联系人</dt><dd>{{ s.contact || '—' }}</dd>
          <dt>电话</dt><dd>{{ s.phone || '—' }}</dd>
          <dt>结算</dt><dd>{{ s.settle }}{{ s.term ? ' · ' + s.term + ' 天' : '' }}</dd>
          <dt>税率</dt><dd>{{ s.tax }}</dd>
          <dt>负责人</dt><dd>{{ userName(s.buyer) }}</dd>
          <dt>准入日期</dt><dd>{{ s.admitted || '—' }}</dd>
          <dt v-if="factoryWh(s.code)">工厂仓</dt><dd v-if="factoryWh(s.code)"><a :href="'#/stock?wh=' + factoryWh(s.code).code">{{ factoryWh(s.code).name }}</a></dd>
        </dl>
        <h4>操作记录</h4><Log :items="s.log"/>
      </aside>
    </div>
    <SupplierBlock v-if="box" :s="s" :mode="box.mode" @close="box = null"/>
    <CertNew v-if="certAdd" :s="s" @close="certAdd = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'suppliers', t: (p) => '供应商 ' + (sup(p.code)?.short || p.code) })

comp('SupplierBlock', {
  props: { s: Object, mode: String },
  emits: ['close'],
  setup(p, { emit }) {
    const reason = ref('')
    const open = computed(() => (p.mode === '拉黑' ? openDocsOfSupplier(p.s.code) : []))
    function ok() {
      const r = run(() => (p.mode === '拉黑' ? blacklistSupplier(p.s, reason.value.trim()) : unblockSupplier(p.s, reason.value.trim())))
      if (r) { emit('close'); toast(p.mode === '拉黑' ? p.s.short + ' 已拉黑，新的采购单、加工单选不到它' : p.s.short + ' 已解除黑名单，现在是停用') }
    }
    return { reason, open, ok }
  },
  template: `<Modal :t="mode + ' ' + s.short" :w="480" @close="$emit('close')">
    <div v-if="open.length" class="banner" style="display:block"><div>有 {{ open.length }} 张单据还没完成，拉黑后可以继续入库、回货：</div><div class="code" style="margin-top:4px">{{ open.join('、') }}</div></div>
    <label class="f">原因<textarea class="inp" v-model="reason"></textarea></label>
    <template #foot><button class="btn" @click="$emit('close')">取消</button>
      <button class="btn pri" :style="mode === '拉黑' ? 'background:var(--red);border-color:var(--red)' : ''" @click="ok">{{ mode }}</button></template>
  </Modal>`,
})

comp('CertNew', {
  props: { s: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ type: '营业执照', until: '', files: [] })
    function ok() { if (run(() => addCert(p.s, { ...f, file: f.files[0] }))) emit('close') }
    return { f, ok }
  },
  template: `<Modal t="加一份证照" :w="440" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in ['营业执照', '验厂报告', '环保证明', '其他']" :key="x">{{ x }}</option></select></label>
      <label class="f">有效期至<input class="inp" type="date" v-model="f.until"></label>
      <div class="f full">照片或扫描件<Pics v-model="f.files" files :max="1" :s="64" tip="拍照、扫描件、PDF 都行；拖进来也可以"/></div>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">加上</button></template>
  </Modal>`,
})

/* ---------- 客户 ---------- */
route('/customers', {
  setup() {
    const s = reactive({ q: '', add: false })
    const rows = computed(() => db.customers.filter((c) => !s.q || (c.code + c.name + c.short + c.contact).toLowerCase().includes(s.q.toLowerCase())))
    const orders = (c) => db.orders.filter((o) => o.customer === c.code && !['已取消'].includes(o.status))
    return { s, rows, orders }
  },
  template: `<div>
    <Head t="客户"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建客户</button></Head>
    <div class="bar" data-c="筛选"><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="客户列表">
      <Tbl :rows="rows" link @row="c => go('/customers/' + c.code)" empty="没有符合条件的客户" :cols="[
        { k: 'code', t: '编码', c: 'code' }, { k: 'short', t: '简称' }, { k: 'contact', t: '联系人' }, { k: 'phone', t: '电话' },
        { k: 'settle', t: '结算', f: c => c.settle + (c.term ? ' ' + c.term + ' 天' : '') },
        { k: 'n', t: '订单', c: 'num', f: c => orders(c).length }, { k: 'amt', t: '订单金额', c: 'num', f: c => fm(sum(orders(c), orderAmt)) }, { k: 'status', t: '状态' }]">
        <template #short="{ r }"><span>{{ r.short }}</span> <span class="dim">{{ r.name }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <CustomerNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'customers', t: '客户' })

comp('CustomerNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ name: '', short: '', contact: '', phone: '', settle: '月结', term: 30 })
    function save() { const c = run(() => createCustomer({ ...f })); if (c) { emit('close'); go('/customers/' + c.code); toast(c.short + ' 已建') } }
    return { f, save }
  },
  template: `<Modal t="新建客户" :w="560" @close="$emit('close')">
    <div class="form">
      <label class="f full">全称<input class="inp" v-model="f.name"></label>
      <label class="f">简称<input class="inp" v-model="f.short"></label>
      <label class="f">编码<input class="inp code" :value="'KH' + String(db.customers.length + 1).padStart(4, '0')" disabled></label>
      <label class="f">联系人<input class="inp" v-model="f.contact"></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
      <label class="f">结算方式<select class="sel" v-model="f.settle"><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></select></label>
      <label class="f">账期（天）<input class="inp num" type="number" v-model.number="f.term"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建客户</button></template>
  </Modal>`,
})

route('/customers/:code', {
  props: ['code'],
  setup(p) {
    const c = computed(() => cust(p.code))
    const tab = ref('orders')
    const orders = computed(() => db.orders.filter((o) => o.customer === p.code).slice().reverse())
    const stock = computed(() => customerStock(p.code))
    const toggle = () => { c.value.status = c.value.status === '启用' ? '停用' : '启用'; act(c.value, c.value.status) }
    return { c, tab, orders, stock, toggle }
  },
  template: `<div v-if="c">
    <Head :t="c.short" :back="['客户', '/customers']">
      <a v-if="c.status === '启用'" class="btn pri" :href="'#/orders?new=1&customer=' + c.code"><Ico n="plus" :s="14"/>下订单</a>
      <More :items="[{ t: c.status === '启用' ? '停用' : '恢复启用', danger: c.status === '启用', fn: toggle }]"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ c.short }}<St :s="c.status"/></div>
        <div class="sub"><span>{{ c.name }}</span></div>
        <Tabs v-model="tab" :items="[['orders', '订单', orders.length], ['terms', '商务条款'], ['stock', '客供料', stock.length]]"/>
        <section v-if="tab === 'orders'" data-c="客户订单">
          <Tbl inset flat :rows="orders" link @row="o => go('/orders/' + o.no)" empty="这个客户还没下过订单" :cols="[
            { k: 'no', t: '订单号', c: 'code' }, { k: 'custNo', t: '客户单号', f: o => o.custNo || '—' }, { k: 'styles', t: '款式', f: o => orderStyles(o).join('、') },
            { k: 'qty', t: '数量', c: 'num', f: o => orderQty(o) }, { k: 'amt', t: '金额', c: 'num', f: o => fm(orderAmt(o)) }, { k: 'due', t: '客户交期', f: o => fd(o.due) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </section>
        <section v-if="tab === 'terms'" data-c="商务条款">
          <div class="form" style="max-width:560px">
            <label class="f">结算方式<select class="sel" v-model="c.settle"><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></select></label>
            <label class="f">账期（天）<input class="inp num" type="number" v-model.number="c.term"></label>
            <label class="f full">价格协议<input class="inp" v-model="c.terms.price"></label>
            <label class="f">扣点<input class="inp" v-model="c.terms.deduct"></label>
            <label class="f">返点<input class="inp" v-model="c.terms.rebate"></label>
          </div>
        </section>
        <section v-if="tab === 'stock'" data-c="客供料库存">
          <Tbl inset flat :rows="stock" empty="这个客户没有放在我们这里的料" :cols="[
            { k: 'w', t: '仓库', f: x => whName(x.w) }, { k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'code', t: 'SKU', c: 'code', f: x => x.sku },
            { k: 'q', t: '数量', c: 'num', f: x => fq(x.q) + ' ' + unitOf(x.sku) }]"/>
        </section>
      </div>
      <aside class="aside" data-c="客户属性">
        <dl class="props">
          <dt>编码</dt><dd class="code">{{ c.code }}</dd>
          <dt>联系人</dt><dd>{{ c.contact || '—' }}</dd>
          <dt>电话</dt><dd>{{ c.phone || '—' }}</dd>
          <dt>结算</dt><dd>{{ c.settle }}{{ c.term ? ' · ' + c.term + ' 天' : '' }}</dd>
        </dl>
        <DocFiles :doc="c"/>
        <h4>操作记录</h4><Log :items="c.log"/>
      </aside>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'customers', t: (p) => '客户 ' + (cust(p.code)?.short || p.code) })

/* ---------- 物料 ---------- */
const MAT_GROUPS = ['面料', '辅料', '包材']
route('/materials', {
  setup() {
    const s = reactive({ cat: '', st: '启用', q: '', add: false })
    const groups = computed(() => MAT_GROUPS.map((g) => ({ g, cats: db.materialCategories.filter((c) => c.parent === g) })))
    const inCat = (m) => !s.cat || m.cat === s.cat || matCat(m.cat).parent === s.cat
    const rows = computed(() => db.materials.filter((m) => inCat(m) && (s.st === '全部' || m.status === s.st) && (!s.q || (m.code + m.name + (m.supNo || '')).toLowerCase().includes(s.q.toLowerCase()))))
    const count = (c) => db.materials.filter((m) => m.cat === c || matCat(m.cat).parent === c).length
    return { s, groups, rows, count }
  },
  template: `<div>
    <Head t="物料"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建物料</button></Head>
    <div style="display:flex;min-height:calc(100vh - 48px)">
      <div style="width:184px;flex:none;border-right:1px solid var(--line);padding:10px 8px" data-c="物料分类">
        <a class="nav-i" :class="{ on: !s.cat }" @click="s.cat = ''"><span>全部</span><span class="n">{{ db.materials.length }}</span></a>
        <div v-for="g in groups" :key="g.g" class="grp">
          <b @click="s.cat = g.g">{{ g.g }}</b>
          <a v-for="c in g.cats" :key="c.code" class="nav-i" :class="{ on: s.cat === c.code }" @click="s.cat = c.code"><span class="code dim">{{ c.code }}</span><span>{{ c.name }}</span><span class="n">{{ count(c.code) || '' }}</span></a>
        </div>
      </div>
      <div style="flex:1;min-width:0">
        <div class="bar" data-c="筛选"><Seg v-model="s.st" :items="['启用', '停用', '全部']"/><span class="sp"></span><Search v-model="s.q"/></div>
        <div data-c="物料列表">
          <Tbl :rows="rows" link @row="m => go('/materials/' + m.code)" empty="这个分类下没有物料" :cols="[
            { k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称', img: m => m.code }, { k: 'type', t: '类型' }, { k: 'unit', t: '单位' },
            { k: 'spec', t: '门幅 · 克重', f: m => m.width ? m.width + 'cm · ' + m.weight + 'g' : '—' }, { k: 'supplier', t: '默认供应商', f: m => supName(m.supplier) },
            { k: 'price', t: '参考单价', c: 'num', f: m => fm(m.price) + '/' + m.purUnit }, { k: 'skus', t: 'SKU', c: 'num', f: m => m.skus.length },
            { k: 'stock', t: '库存', c: 'num', f: m => fq(matStock(m)) + ' ' + m.unit }]">
            <template #unit="{ r }"><span v-if="r.purUnit === r.unit">{{ r.unit }}</span><span v-else>{{ r.unit }} <span class="dim">1 {{ r.purUnit }} = {{ fq(r.factor) }} {{ r.unit }}</span></span></template>
          </Tbl>
        </div>
      </div>
    </div>
    <MaterialNew v-if="s.add" :cat="s.cat && s.cat.length === 2 ? s.cat : 'ML'" @close="s.add = false"/>
  </div>`,
}, { mod: 'materials', t: '物料' })

comp('MaterialNew', {
  props: { cat: String },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ cat: p.cat, name: '', type: '面料', unit: '米', purUnit: '米', factor: 1, width: null, weight: null, comp: '', supplier: 'GYS0001', supNo: '', price: null, moq: null, pack: 1, lead: 7, colors: [], images: [] })
    const code = computed(() => nextMatCode(f.cat))
    const sups = computed(() => db.suppliers.filter((s) => s.status === '合作中'))
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    watch(() => f.unit, (u) => { f.purUnit = u; f.factor = 1 })
    async function save() {
      const d = dupMaterial(f)
      if (d && !(await ask('可能重复', '可能和 ' + d.code + ' ' + d.name + ' 重复，确定新建？', '仍然新建'))) return
      const m = run(() => createMaterial({ ...f, colors: [...f.colors], images: [...f.images] }))
      if (m) { emit('close'); go('/materials/' + m.code); toast(m.code + ' 已建，生成 ' + m.skus.length + ' 个 SKU') }
    }
    return { f, code, sups, toggle, save, MAT_TYPES }
  },
  template: `<Modal t="新建物料" :w="640" @close="$emit('close')">
    <div class="form" style="grid-template-columns:1fr 1fr 1fr">
      <label class="f">分类<select class="sel" v-model="f.cat"><option v-for="c in db.materialCategories" :key="c.code" :value="c.code">{{ c.parent }} / {{ c.name }}</option></select></label>
      <label class="f">编码<input class="inp code" :value="code" disabled></label>
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in MAT_TYPES" :key="x">{{ x }}</option></select></label>
      <label class="f full">名称<input class="inp" v-model="f.name" placeholder="如：220g 斜纹棉"></label>
      <label class="f">库存单位<select class="sel" v-model="f.unit"><option v-for="x in db.dicts.单位" :key="x">{{ x }}</option></select></label>
      <label class="f">采购单位<select class="sel" v-model="f.purUnit"><option v-for="x in db.dicts.单位" :key="x">{{ x }}</option></select></label>
      <label class="f">1 {{ f.purUnit }} = ? {{ f.unit }}<input class="inp num" type="number" v-model.number="f.factor" :disabled="f.purUnit === f.unit"></label>
      <label class="f">门幅 cm<input class="inp num" type="number" v-model.number="f.width"></label>
      <label class="f">克重 g/㎡<input class="inp num" type="number" v-model.number="f.weight"></label>
      <label class="f">成分<input class="inp" v-model="f.comp"></label>
      <label class="f">默认供应商<select class="sel" v-model="f.supplier"><option v-for="s in sups" :key="s.code" :value="s.code">{{ s.short }}</option></select></label>
      <label class="f">供应商货号<input class="inp" v-model="f.supNo"></label>
      <label class="f">单价（每{{ f.purUnit }}）<input class="inp num" type="number" v-model.number="f.price"></label>
      <label class="f">起订量<input class="inp num" type="number" v-model.number="f.moq"></label>
      <label class="f">最小包装<input class="inp num" type="number" v-model.number="f.pack"></label>
      <label class="f">采购周期（天）<input class="inp num" type="number" v-model.number="f.lead"></label>
      <div class="f full">颜色<div class="chips"><button v-for="c in db.colors" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
      <div class="f full" data-c="物料图片">物料图片<Pics v-model="f.images" :colors="f.colors" tip="布样、色卡、辅料实物都可以拍；给图标上颜色，按颜色出图"/></div>
    </div>
    <template #foot><span class="dim">{{ f.colors.length ? f.colors.length + ' 个 SKU：' + f.colors.map(c => code + '-' + c).join('、') : '不选颜色 = 1 个 SKU' }}</span><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建物料</button></template>
  </Modal>`,
})

route('/materials/:code', {
  props: ['code'],
  setup(p) {
    const m = computed(() => mat(p.code))
    const adding = ref(false)
    const used = computed(() => matUsedBy(p.code))
    async function toggle() {
      if (m.value.status === '启用') { if (await ask('停用 ' + m.value.name, '停用后它的 SKU 不能再选进新的资料包、采购单；已有库存照常出库。', '停用', true)) setMaterialStatus(m.value, '停用') }
      else setMaterialStatus(m.value, '启用')
    }
    return { m, adding, used, toggle }
  },
  template: `<div v-if="m">
    <Head :t="m.code" :back="['物料', '/materials']">
      <button class="btn" @click="adding = true"><Ico n="plus" :s="14"/>批量加 SKU</button>
      <More :items="[{ t: m.status === '启用' ? '停用' : '恢复启用', danger: m.status === '启用', fn: toggle }]"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ m.name }}<St :s="m.status"/></div>
        <div class="sub"><span>{{ matCat(m.cat).parent }} / {{ matCat(m.cat).name }}</span><span>{{ m.type }}</span><span v-if="m.comp">{{ m.comp }}</span></div>
        <section data-c="物料SKU">
          <Tbl inset flat :rows="m.skus" :cols="[
            { k: 'code', t: 'SKU', c: 'code', img: k => k.code }, { k: 'color', t: '颜色' }, { k: 'spec', t: '规格', f: k => k.spec || '—' }, { k: 'supColor', t: '供应商色号', f: k => k.supColor || '—' },
            { k: 'safety', t: '安全库存', c: 'num', f: k => k.safety ? fq(k.safety) : '—' },
            { k: 'wl', t: '物料仓', c: 'num', f: k => fq(skuStock(k.code, '物料仓')) }, { k: 'gc', t: '工厂仓', c: 'num', f: k => fq(skuStock(k.code, '工厂仓')) }]">
            <template #color="{ r }"><Sw v-if="r.color" :c="r.color"/><span v-else class="dim">—</span></template>
          </Tbl>
        </section>
        <div class="sec" data-c="用到它的款式">
          <h3>用到它的款式</h3>
          <Tbl inset flat :rows="used" link @row="x => go('/styles/' + x.style)" empty="还没有生效的资料包用到它" :cols="[
            { k: 'style', t: '款号', c: 'code' }, { k: 'name', t: '名称', f: x => styleName(x.style) }, { k: 'ver', t: '资料包', f: x => 'V' + x.ver }, { k: 'part', t: '部位' }, { k: 'src', t: '来源' }]"/>
        </div>
      </div>
      <aside class="aside" data-c="物料属性">
        <Gallery :code="m.code" :colors="[...new Set(m.skus.map(k => k.color).filter(Boolean))]" :owner="m" edit/>
        <dl class="props">
          <dt>库存单位</dt><dd>{{ m.unit }}</dd>
          <dt>采购单位</dt><dd>{{ m.purUnit }}<template v-if="m.purUnit !== m.unit"> · 1 {{ m.purUnit }} = {{ fq(m.factor) }} {{ m.unit }}</template></dd>
          <dt v-if="m.width">门幅</dt><dd v-if="m.width">{{ m.width }} cm</dd>
          <dt v-if="m.weight">克重</dt><dd v-if="m.weight">{{ m.weight }} g/㎡</dd>
          <dt>默认供应商</dt><dd><a v-if="m.supplier" :href="'#/suppliers/' + m.supplier">{{ supName(m.supplier) }}</a><span v-else>—</span></dd>
          <dt>供应商货号</dt><dd>{{ m.supNo || '—' }}</dd>
          <dt>参考单价</dt><dd>{{ fm(m.price) }} / {{ m.purUnit }}</dd>
          <dt>起订量</dt><dd>{{ m.moq ? fq(m.moq) + ' ' + m.purUnit : '—' }}</dd>
          <dt>最小包装</dt><dd>{{ fq(m.pack) }} {{ m.purUnit }}</dd>
          <dt>采购周期</dt><dd>{{ m.lead }} 天</dd>
        </dl>
        <DocFiles :doc="m"/>
        <h4>操作记录</h4><Log :items="m.log"/>
      </aside>
    </div>
    <SkuAdd v-if="adding" :m="m" @close="adding = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'materials', t: (p) => '物料 ' + p.code })

comp('SkuAdd', {
  props: { m: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ colors: [], spec: '' })
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    function ok() {
      if (!f.colors.length) return toast('至少选一个颜色', null, true)
      const made = run(() => addMaterialSkus(p.m, f.colors, f.spec.trim()))
      if (made) { emit('close'); toast('加了 ' + made.length + ' 个 SKU') }
    }
    return { f, toggle, ok }
  },
  template: `<Modal t="批量加 SKU" :w="520" @close="$emit('close')">
    <div class="form">
      <div class="f full">颜色<div class="chips"><button v-for="c in db.colors" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
      <label class="f">规格<input class="inp" v-model="f.spec" placeholder="如 15cm、18L"></label>
    </div>
    <template #foot><span class="dim code">{{ f.colors.map(c => [m.code, c, f.spec.trim()].filter(Boolean).join('-')).join('、') }}</span><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">加上</button></template>
  </Modal>`,
})

/* ---------- 基础 ---------- */
route('/basics', {
  setup() {
    const tab = ref('colors')
    const nc = reactive({ code: '', name: '', family: '黑白灰', hex: '#888888' })
    const families = computed(() => [...new Set([...db.colors.map((c) => c.family), '红', '黄', '绿', '紫'])])
    const newSize = reactive({})
    const whType = ref('全部')
    const catGroups = computed(() => [...new Set(db.categories.map((c) => c.path))].map((p) => ({ p, cats: db.categories.filter((c) => c.path === p) })))
    const colorUsed = (c) => db.styles.filter((s) => s.colors.includes(c)).length
    const catUsed = (c) => db.styles.filter((s) => s.cat === c).length
    function saveColor() { if (run(() => addColor(nc))) { toast('加了颜色 ' + nc.name); Object.assign(nc, { code: '', name: '' }) } }
    function addSize(g) { if (run(() => appendSize(g, newSize[g.id]))) newSize[g.id] = '' }
    const whs = computed(() => db.warehouses.filter((w) => whType.value === '全部' || w.type === whType.value))
    const dictText = (v) => (Array.isArray(v) ? v[0] + ' ' + v[1] + ' 天' : v)
    return { tab, nc, families, newSize, whType, catGroups, colorUsed, catUsed, saveColor, addSize, whs, dictText }
  },
  template: `<div>
    <Head t="基础"/>
    <div class="page">
      <Tabs v-model="tab" :items="[['colors', '颜色', db.colors.length], ['sizes', '尺码组', db.sizeGroups.length], ['cats', '品类', db.categories.length], ['whs', '仓库', db.warehouses.length], ['companies', '主体公司', db.companies.length], ['dicts', '字典']]"/>

      <section v-if="tab === 'colors'" data-c="颜色">
        <Tbl inset flat :rows="db.colors" :cols="[{ k: 'sw', t: '' }, { k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'family', t: '色系' }, { k: 'used', t: '款式', c: 'num', f: c => colorUsed(c.code) || '—' }]">
          <template #sw="{ r }"><Sw :c="r.code" :n="false"/></template>
        </Tbl>
        <div class="row" style="margin-top:14px" data-c="加颜色">
          <input type="color" v-model="nc.hex" style="width:30px;height:30px;padding:0;border:1px solid var(--line-strong);border-radius:6px;background:none">
          <input class="inp code" style="width:80px" v-model="nc.code" placeholder="编码">
          <input class="inp" style="width:120px" v-model="nc.name" placeholder="名称">
          <select class="sel" v-model="nc.family"><option v-for="x in families" :key="x">{{ x }}</option></select>
          <button class="btn" @click="saveColor"><Ico n="plus" :s="14"/>加颜色</button>
        </div>
      </section>

      <section v-if="tab === 'sizes'" data-c="尺码组">
        <div class="list">
          <div v-for="g in db.sizeGroups" :key="g.id" class="li" style="height:auto;padding:12px 4px;gap:14px">
            <b style="width:96px;font-weight:500">{{ g.name }}</b>
            <span class="chips"><span v-for="z in g.sizes" :key="z" class="chip" style="cursor:default">{{ z }}<Ico v-if="!sizeGroupUsed(g.id)" n="x" :s="11" style="cursor:pointer" @click="run(() => removeSize(g, z))"/></span></span>
            <span class="dim" style="font-size:12px">基码 {{ baseSizeOf(g.id) }}{{ sizeGroupUsed(g.id) ? ' · ' + sizeGroupUsed(g.id) + ' 个款在用' : '' }}</span>
            <span style="flex:1"></span>
            <input class="inp" style="width:72px" v-model="newSize[g.id]" @keydown.enter="addSize(g)">
            <button class="btn sm" @click="addSize(g)">{{ sizeGroupUsed(g.id) ? '追加尺码' : '加尺码' }}</button>
          </div>
        </div>
      </section>

      <section v-if="tab === 'cats'" data-c="品类">
        <div v-for="g in catGroups" :key="g.p" style="margin-bottom:18px">
          <div class="dim" style="font-size:12px;margin-bottom:6px">{{ g.p }}</div>
          <div class="list"><div v-for="c in g.cats" :key="c.code" class="li"><span class="code dim" style="width:24px">{{ c.code }}</span>{{ c.name }}<span class="n dim" style="font-weight:400">{{ catUsed(c.code) ? catUsed(c.code) + ' 个款' : '' }}</span></div></div>
        </div>
      </section>

      <section v-if="tab === 'whs'" data-c="仓库">
        <Seg v-model="whType" :items="['全部', '物料仓', '工厂仓', '成品仓']" style="margin-bottom:10px"/>
        <Tbl inset flat :rows="whs" :cols="[{ k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'type', t: '类型' },
          { k: 'factory', t: '绑定加工厂', f: w => w.factory ? supName(w.factory) : '—' }, { k: 'keeper', t: '负责人', f: w => w.keeper ? userName(w.keeper) : '—' }, { k: 'status', t: '状态' }]">
          <template #status="{ r }"><St :s="r.status"/></template>
        </Tbl>
      </section>

      <section v-if="tab === 'companies'" data-c="主体公司">
        <Tbl inset flat :rows="db.companies" :cols="[{ k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'short', t: '简称' }, { k: 'status', t: '状态' }]">
          <template #status="{ r }"><St :s="r.status"/></template>
        </Tbl>
      </section>

      <section v-if="tab === 'dicts'" data-c="字典">
        <div class="list">
          <div v-for="(vals, k) in db.dicts" :key="k" class="li" style="height:auto;padding:10px 4px;align-items:flex-start">
            <b style="width:96px;font-weight:500;flex:none">{{ k }}</b>
            <span class="chips"><span v-for="v in vals" :key="dictText(v)" class="pill">{{ dictText(v) }}</span></span>
          </div>
        </div>
      </section>
    </div>
  </div>`,
}, { mod: 'basics', t: '基础' })

/* ---------- 设置 ---------- */
route('/settings', {
  setup() {
    const tab = ref('users')
    const who = ref('')
    const logs = computed(() => allLogs().filter((x) => !who.value || x.who === who.value))
    const permTone = (v) => ({ 看: '', 改: 'blue', 审: 'green' }[v])
    const addRule = () => db.approvalRules.push({ doc: '采购单', over: 0, role: '管理层' })
    const JST = ['回货登记后推送聚水潭生成成品入库单，成品库存以聚水潭为准', '双向同步：入库推送聚水潭，出库和库存回写本系统', '暂不对接，两边各自录单']
    const FIN = ['金蝶', '用友', '系统内闭环，不对接']
    return { tab, who, logs, permTone, addRule, JST, FIN }
  },
  template: `<div>
    <Head t="设置"/>
    <div class="page">
      <Tabs v-model="tab" :items="[['users', '用户', db.users.length], ['perms', '角色权限'], ['audit', '审批'], ['notify', '通知'], ['integ', '集成'], ['log', '操作日志']]"/>

      <section v-if="tab === 'users'" data-c="用户">
        <Tbl inset flat :rows="db.users" :cols="[{ k: 'name', t: '姓名' }, { k: 'role', t: '角色' }, { k: 'scope', t: '数据范围' }, { k: 'company', t: '默认主体' }]">
          <template #role="{ r }"><select class="sel" style="height:26px" v-model="r.role"><option v-for="x in ROLES" :key="x">{{ x }}</option></select></template>
          <template #scope="{ r }"><Seg v-model="r.scope" :items="['全部', '本人']"/></template>
          <template #company="{ r }"><select class="sel" style="height:26px" :value="r.company || 'ZJ'" @change="r.company = $event.target.value"><option v-for="c in db.companies" :key="c.code" :value="c.code">{{ c.short }}</option></select></template>
        </Tbl>
      </section>

      <section v-if="tab === 'perms'" data-c="角色权限">
        <table class="tbl inset flat">
          <thead><tr><th>模块</th><th v-for="r in ROLES" :key="r" style="text-align:center">{{ r }}</th></tr></thead>
          <tbody><tr v-for="row in db.perms" :key="row.m"><td>{{ row.m }}</td>
            <td v-for="(v, i) in row.v" :key="i" style="text-align:center"><button class="pill" :class="permTone(v)" style="border:none;cursor:pointer;min-width:30px;justify-content:center" @click="cyclePerm(row, i)">{{ v }}</button></td></tr></tbody>
        </table>
      </section>

      <section v-if="tab === 'audit'" data-c="审批">
        <Tbl inset flat :rows="AUDITS" :cols="[{ k: 't', t: '单据' }, { k: 'p', t: '权限点', c: 'code' }, { k: 'roles', t: '谁能审', f: a => auditRoles(a.m).join('、') || '—' }, { k: 'lv', t: '级数', f: () => '单级' }, { k: 'n', t: '待审', c: 'num', f: a => a.n() || '—' }]"/>
        <div class="sec" data-c="多级审批">
          <h3>多级审批<span class="pill blue">二期</span><span class="acts"><button class="btn sm" @click="addRule"><Ico n="plus" :s="12"/>加一条</button></span></h3>
          <table class="tbl inset flat"><tbody>
            <tr v-for="(r, i) in db.approvalRules" :key="i">
              <td style="width:140px"><select class="sel" style="height:26px" v-model="r.doc"><option v-for="a in AUDITS" :key="a.t">{{ a.t }}</option></select></td>
              <td>金额超过 <input class="inp num" style="width:110px;height:26px" type="number" v-model.number="r.over"> 元，再加一级</td>
              <td style="width:140px"><select class="sel" style="height:26px" v-model="r.role"><option v-for="x in ROLES" :key="x">{{ x }}</option></select></td>
              <td class="num" style="width:40px"><button class="btn ghost icon sm" @click="db.approvalRules.splice(i, 1)"><Ico n="trash" :s="13"/></button></td>
            </tr>
            <tr v-if="!db.approvalRules.length"><td colspan="4" class="empty">没有多级规则，所有单据都是单级审核</td></tr>
          </tbody></table>
        </div>
      </section>

      <section v-if="tab === 'notify'" data-c="通知">
        <div class="list">
          <label v-for="(on, k) in db.notify" :key="k" class="li chk"><input type="checkbox" v-model="db.notify[k]" :disabled="k === '站内待办'">{{ k }}<span v-if="k !== '站内待办'" class="pill blue" style="margin-left:auto">二期</span></label>
        </div>
        <div class="h2">推送哪些事</div>
        <div class="list">
          <label v-for="(on, k) in db.notifyEvents" :key="k" class="li chk"><input type="checkbox" v-model="db.notifyEvents[k]">{{ k }}</label>
        </div>
      </section>

      <section v-if="tab === 'integ'" data-c="集成">
        <div class="list">
          <div class="li" style="height:auto;padding:14px 4px;display:block">
            <div class="row"><b style="font-weight:600">聚水潭</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill amber">待 Q01</span></div>
            <div class="col" style="margin-top:10px;gap:6px">
              <label v-for="x in JST" :key="x" class="chk"><input type="radio" name="jst" :value="x" v-model="db.integration.jst">{{ x }}</label>
            </div>
          </div>
          <div class="li" style="height:auto;padding:14px 4px;display:block">
            <div class="row"><b style="font-weight:600">财务软件</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill amber">待 Q04</span></div>
            <div class="row" style="margin-top:10px;gap:16px"><label v-for="x in FIN" :key="x" class="chk"><input type="radio" name="fin" :value="x" v-model="db.integration.fin">{{ x }}</label></div>
          </div>
          <div class="li" style="height:auto;padding:14px 4px"><b style="font-weight:600">电子签章</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill">三期</span></div>
          <div class="li" style="height:auto;padding:14px 4px"><b style="font-weight:600">开放 API</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill">三期</span></div>
        </div>
      </section>

      <section v-if="tab === 'log'" data-c="操作日志">
        <div class="row" style="margin-bottom:8px"><select class="sel" v-model="who"><option value="">所有人</option><option v-for="u in db.users" :key="u.id" :value="u.name">{{ u.name }}</option></select><span class="dim">{{ logs.length }} 条</span></div>
        <Tbl inset flat :rows="logs" link @row="x => go(x.to)" empty="这个人还没有操作" :cols="[{ k: 'at', t: '日期', f: x => fd(x.at) }, { k: 'who', t: '人' }, { k: 'k', t: '对象' }, { k: 'id', t: '单号', c: 'code' }, { k: 'text', t: '做了什么' }]">
          <template #text="{ r }"><span class="wrap">{{ r.text }}</span></template>
        </Tbl>
      </section>
    </div>
  </div>`,
}, { mod: 'settings', t: '设置' })
