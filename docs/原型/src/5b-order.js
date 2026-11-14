/* ============================================================
 * 订单（M06）：列表、颜色 × 尺码矩阵录单（新建、改草稿）、审核即评审、排单、订单变更
 * ============================================================ */
SEARCH.push(() => db.orders.map((o) => ({ t: o.no + ' ' + custOrDash(o), sub: '订单 ' + orderStyles(o).join(' ') + (TRADE && o.custNo ? ' ' + o.custNo : ''), to: '/orders/' + o.no, i: 'file' })))
// 订单的对方：代工（TRADE）显示客户，没客户显示类型；只做自营时显示类型 · 品牌
const custOrDash = (o) => (TRADE ? (o.customer ? custName(o.customer) : o.type) : o.type + (o.brand ? ' · ' + o.brand : ''))
// 金额带币种：人民币用 ¥，外币写币种（一期不换算汇率，PRD §5.3）
const orderMoney = (o, n) => (!n ? '—' : !o.currency || o.currency === '人民币' ? fm(n) : o.currency + ' ' + rnd(n, 2).toLocaleString('zh-CN', { minimumFractionDigits: 2 }))
// 一个款的单价：各码一样显示一个数，大码加价时显示区间
const stylePriceText = (o, st) => { const ps = [...new Set(o.lines.filter((l) => l.style === st).map((l) => l.price).filter((x) => x != null))]; return !ps.length ? '—' : ps.length === 1 ? orderMoney(o, ps[0]) : orderMoney(o, Math.min(...ps)) + ' – ' + orderMoney(o, Math.max(...ps)) }
const styleAmt = (o, st) => rnd(sum(o.lines.filter((l) => l.style === st), (l) => l.qty * (l.price || 0)), 2)
const auditLog = (d) => [...(d.log || [])].reverse().find((x) => /^审核/.test(x.text))

/* ---------- 改草稿（PRD §5.1）：已审核的订单要改，走变更单 ---------- */
const ORDER_FIELDS = [['type', '类型'], ...(TRADE ? [['customer', '客户', custName], ['custNo', '客户订单号']] : []), ['contract', '合同号'], ['brand', '品牌'], ['company', '主体', companyName],
  ['date', '下单日期'], ['due', DUE_T], ['currency', '币种'], ['sales', '业务员', userName], ['merch', '跟单员', userName], ['ship', '送货要求'], ['note', '备注']]
const lineSig = (ls) => JSON.stringify(ls.map((l) => [l.style, l.color, l.size, l.qty, l.price ?? null, l.due || null, l.note || '']).sort())
// quiet：没改动时不报错（"保存并提交"直接提交）
function updateOrder(o, f, quiet) {
  if (o.status !== '草稿') fail(`${o.no} 是「${o.status}」，只有草稿能改；已审核的订单走变更单`)
  const lines = (f.lines || []).filter((l) => l.qty > 0)
  if (!lines.length) fail('至少录一个数量')
  if (TRADE && f.type !== '自营备货' && !f.customer) fail(`${f.type}要选客户`)
  if (!f.due) fail(DUE_T + '要填')
  checkOrderLines(lines)
  const ch = applyFields(o, { ...f, customer: TRADE && f.type !== '自营备货' ? f.customer : null }, ORDER_FIELDS)
  if (lineSig(lines) !== lineSig(o.lines)) { ch.push(`明细 ${orderQty(o)} 件 → ${sum(lines, (l) => l.qty)} 件`); o.lines = lines.map((l) => ({ ver: null, price: null, due: null, note: '', ...l })) }
  if (!ch.length) { if (quiet) return false; fail('没有改动') }
  act(o, '改：' + ch.join('；'))
}

