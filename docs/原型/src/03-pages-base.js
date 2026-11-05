/* ============================================================
 * 首页、基础资料、供应商
 * ============================================================ */
const HomePage = page({
  props: ['params', 'query'],
  computed: {
    audits() {
      return [
        ...db.orders.filter((o) => o.status === '待审核').map((o) => ({ key: o.no, title: `订单 ${o.no}`, sub: [o.type, o.customer && custName(o.customer), fmt(orderQty(o)) + ' 件', '交期 ' + o.due].filter(Boolean).join(' · '), to: '/orders/' + o.no })),
        ...db.purchases.filter((p) => p.status === '待审核').map((p) => ({ key: p.no, title: `采购单 ${p.no}`, sub: `${supName(p.supplier)} · ${p.type} · 交期 ${p.due}`, to: '/purchases/' + p.no })),
      ]
    },
    lateArrivals() {
      return db.purchases
        .filter((p) => ['已审核', '部分到货'].includes(p.status))
        .flatMap((p) => p.lines.filter((l) => l.inQty < l.qty && l.due < TODAY).map((l) => ({ key: p.no + l.sku, title: `${skuLabel(l.sku)} 还差 ${fmtQ(l.qty - l.inQty)}`, sub: `${p.no} · ${supName(p.supplier)} · 交期 ${l.due}，已过 ${-daysLeft(l.due)} 天`, to: '/arrivals' })))
    },
    lateProd() {
      return db.production
        .filter((p) => prodLate(p))
        .map((p) => {
          const n = p.nodes.find((x) => nodeState(x) === 'late')
          return { key: p.no, title: `${p.no} ${styleOf(p.style).name}`, sub: `${supName(p.factory)} · ${n ? `「${n.name}」计划 ${n.pe} 完成，还没完成` : '已过交期'}`, to: '/production/' + p.no }
        })
    },
    toSchedule() {
      return db.orders.filter((o) => o.status === '待排单').map((o) => ({ key: o.no, title: `订单 ${o.no}`, sub: `${custName(o.customer)} · ${fmt(orderQty(o))} 件 · 交期 ${o.due}`, to: '/orders/' + o.no }))
    },
    issues() {
      return db.production.flatMap((p) => p.issues.filter((i) => i.status !== '已关闭').map((i) => ({ key: p.no + i.at, title: `${i.type}：${i.desc}`, sub: `${p.no} · ${i.by} 上报于 ${i.at}`, to: '/production/' + p.no + '?tab=issues' })))
    },
    groups() {
      return [
        { name: '待我审核', items: this.audits, tone: 'pending' },
        { name: '加工延期', items: this.lateProd, tone: 'danger' },
        { name: '到料逾期', items: this.lateArrivals, tone: 'danger' },
        { name: '待排单', items: this.toSchedule, tone: 'pending' },
        { name: '异常待处理', items: this.issues, tone: 'doing' },
      ]
    },
    dueSoon() {
      return db.production
        .filter((p) => p.status !== '已完成')
        .map((p) => ({ ...p, left: daysLeft(p.due) }))
        .sort((a, b) => a.left - b.left)
    },
  },
  data: () => ({
    shortcuts: [
      { name: '订单', pages: [['订单列表', '/orders'], ['算料', '/mrp']] },
      { name: '物料', pages: [['采购单', '/purchases'], ['到料跟踪', '/arrivals'], ['入库单', '/stock-in'], ['出库领料单', '/stock-out'], ['库存', '/stock']] },
      { name: '生产', pages: [['外发加工单', '/production'], ['生产看板', '/board'], ['手机报工', '/mobile']] },
      { name: '基础资料', pages: [['款式档案', '/styles'], ['物料档案', '/materials'], ['供应商档案', '/suppliers']] },
    ],
  }),
  methods: { prodProgress },
  template: `
  <div class="page">
    <header class="hello">
      <h1>下午好，{{ db.me.name }}</h1>
      <p>2026 年 11 月 5 日 星期四 · {{ db.me.role }} · {{ db.company }}</p>
    </header>
    <div class="columns">
      <section class="panel block">
        <h2>待办</h2>
        <div v-for="g in groups" :key="g.name" class="todo-group">
          <h3><StatusTag :tone="g.tone">{{ g.name }}</StatusTag><span class="count">{{ g.items.length }}</span></h3>
          <p v-if="!g.items.length" class="muted" style="margin:0 0 6px;font-size:13px">没有。</p>
          <div v-for="t in g.items" :key="t.key" class="todo" role="link" tabindex="0" @click="go(t.to)" @keydown.enter="go(t.to)">
            <div class="main"><span class="code">{{ t.title }}</span><small>{{ t.sub }}</small></div>
            <el-icon><ArrowRight /></el-icon>
          </div>
        </div>
      </section>
      <div>
        <section class="panel block">
          <h2>在做的加工单</h2>
          <el-table :data="dueSoon" size="small" empty-text="没有在做的加工单" @row-click="(r) => go('/production/' + r.no)">
            <el-table-column label="加工单" min-width="150"><template #default="{ row }"><a class="link code">{{ row.no }}</a><div class="muted" style="font-size:12px">{{ styleOf(row.style).name }} · {{ supName(row.factory) }}</div></template></el-table-column>
            <el-table-column label="进度" width="110"><template #default="{ row }"><el-progress :percentage="prodProgress(row)" :stroke-width="6" /></template></el-table-column>
            <el-table-column label="交期" width="96" class-name="num"><template #default="{ row }"><span :class="row.left < 0 ? 'late-text' : row.left <= 7 ? 'warn-text' : ''">{{ row.left < 0 ? '已过 ' + (-row.left) + ' 天' : '剩 ' + row.left + ' 天' }}</span></template></el-table-column>
          </el-table>
        </section>
        <section class="panel block" style="margin-top:16px">
          <h2>我的功能</h2>
          <div v-for="g in shortcuts" :key="g.name" class="shortcut-group">
            <h3>{{ g.name }}</h3>
            <div class="shortcuts"><a v-for="p in g.pages" :key="p[1]" @click="go(p[1])">{{ p[0] }}</a></div>
          </div>
        </section>
      </div>
    </div>
    <ProtoNote sec="§6.9 系统与首页">待办由系统按单据状态算出来，不用人维护；一期只在站内显示，二期再推到钉钉、企业微信。</ProtoNote>
  </div>`,
})

