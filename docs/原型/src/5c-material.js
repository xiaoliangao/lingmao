/* ============================================================
 * 物料（M07）：算料、采购单、到料跟踪、出入库单、库存与库存日志
 * ============================================================ */
SEARCH.push(() => [
  ...db.mrps.map((m) => ({ t: m.no, sub: '算料单 ' + m.orders.join(' '), to: '/mrp/' + m.no, i: 'sigma' })),
  ...db.purchases.map((p) => ({ t: p.no + ' ' + supName(p.supplier), sub: '采购单', to: '/purchases/' + p.no, i: 'cart' })),
  ...db.stockDocs.map((d) => ({ t: d.no + ' ' + d.type, sub: '出入库单', to: '/stock-docs/' + d.no, i: 'swap' })),
])
const purUnit = (sku) => matOfSku(sku)?.purUnit || ''
const srcText = (l) => (l.src ? [...(l.src.orders || []), ...(l.src.styles || [])].join(' ') : '—')

/* ---------- 算料 ---------- */
route('/mrp', {
  setup() {
    const s = reactive({ tab: '全部', add: false })
    const tabs = computed(() => ['全部', '草稿', '已确认', '已关闭'].map((k) => [k, k, k === '全部' ? db.mrps.length : db.mrps.filter((m) => m.status === k).length]))
    const rows = computed(() => db.mrps.filter((m) => s.tab === '全部' || m.status === s.tab).slice().reverse())
    const buyRows = (m) => m.rows.filter((r) => r.src === '我方采购' && r.purQty > 0)
    return { s, tabs, rows, buyRows }
  },
  template: `<div>
    <Head t="算料"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>选订单算料</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div data-c="算料单列表">
      <Tbl :rows="rows" link @row="m => go('/mrp/' + m.no)" empty="还没有算料单" :cols="[
        { k: 'no', t: '算料单号', c: 'code' }, { k: 'orders', t: '订单', c: 'code', f: m => m.orders.join(' ') },
        { k: 'styles', t: '款式', f: m => [...new Set(m.orders.flatMap(o => orderStyles(order(o))))].join(' ') },
        { k: 'buy', t: '要买', c: 'num', f: m => buyRows(m).length + ' 种' }, { k: 'conv', t: '转采购' },
        { k: 'date', t: '日期', f: m => fd(m.date) }, { k: 'status', t: '状态' }]">
        <template #conv="{ r }"><Prog v-if="buyRows(r).length" :a="buyRows(r).filter(x => x.converted >= x.purQty).length" :b="buyRows(r).length"/><span v-else class="dim">不用买</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <MrpNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'mrp', t: '算料' })

comp('MrpNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const live = computed(() => db.orders.filter(orderLive))
    const picked = reactive([])
    const toggle = (no) => { const i = picked.indexOf(no); i >= 0 ? picked.splice(i, 1) : picked.push(no) }
    function ok() { const m = run(() => createMrp([...picked])); if (m?.no) { emit('close'); go('/mrp/' + m.no) } }
    return { live, picked, toggle, ok }
  },
  template: `<Modal t="选订单算料" :w="640" @close="$emit('close')">
    <Tbl inset flat :rows="live" :sel="o => picked.includes(o.no)" link @row="o => toggle(o.no)" empty="没有已审核的订单" :cols="[
      { k: 'ck', t: '', w: '28px' }, { k: 'no', t: '订单号', c: 'code' }, { k: 'cust', t: '客户', f: custOrDash }, { k: 'styles', t: '款式', c: 'code', f: o => orderStyles(o).join(' ') },
      { k: 'qty', t: '数量', c: 'num', f: o => orderQty(o) }, { k: 'mrp', t: '算过', f: o => mrpsOf(o).map(m => m.no).join(' ') || '—' }]">
      <template #ck="{ r }"><input type="checkbox" :checked="picked.includes(r.no)" style="accent-color:var(--accent)"></template>
    </Tbl>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!picked.length" @click="ok">算料</button></template>
  </Modal>`,
})

route('/mrp/:no', {
  props: ['no'],
  setup(p) {
    const m = computed(() => mrp(p.no))
    const idx = (src) => m.value.rows.map((r, i) => (r.src === src ? i : -1)).filter((i) => i >= 0)
    const buy = computed(() => idx('我方采购'))
    const picked = reactive(buy.value.filter((i) => m.value.rows[i].purQty > m.value.rows[i].converted))
    const toggle = (i) => { const k = picked.indexOf(i); k >= 0 ? picked.splice(k, 1) : picked.push(i) }
    const open = computed(() => m.value.status !== '已关闭')
    const edit = computed(() => m.value.status === '草稿')
    const left = computed(() => buy.value.some((i) => m.value.rows[i].purQty > m.value.rows[i].converted))
    const suppliersFor = () => db.suppliers.filter((s) => s.cats.some((c) => ['面料商', '辅料商', '包材商'].includes(c)))
    function toPo() {
      const pos = run(() => mrpToPurchases(m.value, [...picked]))
      if (pos?.length) { picked.splice(0); toast(`按供应商拆成 ${pos.length} 张采购单草稿`, ['打开 ' + pos[0].no, '/purchases/' + pos[0].no]) }
    }
    function redo() {
      const n = run(() => createMrp(m.value.orders))
      if (!n?.no) return
      const diff = n.rows.map((r) => { const old = m.value.rows.find((x) => x.sku === r.sku && x.owner === r.owner && x.src === r.src); return { r, d: rnd(r.need - (old?.need || 0), 3) } }).filter((x) => x.d)
      if (m.value.status !== '已关闭') doAction('mrp', m.value, '关闭')
      go('/mrp/' + n.no)
      toast(diff.length ? '重新算好了：' + diff.slice(0, 3).map((x) => skuName(x.r.sku) + ' ' + (x.d > 0 ? '+' : '') + fq(x.d)).join('，') : '重新算好了，需求没变')
    }
    const more = computed(() => [{ t: '重新算料', fn: redo }, open.value && { t: '关闭', fn: () => run(() => doAction('mrp', m.value, '关闭')) }].filter(Boolean))
    return { m, idx, buy, picked, toggle, open, edit, left, suppliersFor, toPo, more, updateMrpRow }
  },
  template: `<div v-if="m">
    <Head :t="m.no" :back="['算料', '/mrp']">
      <button v-if="can('mrp', m, '确认')" class="btn" @click="run(() => doAction('mrp', m, '确认'))">确认</button>
      <button v-if="open && left" class="btn pri" :disabled="!picked.length" @click="toPo">生成采购单<span v-if="picked.length">（{{ picked.length }}）</span></button>
      <More :items="more"/>
    </Head>
    <div class="page">
      <Upstream :d="m"/>
      <div class="title">算料 <span v-for="o in m.orders" :key="o" class="code" style="font-size:14px"><a :href="'#/orders/' + o">{{ o }}</a></span><St :s="m.status"/></div>
      <div class="sub"><span>{{ fd(m.date) }}</span><span>{{ [...new Set(m.orders.flatMap(o => orderStyles(order(o))))].join(' ') }}</span></div>
      <div class="sec" data-c="我方采购"><h3>我方采购</h3>
        <table class="tbl inset flat">
          <thead><tr><th style="width:28px"></th><th>物料</th><th>供应商</th><th class="num">需求</th><th class="num">可用库存</th><th class="num">本次用库存</th><th class="num">在途</th><th class="num">净需求</th><th class="num">建议采购</th><th class="num">预计金额</th><th class="num">已转采购</th></tr></thead>
          <tbody>
            <tr v-for="i in buy" :key="i" :class="{ sel: picked.includes(i) }">
              <td><input type="checkbox" :checked="picked.includes(i)" :disabled="!open || m.rows[i].purQty <= m.rows[i].converted" @change="toggle(i)" style="accent-color:var(--accent)"></td>
              <td>{{ skuName(m.rows[i].sku) }}</td>
              <td><select v-if="edit" class="sel" style="height:26px" v-model="m.rows[i].supplier"><option v-for="s in suppliersFor()" :key="s.code" :value="s.code">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></select><template v-else>{{ supName(m.rows[i].supplier) }}</template></td>
              <td class="num">{{ fq(m.rows[i].need) }} <span class="dim">{{ unitOf(m.rows[i].sku) }}</span></td>
              <td class="num">{{ fq(m.rows[i].avail) }}</td>
              <td class="num"><input v-if="edit" class="inp cell num ghost" style="width:84px" :value="m.rows[i].useStock" @change="updateMrpRow(m.rows[i], 'useStock', $event.target.value)"><template v-else>{{ fq(m.rows[i].useStock) }}</template></td>
              <td class="num dim">{{ fq(m.rows[i].transit) }}</td>
              <td class="num">{{ fq(m.rows[i].net) }}</td>
              <td class="num"><b>{{ fq(m.rows[i].purQty) }}</b> <span class="dim">{{ purUnit(m.rows[i].sku) }}</span></td>
              <td class="num">{{ fm(m.rows[i].purQty * m.rows[i].price) }}</td>
              <td class="num">{{ m.rows[i].converted ? fq(m.rows[i].converted) : '—' }}</td>
            </tr>
            <tr v-if="!buy.length"><td colspan="11" class="empty">资料包里没有我方采购的料</td></tr>
          </tbody>
        </table></div>
      <div class="sec" data-c="待客户提供" v-if="idx('客供').length"><h3>待客户提供</h3>
        <Tbl inset flat :rows="idx('客供').map(i => m.rows[i])" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku) }, { k: 'owner', t: '客户', f: r => custName(r.owner) }, { k: 'need', t: '需求', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'avail', t: '客供库存', c: 'num', f: r => fq(r.avail) }, { k: 'gap', t: '缺口', c: 'num' }]">
          <template #gap="{ r }"><span :class="{ red: r.net > 0 }">{{ r.net > 0 ? fq(r.net) : '够了' }}</span></template></Tbl></div>
      <div class="sec" data-c="工厂自备" v-if="idx('工厂自备').length"><h3>工厂自备</h3>
        <Tbl inset flat :rows="idx('工厂自备').map(i => m.rows[i])" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku) }, { k: 'need', t: '需求', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'styles', t: '款式', c: 'code', f: r => r.styles.join(' ') }]"/></div>
      <div class="sec"><h3>操作记录</h3><Log :items="m.log"/></div>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'mrp', t: (p) => '算料单 ' + p.no })