route('/orders', {
  setup() {
    const s = reactive({ tab: nav.query.s || '全部', f: nav.query.f || '', q: '', type: '', brand: '', mine: false, flt: newFlt(), add: nav.query.new || null, imp: false, picked: [] })
    const TABS = ['全部', '草稿', '待审核', '已审核', '生产中', '已完成', '已结案', '已取消']
    const scoped = computed(() => db.orders.filter((o) => inScope(s.mine, o)))
    const tabs = computed(() => TABS.map((k) => [k, k, k === '全部' ? scoped.value.length : scoped.value.filter((o) => o.status === k).length]))
    const rows = computed(() => scoped.value.filter((o) => (s.tab === '全部' || o.status === s.tab) && (s.f !== '待排单' || (orderLive(o) && orderSched(o) === 0))
      && (!s.type || o.type === s.type) && (!s.brand || o.brand === s.brand) && matchQ(s.q, o.no, o.custNo, o.contract, custOrDash(o), ...orderStyles(o))
      && passFlt(s.flt, { date: o.date, party: o.customer, merch: o.merch })).slice().reverse())
    // 导出的列比页面上多（PRD §5.4：页面最多 12 列，其余放详情和导出）
    const XCOLS = [{ k: 'no', t: '订单号', c: 'code' }, { k: 'date', t: '下单日期' }, { k: 'type', t: '类型' },
      ...(TRADE ? [{ k: 'cust', t: '客户', x: (o) => (o.customer ? custName(o.customer) : '') }, { k: 'custNo', t: '客户订单号', c: 'code' }] : []), { k: 'contract', t: '合同号', c: 'code' }, { k: 'brand', t: '品牌' }, { k: 'styles', t: '款号', x: (o) => orderStyles(o).join(' ') },
      { k: 'qty', t: '数量', x: orderQty }, { k: 'amt', t: '金额', x: orderAmt }, { k: 'currency', t: '币种' }, { k: 'due', t: DUE_T }, { k: 'commit', t: '承诺交期' },
      { k: 'sched', t: '已排产', x: orderSched }, { k: 'ret', t: '已回货', x: orderRet }, { k: 'sales', t: '业务员', x: (o) => userName(o.sales) }, { k: 'merch', t: '跟单员', x: (o) => userName(o.merch) },
      { k: 'company', t: '主体', x: (o) => companyName(o.company) }, { k: 'src', t: '来源' }, { k: 'status', t: '状态' }]
    // 勾选的行批量提交、审核；批量审核的承诺交期先按要求交期定（审核弹窗里改的那个），要改的进详情一张张审
    const picks = computed(() => rows.value.filter((o) => s.picked.includes(o.no)))
    async function batch(a) {
      const ok = await batchAct('order', picks.value, a, a === '审核' ? { args: (o) => [o.commitDraft || o.due], note: `承诺交期按${DUE_T}定，要另定的进详情审。` } : {})
      s.picked = s.picked.filter((k) => !ok.includes(k))
    }
    return { s, scoped, tabs, rows, custOrDash, XCOLS, picks, batch }
  },
  template: `<div>
    <Head t="订单"><button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入</button><ExportBtn :rows="rows" :cols="XCOLS" name="订单"/><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建订单</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><Filters v-model="s.flt" :party="TRADE ? 'customer' : ''" merch label="下单">
        <label class="f">订单类型<Pick class="sel" v-model="s.type"><option value="">全部类型</option><option v-for="x in db.dicts.订单类型" :key="x">{{ x }}</option></Pick></label>
        <label v-if="!TRADE" class="f">品牌<Pick class="sel" v-model="s.brand"><option value="">全部品牌</option><option v-for="x in db.dicts.品牌" :key="x">{{ x }}</option></Pick></label>
      </Filters>
      <span v-if="s.type" class="fchip">{{ s.type }}<button type="button" @click="s.type = ''"><Ico n="x" :s="11"/></button></span>
      <span v-if="s.brand" class="fchip">{{ s.brand }}<button type="button" @click="s.brand = ''"><Ico n="x" :s="11"/></button></span>
      <button v-if="s.f" type="button" class="chip on" @click="s.f = ''">{{ s.f }}<Ico n="x" :s="12"/></button>
      <span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q" :ph="TRADE ? '单号 / 客户单号 / 款号' : '单号 / 合同号 / 款号'"/></div>
    <div data-c="订单列表">
      <Tbl :rows="rows" link v-model:picked="s.picked" @row="o => go('/orders/' + o.no)" :empty="scoped.length ? '没有符合条件的订单：换个状态页签，或者清空筛选' : ownOnly() ? '你的数据范围是「本人」：还没有你是制单人、业务员或跟单员的订单' : s.mine ? '还没有你是制单人、业务员或跟单员的订单，切到「全部」看所有人的' : '还没有订单'" :cols="[
        { k: 'no', t: '订单号', c: 'code' }, { k: 'date', t: '下单', f: o => fd(o.date) }, { k: 'cust', t: TRADE ? '客户' : '类型' }, { k: 'styles', t: '款式' },
        { k: 'qty', t: '数量', c: 'num', f: o => orderQty(o).toLocaleString() }, { k: 'amt', t: '金额', c: 'num', f: o => orderMoney(o, orderAmt(o)) },
        { k: 'due', t: '交期' }, { k: 'sched', t: '排产' }, { k: 'ret', t: '回货' }, { k: 'kit', t: '齐料' },
        { k: 'merch', t: '跟单', f: o => userName(o.merch) }, { k: 'status', t: '状态' }]">
        <template #cust="{ r }"><template v-if="TRADE">{{ custOrDash(r) }}<span v-if="r.customer && r.type !== '客户订单'" class="pill" style="margin-left:6px">{{ r.type }}</span></template>
          <template v-else>{{ r.type }}<small v-if="r.brand" class="dim"> {{ r.brand }}</small></template></template>
        <template #styles="{ r }"><span class="row g4"><Thumb v-for="c in orderStyles(r)" :key="c" :code="c" :s="22"/><span class="code mut">{{ orderStyles(r).join(' ') }}</span></span></template>
        <template #due="{ r }"><span :title="r.commit ? '承诺交期；' + DUE_T + ' ' + fd(r.due) : DUE_T + '；审核时定承诺交期'">{{ fd(r.commit || r.due) }}<small class="dim"> {{ r.commit ? '承诺' : TRADE ? '客户' : '要求' }}</small></span></template>
        <template #sched="{ r }"><Prog v-if="r.status !== '草稿' && r.status !== '待审核'" :a="orderSched(r)" :b="orderQty(r)"/><span v-else class="dim">—</span></template>
        <template #ret="{ r }"><Prog v-if="orderSched(r)" :a="orderRet(r)" :b="orderQty(r)"/><span v-else class="dim">—</span></template>
        <template #kit="{ r }"><St v-if="orderLive(r) || r.status === '已完成'" :s="kitState(r).k === 'ok' ? '齐料' : kitState(r).k === 'part' ? '部分到货' : '未到料'" :t="kitState(r).t"/><span v-else class="dim">—</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
      <BatchBar :n="picks.length" @clear="s.picked = []"><button class="btn sm" @click="batch('提交')">提交</button><button class="btn sm" @click="batch('审核')">审核</button><ExportBtn class="sm" :rows="picks" :cols="XCOLS" name="订单" label="导出选中"/></BatchBar>
    </div>
    <OrderForm v-if="s.add" :st="typeof s.add === 'string' ? s.add : null" :customer="nav.query.customer" @close="s.add = null"/>
    <ImportBox v-if="s.imp" kind="orders" @close="s.imp = false"/>
  </div>`,
}, { mod: 'orders', t: '订单' })

