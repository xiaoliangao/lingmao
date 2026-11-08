/* ============================================================
 * 订单（M06）：列表、颜色 × 尺码矩阵录单、审核即评审、排单、订单变更
 * ============================================================ */
SEARCH.push(() => db.orders.map((o) => ({ t: o.no + ' ' + (o.customer ? custName(o.customer) : o.type), sub: '订单 ' + orderStyles(o).join(' ') + (o.custNo ? ' ' + o.custNo : ''), to: '/orders/' + o.no, i: 'file' })))
const custOrDash = (o) => (o.customer ? custName(o.customer) : o.type)
const merchants = () => db.users

route('/orders', {
  setup() {
    const s = reactive({ tab: nav.query.s || '全部', f: nav.query.f || '', q: '', add: nav.query.new || null })
    const tabs = computed(() => ['全部', '草稿', '待审核', '已审核', '生产中', '已完成'].map((k) => [k, k, k === '全部' ? db.orders.length : db.orders.filter((o) => o.status === k).length]))
    const rows = computed(() => {
      const ks = s.q.trim().toLowerCase().split(/\s+/).filter(Boolean)
      return db.orders.filter((o) => (s.tab === '全部' || o.status === s.tab) && (s.f !== '待排单' || (orderLive(o) && orderSched(o) === 0))
        && (!ks.length || ks.some((k) => [o.no, o.custNo, custOrDash(o), ...orderStyles(o)].join(' ').toLowerCase().includes(k)))).slice().reverse()
    })
    return { s, tabs, rows, custOrDash }
  },
  template: `<div>
    <Head t="订单"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建订单</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><button v-if="s.f" class="chip on" @click="s.f = ''">{{ s.f }}<Ico n="x" :s="12"/></button><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="订单列表">
      <Tbl :rows="rows" link @row="o => go('/orders/' + o.no)" empty="没有符合条件的订单" :cols="[
        { k: 'no', t: '订单号', c: 'code' }, { k: 'cust', t: '客户', f: custOrDash }, { k: 'styles', t: '款式' },
        { k: 'qty', t: '数量', c: 'num', f: o => orderQty(o).toLocaleString() }, { k: 'amt', t: '金额', c: 'num', f: o => orderAmt(o) ? fm(orderAmt(o)) : '—' },
        { k: 'due', t: '交期', f: o => fd(o.commit || o.due) }, { k: 'sched', t: '排产' }, { k: 'ret', t: '回货' }, { k: 'kit', t: '齐料' },
        { k: 'merch', t: '跟单', f: o => userName(o.merch) }, { k: 'status', t: '状态' }]">
        <template #styles="{ r }"><span class="row g4"><Thumb v-for="c in orderStyles(r)" :key="c" :code="c" :s="22"/><span class="code mut">{{ orderStyles(r).join(' ') }}</span></span></template>
        <template #sched="{ r }"><Prog v-if="r.status !== '草稿' && r.status !== '待审核'" :a="orderSched(r)" :b="orderQty(r)"/><span v-else class="dim">—</span></template>
        <template #ret="{ r }"><Prog v-if="orderSched(r)" :a="orderRet(r)" :b="orderQty(r)"/><span v-else class="dim">—</span></template>
        <template #kit="{ r }"><St v-if="orderLive(r) || r.status === '已完成'" :s="kitState(r).k === 'ok' ? '齐料' : kitState(r).k === 'part' ? '部分到货' : '未到料'" :t="kitState(r).t"/><span v-else class="dim">—</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <OrderNew v-if="s.add" :st="typeof s.add === 'string' ? s.add : null" :customer="nav.query.customer" @close="s.add = null"/>
  </div>`,
}, { mod: 'orders', t: '订单' })

