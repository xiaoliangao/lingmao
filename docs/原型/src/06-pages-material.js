/* ============================================================
 * 采购单、到料跟踪、入库、出库领料、库存、库存日志
 * ============================================================ */
function nowTime() {
  return TODAY + ' 15:' + String(10 + Math.floor(Math.random() * 49)).padStart(2, '0')
}

/* 一张单据的所有库存变动：先全部检查，够了再一起改（不允许负库存） */
function applyMoves(moves, docType, doc) {
  for (const m of moves) {
    if (m.delta >= 0) continue
    const r = db.stock.find((x) => x.wh === m.wh && x.sku === m.sku && x.owner === m.owner)
    const have = r ? r.qty : 0
    if (have + m.delta < -1e-6) throw new Error(`${whName(m.wh)} 的 ${skuLabel(m.sku)}（${ownerName(m.owner)}）只有 ${fmtQ(have)}，不够出 ${fmtQ(-m.delta)}`)
  }
  const at = nowTime()
  for (const m of moves) {
    let r = db.stock.find((x) => x.wh === m.wh && x.sku === m.sku && x.owner === m.owner)
    if (!r) {
      r = { wh: m.wh, sku: m.sku, owner: m.owner, qty: 0 }
      db.stock.push(r)
    }
    r.qty = round4(r.qty + m.delta)
    db.stockLog.unshift({ at, docType: m.type || docType, doc, wh: m.wh, sku: m.sku, owner: m.owner, change: m.delta, after: r.qty, by: db.me.name })
  }
}

function purchaseRemain(p) {
  return p.lines.reduce((a, l) => a + Math.max(0, l.qty - l.inQty), 0)
}

function purchaseRate(p) {
  const total = p.lines.reduce((a, l) => a + l.qty, 0)
  return total ? Math.round((p.lines.reduce((a, l) => a + Math.min(l.inQty, l.qty), 0) / total) * 100) : 0
}

/* 一张加工单要发给工厂的料（清加工：我方采购 + 客供；包工包料：只有客供） */
function prodNeeds(p) {
  const pack = packOf(p.style, p.pack) || effectivePack(p.style)
  const order = orderOf(p.order)
  const map = new Map()
  if (!pack) return []
  for (const [color, sizes] of Object.entries(p.qty))
    for (const [size, q] of Object.entries(sizes))
      for (const b of pack.bom) {
        if (b.source === '加工厂自备') continue
        if (p.mode === '包工包料' && b.source !== '客供') continue
        const sku = b.colorMode === '随款色' ? b.colorMap[color] : b.fixedSku
        if (!sku) continue
        const owner = b.source === '客供' ? order?.customer || '自有' : '自有'
        const u = b.qtyMode === '分码' ? b.usage[size] : b.usage
        const k = sku + '|' + owner
        const r = map.get(k) || { sku, owner, need: 0 }
        r.need += q * u * (1 + b.loss / 100)
        map.set(k, r)
      }
  const issued = db.stockOuts.filter((x) => x.src === p.no && x.type === '领料出库' && x.status === '已审核').flatMap((x) => x.lines)
  return [...map.values()].map((r) => {
    const done = issued.filter((l) => l.sku === r.sku && l.owner === r.owner).reduce((a, l) => a + l.qty, 0)
    const need = Math.ceil(round4(r.need))
    const available = db.stock.filter((s) => s.sku === r.sku && s.owner === r.owner && whOf(s.wh)?.type === '物料仓').reduce((a, s) => a + s.qty, 0)
    return { ...r, need, issued: done, rest: Math.max(0, need - done), available }
  })
}

const PurchaseListPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: '全部' }),
  computed: {
    highlight() {
      return (this.query.highlight || '').split(',')
    },
    list() {
      return db.purchases.filter((p) => this.tab === '全部' || p.status === this.tab)
    },
    counts() {
      const c = { 全部: db.purchases.length }
      for (const p of db.purchases) c[p.status] = (c[p.status] || 0) + 1
      return c
    },
  },
  methods: {
    amount: (p) => p.lines.reduce((a, l) => a + l.qty * l.price, 0),
    rate: purchaseRate,
    rowClass({ row }) {
      return this.highlight.includes(row.no) ? 'example-row' : ''
    },
  },
  template: `
  <div class="page">
    <PageHeader title="采购单" description="对供应商下单。可以分批到货：每次入库回写「已入库」，到齐自动变「已到齐」。">
      <template #actions>
        <el-button icon="Histogram" @click="go('/mrp')">从算料生成</el-button>
        <el-button type="primary" icon="Plus" @click="proto('手工新建采购单，备货采购、打样采购用')">新建采购单</el-button>
      </template>
    </PageHeader>
    <div v-if="highlight[0]" class="callout info">刚从算料生成的采购单已高亮，状态是草稿：核对数量、单价后提交审核。</div>
    <section class="panel">
      <el-tabs v-model="tab" class="status-tabs">
        <el-tab-pane v-for="t in ['全部','草稿','待审核','已审核','部分到货','已到齐']" :key="t" :name="t" :label="t + ' ' + (counts[t] || 0)" />
      </el-tabs>
      <div class="panel-body">
        <el-table :data="list" :row-class-name="rowClass" empty-text="这个状态下没有采购单">
          <el-table-column label="采购单号" width="150"><template #default="{ row }"><a class="link code" @click="go('/purchases/' + row.no)">{{ row.no }}</a></template></el-table-column>
          <el-table-column label="供应商" width="100"><template #default="{ row }">{{ supName(row.supplier) }}</template></el-table-column>
          <el-table-column prop="type" label="类型" width="86" />
          <el-table-column label="关联订单" width="150"><template #default="{ row }"><span v-if="!row.orders.length" class="muted">—</span><a v-for="o in row.orders" :key="o" class="link code" @click="go('/orders/' + o)">{{ o }}</a></template></el-table-column>
          <el-table-column label="物料" min-width="220"><template #default="{ row }">{{ skuLabel(row.lines[0].sku) }}<span v-if="row.lines.length > 1" class="muted"> 等 {{ row.lines.length }} 行</span></template></el-table-column>
          <el-table-column label="金额" width="110" align="right" class-name="num"><template #default="{ row }">{{ money(amount(row)) }}</template></el-table-column>
          <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }"><span :class="row.due < TODAY && purchaseRemain(row) > 0 && !['草稿','待审核'].includes(row.status) ? 'late-text' : ''">{{ row.due }}</span></template></el-table-column>
          <el-table-column label="到货" width="110"><template #default="{ row }"><el-progress :percentage="rate(row)" :stroke-width="6" /></template></el-table-column>
          <el-table-column label="状态" width="86"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.6 物料（采购单）">单头一个供应商；明细每行一个物料 SKU，可以有各自的交期。送货地点可以选工厂仓（面料直发加工厂）。</ProtoNote>
  </div>`,
})
Object.assign(PurchaseListPage.methods, { purchaseRemain })