/* ---------- 新建订单、改草稿：一个款一张颜色 × 尺码矩阵；单价默认一个款一个，大码加价时按尺码填（PRD §6.5.1） ---------- */
// 能下单的颜色：这个颜色还有没停用的 SKU
const orderableColors = (st) => style(st).colors.filter((c) => style(st).skus.some((k) => k.color === c && k.status !== '停用'))
comp('OrderForm', {
  props: { st: String, customer: String, o: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const o = p.o
    const liveCust = computed(() => db.customers.filter((c) => c.status === '启用' || c.code === o?.customer))
    const f = reactive(o
      ? { type: o.type, customer: o.customer || '', custNo: o.custNo || '', contract: o.contract || '', brand: o.brand || '', company: o.company, date: o.date, due: o.due, currency: o.currency || '人民币', sales: o.sales, merch: o.merch, ship: o.ship || '', note: o.note || '' }
      : { type: TRADE ? '客户订单' : '自营备货', customer: TRADE ? p.customer || db.customers.find((c) => c.status === '启用')?.code || '' : '', custNo: '', contract: '', brand: db.dicts.品牌[0], company: myCompany(), date: clock.today, due: addDays(clock.today, 60), currency: '人民币', sales: roleUser('业务'), merch: roleUser('生产跟单'), ship: '', note: '', files: [] })
    // 一块 = 一个款：price 默认单价，byLine 时 sizePrice 里的码用自己的单价；due 行交期、note 行备注
    const mk = (code, ls = []) => {
      const prices = ls.map((l) => l.price).filter((x) => x != null), price = prices.length ? Math.min(...prices) : null, sizePrice = {}
      for (const l of ls) if (l.price != null && l.price !== price) sizePrice[l.size] = l.price
      return { style: code, price, sizePrice, byLine: Object.keys(sizePrice).length > 0, due: ls[0]?.due || '', note: ls[0]?.note || '', qty: Object.fromEntries(ls.map((l) => [l.color + '|' + l.size, l.qty])) }
    }
    const blocks = reactive(o ? orderStyles(o).map((st) => mk(st, o.lines.filter((l) => l.style === st))) : [])
    const pickable = computed(() => db.styles.filter((s) => s.status === '启用' && !blocks.some((b) => b.style === s.code)))
    const picking = ref(false)
    const addBlock = (code) => { if (code) blocks.push(mk(code)) }
    if (!o && style(p.st)) addBlock(p.st)
    const priceOf = (b, z) => (b.byLine && b.sizePrice[z] != null && b.sizePrice[z] !== '' ? +b.sizePrice[z] : b.price)
    const linesOf = () => blocks.flatMap((b) => Object.entries(b.qty).map(([k, n]) => { const [color, size] = k.split('|'); return { style: b.style, color, size, qty: n, price: priceOf(b, size), due: b.due || null, note: b.note || '' } }))
    const total = computed(() => sum(blocks, (b) => sum(Object.values(b.qty))))
    const amount = computed(() => sum(linesOf(), (l) => l.qty * (l.price || 0)))
    const colorsFor = (b) => [...new Set([...orderableColors(b.style), ...Object.keys(b.qty).filter((k) => b.qty[k] > 0).map((k) => k.split('|')[0])])]
    function save(submit) {
      const body = { ...f, customer: TRADE && f.type !== '自营备货' ? f.customer : null, lines: linesOf() }
      if (o) {
        if (run(() => updateOrder(o, body, submit)) === undefined) return
        if (submit && !run(() => submitOrder(o))) { emit('close'); return }
        emit('close'); toast(submit ? `${o.no} 已提交，等审核` : `${o.no} 已保存`)
        return
      }
      const x = run(() => createOrder(body))
      if (!x?.no) return
      if (submit && !run(() => submitOrder(x))) { emit('close'); go('/orders/' + x.no); return }
      emit('close'); go('/orders/' + x.no); toast(submit ? `${x.no} 已提交，等审核` : `${x.no} 已存草稿`)
    }
    return { o, f, liveCust, blocks, pickable, picking, addBlock, total, amount, colorsFor, save }
  },
  template: `<Modal :t="o ? '改订单 ' + o.no : '新建订单'" :w="840" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr)" data-c="订单单头">
      <label class="f">类型<Pick class="sel" v-model="f.type"><option v-for="x in db.dicts.订单类型" :key="x">{{ x }}</option></Pick></label>
      <label class="f" v-if="TRADE && f.type !== '自营备货'">客户<Pick class="sel" v-model="f.customer"><option v-for="c in liveCust" :key="c.code" :value="c.code">{{ c.short }}</option></Pick></label>
      <label class="f" v-if="TRADE && f.type !== '自营备货'">客户订单号<input class="inp" v-model="f.custNo"></label>
      <label class="f">合同号<input class="inp" v-model="f.contract"></label>
      <label class="f">品牌<Pick class="sel" v-model="f.brand"><option value="">—</option><option v-for="x in db.dicts.品牌" :key="x">{{ x }}</option></Pick></label>
      <label class="f">主体公司<CompanyPick v-model="f.company"/></label>
      <label class="f">下单日期<DatePick v-model="f.date"/></label>
      <label class="f">{{ DUE_T }}<DatePick v-model="f.due"/></label>
      <label class="f">币种<Pick class="sel" v-model="f.currency"><option v-for="x in db.dicts.币种" :key="x">{{ x }}</option></Pick></label>
      <label class="f">业务员<Pick class="sel" v-model="f.sales"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></Pick></label>
      <label class="f">跟单员<Pick class="sel" v-model="f.merch"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></Pick></label>
      <label class="f full">送货要求<input class="inp" v-model="f.ship" :placeholder="TRADE ? '如：分两批送，先送黑色；送到客户杭州仓' : '如：分两批送，先送黑色；送到成品仓'"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <div v-for="(b, i) in blocks" :key="b.style" class="sec" style="margin-top:20px" data-c="录单矩阵">
      <h3><Thumb :code="b.style" :s="24"/><span class="code">{{ b.style }}</span>{{ styleName(b.style) }}
        <span class="acts"><label class="row g4 mut">单价<input class="inp num" style="width:90px;height:26px" type="number" v-model.number="b.price"></label>
          <label class="row g4 mut" style="font-size:12px" title="大码加价时，各码单价不一样"><input type="checkbox" v-model="b.byLine">按尺码定价</label>
          <button class="btn ghost icon sm" @click="blocks.splice(i, 1)"><Ico n="x" :s="13"/></button></span></h3>
      <div v-if="b.byLine" class="row g4" style="margin-bottom:8px;flex-wrap:wrap" data-c="尺码单价"><span class="dim" style="font-size:12px">分码单价</span>
        <label v-for="z in sizesOf(style(b.style).sizeGroup)" :key="z" class="row g4" style="font-size:12px">{{ z }}<input class="inp num" style="width:72px;height:26px" type="number" v-model.number="b.sizePrice[z]" :placeholder="b.price ?? ''"></label></div>
      <Matrix :st="b.style" v-model="b.qty" :colors="colorsFor(b)"/>
      <div class="row g4" style="margin-top:8px" data-c="行交期、行备注"><label class="row g4 mut" style="font-size:12px">行交期<DatePick sm v-model="b.due" placeholder="未设行交期"/></label>
        <input class="inp" style="height:26px;flex:1" v-model="b.note" placeholder="这个款的备注，如：黑色先做"></div>
    </div>
    <div class="row" style="margin-top:16px"><button class="btn" :disabled="!pickable.length" data-c="选款式" @click="picking = true"><Ico n="shirt" :s="14"/>{{ blocks.length ? '再加款式' : '选款式' }}</button></div>
    <DataPick v-if="picking" kind="style" :have="blocks.map(b => b.style)" @pick="cs => cs.forEach(addBlock)" @close="picking = false"/>
    <div v-if="!o" class="sec" data-c="订单附件"><h3>附件</h3><Pics v-model="f.files" files :s="56" :max="20"/></div>
    <template #foot><span class="mut">{{ total }} 件<template v-if="amount"> · {{ orderMoney(f, amount) }}</template></span><span class="sp"></span>
      <button class="btn" @click="save(false)">{{ o ? '保存' : '存草稿' }}</button><button class="btn pri" @click="save(true)">{{ o ? '保存并提交' : '提交审核' }}</button></template>
  </Modal>`,
})