/* ---------- 新建订单：一个款一张颜色 × 尺码矩阵 ---------- */
comp('OrderNew', {
  props: { st: String, customer: String },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ type: '客户订单', customer: p.customer || 'KH0001', custNo: '', due: addDays(clock.today, 60), sales: 'u3', merch: 'u1', ship: '' })
    const blocks = reactive([])
    const pickable = computed(() => db.styles.filter((s) => s.status === '启用' && !blocks.some((b) => b.style === s.code)))
    const pick = ref('')
    const addBlock = (code) => { if (code) blocks.push({ style: code, price: null, qty: {} }); pick.value = '' }
    if (style(p.st)) addBlock(p.st)
    const total = computed(() => sum(blocks, (b) => sum(Object.values(b.qty))))
    const amount = computed(() => sum(blocks, (b) => sum(Object.values(b.qty)) * (b.price || 0)))
    function save(submit) {
      const o = run(() => createOrder({ ...f, customer: f.type === '自营备货' ? null : f.customer, lines: blocks.flatMap((b) => Object.entries(b.qty).map(([k, n]) => { const [color, size] = k.split('|'); return { style: b.style, color, size, qty: n, price: b.price } })) }))
      if (!o?.no) return
      if (submit && !run(() => submitOrder(o))) { emit('close'); go('/orders/' + o.no); return }
      emit('close'); go('/orders/' + o.no); toast(submit ? `${o.no} 已提交，等审核` : `${o.no} 已存草稿`)
    }
    return { f, blocks, pickable, pick, addBlock, total, amount, save }
  },
  template: `<Modal t="新建订单" :w="780" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr)">
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in db.dicts.订单类型" :key="x">{{ x }}</option></select></label>
      <label class="f" v-if="f.type !== '自营备货'">客户<select class="sel" v-model="f.customer"><option v-for="c in db.customers" :key="c.code" :value="c.code">{{ c.short }}</option></select></label>
      <label class="f" v-if="f.type !== '自营备货'">客户订单号<input class="inp" v-model="f.custNo"></label>
      <label class="f">客户交期<input class="inp" type="date" v-model="f.due"></label>
      <label class="f">业务员<select class="sel" v-model="f.sales"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
      <label class="f">跟单员<select class="sel" v-model="f.merch"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
    </div>
    <div v-for="(b, i) in blocks" :key="b.style" class="sec" style="margin-top:20px" data-c="录单矩阵">
      <h3><Thumb :code="b.style" :s="24"/><span class="code">{{ b.style }}</span>{{ styleName(b.style) }}
        <span class="acts"><label class="row g4 mut">单价<input class="inp num" style="width:90px;height:26px" type="number" v-model.number="b.price"></label><button class="btn ghost icon sm" @click="blocks.splice(i, 1)"><Ico n="x" :s="13"/></button></span></h3>
      <Matrix :st="b.style" v-model="b.qty"/>
    </div>
    <div class="row" style="margin-top:16px"><select class="sel" v-model="pick" @change="addBlock(pick)" style="width:260px"><option value="">加一个款……</option><option v-for="s in pickable" :key="s.code" :value="s.code">{{ s.code }} {{ s.name }}{{ activeTp(s.code) ? '' : '（资料包未生效）' }}</option></select></div>
    <template #foot><span class="mut">{{ total }} 件<template v-if="amount"> · {{ fm(amount) }}</template></span><span class="sp"></span>
      <button class="btn" @click="save(false)">存草稿</button><button class="btn pri" @click="save(true)">提交审核</button></template>
  </Modal>`,
})

