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
// 物料 SKU 拆开显示（打印、导出用）：名称、颜色、规格、供应商货号、供应商色号
const skuParts = (s) => { const m = matOfSku(s), k = skuOf(s); return { name: m?.name || s, color: k?.color ? colorName(k.color) : '—', spec: k?.spec || '—', supNo: m?.supNo || '—', supColor: k?.supColor || '—' } }
const nonFgWhs = () => db.warehouses.filter((w) => w.type !== '成品仓' && w.status !== '停用')
if (!db.reserve) db.reserve = {} // 备料比：物料类型 → %（PRD §6.6.1），calcNeeds 读它

/* ---------- 算料 ---------- */
const mrpKey = (r) => r.src + '|' + r.sku + '|' + r.owner
// 算料结果里手动删行（特殊情况，PRD §6.6.1）：只有草稿、没转采购的行
function removeMrpRow(m, r) {
  if (m.status !== '草稿') fail('只有草稿算料单能删行')
  if (r.converted > 0) fail('这一行已经转了采购，不能删')
  m.rows.splice(m.rows.indexOf(r), 1)
  act(m, `删行：${skuName(r.sku)}（${r.src}，需求 ${fq(r.need)} ${unitOf(r.sku)}）`)
}
// 重新算料（订单变更后）：先关旧单释放占用，再按订单现在的数量算新单；旧单已转采购的量带过来免得重复买；记下每种料 + / −
function redoMrp(m) {
  for (const no of m.orders) { const o = order(no); if (!orderLive(o)) fail(`${no} 是「${o.status}」，只有已审核的订单能算料`) }
  if (m.status !== '已关闭') move('mrp', m, '关闭', '重新算料')
  const n = createMrp(m.orders)
  const old = new Map(m.rows.map((r) => [mrpKey(r), r]))
  for (const r of n.rows) { const o = old.get(mrpKey(r)); if (o?.converted && r.src === '我方采购') { r.converted = o.converted; r.purQty = Math.max(r.purQty, o.converted) } }
  const gone = m.rows.filter((r) => !n.rows.some((x) => mrpKey(x) === mrpKey(r)))
  n.prev = { no: m.no, rows: [...n.rows.map((r) => ({ r, from: old.get(mrpKey(r))?.need || 0, to: r.need })), ...gone.map((r) => ({ r, from: r.need, to: 0 }))]
    .map(({ r, from, to }) => ({ src: r.src, sku: r.sku, owner: r.owner, from, to, d: rnd(to - from, 3) })) }
  m.next = n.no
  act(n, '由 ' + m.no + ' 重新算料')
  act(m, '重新算料，换成 ' + n.no)
  return n
}

route('/mrp', {
  setup() {
    const s = reactive({ mine: false, tab: '全部', q: '', by: '', add: false, reserve: false, flt: newFlt() })
    const scoped = computed(() => db.mrps.filter((m) => inScope(s.mine, m)))
    const tabs = computed(() => ['全部', '草稿', '已确认', '已关闭'].map((k) => [k, k, k === '全部' ? scoped.value.length : scoped.value.filter((m) => m.status === k).length]))
    const stylesOf = (m) => [...new Set(m.orders.flatMap((o) => orderStyles(order(o))))]
    const makers = computed(() => [...new Set(db.mrps.map(creatorOf).filter(Boolean))])
    const rows = computed(() => scoped.value.filter((m) => (s.tab === '全部' || m.status === s.tab) && (!s.by || creatorOf(m) === s.by) && passFlt(s.flt, { date: m.date }) && matchQ(s.q, m.no, ...m.orders, ...stylesOf(m))).slice().reverse())
    const buyRows = (m) => m.rows.filter((r) => r.src === '我方采购' && r.purQty > 0)
    const reserveText = computed(() => Object.entries(db.reserve).filter(([, v]) => v > 0).map(([t, v]) => t + ' ' + v + '%').join('、'))
    return { s, tabs, rows, buyRows, stylesOf, makers, reserveText }
  },
  template: `<div>
    <Head t="算料"><button class="btn" @click="s.reserve = true" title="按物料类型多备一点料，默认 0">备料比<span class="dim" style="margin-left:4px">{{ reserveText || '都是 0' }}</span></button><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>选订单算料</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><Filters v-model="s.flt"/>
      <select class="sel" style="height:28px" v-model="s.by"><option value="">全部制单人</option><option v-for="u in makers" :key="u" :value="u">{{ u }}</option></select>
      <span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q"/></div>
    <div data-c="算料单列表">
      <Tbl :rows="rows" link @row="m => go('/mrp/' + m.no)" empty="没有符合条件的算料单。订单审核后点「选订单算料」" :cols="[
        { k: 'no', t: '算料单号', c: 'code' }, { k: 'orders', t: '订单', c: 'code', f: m => m.orders.join(' ') },
        { k: 'styles', t: '款式', f: m => stylesOf(m).join(' ') },
        { k: 'buy', t: '要买', c: 'num', f: m => buyRows(m).length + ' 种' }, { k: 'conv', t: '转采购' },
        { k: 'by', t: '制单人', f: m => creatorOf(m) || '—' }, { k: 'date', t: '日期', f: m => fd(m.date) }, { k: 'status', t: '状态' }]">
        <template #conv="{ r }"><Prog v-if="buyRows(r).length" :a="buyRows(r).filter(x => x.converted >= x.purQty).length" :b="buyRows(r).length"/><span v-else class="dim">不用买</span></template>
        <template #status="{ r }"><St :s="r.status"/><span v-if="r.next" class="dim" style="font-size:12px;margin-left:4px">→ {{ r.next }}</span></template>
      </Tbl>
    </div>
    <MrpNew v-if="s.add" @close="s.add = false"/>
    <MrpReserve v-if="s.reserve" @close="s.reserve = false"/>
  </div>`,
}, { mod: 'mrp', t: '算料' })