/* ---------- 订单详情 ---------- */
route('/orders/:no', {
  props: ['no'],
  setup(p) {
    const o = computed(() => order(p.no))
    const tab = ref(nav.query.tab || 'lines')
    const dlg = ref(''), chg = ref(null)
    const lineMap = (st) => Object.fromEntries(o.value.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, l.qty]))
    const cellSub = (st) => (c, z) => {
      const l = o.value.lines.find((x) => x.style === st && x.color === c && x.size === z)
      if (!l || !orderLive(o.value) && o.value.status !== '已完成') return ''
      return `排 ${scheduledQty(o.value, l)} · 回 ${returnedQty(o.value, l)}`
    }
    const lockedVer = (st) => o.value.lines.find((l) => l.style === st)?.ver
    const lineOf = (st) => o.value.lines.find((l) => l.style === st) || {}
    const down = computed(() => {
      const ms = mrpsOf(o.value)
      return [
        ...prodsOf(o.value).map((x) => ({ type: '加工单', no: x.no, to: '/production/' + x.no, who: supName(x.factory), qty: prodQty(x), status: x.status, p: x })),
        ...ms.map((x) => ({ type: '算料单', no: x.no, to: '/mrp/' + x.no, who: '', qty: null, status: x.status })),
        ...db.purchases.filter((x) => x.lines.some((l) => ms.some((m) => m.no === l.src?.mrp))).map((x) => ({ type: '采购单', no: x.no, to: '/purchases/' + x.no, who: supName(x.supplier), qty: null, status: x.status })),
        ...liveReturns().filter((x) => x.order === o.value.no).map((x) => ({ type: '回货单', no: x.no, to: '/production/' + x.prod, who: supName(x.factory), qty: sum(x.lines, (l) => l.qty), status: '已完成' })),
      ]
    })
    const changes = computed(() => db.changes.filter((c) => c.order === p.no).slice().reverse())
    const pendingChange = computed(() => changes.value.find((c) => c.status === '待审核'))
    const a = (x) => docAct('order', o.value, x, x === '提交' ? `${o.value.no} 已提交，等审核` : null)
    function doMrp() { const m = run(() => createMrp([o.value.no])); if (m?.no) { go('/mrp/' + m.no); toast(`${m.no} 算好了，勾选要买的行生成采购单`) } }
    async function remove() { if (await ask('删除订单 ' + o.value.no, '草稿删除后找不回来；想留个底就用「作废」。', '删除', true) && run(() => removeDraft(db.orders, o.value))) { go('/orders'); toast('删了') } }
    const openChange = (c, cancel) => { chg.value = c || null; dlg.value = cancel ? 'cancel' : 'change' }
    const more = computed(() => [
      can('order', o.value, '撤回') && { t: '撤回', fn: () => a('撤回') },
      can('order', o.value, '反审核') && { t: '反审核', fn: () => a('反审核') },
      can('order', o.value, '结案') && { t: '结案', fn: () => a('结案') },
      can('order', o.value, '取消结案') && { t: '取消结案', fn: () => a('取消结案') },
      orderLive(o.value) && { t: '取消订单（走变更单）', danger: true, fn: () => openChange(null, true) },
      can('order', o.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') },
      o.value.status === '草稿' && { t: '删除', danger: true, fn: remove },
    ].filter(Boolean))
    return { o, tab, dlg, chg, lineMap, cellSub, lockedVer, lineOf, down, changes, pendingChange, a, doMrp, more, openChange, stylePriceText, styleAmt, orderMoney, auditLog }
  },
  template: `<div v-if="o">
    <Head :t="o.no" :back="['订单', '/orders']">
      <button v-if="o.status === '草稿'" class="btn" @click="dlg = 'edit'">编辑</button>
      <button v-if="can('order', o, '提交')" class="btn pri" @click="a('提交')">提交审核</button>
      <template v-if="mayAct('order', o, '审核')"><button class="btn" @click="a('驳回')">驳回</button><button class="btn pri" @click="dlg = 'audit'">审核</button></template><ApproveWait v-else kind="order" :d="o"/>
      <template v-if="orderLive(o)"><button class="btn" @click="openChange()">提变更</button><button class="btn" @click="doMrp">算料</button><button v-if="o.lines.some(l => scheduledQty(o, l) < l.qty)" class="btn pri" @click="dlg = 'schedule'">排单</button></template>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ custOrDash(o) }}<St :s="o.status"/></div>
        <div class="sub"><span>{{ orderQty(o).toLocaleString() }} 件</span><span v-if="orderAmt(o)">{{ orderMoney(o, orderAmt(o)) }}</span><span>交期 {{ fd(o.commit || o.due) }}</span><Days v-if="orderLive(o)" :d="o.commit || o.due" :warn="7"/></div>
        <div v-if="pendingChange" class="banner"><Ico n="diff"/>变更单 {{ pendingChange.no }} 待审核：{{ describeChange(pendingChange) }}<button class="btn sm" @click="tab = 'changes'">去看</button></div>
        <Tabs v-model="tab" :items="[['lines', '明细'], ['down', '下游单据', down.length], ['changes', '变更', changes.length]]"/>
        <section v-if="tab === 'lines'" data-c="订单明细">
          <div v-for="st in orderStyles(o)" :key="st" style="margin-bottom:28px">
            <div class="row" style="margin-bottom:8px;flex-wrap:wrap"><Thumb :code="st" :s="28"/><a class="code" :href="'#/styles/' + st">{{ st }}</a><span>{{ styleName(st) }}</span>
              <span class="pill" :class="lockedVer(st) ? 'blue' : ''">资料包 {{ lockedVer(st) ? 'V' + lockedVer(st) + ' 已锁定' : (activeTp(st) ? 'V' + activeTp(st).ver : '未生效') }}</span>
              <span style="flex:1"></span><span class="mut">单价 {{ stylePriceText(o, st) }}</span><span class="mut">小计 {{ orderMoney(o, styleAmt(o, st)) }}</span></div>
            <div v-if="lineOf(st).due || lineOf(st).note" class="dim" style="font-size:12px;margin:-2px 0 8px" data-c="行交期、行备注"><span v-if="lineOf(st).due">行交期 {{ fd(lineOf(st).due) }}　</span><span v-if="lineOf(st).note">{{ lineOf(st).note }}</span></div>
            <Matrix :st="st" :model-value="lineMap(st)" ro :colors="[...new Set(o.lines.filter(l => l.style === st).map(l => l.color))]" :sub="cellSub(st)"/>
          </div>
        </section>
        <section v-if="tab === 'down'" data-c="下游单据">
          <Tbl inset flat :rows="down" link @row="x => go(x.to)" empty="还没有下游单据：审核后点「排单」「算料」" :cols="[
            { k: 'type', t: '类型' }, { k: 'no', t: '单号', c: 'code' }, { k: 'who', t: '对方' }, { k: 'qty', t: '数量', c: 'num', f: x => x.qty ?? '' }, { k: 'nodes', t: '进度' }, { k: 'status', t: '状态' }]">
            <template #nodes="{ r }"><Nodes v-if="r.p" :p="r.p"/></template>
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </section>
        <section v-if="tab === 'changes'" data-c="变更记录">
          <Tbl inset flat :rows="changes" empty="没有变更。订单审核后要改数量、颜色、交期、单价，点「提变更」" :cols="[{ k: 'no', t: '变更单号', c: 'code' }, { k: 'types', t: '类型', f: c => c.types.join('、') }, { k: 'text', t: '改了什么' }, { k: 'reason', t: '原因' }, { k: 'fee', t: '费用说明', f: c => c.fee || '—' }, { k: 'party', t: '责任方' }, { k: 'by', t: '提交', f: c => userName(c.by) + ' · ' + fd(c.date) }, { k: 'aud', t: '审核', f: c => auditLog(c) ? auditLog(c).who + ' · ' + fd(auditLog(c).at) : '—' }, { k: 'status', t: '状态' }]">
            <template #text="{ r }"><span class="wrap">{{ describeChange(r) }}</span></template>
            <template #reason="{ r }"><span class="wrap">{{ r.reason }}</span><Pics v-if="r.files && r.files.length" :model-value="r.files" readonly :s="32" :limit="3" style="margin-top:4px"/></template>
            <template #fee="{ r }"><span class="wrap">{{ r.fee || '—' }}</span></template>
            <template #status="{ r }"><span class="row" style="flex-wrap:wrap"><St :s="r.status"/>
              <template v-if="mayAct('change', r, '审核')"><button class="btn sm" @click="docAct('change', r, '驳回')">驳回</button><button class="btn sm pri" @click="docAct('change', r, '审核', '变更已写回订单，下游单据已提示')">审核</button></template><ApproveWait v-else kind="change" :d="r"/>
              <button v-if="r.status === '已驳回' && orderLive(o)" class="btn sm" @click="openChange(r)">改了再提交</button></span></template>
          </Tbl>
        </section>
      </div>
      <aside class="aside" data-c="订单属性">
        <dl class="props">
          <dt>类型</dt><dd>{{ o.type }}</dd>
          <template v-if="TRADE"><dt v-if="o.customer">客户</dt><dd v-if="o.customer"><a :href="'#/customers/' + o.customer">{{ custName(o.customer) }}</a></dd>
            <dt>客户单号</dt><dd class="code">{{ o.custNo || '—' }}</dd></template>
          <dt>合同号</dt><dd class="code">{{ o.contract || '—' }}</dd>
          <dt>品牌</dt><dd>{{ o.brand || '—' }}</dd>
          <dt>下单日期</dt><dd>{{ fd(o.date) }}</dd>
          <dt>{{ DUE_T }}</dt><dd>{{ fd(o.due) }}</dd>
          <dt>承诺交期</dt><dd>{{ fd(o.commit) }}</dd>
          <dt>币种</dt><dd>{{ o.currency || '人民币' }}</dd>
          <dt>业务员</dt><dd>{{ userName(o.sales) }}</dd>
          <dt>跟单员</dt><dd>{{ userName(o.merch) }}</dd>
          <dt>送货要求</dt><dd class="wrap">{{ o.ship || '—' }}</dd>
          <dt>来源</dt><dd>{{ o.src || '手工' }}</dd>
          <DocMeta :d="o"/>
          <template v-if="orderLive(o) || o.status === '已完成'">
            <dt>齐料</dt><dd>{{ kitState(o).t }}</dd>
            <dt>排产</dt><dd>{{ orderSched(o) }} / {{ orderQty(o) }}</dd>
            <dt>回货</dt><dd>{{ orderRet(o) }} / {{ orderQty(o) }}</dd>
          </template>
        </dl>
        <ApproveSteps kind="order" :d="o"/>
        <DocFiles :doc="o"/>
        <h4>操作记录</h4><Log :items="o.log"/>
      </aside>
    </div>
    <OrderForm v-if="dlg === 'edit'" :o="o" @close="dlg = ''"/>
    <OrderAudit v-if="dlg === 'audit'" :o="o" @close="dlg = ''"/>
    <OrderSchedule v-if="dlg === 'schedule'" :o="o" @close="dlg = ''" @done="tab = 'down'"/>
    <OrderChange v-if="dlg === 'change' || dlg === 'cancel'" :o="o" :c="chg" :cancel="dlg === 'cancel'" @close="dlg = ''" @done="tab = 'changes'"/>
  </div><NotFound v-else/>`,
}, { mod: 'orders', t: (p) => '订单 ' + p.no })