/* ---------- 订单详情 ---------- */
route('/orders/:no', {
  props: ['no'],
  setup(p) {
    const o = computed(() => order(p.no))
    const tab = ref(nav.query.tab || 'lines')
    const dlg = ref('')
    const lineMap = (st) => Object.fromEntries(o.value.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, l.qty]))
    const cellSub = (st) => (c, z) => {
      const l = o.value.lines.find((x) => x.style === st && x.color === c && x.size === z)
      if (!l || !orderLive(o.value) && o.value.status !== '已完成') return ''
      return `排 ${scheduledQty(o.value, l)} · 回 ${returnedQty(o.value, l)}`
    }
    const stylePrice = (st) => o.value.lines.find((l) => l.style === st)?.price
    const lockedVer = (st) => o.value.lines.find((l) => l.style === st)?.ver
    const down = computed(() => {
      const ms = mrpsOf(o.value)
      return [
        ...prodsOf(o.value).map((x) => ({ type: '加工单', no: x.no, to: '/production/' + x.no, who: supName(x.factory), qty: prodQty(x), status: x.status, p: x })),
        ...ms.map((x) => ({ type: '算料单', no: x.no, to: '/mrp/' + x.no, who: '', qty: null, status: x.status })),
        ...db.purchases.filter((x) => x.lines.some((l) => ms.some((m) => m.no === l.src?.mrp))).map((x) => ({ type: '采购单', no: x.no, to: '/purchases/' + x.no, who: supName(x.supplier), qty: null, status: x.status })),
        ...db.returns.filter((x) => x.order === o.value.no).map((x) => ({ type: '回货单', no: x.no, to: '/production/' + x.prod, who: supName(x.factory), qty: sum(x.lines, (l) => l.qty), status: '已完成' })),
      ]
    })
    const changes = computed(() => db.changes.filter((c) => c.order === p.no).slice().reverse())
    const pendingChange = computed(() => changes.value.find((c) => c.status === '待审核'))
    function act(a) { if (run(() => doAction('order', o.value, a))) toast(`${o.value.no} ${a}了`) }
    function doMrp() { const m = run(() => createMrp([o.value.no])); if (m?.no) { go('/mrp/' + m.no); toast(`${m.no} 算好了，勾选要买的行生成采购单`) } }
    async function remove() { if (await ask('删除订单', `${o.value.no} 删除后找不回来。`, '删除', true)) { db.orders.splice(db.orders.indexOf(o.value), 1); go('/orders') } }
    const more = computed(() => [
      can('order', o.value, '撤回') && { t: '撤回', fn: () => act('撤回') },
      can('order', o.value, '反审核') && { t: '反审核', fn: () => act('反审核') },
      can('order', o.value, '结案') && { t: '结案', fn: () => act('结案') },
      can('order', o.value, '作废') && { t: '作废', danger: true, fn: () => act('作废') },
      o.value.status === '草稿' && { t: '删除', danger: true, fn: remove },
    ].filter(Boolean))
    return { o, tab, dlg, lineMap, cellSub, stylePrice, lockedVer, down, changes, pendingChange, act, doMrp, more }
  },
  template: `<div v-if="o">
    <Head :t="o.no" :back="['订单', '/orders']">
      <button v-if="can('order', o, '提交')" class="btn pri" @click="act('提交')">提交审核</button>
      <template v-if="can('order', o, '审核')"><button class="btn" @click="act('驳回')">驳回</button><button class="btn pri" @click="dlg = 'audit'">审核</button></template>
      <template v-if="orderLive(o)"><button class="btn" @click="dlg = 'change'">提变更</button><button class="btn" @click="doMrp">算料</button><button v-if="o.lines.some(l => scheduledQty(o, l) < l.qty)" class="btn pri" @click="dlg = 'schedule'">排单</button></template>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ o.customer ? custName(o.customer) : o.type }}<St :s="o.status"/></div>
        <div class="sub"><span>{{ orderQty(o).toLocaleString() }} 件</span><span v-if="orderAmt(o)">{{ fm(orderAmt(o)) }}</span><span>交期 {{ fd(o.commit || o.due) }}</span><Days v-if="orderLive(o)" :d="o.commit || o.due" :warn="7"/></div>
        <div v-if="pendingChange" class="banner"><Ico n="diff"/>变更单 {{ pendingChange.no }} 待审核：{{ describeChange(pendingChange) }}<button class="btn sm" @click="tab = 'changes'">去看</button></div>
        <Tabs v-model="tab" :items="[['lines', '明细'], ['down', '下游单据', down.length], ['changes', '变更', changes.length]]"/>
        <section v-if="tab === 'lines'" data-c="订单明细">
          <div v-for="st in orderStyles(o)" :key="st" style="margin-bottom:28px">
            <div class="row" style="margin-bottom:8px"><Thumb :code="st" :s="28"/><a class="code" :href="'#/styles/' + st">{{ st }}</a><span>{{ styleName(st) }}</span>
              <span class="pill" :class="lockedVer(st) ? 'blue' : ''">资料包 {{ lockedVer(st) ? 'V' + lockedVer(st) + ' 已锁定' : (activeTp(st) ? 'V' + activeTp(st).ver : '未生效') }}</span>
              <span style="flex:1"></span><span class="mut" v-if="stylePrice(st)">单价 {{ fm(stylePrice(st)) }}</span></div>
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
          <Tbl inset flat :rows="changes" empty="没有变更" :cols="[{ k: 'no', t: '变更单号', c: 'code' }, { k: 'text', t: '改了什么', f: c => describeChange(c) }, { k: 'reason', t: '原因' }, { k: 'party', t: '责任方' }, { k: 'date', t: '日期', f: c => fd(c.date) }, { k: 'status', t: '状态' }]">
            <template #text="{ r }"><span class="wrap">{{ describeChange(r) }}</span></template>
            <template #status="{ r }"><span class="row"><St :s="r.status"/><template v-if="r.status === '待审核'"><button class="btn sm" @click="run(() => doAction('change', r, '驳回'))">驳回</button><button class="btn sm pri" @click="run(() => doAction('change', r, '审核')) && toast('变更已写回订单，下游单据已提示')">审核</button></template></span></template>
          </Tbl>
        </section>
      </div>
      <aside class="aside" data-c="订单属性">
        <dl class="props">
          <dt>类型</dt><dd>{{ o.type }}</dd>
          <dt v-if="o.customer">客户</dt><dd v-if="o.customer"><a :href="'#/customers/' + o.customer">{{ custName(o.customer) }}</a></dd>
          <dt v-if="o.custNo">客户单号</dt><dd v-if="o.custNo" class="code">{{ o.custNo }}</dd>
          <dt>下单日期</dt><dd>{{ fd(o.date) }}</dd>
          <dt>客户交期</dt><dd>{{ fd(o.due) }}</dd>
          <dt>承诺交期</dt><dd>{{ fd(o.commit) }}</dd>
          <dt>业务员</dt><dd>{{ userName(o.sales) }}</dd>
          <dt>跟单员</dt><dd>{{ userName(o.merch) }}</dd>
          <dt>主体</dt><dd>{{ db.companies.find(c => c.code === o.company).short }}</dd>
          <dt v-if="o.ship">送货要求</dt><dd v-if="o.ship">{{ o.ship }}</dd>
          <template v-if="orderLive(o) || o.status === '已完成'">
            <dt>齐料</dt><dd>{{ kitState(o).t }}</dd>
            <dt>排产</dt><dd>{{ orderSched(o) }} / {{ orderQty(o) }}</dd>
            <dt>回货</dt><dd>{{ orderRet(o) }} / {{ orderQty(o) }}</dd>
          </template>
        </dl>
        <h4>操作记录</h4><Log :items="o.log"/>
      </aside>
    </div>
    <OrderAudit v-if="dlg === 'audit'" :o="o" @close="dlg = ''"/>
    <OrderSchedule v-if="dlg === 'schedule'" :o="o" @close="dlg = ''" @done="tab = 'down'"/>
    <OrderChange v-if="dlg === 'change'" :o="o" @close="dlg = ''" @done="tab = 'changes'"/>
  </div><NotFound v-else/>`,
}, { mod: 'orders', t: (p) => '订单 ' + p.no })

