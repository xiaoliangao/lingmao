/* ============================================================
 * 外发加工单、生产看板、手机报工
 * ============================================================ */
const NODE_STATE_TEXT = { done: '已完成', 'done-late': '已完成（晚了）', doing: '进行中', late: '延期', todo: '未开始' }
const NODE_STATE_TONE = { done: 'done', 'done-late': 'done', doing: 'doing', late: 'danger', todo: 'off' }

function prodStatusAfterReport(p) {
  if (p.status === '已下单') p.status = '生产中'
}

const ProductionListPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: '全部' }),
  computed: {
    list() {
      return db.production.filter((p) => this.tab === '全部' || p.status === this.tab)
    },
  },
  methods: {
    cur: prodCurrentNode,
    late: prodLate,
    progress: prodProgress,
    received: prodReceived,
    nodeState,
    openIssues: (p) => p.issues.filter((i) => i.status !== '已关闭').length,
  },
  template: `
  <div class="page">
    <PageHeader title="外发加工单" description="对加工厂下的单：清加工（料我们发）、包工包料（成品采购，料工厂自备）、半包。从订单「排单」生成。">
      <template #actions><el-button icon="Share" @click="go('/orders')">去订单排单</el-button></template>
    </PageHeader>
    <section class="panel">
      <el-tabs v-model="tab" class="status-tabs"><el-tab-pane v-for="t in ['全部','已下单','生产中','部分回货','已完成']" :key="t" :name="t" :label="t" /></el-tabs>
      <div class="panel-body">
        <el-table :data="list" empty-text="这个状态下没有加工单">
          <el-table-column label="加工单号" width="150"><template #default="{ row }"><a class="link code" @click="go('/production/' + row.no)">{{ row.no }}</a></template></el-table-column>
          <el-table-column label="款式" min-width="160"><template #default="{ row }"><div style="display:flex;gap:8px;align-items:center"><GarmentThumb :shape="styleOf(row.style).shape" :color="Object.keys(row.qty)[0]" :size="34" /><div><span class="code">{{ row.style }}</span><div style="font-size:12px">{{ styleOf(row.style).name }}</div></div></div></template></el-table-column>
          <el-table-column label="加工厂" width="96"><template #default="{ row }">{{ supName(row.factory) }}</template></el-table-column>
          <el-table-column prop="mode" label="方式" width="72" />
          <el-table-column label="数量" width="70" align="right" class-name="num"><template #default="{ row }">{{ fmt(matrixTotal(row.qty)) }}</template></el-table-column>
          <el-table-column label="加工价" width="76" align="right" class-name="num"><template #default="{ row }">{{ money(row.price) }}</template></el-table-column>
          <el-table-column label="当前节点" width="120"><template #default="{ row }"><template v-if="cur(row)">{{ cur(row).name }} <StatusTag :tone="NODE_STATE_TONE[nodeState(cur(row))]">{{ NODE_STATE_TEXT[nodeState(cur(row))] }}</StatusTag></template><span v-else class="ok-text">全部完成</span></template></el-table-column>
          <el-table-column label="进度" width="110"><template #default="{ row }"><el-progress :percentage="progress(row)" :stroke-width="6" :status="late(row) ? 'exception' : undefined" /></template></el-table-column>
          <el-table-column label="交期" width="100" class-name="num"><template #default="{ row }">{{ row.due }}<div v-if="row.status !== '已完成'" :class="daysLeft(row.due) < 0 ? 'late-text' : daysLeft(row.due) <= 7 ? 'warn-text' : 'muted'" style="font-size:12px">{{ daysLeft(row.due) < 0 ? '已过 ' + -daysLeft(row.due) + ' 天' : '剩 ' + daysLeft(row.due) + ' 天' }}</div></template></el-table-column>
          <el-table-column label="回货" width="60" align="right" class-name="num"><template #default="{ row }">{{ fmt(received(row)) }}</template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" /> <StatusTag v-if="openIssues(row)" tone="danger">异常</StatusTag></template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.7 生产外发">一张加工单 = 一个加工厂 + 一种加工方式 + 一个款的若干颜色尺码。</ProtoNote>
  </div>`,
})
Object.assign(ProductionListPage, { data: ((orig) => function () { return { ...orig.call(this), NODE_STATE_TEXT, NODE_STATE_TONE } })(ProductionListPage.data) })