/* ---------- 审核即评审：资料包、齐料参考、在手负荷，填承诺交期 ---------- */
comp('OrderAudit', {
  props: { o: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const info = computed(() => reviewInfo(p.o))
    const commit = ref(p.o.commitDraft || p.o.due)
    function ok() {
      if (!run(() => doAction('order', p.o, '审核', commit.value))) return
      emit('close')
      toast(p.o.status === '待审核' ? `${p.o.no} 这一级审过了，等${lvName(curLevel('order', p.o))}审` : `${p.o.no} 已审核，资料包已锁定`)
    }
    return { info, commit, ok }
  },
  template: `<Modal :t="'审核 ' + o.no" :w="640" @close="$emit('close')">
    <div class="sec" style="margin-top:0" data-c="评审：资料包"><h3>资料包</h3>
      <div v-for="x in info.packs" :key="x.style" class="row" style="height:30px"><Thumb :code="x.style" :s="22"/><span class="code">{{ x.style }}</span>
        <St v-if="x.tp" s="已生效" :t="'V' + x.tp.ver + ' 审核后锁定'"/><span v-else class="flag red">资料包没生效，不能审</span></div></div>
    <div class="sec" data-c="评审：齐料参考"><h3>缺口最大的料</h3>
      <Tbl inset flat :rows="info.short" empty="现有库存够用" :cols="[{ k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'need', t: '需求', c: 'num', f: x => fq(x.need) + ' ' + unitOf(x.sku) }, { k: 'avail', t: '可用', c: 'num', f: x => fq(x.avail) }, { k: 'gap', t: '缺口', c: 'num', f: x => fq(x.gap) }, { k: 'lead', t: '采购周期', c: 'num', f: x => matOfSku(x.sku).lead + ' 天' }]">
        <template #gap="{ r }"><span class="red">{{ fq(r.gap) }}</span></template></Tbl></div>
    <div class="sec" data-c="评审：工厂负荷"><h3>默认加工厂在手</h3>
      <div v-for="x in info.loads" :key="x.factory" class="row" style="height:28px"><span>{{ supName(x.factory) }}</span><span style="flex:1"></span><span class="mut">在制 {{ x.qty.toLocaleString() }} 件</span></div></div>
    <div class="sec"><label class="f" style="width:200px">承诺交期<DatePick v-model="commit"/></label></div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">通过</button></template>
  </Modal>`,
})