const PurchaseDetailPage = page({
  props: ['params', 'query'],
  computed: {
    p() {
      return purchaseOf(this.params.id)
    },
    ins() {
      return db.stockIns.filter((x) => x.src === this.p.no)
    },
    amount() {
      return this.p.lines.reduce((a, l) => a + l.qty * l.price, 0)
    },
  },
  methods: {
    unit(sku) {
      return skuInfo(sku).mat?.buyUnit
    },
    submit() {
      this.p.status = '待审核'
      ElMessage.success('已提交审核')
    },
    audit() {
      this.p.status = '已审核'
      ElMessage.success('审核通过，现在算「在途」了；可以打印或发给供应商')
    },
    receiveAll() {
      const lines = this.p.lines.filter((l) => l.qty > l.inQty)
      const no = nextNo('RK')
      const moves = lines.map((l) => ({ wh: this.p.wh, sku: l.sku, owner: '自有', delta: round4((l.qty - l.inQty) * (skuInfo(l.sku).mat?.rate || 1)) }))
      try {
        applyMoves(moves, '采购入库', no)
      } catch (e) {
        return ElMessage.error(e.message)
      }
      db.stockIns.unshift({ no, type: '采购入库', wh: this.p.wh, supplier: this.p.supplier, customer: '', src: this.p.no, date: TODAY, status: '已审核', by: db.me.name, lines: moves.map((m) => ({ sku: m.sku, qty: m.delta, lot: '' })) })
      lines.forEach((l) => (l.inQty = l.qty))
      this.p.status = '已到齐'
      ElMessage.success(`入库单 ${no} 已审核：库存增加、库存日志已写、采购单「已入库」已回写`)
    },
    close() {
      ElMessageBox.confirm('结案后剩下没到的数量不再等，也不再算在途。', '采购单结案', { confirmButtonText: '结案', cancelButtonText: '取消' })
        .then(() => {
          this.p.status = '已结案'
          ElMessage.success('已结案')
        })
        .catch(() => {})
    },
    purchaseRemain,
  },
  template: `
  <div class="page" v-if="p">
    <PageHeader :title="'采购单 ' + p.no" :description="supName(p.supplier) + ' · ' + p.type + ' · 采购员 ' + p.buyer">
      <template #title-extra><StatusTag :status="p.status" /></template>
      <template #actions>
        <el-button icon="Printer" @click="proto('打印或导出 PDF 发给供应商；二期供应商有账号后在门户里确认')">打印</el-button>
        <el-button v-if="p.status === '草稿'" type="primary" @click="submit">提交审核</el-button>
        <el-button v-if="p.status === '待审核'" type="primary" @click="audit">审核通过</el-button>
        <template v-if="['已审核','部分到货'].includes(p.status)">
          <el-button @click="close">结案</el-button>
          <el-button @click="go('/stock-in')">部分入库</el-button>
          <el-button type="primary" icon="Download" @click="receiveAll">剩下的一次入库</el-button>
        </template>
      </template>
    </PageHeader>
    <FlowBar :steps="['草稿','待审核','已审核','部分到货','已到齐']" :current="p.status" />
    <section class="panel block">
      <dl class="desc-grid">
        <div><dt>供应商</dt><dd>{{ supName(p.supplier) }}</dd></div>
        <div><dt>采购类型</dt><dd>{{ p.type }}</dd></div>
        <div><dt>关联订单</dt><dd><a v-for="o in p.orders" :key="o" class="link code" @click="go('/orders/' + o)">{{ o }}</a><span v-if="!p.orders.length">—</span></dd></div>
        <div><dt>下单 / 交期</dt><dd class="num">{{ p.date }} / {{ p.due }}</dd></div>
        <div><dt>送货到</dt><dd>{{ whName(p.wh) }}</dd></div>
        <div><dt>税率</dt><dd class="num">{{ p.tax }}%</dd></div>
        <div><dt>金额（含税）</dt><dd class="num">{{ money(amount) }}</dd></div>
        <div><dt>未到</dt><dd class="num">{{ fmtQ(purchaseRemain(p)) }}</dd></div>
      </dl>
    </section>
    <section class="panel" style="margin-top:16px"><div class="panel-body">
      <el-table :data="p.lines" size="small">
        <el-table-column label="物料 SKU" min-width="200"><template #default="{ row }"><span class="code">{{ row.sku }}</span><div style="font-size:12px">{{ skuLabel(row.sku) }}</div></template></el-table-column>
        <el-table-column label="供应商货号 / 色号" width="150"><template #default="{ row }"><span class="code">{{ skuInfo(row.sku).mat?.supplierNo }}</span> <span class="muted">{{ skuInfo(row.sku).sku?.colorNo }}</span></template></el-table-column>
        <el-table-column label="数量" width="100" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.qty) }} {{ unit(row.sku) }}</template></el-table-column>
        <el-table-column label="单价" width="80" align="right" class-name="num"><template #default="{ row }">{{ money(row.price) }}</template></el-table-column>
        <el-table-column label="金额" width="110" align="right" class-name="num"><template #default="{ row }">{{ money(row.qty * row.price) }}</template></el-table-column>
        <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }">{{ row.due }}</template></el-table-column>
        <el-table-column label="已入库" width="90" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.inQty) }}</template></el-table-column>
        <el-table-column label="未入库" width="110" align="right" class-name="num"><template #default="{ row }"><span :class="row.qty > row.inQty && row.due < TODAY && !['草稿','待审核'].includes(p.status) ? 'late-text' : ''">{{ fmtQ(Math.max(0, row.qty - row.inQty)) }}<template v-if="row.qty > row.inQty && row.due < TODAY && !['草稿','待审核'].includes(p.status)"> · 逾期 {{ -daysLeft(row.due) }} 天</template></span></template></el-table-column>
      </el-table>
      <h4 style="margin:18px 0 8px">入库记录</h4>
      <el-table :data="ins" size="small" empty-text="还没有入库">
        <el-table-column prop="no" label="入库单" width="150" class-name="code" />
        <el-table-column prop="date" label="日期" width="100" class-name="num" />
        <el-table-column label="内容" min-width="240"><template #default="{ row }">{{ row.lines.map((l) => skuLabel(l.sku) + ' ' + fmtQ(l.qty) + (l.lot ? '（缸号 ' + l.lot + '）' : '')).join('；') }}</template></el-table-column>
        <el-table-column label="状态" width="86"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
      </el-table>
    </div></section>
    <ProtoNote sec="§6.6 物料（采购单）">审核后才算「在途」；算料时在途只作参考，要用得手填。入库按采购单位 × 换算系数记成库存单位。</ProtoNote>
  </div>
  <div v-else class="page"><BlankState title="没有这张采购单" description="单号可能写错了。"><el-button @click="go('/purchases')">回到采购单</el-button></BlankState></div>`,
})