/* ---------------- 物料档案 ---------------- */
function blankMaterial() {
  return { code: '', name: '', cat: '', type: '面料', unit: '米', buyUnit: '米', rate: 1, width: null, gsm: null, comp: '', supplier: '', supplierNo: '', price: null, moq: null, lead: null, status: '启用', skus: [] }
}

const MaterialsPage = page({
  props: ['params', 'query'],
  data: () => ({
    cat: '',
    q: { keyword: '', supplier: '', status: '' },
    applied: { keyword: '', supplier: '', status: '' },
    drawer: false,
    form: blankMaterial(),
    editing: null,
    page: 1,
  }),
  computed: {
    tree() {
      return [{ id: '', label: '全部物料', children: db.materialCategories }]
    },
    list() {
      const { keyword, supplier, status } = this.applied
      return db.materials.filter((m) => {
        if (this.cat && !(m.cat === this.cat || m.cat.startsWith(this.cat + '-'))) return false
        if (keyword && !(m.code + m.name + m.supplierNo).includes(keyword)) return false
        if (supplier && m.supplier !== supplier) return false
        if (status && m.status !== status) return false
        return true
      })
    },
    selectableSuppliers() {
      return db.suppliers
    },
  },
  methods: {
    catName(id) {
      const all = db.materialCategories.flatMap((c) => [c, ...(c.children || []).map((x) => ({ ...x, label: c.label + ' / ' + x.label }))])
      return all.find((c) => c.id === id)?.label || '—'
    },
    search() {
      this.applied = { ...this.q }
    },
    reset() {
      this.q = { keyword: '', supplier: '', status: '' }
      this.search()
    },
    openCreate() {
      this.editing = null
      this.form = blankMaterial()
      this.form.cat = this.cat || 'FAB-W'
      this.drawer = true
    },
    openEdit(m) {
      this.editing = m
      this.form = JSON.parse(JSON.stringify(m))
      this.drawer = true
    },
    addSku() {
      this.form.skus.push({ sku: '', color: '', colorNo: '', spec: '', safety: 0, isNew: true })
    },
    skuCode(s) {
      const base = this.form.code || '（物料编码）'
      return [base, s.color, s.color ? '' : s.spec].filter(Boolean).join('-') || base
    },
    used(s) {
      return db.stock.some((r) => r.sku === s.sku) || Object.values(db.packs).flat().some((p) => p.bom.some((b) => b.fixedSku === s.sku || Object.values(b.colorMap || {}).includes(s.sku)))
    },
    removeSku(i) {
      this.form.skus.splice(i, 1)
    },
    save() {
      if (!this.form.name) return ElMessage.warning('名称要填')
      if (this.editing) {
        Object.assign(this.editing, JSON.parse(JSON.stringify(this.form)))
        this.editing.skus.forEach((s) => (s.sku = s.sku || [this.editing.code, s.color || s.spec].join('-')))
        ElMessage.success('保存好了')
      } else {
        const prefix = { 面料: 'ML', 里料: 'ML', 辅料: 'FL', 标签吊牌: 'BQ', 包材: 'BC' }[this.form.type] || 'ML'
        const n = db.materials.filter((m) => m.code.startsWith(prefix)).length + 1
        this.form.code = prefix + String(n).padStart(4, '0')
        this.form.skus.forEach((s) => (s.sku = [this.form.code, s.color || s.spec].join('-')))
        db.materials.unshift(JSON.parse(JSON.stringify(this.form)))
        ElMessage.success(`建好了，编码 ${this.form.code}`)
      }
      this.drawer = false
    },
    toggle(m) {
      const stop = m.status === '启用'
      ElMessageBox.confirm(stop ? `停用后，新的 BOM、采购单里选不到「${m.name}」；已有单据和库存不受影响。` : `启用「${m.name}」？`, stop ? '停用物料' : '启用物料', { confirmButtonText: stop ? '停用' : '启用', cancelButtonText: '取消', type: 'warning' })
        .then(() => {
          m.status = stop ? '停用' : '启用'
          ElMessage.success(stop ? '已停用' : '已启用')
        })
        .catch(() => {})
    },
    firstHex(m) {
      return colorOf(m.skus[0]?.color).hex
    },
  },
  template: `
  <div class="page">
    <PageHeader title="物料档案" description="面料、里料、辅料、标签吊牌、包材统一建档。一个物料按颜色、规格分 SKU，BOM、采购、库存都落到 SKU。">
      <template #actions>
        <el-button icon="Upload" @click="proto('弹出导入向导：下载模板 → 填好上传 → 先预览错误行，确认后才导入。旧系统导出的物料表按这个模板转一次')">从 Excel 导入</el-button>
        <el-button type="primary" icon="Plus" @click="openCreate">新增物料</el-button>
      </template>
    </PageHeader>
    <div class="split">
      <aside class="panel tree-panel">
        <h2>物料分类</h2>
        <el-tree :data="tree" node-key="id" default-expand-all highlight-current :current-node-key="cat" :expand-on-click-node="false" @node-click="(n) => (cat = n.id)" />
      </aside>
      <section class="panel">
        <el-form class="filter-bar" inline @submit.prevent="search">
          <el-form-item label="关键字"><el-input v-model="q.keyword" clearable placeholder="编码、名称、供应商货号" /></el-form-item>
          <el-form-item label="供应商">
            <el-select v-model="q.supplier" clearable filterable placeholder="全部">
              <el-option v-for="s in db.suppliers" :key="s.id" :label="s.short" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="q.status" clearable placeholder="全部" style="width:110px"><el-option label="启用" value="启用" /><el-option label="停用" value="停用" /></el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" native-type="submit">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
        </el-form>
        <div class="panel-body">
          <el-table :data="list" empty-text="这个分类下还没有物料，或者没有符合条件的">
            <el-table-column width="60"><template #default="{ row }"><span class="thumb" :style="{ width: '34px', height: '34px', background: firstHex(row) }"></span></template></el-table-column>
            <el-table-column prop="code" label="物料编码" width="96" class-name="code" />
            <el-table-column label="名称" min-width="190"><template #default="{ row }">{{ row.name }}<div class="muted" style="font-size:12px">{{ [catName(row.cat), row.comp, row.width && row.width + 'cm', row.gsm && row.gsm + 'g'].filter(Boolean).join(' · ') }}</div></template></el-table-column>
            <el-table-column label="单位" width="84"><template #default="{ row }">{{ row.unit }}<span v-if="row.buyUnit !== row.unit" class="muted" style="font-size:12px"><br>采购按{{ row.buyUnit }}</span></template></el-table-column>
            <el-table-column label="默认供应商" min-width="110"><template #default="{ row }">{{ supName(row.supplier) }}<div class="muted code" style="font-size:12px">{{ row.supplierNo }}</div></template></el-table-column>
            <el-table-column label="参考单价" width="96" align="right" class-name="num"><template #default="{ row }">{{ row.price ? money(row.price) : '—' }}</template></el-table-column>
            <el-table-column label="SKU" width="64" align="right" class-name="num"><template #default="{ row }">{{ row.skus.length }}</template></el-table-column>
            <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            <el-table-column label="操作" width="120"><template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="row.status === '启用' ? 'danger' : 'primary'" @click="toggle(row)">{{ row.status === '启用' ? '停用' : '启用' }}</el-button>
            </template></el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="list.length" :page-size="20" />
        </div>
      </section>
    </div>

    <el-drawer v-model="drawer" :title="editing ? '编辑物料 ' + editing.code : '新增物料'" size="62%">
      <el-form :model="form" label-width="96px">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="物料编码"><el-input v-model="form.code" disabled placeholder="保存后按 分类码 + 4 位流水 生成" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="名称" required><el-input v-model="form.name" placeholder="如 220g 斜纹棉" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="类型"><el-select v-model="form.type"><el-option v-for="t in ['面料','里料','辅料','标签吊牌','包材']" :key="t" :label="t" :value="t" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="分类"><el-tree-select v-model="form.cat" :data="db.materialCategories" node-key="id" :props="{ label: 'label', value: 'id' }" check-strictly default-expand-all /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="库存单位"><el-select v-model="form.unit"><el-option v-for="u in ['米','码','公斤','个','条','套','卷']" :key="u" :label="u" :value="u" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="采购单位"><el-select v-model="form.buyUnit"><el-option v-for="u in ['米','码','公斤','个','条','套','卷']" :key="u" :label="u" :value="u" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="换算"><el-input-number v-model="form.rate" :min="0.0001" :precision="4" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="24" style="margin:-10px 0 8px 96px" class="muted"><small>1 {{ form.buyUnit }} = {{ form.rate }} {{ form.unit }}。BOM、库存按库存单位记，采购单按采购单位下，入库时自动换算。</small></el-col>
          <el-col :span="8"><el-form-item label="门幅 (cm)"><el-input-number v-model="form.width" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="克重 (g/㎡)"><el-input-number v-model="form.gsm" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="成分"><el-input v-model="form.comp" placeholder="如 100% 棉" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="默认供应商">
            <el-select v-model="form.supplier" filterable clearable placeholder="算料后按它分采购单">
              <el-option v-for="s in db.suppliers" :key="s.id" :label="s.short + (['停用','黑名单'].includes(s.status) ? '（' + s.status + '，不能选）' : '')" :value="s.id" :disabled="['停用','黑名单'].includes(s.status)" />
            </el-select>
          </el-form-item></el-col>
          <el-col :span="12"><el-form-item label="供应商货号"><el-input v-model="form.supplierNo" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="参考单价"><el-input-number v-model="form.price" :precision="2" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="起订量"><el-input-number v-model="form.moq" :controls="false" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="采购周期(天)"><el-input-number v-model="form.lead" :controls="false" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <div class="panel-title" style="padding:6px 0 10px"><h2>SKU（颜色 / 规格）</h2><el-button size="small" icon="Plus" @click="addSku">加一个颜色或规格</el-button></div>
        <el-table :data="form.skus" size="small" empty-text="还没有 SKU。面料按颜色加，拉链纽扣这类按规格加。">
          <el-table-column label="颜色" width="150"><template #default="{ row }"><el-select v-model="row.color" size="small" clearable placeholder="无颜色"><el-option v-for="c in db.colors" :key="c.code" :label="c.name + ' ' + c.code" :value="c.code" /></el-select></template></el-table-column>
          <el-table-column label="供应商色号" width="140"><template #default="{ row }"><el-input v-model="row.colorNo" size="small" /></template></el-table-column>
          <el-table-column label="规格" width="110"><template #default="{ row }"><el-input v-model="row.spec" size="small" placeholder="如 60cm" /></template></el-table-column>
          <el-table-column label="SKU 编码" min-width="130"><template #default="{ row }"><span class="code">{{ row.sku || skuCode(row) }}</span></template></el-table-column>
          <el-table-column label="安全库存" width="110"><template #default="{ row }"><el-input-number v-model="row.safety" size="small" :min="0" :controls="false" style="width:90px" /></template></el-table-column>
          <el-table-column label="" width="90"><template #default="{ row, $index }">
            <el-tooltip v-if="!row.isNew && used(row)" content="已经有库存或被 BOM 用到，不能删，只能停用" placement="top"><el-button link disabled>删除</el-button></el-tooltip>
            <el-button v-else link type="danger" @click="removeSku($index)">删除</el-button>
          </template></el-table-column>
        </el-table>
      </el-form>
      <template #footer><el-button @click="drawer = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-drawer>
    <ProtoNote sec="§6.1 基础资料">一物一码：同一供应商、同一货号和色号不能建第二个物料 SKU，保存时会提示已有的那一个。</ProtoNote>
  </div>`,
})