/* ---------- 采购单 ---------- */
route('/purchases', {
  setup() {
    const s = reactive({ tab: nav.query.s || '全部', q: '', add: false })
    const tabs = computed(() => ['全部', '草稿', '待审核', '已审核', '部分到货', '已到齐'].map((k) => [k, k, k === '全部' ? db.purchases.length : db.purchases.filter((p) => p.status === k).length]))
    const rows = computed(() => db.purchases.filter((p) => (s.tab === '全部' || p.status === s.tab) && (!s.q || (p.no + supName(p.supplier) + p.lines.map((l) => l.sku + skuName(l.sku)).join(' ')).toLowerCase().includes(s.q.toLowerCase()))).slice().reverse())
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="采购"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建采购单</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="采购单列表">
      <Tbl :rows="rows" link @row="p => go('/purchases/' + p.no)" empty="没有符合条件的采购单" :cols="[
        { k: 'no', t: '采购单号', c: 'code' }, { k: 'sup', t: '供应商' }, { k: 'type', t: '类型' },
        { k: 'mat', t: '物料', f: p => skuName(p.lines[0].sku) + (p.lines.length > 1 ? ' 等 ' + p.lines.length + ' 种' : '') },
        { k: 'amt', t: '金额', c: 'num', f: p => fm(poAmt(p)) }, { k: 'due', t: '交期', f: p => fd(p.due) }, { k: 'recv', t: '到货' },
        { k: 'buyer', t: '采购员', f: p => userName(p.buyer) }, { k: 'status', t: '状态' }]">
        <template #sup="{ r }">{{ supName(r.supplier) }}<span v-if="sup(r.supplier).status !== '合作中'" class="flag red" style="margin-left:6px">{{ sup(r.supplier).status }}</span></template>
        <template #recv="{ r }"><Prog v-if="!['草稿', '待审核'].includes(r.status)" :a="poRecv(r)" :b="poQty(r)"/><span v-else class="dim">—</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <PurchaseNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'purchases', t: '采购' })

comp('PurchaseNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ supplier: 'GYS0001', type: '备货采购', wh: 'WL01', lines: [{ sku: '', qty: null, price: null }] })
    const sups = computed(() => db.suppliers.filter((s) => !isFactory(s)))
    const skus = computed(() => db.materials.filter((m) => m.status === '启用').flatMap((m) => m.skus.map((k) => ({ code: k.code, t: skuName(k.code), mine: m.supplier === f.supplier }))).sort((a, b) => b.mine - a.mine))
    const setSku = (l) => { l.price = matOfSku(l.sku)?.price ?? null }
    function ok() { const p = run(() => createPurchase({ ...f, lines: f.lines.map((l) => ({ ...l })) })); if (p?.no) { emit('close'); go('/purchases/' + p.no) } }
    return { f, sups, skus, setSku, ok }
  },
  template: `<Modal t="新建采购单" :w="680" @close="$emit('close')">
    <div class="form" style="grid-template-columns:2fr 1fr 1fr">
      <label class="f">供应商<select class="sel" v-model="f.supplier"><option v-for="s in sups" :key="s.code" :value="s.code" :disabled="s.status !== '合作中'">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></select></label>
      <label class="f">类型<select class="sel" v-model="f.type"><option>备货采购</option><option>订单采购</option><option>打样采购</option></select></label>
      <label class="f">交货到<select class="sel" v-model="f.wh"><option v-for="w in db.warehouses.filter(w => w.type !== '成品仓')" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
    </div>
    <table class="tbl inset flat" style="margin-top:14px" data-c="采购明细"><thead><tr><th>物料</th><th class="num" style="width:110px">数量</th><th class="num" style="width:110px">单价</th><th style="width:32px"></th></tr></thead>
      <tbody><tr v-for="(l, i) in f.lines" :key="i">
        <td><select class="sel" style="width:100%;height:28px" v-model="l.sku" @change="setSku(l)"><option value="">选物料</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ k.t }}</option></select></td>
        <td><input class="inp cell num" type="number" v-model.number="l.qty" :placeholder="l.sku ? purUnit(l.sku) : ''"></td>
        <td><input class="inp cell num" type="number" v-model.number="l.price"></td>
        <td><button class="btn ghost icon sm" @click="f.lines.splice(i, 1)"><Ico n="x" :s="12"/></button></td></tr></tbody></table>
    <button class="btn ghost" style="margin-top:8px" @click="f.lines.push({ sku: '', qty: null, price: null })"><Ico n="plus" :s="14"/>加一行</button>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">存草稿</button></template>
  </Modal>`,
})