/* ---------- 排单：每个款一块，可以拆给几个工厂；一块生成一张加工单（单头字段见 PRD §6.7.1） ---------- */
comp('OrderSchedule', {
  props: { o: Object },
  emits: ['close', 'done'],
  setup(p, { emit }) {
    const factories = computed(() => db.suppliers.filter(isFactory))
    const remain = (st) => Object.fromEntries(p.o.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, Math.max(0, l.qty - scheduledQty(p.o, l))]))
    const due = addDays(p.o.commit || p.o.due, -10)
    // settle 空着 = 按加工厂档案上的结算方式
    // 加工单价默认取价格档案里 工厂 × 款式 × 加工方式 的现价，没有就空着手填（PRD §6.10 规则 3）；和现价不一样要写改价原因（why）
    const refOf = (b) => curProcPrice(b.factory, b.style, b.mode)
    const mk = (st, qty) => { const b = { style: st, factory: style(st).factory, mode: '清加工', price: null, why: '', due, merch: p.o.merch, settle: '', terms: '', note: '', qty }; b.price = b.dft = refOf(b); return b }
    // 换了工厂、加工方式：单价没手改过（还是带出来的）就换成新的现价
    const reprice = (b) => { const d = refOf(b); if (b.price == null || b.price === '' || b.price === b.dft) b.price = d; b.dft = d }
    const priceOff = (b) => refOf(b) != null && b.price !== '' && b.price != null && b.price !== refOf(b)
    const blocks = reactive(orderStyles(p.o).filter((st) => sum(Object.values(remain(st)))).map((st) => mk(st, remain(st))))
    const more = (st) => blocks.splice(blocks.map((b) => b.style).lastIndexOf(st) + 1, 0, mk(st, {}))
    const colorsOf = (st) => [...new Set(p.o.lines.filter((l) => l.style === st).map((l) => l.color))]
    const sub = (st) => (c, z) => '余 ' + (remain(st)[c + '|' + z] ?? 0)
    function ok() {
      const ps = run(() => scheduleOrder(p.o, blocks))
      if (!ps?.length) return
      emit('close'); emit('done')
      toast(`生成 ${ps.length} 张加工单草稿`, ['打开 ' + ps[0].no, '/production/' + ps[0].no])
    }
    return { factories, blocks, more, colorsOf, sub, ok, refOf, reprice, priceOff }
  },
  template: `<Modal :t="'排单 ' + o.no" :w="840" @close="$emit('close')">
    <div v-if="!blocks.length" class="empty">订单的数量都已经排完了</div>
    <div v-for="(b, i) in blocks" :key="i" class="sec" :style="i ? '' : 'margin-top:0'" data-c="排单块">
      <h3><Thumb :code="b.style" :s="24"/><span class="code">{{ b.style }}</span>{{ styleName(b.style) }}<span class="acts"><button class="btn ghost sm" @click="more(b.style)"><Ico n="plus" :s="12"/>再加一个工厂</button><button v-if="blocks.filter(x => x.style === b.style).length > 1" class="btn ghost icon sm" @click="blocks.splice(i, 1)"><Ico n="x" :s="12"/></button></span></h3>
      <div class="form" style="grid-template-columns:2fr 1fr 1fr 1.3fr;margin-bottom:10px">
        <label class="f">加工厂<Pick class="sel" v-model="b.factory" @change="reprice(b)"><option v-for="s in factories" :key="s.code" :value="s.code" :disabled="s.status !== '合作中'">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></Pick></label>
        <label class="f">加工方式<Pick class="sel" v-model="b.mode" @change="reprice(b)"><option v-for="x in db.dicts.加工方式" :key="x">{{ x }}</option></Pick></label>
        <label class="f">单价（元/件）<input class="inp num" type="number" v-model.number="b.price" :placeholder="refOf(b) == null ? '档案里没有，手填' : ''">
          <small class="dim" style="font-size:11.5px">{{ refOf(b) != null ? '档案现价 ' + fm(refOf(b)) : '档案里没有这家 × ' + b.mode + ' 的价' }}<template v-if="activeTp(b.style)"> · 工价合计 {{ fm(tpLabor(activeTp(b.style))) }}</template></small></label>
        <label class="f">交期<DatePick v-model="b.due"/></label>
        <label class="f">生产跟单<Pick class="sel" v-model="b.merch"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></Pick></label>
        <label class="f">结算方式<Pick class="sel" v-model="b.settle"><option value="">按加工厂（{{ sup(b.factory) ? sup(b.factory).settle : '—' }}）</option><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></Pick></label>
        <label class="f" style="grid-column:span 2">条款<input class="inp" v-model="b.terms" placeholder="如：次品率超 3% 扣款；延期每天扣 0.5%"></label>
        <label v-if="priceOff(b)" class="f full" data-c="改价原因">改价原因<input class="inp" v-model="b.why" :placeholder="'单价和档案现价 ' + fm(refOf(b)) + ' 不一样，写清楚为什么；审核时记进价格档案'"></label>
        <label class="f full">备注<input class="inp" v-model="b.note"></label>
      </div>
      <Matrix :st="b.style" v-model="b.qty" :colors="colorsOf(b.style)" :sub="sub(b.style)"/>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!blocks.length" @click="ok">生成加工单</button></template>
  </Modal>`,
})