/* ---------------- 颜色尺码 ---------------- */
const ColorSizePage = page({
  props: ['params', 'query'],
  methods: {
    styleCount(g) {
      return db.styles.filter((s) => s.sizeGroup === g.id).length
    },
  },
  template: `
  <div class="page">
    <PageHeader title="颜色与尺码组" description="款式 SKU = 款号-颜色码-尺码，比如 27CF001-KQ-M。颜色码和尺码建好以后别改，改了旧 SKU 就对不上了。" />
    <div class="columns" style="grid-template-columns: minmax(0,1fr) minmax(0,1fr)">
      <section class="panel">
        <div class="panel-title"><h2>颜色</h2><el-button size="small" type="primary" icon="Plus" @click="proto('弹出新增颜色：颜色码（2 位字母，建议拼音首字母）、名称、色系、色值')">新增颜色</el-button></div>
        <div class="panel-body">
          <el-table :data="db.colors" size="small" empty-text="还没有颜色">
            <el-table-column width="50"><template #default="{ row }"><i class="swatch" :style="{ background: row.hex, width: '22px', height: '22px' }"></i></template></el-table-column>
            <el-table-column prop="code" label="颜色码" width="80" class-name="code" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="family" label="色系" width="80" />
            <el-table-column label="操作" width="70"><template #default><el-button link type="primary" @click="proto('弹出编辑：只能改名称和色值，颜色码锁定')">编辑</el-button></template></el-table-column>
          </el-table>
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><h2>尺码组</h2><el-button size="small" type="primary" icon="Plus" @click="proto('弹出新增尺码组：组名 + 按顺序加尺码（拖动排序）')">新增尺码组</el-button></div>
        <div class="panel-body">
          <el-table :data="db.sizeGroups" size="small" empty-text="还没有尺码组">
            <el-table-column prop="name" label="组名" width="130" />
            <el-table-column label="尺码（按顺序）"><template #default="{ row }"><el-tag v-for="s in row.sizes" :key="s" size="small" type="info" style="margin-right:4px" class="code">{{ s }}</el-tag></template></el-table-column>
            <el-table-column label="用到的款" width="90" align="right" class-name="num"><template #default="{ row }">{{ styleCount(row) }}</template></el-table-column>
          </el-table>
          <p class="muted" style="font-size:12px;margin:12px 0 0">品类码（风衣 F、夹克 J、T 恤 T、针织衫 W、卫衣 S、裤装 K）和季节码（春 C、夏 X、秋 Q、冬 D）在「编码规则」里维护，生成款号时用。</p>
        </div>
      </section>
    </div>
    <ProtoNote sec="§6.1 基础资料">季节、年份、波段、风格、品类这些口径放在字典和品类树里，这里只放会进 SKU 码的颜色和尺码。</ProtoNote>
  </div>`,
})