function returnPurchase(p, lines) {
  if (!['已审核', '部分到货', '已到齐'].includes(p.status)) fail('采购单入库后才能退货')
  const ls = lines.filter((x) => x.qty > 0)
  if (!ls.length) fail('至少退一行')
  const errs = ls.filter((x) => x.qty > p.lines[x.i].received - p.lines[x.i].returned + 0.0005).map((x) => `${skuName(p.lines[x.i].sku)}：最多能退 ${fq(p.lines[x.i].received - p.lines[x.i].returned)}`)
  if (errs.length) fail('退货数超过已入库', errs)
  const d = createStockDoc({ type: '采购退货', src: p.no, partner: p.supplier, from: p.wh, reason: lines.reason || '', lines: ls.map((x) => { const l = p.lines[x.i]; return { sku: l.sku, owner: l.owner, qty: rnd(x.qty * matOfSku(l.sku).factor, 3), price: l.price } }) })
  postStockDoc(d)
  ls.forEach((x) => { p.lines[x.i].returned = rnd(p.lines[x.i].returned + x.qty, 3) })
  act(p, '退货 ' + d.no)
  return d
}

route('/purchases/:no', {
  props: ['no'],
  setup(p) {
    const po = computed(() => purchase(p.no))
    const dlg = ref('')
    const docs = computed(() => db.stockDocs.filter((d) => d.src === p.no))
    const a = (x) => { if (run(() => doAction('purchase', po.value, x))) toast(`${po.value.no} ${x}了`) }
    const more = computed(() => [
      ['已审核', '部分到货', '已到齐'].includes(po.value.status) && poRecv(po.value) > 0 && { t: '退货', fn: () => { dlg.value = 'return' } },
      can('purchase', po.value, '撤回') && { t: '撤回', fn: () => a('撤回') },
      can('purchase', po.value, '反审核') && { t: '反审核', fn: () => a('反审核') },
      can('purchase', po.value, '结案') && { t: '结案（剩下的不要了）', fn: () => a('结案') },
      { t: '打印', fn: () => toast('原型：这里会出可打印的采购单（抬头取主体公司）') },
      can('purchase', po.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') },
    ].filter(Boolean))
    return { po, dlg, docs, a, more, srcText, purUnit }
  },
  template: `<div v-if="po">
    <Head :t="po.no" :back="['采购', '/purchases']">
      <button v-if="can('purchase', po, '提交')" class="btn pri" @click="a('提交')">提交审核</button>
      <template v-if="can('purchase', po, '审核')"><button class="btn" @click="a('驳回')">驳回</button><button class="btn pri" @click="a('审核')">审核</button></template>
      <button v-if="['已审核', '部分到货'].includes(po.status)" class="btn pri" @click="dlg = 'recv'">到货入库</button>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <Upstream :d="po"/>
        <div v-if="sup(po.supplier).status !== '合作中'" class="banner red"><Ico n="alert"/>{{ supName(po.supplier) }} 现在是「{{ sup(po.supplier).status }}」：这张单可以继续入库，不能再新建</div>
        <div class="title">{{ supName(po.supplier) }}<St :s="po.status"/></div>
        <div class="sub"><span>{{ po.type }}</span><span>{{ fm(poAmt(po)) }}</span><span>交期 {{ fd(po.due) }}</span><span>送到 {{ whName(po.wh) }}</span></div>
        <section data-c="采购明细">
          <Tbl inset flat :rows="po.lines" :cols="[
            { k: 'sku', t: '物料', f: l => skuName(l.sku) }, { k: 'owner', t: '物权', f: l => ownerName(l.owner) },
            { k: 'qty', t: '数量', c: 'num', f: l => fq(l.qty) + ' ' + purUnit(l.sku) }, { k: 'price', t: '单价', c: 'num', f: l => fm(l.price) },
            { k: 'amt', t: '金额', c: 'num', f: l => fm(l.qty * l.price) }, { k: 'recv', t: '已入库', c: 'num', f: l => fq(l.received) },
            { k: 'left', t: '未入库', c: 'num', f: l => fq(Math.max(0, l.qty - l.received)) }, { k: 'exp', t: '预计到货' }, { k: 'src', t: '来源', c: 'code', f: srcText }]">
            <template #exp="{ r }"><span v-if="r.received >= r.qty" class="dim">到齐</span><span v-else class="row g4">{{ fd(r.expect || r.due || po.due) }}<Days v-if="!['草稿','待审核'].includes(po.status)" :d="r.expect || r.due || po.due"/></span><div v-if="r.note" class="dim" style="font-size:12px">{{ r.note }}</div></template>
          </Tbl>
        </section>
        <div class="sec" data-c="入库记录"><h3>入库 / 退货</h3>
          <Tbl inset flat :rows="docs" link @row="d => go('/stock-docs/' + d.no)" empty="还没有入库" :cols="[{ k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'qty', t: '数量', c: 'num', f: d => d.lines.map(l => fq(l.purQty ?? l.qty)).join(' / ') }, { k: 'lot', t: '缸号', f: d => d.lines.map(l => l.lot).filter(Boolean).join(' ') || '—' }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template></Tbl></div>
      </div>
      <aside class="aside" data-c="采购单属性">
        <dl class="props">
          <dt>供应商</dt><dd><a :href="'#/suppliers/' + po.supplier">{{ sup(po.supplier).name }}</a></dd>
          <dt>采购员</dt><dd>{{ userName(po.buyer) }}</dd>
          <dt>下单日期</dt><dd>{{ fd(po.date) }}</dd>
          <dt>交货日期</dt><dd>{{ fd(po.due) }}</dd>
          <dt>交货地点</dt><dd>{{ whName(po.wh) }}</dd>
          <dt>结算</dt><dd>{{ po.settle }} · 税率 {{ po.tax }}</dd>
          <dt>主体</dt><dd>{{ db.companies.find(c => c.code === po.company).short }}</dd>
        </dl>
        <h4>操作记录</h4><Log :items="po.log"/>
      </aside>
    </div>
    <PurchaseReceive v-if="dlg" :po="po" :mode="dlg" @close="dlg = ''"/>
  </div><NotFound v-else/>`,
}, { mod: 'purchases', t: (p) => '采购单 ' + p.no })

comp('PurchaseReceive', {
  props: { po: Object, mode: String },
  emits: ['close'],
  setup(p, { emit }) {
    const recv = p.mode === 'recv'
    const lines = reactive(p.po.lines.map((l, i) => ({ i, qty: recv ? rnd(Math.max(0, l.qty - l.received), 3) : 0, lot: '' })))
    const f = reactive({ wh: p.po.wh, supNo: '', reason: '' })
    const whs = computed(() => db.warehouses.filter((w) => w.type !== '成品仓'))
    function ok() {
      const d = run(() => (recv ? receivePurchase(p.po, { lines, wh: f.wh, supNo: f.supNo }) : returnPurchase(p.po, Object.assign([...lines], { reason: f.reason }))))
      if (d?.no) { emit('close'); toast(`${d.no} 已过账，库存已更新`, ['打开', '/stock-docs/' + d.no]) }
    }
    return { recv, lines, f, whs, ok, purUnit }
  },
  template: `<Modal :t="(recv ? '到货入库 ' : '退货 ') + po.no" :w="720" @close="$emit('close')">
    <div class="form" style="margin-bottom:12px">
      <label v-if="recv" class="f">入哪个仓<select class="sel" v-model="f.wh"><option v-for="w in whs" :key="w.code" :value="w.code">{{ w.name }}{{ w.type === '工厂仓' ? '（面料直发工厂）' : '' }}</option></select></label>
      <label v-if="recv" class="f">供应商送货单号<input class="inp" v-model="f.supNo"></label>
      <label v-else class="f full">退货原因<input class="inp" v-model="f.reason"></label>
    </div>
    <table class="tbl inset flat" data-c="入库数量"><thead><tr><th>物料</th><th class="num">{{ recv ? '未入库' : '已入库' }}</th><th class="num" style="width:120px">本次</th><th v-if="recv" style="width:120px">缸号</th></tr></thead>
      <tbody><tr v-for="x in lines" :key="x.i"><td>{{ skuName(po.lines[x.i].sku) }}</td>
        <td class="num">{{ fq(recv ? Math.max(0, po.lines[x.i].qty - po.lines[x.i].received) : po.lines[x.i].received - po.lines[x.i].returned) }} <span class="dim">{{ purUnit(po.lines[x.i].sku) }}</span></td>
        <td><input class="inp cell num" type="number" v-model.number="x.qty"></td><td v-if="recv"><input class="inp cell" v-model="x.lot"></td></tr></tbody></table>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ recv ? '入库并过账' : '退货并过账' }}</button></template>
  </Modal>`,
})

/* ---------- 到料跟踪 ---------- */
route('/arrivals', {
  setup() {
    const s = reactive({ tab: nav.query.f || 'all' })
    const all = computed(() => arrivals().sort((a, b) => a.left - b.left))
    const tabs = computed(() => [['all', '全部', all.value.length], ['late', '已逾期', all.value.filter((x) => x.risk === 'late').length], ['near', '3 天内', all.value.filter((x) => x.risk === 'near').length]])
    const rows = computed(() => all.value.filter((x) => s.tab === 'all' || x.risk === s.tab))
    return { s, tabs, rows, srcText, purUnit }
  },
  template: `<div>
    <Head t="到料"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div data-c="到料列表">
      <Tbl :rows="rows" empty="没有该到没到的料" :cols="[
        { k: 'po', t: '采购单', c: 'code' }, { k: 'sup', t: '供应商', f: x => supName(x.p.supplier) }, { k: 'sku', t: '物料', f: x => skuName(x.l.sku) },
        { k: 'src', t: '订单 / 款', c: 'code', f: x => srcText(x.l) }, { k: 'qty', t: '采购', c: 'num', f: x => fq(x.l.qty) },
        { k: 'recv', t: '已到', c: 'num', f: x => fq(x.l.received) }, { k: 'left', t: '未到', c: 'num', f: x => fq(rnd(x.l.qty - x.l.received, 3)) + ' ' + purUnit(x.l.sku) },
        { k: 'due', t: '交期', f: x => fd(x.l.due || x.p.due) }, { k: 'exp', t: '预计到货' }, { k: 'days', t: '' }, { k: 'note', t: '跟进', w: '220px' }]">
        <template #po="{ r }"><a :href="'#/purchases/' + r.p.no">{{ r.p.no }}</a></template>
        <template #exp="{ r }"><input class="inp cell ghost" type="date" style="width:130px" v-model="r.l.expect"></template>
        <template #days="{ r }"><Days :d="r.date"/></template>
        <template #note="{ r }"><input class="inp cell ghost" v-model="r.l.note"></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'arrivals', t: '到料' })

/* ---------- 出入库单 ---------- */
const DOC_GROUPS = { 入库: ['采购入库', '客供入库', '其他入库'], 领料: ['领料', '退料'], 调拨: ['调拨'], 出库: ['采购退货', '其他出库'] }
const srcLink = (d) => (!d.src ? '' : d.src.startsWith('CG') ? '/purchases/' + d.src : d.src.startsWith('JG') ? '/production/' + d.src : '')
const partnerName = (d) => (!d.partner ? '—' : d.partner.startsWith('KH') ? custName(d.partner) : supName(d.partner))
route('/stock-docs', {
  setup() {
    const s = reactive({ tab: '全部', q: '', add: '' })
    const tabs = computed(() => [['全部', '全部', db.stockDocs.length], ...Object.entries(DOC_GROUPS).map(([k, ts]) => [k, k, db.stockDocs.filter((d) => ts.includes(d.type)).length])])
    const rows = computed(() => db.stockDocs.filter((d) => (s.tab === '全部' || DOC_GROUPS[s.tab].includes(d.type)) && (!s.q || (d.no + d.type + d.src + d.lines.map((l) => skuName(l.sku)).join(' ')).includes(s.q))).slice().reverse())
    const news = ['客供入库', '其他入库', '调拨', '退料', '其他出库'].map((t) => ({ t, fn: () => { s.add = t } }))
    return { s, tabs, rows, news, partnerName }
  },
  template: `<div>
    <Head t="出入库"><button class="btn pri" @click="s.add = '客供入库'"><Ico n="plus" :s="14"/>客供入库</button><More :items="news"/></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="出入库列表">
      <Tbl :rows="rows" link @row="d => go('/stock-docs/' + d.no)" empty="没有符合条件的单据" :cols="[
        { k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'flow', t: '出仓 → 入仓' }, { k: 'partner', t: '往来', f: partnerName },
        { k: 'src', t: '源单', c: 'code', f: d => d.src || '—' }, { k: 'mat', t: '物料', f: d => skuName(d.lines[0].sku) + (d.lines.length > 1 ? ' 等 ' + d.lines.length + ' 种' : '') },
        { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'status', t: '状态' }]">
        <template #flow="{ r }"><span class="mut">{{ r.from ? whName(r.from) : '' }}<template v-if="r.from && r.to"> → </template>{{ r.to ? whName(r.to) : '' }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <StockDocNew v-if="s.add" :type="s.add" @close="s.add = ''"/>
  </div>`,
}, { mod: 'stockdocs', t: '出入库' })

comp('StockDocNew', {
  props: { type: String },
  emits: ['close'],
  setup(p, { emit }) {
    const t = p.type
    const f = reactive({ type: t, partner: t === '客供入库' ? 'KH0001' : '', from: ['调拨', '其他出库'].includes(t) ? 'WL01' : t === '退料' ? 'GC0004' : null, to: ['客供入库', '其他入库', '退料'].includes(t) ? 'WL01' : t === '调拨' ? 'GC0005' : null, reason: '', lines: [{ sku: '', owner: '自有', qty: null, lot: '' }] })
    const skus = computed(() => db.materials.flatMap((m) => m.skus.map((k) => ({ code: k.code, t: skuName(k.code) }))))
    const owners = computed(() => ['自有', ...db.customers.map((c) => c.code)])
    const have = (l) => (f.from && l.sku ? stockQty(f.from, l.sku, l.owner) : null)
    function ok() { const d = run(() => createStockDoc({ ...f, lines: f.lines.map((l) => ({ ...l })) })); if (d?.no) { emit('close'); go('/stock-docs/' + d.no); toast(`${d.no} 存了草稿，审核后才改库存`) } }
    return { t, f, skus, owners, have, ok }
  },
  template: `<Modal :t="'新建' + t" :w="700" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr)">
      <label v-if="t === '客供入库'" class="f">客户<select class="sel" v-model="f.partner"><option v-for="c in db.customers" :key="c.code" :value="c.code">{{ c.short }}</option></select></label>
      <label v-if="f.from !== null" class="f">出仓<select class="sel" v-model="f.from"><option v-for="w in db.warehouses.filter(w => w.type !== '成品仓')" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label v-if="f.to !== null" class="f">入仓<select class="sel" v-model="f.to"><option v-for="w in db.warehouses.filter(w => w.type !== '成品仓')" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label v-if="['其他入库', '其他出库', '调拨', '退料'].includes(t)" class="f">原因<input class="inp" v-model="f.reason" :placeholder="t === '其他入库' ? '期初 / 盘盈 / 样品' : t === '其他出库' ? '报废 / 盘亏 / 卖余料' : ''"></label>
    </div>
    <table class="tbl inset flat" style="margin-top:14px" data-c="出入库明细"><thead><tr><th>物料</th><th v-if="t !== '客供入库'" style="width:130px">物权</th><th v-if="f.from" class="num">库存</th><th class="num" style="width:100px">数量</th><th style="width:100px">缸号</th><th style="width:32px"></th></tr></thead>
      <tbody><tr v-for="(l, i) in f.lines" :key="i">
        <td><select class="sel" style="width:100%;height:28px" v-model="l.sku"><option value="">选物料</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ k.t }}</option></select></td>
        <td v-if="t !== '客供入库'"><select class="sel" style="width:100%;height:28px" v-model="l.owner"><option v-for="o in owners" :key="o" :value="o">{{ ownerName(o) }}</option></select></td>
        <td v-if="f.from" class="num dim">{{ have(l) == null ? '—' : fq(have(l)) }}</td>
        <td><input class="inp cell num" type="number" v-model.number="l.qty"></td><td><input class="inp cell" v-model="l.lot"></td>
        <td><button class="btn ghost icon sm" @click="f.lines.splice(i, 1)"><Ico n="x" :s="12"/></button></td></tr></tbody></table>
    <button class="btn ghost" style="margin-top:8px" @click="f.lines.push({ sku: '', owner: '自有', qty: null, lot: '' })"><Ico n="plus" :s="14"/>加一行</button>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">存草稿</button></template>
  </Modal>`,
})

route('/stock-docs/:no', {
  props: ['no'],
  setup(p) {
    const d = computed(() => stockDoc(p.no))
    const a = (x) => { if (run(() => doAction('stock', d.value, x))) toast(x === '审核' ? `${d.value.no} 已过账，库存已更新` : `${d.value.no} ${x}了`) }
    const more = computed(() => [can('stock', d.value, '反审核') && { t: '反审核（库存改回去）', fn: () => a('反审核') }, can('stock', d.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') }].filter(Boolean))
    return { d, a, more, srcLink, partnerName }
  },
  template: `<div v-if="d">
    <Head :t="d.no" :back="['出入库', '/stock-docs']"><button v-if="can('stock', d, '审核')" class="btn pri" @click="a('审核')">审核过账</button><More :items="more"/></Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ d.type }}<St :s="d.status"/></div>
        <div class="sub"><span v-if="d.from || d.to">{{ d.from ? whName(d.from) : partnerName(d) }} → {{ d.to ? whName(d.to) : partnerName(d) }}</span><span>{{ fd(d.date) }}</span></div>
        <section data-c="出入库明细">
          <Tbl inset flat :rows="d.lines" :cols="[{ k: 'sku', t: '物料', f: l => skuName(l.sku) }, { k: 'code', t: 'SKU', c: 'code', f: l => l.sku }, { k: 'owner', t: '物权', f: l => ownerName(l.owner) },
            { k: 'qty', t: '数量', c: 'num', f: l => fq(l.qty) + ' ' + unitOf(l.sku) }, { k: 'pq', t: '采购单位', c: 'num', f: l => l.purQty != null && matOfSku(l.sku).factor !== 1 ? fq(l.purQty) + ' ' + matOfSku(l.sku).purUnit : '' },
            { k: 'lot', t: '缸号', f: l => l.lot || '—' }, { k: 'have', t: '出仓库存', c: 'num', f: l => d.from && d.status === '草稿' ? fq(stockQty(d.from, l.sku, l.owner)) : '' }]"/>
        </section>
      </div>
      <aside class="aside" data-c="单据属性">
        <dl class="props">
          <dt>类型</dt><dd>{{ d.type }}</dd>
          <dt v-if="d.from">出仓</dt><dd v-if="d.from">{{ whName(d.from) }}</dd>
          <dt v-if="d.to">入仓</dt><dd v-if="d.to">{{ whName(d.to) }}</dd>
          <dt v-if="d.partner">往来</dt><dd v-if="d.partner">{{ partnerName(d) }}</dd>
          <dt v-if="d.src">源单</dt><dd v-if="d.src"><a class="code" :href="'#' + srcLink(d)">{{ d.src }}</a></dd>
          <dt v-if="d.supNo">送货单号</dt><dd v-if="d.supNo" class="code">{{ d.supNo }}</dd>
          <dt v-if="d.reason">原因</dt><dd v-if="d.reason">{{ d.reason }}</dd>
          <dt>审核人</dt><dd>{{ d.auditor ? userName(d.auditor) : '—' }}</dd>
        </dl>
        <h4>操作记录</h4><Log :items="d.log"/>
      </aside>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'stockdocs', t: (p) => '出入库单 ' + p.no })

