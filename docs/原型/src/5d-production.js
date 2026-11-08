/* ============================================================
 * 生产与外发（M08）：加工单（进度节点、领料、异常、回货）、生产看板、手机报工
 * ============================================================ */
SEARCH.push(() => db.productions.map((p) => ({ t: p.no + ' ' + p.style + ' ' + supName(p.factory), sub: '加工单', to: '/production/' + p.no, i: 'factory' })))
const RISK_F = { late: '已逾期', node: '节点延期', near: '7 天内交货', issue: '有异常', upstream: '上游已变更' }
const riskMatch = (p, f) => (f === 'upstream' ? p.upstream && !p.upstream.done : prodRisk(p)[f])
const byRisk = (a, b) => prodRisk(b).score - prodRisk(a).score || expectDue(a).localeCompare(expectDue(b))

route('/production', {
  setup() {
    const s = reactive({ tab: nav.query.s || '全部', f: nav.query.f || '', q: '' })
    const tabs = computed(() => ['全部', '草稿', '待审核', '已下单', '生产中', '部分回货', '已完成'].map((k) => [k, k, k === '全部' ? db.productions.length : db.productions.filter((p) => p.status === k).length]))
    const rows = computed(() => db.productions.filter((p) => (s.tab === '全部' || p.status === s.tab) && (!s.f || riskMatch(p, s.f)) && (!s.q || (p.no + p.style + supName(p.factory) + p.order).toLowerCase().includes(s.q.toLowerCase()))).slice().sort(byRisk))
    return { s, tabs, rows, RISK_F }
  },
  template: `<div>
    <Head t="加工单"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/>
      <select class="sel" style="height:28px" v-model="s.f"><option value="">全部风险</option><option v-for="(t, k) in RISK_F" :key="k" :value="k">{{ t }}</option></select>
      <span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="加工单列表"><ProdTable :rows="rows"/></div>
  </div>`,
}, { mod: 'production', t: '加工单' })

comp('ProdTable', {
  props: { rows: Array },
  template: `<Tbl :rows="rows" link @row="p => go('/production/' + p.no)" empty="没有符合条件的加工单。订单审核后在订单里「排单」生成" :cols="[
    { k: 'no', t: '加工单号', c: 'code' }, { k: 'style', t: '款式' }, { k: 'factory', t: '加工厂', f: p => supName(p.factory) }, { k: 'mode', t: '方式' },
    { k: 'qty', t: '数量', c: 'num', f: p => prodQty(p) }, { k: 'due', t: '交期' }, { k: 'left', t: '' }, { k: 'nodes', t: '节点' },
    { k: 'issue', t: '领料', f: p => prodLive(p) ? issueState(p) : '—' }, { k: 'ret', t: '回货' }, { k: 'status', t: '状态' }]">
    <template #style="{ r }"><span class="row"><Thumb :code="r.style" :s="24"/><span class="code">{{ r.style }}</span></span></template>
    <template #due="{ r }">{{ fd(expectDue(r)) }}<span v-if="r.expect" class="dim" style="text-decoration:line-through;margin-left:4px">{{ fd(r.due) }}</span></template>
    <template #left="{ r }"><Days :d="expectDue(r)" :warn="7" :done="!prodLive(r)"/></template>
    <template #nodes="{ r }"><Nodes :p="r"/></template>
    <template #ret="{ r }"><Prog v-if="prodLive(r) || r.status === '已完成'" :a="prodRet(r)" :b="prodQty(r)"/><span v-else class="dim">—</span></template>
    <template #status="{ r }"><span class="row"><St :s="r.status"/><Ico v-if="prodRisk(r).issue" n="alert" :s="14" class="amber"/><Ico v-if="r.upstream && !r.upstream.done" n="diff" :s="14" class="amber"/></span></template>
  </Tbl>`,
})