/* ---------------- 仓库 ---------------- */
const WarehousePage = page({
  props: ['params', 'query'],
  template: `
  <div class="page">
    <PageHeader title="仓库" description="物料仓、成品仓是真实的仓库；工厂仓是虚拟的，一个加工厂一个，记“料在哪个工厂手上”。">
      <template #actions><el-button type="primary" icon="Plus" @click="proto('弹出新增仓库；工厂仓在加工厂准入时自动建，不用手动加')">新增仓库</el-button></template>
    </PageHeader>
    <div class="callout info">领料 = 把料从物料仓调到工厂仓；面料直接从供应商发到工厂 = 采购入库直接入工厂仓。这样“发了多少料给工厂、还剩多少”随时查得到（二期再加回货核销）。</div>
    <section class="panel"><div class="panel-body">
      <el-table :data="db.warehouses" empty-text="还没有仓库">
        <el-table-column prop="code" label="编码" width="90" class-name="code" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="类型" width="100"><template #default="{ row }"><span class="mode-tag" :class="{ accent: row.type === '工厂仓' }">{{ row.type }}</span></template></el-table-column>
        <el-table-column label="绑定加工厂" width="120"><template #default="{ row }">{{ row.factory ? supName(row.factory) : '—' }}</template></el-table-column>
        <el-table-column prop="address" label="地址" min-width="220" />
        <el-table-column prop="keeper" label="负责人" width="90" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
      </el-table>
    </div></section>
    <ProtoNote sec="§6.1 基础资料、§6.6 物料">货位（仓库下面的库位）放二期；一期库存只到仓库。</ProtoNote>
  </div>`,
})