const ArrivalsPage = page({
  props: ['params', 'query'],
  data: () => ({ only: '未到' }),
  computed: {
    rows() {
      return db.purchases
        .filter((p) => ['已审核', '部分到货', '已到齐'].includes(p.status))
        .flatMap((p) => p.lines.map((l) => ({ p, l, rest: Math.max(0, l.qty - l.inQty), left: daysLeft(l.due) })))
        .filter((r) => this.only === '全部' || (this.only === '未到' && r.rest > 0) || (this.only === '逾期' && r.rest > 0 && r.left < 0))
        .sort((a, b) => a.left - b.left)
    },
    stats() {
      const open = db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status)).flatMap((p) => p.lines).filter((l) => l.qty > l.inQty)
      return { open: open.length, late: open.filter((l) => l.due < TODAY).length, soon: open.filter((l) => l.due >= TODAY && daysLeft(l.due) <= 3).length }
    },
  },
  template: `
  <div class="page">
    <PageHeader title="到料跟踪" description="按采购明细看：哪些料还没到、过了交期几天。缺料会卡住加工单「到料」节点。" />
    <div class="kpis" style="grid-template-columns:repeat(3,minmax(0,1fr))">
      <div class="kpi"><small>还没到齐的明细</small><strong>{{ stats.open }}</strong></div>
      <div class="kpi alert"><small>已过交期</small><strong>{{ stats.late }}</strong></div>
      <div class="kpi warn"><small>3 天内到期</small><strong>{{ stats.soon }}</strong></div>
    </div>
    <section class="panel">
      <el-tabs v-model="only" class="status-tabs"><el-tab-pane v-for="t in ['未到','逾期','全部']" :key="t" :name="t" :label="t" /></el-tabs>
      <div class="panel-body">
        <el-table :data="rows" empty-text="没有要跟的料，都到齐了">
          <el-table-column label="采购单 / 关联订单" width="150"><template #default="{ row }"><a class="link code" @click="go('/purchases/' + row.p.no)">{{ row.p.no }}</a><div class="muted code" style="font-size:12px">{{ row.p.orders[0] || '备货采购' }}</div></template></el-table-column>
          <el-table-column label="供应商" width="100"><template #default="{ row }">{{ supName(row.p.supplier) }}</template></el-table-column>
          <el-table-column label="物料" min-width="190"><template #default="{ row }"><span class="code">{{ row.l.sku }}</span><div style="font-size:12px">{{ skuLabel(row.l.sku) }}</div></template></el-table-column>
          <el-table-column label="采购" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.l.qty) }}</template></el-table-column>
          <el-table-column label="已到" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.l.inQty) }}</template></el-table-column>
          <el-table-column label="未到" width="80" align="right" class-name="num"><template #default="{ row }"><b>{{ fmtQ(row.rest) }}</b></template></el-table-column>
          <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }">{{ row.l.due }}</template></el-table-column>
          <el-table-column label="情况" width="110"><template #default="{ row }">
            <StatusTag v-if="!row.rest" tone="done">已到齐</StatusTag>
            <StatusTag v-else-if="row.left < 0" tone="danger">逾期 {{ -row.left }} 天</StatusTag>
            <StatusTag v-else-if="row.left <= 3" tone="pending">还剩 {{ row.left }} 天</StatusTag>
            <span v-else class="muted">还剩 {{ row.left }} 天</span>
          </template></el-table-column>
          <el-table-column prop="p.buyer" label="采购员" width="76" />
          <el-table-column label="操作" width="160"><template #default="{ row }">
            <el-button v-if="row.rest" link type="primary" @click="proto('记一条催料：联系了谁、对方承诺哪天发')">催料</el-button>
            <el-button v-if="row.rest" link type="primary" @click="go('/stock-in')">登记到货</el-button>
          </template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.6 物料（到料跟踪）">一期：逾期标红、首页待办提醒。二期：按订单汇总「齐料率」，缺哪种料一眼看到。</ProtoNote>
  </div>`,
})