const ProductionDetailPage = page({
  props: ['params', 'query'],
  data() {
    return {
      tab: this.query.tab || 'matrix',
      reporting: false,
      rep: { node: '', as: '', ae: '', qty: 0, note: '' },
      receiving: false,
      recv: { rows: [], wh: 'WH09', logistics: '', note: '' },
      issuing: false,
      iss: { type: '缺料', desc: '', duty: '供应商', newDue: '' },
      NODE_STATE_TEXT,
      NODE_STATE_TONE,
    }
  },
  computed: {
    p() {
      return prodOf(this.params.id)
    },
    total() {
      return matrixTotal(this.p.qty)
    },
    late() {
      return prodLate(this.p)
    },
    needs() {
      return prodNeeds(this.p).map((r) => ({ ...r, atFactory: db.stock.filter((s) => s.sku === r.sku && s.owner === r.owner && whOf(s.wh)?.factory === this.p.factory).reduce((a, s) => a + s.qty, 0) }))
    },
  },
  methods: {
    nodeState,
    received: prodReceived,
    progress: prodProgress,
    nodePct(n) {
      return Math.min(100, Math.round((n.qty / this.total) * 100))
    },
    lateBy(plan, actual) {
      return actual && plan && actual > plan ? days(plan, actual) : 0
    },
    openReport(name) {
      const n = (name && this.p.nodes.find((x) => x.name === name)) || prodCurrentNode(this.p) || this.p.nodes[0]
      this.rep = { node: n.name, as: n.as || TODAY, ae: '', qty: n.qty, note: '' }
      this.reporting = true
    },
    saveReport() {
      const n = this.p.nodes.find((x) => x.name === this.rep.node)
      n.as = this.rep.as || n.as || TODAY
      n.qty = this.rep.qty
      if (this.rep.ae) n.ae = this.rep.ae
      else if (this.rep.qty >= this.total) n.ae = TODAY
      this.p.reports.unshift({ at: TODAY + ' 15:40', by: db.me.name, node: n.name, qty: n.qty, note: this.rep.note })
      prodStatusAfterReport(this.p)
      this.reporting = false
      ElMessage.success(`「${n.name}」已更新${n.ae ? '，标记完成' : ''}`)
    },
    openReceive() {
      this.recv = { rows: Object.keys(this.p.qty).map((c) => ({ color: c, plan: sumValues(this.p.qty[c]), got: sumValues(this.p.received[c]), qty: 0, defects: 0 })), wh: 'WH09', logistics: '', note: '' }
      this.receiving = true
    },
    saveReceive() {
      const sum = this.recv.rows.reduce((a, r) => a + r.qty, 0)
      if (!sum) return ElMessage.warning('填一下这次回了多少件')
      for (const r of this.recv.rows) {
        let left = r.qty
        const rec = (this.p.received[r.color] ||= {})
        for (const [size, q] of Object.entries(this.p.qty[r.color])) {
          const room = q - (rec[size] || 0)
          const take = Math.min(room, left)
          rec[size] = (rec[size] || 0) + take
          left -= take
        }
      }
      const defects = this.recv.rows.reduce((a, r) => a + r.defects, 0)
      this.p.defects += defects
      this.p.receipts.unshift({ no: nextNo('HH'), date: TODAY, qty: sum, defects, wh: this.recv.wh, logistics: this.recv.logistics, note: this.recv.note })
      this.p.status = prodReceived(this.p) >= this.total ? '已完成' : '部分回货'
      this.receiving = false
      this.tab = 'receipts'
      ElMessage.success(`回货 ${sum} 件已登记${this.p.status === '已完成' ? '，全部回齐，加工单完成' : ''}。成品入库推聚水潭放二期`)
    },
    saveIssue() {
      if (!this.iss.desc) return ElMessage.warning('写一下出了什么事')
      this.p.issues.unshift({ ...this.iss, by: db.me.name, at: TODAY + ' 15:45', action: '', status: '待处理' })
      this.issuing = false
      this.tab = 'issues'
      ElMessage.success('异常已上报，首页「异常待处理」能看到')
    },
    closeIssue(i) {
      ElMessageBox.prompt('写一下怎么处理的，关闭后进入历史。', '关闭异常', { confirmButtonText: '关闭异常', cancelButtonText: '取消', inputValue: i.action })
        .then(({ value }) => {
          i.action = value
          i.status = '已关闭'
          ElMessage.success('已关闭')
        })
        .catch(() => {})
    },
  },
  template: `
  <div class="page" v-if="p">
    <PageHeader :title="'加工单 ' + p.no" :description="styleOf(p.style).code + ' ' + styleOf(p.style).name + ' · ' + supName(p.factory) + ' · ' + p.mode + ' · 跟单 ' + p.follower">
      <template #title-extra><StatusTag :status="p.status" /><StatusTag v-if="late" tone="danger">延期</StatusTag></template>
      <template #actions>
        <el-button icon="Printer" @click="proto('打印加工单（带资料包 BOM 和工艺），发给工厂')">打印加工单</el-button>
        <el-button v-if="p.status !== '已完成'" icon="Box" @click="go('/stock-out')">领料</el-button>
        <el-button v-if="p.status !== '已完成'" icon="Warning" @click="issuing = true">上报异常</el-button>
        <el-button v-if="p.status !== '已完成'" @click="openReceive">登记回货</el-button>
        <el-button v-if="p.status !== '已完成'" type="primary" icon="Flag" @click="openReport()">汇报进度</el-button>
      </template>
    </PageHeader>
    <FlowBar :steps="['已下单','生产中','部分回货','已完成']" :current="p.status" />

    <section class="panel block">
      <dl class="desc-grid">
        <div><dt>加工厂</dt><dd>{{ supName(p.factory) }}</dd></div>
        <div><dt>加工方式</dt><dd>{{ p.mode }}</dd></div>
        <div><dt>关联订单</dt><dd><a class="link code" @click="go('/orders/' + p.order)">{{ p.order }}</a></dd></div>
        <div><dt>款式 / 资料包</dt><dd><a class="link code" @click="go('/styles/' + p.style + '?tab=pack')">{{ p.style }}</a> · {{ p.pack }}</dd></div>
        <div><dt>数量</dt><dd class="num">{{ fmt(total) }} 件</dd></div>
        <div><dt>加工价 / 金额</dt><dd class="num">{{ money(p.price) }} / {{ money(p.price * total) }}</dd></div>
        <div><dt>下单 / 交期</dt><dd class="num">{{ p.date }} / {{ p.due }} <span v-if="p.status !== '已完成'" :class="daysLeft(p.due) < 0 ? 'late-text' : 'muted'">（{{ daysLeft(p.due) < 0 ? '已过 ' + -daysLeft(p.due) + ' 天' : '剩 ' + daysLeft(p.due) + ' 天' }}）</span></dd></div>
        <div><dt>回货 / 次品</dt><dd class="num">{{ fmt(received(p)) }} / {{ p.defects }}</dd></div>
        <div class="full"><dt>条款</dt><dd>{{ p.terms || '—' }}</dd></div>
      </dl>
    </section>

    <section class="panel block" style="margin-top:16px">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <h2 style="margin:0;font-size:15px">生产进度</h2>
        <span class="muted" style="font-size:12px">每个节点记 计划开始 / 计划完成 / 实际开始 / 实际完成 四个时间；过了计划完成还没完成就标红</span>
      </div>
      <div class="nodes">
        <div v-for="n in p.nodes" :key="n.name" class="node" :class="nodeState(n).startsWith('done') ? 'done' : nodeState(n) === 'todo' ? 'idle' : nodeState(n)" role="button" tabindex="0" @click="p.status !== '已完成' && openReport(n.name)">
          <h3>{{ n.name }}<StatusTag :tone="NODE_STATE_TONE[nodeState(n)]">{{ NODE_STATE_TEXT[nodeState(n)] }}</StatusTag></h3>
          <div class="row"><span>计划</span><b>{{ n.ps.slice(5) }} ~ {{ n.pe.slice(5) }}</b></div>
          <div class="row"><span>实际</span><b :class="{ late: lateBy(n.pe, n.ae) || nodeState(n) === 'late' }">{{ n.as ? n.as.slice(5) + ' ~ ' + (n.ae ? n.ae.slice(5) : '进行中') : '—' }}</b></div>
          <div class="row"><span>完成</span><b>{{ n.qty }} / {{ total }}</b></div>
          <div v-if="lateBy(n.pe, n.ae)" class="row"><span class="late-text">晚了 {{ lateBy(n.pe, n.ae) }} 天</span></div>
          <div v-else-if="nodeState(n) === 'late'" class="row"><span class="late-text">计划 {{ n.pe.slice(5) }} 完成，已过 {{ -daysLeft(n.pe) }} 天</span></div>
          <div class="bar"><i :style="{ width: nodePct(n) + '%' }"></i></div>
        </div>
      </div>
    </section>

    <section class="panel" style="margin-top:16px"><div class="panel-body">
      <el-tabs v-model="tab" class="sub-tabs">
        <el-tab-pane label="加工明细" name="matrix"><QtyMatrix :qty="p.qty" :sizes="sizesOfStyle(p.style)" :received="p.received" /></el-tab-pane>
        <el-tab-pane :label="'汇报记录 ' + p.reports.length" name="reports">
          <ul v-if="p.reports.length" class="timeline-list"><li v-for="r in p.reports" :key="r.at">「{{ r.node }}」完成 {{ r.qty }} 件{{ r.note ? '：' + r.note : '' }}<small>{{ r.at }} · {{ r.by }}（手机报工）</small></li></ul>
          <p v-else class="muted">还没有人汇报。跟单员可以在手机上报，见左侧菜单「手机报工」。</p>
        </el-tab-pane>
        <el-tab-pane :label="'回货记录 ' + p.receipts.length" name="receipts">
          <el-table :data="p.receipts" size="small" empty-text="还没有回货。工厂发货后点「登记回货」。">
            <el-table-column prop="no" label="回货单" width="150" class-name="code" />
            <el-table-column prop="date" label="日期" width="100" class-name="num" />
            <el-table-column label="件数" width="80" align="right" class-name="num"><template #default="{ row }">{{ row.qty }}</template></el-table-column>
            <el-table-column label="次品" width="70" align="right" class-name="num"><template #default="{ row }">{{ row.defects }}</template></el-table-column>
            <el-table-column label="收货仓" width="110"><template #default="{ row }">{{ whName(row.wh) }}</template></el-table-column>
            <el-table-column prop="logistics" label="物流" width="180" />
            <el-table-column prop="note" label="备注" min-width="160" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="'异常事件 ' + p.issues.length" name="issues">
          <el-table :data="p.issues" size="small" empty-text="没有异常。缺料、质量、延期这类事点「上报异常」，处理过程留痕。">
            <el-table-column prop="type" label="类型" width="70" />
            <el-table-column prop="desc" label="描述" min-width="200" />
            <el-table-column prop="duty" label="责任方" width="130" />
            <el-table-column prop="action" label="处理" min-width="200" />
            <el-table-column label="新交期" width="100" class-name="num"><template #default="{ row }">{{ row.newDue || '—' }}</template></el-table-column>
            <el-table-column label="上报" width="140"><template #default="{ row }">{{ row.by }}<div class="muted num" style="font-size:12px">{{ row.at }}</div></template></el-table-column>
            <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            <el-table-column label="" width="80"><template #default="{ row }"><el-button v-if="row.status !== '已关闭'" link type="primary" @click="closeIssue(row)">关闭</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="用料" name="material">
          <el-table :data="needs" size="small" empty-text="这张加工单不用我方发料（包工包料且没有客供料）">
            <el-table-column label="物料" min-width="200"><template #default="{ row }"><span class="code">{{ row.sku }}</span> {{ skuLabel(row.sku) }}</template></el-table-column>
            <el-table-column label="物权" width="110"><template #default="{ row }">{{ ownerName(row.owner) }}</template></el-table-column>
            <el-table-column label="应领" width="90" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.need) }}</template></el-table-column>
            <el-table-column label="已领" width="90" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.issued) }}</template></el-table-column>
            <el-table-column label="还没领" width="90" align="right" class-name="num"><template #default="{ row }"><span :class="row.rest ? 'warn-text' : 'ok-text'">{{ fmtQ(row.rest) }}</span></template></el-table-column>
            <el-table-column label="工厂仓现有" width="100" align="right" class-name="num"><template #default="{ row }">{{ fmtQ(row.atFactory) }}</template></el-table-column>
          </el-table>
          <p class="muted" style="font-size:12px;margin:10px 0 0">二期：回货后按件数 × BOM 自动核销工厂仓，算出工厂该退的余料（料账平衡）。</p>
        </el-tab-pane>
      </el-tabs>
    </div></section>

    <el-dialog v-model="reporting" title="汇报进度" width="460px">
      <el-form label-width="80px">
        <el-form-item label="节点"><el-select v-model="rep.node"><el-option v-for="n in p.nodes" :key="n.name" :label="n.name" :value="n.name" /></el-select></el-form-item>
        <el-form-item label="实际开始"><el-date-picker v-model="rep.as" value-format="YYYY-MM-DD" type="date" /></el-form-item>
        <el-form-item label="实际完成"><el-date-picker v-model="rep.ae" value-format="YYYY-MM-DD" type="date" placeholder="还没完成就空着" /></el-form-item>
        <el-form-item label="完成数量"><el-input-number v-model="rep.qty" :min="0" :max="total" /> <span class="muted" style="margin-left:8px">/ {{ total }}</span></el-form-item>
        <el-form-item label="照片"><div class="m-photo" style="width:72px;height:72px" @click="proto('拍照或上传现场照片')"><el-icon><Camera /></el-icon>加照片</div></el-form-item>
        <el-form-item label="备注"><el-input v-model="rep.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reporting = false">取消</el-button><el-button type="primary" @click="saveReport">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="receiving" title="登记回货" width="620px">
      <el-table :data="recv.rows" size="small">
        <el-table-column label="颜色" width="120"><template #default="{ row }"><ColorChip :code="row.color" /></template></el-table-column>
        <el-table-column label="下单" width="70" align="right" class-name="num"><template #default="{ row }">{{ row.plan }}</template></el-table-column>
        <el-table-column label="已回" width="70" align="right" class-name="num"><template #default="{ row }">{{ row.got }}</template></el-table-column>
        <el-table-column label="本次回货" width="130"><template #default="{ row }"><el-input-number v-model="row.qty" size="small" :min="0" :max="row.plan - row.got" :controls="false" style="width:90px" /></template></el-table-column>
        <el-table-column label="其中次品" width="120"><template #default="{ row }"><el-input-number v-model="row.defects" size="small" :min="0" :controls="false" style="width:80px" /></template></el-table-column>
      </el-table>
      <el-form label-width="80px" style="margin-top:14px">
        <el-form-item label="收货仓"><el-select v-model="recv.wh"><el-option v-for="w in db.warehouses.filter((x) => x.type === '成品仓')" :key="w.code" :label="w.name" :value="w.code" /></el-select></el-form-item>
        <el-form-item label="物流单号"><el-input v-model="recv.logistics" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="recv.note" /></el-form-item>
      </el-form>
      <p class="muted" style="font-size:12px">一期只登记回货件数，不管成品库存。成品入库要不要由本系统推给聚水潭，是待决问题 Q01。</p>
      <template #footer><el-button @click="receiving = false">取消</el-button><el-button type="primary" @click="saveReceive">登记回货</el-button></template>
    </el-dialog>

    <el-dialog v-model="issuing" title="上报异常" width="480px">
      <el-form label-width="80px">
        <el-form-item label="类型"><el-select v-model="iss.type"><el-option v-for="t in ['缺料','质量','延期','工艺问题','其他']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="出了什么事"><el-input v-model="iss.desc" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="责任方"><el-select v-model="iss.duty"><el-option v-for="t in ['供应商','加工厂','客户','内部']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="影响交期"><el-date-picker v-model="iss.newDue" value-format="YYYY-MM-DD" type="date" placeholder="不影响就空着" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="issuing = false">取消</el-button><el-button type="primary" @click="saveIssue">上报</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.7 生产外发">节点（到料、裁剪、车缝、后整、包装、入仓）可在字典里改名、增减；排单时按交期倒推出计划日期，跟单再调。</ProtoNote>
  </div>
  <div v-else class="page"><BlankState title="没有这张加工单" description="单号可能写错了。"><el-button @click="go('/production')">回到加工单列表</el-button></BlankState></div>`,
})
Object.assign(ProductionDetailPage.methods, { sizesOfStyle, ownerName })