/* ---------------- 供应商 ---------------- */
const SupplierPage = page({
  props: ['params', 'query'],
  data: () => ({ tab: '全部', keyword: '', cat: '', drawer: false, current: null }),
  computed: {
    cats() {
      return [...new Set(db.suppliers.map((s) => s.cat))]
    },
    list() {
      return db.suppliers.filter((s) => (this.tab === '全部' || s.status === this.tab) && (!this.cat || s.cat === this.cat) && (!this.keyword || (s.id + s.short + s.name).includes(this.keyword)))
    },
    counts() {
      const c = { 全部: db.suppliers.length }
      for (const s of db.suppliers) c[s.status] = (c[s.status] || 0) + 1
      return c
    },
  },
  methods: {
    expired(s) {
      return s.certs.filter((c) => c.until !== '长期' && c.until < TODAY).length
    },
    open(s) {
      this.current = s
      this.drawer = true
    },
    admit(s) {
      ElMessageBox.confirm(`准入以后，「${s.short}」可以被选进采购单、加工单。资质附件齐了吗？`, '准入供应商', { confirmButtonText: '准入', cancelButtonText: '再看看' })
        .then(() => {
          s.status = '合作中'
          ElMessage.success('已准入' + (['成衣厂', '加工厂'].includes(s.cat) ? '，同时建好了它的工厂仓' : ''))
        })
        .catch(() => {})
    },
    block(s) {
      ElMessageBox.prompt('拉黑后，新建采购单、加工单时选不到它；已经下的单照常执行，但会标红提醒。原因会写进操作日志。', `把「${s.short}」拉黑`, { confirmButtonText: '拉黑', cancelButtonText: '取消', inputPlaceholder: '写清原因，如 两批面料克重不足', inputValidator: (v) => !!v || '原因要写', confirmButtonClass: 'el-button--danger' })
        .then(({ value }) => {
          s.status = '黑名单'
          s.blackReason = value
          ElMessage.success('已拉黑')
        })
        .catch(() => {})
    },
    stop(s) {
      ElMessageBox.confirm(`停用后，新单据里选不到「${s.short}」；已有单据不受影响，随时可以重新启用。`, '停用供应商', { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' })
        .then(() => {
          s.status = '停用'
          ElMessage.success('已停用')
        })
        .catch(() => {})
    },
    restore(s) {
      s.status = '合作中'
      ElMessage.success('已恢复合作')
    },
  },
  template: `
  <div class="page">
    <PageHeader title="供应商档案" description="面料商、辅料商、成衣厂、加工厂、外协（印绣洗）都在这里。只有「合作中」的能被选进新单据。">
      <template #actions>
        <el-button icon="Upload" @click="proto('从 Excel 导入供应商，旧系统导出的供应商表按模板转一次')">从 Excel 导入</el-button>
        <el-button type="primary" icon="Plus" @click="proto('弹出新增供应商，新建后默认「待准入」，补齐资质后点准入')">新增供应商</el-button>
      </template>
    </PageHeader>
    <section class="panel">
      <el-tabs v-model="tab" class="status-tabs">
        <el-tab-pane v-for="t in ['全部','合作中','待准入','停用','黑名单']" :key="t" :name="t" :label="t + ' ' + (counts[t] || 0)" />
      </el-tabs>
      <el-form class="filter-bar" inline @submit.prevent>
        <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="编码、简称、全称" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="cat" clearable placeholder="全部"><el-option v-for="c in cats" :key="c" :label="c" :value="c" /></el-select></el-form-item>
      </el-form>
      <div class="panel-body">
        <el-table :data="list" empty-text="这个状态下没有供应商">
          <el-table-column prop="id" label="编码" width="74" class-name="code" />
          <el-table-column label="简称 / 全称" min-width="180"><template #default="{ row }"><a class="link" @click="open(row)">{{ row.short }}</a><div class="muted" style="font-size:12px">{{ row.name }}</div></template></el-table-column>
          <el-table-column prop="cat" label="分类" width="120" />
          <el-table-column prop="level" label="等级" width="60" align="center" />
          <el-table-column label="结算" width="100"><template #default="{ row }">{{ row.settle }}{{ row.days ? ' ' + row.days + ' 天' : '' }}</template></el-table-column>
          <el-table-column prop="invoice" label="发票" width="90" />
          <el-table-column label="联系人" width="120"><template #default="{ row }">{{ row.contact }}<div class="muted num" style="font-size:12px">{{ row.phone }}</div></template></el-table-column>
          <el-table-column label="资质" width="100"><template #default="{ row }">
            <span v-if="!row.certs.length" class="warn-text">没有附件</span>
            <span v-else>{{ row.certs.length }} 份<span v-if="expired(row)" class="late-text">，{{ expired(row) }} 份过期</span></span>
          </template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tooltip v-if="row.blackReason" :content="row.blackReason" placement="top"><span><StatusTag :status="row.status" /></span></el-tooltip><StatusTag v-else :status="row.status" /></template></el-table-column>
          <el-table-column label="操作" width="170"><template #default="{ row }">
            <el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button v-if="row.status === '待准入'" link type="primary" @click="admit(row)">准入</el-button>
            <el-button v-if="row.status === '合作中'" link type="primary" @click="stop(row)">停用</el-button>
            <el-button v-if="row.status === '停用'" link type="primary" @click="restore(row)">恢复</el-button>
            <el-button v-if="row.status !== '黑名单'" link type="danger" @click="block(row)">拉黑</el-button>
          </template></el-table-column>
        </el-table>
      </div>
    </section>

    <el-drawer v-model="drawer" :title="current ? current.short + ' · ' + current.id : ''" size="56%">
      <template v-if="current">
        <div v-if="current.status === '黑名单'" class="callout danger">已拉黑：{{ current.blackReason }}</div>
        <dl class="desc-grid" style="grid-template-columns:repeat(2,minmax(0,1fr))">
          <div class="wide"><dt>全称</dt><dd>{{ current.name }}</dd></div>
          <div><dt>分类</dt><dd>{{ current.cat }}</dd></div>
          <div><dt>等级</dt><dd>{{ current.level }}</dd></div>
          <div><dt>结算方式 / 账期</dt><dd>{{ current.settle }} {{ current.days ? current.days + ' 天' : '' }}</dd></div>
          <div><dt>发票</dt><dd>{{ current.invoice }}</dd></div>
          <div><dt>联系人</dt><dd>{{ current.contact }} <span class="num">{{ current.phone }}</span></dd></div>
          <div><dt>负责采购 / 跟单</dt><dd>{{ current.buyer }}</dd></div>
          <div class="wide"><dt>开户行 / 账号</dt><dd class="muted">（示意）某某银行 某某支行 · 6222 **** **** 0012</dd></div>
        </dl>
        <div class="panel-title" style="padding:20px 0 10px"><h2>资质附件</h2><el-button size="small" icon="Upload" @click="proto('上传营业执照、验厂报告、检测证书并填有效期；到期前 30 天首页提醒')">上传资质</el-button></div>
        <el-table :data="current.certs" size="small" empty-text="还没传资质。准入前至少要有营业执照。">
          <el-table-column prop="name" label="资质" />
          <el-table-column label="有效期至" width="140" class-name="num"><template #default="{ row }"><span :class="row.until !== '长期' && row.until < TODAY ? 'late-text' : ''">{{ row.until }}{{ row.until !== '长期' && row.until < TODAY ? '（已过期）' : '' }}</span></template></el-table-column>
          <el-table-column label="" width="80"><template #default><el-button link type="primary" @click="proto('预览附件')">查看</el-button></template></el-table-column>
        </el-table>
        <p class="muted" style="font-size:12px;margin-top:16px">邀请供应商自己注册、实名认证、线上验厂属于“外部协同门户”，等甲方定了外部账号范围（待决问题 Q02）再做。</p>
      </template>
    </el-drawer>
    <ProtoNote sec="§6.8 供应商">一期：档案、分类、准入状态、资质附件、停用和黑名单；绩效考核、合作历史放后面。</ProtoNote>
  </div>`,
})