/* ---------- 提变更（PRD §6.5.6）：数量、配色配码（加颜色）、交期、单价、换资料包版本，或者取消整张订单；被驳回的改了再提交 ---------- */
comp('OrderChange', {
  props: { o: Object, c: Object, cancel: Boolean },
  emits: ['close', 'done'],
  setup(p, { emit }) {
    const c0 = p.c, sts = orderStyles(p.o)
    const firstLine = (st) => p.o.lines.find((l) => l.style === st)
    const qty = reactive(Object.fromEntries(sts.map((st) => [st, Object.fromEntries(p.o.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, l.qty]))])))
    const extra = reactive(Object.fromEntries(sts.map((st) => [st, []]))) // 这次新加的颜色
    const price = reactive(Object.fromEntries(sts.map((st) => [st, firstLine(st)?.price ?? ''])))
    const ver = reactive(Object.fromEntries(sts.map((st) => [st, firstLine(st)?.ver ?? ''])))
    const f = reactive({ cancel: p.cancel, due: p.o.due, reason: '', party: CHANGE_PARTIES[0], fee: '', files: [] })
    if (c0) { // 改了再提交：把上次的改动放回去
      Object.assign(f, { cancel: !!c0.cancel, due: c0.due?.to || p.o.due, reason: c0.reason, party: c0.party, fee: c0.fee, files: [...(c0.files || [])] })
      for (const i of c0.items) {
        const [st, co, sz] = i.k.split('|')
        if (!qty[st]) continue
        qty[st] = { ...qty[st], [co + '|' + sz]: i.to }
        if (!p.o.lines.some((l) => l.style === st && l.color === co) && !extra[st].includes(co)) extra[st].push(co)
      }
      for (const x of c0.prices || []) price[x.style] = x.to ?? ''
      for (const x of c0.vers || []) ver[x.style] = x.to
    }
    const colorsOf = (st) => [...new Set(p.o.lines.filter((l) => l.style === st).map((l) => l.color)), ...extra[st]]
    const addable = (st) => orderableColors(st).filter((c) => !colorsOf(st).includes(c))
    const vers = (st) => tpsOf(st).filter((t) => t.status === '已生效' || t.ver === firstLine(st)?.ver)
    const sub = (st) => (c, z) => { const l = p.o.lines.find((x) => x.style === st && x.color === c && x.size === z); return l ? '原 ' + l.qty + ' · 已排 ' + scheduledQty(p.o, l) : '新加' }
    const down = computed(() => orderDown(p.o))
    function ok() {
      const body = { ...f, items: f.cancel ? [] : Object.entries(qty).flatMap(([st, m]) => Object.entries(m).map(([k, n]) => ({ k: st + '|' + k, to: n }))),
        prices: Object.entries(price).map(([style, to]) => ({ style, to })), vers: Object.entries(ver).map(([style, to]) => ({ style, to })) }
      if (c0) { if (run(() => resubmitChange(c0, body))) { emit('close'); emit('done'); toast(`${c0.no} 已重新提交`) } return }
      const c = run(() => createChange(p.o, body))
      if (c?.no) { emit('close'); emit('done'); toast(`${c.no} 已提交，审核后写回订单`) }
    }
    return { c0, qty, extra, price, ver, f, colorsOf, addable, vers, sub, down, ok }
  },
  template: `<Modal :t="c0 ? '改变更单 ' + c0.no : f.cancel ? '取消订单 ' + o.no : '变更 ' + o.no" :w="840" @close="$emit('close')">
    <label class="row g4" style="margin-bottom:14px" data-c="取消订单"><input type="checkbox" v-model="f.cancel">取消整张订单</label>
    <div v-if="f.cancel && down.length" class="banner" data-c="要先处理的下游单据"><Ico n="alert"/><span>先处理这些下游单据：<template v-for="no in down" :key="no"><a class="code" :href="'#/' + (no.startsWith('JG') ? 'production' : 'mrp') + '/' + no">{{ no }}</a>　</template></span></div>
    <template v-if="!f.cancel">
      <div v-for="st in Object.keys(qty)" :key="st" style="margin-bottom:18px" data-c="变更矩阵">
        <div class="row" style="margin-bottom:6px;flex-wrap:wrap"><Thumb :code="st" :s="22"/><span class="code">{{ st }}</span>{{ styleName(st) }}<span style="flex:1"></span>
          <Pick v-if="addable(st).length" class="sel" style="height:26px" @change="extra[st].push($event)" data-c="配色配码" modelValue=""><option value="">加颜色……</option><option v-for="c in addable(st)" :key="c" :value="c">{{ colorName(c) }}</option></Pick>
          <label class="row g4 mut" style="font-size:12px">单价<input class="inp num" style="width:84px;height:26px" type="number" v-model.number="price[st]"></label>
          <label class="row g4 mut" style="font-size:12px">资料包<Pick class="sel" style="height:26px" v-model.number="ver[st]"><option v-for="t in vers(st)" :key="t.ver" :value="t.ver">V{{ t.ver }}{{ t.status === '已生效' ? '' : '（' + t.status + '）' }}</option></Pick></label></div>
        <Matrix :st="st" v-model="qty[st]" :colors="colorsOf(st)" :sub="sub(st)"/></div>
      <div class="form"><label class="f">{{ DUE_T }}<DatePick v-model="f.due"/></label></div>
    </template>
    <div class="form" style="margin-top:12px">
      <label class="f">责任方<Pick class="sel" v-model="f.party"><option v-for="x in CHANGE_PARTIES" :key="x">{{ x }}</option></Pick></label>
      <label class="f full">原因<input class="inp" v-model="f.reason"></label>
      <label class="f full">费用说明<input class="inp" v-model="f.fee" :placeholder="TRADE ? '如：客户承担已裁片损失' : '如：已裁片损失由我方承担'"></label>
      <div class="f full" data-c="变更附件">{{ TRADE ? '客户确认' : '变更依据' }}<Pics v-model="f.files" files :s="56" :tip="TRADE ? '客户的邮件、聊天截图；Ctrl+V 直接贴' : '审批截图、邮件、聊天记录；Ctrl+V 直接贴'"/></div>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ c0 ? '重新提交' : '提交变更' }}</button></template>
  </Modal>`,
})