/* ---------- 审核即评审：资料包、齐料参考、在手负荷，填承诺交期 ---------- */
comp('OrderAudit', {
  props: { o: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const info = computed(() => reviewInfo(p.o))
    const commit = ref(p.o.due)
    function ok() { if (run(() => auditOrder(p.o, commit.value))) { emit('close'); toast(`${p.o.no} 已审核，资料包已锁定`) } }
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
    <div class="sec"><label class="f" style="width:200px">承诺交期<input class="inp" type="date" v-model="commit"></label></div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">通过</button></template>
  </Modal>`,
})

/* ---------- 排单：每个款一块，可以拆给几个工厂；一块生成一张加工单 ---------- */
comp('OrderSchedule', {
  props: { o: Object },
  emits: ['close', 'done'],
  setup(p, { emit }) {
    const factories = computed(() => db.suppliers.filter(isFactory))
    const remain = (st) => Object.fromEntries(p.o.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, Math.max(0, l.qty - scheduledQty(p.o, l))]))
    const due = addDays(p.o.commit || p.o.due, -10)
    const mk = (st, qty) => ({ style: st, factory: style(st).factory, mode: '清加工', price: activeTp(st) ? tpLabor(activeTp(st)) : null, due, qty })
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
    return { factories, blocks, more, colorsOf, sub, ok }
  },
  template: `<Modal :t="'排单 ' + o.no" :w="820" @close="$emit('close')">
    <div v-if="!blocks.length" class="empty">订单的数量都已经排完了</div>
    <div v-for="(b, i) in blocks" :key="i" class="sec" :style="i ? '' : 'margin-top:0'" data-c="排单块">
      <h3><Thumb :code="b.style" :s="24"/><span class="code">{{ b.style }}</span>{{ styleName(b.style) }}<span class="acts"><button class="btn ghost sm" @click="more(b.style)"><Ico n="plus" :s="12"/>再加一个工厂</button><button v-if="blocks.filter(x => x.style === b.style).length > 1" class="btn ghost icon sm" @click="blocks.splice(i, 1)"><Ico n="x" :s="12"/></button></span></h3>
      <div class="form" style="grid-template-columns:2fr 1fr 1fr 1.3fr;margin-bottom:10px">
        <label class="f">加工厂<select class="sel" v-model="b.factory"><option v-for="s in factories" :key="s.code" :value="s.code" :disabled="s.status !== '合作中'">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></select></label>
        <label class="f">加工方式<select class="sel" v-model="b.mode"><option v-for="x in db.dicts.加工方式" :key="x">{{ x }}</option></select></label>
        <label class="f">单价（元/件）<input class="inp num" type="number" v-model.number="b.price"></label>
        <label class="f">交期<input class="inp" type="date" v-model="b.due"></label>
      </div>
      <Matrix :st="b.style" v-model="b.qty" :colors="colorsOf(b.style)" :sub="sub(b.style)"/>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!blocks.length" @click="ok">生成加工单</button></template>
  </Modal>`,
})

/* ---------- 提变更：改数量（矩阵）、改交期 ---------- */
comp('OrderChange', {
  props: { o: Object },
  emits: ['close', 'done'],
  setup(p, { emit }) {
    const qty = reactive(Object.fromEntries(orderStyles(p.o).map((st) => [st, Object.fromEntries(p.o.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, l.qty]))])))
    const f = reactive({ due: p.o.due, reason: '', party: '客户', fee: '' })
    const colorsOf = (st) => [...new Set(p.o.lines.filter((l) => l.style === st).map((l) => l.color))]
    const sub = (st) => (c, z) => { const l = p.o.lines.find((x) => x.style === st && x.color === c && x.size === z); return l ? '原 ' + l.qty + ' · 已排 ' + scheduledQty(p.o, l) : '' }
    function ok() {
      const items = Object.entries(qty).flatMap(([st, m]) => Object.entries(m).map(([k, n]) => ({ k: st + '|' + k, to: n })))
      const c = run(() => createChange(p.o, { ...f, items }))
      if (c?.no) { emit('close'); emit('done'); toast(`${c.no} 已提交，审核后写回订单`) }
    }
    return { qty, f, colorsOf, sub, ok }
  },
  template: `<Modal :t="'变更 ' + o.no" :w="780" @close="$emit('close')">
    <div v-for="st in Object.keys(qty)" :key="st" style="margin-bottom:18px" data-c="变更矩阵"><div class="row" style="margin-bottom:6px"><Thumb :code="st" :s="22"/><span class="code">{{ st }}</span>{{ styleName(st) }}</div>
      <Matrix :st="st" v-model="qty[st]" :colors="colorsOf(st)" :sub="sub(st)"/></div>
    <div class="form">
      <label class="f">客户交期<input class="inp" type="date" v-model="f.due"></label>
      <label class="f">责任方<select class="sel" v-model="f.party"><option>客户</option><option>我方</option><option>工厂</option><option>供应商</option></select></label>
      <label class="f full">原因<input class="inp" v-model="f.reason"></label>
      <label class="f full">费用说明<input class="inp" v-model="f.fee" placeholder="如：客户承担已裁片损失"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">提交变更</button></template>
  </Modal>`,
})

/* ---------- 变更单列表 ---------- */
route('/changes', {
  setup() {
    const s = reactive({ tab: '全部' })
    const tabs = computed(() => ['全部', '待审核', '已审核', '已驳回'].map((k) => [k, k, k === '全部' ? db.changes.length : db.changes.filter((c) => c.status === k).length]))
    const rows = computed(() => db.changes.filter((c) => s.tab === '全部' || c.status === s.tab).slice().reverse())
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="变更"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div data-c="变更列表">
      <Tbl :rows="rows" link @row="c => go('/orders/' + c.order + '?tab=changes')" empty="没有变更单。已审核的订单在详情页「提变更」" :cols="[
        { k: 'no', t: '变更单号', c: 'code' }, { k: 'order', t: '订单', c: 'code' }, { k: 'text', t: '改了什么' }, { k: 'reason', t: '原因' },
        { k: 'party', t: '责任方' }, { k: 'by', t: '提交人', f: c => userName(c.by) }, { k: 'date', t: '日期', f: c => fd(c.date) }, { k: 'status', t: '状态' }]">
        <template #text="{ r }"><span class="wrap">{{ describeChange(r) }}</span></template>
        <template #status="{ r }"><span class="row" @click.stop><St :s="r.status"/><template v-if="r.status === '待审核'"><button class="btn sm" @click="run(() => doAction('change', r, '驳回'))">驳回</button><button class="btn sm pri" @click="run(() => doAction('change', r, '审核')) && toast('变更已写回订单，下游单据已提示')">审核</button></template></span></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'changes', t: '变更' })

expose({ createMrp, submitOrder, custOrDash })