/* ---------- 库存与日志 ---------- */
route('/stock', {
  setup() {
    const s = reactive({ tab: nav.query.wh ? '全部' : '物料仓', wh: nav.query.wh || '', view: '明细', owner: '', q: '', log: null })
    const rows = computed(() => Object.entries(db.stock).filter(([, q]) => Math.abs(q) > 0.0005).map(([k, qty]) => { const [w, sku, owner] = k.split('|'); return { k, w, sku, owner, qty, last: db.stockLogs.find((x) => x.wh === w && x.sku === sku && x.owner === owner)?.at } })
      .filter((r) => (s.tab === '全部' || wh(r.w).type === s.tab) && (!s.wh || r.w === s.wh) && (!s.owner || r.owner === s.owner) && (!s.q || (r.sku + skuName(r.sku) + whName(r.w)).includes(s.q)))
      .sort((a, b) => a.sku.localeCompare(b.sku)))
    const summary = computed(() => {
      const m = {}
      rows.value.forEach((r) => { const x = (m[r.sku + '|' + r.owner] ||= { sku: r.sku, owner: r.owner, mat: 0, fac: 0 }); wh(r.w).type === '工厂仓' ? (x.fac += r.qty) : (x.mat += r.qty) })
      return Object.values(m)
    })
    const owners = computed(() => [...new Set(Object.keys(db.stock).map((k) => k.split('|')[2]))])
    const logs = computed(() => (s.log ? db.stockLogs.filter((x) => x.wh === s.log.w && x.sku === s.log.sku && x.owner === s.log.owner) : db.stockLogs).filter((x) => s.log || !s.q || (x.no + x.sku + skuName(x.sku)).includes(s.q)))
    const safety = (sku) => skuOf(sku)?.safety
    return { s, rows, summary, owners, logs, safety }
  },
  template: `<div>
    <Head t="库存"/>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.tab" :items="['物料仓', '工厂仓', '全部']"/>
      <button v-if="s.wh" class="chip on" @click="s.wh = ''">{{ whName(s.wh) }}<Ico n="x" :s="12"/></button>
      <select class="sel" style="height:28px" v-model="s.owner"><option value="">全部物权</option><option v-for="o in owners" :key="o" :value="o">{{ ownerName(o) }}</option></select>
      <span class="sp"></span><Seg v-model="s.view" :items="['明细', '按物料', '日志']"/><Search v-model="s.q"/>
    </div>
    <div v-if="s.view === '明细'" data-c="库存明细">
      <Tbl :rows="rows" link @row="r => s.log = r" empty="没有库存" :cols="[
        { k: 'w', t: '仓库', f: r => whName(r.w) }, { k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: r => skuName(r.sku) },
        { k: 'owner', t: '物权' }, { k: 'qty', t: '数量', c: 'num' }, { k: 'safety', t: '安全库存', c: 'num', f: r => safety(r.sku) ? fq(safety(r.sku)) : '—' }, { k: 'last', t: '最近变动', f: r => fd(r.last) }]">
        <template #owner="{ r }"><span :class="{ amber: r.owner !== '自有' }">{{ ownerName(r.owner) }}</span></template>
        <template #qty="{ r }"><span :class="{ red: safety(r.sku) && r.qty < safety(r.sku) }">{{ fq(r.qty) }}</span> <span class="dim">{{ unitOf(r.sku) }}</span></template>
      </Tbl>
    </div>
    <div v-if="s.view === '按物料'" data-c="库存汇总">
      <Tbl :rows="summary" empty="没有库存" :cols="[{ k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: r => skuName(r.sku) }, { k: 'owner', t: '物权', f: r => ownerName(r.owner) },
        { k: 'mat', t: '物料仓', c: 'num', f: r => fq(r.mat) }, { k: 'fac', t: '工厂仓', c: 'num', f: r => fq(r.fac) }, { k: 'all', t: '合计', c: 'num', f: r => fq(r.mat + r.fac) + ' ' + unitOf(r.sku) }]"/>
    </div>
    <div v-if="s.view === '日志'" data-c="库存日志"><StockLogTable :rows="logs"/></div>
    <Modal v-if="s.log" :t="whName(s.log.w) + ' · ' + skuName(s.log.sku)" :w="820" @close="s.log = null"><StockLogTable :rows="logs" inset/></Modal>
  </div>`,
}, { mod: 'stock', t: '库存' })

comp('StockLogTable', {
  props: { rows: Array, inset: Boolean },
  template: `<Tbl :inset="inset" :flat="inset" :rows="rows" empty="没有变动" :cols="[{ k: 'at', t: '日期', f: x => fd(x.at) }, { k: 'type', t: '类型' }, { k: 'no', t: '单号', c: 'code' },
    { k: 'wh', t: '仓库', f: x => whName(x.wh) }, { k: 'sku', t: '物料', f: x => skuName(x.sku) }, { k: 'owner', t: '物权', f: x => ownerName(x.owner) },
    { k: 'delta', t: '变动', c: 'num' }, { k: 'bal', t: '结存', c: 'num', f: x => fq(x.bal) }, { k: 'lot', t: '缸号', f: x => x.lot || '—' }, { k: 'who', t: '操作人', f: x => userName(x.who) }]">
    <template #no="{ r }"><a :href="'#/stock-docs/' + r.no">{{ r.no }}</a></template>
    <template #delta="{ r }"><span :class="r.delta > 0 ? 'green' : 'red'">{{ r.delta > 0 ? '+' : '' }}{{ fq(r.delta) }}</span></template>
  </Tbl>`,
})

expose({ updateMrpRow, srcText, purUnit, partnerName })
