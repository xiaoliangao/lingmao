/* ============================================================
 * 订单、排单、算料
 * ============================================================ */
const ORDER_STEPS = ['草稿', '待审核', '待排单', '生产中', '已完成']

function orderProduction(no) {
  return db.production.filter((p) => p.order === no)
}

function orderReceived(o) {
  return orderProduction(o.no).reduce((a, p) => a + prodReceived(p), 0)
}

function addDays(d, n) {
  const x = new Date(d)
  x.setDate(x.getDate() + n)
  return x.toISOString().slice(0, 10)
}

const OrderListPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: '全部', keyword: '' }),
  computed: {
    list() {
      return db.orders.filter((o) => (this.tab === '全部' || o.status === this.tab) && (!this.keyword || (o.no + custName(o.customer) + o.contract + o.lines.map((l) => l.style).join()).includes(this.keyword)))
    },
    counts() {
      const c = { 全部: db.orders.length }
      for (const o of db.orders) c[o.status] = (c[o.status] || 0) + 1
      return c
    },
  },
  methods: {
    received: orderReceived,
    styleText(o) {
      const s = styleOf(o.lines[0].style)
      return s.code + ' ' + s.name + (o.lines.length > 1 ? ` 等 ${o.lines.length} 款` : '')
    },
  },
  template: `
  <div class="page">
    <PageHeader title="订单" description="客户订单、自营备货、翻单、补单都在这里。审核即评审：审核时确认交期、锁定资料包版本。">
      <template #actions>
        <el-button icon="Upload" @click="proto('按模板导入订单：一行一个 SKU（款号、颜色、尺码、数量、单价、交期），导入前先预览错误行')">从 Excel 导入</el-button>
        <el-button type="primary" icon="Plus" @click="proto('新建订单：选客户、款式后出现 颜色 × 尺码 表格，直接填数量')">新建订单</el-button>
      </template>
    </PageHeader>
    <section class="panel">
      <el-tabs v-model="tab" class="status-tabs">
        <el-tab-pane v-for="t in ['全部', ...ORDER_STEPS]" :key="t" :name="t" :label="t + ' ' + (counts[t] || 0)" />
      </el-tabs>
      <el-form class="filter-bar" inline @submit.prevent>
        <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="订单号、客户、合同号、款号；多个用空格隔开" style="width:320px" /></el-form-item>
      </el-form>
      <div class="panel-body">
        <el-table :data="list" empty-text="这个状态下没有订单">
          <el-table-column label="订单号" width="140"><template #default="{ row }"><a class="link code" @click="go('/orders/' + row.no)">{{ row.no }}</a></template></el-table-column>
          <el-table-column prop="type" label="类型" width="80" />
          <el-table-column label="客户" width="100"><template #default="{ row }">{{ custName(row.customer) }}</template></el-table-column>
          <el-table-column label="款式" min-width="150"><template #default="{ row }">{{ styleText(row) }}</template></el-table-column>
          <el-table-column label="数量" width="70" align="right" class-name="num"><template #default="{ row }">{{ fmt(orderQty(row)) }}</template></el-table-column>
          <el-table-column label="金额" width="100" align="right" class-name="num"><template #default="{ row }">{{ money(orderAmount(row)) }}</template></el-table-column>
          <el-table-column label="客户交期" width="96" class-name="num"><template #default="{ row }">{{ row.due }}</template></el-table-column>
          <el-table-column label="承诺交期" width="96" class-name="num"><template #default="{ row }">{{ row.promise || '—' }}</template></el-table-column>
          <el-table-column prop="follower" label="跟单" width="64" />
          <el-table-column label="回货" width="100"><template #default="{ row }"><el-progress v-if="['生产中','已完成'].includes(row.status)" :percentage="Math.round(received(row) / orderQty(row) * 100)" :stroke-width="6" /><span v-else class="muted">—</span></template></el-table-column>
          <el-table-column label="状态" width="86"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.5 订单与排单">列表只放 11 列，其余字段在详情里看。</ProtoNote>
  </div>`,
})
OrderListPage.data = ((orig) =>
  function () {
    return { ...orig.call(this), ORDER_STEPS }
  })(OrderListPage.data)

const OrderDetailPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: 'down', auditing: false, auditForm: { promise: '', opinion: '' }, scheduling: false, plan: [], changing: false, change: { type: '数量', detail: '', reason: '', duty: '客户' } }),
  computed: {
    o() {
      return orderOf(this.params.id)
    },
    prods() {
      return orderProduction(this.o.no)
    },
    purchases() {
      return db.purchases.filter((p) => p.orders.includes(this.o.no))
    },
    picks() {
      const jg = this.prods.map((p) => p.no)
      return db.stockOuts.filter((x) => jg.includes(x.src))
    },
    mrpPreview() {
      const { rows, issues } = computeMrp([this.o.no])
      return {
        buy: rows.filter((r) => r.source === '我方采购' && mrpSuggest(r) > 0),
        customer: rows.filter((r) => r.source === '客供'),
        issues,
      }
    },
    planGroups() {
      const g = {}
      for (const r of this.plan) if (r.factory) (g[r.factory + '|' + r.mode] ||= []).push(r)
      return Object.values(g)
    },
    refPrice() {
      const p = effectivePack(this.o.lines[0].style)
      return p ? p.process.reduce((a, x) => a + x.price, 0) : 0
    },
    factories() {
      return db.suppliers.filter((s) => ['成衣厂', '加工厂'].includes(s.cat))
    },
  },
  methods: {
    sizes(style) {
      return sizesOfStyle(style)
    },
    packText(line) {
      if (line.pack) return '资料包 ' + line.pack + '（已锁定）'
      const p = effectivePack(line.style)
      return p ? '资料包 ' + p.ver + '（审核时锁定）' : '还没有已生效的资料包'
    },
    submit() {
      const missing = this.o.lines.filter((l) => !effectivePack(l.style))
      if (missing.length) return ElMessage.error(`${missing.map((l) => l.style).join('、')} 的资料包还是草稿，生效后才能提交审核（算料要用 BOM）`)
      this.o.status = '待审核'
      ElMessage.success('已提交，等审核')
    },
    openAudit() {
      this.auditForm = { promise: this.o.due, opinion: '' }
      this.auditing = true
    },
    doAudit() {
      this.o.lines.forEach((l) => (l.pack = effectivePack(l.style).ver))
      this.o.promise = this.auditForm.promise
      this.o.status = '待排单'
      this.o.auditBy = db.me.name
      this.o.auditAt = TODAY + ' 15:20'
      this.auditing = false
      ElMessage.success('审核通过，资料包版本已锁定。下一步：排单、算料')
    },
    back() {
      ElMessageBox.confirm('退回后订单回到草稿，业务员改完再提交。', '退回订单', { confirmButtonText: '退回', cancelButtonText: '取消' })
        .then(() => {
          this.o.status = '草稿'
          ElMessage.success('已退回')
        })
        .catch(() => {})
    },
    unaudit() {
      if (this.prods.length || this.purchases.length) return ElMessage.error('已经有加工单或采购单了，不能反审核。要改数量、交期请走「变更」')
      this.o.status = '待审核'
      this.o.lines.forEach((l) => (l.pack = ''))
      ElMessage.success('已反审核')
    },
    remove() {
      ElMessageBox.confirm('删除后这张订单就没了，不能恢复。草稿才能删。', '删除订单', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger' })
        .then(() => {
          db.orders.splice(db.orders.indexOf(this.o), 1)
          ElMessage.success('已删除')
          go('/orders')
        })
        .catch(() => {})
    },
    openSchedule() {
      // 贯穿例子：卡其 400 件排给某某制衣厂（清加工 38 元/件，交期 2027-03-10），藏青、黑色排给明辉服装
      const isExample = this.o.no === 'DD202611050001'
      this.plan = this.o.lines.flatMap((l) =>
        Object.keys(l.qty).map((c) => {
          const s = styleOf(l.style)
          const preset = isExample && c !== 'KQ' ? { factory: 'S005', price: 36, due: '2027-03-12' } : { factory: s.factory, price: isExample ? 38 : Math.round(this.refPrice) || 40, due: isExample ? '2027-03-10' : addDays(this.o.promise || this.o.due, -10) }
          return { style: l.style, color: c, qty: sumValues(l.qty[c]), sizes: l.qty[c], pack: l.pack, mode: '清加工', ...preset }
        }),
      )
      this.scheduling = true
    },
    doSchedule() {
      if (this.plan.some((r) => !r.factory)) return ElMessage.warning('每一行都要选加工厂')
      const made = []
      for (const rows of this.planGroups) {
        const first = rows[0]
        const qty = {}
        rows.forEach((r) => (qty[r.color] = { ...r.sizes }))
        const due = rows.map((r) => r.due).sort()[0]
        const start = addDays(TODAY, 1)
        const span = days(start, due)
        const at = (f) => addDays(start, Math.round(span * f))
        const no = nextNo('JG')
        db.production.unshift({
          no, order: this.o.no, style: first.style, pack: first.pack, factory: first.factory, mode: first.mode, price: first.price, date: TODAY, due, follower: this.o.follower, status: '已下单',
          qty, received: {}, defects: 0, terms: '次品率超 2% 的部分按加工价 2 倍扣款；每延期一天扣总加工费 1%',
          nodes: [
            { name: '到料', ps: start, pe: at(0.25), as: '', ae: '', qty: 0 },
            { name: '裁剪', ps: at(0.25), pe: at(0.35), as: '', ae: '', qty: 0 },
            { name: '车缝', ps: at(0.35), pe: at(0.8), as: '', ae: '', qty: 0 },
            { name: '后整', ps: at(0.8), pe: at(0.9), as: '', ae: '', qty: 0 },
            { name: '包装', ps: at(0.9), pe: at(0.96), as: '', ae: '', qty: 0 },
            { name: '入仓', ps: at(0.96), pe: due, as: '', ae: '', qty: 0 },
          ],
          issues: [], receipts: [], reports: [],
        })
        made.push(no)
      }
      this.o.status = '生产中'
      this.scheduling = false
      ElMessage.success(`生成了 ${made.length} 张加工单：${made.join('、')}`)
      go('/production/' + made[0])
    },
    saveChange() {
      if (!this.change.detail) return ElMessage.warning('写一下改了什么')
      this.o.changes.unshift({ no: nextNo('BG'), ...this.change, by: db.me.name, at: TODAY + ' 15:30', status: '待审核', effect: '审核后同步到加工单；数量变了会提示重新算料' })
      this.changing = false
      this.tab = 'changes'
      ElMessage.success('变更单已提交，审核后生效')
    },
    prodProgress,
    received: orderReceived,
    sumValues,
  },
  template: `
  <div class="page" v-if="o">
    <PageHeader :title="'订单 ' + o.no" :description="o.type + ' · ' + (o.customer ? cust(o.customer).name : '自营') + (o.contract ? ' · 合同 ' + o.contract : '') + (o.source ? ' · 翻自 ' + o.source : '')">
      <template #title-extra><StatusTag :status="o.status" /></template>
      <template #actions>
        <template v-if="o.status === '草稿'">
          <el-button type="danger" plain @click="remove">删除</el-button>
          <el-button type="primary" @click="submit">提交审核</el-button>
        </template>
        <template v-if="o.status === '待审核'">
          <el-button @click="back">退回</el-button>
          <el-button icon="Histogram" @click="go('/mrp?orders=' + o.no)">先看算料</el-button>
          <el-button type="primary" icon="Check" @click="openAudit">审核通过</el-button>
        </template>
        <template v-if="o.status === '待排单'">
          <el-button @click="unaudit">反审核</el-button>
          <el-button icon="Edit" @click="changing = true">变更</el-button>
          <el-button icon="Histogram" @click="go('/mrp?orders=' + o.no)">算料</el-button>
          <el-button type="primary" icon="Share" @click="openSchedule">排单（生成加工单）</el-button>
        </template>
        <template v-if="o.status === '生产中'">
          <el-button icon="Edit" @click="changing = true">变更</el-button>
          <el-button icon="Histogram" @click="go('/mrp?orders=' + o.no)">算料</el-button>
          <el-button type="primary" @click="go('/board')">看生产看板</el-button>
        </template>
        <el-button v-if="o.status === '已完成'" icon="CopyDocument" @click="proto('复制成新订单（翻单，二期）：资料包沿用当时锁定的版本，改数量和交期就行')">翻单（复制为新订单）</el-button>
      </template>
    </PageHeader>
    <FlowBar :steps="ORDER_STEPS" :current="o.status" />

    <section class="panel block">
      <dl class="desc-grid">
        <div><dt>订单类型</dt><dd>{{ o.type }}</dd></div>
        <div><dt>客户</dt><dd>{{ custName(o.customer) }}</dd></div>
        <div><dt>下单日期</dt><dd class="num">{{ o.date }}</dd></div>
        <div><dt>客户交期</dt><dd class="num">{{ o.due }} <span class="muted">（剩 {{ daysLeft(o.due) }} 天）</span></dd></div>
        <div><dt>承诺交期</dt><dd class="num">{{ o.promise || '审核时填' }}</dd></div>
        <div><dt>跟单 / 业务</dt><dd>{{ o.follower }} / {{ o.sales || '—' }}</dd></div>
        <div><dt>数量 / 金额</dt><dd class="num">{{ fmt(orderQty(o)) }} 件 / {{ money(orderAmount(o)) }}</dd></div>
        <div><dt>审核</dt><dd>{{ o.auditBy ? o.auditBy + ' · ' + o.auditAt : '—' }}</dd></div>
        <div class="full"><dt>送货要求</dt><dd>{{ o.delivery }}</dd></div>
        <div v-if="o.note" class="full"><dt>备注</dt><dd>{{ o.note }}</dd></div>
      </dl>
    </section>

    <section v-for="l in o.lines" :key="l.style" class="panel" style="margin-top:16px">
      <div class="panel-title">
        <div style="display:flex;gap:12px;align-items:center">
          <GarmentThumb :shape="styleOf(l.style).shape" :color="Object.keys(l.qty)[0]" :size="44" />
          <div><h2><a class="link code" @click="go('/styles/' + l.style + '?tab=pack')">{{ l.style }}</a> {{ styleOf(l.style).name }}</h2><span class="hint">{{ packText(l) }} · 单价 {{ money(l.price) }} · {{ fmt(matrixTotal(l.qty)) }} 件 · {{ money(matrixTotal(l.qty) * l.price) }}</span></div>
        </div>
        <span v-if="o.status === '草稿'" class="hint">草稿可以直接改格子里的数量</span>
      </div>
      <div class="panel-body"><QtyMatrix :qty="l.qty" :sizes="sizes(l.style)" :editable="o.status === '草稿'" /></div>
    </section>

    <section class="panel" style="margin-top:16px"><div class="panel-body">
      <el-tabs v-model="tab" class="sub-tabs">
        <el-tab-pane :label="'下游单据 ' + (prods.length + purchases.length + picks.length)" name="down">
          <p v-if="!prods.length && !purchases.length" class="muted">还没有下游单据。审核后点「排单」生成加工单，点「算料」生成采购单。</p>
          <template v-if="prods.length">
            <h4 style="margin:0 0 8px">外发加工单</h4>
            <el-table :data="prods" size="small" style="margin-bottom:16px">
              <el-table-column label="加工单" width="150"><template #default="{ row }"><a class="link code" @click="go('/production/' + row.no)">{{ row.no }}</a></template></el-table-column>
              <el-table-column label="加工厂" width="110"><template #default="{ row }">{{ supName(row.factory) }}</template></el-table-column>
              <el-table-column prop="mode" label="加工方式" width="90" />
              <el-table-column label="颜色" min-width="140"><template #default="{ row }"><ColorChip v-for="c in Object.keys(row.qty)" :key="c" :code="c" /></template></el-table-column>
              <el-table-column label="数量" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmt(matrixTotal(row.qty)) }}</template></el-table-column>
              <el-table-column label="进度" width="130"><template #default="{ row }"><el-progress :percentage="prodProgress(row)" :stroke-width="6" /></template></el-table-column>
              <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }">{{ row.due }}</template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            </el-table>
          </template>
          <template v-if="purchases.length">
            <h4 style="margin:0 0 8px">采购单</h4>
            <el-table :data="purchases" size="small" style="margin-bottom:16px">
              <el-table-column label="采购单" width="150"><template #default="{ row }"><a class="link code" @click="go('/purchases/' + row.no)">{{ row.no }}</a></template></el-table-column>
              <el-table-column label="供应商" width="110"><template #default="{ row }">{{ supName(row.supplier) }}</template></el-table-column>
              <el-table-column label="物料" min-width="220"><template #default="{ row }">{{ linesBrief(row.lines, false) }}</template></el-table-column>
              <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }">{{ row.due }}</template></el-table-column>
              <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            </el-table>
          </template>
          <template v-if="picks.length">
            <h4 style="margin:0 0 8px">领料单</h4>
            <el-table :data="picks" size="small">
              <el-table-column prop="no" label="单号" width="150" class-name="code" />
              <el-table-column label="从 → 到" min-width="200"><template #default="{ row }">{{ whName(row.from) }} → {{ whName(row.to) }}</template></el-table-column>
              <el-table-column label="行数" width="70" align="right" class-name="num"><template #default="{ row }">{{ row.lines.length }}</template></el-table-column>
              <el-table-column prop="date" label="日期" width="100" class-name="num" />
              <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            </el-table>
          </template>
        </el-tab-pane>
        <el-tab-pane :label="'变更记录 ' + o.changes.length" name="changes">
          <el-table :data="o.changes" size="small" empty-text="没有变更过。审核后的订单改数量、交期、配色配码，都要走变更单留痕。">
            <el-table-column prop="no" label="变更单" width="150" class-name="code" />
            <el-table-column prop="type" label="类型" width="70" />
            <el-table-column prop="detail" label="改了什么" min-width="220" />
            <el-table-column prop="reason" label="原因" width="100" />
            <el-table-column prop="duty" label="责任方" width="80" />
            <el-table-column prop="effect" label="影响" min-width="200" />
            <el-table-column label="提交" width="150"><template #default="{ row }">{{ row.by }}<div class="muted num" style="font-size:12px">{{ row.at }}</div></template></el-table-column>
            <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="操作记录" name="log">
          <ul class="timeline-list">
            <li v-if="o.auditAt">审核通过，资料包版本锁定<small>{{ o.auditAt }} · {{ o.auditBy }}</small></li>
            <li>创建订单<small>{{ o.date }} 09:30 · {{ o.sales || o.follower }}</small></li>
          </ul>
        </el-tab-pane>
      </el-tabs>
    </div></section>

    <el-dialog v-model="auditing" title="审核订单（评审）" width="560px">
      <el-form label-width="96px">
        <el-form-item label="客户交期"><span class="num">{{ o.due }}</span></el-form-item>
        <el-form-item label="承诺交期"><el-date-picker v-model="auditForm.promise" value-format="YYYY-MM-DD" type="date" /></el-form-item>
        <el-form-item label="锁定资料包"><span v-for="l in o.lines" :key="l.style" style="margin-right:12px"><span class="code">{{ l.style }}</span> → {{ effectivePack(l.style)?.ver }}</span></el-form-item>
        <el-form-item label="算料预估">
          <div style="font-size:13px;line-height:1.7">
            <div>要采购 <b class="num">{{ mrpPreview.buy.length }}</b> 种物料<span v-if="mrpPreview.buy.length">，其中面料 {{ mrpPreview.buy.filter((r) => r.mat.type === '面料').map((r) => skuLabel(r.sku)).join('、') }}</span></div>
            <div v-if="mrpPreview.customer.length">客供料 {{ mrpPreview.customer.map((r) => skuLabel(r.sku) + ' ' + fmtQ(mrpNeed(r)) + ' 个').join('、') }}，要找客户要</div>
            <div v-for="i in mrpPreview.issues" :key="i" class="late-text">{{ i }}</div>
          </div>
        </el-form-item>
        <el-form-item label="评审意见"><el-input v-model="auditForm.opinion" type="textarea" :rows="2" placeholder="产能、面料周期有没有风险，可以不写" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="auditing = false">取消</el-button><el-button type="primary" @click="doAudit">审核通过</el-button></template>
    </el-dialog>

    <el-dialog v-model="scheduling" title="排单：把订单拆成加工单" width="980px">
      <div class="callout info">按颜色一行，给每行选加工厂。同一个加工厂、同一种加工方式的行合成一张加工单。参考加工价（工艺单工序合计）{{ money(refPrice) }} / 件。</div>
      <el-table :data="plan" size="small">
        <el-table-column label="款式" width="90"><template #default="{ row }"><span class="code">{{ row.style }}</span></template></el-table-column>
        <el-table-column label="颜色" width="110"><template #default="{ row }"><ColorChip :code="row.color" /></template></el-table-column>
        <el-table-column label="数量" width="70" align="right" class-name="num"><template #default="{ row }">{{ row.qty }}</template></el-table-column>
        <el-table-column label="尺码配比" min-width="150"><template #default="{ row }"><span class="num muted" style="font-size:12px">{{ Object.entries(row.sizes).map(([k, v]) => k + ' ' + v).join(' · ') }}</span></template></el-table-column>
        <el-table-column label="加工厂" width="150"><template #default="{ row }"><el-select v-model="row.factory" size="small" placeholder="选加工厂"><el-option v-for="f in factories" :key="f.id" :label="f.short + (f.status !== '合作中' ? '（' + f.status + '）' : '')" :value="f.id" :disabled="f.status !== '合作中'" /></el-select></template></el-table-column>
        <el-table-column label="加工方式" width="120"><template #default="{ row }"><el-select v-model="row.mode" size="small"><el-option v-for="m in ['清加工','包工包料','半包']" :key="m" :label="m" :value="m" /></el-select></template></el-table-column>
        <el-table-column label="加工价/件" width="110"><template #default="{ row }"><el-input-number v-model="row.price" size="small" :min="0" :precision="2" :controls="false" style="width:90px" /></template></el-table-column>
        <el-table-column label="工厂交期" width="150"><template #default="{ row }"><el-date-picker v-model="row.due" size="small" value-format="YYYY-MM-DD" type="date" style="width:130px" /></template></el-table-column>
        <el-table-column label="" width="70"><template #default><el-button link type="primary" size="small" @click="proto('按尺码把这个颜色拆一部分给另一家厂')">拆分</el-button></template></el-table-column>
      </el-table>
      <p class="muted" style="font-size:13px;margin:12px 0 0">清加工：料全部我方供，算料时全部算进来。包工包料：料由工厂自备，只有标了「我方采购」「客供」的行要我们发。半包：BOM 上逐行标。</p>
      <template #footer>
        <span class="muted" style="margin-right:12px">将生成 {{ planGroups.length }} 张加工单</span>
        <el-button @click="scheduling = false">取消</el-button>
        <el-button type="primary" @click="doSchedule">生成 {{ planGroups.length }} 张加工单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="changing" title="订单变更" width="520px">
      <el-form label-width="80px">
        <el-form-item label="变更类型"><el-select v-model="change.type"><el-option v-for="t in ['数量','交期','配色配码','单价','取消']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="改了什么"><el-input v-model="change.detail" type="textarea" :rows="2" placeholder="如 卡其 M 120 → 140；交期 03-20 → 03-25" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="change.reason" /></el-form-item>
        <el-form-item label="责任方"><el-radio-group v-model="change.duty"><el-radio value="客户">客户</el-radio><el-radio value="内部">内部</el-radio><el-radio value="工厂">工厂</el-radio></el-radio-group></el-form-item>
      </el-form>
      <p class="muted" style="font-size:12px">变更单审核后才改订单；改之前的值留在变更记录里。下游加工单、采购单会标「上游已变更」，提醒跟单处理。</p>
      <template #footer><el-button @click="changing = false">取消</el-button><el-button type="primary" @click="saveChange">提交变更</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.5 订单与排单">明细是 颜色 × 尺码 矩阵，一格就是一个 SKU 的数量。贯穿例子：华东某服饰 27CF001 女式风衣 3 色 × 5 码 1200 件，交期 2027-03-20。</ProtoNote>
  </div>
  <div v-else class="page"><BlankState title="没有这张订单" description="可能已经删除。"><el-button @click="go('/orders')">回到订单列表</el-button></BlankState></div>`,
})
Object.assign(OrderDetailPage.methods, { cust, effectivePack, mrpNeed })
OrderDetailPage.data = ((orig) =>
  function () {
    return { ...orig.call(this), ORDER_STEPS }
  })(OrderDetailPage.data)