/* ---------- 加工单详情 ---------- */
route('/production/:no', {
  props: ['no'],
  setup(p) {
    const x = computed(() => production(p.no))
    const tab = ref('nodes'), dlg = ref(''), node = ref(null), closing = ref(null)
    const docs = computed(() => db.stockDocs.filter((d) => d.src === p.no))
    const rets = computed(() => db.returns.filter((r) => r.prod === p.no))
    const openIssues = computed(() => x.value.issues.filter((i) => i.status !== '已关闭'))
    const qtyMap = computed(() => Object.fromEntries(x.value.lines.map((l) => [l.color + '|' + l.size, l.qty])))
    const sub = (c, z) => { const l = x.value.lines.find((y) => y.color === c && y.size === z); return l && (l.ret || l.def) ? `回 ${l.ret}${l.def ? ' · 次 ' + l.def : ''}` : '' }
    const a = (n) => { if (run(() => doAction('production', x.value, n))) toast(n === '审核' ? `${x.value.no} 已下单，按交期倒排了 ${x.value.nodes.length} 个节点` : `${x.value.no} ${n}了`) }
    const report = (i) => { if (prodLive(x.value)) { node.value = i; dlg.value = 'report' } }
    const more = computed(() => [
      prodLive(x.value) && { t: '上报异常', fn: () => { dlg.value = 'issue' } },
      can('production', x.value, '撤回') && { t: '撤回', fn: () => a('撤回') },
      { t: '打印加工单和资料包', fn: () => toast('原型：这里会出加工单 + 资料包（按颜色展开的 BOM、工艺单），可存 PDF 发工厂') },
      can('production', x.value, '结案') && { t: '结案', fn: () => a('结案') },
      can('production', x.value, '作废') && { t: '作废', danger: true, fn: () => a('作废') },
    ].filter(Boolean))
    const nodeCls = (n) => nodeState(n)
    return { x, tab, dlg, node, closing, docs, rets, openIssues, qtyMap, sub, a, report, more, nodeCls }
  },
  template: `<div v-if="x">
    <Head :t="x.no" :back="['加工单', '/production']">
      <button v-if="can('production', x, '提交')" class="btn pri" @click="a('提交')">提交审核</button>
      <template v-if="can('production', x, '审核')"><button class="btn" @click="a('驳回')">驳回</button><button class="btn pri" @click="a('审核')">审核下单</button></template>
      <template v-if="prodLive(x)"><button class="btn" @click="dlg = 'issueMat'">领料</button><button class="btn" @click="dlg = 'return'">登记回货</button><button class="btn pri" @click="report(null)">报进度</button></template>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <Upstream :d="x"/>
        <div v-for="i in openIssues" :key="i.id" class="banner red" data-c="异常提示"><Ico n="alert"/>{{ i.type }}：{{ i.desc }}<span v-if="i.newDate" class="mut">（预计交期 {{ fd(i.newDate) }}）</span><button class="btn sm" @click="tab = 'issues'">去处理</button></div>
        <div class="title">{{ supName(x.factory) }}<St :s="x.status"/></div>
        <div class="sub"><a class="code" :href="'#/styles/' + x.style">{{ x.style }}</a><span>{{ styleName(x.style) }}</span><span>{{ x.mode }}</span><span>{{ prodQty(x) }} 件</span><span>交期 {{ fd(expectDue(x)) }}</span><Days v-if="prodLive(x)" :d="expectDue(x)" :warn="7"/></div>
        <Tabs v-model="tab" :items="[['nodes', '进度'], ['lines', '明细'], ['mat', '领料', docs.length], ['issues', '异常', x.issues.length], ['rets', '回货', rets.length]]"/>
        <section v-if="tab === 'nodes'" data-c="进度节点">
          <div v-if="!x.nodes.length" class="empty">审核下单后按交期往前倒排节点</div>
          <ul class="tl">
            <li v-for="(n, i) in x.nodes" :key="n.name" :class="nodeCls(n)" style="cursor:pointer" @click="report(i)">
              <span class="dot"></span><b>{{ n.name }}</b>
              <div class="col"><small>计划 {{ fd(n.ps) }} – {{ fd(n.pf) }}</small><small v-if="n.as" :class="{ red: nodeCls(n) === 'late' }">实际 {{ fd(n.as) }} – {{ n.af ? fd(n.af) : '进行中' }}<template v-if="n.qty"> · {{ n.qty }} 件</template><template v-if="n.by"> · {{ userName(n.by) }}</template></small></div>
              <span><span v-if="nodeCls(n) === 'late'" class="flag red">{{ n.af ? '晚了 ' + -daysLeft2(n.pf, n.af) + ' 天' : '延期' }}</span><span v-else-if="nodeCls(n) === 'warn'" class="flag amber">临期</span><Ico v-else-if="n.af" n="check" :s="14" class="green"/></span>
            </li>
          </ul>
        </section>
        <section v-if="tab === 'lines'" data-c="加工明细"><Matrix :st="x.style" :model-value="qtyMap" ro :colors="[...new Set(x.lines.map(l => l.color))]" :sub="sub"/></section>
        <section v-if="tab === 'mat'" data-c="领料">
          <Tbl inset flat :rows="issuePlan(x)" empty="资料包里没有要发给工厂的料" :cols="[{ k: 'sku', t: '物料', f: r => skuName(r.sku), img: r => r.sku }, { k: 'owner', t: '物权', f: r => ownerName(r.owner) },
            { k: 'need', t: '应领', c: 'num', f: r => fq(r.need) + ' ' + unitOf(r.sku) }, { k: 'issued', t: '已领', c: 'num', f: r => fq(r.issued) }, { k: 'suggest', t: '还差', c: 'num' }, { k: 'avail', t: '主料仓库存', c: 'num', f: r => fq(r.avail) }]">
            <template #suggest="{ r }"><span :class="{ amber: r.suggest > 0 }">{{ r.suggest > 0 ? fq(r.suggest) : '领齐' }}</span></template></Tbl>
          <div class="sec"><h3>领料单</h3><Tbl inset flat :rows="docs" link @row="d => go('/stock-docs/' + d.no)" empty="还没领过料" :cols="[{ k: 'no', t: '单号', c: 'code' }, { k: 'type', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) }, { k: 'n', t: '物料', f: d => d.lines.length + ' 种' }, { k: 'status', t: '状态' }]"><template #status="{ r }"><St :s="r.status"/></template></Tbl></div>
        </section>
        <section v-if="tab === 'issues'" data-c="异常">
          <Tbl inset flat :rows="x.issues" empty="没有异常" :cols="[{ k: 'type', t: '类型' }, { k: 'desc', t: '描述' }, { k: 'at', t: '上报', f: i => fd(i.at) + ' ' + userName(i.by) }, { k: 'newDate', t: '新交期', f: i => fd(i.newDate) }, { k: 'party', t: '责任方', f: i => i.party || '—' }, { k: 'measure', t: '处理措施', f: i => i.measure || '—' }, { k: 'status', t: '状态' }]">
            <template #desc="{ r }"><span class="wrap">{{ r.desc }}</span></template>
            <template #status="{ r }"><span class="row"><St :s="r.status"/><button v-if="r.status !== '已关闭'" class="btn sm" @click="closing = r">关闭</button></span></template>
          </Tbl>
          <button v-if="prodLive(x)" class="btn ghost" style="margin-top:10px" @click="dlg = 'issue'"><Ico n="plus" :s="14"/>上报异常</button>
        </section>
        <section v-if="tab === 'rets'" data-c="回货">
          <Tbl inset flat :rows="rets" empty="还没回货" :cols="[{ k: 'no', t: '回货单号', c: 'code' }, { k: 'date', t: '日期', f: r => fd(r.date) }, { k: 'qty', t: '回货', c: 'num', f: r => sum(r.lines, l => l.qty) }, { k: 'def', t: '次品', c: 'num', f: r => sum(r.lines, l => l.def) }, { k: 'logistics', t: '物流单号', c: 'code', f: r => r.logistics || '—' }]"/>
        </section>
      </div>
      <aside class="aside" data-c="加工单属性">
        <Gallery :code="x.style" :colors="[...new Set(x.lines.map(l => l.color))]"/>
        <dl class="props">
          <dt>订单</dt><dd><a class="code" :href="'#/orders/' + x.order">{{ x.order }}</a></dd>
          <dt>资料包</dt><dd>V{{ x.ver }}</dd>
          <dt>加工厂</dt><dd><a :href="'#/suppliers/' + x.factory">{{ sup(x.factory).name }}</a></dd>
          <dt>加工方式</dt><dd>{{ x.mode }}</dd>
          <dt>单价</dt><dd>{{ fm(x.price) }} / 件</dd>
          <dt>金额</dt><dd>{{ fm(prodAmt(x)) }}</dd>
          <dt>下单日期</dt><dd>{{ fd(x.date) }}</dd>
          <dt>交期</dt><dd>{{ fd(x.due) }}<span v-if="x.expect" class="amber"> → {{ fd(x.expect) }}</span></dd>
          <dt>跟单</dt><dd>{{ userName(x.merch) }}</dd>
          <dt>结算</dt><dd>{{ x.settle }}</dd>
          <template v-if="prodLive(x) || x.status === '已完成'">
            <dt>齐料</dt><dd>{{ kitState(order(x.order)).t }}</dd>
            <dt>领料</dt><dd>{{ issueState(x) }}</dd>
            <dt>回货</dt><dd>{{ prodRet(x) }} / {{ prodQty(x) }}<span v-if="prodDef(x)" class="dim"> · 次品 {{ prodDef(x) }}</span></dd>
          </template>
        </dl>
        <h4>操作记录</h4><Log :items="x.log"/>
      </aside>
    </div>
    <ProdReport v-if="dlg === 'report'" :p="x" :i="node" @close="dlg = ''"/>
    <ProdIssueMat v-if="dlg === 'issueMat'" :p="x" @close="dlg = ''" @done="tab = 'mat'"/>
    <ProdException v-if="dlg === 'issue'" :p="x" @close="dlg = ''" @done="tab = 'issues'"/>
    <ProdReturn v-if="dlg === 'return'" :p="x" @close="dlg = ''" @done="tab = 'rets'"/>
    <Modal v-if="closing" :t="'关闭异常：' + closing.type" :w="460" @close="closing = null">
      <label class="f">处理措施<textarea class="inp" v-model="closing.draft"></textarea></label>
      <template #foot><button class="btn" @click="closing = null">取消</button><button class="btn pri" @click="run(() => closeIssue(x, closing, closing.draft)) && (closing = null)">关闭异常</button></template>
    </Modal>
  </div><NotFound v-else/>`,
}, { mod: 'production', t: (p) => '加工单 ' + p.no })