/* ---------- 变更单列表 ---------- */
route('/changes', {
  setup() {
    const s = reactive({ tab: '全部', q: '', mine: false, flt: newFlt() })
    const scoped = computed(() => db.changes.filter((c) => inScope(s.mine, c, order(c.order))))
    const tabs = computed(() => ['全部', '待审核', '已审核', '已驳回'].map((k) => [k, k, k === '全部' ? scoped.value.length : scoped.value.filter((c) => c.status === k).length]))
    const rows = computed(() => scoped.value.filter((c) => (s.tab === '全部' || c.status === s.tab) && matchQ(s.q, c.no, c.order, ...c.items.map((i) => i.label)) && passFlt(s.flt, { date: c.date, party: order(c.order)?.customer })).slice().reverse())
    return { s, tabs, rows, auditLog }
  },
  template: `<div>
    <Head t="变更"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><Filters v-model="s.flt" :party="TRADE ? 'customer' : ''" label="提交"/><span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q" ph="变更单号 / 订单号"/></div>
    <div data-c="变更列表">
      <Tbl :rows="rows" link @row="c => go('/orders/' + c.order + '?tab=changes')" empty="没有符合条件的变更单。已审核的订单在详情页「提变更」" :cols="[
        { k: 'no', t: '变更单号', c: 'code' }, { k: 'order', t: '订单', c: 'code' }, { k: 'types', t: '类型', f: c => c.types.join('、') }, { k: 'text', t: '改了什么' }, { k: 'reason', t: '原因' },
        { k: 'fee', t: '费用说明', f: c => c.fee || '—' }, { k: 'party', t: '责任方' }, { k: 'by', t: '提交', f: c => userName(c.by) + ' · ' + fd(c.date) }, { k: 'aud', t: '审核', f: c => auditLog(c) ? auditLog(c).who + ' · ' + fd(auditLog(c).at) : '—' }, { k: 'status', t: '状态' }]">
        <template #text="{ r }"><span class="wrap">{{ describeChange(r) }}</span></template>
        <template #status="{ r }"><span class="row" @click.stop><St :s="r.status"/><template v-if="mayAct('change', r, '审核')"><button class="btn sm" @click="docAct('change', r, '驳回')">驳回</button><button class="btn sm pri" @click="docAct('change', r, '审核', '变更已写回订单，下游单据已提示')">审核</button></template><ApproveWait v-else kind="change" :d="r"/></span></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'changes', t: '变更' })

expose({ createMrp, submitOrder, custOrDash, orderMoney, updateOrder, CHANGE_PARTIES })

/* ---------- Excel 导入订单（PRD §6.5.2）：一行一个 SKU，同一个单号的行进同一张订单 ---------- */
// 分单的列：代工（TRADE）按客户订单号，只做自营按合同号
const ORDER_GROUP = TRADE ? { k: 'custNo', t: '客户订单号', ex: 'PO-2611-018' } : { k: 'contract', t: '合同号', ex: 'HT-2611-018' }
IMPORTS.orders = {
  title: '导入订单', name: '订单',
  // 按「分单号」分单（选填）：同一个分单号的行进同一张订单，都不填整个文件是一张订单；合同号（TRADE 开时客户订单号）只是记到订单上
  note: `一行一个 SKU；同一个分单号的行进同一张订单，分单号都不填就是一张订单。${ORDER_GROUP.t}记到订单上。导进来是草稿，核对后再提交审核。`,
  head: [
    { k: 'type', t: '订单类型', opts: () => db.dicts.订单类型.map((x) => [x, x]), def: () => (TRADE ? '客户订单' : '自营备货') },
    TRADE ? { k: 'customer', t: '客户', opts: () => [['', '—'], ...db.customers.filter((c) => c.status === '启用').map((c) => [c.code, c.short])], def: () => db.customers[0]?.code || '' }
      : { k: 'brand', t: '品牌', opts: () => [['', '—'], ...db.dicts.品牌.map((x) => [x, x])], def: () => db.dicts.品牌[0] },
    { k: 'due', t: DUE_T, type: 'date', def: () => addDays(clock.today, 60) },
    { k: 'merch', t: '跟单员', opts: () => db.users.map((u) => [u.id, u.name]), def: () => 'u1' },
  ],
  cols: [
    { k: 'split', t: '分单号', tip: '选填；同一个分单号的行进同一张订单，都不填整个文件是一张订单', ex: '1', text: true, w: 8 },
    { ...ORDER_GROUP, tip: '选填，记到订单上；同一张订单的行要填一样的', text: true, w: 16 },
    { k: 'style', t: '款号', req: true, tip: '要是启用的款', ex: '27CF001', text: true },
    { k: 'color', t: '颜色', req: true, tip: '颜色名称或编码，要是这个款有的颜色', ex: '卡其' },
    { k: 'size', t: '尺码', req: true, tip: '要是这个款尺码组里的', ex: 'M', text: true, w: 8 },
    { k: 'qty', t: '数量', req: true, int: true, tip: '正整数，件', ex: 120, w: 8 },
    { k: 'price', t: '单价', num: true, tip: '选填，元', ex: 189, w: 8 },
    { k: 'due', t: '行交期', date: true, tip: `选填；不填用对话框里选的${DUE_T}。同一张单不同款交期不同时填`, ex: '2027-01-20' },
  ],
  example: () => db.styles.filter((s) => s.status === '启用' && activeTp(s.code)).slice(0, 2).flatMap((s, i) =>
    s.colors.slice(0, 2).flatMap((c) => sizesOf(s.sizeGroup).slice(0, 3).map((z, j) => ({ split: String(i + 1), [ORDER_GROUP.k]: ORDER_GROUP.ex.slice(0, -2) + (18 + i), style: s.code, color: colorName(c), size: z, qty: 60 + j * 20, price: s.tagPrice ? rnd(s.tagPrice * 0.4, 0) : '' })))),
  check(rows, bad, head) {
    if (TRADE && head.type !== '自营备货' && !head.customer) bad('—', '客户', head.type + '要选客户')
    if (!head.due) bad('—', DUE_T, '要选' + DUE_T)
    const groups = new Map(), seen = new Map(), nos = new Map() // nos：分单号 → 这张单的合同号（客户订单号），第一次填的那行
    rows.forEach((r) => {
      const g = String(r.split ?? '').trim(), no = String(r[ORDER_GROUP.k] ?? '').trim()
      if (no && nos.get(g) && nos.get(g).no !== no) return bad(r, ORDER_GROUP.t, `和第 ${nos.get(g).r} 行同一张订单${g ? '（分单号 ' + g + '）' : ''}，${ORDER_GROUP.t}却不一样；要分成两张单就填不同的分单号`)
      if (no && !nos.get(g)) nos.set(g, { no, r: r._r })
      const s = style(r.style)
      if (!s) return bad(r, '款号', `没有款号 ${r.style}`)
      if (s.status !== '启用') return bad(r, '款号', `${r.style} 现在是「${s.status}」，只有启用的款能下单`)
      const c = s.colors.find((x) => x === r.color.toUpperCase() || colorName(x) === r.color)
      if (!c) return bad(r, '颜色', `${r.style} 没有颜色「${r.color}」，有：${s.colors.map(colorName).join('、')}`)
      const z = r.size.toUpperCase()
      if (!sizesOf(s.sizeGroup).includes(z)) return bad(r, '尺码', `${r.style} 的尺码里没有「${r.size}」，有：${sizesOf(s.sizeGroup).join(' ')}`)
      const key = [g, s.code, c, z].join('|')
      if (seen.has(key)) return bad(r, '尺码', `和第 ${seen.get(key)} 行是同一张订单的同一个 SKU，合成一行再导`)
      seen.set(key, r._r)
      if (!groups.has(g)) groups.set(g, [])
      groups.get(g).push({ style: s.code, color: c, size: z, qty: r.qty, price: r.price, due: r.due || null })
    })
    return [...groups].map(([g, lines]) => [nos.get(g)?.no || '', lines])
  },
  save(groups, head) {
    const made = groups.map(([no, lines]) => {
      const o = createOrder({ type: head.type, customer: TRADE && head.type !== '自营备货' ? head.customer : null, [ORDER_GROUP.k]: no, brand: head.brand || '', due: head.due, merch: head.merch, src: 'Excel 导入', lines })
      act(o, `Excel 导入 ${lines.length} 行`)
      return o
    })
    return { text: `建了 ${made.length} 张订单草稿：${made.map((o) => o.no).join('、')}。核对后提交审核`, to: made.length === 1 ? '/orders/' + made[0].no : '/orders' }
  },
}