comp('MrpReserve', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive(Object.fromEntries(db.dicts.物料类型.map((t) => [t, db.reserve[t] || 0])))
    function ok() {
      const bad = Object.entries(f).filter(([, v]) => !(v >= 0 && v <= 50))
      if (bad.length) return toast('备料比填 0 到 50 之间的数', null, true)
      Object.assign(db.reserve, f)
      emit('close')
      toast('备料比改好了，以后新算的算料单按它算')
    }
    return { f, ok }
  },
  template: `<Modal t="备料比" :w="480" @close="$emit('close')">
    <p class="mut" style="margin-bottom:14px">需求 = 毛需求 ×（1 + 损耗）×（1 + 备料比）。按物料类型设，比如面料多备 2% 留着补料、防色差；默认 0。改了只影响以后新算的算料单。</p>
    <div class="form"><label v-for="(v, t) in f" :key="t" class="f">{{ t }}（%）<input class="inp num" type="number" min="0" max="50" v-model.number="f[t]"></label></div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">保存</button></template>
  </Modal>`,
})

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
      { k: 'ck', t: '', w: '28px' }, { k: 'no', t: '订单号', c: 'code' }, { k: 'cust', t: TRADE ? '客户' : '类型', f: custOrDash }, { k: 'styles', t: '款式', c: 'code', f: o => orderStyles(o).join(' ') },
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
    const rowsOf = (src) => (m.value ? m.value.rows.filter((r) => r.src === src) : [])
    const buy = computed(() => rowsOf('我方采购'))
    const picked = reactive(buy.value.filter((r) => r.purQty > r.converted).map(mrpKey))
    const toggle = (r) => { const k = picked.indexOf(mrpKey(r)); k >= 0 ? picked.splice(k, 1) : picked.push(mrpKey(r)) }
    const open = computed(() => m.value.status !== '已关闭')
    const edit = computed(() => m.value.status === '草稿')
    const left = computed(() => buy.value.some((r) => r.purQty > r.converted))
    const suppliersFor = () => db.suppliers.filter((s) => s.cats.some((c) => ['面料商', '辅料商', '包材商'].includes(c)))
    const confirmer = computed(() => [...(m.value.log || [])].reverse().find((x) => x.text === '确认' || x.text.startsWith('确认')))
    const changed = computed(() => (m.value.prev ? m.value.prev.rows.filter((x) => x.d) : []))
    const short = (r) => Math.max(0, rnd(r.need - r.useStock - mrpRecv(m.value, r) - useTransitSplit(r).got, 3))
    const tr = ref(null) // 点开看本次用在途用的是哪几张采购单、赶不上的在途
    const tot = computed(() => ({ amt: sum(buy.value, (r) => r.purQty * r.price), conv: sum(buy.value, (r) => r.converted * r.price) }))
    function toPo() {
      const idxs = m.value.rows.map((r, i) => (picked.includes(mrpKey(r)) ? i : -1)).filter((i) => i >= 0)
      const pos = run(() => mrpToPurchases(m.value, idxs))
      if (pos?.length) { picked.splice(0); toast(`按供应商拆成 ${pos.length} 张采购单草稿`, ['打开 ' + pos[0].no, '/purchases/' + pos[0].no]) }
    }
    async function redo() {
      if (!(await ask('重新算料', '按订单现在的数量和锁定的资料包重算一张新算料单，和这张对比列出每种料 + / −；这张自动关闭，已经转了采购的量带到新单上。', '重新算料'))) return
      const n = run(() => redoMrp(m.value))
      if (n?.no) { go('/mrp/' + n.no); toast(n.prev.rows.some((x) => x.d) ? `重新算好了，有 ${n.prev.rows.filter((x) => x.d).length} 种料变了` : '重新算好了，需求没变') }
    }
    async function close() { if (await ask('关闭 ' + m.value.no, '关闭后不能再生成采购单，"本次使用库存"的占用释放给别的算料单。', '关闭')) docAct('mrp', m.value, '关闭') }
    async function delRow(r) { if (await ask('删掉这一行', `${skuName(r.sku)} 不再算进这张单（比如这种料改由工厂自备）。删了要重新算料才回得来。`, '删除', true)) run(() => removeMrpRow(m.value, r)) }
    const more = computed(() => [{ t: '重新算料', fn: redo }, open.value && { t: '关闭', fn: close }].filter(Boolean))
    return { m, rowsOf, buy, picked, toggle, open, edit, left, suppliersFor, confirmer, changed, short, tr, tot, toPo, more, delRow, updateMrpRow, mrpKey, mrpRecv }
  },
  template: `<div v-if="m">
    <Head :t="m.no" :back="['算料', '/mrp']">
      <button v-if="can('mrp', m, '确认')" class="btn" @click="docAct('mrp', m, '确认')">确认</button>
      <button v-if="open && left" class="btn pri" :disabled="!picked.length" @click="toPo">生成采购单<span v-if="picked.length">（{{ picked.length }}）</span></button>
      <More :items="more"/>
    </Head>
    <div class="page">
      <Upstream :d="m"/>
      <div v-if="m.next" class="banner"><Ico n="alert"/><span>这张已经重新算过，看新的 <a class="code" :href="'#/mrp/' + m.next">{{ m.next }}</a></span></div>
      <div class="title">算料 <span v-for="o in m.orders" :key="o" class="code" style="font-size:14px"><a :href="'#/orders/' + o">{{ o }}</a></span><St :s="m.status"/></div>
      <div class="sub"><span>{{ fd(m.date) }}</span><span>{{ [...new Set(m.orders.flatMap(o => orderStyles(order(o))))].join(' ') }}</span></div>
      <dl class="props wide" data-c="算料单单头">
        <dt>主体公司</dt><dd>{{ companyName(m.company) }}</dd>
        <dt>制单</dt><dd>{{ m.log[0] ? m.log[0].who + ' · ' + fd(m.log[0].at) : '—' }}</dd>
        <dt>确认</dt><dd>{{ confirmer ? confirmer.who + ' · ' + fd(confirmer.at) : '—' }}</dd>
        <dt>备料比</dt><dd>{{ Object.entries(db.reserve).filter(x => x[1] > 0).map(x => x[0] + ' ' + x[1] + '%').join('、') || '都是 0' }}<span class="dim">（新算时用）</span></dd>
        <dt>要料日期</dt><dd :title="'订单已排单取加工单裁剪的计划开始；没排单按承诺交期倒推。在途预计到货不晚于它才算赶得上'">{{ m.needDate ? fd(m.needDate) : '—' }}<span class="dim">（算的时候）</span></dd>
        <dt>备注</dt><dd style="grid-column:span 5"><NoteEdit :d="m"/></dd>
      </dl>
      <div v-if="m.prev" class="sec" data-c="和上一张对比"><h3>和上一张 <a class="code" :href="'#/mrp/' + m.prev.no">{{ m.prev.no }}</a> 比</h3>
        <Tbl v-if="changed.length" inset flat :rows="changed" :cols="[{ k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'code', t: 'SKU', c: 'code', f: x => x.sku }, { k: 'src', t: '来源' }, { k: 'from', t: '上一张需求', c: 'num', f: x => fq(x.from) }, { k: 'to', t: '这一张', c: 'num', f: x => fq(x.to) + ' ' + unitOf(x.sku) }, { k: 'd', t: '变化', c: 'num' }]">
          <template #d="{ r }"><b :class="r.d > 0 ? 'red' : 'green'">{{ r.d > 0 ? '+' : '' }}{{ fq(r.d) }}</b></template></Tbl>
        <p v-else class="dim">每种料的需求都没变。</p>
      </div>
      <div class="sec" data-c="我方采购"><h3>我方采购<span class="acts dim" style="font-size:12px">预计金额 {{ fm(tot.amt) }}　已转采购 {{ fm(tot.conv) }}</span></h3>
        <div style="overflow-x:auto"><table class="tbl inset flat mrp-buy">
          <thead><tr><th style="width:28px"></th><th>物料</th><th>供应商</th><th class="num">毛需求</th><th class="num">需求</th><th class="num">可用库存</th><th class="num">本次用库存</th><th class="num" title="交期赶得上要料日期的在途，去掉别的算料单占着的">可用在途</th><th class="num">本次用在途</th><th class="num" title="预计到货晚于要料日期，不扣">赶不上的在途</th><th class="num">净需求</th><th class="num">建议采购</th><th class="num">参考单价</th><th class="num">预计金额</th><th class="num">已转采购</th><th class="num">已入库</th><th v-if="edit" style="width:32px"></th></tr></thead>
          <tbody>
            <template v-for="r in buy" :key="mrpKey(r)">
            <tr :class="{ sel: picked.includes(mrpKey(r)) }">
              <td><input type="checkbox" :checked="picked.includes(mrpKey(r))" :disabled="!open || r.purQty <= r.converted" @change="toggle(r)" style="accent-color:var(--accent)"></td>
              <td><span class="row"><Thumb :code="r.sku" :s="32"/><span class="col"><span>{{ skuName(r.sku) }}</span><small class="code dim">{{ r.sku }}</small></span></span></td>
              <td><select v-if="edit" class="sel" style="height:26px" v-model="r.supplier"><option v-for="s in suppliersFor()" :key="s.code" :value="s.code">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></select><template v-else>{{ supName(r.supplier) }}</template></td>
              <td class="num dim">{{ fq(r.gross) }}</td>
              <td class="num" :title="'损耗' + (r.reserve ? '、备料比 ' + r.reserve + '%' : '') + '以后'">{{ fq(r.need) }} <span class="dim">{{ unitOf(r.sku) }}</span></td>
              <td class="num">{{ fq(r.avail) }}</td>
              <td class="num"><input v-if="edit" class="inp cell num ghost" style="width:80px" :value="r.useStock" @change="updateMrpRow(r, 'useStock', $event.target.value)"><template v-else>{{ fq(r.useStock) }}</template></td>
              <td class="num">{{ fq(r.availTransit || 0) }}</td>
              <td class="num"><input v-if="edit" class="inp cell num ghost" style="width:80px" :value="r.useTransit || 0" @change="updateMrpRow(r, 'useTransit', $event.target.value)"><template v-else>{{ fq(r.useTransit || 0) }}</template>
                <a v-if="r.transitUse?.length || r.transitLate?.length" class="dim" style="font-size:12px;margin-left:4px" @click="tr = tr === mrpKey(r) ? null : mrpKey(r)" title="看用的是哪几张采购单">{{ tr === mrpKey(r) ? '收起' : '明细' }}</a></td>
              <td class="num"><span v-if="r.lateTransit" class="amber">{{ fq(r.lateTransit) }}</span><span v-else class="dim">—</span></td>
              <td class="num">{{ fq(r.net) }}</td>
              <td class="num"><input v-if="edit" class="inp cell num ghost" style="width:80px;font-weight:600" :value="r.purQty" @change="updateMrpRow(r, 'purQty', $event.target.value, m)"><b v-else>{{ fq(r.purQty) }}</b> <span class="dim">{{ purUnit(r.sku) }}</span></td>
              <td class="num">{{ fm(r.price) }}</td>
              <td class="num">{{ fm(r.purQty * r.price) }}</td>
              <td class="num">{{ r.converted ? fq(r.converted) : '—' }}</td>
              <td class="num"><template v-if="r.converted || r.useStock || r.useTransit"><span v-if="!short(r)" class="flag" style="color:var(--green)"><Ico n="check" :s="12"/>齐</span><span v-else :title="'还差 ' + fq(short(r)) + ' ' + unitOf(r.sku)">{{ fq(mrpRecv(m, r)) }} <span class="amber" style="font-size:12px">差 {{ fq(short(r)) }}</span></span></template><span v-else class="dim">—</span></td>
              <td v-if="edit"><button v-if="!r.converted" class="btn ghost icon sm" title="删掉这一行" @click="delRow(r)"><Ico n="x" :s="12"/></button></td>
            </tr>
            <tr v-if="tr === mrpKey(r)" data-c="在途明细"><td></td><td :colspan="edit ? 16 : 15" style="white-space:normal;padding:8px 0 12px">
              <div v-if="r.transitUse?.length" class="mut" style="font-size:12.5px">本次用在途：<span v-for="u in r.transitUse" :key="u.po + u.i" style="margin-right:14px"><a class="code" :href="'#/purchases/' + u.po">{{ u.po }}</a> {{ purchase(u.po)?.type }} · 预计 {{ fd(u.date) }} 到 · 用 {{ fq(u.qty) }} {{ unitOf(r.sku) }}</span></div>
              <div v-if="r.transitLate?.length" class="amber" style="font-size:12.5px;margin-top:4px">赶不上的在途（要料日期 {{ fd(m.needDate) }}，不扣）：<span v-for="u in r.transitLate" :key="u.po + u.i" style="margin-right:14px"><a class="code" :href="'#/purchases/' + u.po">{{ u.po }}</a> 预计 {{ fd(u.date) }} 到 · {{ fq(u.free) }} {{ unitOf(r.sku) }}</span></div>
            </td></tr>
            </template>
            <tr v-if="!buy.length"><td :colspan="edit ? 17 : 16" class="empty">资料包里没有我方采购的料</td></tr>
          </tbody>
        </table></div>
        <p class="dim" style="font-size:12px;margin-top:8px">毛需求 = 订单数 × 单件用量；需求 = 毛需求 ×（1 + 损耗）×（1 + 备料比）。可用 = 库存 + 交期赶得上要料日期的在途 − 别的算料单占着的：先下的备货采购还没到也自动扣，不会重复买；赶不上的在途单独列出来、不扣。本次用库存、本次用在途可以改小。已入库按这张单（和重新算料前的旧单）生成的采购单、本次用在途里到了货的算，加上本次用库存够了就是「齐」。</p>
      </div>
      <div class="sec" data-c="待客户提供" v-if="TRADE && rowsOf('客供').length"><h3>待客户提供</h3>
        <Tbl inset flat :rows="rowsOf('客供')" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku), img: r => r.sku }, { k: 'code', t: 'SKU', c: 'code', f: r => r.sku }, { k: 'owner', t: '客户', f: r => custName(r.owner) }, { k: 'need', t: '需求', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'avail', t: '客供库存', c: 'num', f: r => fq(r.avail) }, { k: 'gap', t: '缺口', c: 'num' }, { k: 'del', t: '' }]">
          <template #gap="{ r }"><span :class="{ red: r.net > 0 }">{{ r.net > 0 ? fq(r.net) : '够了' }}</span></template>
          <template #del="{ r }"><button v-if="edit" class="btn ghost icon sm" title="删掉这一行" @click="delRow(r)"><Ico n="x" :s="12"/></button></template></Tbl></div>
      <div class="sec" data-c="工厂自备" v-if="rowsOf('工厂自备').length"><h3>工厂自备</h3>
        <Tbl inset flat :rows="rowsOf('工厂自备')" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku), img: r => r.sku }, { k: 'code', t: 'SKU', c: 'code', f: r => r.sku }, { k: 'need', t: '需求', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'styles', t: '款式', c: 'code', f: r => r.styles.join(' ') }]"/></div>
      <div class="sec"><DocFiles :doc="m"/></div>
      <div class="sec"><h3>操作记录</h3><Log :items="m.log"/></div>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'mrp', t: (p) => '算料单 ' + p.no })

/* ---------- 采购单 ---------- */
const PO_FIELDS = [['company', '主体', companyName], ['supplier', '供应商', supName], ['type', '类型'], ['buyer', '采购员', userName], ['date', '下单日期', fd], ['due', '交货日期', fd],
  ['wh', '交货地点', whName], ['settle', '结算方式'], ['tax', '税率'], ['taxIncl', '含税', (v) => (v ? '含税' : '不含税')], ['plan', '计划用于款号'], ['note', '备注']]
const poLineSig = (l) => [l.sku, l.owner || '自有', l.qty, l.price, l.priceWhy || '', l.due || '', l.expect || ''].join('|')
function updatePurchase(p, f) {
  if (p.status !== '草稿') fail('只有草稿能改；已提交的先撤回，或等审核人驳回')
  if (!canUseSupplier(f.supplier, '采购')) fail(`${supName(f.supplier)} 现在是「${sup(f.supplier)?.status || '未选'}」，不能下采购单`)
  const lines = (f.lines || []).filter((l) => l.sku && l.qty > 0)
  if (!lines.length) fail('至少一行物料')
  if (!f.date || !f.due) fail('下单日期、交货日期都要填')
  if (f.due < f.date) fail('交货日期不能早于下单日期')
  lines.forEach((l) => { if (l.price == null || l.price === '') l.price = poDefaultPrice(f.supplier, l.sku, f.date) })
  checkPoPrices(f.supplier, f.date, lines) // 和档案现价不一样的行要写改价原因（5e-price.js）
  const ch = applyFields(p, f, PO_FIELDS)
  if (p.lines.map(poLineSig).join(';') !== lines.map(poLineSig).join(';')) {
    ch.push(p.lines.length === lines.length ? '明细：' + lines.filter((l, i) => poLineSig(l) !== poLineSig(p.lines[i])).map((l) => skuName(l.sku)).join('、') : `明细 ${p.lines.length} 行 → ${lines.length} 行`)
    // 算料生成的行改了数量或删了：算料单的"已转采购"跟着改，免得少买或重复买
    shiftMrp(p.lines, -1)
    p.lines = lines.map((l) => ({ owner: '自有', due: null, expect: null, note: '', priceWhy: '', received: 0, returned: 0, src: null, ...l }))
    shiftMrp(p.lines, 1, p.no + ' 改数量')
  }
  if (!ch.length) fail('没有改动')
  act(p, '改：' + ch.join('；'))
}
const mrpRowOf = (l) => mrp(l.src.mrp)?.rows.find((x) => x.src === '我方采购' && x.sku === l.sku && x.owner === (l.owner || '自有'))
function shiftMrp(lines, k, why) {
  for (const l of lines.filter((x) => x.src?.mrp)) {
    const r = mrpRowOf(l)
    if (!r) continue
    const was = r.converted
    r.converted = Math.max(0, rnd(r.converted + k * l.qty, 3))
    if (why && r.converted !== was) act(mrp(l.src.mrp), `${why}，${skuName(l.sku)} 已转采购 → ${fq(r.converted)}`)
  }
}
// 算料生成的采购单删掉或作废：算料单那几行的"已转采购"退回去，可以重新生成
function releaseMrp(p) {
  for (const l of p.lines.filter((x) => x.src?.mrp)) {
    const m = mrp(l.src.mrp), r = mrpRowOf(l)
    if (r && r.converted) { r.converted = Math.max(0, rnd(r.converted - l.qty, 3)); act(m, `${p.no} ${p.status === '草稿' ? '删除' : '作废'}，${skuName(l.sku)} 已转采购退回 ${fq(l.qty)}`) }
  }
}
function deletePurchase(p) {
  if (p.status === '草稿') releaseMrp(p)
  removeDraft(db.purchases, p)
}
// 采购退货：从料现在所在的仓退（入库时可能改到了工厂仓），库存不够就不建单；已退货在过账时回写（采购单位）
function returnPurchase(p, f) {
  if (!['已审核', '部分到货', '已到齐'].includes(p.status)) fail('采购单入库后才能退货')
  const ls = f.lines.filter((x) => x.qty > 0)
  if (!ls.length) fail('至少退一行')
  const errs = ls.filter((x) => x.qty > p.lines[x.i].received - p.lines[x.i].returned + 0.0005).map((x) => `${skuName(p.lines[x.i].sku)}：最多能退 ${fq(p.lines[x.i].received - p.lines[x.i].returned)} ${purUnit(p.lines[x.i].sku)}`)
  if (errs.length) fail('退货数超过已入库', errs)
  const from = f.from || p.wh
  const lines = ls.map((x) => { const l = p.lines[x.i]; return { sku: l.sku, owner: l.owner, qty: rnd(x.qty * matOfSku(l.sku).factor, 3), purQty: x.qty, price: l.price, poLine: x.i, note: x.note || '' } })
  const short = stockShortage(from, lines)
  if (short.length) fail(`${whName(from)} 库存不够，没退成`, short)
  const d = createStockDoc({ type: '采购退货', src: p.no, partner: p.supplier, from, reason: f.reason || '', logistics: f.logistics || '', note: f.note || '', ...(f.date ? { date: f.date } : {}), lines })
  if (f.files?.length) d.files = f.files
  postStockDoc(d)
  return d
}

route('/purchases', {
  setup() {
    const s = reactive({ mine: false, tab: nav.query.s || '全部', q: '', buyer: '', add: false, flt: newFlt() })
    const ST = ['全部', '草稿', '待审核', '已审核', '部分到货', '已到齐', '已结案', '已作废']
    const scoped = computed(() => db.purchases.filter((p) => inScope(s.mine, p)))
    const tabs = computed(() => ST.map((k) => [k, k, k === '全部' ? scoped.value.length : scoped.value.filter((p) => p.status === k).length]))
    const rows = computed(() => scoped.value.filter((p) => (s.tab === '全部' || p.status === s.tab) && (!s.buyer || p.buyer === s.buyer)
      && passFlt(s.flt, { date: p.date, party: p.supplier })
      && matchQ(s.q, p.no, supName(p.supplier), ...p.lines.flatMap((l) => [l.sku, skuName(l.sku), l.src?.mrp || '', srcText(l)]))).slice().reverse())
    const xcols = [{ k: 'no', t: '采购单号', c: 'code' }, { k: 'sup', t: '供应商', f: (p) => supName(p.supplier) }, { k: 'type', t: '类型' },
      { k: 'mat', t: '物料', f: (p) => p.lines.map((l) => l.sku).join(' ') }, { k: 'qty', t: '总数量', f: poQty }, { k: 'amt', t: '金额', f: poAmt },
      { k: 'date', t: '下单日期' }, { k: 'due', t: '交货日期' }, { k: 'wh', t: '交货地点', f: (p) => whName(p.wh) }, { k: 'recv', t: '已入库', f: poRecv },
      { k: 'buyer', t: '采购员', f: (p) => userName(p.buyer) }, { k: 'company', t: '主体', f: (p) => companyName(p.company) }, { k: 'status', t: '状态' }]
    return { s, tabs, rows, xcols }
  },
  template: `<div>
    <Head t="采购"><ExportBtn :rows="rows" :cols="xcols" name="采购单"/><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建采购单</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div class="bar" data-c="筛选：日期、供应商、采购员"><Filters v-model="s.flt" party="supplier" label="下单日期"/>
      <select class="sel" style="height:28px" v-model="s.buyer"><option value="">全部采购员</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select>
      <span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q"/></div>
    <div data-c="采购单列表">
      <Tbl :rows="rows" link @row="p => go('/purchases/' + p.no)" empty="没有符合条件的采购单。单号、物料、订单号、款号都能搜，几个号用空格隔开" :cols="[
        { k: 'no', t: '采购单号', c: 'code' }, { k: 'sup', t: '供应商' }, { k: 'type', t: '类型' },
        { k: 'mat', t: '物料', f: p => skuName(p.lines[0].sku) + (p.lines.length > 1 ? ' 等 ' + p.lines.length + ' 种' : ''), img: p => p.lines[0].sku },
        { k: 'amt', t: '金额', c: 'num', f: p => fm(poAmt(p)) }, { k: 'date', t: '下单日期', f: p => fd(p.date) }, { k: 'due', t: '交期', f: p => fd(p.due) }, { k: 'recv', t: '到货' },
        { k: 'buyer', t: '采购员', f: p => userName(p.buyer) }, { k: 'status', t: '状态' }]">
        <template #sup="{ r }">{{ supName(r.supplier) }}<span v-if="sup(r.supplier).status !== '合作中'" class="flag red" style="margin-left:6px">{{ sup(r.supplier).status }}</span></template>
        <template #recv="{ r }"><Prog v-if="!['草稿', '待审核', '已作废'].includes(r.status)" :a="poRecv(r)" :b="poQty(r)"/><span v-else class="dim">—</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <PurchaseForm v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'purchases', t: '采购' })