comp('ProdReport', {
  props: { p: Object, i: Number },
  emits: ['close'],
  setup(props, { emit }) {
    const cur = props.i ?? Math.max(0, props.p.nodes.findIndex((n) => !n.af))
    const f = reactive({ i: cur, as: '', af: '', qty: null, note: '' })
    watch(() => f.i, (i) => { const n = props.p.nodes[i]; Object.assign(f, { as: n.as || clock.today, af: n.af || clock.today, qty: n.qty, note: n.note }) }, { immediate: true })
    function ok() {
      const w = run(() => reportNode(props.p, f.i, { ...f }))
      if (w) { emit('close'); toast(typeof w === 'string' ? `已报，${w}` : `${props.p.nodes[f.i].name}已报`) }
    }
    return { f, ok }
  },
  template: `<Modal :t="'报进度 ' + p.no" :w="520" @close="$emit('close')">
    <div class="chips" style="margin-bottom:14px" data-c="选节点"><button v-for="(n, k) in p.nodes" :key="n.name" class="chip" :class="{ on: f.i === k }" @click="f.i = k"><Ico v-if="n.af" n="check" :s="12" class="green"/>{{ n.name }}</button></div>
    <div class="form">
      <label class="f">实际开始<input class="inp" type="date" v-model="f.as"></label>
      <label class="f">实际完成<input class="inp" type="date" v-model="f.af"></label>
      <label class="f">数量（件）<input class="inp num" type="number" v-model.number="f.qty"></label>
      <div class="f">照片<button class="btn" @click="toast('原型：手机上可以拍照，最多 9 张')"><Ico n="camera" :s="14"/>加照片</button></div>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <template #foot><button class="btn ghost" @click="f.af = ''">只报开始</button><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">提交</button></template>
  </Modal>`,
})