/* ---------------- 算料 ---------------- */
const MrpPage = page({
  props: ['params', 'query'],
  data() {
    const fromQuery = (this.query.orders || '').split(',').filter(Boolean)
    return { picked: fromQuery.length ? fromQuery : ['DD202611050001'], result: { rows: [], issues: [] } }
  },
  created() {
    this.run()
  },
  watch: {
    picked() {
      this.run()
    },
  },
  computed: {
    options() {
      return db.orders.filter((o) => !['草稿', '已完成'].includes(o.status))
    },
    buyRows() {
      return this.result.rows.filter((r) => r.source === '我方采购')
    },
    customerRows() {
      return this.result.rows.filter((r) => r.source === '客供')
    },
    factoryRows() {
      return this.result.rows.filter((r) => r.source === '加工厂自备')
    },
    toBuy() {
      return this.buyRows.filter((r) => r.checked && mrpSuggest(r) > 0)
    },
    bySupplier() {
      const g = {}
      for (const r of this.toBuy) (g[r.mat.supplier || '未定'] ||= []).push(r)
      return g
    },
  },
  methods: {
    run() {
      this.result = computeMrp(this.picked)
    },
    need: mrpNeed,
    use: mrpUse,
    net: mrpNet,
    suggest: mrpSuggest,
    setUse(r, v) {
      r.use = v
    },
    rowClass({ row }) {
      return row.sku === 'ML0002-KQ' ? 'example-row' : ''
    },
    generate() {
      const groups = Object.entries(this.bySupplier)
      if (!groups.length) return ElMessage.warning('没有要采购的物料')
      if (this.bySupplier['未定']) return ElMessage.error('有物料没设默认供应商，先去物料档案补上')
      const made = []
      for (const [supplier, rows] of groups) {
        const no = nextNo('CG')
        db.purchases.unshift({
          no, supplier, type: '订单采购', orders: [...this.picked], date: TODAY, due: addDays(TODAY, Math.max(...rows.map((r) => r.mat.lead || 7))), buyer: '吴佳', wh: rows.some((r) => r.mat.type === '面料' || r.mat.type === '里料') ? 'WH01' : 'WH02', status: '草稿', tax: 13,
          lines: rows.map((r) => ({ sku: r.sku, qty: mrpSuggest(r), price: round4((r.mat.price || 0) * (r.mat.rate || 1)), due: addDays(TODAY, r.mat.lead || 7), inQty: 0 })),
        })
        made.push(no)
      }
      ElMessage.success(`生成了 ${made.length} 张采购单（草稿）：${made.join('、')}`)
      go('/purchases?highlight=' + made.join(','))
    },
  },
  template: `
  <div class="page">
    <PageHeader title="算料" description="把订单按 BOM 展开成物料需求，扣掉这次要用的库存，得出每种物料要买多少。">
      <template #actions>
        <el-button icon="Document" @click="proto('保存成算料单 SL…，留底可追溯；订单变更后重算会和上次对比差异')">保存为算料单</el-button>
        <el-button type="primary" icon="ShoppingCart" :disabled="!toBuy.length" @click="generate">按供应商生成采购单（{{ Object.keys(bySupplier).length }} 张）</el-button>
      </template>
    </PageHeader>
    <section class="panel block">
      <div class="inline-actions" style="margin-bottom:8px">
        <span>算哪些订单</span>
        <el-select v-model="picked" multiple style="min-width:420px" placeholder="选已审核、生产中的订单">
          <el-option v-for="o in options" :key="o.no" :label="o.no + ' · ' + (custName(o.customer) === '—' ? '自营' : custName(o.customer)) + ' · ' + o.status" :value="o.no" />
        </el-select>
      </div>
      <p class="muted" style="margin:0;font-size:13px;line-height:1.8">
        毛需求 = 件数 × 单件用量（分码的按尺码取）；含损耗 = 毛需求 × (1 + 损耗率)；需求 = 含损耗 × (1 + 备料比)；<br>
        净需求 = 需求 − 本次用库存 − 本次用在途；建议采购 = 净需求换算成采购单位后向上取整，不到起订量按起订量。可用库存已扣掉其他算料单占用的；在途只作参考，默认不扣（免得一批在途被两张单重复用），要用就手填。
      </p>
    </section>
    <div v-for="i in result.issues" :key="i" class="callout danger" style="margin-top:12px">{{ i }}</div>

    <section class="panel" style="margin-top:16px">
      <div class="panel-title"><h2>要采购的物料（我方采购）</h2><span class="hint">勾掉的行不生成采购单。高亮行是 PRD 里的例子：卡其布</span></div>
      <div class="panel-body">
        <el-table :data="buyRows" size="small" :row-class-name="rowClass" empty-text="选的订单没有要我方采购的物料">
          <el-table-column width="42"><template #default="{ row }"><el-checkbox v-model="row.checked" /></template></el-table-column>
          <el-table-column label="物料 SKU / 用在" min-width="180"><template #default="{ row }"><span class="code">{{ row.sku }}</span> <span class="muted" style="font-size:12px">{{ row.parts }}</span><div style="font-size:12px">{{ skuLabel(row.sku) }}</div></template></el-table-column>
          <el-table-column label="毛需求" width="84" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.gross) }}</template></el-table-column>
          <el-table-column label="含损耗" width="88" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.withLoss) }}<div class="muted" style="font-size:12px">损耗 {{ row.loss }}%</div></template></el-table-column>
          <el-table-column label="备料比" width="80"><template #default="{ row }"><span style="display:inline-flex;align-items:center;gap:3px"><el-input-number v-model="row.prep" size="small" :min="0" :max="50" :controls="false" style="width:46px" />%</span></template></el-table-column>
          <el-table-column label="可用库存" width="84" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.available) }}</template></el-table-column>
          <el-table-column label="本次用库存" width="96"><template #default="{ row }"><el-input-number :model-value="use(row)" size="small" :min="0" :max="row.available" :controls="false" style="width:72px" @update:model-value="(v) => setUse(row, v)" /></template></el-table-column>
          <el-table-column label="本次用在途" width="96"><template #default="{ row }"><el-input-number v-model="row.useTransit" size="small" :min="0" :max="row.transit" :controls="false" style="width:72px" /><div class="muted" style="font-size:12px">在途 {{ fmtQ(row.transit) }}</div></template></el-table-column>
          <el-table-column label="净需求" width="96" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(net(row)) }} <span class="muted">{{ row.mat.unit }}</span></template></el-table-column>
          <el-table-column label="建议采购" width="110" align="right" class-name="num"><template #default="{ row }"><b v-if="suggest(row)">{{ fmt(suggest(row)) }}</b><span v-else class="ok-text">不用买</span> <span v-if="suggest(row)" class="muted">{{ row.mat.buyUnit }}</span></template></el-table-column>
          <el-table-column label="供应商" width="90"><template #default="{ row }">{{ supName(row.mat.supplier) }}</template></el-table-column>
        </el-table>
      </div>
    </section>

    <div class="columns" style="margin-top:16px;grid-template-columns:minmax(0,1fr) minmax(0,1fr)">
      <section class="panel">
        <div class="panel-title"><h2>客供料（找客户要）</h2><el-button size="small" @click="proto('导出客供料清单发给客户：要什么、要多少、什么时候前到')">导出给客户</el-button></div>
        <div class="panel-body">
          <el-table :data="customerRows" size="small" empty-text="没有客供料">
            <el-table-column label="物料" min-width="150"><template #default="{ row }">{{ skuLabel(row.sku) }}</template></el-table-column>
            <el-table-column label="货主" width="100"><template #default="{ row }">{{ ownerName(row.owner) }}</template></el-table-column>
            <el-table-column label="需求" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(need(row)) }}</template></el-table-column>
            <el-table-column label="已收到" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.available) }}</template></el-table-column>
            <el-table-column label="还差" width="80" align="right" class-name="num"><template #default="{ row }"><span :class="need(row) > row.available ? 'late-text' : 'ok-text'">{{ fmtQ(Math.max(0, need(row) - row.available)) }}</span></template></el-table-column>
          </el-table>
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><h2>加工厂自备（不采购）</h2><span class="hint">包在加工价里，只列出来对一下</span></div>
        <div class="panel-body">
          <el-table :data="factoryRows" size="small" empty-text="没有加工厂自备的料">
            <el-table-column label="物料" min-width="160"><template #default="{ row }">{{ skuLabel(row.sku) }}</template></el-table-column>
            <el-table-column label="需求" width="90" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(need(row)) }}</template></el-table-column>
          </el-table>
        </div>
      </section>
    </div>
    <ProtoNote sec="§6.6 物料（算料）">例子：卡其 400 件，大身 220g 斜纹棉分码用量 S2.25 M2.35 L2.45 XL2.55 XXL2.65 米 → 毛需求 962 米，损耗 3% → 990.86 米，库存 120 米 → 建议采购 871 米。</ProtoNote>
  </div>`,
})
Object.assign(MrpPage.methods, { ownerName })