const StockInPage = page({
  props: ['params', 'query'],
  data: () => ({ creating: false, form: { type: '采购入库', src: '', wh: 'WH01', customer: 'C001', lines: [] } }),
  computed: {
    openPurchases() {
      return db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status))
    },
  },
  watch: {
    'form.src'(no) {
      const p = purchaseOf(no)
      if (!p) return
      this.form.wh = p.wh
      this.form.lines = p.lines.filter((l) => l.qty > l.inQty).map((l) => ({ sku: l.sku, plan: l.qty - l.inQty, qty: round4((l.qty - l.inQty) * (skuInfo(l.sku).mat?.rate || 1)), lot: '', pLine: l }))
    },
    'form.type'(t) {
      this.form.src = ''
      this.form.lines = t === '客供入库' ? [{ sku: 'BQ0003-HE', plan: null, qty: 1212, lot: '' }] : []
    },
  },
  methods: {
    open() {
      this.form = { type: '采购入库', src: '', wh: 'WH01', customer: 'C001', lines: [] }
      this.creating = true
    },
    save(audit) {
      if (!this.form.lines.length) return ElMessage.warning('没有要入库的物料')
      const no = nextNo('RK')
      const owner = this.form.type === '客供入库' ? this.form.customer : '自有'
      const doc = { no, type: this.form.type, wh: this.form.wh, supplier: purchaseOf(this.form.src)?.supplier || '', customer: this.form.type === '客供入库' ? this.form.customer : '', src: this.form.src, date: TODAY, status: '草稿', by: db.me.name, lines: this.form.lines.map((l) => ({ sku: l.sku, qty: l.qty, lot: l.lot })) }
      if (audit) {
        try {
          applyMoves(this.form.lines.map((l) => ({ wh: this.form.wh, sku: l.sku, owner, delta: l.qty })), this.form.type, no)
        } catch (e) {
          return ElMessage.error(e.message)
        }
        doc.status = '已审核'
        const p = purchaseOf(this.form.src)
        if (p) {
          this.form.lines.forEach((l) => (l.pLine.inQty = round4(l.pLine.inQty + l.qty / (skuInfo(l.sku).mat?.rate || 1))))
          p.status = purchaseRemain(p) > 0 ? '部分到货' : '已到齐'
        }
      }
      db.stockIns.unshift(doc)
      this.creating = false
      ElMessage.success(audit ? `入库单 ${no} 已审核：库存已加，采购单已回写` : `入库单 ${no} 已保存为草稿`)
    },
    auditDoc(d) {
      const owner = d.type === '客供入库' ? d.customer : '自有'
      try {
        applyMoves(d.lines.map((l) => ({ wh: d.wh, sku: l.sku, owner, delta: l.qty })), d.type, d.no)
      } catch (e) {
        return ElMessage.error(e.message)
      }
      d.status = '已审核'
      ElMessage.success('已审核，库存已加')
    },
  },
  template: `
  <div class="page">
    <PageHeader title="入库单" description="采购入库、客供入库、其他入库。审核即过账：库存增加、写库存日志、回写采购单。">
      <template #actions><el-button type="primary" icon="Plus" @click="open">新建入库单</el-button></template>
    </PageHeader>
    <section class="panel"><div class="panel-body">
      <el-table :data="db.stockIns" empty-text="还没有入库单">
        <el-table-column prop="no" label="入库单号" width="150" class-name="code" />
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column label="仓库" width="110"><template #default="{ row }">{{ whName(row.wh) }}</template></el-table-column>
        <el-table-column label="供应商 / 客户" width="110"><template #default="{ row }">{{ row.supplier ? supName(row.supplier) : row.customer ? '客供 · ' + custName(row.customer) : '—' }}</template></el-table-column>
        <el-table-column label="来源单" width="150"><template #default="{ row }"><a v-if="row.src && row.src.startsWith('CG')" class="link code" @click="go('/purchases/' + row.src)">{{ row.src }}</a><span v-else class="code">{{ row.src || '—' }}</span></template></el-table-column>
        <el-table-column label="内容" min-width="220"><template #default="{ row }">{{ linesBrief(row.lines) }}</template></el-table-column>
        <el-table-column prop="date" label="日期" width="100" class-name="num" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button v-if="row.status === '草稿'" link type="primary" @click="auditDoc(row)">审核</el-button></template></el-table-column>
      </el-table>
    </div></section>

    <el-dialog v-model="creating" title="新建入库单" width="820px">
      <el-form label-width="80px" inline>
        <el-form-item label="类型"><el-select v-model="form.type" style="width:130px"><el-option v-for="t in ['采购入库','客供入库','其他入库']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item v-if="form.type === '采购入库'" label="采购单"><el-select v-model="form.src" placeholder="选还没到齐的采购单" style="width:260px"><el-option v-for="p in openPurchases" :key="p.no" :label="p.no + ' · ' + supName(p.supplier)" :value="p.no" /></el-select></el-form-item>
        <el-form-item v-if="form.type === '客供入库'" label="客户"><el-select v-model="form.customer" style="width:160px"><el-option v-for="c in db.customers" :key="c.id" :label="c.short" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="仓库"><el-select v-model="form.wh" style="width:180px"><el-option v-for="w in db.warehouses.filter((x) => x.type !== '成品仓')" :key="w.code" :label="w.name" :value="w.code" /></el-select></el-form-item>
      </el-form>
      <el-table :data="form.lines" size="small" empty-text="选了采购单以后，没到的物料会列在这里">
        <el-table-column label="物料" min-width="200"><template #default="{ row }"><span class="code">{{ row.sku }}</span> {{ skuLabel(row.sku) }}</template></el-table-column>
        <el-table-column label="采购未到" width="100" align="right" class-name="num"><template #default="{ row }">{{ row.plan === null ? '—' : fmtQ(row.plan) }}</template></el-table-column>
        <el-table-column label="本次入库" width="130"><template #default="{ row }"><el-input-number v-model="row.qty" size="small" :min="0" :controls="false" style="width:100px" /></template></el-table-column>
        <el-table-column label="缸号 / 批次" width="160"><template #default="{ row }"><el-input v-model="row.lot" size="small" placeholder="面料建议填" /></template></el-table-column>
      </el-table>
      <p class="muted" style="font-size:12px;margin:10px 0 0">一期记缸号但库存不按缸号分开；要不要按缸号管库存是待决问题。客供入库的料，库存的「物权」记在客户名下，不会被别的订单算料用掉。</p>
      <template #footer><el-button @click="creating = false">取消</el-button><el-button @click="save(false)">存草稿</el-button><el-button type="primary" @click="save(true)">保存并审核</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.6 物料（出入库）">PDA 扫码入库放三期；聚水潭只管成品，物料仓在本系统（待决问题 Q01 确认）。</ProtoNote>
  </div>`,
})

const StockOutPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: '全部', picking: false, pickJg: '', pickLines: [] }),
  computed: {
    list() {
      return db.stockOuts.filter((x) => this.tab === '全部' || x.type === this.tab)
    },
    openJgs() {
      return db.production.filter((p) => p.status !== '已完成')
    },
  },
  watch: {
    pickJg(no) {
      const p = prodOf(no)
      this.pickLines = p ? prodNeeds(p).map((r) => ({ ...r, qty: Math.min(r.rest, r.available) })) : []
    },
  },
  methods: {
    openPick() {
      this.pickJg = ''
      this.pickLines = []
      this.picking = true
    },
    savePick() {
      const p = prodOf(this.pickJg)
      if (!p) return ElMessage.warning('先选加工单')
      const lines = this.pickLines.filter((l) => l.qty > 0)
      if (!lines.length) return ElMessage.warning('没有要发的料')
      const to = db.warehouses.find((w) => w.factory === p.factory)?.code
      const no = nextNo('LL')
      const moves = []
      for (const l of lines) {
        let rest = l.qty
        for (const s of db.stock.filter((x) => x.sku === l.sku && x.owner === l.owner && whOf(x.wh)?.type === '物料仓' && x.qty > 0)) {
          const take = Math.min(rest, s.qty)
          if (take <= 0) continue
          moves.push({ wh: s.wh, sku: l.sku, owner: l.owner, delta: -take, type: '领料出库' })
          rest = round4(rest - take)
        }
        if (rest > 0) return ElMessage.error(`${skuLabel(l.sku)} 库存不够，还差 ${fmtQ(rest)}`)
        moves.push({ wh: to, sku: l.sku, owner: l.owner, delta: l.qty, type: '领料入库' })
      }
      try {
        applyMoves(moves, '领料出库', no)
      } catch (e) {
        return ElMessage.error(e.message)
      }
      db.stockOuts.unshift({ no, type: '领料出库', from: moves[0].wh, to, src: p.no, date: TODAY, status: '已审核', by: db.me.name, lines: lines.map((l) => ({ sku: l.sku, qty: l.qty, owner: l.owner })) })
      this.picking = false
      ElMessage.success(`领料单 ${no} 已审核：料从物料仓转到 ${whName(to)}`)
    },
    srcLink(row) {
      if (row.src.startsWith('JG')) go('/production/' + row.src)
      else if (row.src.startsWith('CG')) go('/purchases/' + row.src)
    },
  },
  template: `
  <div class="page">
    <PageHeader title="出库领料单" description="领料（发料给工厂）、退料（余料回仓）、调拨、采购退货、其他出库。都是库存搬家，审核即过账。">
      <template #actions>
        <el-button @click="proto('新建退料（工厂仓退回物料仓）、调拨（仓库之间搬）、采购退货（出库并回写采购单）')">新建退料 / 调拨 / 退货</el-button>
        <el-button type="primary" icon="Plus" @click="openPick">按加工单领料</el-button>
      </template>
    </PageHeader>
    <section class="panel">
      <el-tabs v-model="tab" class="status-tabs"><el-tab-pane v-for="t in ['全部','领料出库','退料入库','调拨','采购退货']" :key="t" :name="t" :label="t" /></el-tabs>
      <div class="panel-body">
        <el-table :data="list" empty-text="这一类还没有单据">
          <el-table-column prop="no" label="单号" width="150" class-name="code" />
          <el-table-column prop="type" label="类型" width="90" />
          <el-table-column label="从 → 到" min-width="220"><template #default="{ row }">{{ whName(row.from) }} → {{ row.to ? whName(row.to) : '退回供应商' }}</template></el-table-column>
          <el-table-column label="来源单" width="150"><template #default="{ row }"><a v-if="row.src" class="link code" @click="srcLink(row)">{{ row.src }}</a><span v-else class="muted">—</span></template></el-table-column>
          <el-table-column label="内容" min-width="240"><template #default="{ row }">{{ linesBrief(row.lines) }}</template></el-table-column>
          <el-table-column prop="date" label="日期" width="100" class-name="num" />
          <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        </el-table>
      </div>
    </section>

    <el-dialog v-model="picking" title="按加工单领料（发料给工厂）" width="900px">
      <el-form inline label-width="70px">
        <el-form-item label="加工单"><el-select v-model="pickJg" placeholder="选加工单" style="width:340px"><el-option v-for="p in openJgs" :key="p.no" :label="p.no + ' · ' + styleOf(p.style).name + ' · ' + supName(p.factory) + ' · ' + p.mode" :value="p.no" /></el-select></el-form-item>
      </el-form>
      <div v-if="pickJg" class="callout info">应领 = 加工单数量 × BOM 用量 × (1 + 损耗)，按资料包 {{ prodOf(pickJg).pack }} 算；减掉已经领过的。{{ prodOf(pickJg).mode === '包工包料' ? '包工包料：只发客供料。' : '' }}料会从物料仓转到「{{ whName(db.warehouses.find((w) => w.factory === prodOf(pickJg).factory)?.code) }}」。</div>
      <el-table :data="pickLines" size="small" empty-text="选了加工单以后，要发的料会列在这里">
        <el-table-column label="物料" min-width="200"><template #default="{ row }"><span class="code">{{ row.sku }}</span> {{ skuLabel(row.sku) }}</template></el-table-column>
        <el-table-column label="物权" width="110"><template #default="{ row }">{{ ownerName(row.owner) }}</template></el-table-column>
        <el-table-column label="应领" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.need) }}</template></el-table-column>
        <el-table-column label="已领" width="80" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.issued) }}</template></el-table-column>
        <el-table-column label="仓里有" width="80" align="right" class-name="num"><template #default="{ row }"><span :class="row.available < row.rest ? 'late-text' : ''">{{ fmtQ(row.available) }}</span></template></el-table-column>
        <el-table-column label="本次发" width="120"><template #default="{ row }"><el-input-number v-model="row.qty" size="small" :min="0" :max="row.available" :controls="false" style="width:90px" /></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="picking = false">取消</el-button><el-button type="primary" @click="savePick">保存并审核</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.6 物料（出入库）">领料不是“出库消失”，而是把料搬进工厂仓：工厂手上有多少料随时查得到。二期按回货件数自动核销（工厂料账平衡）。</ProtoNote>
  </div>`,
})
Object.assign(StockOutPage.methods, { prodOf, ownerName })