comp('ProdIssueMat', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const rows = reactive(issuePlan(props.p).map((r) => ({ ...r, qty: Math.min(r.suggest, Math.max(0, r.avail)) }))) // 默认有多少领多少
    const reason = ref('')
    const over = computed(() => rows.some((r) => r.qty > r.suggest + 0.0005))
    function ok() {
      if (over.value && !reason.value) return toast('超领要写原因', null, true)
      const d = run(() => issueMaterials(props.p, rows.map((r) => ({ sku: r.sku, owner: r.owner, qty: +r.qty || 0, reason: reason.value }))))
      if (d?.no) { emit('close'); emit('done'); toast(`${d.no} 已过账：主料仓 → ${whName(d.to)}`, ['打开', '/stock-docs/' + d.no]) }
    }
    return { rows, reason, over, ok }
  },
  template: `<Modal :t="'领料 ' + p.no" :w="720" @close="$emit('close')">
    <p class="mut" style="margin-bottom:10px">主料仓 → {{ whName(factoryWh(p.factory)?.code) }}</p>
    <table class="tbl inset flat" data-c="领料明细"><thead><tr><th>物料</th><th>物权</th><th class="num">还差</th><th class="num">主料仓库存</th><th class="num" style="width:120px">本次领</th></tr></thead>
      <tbody><tr v-for="r in rows" :key="r.sku + r.owner"><td><span class="row"><Thumb :code="r.sku" :s="30"/>{{ skuName(r.sku) }}</span></td><td :class="{ amber: r.owner !== '自有' }">{{ ownerName(r.owner) }}</td><td class="num">{{ fq(r.suggest) }} <span class="dim">{{ unitOf(r.sku) }}</span></td>
        <td class="num" :class="{ red: r.avail < r.qty }">{{ fq(r.avail) }}</td><td><input class="inp cell num" type="number" v-model.number="r.qty"></td></tr>
        <tr v-if="!rows.length"><td colspan="5" class="empty">资料包里没有要发给工厂的料</td></tr></tbody></table>
    <label v-if="over" class="f" style="margin-top:12px">超领原因<input class="inp" v-model="reason"></label>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">领料并过账</button></template>
  </Modal>`,
})