// 新建、改草稿共用：po 不给就是新建
comp('PurchaseForm', {
  props: { po: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const x = p.po
    const s0 = sup('GYS0001')
    const f = reactive(x
      ? { ...Object.fromEntries(PO_FIELDS.map(([k]) => [k, x[k]])), plan: [...(x.plan || [])], lines: x.lines.map((l) => ({ sku: l.sku, owner: l.owner || '自有', qty: l.qty, price: l.price, priceWhy: l.priceWhy || '', due: l.due || '', expect: l.expect || '', src: l.src, note: l.note })) }
      : { company: myCompany(), supplier: s0.code, type: '备货采购', buyer: s0.buyer || 'u2', date: clock.today, due: '', wh: 'WL01', settle: s0.settle, tax: s0.tax, taxIncl: true, plan: [], note: '', files: [], lines: [{ sku: '', owner: '自有', qty: null, price: null, priceWhy: '', due: '', expect: '' }] })
    const sups = computed(() => db.suppliers.filter((s) => !isFactory(s)))
    const skus = computed(() => db.materials.filter((m) => m.status === '启用').flatMap((m) => m.skus.filter((k) => k.status !== '停用').map((k) => ({ code: k.code, t: skuName(k.code) + ' · ' + k.code, mine: m.supplier === f.supplier }))).sort((a, b) => b.mine - a.mine))
    const owners = computed(() => ['自有', ...db.customers.filter((c) => c.status === '启用').map((c) => c.code)])
    const lead = computed(() => Math.max(7, ...f.lines.filter((l) => l.sku).map((l) => matOfSku(l.sku).lead || 7)))
    const dueAuto = computed(() => addDays(f.date || clock.today, lead.value))
    // 单价默认取价格档案里这家在下单日的现价，没有就带物料 SKU 单价（PRD §6.6.2）；换了供应商，没手改过的行跟着换
    const dft = (l) => (l.sku ? poDefaultPrice(f.supplier, l.sku, f.date) : null)
    const curOf = (l) => (l.sku ? curMatPrice(f.supplier, matOfSku(l.sku).code, l.sku, f.date) : null)
    const off = (l) => curOf(l) != null && l.price != null && l.price !== '' && !samePrice(curOf(l), l.price) // 和档案现价不一样：要写改价原因
    const was = new Map() // 行 → 上次带出来的默认价
    const setSku = (l) => { l.price = dft(l); was.set(l, l.price) }
    const setSup = () => {
      const s = sup(f.supplier); f.settle = s.settle; f.tax = s.tax; if (s.buyer) f.buyer = s.buyer
      f.lines.forEach((l) => { if (l.sku && (l.price == null || l.price === '' || l.price === was.get(l))) { l.price = dft(l); was.set(l, l.price) } })
    }
    const styles = computed(() => db.styles.filter((s) => s.status === '启用' && !f.plan.includes(s.code)))
    const addPlan = (e) => { if (e.target.value) f.plan.push(e.target.value); e.target.value = '' }
    const amt = computed(() => sum(f.lines, (l) => (+l.qty || 0) * (+l.price || 0)))
    function ok() {
      const g = { ...f, due: f.due || dueAuto.value, lines: f.lines.map((l) => ({ ...l, due: l.due || null, expect: l.expect || null })) }
      if (x) { if (run(() => updatePurchase(x, g))) { emit('close'); toast('已保存') } return }
      const po = run(() => createPurchase(g))
      if (po?.no) { emit('close'); go('/purchases/' + po.no) }
    }
    return { x, f, sups, skus, owners, lead, dueAuto, setSku, setSup, curOf, off, styles, addPlan, amt, ok, purUnit }
  },
  template: `<Modal :t="x ? '改采购单 ' + x.no : '新建采购单'" :w="900" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(4,1fr)" data-c="采购单单头">
      <label class="f" style="grid-column:span 2">供应商<select class="sel" v-model="f.supplier" @change="setSup"><option v-for="s in sups" :key="s.code" :value="s.code" :disabled="s.status !== '合作中'">{{ s.short }}{{ s.status !== '合作中' ? '（' + s.status + '）' : '' }}</option></select></label>
      <label class="f">采购类型<select class="sel" v-model="f.type"><option>备货采购</option><option>订单采购</option><option>打样采购</option></select></label>
      <label class="f">主体公司<CompanyPick v-model="f.company"/></label>
      <label class="f">采购员<select class="sel" v-model="f.buyer"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
      <label class="f">下单日期<input class="inp" type="date" v-model="f.date"></label>
      <label class="f">交货日期<input class="inp" type="date" v-model="f.due" :placeholder="dueAuto"><small v-if="!f.due" class="dim">不填 = {{ fd(dueAuto) }}（下单日 + 最长采购周期 {{ lead }} 天）</small></label>
      <label class="f">交货地点<select class="sel" v-model="f.wh"><option v-for="w in db.warehouses.filter(w => w.type !== '成品仓')" :key="w.code" :value="w.code">{{ w.name }}{{ w.type === '工厂仓' ? '（面料直发工厂）' : '' }}</option></select></label>
      <label class="f">结算方式<select class="sel" v-model="f.settle"><option v-for="v in db.dicts.结算方式" :key="v">{{ v }}</option></select></label>
      <label class="f">税率<select class="sel" v-model="f.tax"><option v-for="v in db.dicts.税率" :key="v">{{ v }}</option></select></label>
      <label class="f">是否含税<select class="sel" v-model="f.taxIncl"><option :value="true">含税</option><option :value="false">不含税</option></select></label>
      <div v-if="f.type === '备货采购'" class="f" style="grid-column:span 4" data-c="计划用于款号">计划用于款号<span class="row g4" style="flex-wrap:wrap;min-height:30px">
        <span v-for="(c, i) in f.plan" :key="c" class="pill code">{{ c }} {{ styleName(c) }}<a style="margin-left:4px" title="去掉" @click="f.plan.splice(i, 1)">×</a></span>
        <select class="sel" style="height:28px;width:200px" @change="addPlan"><option value="">{{ f.plan.length ? '再加一个款' : '选填：备的料打算给哪个款' }}</option><option v-for="s in styles" :key="s.code" :value="s.code">{{ s.code }} {{ s.name }}</option></select>
        <small class="dim">只是备注：不给这个款预留，到了进公共库存，谁先算料谁用</small></span></div>
      <label class="f" style="grid-column:span 4">备注<input class="inp" v-model="f.note" placeholder="比如：面料每卷标缸号，随货带色卡"></label>
    </div>
    <table class="tbl inset flat" style="margin-top:14px" data-c="采购明细"><thead><tr><th>物料</th><th v-if="TRADE" style="width:130px">物权</th><th class="num" style="width:96px">数量</th><th class="num" style="width:96px">单价</th><th style="width:150px">改价原因</th><th class="num" style="width:96px">金额</th><th style="width:136px">行交期</th><th style="width:136px">预计到货</th><th style="width:32px"></th></tr></thead>
      <tbody><tr v-for="(l, i) in f.lines" :key="i">
        <td><select class="sel" style="width:100%;height:28px" v-model="l.sku" @change="setSku(l)" :disabled="!!l.src"><option value="">选物料</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ k.t }}</option></select><small v-if="l.src" class="dim">来自算料单 {{ l.src.mrp }}</small></td>
        <td v-if="TRADE"><select class="sel" style="width:100%;height:28px" v-model="l.owner" title="客户代采记客户" :disabled="!!l.src"><option v-for="o in owners" :key="o" :value="o">{{ o === '自有' ? '自有' : '代采 · ' + custName(o) }}</option></select></td>
        <td><input class="inp cell num" type="number" v-model.number="l.qty" :placeholder="l.sku ? purUnit(l.sku) : ''"></td>
        <td><input class="inp cell num" type="number" v-model.number="l.price" :class="{ amber: off(l) }"><small v-if="curOf(l) != null" class="dim" style="display:block;text-align:right;font-size:11px">档案 {{ fm(curOf(l)) }}</small></td>
        <td><input v-if="off(l)" class="inp cell" style="width:100%" v-model="l.priceWhy" placeholder="改了单价要写原因"><span v-else class="dim">—</span></td>
        <td class="num">{{ l.qty && l.price ? fm(l.qty * l.price) : '—' }}</td>
        <td><input class="inp cell" type="date" v-model="l.due" title="不填用单头交货日期"></td>
        <td><input class="inp cell" type="date" v-model="l.expect"></td>
        <td><button class="btn ghost icon sm" @click="f.lines.splice(i, 1)"><Ico n="x" :s="12"/></button></td></tr></tbody>
      <tfoot><tr><td>合计 {{ f.lines.filter(l => l.sku).length }} 行</td><td v-if="TRADE"></td><td class="num">{{ fq(sum(f.lines, l => l.qty)) }}</td><td></td><td></td><td class="num">{{ fm(amt) }}</td><td colspan="3"></td></tr></tfoot></table>
    <div class="row" style="margin-top:8px"><button class="btn ghost" @click="f.lines.push({ sku: '', owner: '自有', qty: null, price: null, priceWhy: '', due: '', expect: '' })"><Ico n="plus" :s="14"/>加一行</button>
      <span class="dim" style="font-size:12px">单价默认取价格档案里这家的现价；改了价要写原因，审核时记进价格档案</span></div>
    <div v-if="!x" class="sec" data-c="采购单附件"><h3>附件<span class="dim" style="font-weight:400;font-size:12px">　选填：报价单、合同、色卡照片</span></h3><Pics v-model="f.files" files :s="56"/></div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ x ? '保存' : '存草稿' }}</button></template>
  </Modal>`,
})