const StockPage = page({
  props: ['params', 'query'],
  data: () => ({ wh: '全部', owner: '', keyword: '' }),
  computed: {
    rows() {
      return db.stock
        .filter((r) => (this.wh === '全部' || r.wh === this.wh) && (!this.owner || (this.owner === '自有' ? r.owner === '自有' : r.owner !== '自有')) && (!this.keyword || (r.sku + skuLabel(r.sku)).includes(this.keyword)))
        .map((r) => {
          const { mat, sku } = skuInfo(r.sku)
          return { ...r, mat, s: sku, last: db.stockLog.find((l) => l.wh === r.wh && l.sku === r.sku)?.at || '' }
        })
    },
  },
  methods: {
    isFactory(code) {
      return whOf(code)?.type === '工厂仓'
    },
    inTransit,
  },
  template: `
  <div class="page">
    <PageHeader title="库存" description="按 仓库 + 物料 SKU + 物权 记。物权 = 料是谁的：自有，还是客户提供的（客供）。">
      <template #actions><el-button icon="Download" @click="proto('导出当前筛选结果')">导出</el-button></template>
    </PageHeader>
    <section class="panel">
      <el-tabs v-model="wh" class="status-tabs">
        <el-tab-pane name="全部" label="全部仓库" />
        <el-tab-pane v-for="w in db.warehouses.filter((x) => x.type !== '成品仓')" :key="w.code" :name="w.code" :label="w.name" />
      </el-tabs>
      <el-form class="filter-bar" inline @submit.prevent>
        <el-form-item label="物料"><el-input v-model="keyword" clearable placeholder="SKU、名称" /></el-form-item>
        <el-form-item label="物权"><el-select v-model="owner" clearable placeholder="全部" style="width:110px"><el-option label="自有" value="自有" /><el-option label="客供" value="客供" /></el-select></el-form-item>
      </el-form>
      <div class="panel-body">
        <div v-if="wh !== '全部' && isFactory(wh)" class="callout info">这是工厂仓：数字 = 发给 {{ supName(whOf(wh).factory) }} 还没核销的料。二期按回货件数 × BOM 自动核销，剩下的就是工厂该退回或该解释的料。</div>
        <el-table :data="rows" empty-text="这个仓库里没有库存">
          <el-table-column label="仓库" width="150"><template #default="{ row }">{{ whName(row.wh) }}<span v-if="isFactory(row.wh)" class="mode-tag accent" style="margin-left:4px">工厂</span></template></el-table-column>
          <el-table-column label="物料 SKU" width="120" class-name="code"><template #default="{ row }">{{ row.sku }}</template></el-table-column>
          <el-table-column label="名称 / 颜色规格" min-width="190"><template #default="{ row }">{{ skuLabel(row.sku) }}<div class="muted" style="font-size:12px">{{ row.s?.colorNo }}</div></template></el-table-column>
          <el-table-column label="物权" width="130"><template #default="{ row }"><span class="mode-tag" :class="{ accent: row.owner !== '自有' }">{{ ownerName(row.owner) }}</span></template></el-table-column>
          <el-table-column label="数量" width="120" align="right" class-name="num"><template #default="{ row }"><b>{{ fmtQ(row.qty) }}</b> {{ row.mat?.unit }}</template></el-table-column>
          <el-table-column label="安全库存" width="90" align="right" class-name="num"><template #default="{ row }"><span v-if="!isFactory(row.wh) && row.s?.safety" :class="row.qty < row.s.safety ? 'late-text' : ''">{{ row.s.safety }}</span><span v-else class="muted">—</span></template></el-table-column>
          <el-table-column label="在途" width="80" align="right" class-name="num"><template #default="{ row }">{{ isFactory(row.wh) ? '—' : fmtQ(inTransit(row.sku)) }}</template></el-table-column>
          <el-table-column label="最近变动" width="140" class-name="num"><template #default="{ row }">{{ row.last || '—' }}</template></el-table-column>
          <el-table-column label="" width="80"><template #default="{ row }"><el-button link type="primary" @click="go('/stock-log?sku=' + row.sku)">流水</el-button></template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.6 物料（库存）">库存不允许负数：出库时数不够整张单不过账，并提示哪一行差多少。货位、按缸号分库存放二期。</ProtoNote>
  </div>`,
})
Object.assign(StockPage.methods, { whOf })