comp('ProdException', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const f = reactive({ type: '缺料', desc: '', impact: false, newDate: addDays(expectDue(props.p), 5), party: '' })
    function ok() { if (run(() => reportIssue(props.p, { ...f, newDate: f.impact ? f.newDate : null }))) { emit('close'); emit('done'); toast('异常已上报') } }
    return { f, ok }
  },
  template: `<Modal :t="'上报异常 ' + p.no" :w="520" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in db.dicts.异常类型" :key="x">{{ x }}</option></select></label>
      <label class="f">责任方<select class="sel" v-model="f.party"><option value="">待定</option><option>我方</option><option>工厂</option><option>供应商</option><option>客户</option></select></label>
      <label class="f full">描述<textarea class="inp" v-model="f.desc"></textarea></label>
      <label class="chk"><input type="checkbox" v-model="f.impact">影响交期</label>
      <label v-if="f.impact" class="f">预计新交期<input class="inp" type="date" v-model="f.newDate"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">上报</button></template>
  </Modal>`,
})

comp('ProdReturn', {
  props: { p: Object },
  emits: ['close', 'done'],
  setup(props, { emit }) {
    const qty = ref(Object.fromEntries(props.p.lines.map((l) => [l.color + '|' + l.size, Math.max(0, l.qty - l.ret)])))
    const def = ref({})
    const f = reactive({ date: clock.today, logistics: '' })
    const colors = [...new Set(props.p.lines.map((l) => l.color))]
    const sub = (c, z) => { const l = props.p.lines.find((y) => y.color === c && y.size === z); return l ? `加工 ${l.qty} · 已回 ${l.ret}` : '' }
    function ok() {
      const lines = Object.fromEntries(Object.keys({ ...qty.value, ...def.value }).map((k) => [k, { qty: qty.value[k] || 0, def: def.value[k] || 0 }]))
      const r = run(() => registerReturn(props.p, { ...f, lines }))
      if (r?.no) { emit('close'); emit('done'); toast(`${r.no} 已登记：${sum(r.lines, (l) => l.qty)} 件${props.p.status === '已完成' ? '，加工单完成' : ''}`) }
    }
    return { qty, def, f, colors, sub, ok }
  },
  template: `<Modal :t="'登记回货 ' + p.no" :w="760" @close="$emit('close')">
    <div class="form" style="margin-bottom:14px"><label class="f">回货日期<input class="inp" type="date" v-model="f.date"></label><label class="f">物流单号<input class="inp" v-model="f.logistics"></label></div>
    <div class="sec" style="margin-top:0" data-c="回货数"><h3>回货数（含次品）</h3><Matrix :st="p.style" v-model="qty" :colors="colors" :sub="sub"/></div>
    <div class="sec" data-c="次品数"><h3>其中次品</h3><Matrix :st="p.style" v-model="def" :colors="colors"/></div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">登记</button></template>
  </Modal>`,
})