route('/purchases/:no', {
  props: ['no'],
  setup(p) {
    const po = computed(() => purchase(p.no))
    const dlg = ref(''), printing = ref(false)
    const docs = computed(() => db.stockDocs.filter((d) => d.src === p.no))
    async function a(x) { const r = await docAct('purchase', po.value, x); if (r && x === '作废') releaseMrp(po.value) }
    async function del() {
      if (!(await ask('删除 ' + po.value.no, '草稿删掉就找不回来了；从算料单生成的，算料单上的"已转采购"会退回去，可以重新生成。', '删除', true))) return
      if (run(() => deletePurchase(po.value))) { go('/purchases'); toast('删掉了') }
    }
    const more = computed(() => [
      ['已审核', '部分到货', '已到齐'].includes(po.value.status) && poRecv(po.value) > 0 && { t: '退货', fn: () => { dlg.value = 'return' } },
      ...['撤回', '反审核', '结案', '取消结案'].filter((x) => can('purchase', po.value, x)).map((x) => ({ t: x === '结案' ? '结案（剩下的不要了）' : x, fn: () => a(x) })),
      { t: '打印', fn: () => { printing.value = true } },
      po.value.status === '草稿' && { t: '删除', danger: true, fn: del },
      can('purchase', po.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') },
    ].filter(Boolean))
    const progress = computed(() => `${fq(poRecv(po.value))} / ${fq(poQty(po.value))}（${fpct(poRecv(po.value), poQty(po.value))}%）`)
    return { po, dlg, printing, docs, a, more, progress, srcText, purUnit, skuParts }
  },
  template: `<div v-if="po">
    <Head :t="po.no" :back="['采购', '/purchases']">
      <template v-if="po.status === '草稿'"><button class="btn" @click="dlg = 'edit'">编辑</button><button class="btn pri" @click="a('提交')">提交审核</button></template>
      <template v-if="mayAct('purchase', po, '审核')"><button class="btn" @click="a('驳回')">驳回</button><button class="btn pri" @click="a('审核')">审核</button></template><ApproveWait v-else kind="purchase" :d="po"/>
      <button v-if="canGenContract('purchase', po)" class="btn" @click="dlg = 'contract'">生成合同</button>
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
            { k: 'sku', t: '物料' }, { k: 'supno', t: '供应商货号 · 色号', f: l => skuParts(l.sku).supNo + ' · ' + skuParts(l.sku).supColor }, ...(TRADE ? [{ k: 'owner', t: '物权', f: l => l.owner && l.owner !== '自有' ? '代采 · ' + custName(l.owner) : '自有' }] : []),
            { k: 'qty', t: '数量', c: 'num', f: l => fq(l.qty) + ' ' + purUnit(l.sku) }, { k: 'price', t: '单价', c: 'num', f: l => fm(l.price) },
            { k: 'amt', t: '金额', c: 'num', f: l => fm(l.qty * l.price) }, { k: 'recv', t: '已入库', c: 'num', f: l => fq(l.received) }, { k: 'ret', t: '已退货', c: 'num', f: l => l.returned ? fq(l.returned) : '—' },
            { k: 'left', t: '未入库', c: 'num', f: l => fq(Math.max(0, l.qty - l.received)) }, { k: 'due', t: '行交期', f: l => l.due ? fd(l.due) : '同单头' }, { k: 'exp', t: '预计到货' }, { k: 'src', t: '来源', c: 'code' }]">
            <template #sku="{ r }"><span class="row"><Thumb :code="r.sku" :s="32"/><span class="col"><span>{{ skuName(r.sku) }}</span><small class="code dim">{{ r.sku }}</small></span></span></template>
            <template #price="{ r }">{{ fm(r.price) }}<div v-if="r.priceWhy" class="amber" style="font-size:12px;white-space:normal" title="和价格档案现价不一样，改价原因">改价：{{ r.priceWhy }}</div></template>
            <template #exp="{ r }"><span v-if="r.received >= r.qty" class="dim">到齐</span><span v-else class="row g4">{{ fd(r.expect || r.due || po.due) }}<Days v-if="!['草稿','待审核','已结案','已作废'].includes(po.status)" :d="r.expect || r.due || po.due"/></span><div v-if="r.note" class="dim" style="font-size:12px">{{ r.note }}</div></template>
            <template #src="{ r }"><template v-if="r.src"><a v-if="r.src.mrp" :href="'#/mrp/' + r.src.mrp">{{ r.src.mrp }}</a><div class="dim" style="font-size:12px">{{ srcText(r) }}</div></template><span v-else class="dim">—</span></template>
            <template #foot><tr><td>合计 {{ po.lines.length }} 行</td><td></td><td></td><td class="num">{{ fq(poQty(po)) }}</td><td></td><td class="num">{{ fm(poAmt(po)) }}</td><td class="num">{{ fq(poRecv(po)) }}</td><td class="num">{{ fq(sum(po.lines, l => l.returned)) }}</td><td class="num">{{ fq(sum(po.lines, l => Math.max(0, l.qty - l.received))) }}</td><td colspan="3"></td></tr></template>
          </Tbl>
        </section>
        <div class="sec" data-c="入库记录"><h3>入库 / 退货</h3>
          <Tbl inset flat :rows="docs" link @row="d => go('/stock-docs/' + d.no)" empty="还没有入库" :cols="[{ k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'wh', t: '仓库', f: d => whName(d.to || d.from) }, { k: 'qty', t: '数量', c: 'num', f: d => d.lines.map(l => fq(l.purQty ?? l.qty)).join(' / ') }, { k: 'lot', t: '缸号', f: d => d.lines.map(l => l.lot).filter(Boolean).join(' ') || '—' }, { k: 'log', t: '物流单号', c: 'code', f: d => d.logistics || '—' }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template></Tbl></div>
      </div>
      <aside class="aside" data-c="采购单属性">
        <dl class="props">
          <dt>供应商</dt><dd><a :href="'#/suppliers/' + po.supplier">{{ sup(po.supplier).name }}</a></dd>
          <dt>采购类型</dt><dd>{{ po.type }}</dd>
          <template v-if="po.plan?.length"><dt>计划用于</dt><dd><a v-for="c in po.plan" :key="c" class="code" :href="'#/styles/' + c" style="margin-right:6px">{{ c }}</a><div class="dim" style="font-size:12px">只是备注，不预留</div></dd></template>
          <dt>采购员</dt><dd>{{ userName(po.buyer) }}</dd>
          <dt>下单日期</dt><dd>{{ fd(po.date) }}</dd>
          <dt>交货日期</dt><dd>{{ fd(po.due) }}</dd>
          <dt>交货地点</dt><dd>{{ whName(po.wh) }}</dd>
          <dt>结算</dt><dd>{{ po.settle }} · 税率 {{ po.tax }} · {{ po.taxIncl === false ? '不含税' : '含税' }}</dd>
          <dt>总数量</dt><dd>{{ fq(poQty(po)) }}</dd>
          <dt>总金额</dt><dd>{{ fm(poAmt(po)) }}</dd>
          <dt>到货进度</dt><dd>{{ ['草稿', '待审核'].includes(po.status) ? '—' : progress }}</dd>
          <DocMeta :d="po"/>
        </dl>
        <ApproveSteps kind="purchase" :d="po"/>
        <DocFiles :doc="po"/>
        <DocContracts kind="purchase" :d="po"/>
        <h4>操作记录</h4><Log :items="po.log"/>
      </aside>
    </div>
    <PurchaseReceive v-if="dlg === 'recv' || dlg === 'return'" :po="po" :mode="dlg" @close="dlg = ''"/>
    <PurchaseForm v-if="dlg === 'edit'" :po="po" @close="dlg = ''"/>
    <ContractGen v-if="dlg === 'contract'" kind="purchase" :d="po" @close="dlg = ''"/>
    <PrintSheet v-if="printing" t="采购单" :no="po.no" :company="po.company" @close="printing = false">
      <dl class="sheet-meta">
        <div><dt>供应商</dt><dd>{{ sup(po.supplier).name }}</dd></div><div><dt>联系人</dt><dd>{{ sup(po.supplier).contact || '—' }} {{ sup(po.supplier).phone || '' }}</dd></div><div><dt>采购类型</dt><dd>{{ po.type }}</dd></div>
        <div><dt>下单日期</dt><dd>{{ po.date }}</dd></div><div><dt>交货日期</dt><dd>{{ po.due }}</dd></div><div><dt>采购员</dt><dd>{{ userName(po.buyer) }}</dd></div>
        <div style="grid-column:span 2"><dt>交货地点</dt><dd>{{ whName(po.wh) }}　{{ wh(po.wh)?.addr || '' }}</dd></div><div><dt>结算</dt><dd>{{ po.settle }} · {{ po.tax }} {{ po.taxIncl === false ? '不含税' : '含税' }}</dd></div>
      </dl>
      <table class="sheet-tbl"><thead><tr><th>#</th><th>物料编码</th><th>名称</th><th>颜色</th><th>规格</th><th>供应商货号</th><th>色号</th><th>单位</th><th class="num">数量</th><th class="num">单价</th><th class="num">金额</th><th>交期</th></tr></thead>
        <tbody><tr v-for="(l, i) in po.lines" :key="i"><td>{{ i + 1 }}</td><td>{{ l.sku }}</td><td>{{ skuParts(l.sku).name }}</td><td>{{ skuParts(l.sku).color }}</td><td>{{ skuParts(l.sku).spec }}</td><td>{{ skuParts(l.sku).supNo }}</td><td>{{ skuParts(l.sku).supColor }}</td><td>{{ purUnit(l.sku) }}</td><td class="num">{{ fq(l.qty) }}</td><td class="num">{{ fm(l.price) }}</td><td class="num">{{ fm(l.qty * l.price) }}</td><td>{{ l.due || po.due }}</td></tr></tbody>
        <tfoot><tr><td colspan="8">合计（大写金额以合同为准）</td><td class="num">{{ fq(poQty(po)) }}</td><td></td><td class="num">{{ fm(poAmt(po)) }}</td><td></td></tr></tfoot></table>
      <p class="sheet-note">备注：{{ po.note || '—' }}<br>送货单请写明采购单号；面料每卷标缸号、米数；有色差、短码请先联系采购员。</p>
      <div class="sheet-sign"><span>制单：{{ creatorOf(po) || '' }}</span><span>审核：</span><span>供应商确认（签字盖章）：</span></div>
    </PrintSheet>
  </div><NotFound v-else/>`,
}, { mod: 'purchases', t: (p) => '采购单 ' + p.no })

comp('PurchaseReceive', {
  props: { po: Object, mode: String },
  emits: ['close'],
  setup(p, { emit }) {
    const recv = p.mode === 'recv'
    const lines = reactive(p.po.lines.map((l, i) => ({ i, qty: recv ? rnd(Math.max(0, l.qty - l.received), 3) : 0, lot: '', rolls: null, note: '' })))
    const f = reactive({ wh: p.po.wh, from: p.po.wh, date: clock.today, supNo: '', logistics: '', note: '', reason: '', files: [] })
    const whs = computed(() => db.warehouses.filter((w) => w.type !== '成品仓'))
    // 退货从哪个仓出：这张单的料现在在哪些仓有库存（入库时可能改到了工厂仓）
    const fromWhs = computed(() => whs.value.filter((w) => w.code === p.po.wh || p.po.lines.some((l) => stockQty(w.code, l.sku, l.owner) > 0)))
    const have = (x) => { const l = p.po.lines[x.i]; return rnd(stockQty(f.from, l.sku, l.owner) / matOfSku(l.sku).factor, 3) }
    function ok() {
      const ls = lines.map((x) => ({ ...x }))
      const d = run(() => (recv ? receivePurchase(p.po, { lines: ls, wh: f.wh, date: f.date, supNo: f.supNo, logistics: f.logistics, note: f.note, files: f.files }) : returnPurchase(p.po, { lines: ls, from: f.from, date: f.date, logistics: f.logistics, reason: f.reason, files: f.files })))
      if (d?.no) { emit('close'); toast(`${d.no} 已过账，库存已更新`, ['打开', '/stock-docs/' + d.no]) }
    }
    return { recv, lines, f, whs, fromWhs, have, ok, purUnit }
  },
  template: `<Modal :t="(recv ? '到货入库 ' : '退货 ') + po.no" :w="820" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(3,1fr);margin-bottom:12px">
      <label v-if="recv" class="f">入哪个仓<select class="sel" v-model="f.wh"><option v-for="w in whs" :key="w.code" :value="w.code">{{ w.name }}{{ w.type === '工厂仓' ? '（面料直发工厂）' : '' }}</option></select></label>
      <label v-else class="f">从哪个仓退<select class="sel" v-model="f.from"><option v-for="w in fromWhs" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label class="f">{{ recv ? '到货日期' : '退货日期' }}<input class="inp" type="date" v-model="f.date" :max="clock.today"><small class="dim">第二天补录就填实际那天</small></label>
      <label class="f">物流单号<input class="inp" v-model="f.logistics"></label>
      <label v-if="recv" class="f">供应商送货单号<input class="inp" v-model="f.supNo"></label>
      <label v-if="recv" class="f" style="grid-column:span 2">备注<input class="inp" v-model="f.note"></label>
      <label v-else class="f full">退货原因<input class="inp" v-model="f.reason" placeholder="比如：色差、短码、布面瑕疵"></label>
    </div>
    <table class="tbl inset flat" data-c="入库数量"><thead><tr><th>物料</th><th class="num">{{ recv ? '未入库' : '可退' }}</th><th v-if="!recv" class="num">出仓库存</th><th class="num" style="width:110px">本次</th><th v-if="recv" style="width:110px">缸号</th><th v-if="recv" class="num" style="width:76px">卷数</th><th style="width:150px">备注</th></tr></thead>
      <tbody><tr v-for="x in lines" :key="x.i"><td><span class="row"><Thumb :code="po.lines[x.i].sku" :s="30"/><span class="col"><span>{{ skuName(po.lines[x.i].sku) }}</span><small class="code dim">{{ po.lines[x.i].sku }}</small></span></span></td>
        <td class="num">{{ fq(recv ? Math.max(0, po.lines[x.i].qty - po.lines[x.i].received) : po.lines[x.i].received - po.lines[x.i].returned) }} <span class="dim">{{ purUnit(po.lines[x.i].sku) }}</span></td>
        <td v-if="!recv" class="num dim">{{ fq(have(x)) }}</td>
        <td><input class="inp cell num" type="number" v-model.number="x.qty"></td><td v-if="recv"><input class="inp cell" v-model="x.lot"></td><td v-if="recv"><input class="inp cell num" type="number" v-model.number="x.rolls"></td><td><input class="inp cell" v-model="x.note"></td></tr></tbody></table>
    <div class="sec" data-c="到货照片"><h3>{{ recv ? '送货单、到货照片' : '退货问题照片' }}<span class="dim" style="font-weight:400;font-size:12px">　选填，挂在{{ recv ? '入库单' : '退货单' }}的附件里</span></h3><Pics v-model="f.files" files :s="64" :tip="recv ? '拍送货单、外箱、布头标签；拖进来也行' : '拍问题部位、退货包装'"/></div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ recv ? '入库并过账' : '退货并过账' }}</button></template>
  </Modal>`,
})

/* ---------- 到料跟踪 ---------- */
route('/arrivals', {
  setup() {
    const s = reactive({ tab: nav.query.f || 'all', q: '', buyer: '', flt: newFlt() })
    const all = computed(() => arrivals().filter((x) => (!s.buyer || x.p.buyer === s.buyer) && passFlt(s.flt, { date: x.date, party: x.p.supplier })
      && matchQ(s.q, x.p.no, x.l.sku, skuName(x.l.sku), supName(x.p.supplier), srcText(x.l), x.l.src?.mrp || '')).sort((a, b) => a.left - b.left))
    // 赶不上开裁（PRD §6.6.3）：预计到货晚于要料日期（加工单裁剪的计划开始），采购交期没过也标出来
    const tabs = computed(() => [['all', '全部', all.value.length], ['late', '已逾期', all.value.filter((x) => x.risk === 'late').length], ['near', '3 天内', all.value.filter((x) => x.risk === 'near').length], ['cut', '赶不上开裁', all.value.filter((x) => x.cutLate > 0).length]])
    const rows = computed(() => all.value.filter((x) => s.tab === 'all' || (s.tab === 'cut' ? x.cutLate > 0 : x.risk === s.tab)))
    return { s, tabs, rows, srcText, purUnit }
  },
  template: `<div>
    <Head t="到料"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><Filters v-model="s.flt" party="supplier" label="交期"/>
      <select class="sel" style="height:28px" v-model="s.buyer"><option value="">全部采购员</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select>
      <span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="到料列表">
      <Tbl :rows="rows" empty="没有该到没到的料。订单号、款号、采购单号、物料都能搜" :cols="[
        { k: 'po', t: '采购单', c: 'code' }, { k: 'sup', t: '供应商', f: x => supName(x.p.supplier) }, { k: 'sku', t: '物料' },
        { k: 'src', t: '订单 / 款', c: 'code', f: x => srcText(x.l) }, { k: 'qty', t: '采购', c: 'num', f: x => fq(x.l.qty) },
        { k: 'recv', t: '已到', c: 'num', f: x => fq(x.l.received) }, { k: 'left', t: '未到', c: 'num', f: x => fq(rnd(x.l.qty - x.l.received, 3)) + ' ' + purUnit(x.l.sku) },
        { k: 'due', t: '交期', f: x => fd(x.l.due || x.p.due) }, { k: 'exp', t: '预计到货' }, { k: 'days', t: '' }, { k: 'need', t: '要料日期' }, { k: 'note', t: '跟进', w: '220px' }]">
        <template #need="{ r }"><template v-if="r.need">{{ fd(r.need) }}<div v-if="r.cutLate" class="flag red" style="display:flex" title="预计到货（没填用交期）晚于加工单裁剪的计划开始">赶不上开裁，晚 {{ r.cutLate }} 天</div></template><span v-else class="dim" title="没被算料用上的备货采购没有要料日期">—</span></template>
        <template #po="{ r }"><a :href="'#/purchases/' + r.p.no">{{ r.p.no }}</a><div class="dim" style="font-size:12px">{{ userName(r.p.buyer) }}</div></template>
        <template #sku="{ r }"><span class="row"><Thumb :code="r.l.sku" :s="32"/><span class="col"><span>{{ skuName(r.l.sku) }}</span><small class="code dim">{{ r.l.sku }}</small></span></span></template>
        <template #exp="{ r }"><input class="inp cell ghost" type="date" style="width:130px" v-model="r.l.expect"></template>
        <template #days="{ r }"><Days :d="r.date"/></template>
        <template #note="{ r }"><input class="inp cell ghost" v-model="r.l.note"></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'arrivals', t: '到料' })

/* ---------- 出入库单 ---------- */
const DOC_GROUPS = { 入库: ['采购入库', ...(TRADE ? ['客供入库'] : []), '其他入库'], 领料: ['领料', '退料'], 调拨: ['调拨'], 出库: ['采购退货', '其他出库'] }
const srcLink = (d) => (!d.src ? '' : d.src.startsWith('CG') ? '/purchases/' + d.src : d.src.startsWith('JG') ? '/production/' + d.src : '')
const partnerName = (d) => (!d.partner ? '—' : d.partner.startsWith('KH') ? custName(d.partner) : supName(d.partner))
const docQty = (d) => rnd(sum(d.lines, (l) => l.qty), 3)
const docAmt = (d) => rnd(sum(d.lines, (l) => l.qty * (l.price || 0)), 2)
// 单价按库存单位；采购入库、采购退货的行带的是采购单价（按采购单位），折成库存单位算金额
const linePrice = (l) => (l.price == null ? null : l.purQty != null ? rnd(l.price / (matOfSku(l.sku)?.factor || 1), 4) : l.price)
const lineAmt = (l) => (l.price == null ? null : l.purQty != null ? rnd(l.purQty * l.price, 2) : rnd(l.qty * l.price, 2))
const SD_FIELDS = [['company', '主体', companyName], ['date', '日期', fd], ['partner', '往来', (v) => partnerName({ partner: v })], ['from', '出仓', whName], ['to', '入仓', whName],
  ['src', '源单'], ['reason', '原因'], ['supNo', '送货单号'], ['logistics', '物流单号'], ['note', '备注']]
const sdLineSig = (l) => [l.sku, l.owner || '自有', l.qty, l.price ?? '', l.lot || '', l.rolls ?? '', l.note || ''].join('|')
// 手工建的单能改；采购入库、采购退货、领料是源单生成的（数量按采购行、加工单回写），改错了作废，回源单重做
const SD_EDIT = ['客供入库', '其他入库', '调拨', '退料', '其他出库']
function updateStockDoc(d, f) {
  if (d.status !== '草稿') fail('只有草稿能改；已审核的先反审核')
  if (!SD_EDIT.includes(d.type)) fail(`${d.type}是从 ${d.src || '源单'} 生成的，不能直接改：作废这张，回源单重新做`)
  const lines = checkStockDoc({ ...f, type: d.type })
  const ch = applyFields(d, f, SD_FIELDS)
  if (d.lines.map(sdLineSig).join(';') !== lines.map(sdLineSig).join(';')) {
    ch.push(d.lines.length === lines.length ? '明细：' + lines.filter((l, i) => sdLineSig(l) !== sdLineSig(d.lines[i])).map((l) => skuName(l.sku)).join('、') : `明细 ${d.lines.length} 行 → ${lines.length} 行`)
    const owner = d.type === '客供入库' ? d.partner : null
    d.lines = lines.map((l) => ({ owner: owner || '自有', price: null, lot: '', ...l, ...(owner ? { owner } : {}) }))
  }
  if (!ch.length) fail('没有改动')
  act(d, '改：' + ch.join('；'))
}

route('/stock-docs', {
  setup() {
    const s = reactive({ mine: false, tab: '全部', st: '', wh: '', party: '', q: '', add: '', flt: newFlt() })
    const scoped = computed(() => db.stockDocs.filter((d) => inScope(s.mine, d)))
    const tabs = computed(() => [['全部', '全部', scoped.value.length], ...Object.entries(DOC_GROUPS).map(([k, ts]) => [k, k, scoped.value.filter((d) => ts.includes(d.type)).length])])
    const parties = computed(() => [...new Set(db.stockDocs.map((d) => d.partner).filter(Boolean))].map((c) => [c, partnerName({ partner: c })]))
    const rows = computed(() => scoped.value.filter((d) => (s.tab === '全部' || DOC_GROUPS[s.tab].includes(d.type)) && (!s.st || d.status === s.st) && (!s.wh || d.from === s.wh || d.to === s.wh) && (!s.party || d.partner === s.party)
      && passFlt(s.flt, { date: d.date }) && matchQ(s.q, d.no, d.type, d.src, d.supNo || '', d.logistics || '', ...d.lines.flatMap((l) => [l.sku, skuName(l.sku), l.lot || '']))).slice().reverse())
    // 手工新建的类型，第一个放在页头按钮上（TRADE 关着时没有客供入库）
    const NEW_TYPES = [...(TRADE ? ['客供入库'] : []), '其他入库', '调拨', '退料', '其他出库']
    const news = NEW_TYPES.map((t) => ({ t, fn: () => { s.add = t } }))
    return { s, tabs, parties, rows, news, NEW_TYPES, partnerName }
  },
  template: `<div>
    <Head t="出入库"><button class="btn pri" @click="s.add = NEW_TYPES[0]"><Ico n="plus" :s="14"/>{{ NEW_TYPES[0] }}</button><More :items="news"/></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/>
      <select class="sel" style="height:28px" v-model="s.st"><option value="">全部状态</option><option v-for="x in ['草稿', '已审核', '已作废']" :key="x">{{ x }}</option></select>
      <Filters v-model="s.flt"/>
      <select class="sel" style="height:28px;max-width:140px" v-model="s.wh"><option value="">全部仓库</option><option v-for="w in db.warehouses.filter(w => w.type !== '成品仓')" :key="w.code" :value="w.code">{{ w.name }}</option></select>
      <select class="sel" style="height:28px;max-width:140px" v-model="s.party"><option value="">全部往来</option><option v-for="x in parties" :key="x[0]" :value="x[0]">{{ x[1] }}</option></select>
      <span class="sp"></span><MineToggle v-model="s.mine"/><Search v-model="s.q"/></div>
    <div data-c="出入库列表">
      <Tbl :rows="rows" link @row="d => go('/stock-docs/' + d.no)" empty="没有符合条件的单据。单号、源单、物料、缸号都能搜" :cols="[
        { k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'flow', t: '出仓 → 入仓' }, { k: 'partner', t: '往来', f: partnerName },
        { k: 'src', t: '源单', c: 'code', f: d => d.src || '—' }, { k: 'mat', t: '物料', f: d => skuName(d.lines[0].sku) + (d.lines.length > 1 ? ' 等 ' + d.lines.length + ' 种' : ''), img: d => d.lines[0].sku },
        { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'status', t: '状态' }]">
        <template #flow="{ r }"><span class="mut">{{ r.from ? whName(r.from) : '' }}<template v-if="r.from && r.to"> → </template>{{ r.to ? whName(r.to) : '' }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <StockDocForm v-if="s.add" :type="s.add" @close="s.add = ''"/>
  </div>`,
}, { mod: 'stockdocs', t: '出入库' })

// 新建、改草稿共用：d 不给就是新建；init 预填（加工单详情点「退料」时带上源单、出仓）
comp('StockDocForm', {
  props: { type: String, d: Object, init: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const x = p.d, t = x ? x.type : p.type
    const blank = () => ({ sku: '', owner: '自有', qty: null, price: null, lot: '', rolls: null, note: '' })
    const f = reactive(x
      ? { ...Object.fromEntries(SD_FIELDS.map(([k]) => [k, x[k] ?? ''])), from: x.from, to: x.to, lines: x.lines.map((l) => ({ ...blank(), ...l })) }
      : { company: myCompany(), date: clock.today, partner: t === '客供入库' ? 'KH0001' : '', from: ['调拨', '其他出库'].includes(t) ? 'WL01' : t === '退料' ? 'GC0004' : null, to: ['客供入库', '其他入库', '退料'].includes(t) ? 'WL01' : t === '调拨' ? 'GC0005' : null,
        src: '', reason: '', supNo: '', logistics: '', note: '', lines: [blank()], ...(p.init || {}) })
    if (!f.lines.length) f.lines.push(blank())
    const whsFrom = computed(() => db.warehouses.filter((w) => (t === '退料' ? w.type === '工厂仓' : w.type !== '成品仓') && (w.status !== '停用' || w.code === f.from)))
    const whsTo = computed(() => db.warehouses.filter((w) => (t === '退料' ? w.type === '物料仓' : w.type !== '成品仓') && (w.status !== '停用' || w.code === f.to)))
    // 退料的源单：这个工厂仓所属加工厂的加工单（可选，选了才扣得掉加工单的"已领"）
    const prods = computed(() => (t === '退料' ? db.productions.filter((pr) => factoryWh(pr.factory)?.code === f.from && !['草稿', '待审核', '已作废'].includes(pr.status)) : []))
    const skus = computed(() => db.materials.flatMap((m) => m.skus.filter((k) => k.status !== '停用' || f.lines.some((l) => l.sku === k.code)).map((k) => ({ code: k.code, t: skuName(k.code) + ' · ' + k.code }))))
    const owners = computed(() => ['自有', ...db.customers.map((c) => c.code)])
    const have = (l) => (f.from && l.sku ? stockQty(f.from, l.sku, l.owner) : null)
    // 选了加工单：往来记加工厂，明细带出这张单领过的料（数量自己填）
    function pickProd() {
      const pr = production(f.src)
      if (!pr) return
      f.partner = pr.factory
      const got = issuePlan(pr).filter((r) => r.issued > 0)
      if (got.length && !f.lines.some((l) => l.sku)) f.lines.splice(0, f.lines.length, ...got.map((r) => ({ ...blank(), sku: r.sku, owner: r.owner })))
    }
    const amt = computed(() => sum(f.lines, (l) => (+l.qty || 0) * (+l.price || 0)))
    function ok() {
      const g = { ...f, type: t, lines: f.lines.map((l) => ({ ...l })) }
      if (x) { if (run(() => updateStockDoc(x, g))) { emit('close'); toast('已保存') } return }
      const d = run(() => createStockDoc(g))
      if (d?.no) { emit('close'); go('/stock-docs/' + d.no); toast(`${d.no} 存了草稿，审核后才改库存`) }
    }
    return { x, t, f, whsFrom, whsTo, prods, skus, owners, have, pickProd, amt, ok }
  },
  template: `<Modal :t="x ? '改' + t + ' ' + x.no : '新建' + t" :w="900" @close="$emit('close')">
    <div class="form" style="grid-template-columns:repeat(4,1fr)" data-c="出入库单单头">
      <label class="f">主体公司<CompanyPick v-model="f.company"/></label>
      <label class="f">日期<input class="inp" type="date" v-model="f.date" :max="clock.today"></label>
      <label v-if="t === '客供入库'" class="f">客户<select class="sel" v-model="f.partner"><option v-for="c in db.customers" :key="c.code" :value="c.code">{{ c.short }}</option></select></label>
      <label v-if="f.from !== null" class="f">出仓<select class="sel" v-model="f.from"><option v-for="w in whsFrom" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label v-if="f.to !== null" class="f">入仓<select class="sel" v-model="f.to"><option v-for="w in whsTo" :key="w.code" :value="w.code">{{ w.name }}</option></select></label>
      <label v-if="t === '退料'" class="f">源单（加工单，选填）<select class="sel" v-model="f.src" @change="pickProd"><option value="">不挂加工单</option><option v-for="pr in prods" :key="pr.no" :value="pr.no">{{ pr.no }} {{ pr.style }}</option></select></label>
      <label v-if="['其他入库', '其他出库'].includes(t)" class="f">原因<select class="sel" v-model="f.reason"><option value="">选原因（必填）</option><option v-for="v in db.dicts.其他出入库原因" :key="v">{{ v }}</option></select></label>
      <label v-if="['调拨', '退料'].includes(t)" class="f">原因<input class="inp" v-model="f.reason" :placeholder="t === '退料' ? '比如：大货做完剩料' : ''"></label>
      <label v-if="t === '客供入库'" class="f">送货单号<input class="inp" v-model="f.supNo"></label>
      <label class="f">物流单号<input class="inp" v-model="f.logistics"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <p v-if="t === '退料'" class="dim" style="font-size:12px;margin-top:8px">退料：工厂剩料从工厂仓退回物料仓。挂上加工单，那张单的"已领"会扣掉这次退的。</p>
    <table class="tbl inset flat" style="margin-top:14px" data-c="出入库明细"><thead><tr><th>物料</th><th v-if="TRADE && t !== '客供入库'" style="width:120px">物权</th><th v-if="f.from" class="num">库存</th><th class="num" style="width:90px">数量</th><th class="num" style="width:80px">单价</th><th class="num" style="width:84px">金额</th><th style="width:96px">缸号</th><th class="num" style="width:64px">卷数</th><th style="width:120px">备注</th><th style="width:32px"></th></tr></thead>
      <tbody><tr v-for="(l, i) in f.lines" :key="i">
        <td><select class="sel" style="width:100%;height:28px" v-model="l.sku"><option value="">选物料</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ k.t }}</option></select></td>
        <td v-if="TRADE && t !== '客供入库'"><select class="sel" style="width:100%;height:28px" v-model="l.owner"><option v-for="o in owners" :key="o" :value="o">{{ ownerName(o) }}</option></select></td>
        <td v-if="f.from" class="num dim">{{ have(l) == null ? '—' : fq(have(l)) }}</td>
        <td><input class="inp cell num" type="number" v-model.number="l.qty" :placeholder="l.sku ? unitOf(l.sku) : ''"></td>
        <td><input class="inp cell num" type="number" v-model.number="l.price" title="元 / 库存单位"></td>
        <td class="num">{{ l.qty && l.price ? fm(l.qty * l.price) : '—' }}</td>
        <td><input class="inp cell" v-model="l.lot"></td><td><input class="inp cell num" type="number" v-model.number="l.rolls"></td><td><input class="inp cell" v-model="l.note"></td>
        <td><button class="btn ghost icon sm" @click="f.lines.splice(i, 1)"><Ico n="x" :s="12"/></button></td></tr></tbody>
      <tfoot><tr><td>合计</td><td v-if="TRADE && t !== '客供入库'"></td><td v-if="f.from"></td><td class="num">{{ fq(sum(f.lines, l => l.qty)) }}</td><td></td><td class="num">{{ fm(amt) }}</td><td></td><td class="num">{{ fq(sum(f.lines, l => l.rolls)) }}</td><td colspan="2"></td></tr></tfoot></table>
    <button class="btn ghost" style="margin-top:8px" @click="f.lines.push({ sku: '', owner: '自有', qty: null, price: null, lot: '', rolls: null, note: '' })"><Ico n="plus" :s="14"/>加一行</button>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ x ? '保存' : '存草稿' }}</button></template>
  </Modal>`,
})

route('/stock-docs/:no', {
  props: ['no'],
  setup(p) {
    const d = computed(() => stockDoc(p.no))
    const dlg = ref('')
    const a = (x) => docAct('stock', d.value, x, x === '审核' ? `${d.value.no} 已过账，库存已更新` : '')
    async function del() { if (await ask('删除 ' + d.value.no, '草稿删掉就找不回来了，库存不受影响（草稿还没过账）。', '删除', true)) { if (run(() => removeDraft(db.stockDocs, d.value))) { go('/stock-docs'); toast('删掉了') } } }
    const more = computed(() => [can('stock', d.value, '反审核') && { t: '反审核（库存改回去）', fn: () => a('反审核') },
      d.value.status === '草稿' && { t: '删除', danger: true, fn: del }, can('stock', d.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') }].filter(Boolean))
    // 采购入库、采购退货：数量按采购单位记的，金额按采购单价 × 采购数量
    const noteOf = (l) => [l.reason && '超领：' + l.reason, l.note].filter(Boolean).join('；') || '—'
    return { d, dlg, a, more, srcLink, partnerName, docQty, docAmt, linePrice, lineAmt, noteOf, SD_EDIT }
  },
  template: `<div v-if="d">
    <Head :t="d.no" :back="['出入库', '/stock-docs']"><button v-if="d.status === '草稿' && SD_EDIT.includes(d.type)" class="btn" @click="dlg = 'edit'">编辑</button><button v-if="can('stock', d, '审核')" class="btn pri" @click="a('审核')">审核过账</button><More :items="more"/></Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ d.type }}<St :s="d.status"/></div>
        <div class="sub"><span v-if="d.from || d.to">{{ d.from ? whName(d.from) : partnerName(d) }} → {{ d.to ? whName(d.to) : partnerName(d) }}</span><span>{{ fd(d.date) }}</span><span>{{ d.lines.length }} 种 · 金额 {{ fm(docAmt(d)) }}</span></div>
        <section data-c="出入库明细">
          <Tbl inset flat :rows="d.lines" :cols="[{ k: 'sku', t: '物料', f: l => skuName(l.sku), img: l => l.sku }, { k: 'code', t: 'SKU', c: 'code', f: l => l.sku }, ...(TRADE ? [{ k: 'owner', t: '物权', f: l => ownerName(l.owner) }] : []),
            { k: 'qty', t: '数量', c: 'num', f: l => fq(l.qty) + ' ' + unitOf(l.sku) }, { k: 'pq', t: '采购单位', c: 'num', f: l => l.purQty != null && matOfSku(l.sku).factor !== 1 ? fq(l.purQty) + ' ' + matOfSku(l.sku).purUnit : '' },
            { k: 'price', t: '单价', c: 'num', f: l => linePrice(l) == null ? '—' : fm(linePrice(l)) }, { k: 'amt', t: '金额', c: 'num', f: l => lineAmt(l) == null ? '—' : fm(lineAmt(l)) },
            { k: 'lot', t: '缸号', f: l => l.lot || '—' }, { k: 'rolls', t: '卷数', c: 'num', f: l => l.rolls || '—' }, { k: 'note', t: '备注', f: noteOf },
            { k: 'have', t: '出仓库存', c: 'num', f: l => d.from && d.status === '草稿' ? fq(stockQty(d.from, l.sku, l.owner)) : '' }]">
            <template #foot><tr><td>合计</td><td></td><td v-if="TRADE"></td><td class="num">{{ fq(docQty(d)) }}</td><td></td><td></td><td class="num">{{ fm(docAmt(d)) }}</td><td></td><td class="num">{{ sum(d.lines, l => l.rolls) || '—' }}</td><td></td><td></td></tr></template>
          </Tbl>
          <p v-if="d.lines.some(l => l.purQty != null)" class="dim" style="font-size:12px;margin-top:8px">数量是库存单位；采购入库、退货的单价是采购单价折成库存单位，金额 = 采购数量 × 采购单价。</p>
        </section>
      </div>
      <aside class="aside" data-c="单据属性">
        <dl class="props">
          <dt>类型</dt><dd>{{ d.type }}</dd>
          <dt>日期</dt><dd>{{ fd(d.date) }}</dd>
          <dt v-if="d.from">出仓</dt><dd v-if="d.from">{{ whName(d.from) }}</dd>
          <dt v-if="d.to">入仓</dt><dd v-if="d.to">{{ whName(d.to) }}</dd>
          <dt v-if="d.partner">往来</dt><dd v-if="d.partner">{{ partnerName(d) }}</dd>
          <dt v-if="d.src">源单</dt><dd v-if="d.src"><a class="code" :href="'#' + srcLink(d)">{{ d.src }}</a></dd>
          <dt v-if="d.supNo">送货单号</dt><dd v-if="d.supNo" class="code">{{ d.supNo }}</dd>
          <dt>物流单号</dt><dd class="code">{{ d.logistics || '—' }}</dd>
          <dt v-if="d.reason">原因</dt><dd v-if="d.reason">{{ d.reason }}</dd>
          <DocMeta :d="d"/>
        </dl>
        <DocFiles :doc="d"/>
        <h4>操作记录</h4><Log :items="d.log"/>
      </aside>
    </div>
    <StockDocForm v-if="dlg === 'edit'" :d="d" @close="dlg = ''"/>
  </div><NotFound v-else/>`,
}, { mod: 'stockdocs', t: (p) => '出入库单 ' + p.no })

/* ---------- 库存与日志 ---------- */
// 期初库存导入（PRD §10）：同一个仓库的行合成一张「其他入库（期初）」直接过账
IMPORTS.stock = {
  title: '导入期初库存', name: '期初库存',
  note: `上线时把各仓现有的料导进来。一行一个 SKU + 仓库${TRADE ? ' + 物权' : ''}；同一个仓库的行合成一张「其他入库（原因：期初）」并直接过账。`,
  head: [{ k: 'date', t: '期初日期', type: 'date', def: () => clock.today }],
  cols: [
    { k: 'sku', t: '物料SKU编码', req: true, text: true, tip: '要是物料里已有的 SKU 编码', ex: 'ML0012-KQ', w: 16 },
    { k: 'wh', t: '仓库编码', req: true, text: true, tip: '物料仓或工厂仓的编码', ex: 'WL01', opts: () => nonFgWhs().map((w) => w.code) },
    ...(TRADE ? [{ k: 'owner', t: '物权', text: true, tip: '不填 = 自有；客供料填客户编码', ex: '自有' }] : []), // TRADE 关着时全是自有
    { k: 'qty', t: '数量', req: true, num: true, tip: '库存单位（面料一般是米），最多 3 位小数', ex: 120 },
    { k: 'price', t: '单价', num: true, tip: '元 / 库存单位，以后算成本用；不填 = 物料 SKU 单价（没有就用参考单价）折成库存单位', ex: 18.5 },
    { k: 'lot', t: '缸号', text: true, tip: '选填', ex: 'G2610-07' },
  ],
  example: () => [{ sku: 'ML0012-KQ', wh: 'WL01', owner: '自有', qty: 120, price: 18.5, lot: 'G2610-07' }, { sku: 'ML0015-HE', wh: 'WL01', owner: ifTrade('KH0001', '自有'), qty: 200, price: '', lot: '' }, { sku: 'FK0021-HE', wh: 'GC0004', owner: '自有', qty: 800, price: 0.35, lot: '' }],
  check(rows, bad, head) {
    if (!head.date) bad('—', '期初日期', '要选期初日期')
    else if (head.date > clock.today) bad('—', '期初日期', '不能晚于今天')
    const seen = new Map(), groups = new Map()
    for (const r of rows) {
      if (!skuOf(r.sku)) { bad(r, '物料SKU编码', `没有 SKU ${r.sku}，先在物料里建好`); continue }
      const owner = !r.owner || r.owner === '自有' ? '自有' : r.owner
      if (owner !== '自有' && !cust(owner)) { bad(r, '物权', `「${r.owner}」不是客户编码；自有的料不填或填「自有」`); continue }
      if (!(r.qty > 0)) { bad(r, '数量', '要大于 0'); continue }
      const key = [r.sku, r.wh, owner, r.lot].join('|')
      if (seen.has(key)) { bad(r, '物料SKU编码', `和第 ${seen.get(key)} 行重复（同一个仓库、${TRADE ? '物权、' : ''}缸号），合成一行再导`); continue }
      seen.set(key, r._r)
      if (!groups.has(r.wh)) groups.set(r.wh, [])
      const ref = skuPrice(r.sku)
      groups.get(r.wh).push({ sku: r.sku, owner, qty: r.qty, price: r.price ?? (ref == null ? null : rnd(ref / matOfSku(r.sku).factor, 4)), lot: r.lot })
    }
    return [...groups]
  },
  save(groups, head) {
    const docs = groups.map(([w, lines]) => {
      const d = createStockDoc({ type: '其他入库', reason: '期初', to: w, date: head.date, note: 'Excel 导入期初库存', lines })
      act(d, `Excel 导入 ${lines.length} 行`)
      postStockDoc(d)
      return d
    })
    return { text: `建了 ${docs.length} 张期初入库单并过账：${docs.map((d) => d.no).join('、')}`, to: docs.length === 1 ? '/stock-docs/' + docs[0].no : '/stock-docs' }
  },
}

route('/stock', {
  setup() {
    const s = reactive({ tab: nav.query.wh ? '全部' : '物料仓', wh: nav.query.wh || '', view: '明细', owner: '', q: '', log: null, type: '', imp: false, flt: newFlt() })
    const whs = computed(() => db.warehouses.filter((w) => w.type !== '成品仓' && (s.tab === '全部' || w.type === s.tab)))
    watch(() => s.tab, () => { if (s.wh && !whs.value.some((w) => w.code === s.wh)) s.wh = '' })
    const rows = computed(() => Object.entries(db.stock).filter(([, q]) => Math.abs(q) > 0.0005).map(([k, qty]) => { const [w, sku, owner] = k.split('|'); return { k, w, sku, owner, qty, last: db.stockLogs.find((x) => x.wh === w && x.sku === sku && x.owner === owner)?.at } })
      .filter((r) => (s.tab === '全部' || wh(r.w).type === s.tab) && (!s.wh || r.w === s.wh) && (!s.owner || r.owner === s.owner) && matchQ(s.q, r.sku, skuName(r.sku), whName(r.w)))
      .sort((a, b) => a.sku.localeCompare(b.sku)))
    const summary = computed(() => {
      const m = {}
      for (const r of rows.value) { const x = (m[r.sku + '|' + r.owner] ||= { sku: r.sku, owner: r.owner, mat: 0, fac: 0 }); if (wh(r.w).type === '工厂仓') x.fac += r.qty; else x.mat += r.qty }
      return Object.values(m)
    })
    const owners = computed(() => [...new Set(Object.keys(db.stock).map((k) => k.split('|')[2]))])
    // 日志：点一行看那一格的流水；日志视图按日期、类型、仓库筛，关键字搜单号、物料、缸号（PRD §6.6.5 按缸号查流水）
    const logs = computed(() => (s.log ? db.stockLogs.filter((x) => x.wh === s.log.w && x.sku === s.log.sku && x.owner === s.log.owner)
      : db.stockLogs.filter((x) => (!s.wh || x.wh === s.wh) && (!s.type || x.type === s.type) && (!s.owner || x.owner === s.owner) && passFlt(s.flt, { date: x.at }) && matchQ(s.q, x.no, x.sku, skuName(x.sku), x.lot))))
    const safety = (sku) => skuOf(sku)?.safety
    const unitTxt = (r) => unitOf(r.sku)
    const XC = {
      明细: [{ k: 'w', t: '仓库', f: (r) => whName(r.w) }, { k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: (r) => skuParts(r.sku).name }, { k: 'color', t: '颜色', f: (r) => skuParts(r.sku).color }, { k: 'spec', t: '规格', f: (r) => skuParts(r.sku).spec },
        ...(TRADE ? [{ k: 'owner', t: '物权', f: (r) => ownerName(r.owner) }] : []), { k: 'qty', t: '数量', f: (r) => rnd(r.qty, 3) }, { k: 'unit', t: '单位', f: unitTxt }, { k: 'safety', t: '安全库存', f: (r) => safety(r.sku) ?? '' }, { k: 'last', t: '最近变动', f: (r) => r.last || '' }],
      按物料: [{ k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: (r) => skuName(r.sku) }, ...(TRADE ? [{ k: 'owner', t: '物权', f: (r) => ownerName(r.owner) }] : []), { k: 'mat', t: '物料仓', f: (r) => rnd(r.mat, 3) }, { k: 'fac', t: '工厂仓', f: (r) => rnd(r.fac, 3) }, { k: 'all', t: '合计', f: (r) => rnd(r.mat + r.fac, 3) }, { k: 'unit', t: '单位', f: unitTxt }],
      日志: [{ k: 'at', t: '日期' }, { k: 'type', t: '类型' }, { k: 'no', t: '单号', c: 'code' }, { k: 'wh', t: '仓库', f: (x) => whName(x.wh) }, { k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: (x) => skuName(x.sku) },
        ...(TRADE ? [{ k: 'owner', t: '物权', f: (x) => ownerName(x.owner) }] : []), { k: 'delta', t: '变动' }, { k: 'bal', t: '结存' }, { k: 'lot', t: '缸号' }, { k: 'who', t: '操作人', f: (x) => userName(x.who) }],
    }
    const xrows = computed(() => (s.view === '明细' ? rows.value : s.view === '按物料' ? summary.value : logs.value))
    return { s, whs, rows, summary, owners, logs, safety, XC, xrows, STOCK_TYPES }
  },
  template: `<div>
    <Head t="库存"><a v-if="reorderRows().length" class="btn" href="#/reorder" title="物料仓库存 + 在途 − 已被占用 低于安全库存">补货提醒<span class="amber" style="margin-left:4px">{{ reorderRows().length }}</span></a><button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入期初库存</button><ExportBtn :rows="xrows" :cols="XC[s.view]" :name="s.view === '日志' ? '库存日志' : '库存'"/></Head>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.tab" :items="['物料仓', '工厂仓', '全部']"/>
      <select class="sel" style="height:28px;max-width:150px" v-model="s.wh" data-c="选仓库"><option value="">全部仓库</option><option v-for="w in whs" :key="w.code" :value="w.code">{{ w.name }}</option></select>
      <select v-if="TRADE" class="sel" style="height:28px" v-model="s.owner"><option value="">全部物权</option><option v-for="o in owners" :key="o" :value="o">{{ ownerName(o) }}</option></select>
      <span class="sp"></span><Seg v-model="s.view" :items="['明细', '按物料', '日志']"/><Search v-model="s.q"/>
    </div>
    <div v-if="s.view === '日志'" class="bar" data-c="日志筛选"><Filters v-model="s.flt"/>
      <select class="sel" style="height:28px" v-model="s.type"><option value="">全部单据类型</option><option v-for="(v, t) in STOCK_TYPES" :key="t" :value="t">{{ t }}</option></select>
      <span class="dim" style="font-size:12px">关键字能搜单号、物料、缸号</span></div>
    <div v-if="s.view === '明细'" data-c="库存明细">
      <Tbl :rows="rows" link @row="r => s.log = r" empty="没有库存。SKU、物料名、仓库都能搜，几个编码用空格隔开" :cols="[
        { k: 'w', t: '仓库', f: r => whName(r.w) }, { k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: r => skuName(r.sku), img: r => r.sku },
        ...(TRADE ? [{ k: 'owner', t: '物权' }] : []), { k: 'qty', t: '数量', c: 'num' }, { k: 'safety', t: '安全库存', c: 'num', f: r => safety(r.sku) ? fq(safety(r.sku)) : '—' }, { k: 'last', t: '最近变动', f: r => fd(r.last) }]">
        <template #owner="{ r }"><span :class="{ amber: r.owner !== '自有' }">{{ ownerName(r.owner) }}</span></template>
        <template #qty="{ r }"><span :class="{ amber: safety(r.sku) && wh(r.w).type === '物料仓' && belowSafety(r.sku) }" :title="safety(r.sku) && belowSafety(r.sku) ? '物料仓库存 + 在途 − 已被占用 低于安全库存，去补货提醒生成备货采购单' : null">{{ fq(r.qty) }}</span> <span class="dim">{{ unitOf(r.sku) }}</span></template>
      </Tbl>
    </div>
    <div v-if="s.view === '按物料'" data-c="库存汇总">
      <Tbl :rows="summary" empty="没有库存" :cols="[{ k: 'sku', t: 'SKU', c: 'code' }, { k: 'name', t: '物料', f: r => skuName(r.sku), img: r => r.sku }, ...(TRADE ? [{ k: 'owner', t: '物权', f: r => ownerName(r.owner) }] : []),
        { k: 'mat', t: '物料仓', c: 'num', f: r => fq(r.mat) }, { k: 'fac', t: '工厂仓', c: 'num', f: r => fq(r.fac) }, { k: 'all', t: '合计', c: 'num', f: r => fq(r.mat + r.fac) + ' ' + unitOf(r.sku) }]"/>
    </div>
    <div v-if="s.view === '日志'" data-c="库存日志"><StockLogTable :rows="logs"/></div>
    <Modal v-if="s.log" :t="whName(s.log.w) + ' · ' + skuName(s.log.sku)" :w="860" @close="s.log = null"><StockLogTable :rows="logs" inset/></Modal>
    <ImportBox v-if="s.imp" kind="stock" @close="s.imp = false"/>
  </div>`,
}, { mod: 'stock', t: '库存' })

comp('StockLogTable', {
  props: { rows: Array, inset: Boolean },
  template: `<Tbl :inset="inset" :flat="inset" :rows="rows" :link="!inset" empty="没有变动" :cols="[{ k: 'at', t: '日期', f: x => fd(x.at) }, { k: 'type', t: '类型' }, { k: 'no', t: '单号', c: 'code' },
    { k: 'wh', t: '仓库', f: x => whName(x.wh) }, { k: 'sku', t: '物料', f: x => skuName(x.sku) }, ...(TRADE ? [{ k: 'owner', t: '物权', f: x => ownerName(x.owner) }] : []),
    { k: 'delta', t: '变动', c: 'num' }, { k: 'bal', t: '结存', c: 'num', f: x => fq(x.bal) }, { k: 'lot', t: '缸号', f: x => x.lot || '—' }, { k: 'who', t: '操作人', f: x => userName(x.who) }]" @row="x => go('/stock-docs/' + x.no)">
    <template #no="{ r }"><a :href="'#/stock-docs/' + r.no" @click.stop>{{ r.no }}</a></template>
    <template #delta="{ r }"><span :class="r.delta > 0 ? 'green' : 'red'">{{ r.delta > 0 ? '+' : '' }}{{ fq(r.delta) }}</span></template>
  </Tbl>`,
})

expose({ updateMrpRow, srcText, purUnit, partnerName, skuParts, releaseMrp, mrpRecv, docQty, docAmt })