const StockLogPage = page({
  props: ['params', 'query'],
  data() {
    return { keyword: this.query.sku || '', type: '' }
  },
  computed: {
    rows() {
      return db.stockLog.filter((l) => (!this.keyword || (l.sku + l.doc + skuLabel(l.sku)).includes(this.keyword)) && (!this.type || l.docType === this.type))
    },
    types() {
      return [...new Set(db.stockLog.map((l) => l.docType))]
    },
  },
  template: `
  <div class="page">
    <PageHeader title="库存日志" description="每一次库存变化一条：哪张单、哪个仓、加了还是减了、变完剩多少。只能看，不能改。" />
    <section class="panel">
      <el-form class="filter-bar" inline @submit.prevent>
        <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="SKU、单号" /></el-form-item>
        <el-form-item label="单据类型"><el-select v-model="type" clearable placeholder="全部"><el-option v-for="t in types" :key="t" :label="t" :value="t" /></el-select></el-form-item>
      </el-form>
      <div class="panel-body">
        <el-table :data="rows" empty-text="没有符合条件的库存变动">
          <el-table-column prop="at" label="时间" width="140" class-name="num" />
          <el-table-column prop="docType" label="单据类型" width="90" />
          <el-table-column prop="doc" label="单号" width="150" class-name="code" />
          <el-table-column label="仓库" width="150"><template #default="{ row }">{{ whName(row.wh) }}</template></el-table-column>
          <el-table-column label="物料" min-width="200"><template #default="{ row }"><span class="code">{{ row.sku }}</span> {{ skuLabel(row.sku) }}</template></el-table-column>
          <el-table-column label="物权" width="110"><template #default="{ row }">{{ ownerName(row.owner) }}</template></el-table-column>
          <el-table-column label="变动" width="90" align="right" class-name="num"><template #default="{ row }"><span :class="row.change > 0 ? 'ok-text' : 'late-text'">{{ row.change > 0 ? '+' : '' }}{{ fmtQ(row.change) }}</span></template></el-table-column>
          <el-table-column label="变动后" width="90" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.after) }}</template></el-table-column>
          <el-table-column prop="by" label="操作人" width="76" />
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.6 物料（库存日志）">日志和库存在同一个事务里写，对不上就说明有 bug。</ProtoNote>
  </div>`,
})