/* ---------- 生产看板 ---------- */
route('/board', {
  setup() {
    const s = reactive({ card: '', view: '按单', factory: '' })
    const live = computed(() => db.productions.filter(prodLive).filter((p) => !s.factory || p.factory === s.factory))
    const month = clock.today.slice(0, 7)
    const cards = computed(() => {
      const L = live.value
      const run = L.filter((p) => ['生产中', '部分回货'].includes(p.status))
      return [
        { k: 'run', t: '在制', n: run.length, m: sum(run, (p) => prodQty(p) - prodRet(p)) + ' 件', f: (p) => ['生产中', '部分回货'].includes(p.status) },
        { k: 'wait', t: '待开工', n: L.filter((p) => p.status === '已下单').length, f: (p) => p.status === '已下单' },
        { k: 'near', t: '7 天内交货', n: L.filter((p) => prodRisk(p).near).length, f: (p) => prodRisk(p).near },
        { k: 'late', t: '已逾期', n: L.filter((p) => prodRisk(p).late).length, f: (p) => prodRisk(p).late },
        { k: 'node', t: '节点延期', n: L.filter((p) => prodRisk(p).node).length, f: (p) => prodRisk(p).node },
        { k: 'done', t: '本月回货', n: sum(db.returns.filter((r) => r.date.startsWith(month)), (r) => sum(r.lines, (l) => l.qty)), m: '件', f: null },
      ]
    })
    const rows = computed(() => { const c = cards.value.find((x) => x.k === s.card); return live.value.filter((p) => !c?.f || c.f(p)).slice().sort(byRisk) })
    const factories = computed(() => db.suppliers.filter(isFactory).map((f) => { const ps = db.productions.filter((p) => p.factory === f.code && prodLive(p)); return { f, n: ps.length, qty: sum(ps, (p) => prodQty(p) - prodRet(p)), late: ps.filter((p) => prodRisk(p).late).length } }).filter((x) => x.n || x.f.status === '合作中'))
    const maxQty = computed(() => Math.max(1, ...factories.value.map((x) => x.qty)))
    return { s, cards, rows, factories, maxQty }
  },
  template: `<div>
    <Head t="看板"><select class="sel" style="height:28px" v-model="s.factory"><option value="">全部工厂</option><option v-for="f in db.suppliers.filter(isFactory)" :key="f.code" :value="f.code">{{ f.short }}</option></select></Head>
    <div class="stats" data-c="数字卡">
      <div v-for="c in cards" :key="c.k" class="stat" :class="{ on: s.card === c.k }" @click="c.f && (s.card = s.card === c.k ? '' : c.k)"><span>{{ c.t }}</span><b :class="{ red: c.k === 'late' && c.n, amber: (c.k === 'near' || c.k === 'node') && c.n }">{{ c.n.toLocaleString() }}<small v-if="c.m">{{ c.m }}</small></b></div>
    </div>
    <div class="bar"><Seg v-model="s.view" :items="['按单', '按工厂']"/></div>
    <div v-if="s.view === '按单'" data-c="看板列表"><ProdTable :rows="rows"/></div>
    <div v-else data-c="按工厂">
      <Tbl :rows="factories" link @row="x => { s.factory = x.f.code; s.view = '按单' }" :cols="[{ k: 'f', t: '加工厂', f: x => x.f.short }, { k: 'cat', t: '类型', f: x => x.f.cats.join(' ') }, { k: 'n', t: '在制单数', c: 'num' }, { k: 'qty', t: '在制件数', c: 'num' }, { k: 'bar', t: '', w: '30%' }, { k: 'late', t: '逾期单', c: 'num' }]">
        <template #bar="{ r }"><span class="hbar"><b :style="{ width: (r.qty / maxQty * 100) + '%' }"></b></span></template>
        <template #late="{ r }"><span :class="{ red: r.late }">{{ r.late }}</span></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'board', t: '看板' })

/* ---------- 手机报工（跟单员；工厂视角要外部账号，三期） ---------- */
route('/mobile', {
  setup() {
    const s = reactive({ who: '跟单员', factory: 'GYS0004', no: null, node: null, f: { as: '', af: '', qty: null, note: '' } })
    const list = computed(() => db.productions.filter(prodLive).filter((p) => (s.who === '跟单员' ? p.merch === 'u1' : p.factory === s.factory)).slice().sort(byRisk))
    const p = computed(() => production(s.no))
    function pickNode(i) { const n = p.value.nodes[i]; s.node = i; Object.assign(s.f, { as: n.as || clock.today, af: n.af || clock.today, qty: n.qty, note: n.note }) }
    function submit() { const w = run(() => reportNode(p.value, s.node, { ...s.f })); if (w) { toast(`${p.value.nodes[s.node].name}已报，电脑上马上能看到`); s.node = null } }
    const back = () => { if (s.node != null) s.node = null; else s.no = null }
    return { s, list, p, pickNode, submit, back }
  },
  template: `<div>
    <Head t="手机报工"><Seg v-model="s.who" :items="['跟单员', '工厂']"/><select v-if="s.who === '工厂'" class="sel" style="height:28px" v-model="s.factory"><option v-for="f in db.suppliers.filter(f => isFactory(f) && f.status === '合作中')" :key="f.code" :value="f.code">{{ f.short }}</option></select></Head>
    <div class="phone" data-c="手机">
      <div class="scr">
        <template v-if="!p">
          <div class="ph">{{ s.who === '跟单员' ? '我的跟单' : supName(s.factory) }}<span style="flex:1"></span><span class="dim" style="font-size:12px;font-weight:400">{{ s.who === '跟单员' ? '陈晓' : '外部账号' }}</span></div>
          <div v-for="x in list" :key="x.no" class="pi" @click="s.no = x.no">
            <Thumb :code="x.style" :s="52"/>
            <div class="col" style="flex:1;min-width:0"><div class="row"><b class="code">{{ x.style }}</b><span style="flex:1"></span><Days :d="expectDue(x)" :warn="7"/></div>
              <span class="mut">{{ supName(x.factory) }} · {{ prodQty(x) }} 件</span><span class="row"><Nodes :p="x"/><span class="dim" style="font-size:12px">{{ curNode(x)?.name || '待入仓' }}</span></span></div>
          </div>
          <div v-if="!list.length" class="empty">没有在做的单</div>
        </template>
        <template v-else>
          <div class="ph"><button class="btn ghost icon" @click="back"><Ico n="left"/></button>{{ s.node == null ? p.style : p.nodes[s.node].name }}</div>
          <template v-if="s.node == null">
            <div style="padding:14px 16px" class="col"><span>{{ styleName(p.style) }} · {{ supName(p.factory) }}</span><span class="mut">{{ prodQty(p) }} 件 · 交期 {{ fd(expectDue(p)) }}</span></div>
            <div class="nodes-big"><button v-for="(n, i) in p.nodes" :key="n.name" :class="[nodeState(n) === 'late' ? 'late' : n.af ? 'done' : curNode(p) === n ? 'on' : '']" @click="pickNode(i)">{{ n.name }}<small>{{ n.af ? '完成 ' + fd(n.af) : n.as ? '进行中' : '计划 ' + fd(n.pf) }}</small></button></div>
            <div style="padding:0 16px 16px"><a class="btn" style="width:100%" :href="'#/production/' + p.no">在电脑上打开</a></div>
          </template>
          <div v-else class="col" style="padding:16px;gap:12px">
            <label class="f">实际开始<input class="inp" type="date" v-model="s.f.as"></label>
            <label class="f">实际完成<input class="inp" type="date" v-model="s.f.af"></label>
            <label class="f">数量（件）<input class="inp num" type="number" v-model.number="s.f.qty"></label>
            <button class="btn" style="height:44px" @click="toast('原型：调起相机，最多 9 张')"><Ico n="camera"/>拍照</button>
            <label class="f">备注<textarea class="inp" v-model="s.f.note"></textarea></label>
            <button class="btn pri" style="height:44px" @click="submit">提交</button>
          </div>
        </template>
      </div>
    </div>
  </div>`,
}, { mod: 'mobile', t: '手机报工' })

expose({ reportNode, issueMaterials, closeIssue, factoryWh, daysLeft2: (a, b) => diffDays(a, b) })