const BoardPage = page({
  props: ['params', 'query'],
  data: () => ({ factory: '', follower: '' }),
  computed: {
    list() {
      return db.production.filter((p) => (!this.factory || p.factory === this.factory) && (!this.follower || p.follower === this.follower))
    },
    lanes() {
      const lane = (p) => {
        if (p.status === '已完成') return 4
        const cur = prodCurrentNode(p)
        if (!cur || (!cur.as && cur.name === '到料')) return 0
        if (['到料', '裁剪'].includes(cur.name)) return 1
        if (cur.name === '车缝') return 2
        return 3
      }
      const names = ['待开工', '到料 / 裁剪', '车缝', '后整 / 包装 / 入仓', '已完成']
      return names.map((name, i) => ({ name, items: this.list.filter((p) => lane(p) === i) }))
    },
    kpi() {
      const open = this.list.filter((p) => p.status !== '已完成')
      return {
        open: open.length,
        pieces: open.reduce((a, p) => a + matrixTotal(p.qty) - prodReceived(p), 0),
        late: open.filter((p) => prodLate(p)).length,
        soon: open.filter((p) => daysLeft(p.due) >= 0 && daysLeft(p.due) <= 7).length,
        done: this.list.filter((p) => p.status === '已完成').reduce((a, p) => a + prodReceived(p), 0),
      }
    },
  },
  methods: {
    late: prodLate,
    progress: prodProgress,
    cur: prodCurrentNode,
  },
  template: `
  <div class="page">
    <PageHeader title="生产看板" description="所有加工单按所在节点分列。左边红线 = 延期，黄线 = 7 天内到期。">
      <template #actions>
        <el-select v-model="factory" clearable placeholder="全部加工厂" style="width:150px"><el-option v-for="s in db.suppliers.filter((x) => ['成衣厂','加工厂'].includes(x.cat))" :key="s.id" :label="s.short" :value="s.id" /></el-select>
        <el-select v-model="follower" clearable placeholder="全部跟单" style="width:120px"><el-option label="陈晓" value="陈晓" /></el-select>
      </template>
    </PageHeader>
    <div class="kpis">
      <div class="kpi"><small>在制加工单</small><strong>{{ kpi.open }}</strong></div>
      <div class="kpi"><small>在制件数</small><strong>{{ fmt(kpi.pieces) }}</strong></div>
      <div class="kpi alert"><small>已延期</small><strong>{{ kpi.late }}</strong></div>
      <div class="kpi warn"><small>7 天内到期</small><strong>{{ kpi.soon }}</strong></div>
      <div class="kpi"><small>已回货（件）</small><strong>{{ fmt(kpi.done) }}</strong></div>
    </div>
    <div class="lanes">
      <div v-for="l in lanes" :key="l.name" class="lane">
        <h3>{{ l.name }}<span>{{ l.items.length }}</span></h3>
        <div v-if="!l.items.length" class="empty">没有</div>
        <div v-for="p in l.items" :key="p.no" class="job" :class="{ late: late(p), soon: !late(p) && p.status !== '已完成' && daysLeft(p.due) <= 7 }" role="link" tabindex="0" @click="go('/production/' + p.no)" @keydown.enter="go('/production/' + p.no)">
          <div class="top"><span class="code">{{ p.no }}</span><b class="num">{{ fmt(matrixTotal(p.qty)) }} 件</b></div>
          <div>{{ styleOf(p.style).name }} <span class="muted code">{{ p.style }}</span></div>
          <div class="meta">{{ supName(p.factory) }} · {{ p.mode }} · {{ cur(p) ? cur(p).name : '完成' }}</div>
          <div class="meta" :class="p.status !== '已完成' && daysLeft(p.due) < 0 ? 'late-text' : ''">交期 {{ p.due.slice(5) }}{{ p.status === '已完成' ? '' : daysLeft(p.due) < 0 ? '，已过 ' + -daysLeft(p.due) + ' 天' : '，剩 ' + daysLeft(p.due) + ' 天' }}</div>
          <el-progress :percentage="progress(p)" :stroke-width="5" :status="late(p) ? 'exception' : p.status === '已完成' ? 'success' : undefined" />
        </div>
      </div>
    </div>
    <ProtoNote sec="§6.7 生产外发（生产看板）">一期是这一屏；按订单看进度总览、客户能自己查进度放二期、三期。</ProtoNote>
  </div>`,
})

const MobilePage = page({
  props: ['params', 'query'],
  data: () => ({ screen: 'list', jg: '', form: { node: '', qty: 0, note: '', photos: 0 } }),
  computed: {
    mine() {
      return db.production.filter((p) => p.follower === db.me.name && p.status !== '已完成')
    },
    p() {
      return prodOf(this.jg)
    },
    total() {
      return this.p ? matrixTotal(this.p.qty) : 0
    },
  },
  methods: {
    late: prodLate,
    progress: prodProgress,
    cur: prodCurrentNode,
    nodeState,
    open(p) {
      this.jg = p.no
      const n = prodCurrentNode(p) || p.nodes[0]
      this.form = { node: n.name, qty: n.qty, note: '', photos: 0 }
      this.screen = 'detail'
    },
    pick(n) {
      this.form.node = n.name
      this.form.qty = n.qty
    },
    submit() {
      const n = this.p.nodes.find((x) => x.name === this.form.node)
      n.as = n.as || TODAY
      n.qty = this.form.qty
      if (this.form.qty >= this.total) n.ae = TODAY
      this.p.reports.unshift({ at: TODAY + ' 16:05', by: db.me.name, node: n.name, qty: n.qty, note: this.form.note + (this.form.photos ? `（${this.form.photos} 张照片）` : '') })
      prodStatusAfterReport(this.p)
      ElMessage.success(`已提交：${n.name} ${n.qty} 件${n.ae ? '，节点完成' : ''}`)
      this.screen = 'list'
    },
  },
  template: `
  <div class="page">
    <PageHeader title="手机报工" description="跟单员在工厂现场用手机浏览器打开，登录同一个账号，不用装 App。下面是手机上看到的样子，可以点。" />
    <div class="phone-stage">
      <div class="phone" aria-label="手机预览">
        <div class="phone-status"><span>15:40</span><span>5G ▮▮▮</span></div>
        <template v-if="screen === 'list'">
          <div class="phone-bar"><button aria-hidden="true"></button><h2>我的跟单</h2><button aria-hidden="true"></button></div>
          <div class="phone-body">
            <p class="muted" style="margin:0 0 8px;font-size:12px">{{ mine.length }} 张在做 · 点一张汇报进度</p>
            <div v-for="p in mine" :key="p.no" class="m-card" role="button" tabindex="0" @click="open(p)" @keydown.enter="open(p)">
              <div class="top"><span>{{ styleOf(p.style).name }}</span><StatusTag v-if="late(p)" tone="danger">延期</StatusTag><StatusTag v-else :status="p.status" /></div>
              <div class="meta"><span class="code">{{ p.no }}</span></div>
              <div class="meta">{{ supName(p.factory) }} · {{ fmt(matrixTotal(p.qty)) }} 件 · 交期 {{ p.due.slice(5) }}</div>
              <div class="meta">当前：{{ cur(p) ? cur(p).name + ' ' + cur(p).qty + ' 件' : '全部完成' }}</div>
              <el-progress :percentage="progress(p)" :stroke-width="5" :status="late(p) ? 'exception' : undefined" style="margin-top:6px" />
            </div>
            <p v-if="!mine.length" class="muted" style="text-align:center;margin-top:40px">没有在做的加工单</p>
          </div>
        </template>
        <template v-else-if="p">
          <div class="phone-bar"><button @click="screen = 'list'">‹ 返回</button><h2>汇报进度</h2><button aria-hidden="true"></button></div>
          <div class="phone-body">
            <div class="m-card">
              <div class="top"><span>{{ styleOf(p.style).name }}</span><span class="code" style="font-weight:500">{{ fmt(total) }} 件</span></div>
              <div class="meta"><span class="code">{{ p.no }}</span> · {{ supName(p.factory) }}</div>
              <div class="meta">交期 {{ p.due }}</div>
            </div>
            <div class="m-label">哪个节点</div>
            <div class="m-chips">
              <button v-for="n in p.nodes" :key="n.name" type="button" class="m-chip" :class="{ active: form.node === n.name, done: n.ae && form.node !== n.name }" @click="pick(n)">{{ n.name }}{{ n.ae ? ' ✓' : '' }}</button>
            </div>
            <div class="m-label">累计完成件数</div>
            <el-input-number v-model="form.qty" :min="0" :max="total" :step="10" style="width:100%" />
            <p class="muted" style="font-size:12px;margin:4px 0 0">填到 {{ total }} 就算这个节点完成，完成日期记今天</p>
            <div class="m-label">现场照片</div>
            <div class="m-chips">
              <span v-for="i in form.photos" :key="i" class="m-photo" style="border-style:solid;background:var(--lm-greige-2)">照片 {{ i }}</span>
              <div class="m-photo" role="button" tabindex="0" @click="form.photos++" @keydown.enter="form.photos++"><el-icon :size="22"><Camera /></el-icon>拍照 / 相册</div>
            </div>
            <div class="m-label">备注</div>
            <el-input v-model="form.note" type="textarea" :rows="2" placeholder="比如：两条线在做，日产 40 件" />
            <el-button type="primary" class="m-submit" @click="submit">提交</el-button>
          </div>
        </template>
      </div>
      <div class="phone-notes">
        <h2>说明</h2>
        <ul>
          <li>一期：跟单员在手机上报，和电脑上「汇报进度」是同一份数据。</li>
          <li>页面按手机宽度排版，用手机浏览器打开系统地址即可，不用装 App。</li>
          <li>提交后电脑上的加工单详情、生产看板马上更新；延期的单子在首页待办里提醒。</li>
          <li>工厂自己报工要给工厂开外部账号，等待决问题 Q02 定了再做。</li>
        </ul>
      </div>
    </div>
    <ProtoNote sec="§6.7 生产外发（手机报工）、§6.9 系统">一期只做这一个手机页面；手机审批、手机看报表放二期。</ProtoNote>
  </div>`,
})
