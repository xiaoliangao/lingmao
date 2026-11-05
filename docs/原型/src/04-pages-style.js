/* ============================================================
 * 款式档案、款式详情（SKU、资料包）、研发打样（二期）
 * ============================================================ */
const SOURCE_TONE = { 我方采购: '', 客供: 'accent', 加工厂自备: '' }

const StyleListPage = page({
  props: ['params', 'query'],
  data: () => ({ view: 'card', keyword: '', status: '' }),
  computed: {
    list() {
      return db.styles.filter((s) => (!this.keyword || (s.code + s.name + s.customerNo + s.designer).includes(this.keyword)) && (!this.status || s.status === this.status))
    },
  },
  methods: {
    packStatus(s) {
      const p = effectivePack(s.code)
      if (p) return { text: '资料包 ' + p.ver + ' 已生效', tone: 'done' }
      const draft = packsOf(s.code).find((x) => x.status === '草稿')
      return draft ? { text: '资料包 ' + draft.ver + ' 草稿', tone: 'pending' } : { text: '还没有资料包', tone: 'off' }
    },
    sizeName(s) {
      return sizeGroupOf(s.sizeGroup)?.name
    },
  },
  template: `
  <div class="page">
    <PageHeader title="款式档案" description="大货款的唯一来源：款号、颜色尺码（SKU）、资料包（BOM + 工艺）。下单、算料、外发都从这里取数。">
      <template #actions>
        <el-radio-group v-model="view" size="default"><el-radio-button value="card">图卡</el-radio-button><el-radio-button value="table">表格</el-radio-button></el-radio-group>
        <el-button icon="Upload" @click="proto('从 Excel 导入款式和 SKU（旧系统导出的款式信息按模板转一次）')">从 Excel 导入</el-button>
        <el-button type="primary" icon="Plus" @click="proto('新建款式：选年份、季节、品类后自动生成款号，如 27CF001；也可以从样衣「转大货」生成')">新增款式</el-button>
      </template>
    </PageHeader>
    <section class="panel">
      <el-form class="filter-bar" inline @submit.prevent>
        <el-form-item label="关键字"><el-input v-model="keyword" clearable placeholder="款号、名称、客户款号、设计师" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="status" clearable placeholder="全部" style="width:110px"><el-option label="启用" value="启用" /><el-option label="停用" value="停用" /></el-select></el-form-item>
      </el-form>
      <div class="panel-body">
        <div v-if="view === 'card'" class="cards">
          <div v-for="s in list" :key="s.code" class="card" role="link" tabindex="0" @click="go('/styles/' + s.code)" @keydown.enter="go('/styles/' + s.code)">
            <div class="pic"><GarmentThumb :shape="s.shape" :color="s.colors[0]" :size="120" style="border:0;background:transparent" /></div>
            <div class="info">
              <div class="title"><span class="code">{{ s.code }}</span><StatusTag :status="s.status" /></div>
              <div>{{ s.name }}</div>
              <div class="meta">{{ s.year }} {{ s.season }} · {{ s.cat }} · {{ sizeName(s) }}</div>
              <div style="margin-top:6px"><ColorChip v-for="c in s.colors" :key="c" :code="c" /></div>
              <div style="margin-top:4px"><StatusTag :tone="packStatus(s).tone">{{ packStatus(s).text }}</StatusTag></div>
            </div>
          </div>
        </div>
        <el-table v-else :data="list" empty-text="没有符合条件的款式" @row-click="(r) => go('/styles/' + r.code)">
          <el-table-column width="60"><template #default="{ row }"><GarmentThumb :shape="row.shape" :color="row.colors[0]" :size="38" /></template></el-table-column>
          <el-table-column prop="code" label="款号" width="96" class-name="code" />
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="cat" label="品类" width="120" />
          <el-table-column label="年份季节" width="90"><template #default="{ row }">{{ row.year }} {{ row.season }}</template></el-table-column>
          <el-table-column prop="wave" label="波段" width="64" />
          <el-table-column prop="designer" label="设计师" width="80" />
          <el-table-column label="客户 / 客户款号" width="150"><template #default="{ row }">{{ custName(row.customer) }}<div class="muted code" style="font-size:12px">{{ row.customerNo }}</div></template></el-table-column>
          <el-table-column label="颜色 × 尺码" width="140"><template #default="{ row }">{{ row.colors.length }} 色 × {{ sizeName(row) }}</template></el-table-column>
          <el-table-column label="吊牌价" width="80" align="right" class-name="num"><template #default="{ row }">{{ money(row.tagPrice) }}</template></el-table-column>
          <el-table-column label="资料包" width="150"><template #default="{ row }"><StatusTag :tone="packStatus(row).tone">{{ packStatus(row).text }}</StatusTag></template></el-table-column>
          <el-table-column label="状态" width="76"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        </el-table>
      </div>
    </section>
    <ProtoNote sec="§6.2 款式">样衣（研发阶段）和款式（大货）分两张表：没通过的样衣不会混进款式列表。</ProtoNote>
  </div>`,
})

const StyleDetailPage = page({
  props: ['params', 'query'],
  data() {
    const s = styleOf(this.params.id)
    const packs = packsOf(this.params.id)
    return {
      tab: this.query.tab || 'basic',
      packTab: 'bom',
      bomOpen: ['大身'],
      skuColors: s ? [...s.colors] : [],
      skuGroup: s ? s.sizeGroup : '',
      ver: (packs.find((p) => p.status === '已生效') || packs[0])?.ver || '',
      printing: false,
    }
  },
  computed: {
    s() {
      return styleOf(this.params.id)
    },
    sizes() {
      return sizeGroupOf(this.skuGroup)?.sizes || []
    },
    skuRows() {
      const rows = []
      for (const c of this.skuColors) for (const z of this.sizes) {
        const exists = this.s.colors.includes(c) && this.s.sizeGroup === this.skuGroup
        rows.push({ sku: `${this.s.code}-${c}-${z}`, color: c, size: z, exists, used: this.usedIn(c) })
      }
      return rows
    },
    removedUsed() {
      return this.s.colors.filter((c) => !this.skuColors.includes(c) && this.usedIn(c))
    },
    packs() {
      return packsOf(this.s.code)
    },
    pack() {
      return this.packs.find((p) => p.ver === this.ver)
    },
    processTotal() {
      const list = this.pack?.process || []
      return { sec: list.reduce((a, p) => a + p.sec, 0), price: list.reduce((a, p) => a + p.price, 0) }
    },
    bomCost() {
      return (this.pack?.bom || []).reduce((a, b) => a + this.lineCost(b), 0)
    },
    logs() {
      return db.logs[this.s.code] || []
    },
    orders() {
      return db.orders.filter((o) => o.lines.some((l) => l.style === this.s.code))
    },
  },
  methods: {
    usedIn(color) {
      return db.orders.some((o) => o.status !== '草稿' && o.lines.some((l) => l.style === this.s.code && l.qty[color]))
    },
    saveSkus() {
      if (this.removedUsed.length) return ElMessage.error(`${this.removedUsed.map((c) => colorOf(c).name).join('、')} 已经有订单在用，不能去掉，只能停用这个颜色`)
      const added = this.skuRows.filter((r) => !r.exists).length
      this.s.colors = [...this.skuColors]
      this.s.sizeGroup = this.skuGroup
      ;(db.logs[this.s.code] ||= []).unshift({ at: TODAY + ' 15:00', by: db.me.name, text: `颜色尺码改为 ${this.skuColors.map((c) => colorOf(c).name).join('、')} × ${sizeGroupOf(this.skuGroup).name}，新增 SKU ${added} 个` })
      ElMessage.success(`保存好了，新增 SKU ${added} 个`)
    },
    mat(code) {
      return matOf(code) || {}
    },
    usageText(b) {
      if (b.qtyMode === '统一') return fmtQ(b.usage)
      const v = Object.values(b.usage)
      return fmtQ(Math.min(...v)) + ' ~ ' + fmtQ(Math.max(...v))
    },
    avgUsage(b) {
      if (b.qtyMode === '统一') return b.usage
      const v = Object.values(b.usage)
      return v.reduce((a, x) => a + x, 0) / v.length
    },
    lineCost(b) {
      if (b.source !== '我方采购') return 0
      return this.avgUsage(b) * (1 + b.loss / 100) * (this.mat(b.mat).price || 0)
    },
    skuOf(b, color) {
      return b.colorMode === '随款色' ? b.colorMap[color] : b.fixedSku
    },
    colorNo(sku) {
      return skuInfo(sku).sku?.colorNo || ''
    },
    newVersion() {
      const base = this.packs.find((p) => p.status === '已生效') || this.packs[0]
      const n = Math.max(...this.packs.map((p) => Number(p.ver.slice(1)))) + 1
      const copy = JSON.parse(JSON.stringify(base))
      Object.assign(copy, { ver: 'V' + n, status: '草稿', date: TODAY, by: db.me.name, note: '从 ' + base.ver + ' 复制，改完再生效' })
      this.packs.unshift(copy)
      this.ver = copy.ver
      ElMessage.success(`复制出 ${copy.ver}（草稿）。已生效的 ${base.ver} 不受影响`)
    },
    activate() {
      const old = this.packs.find((p) => p.status === '已生效')
      ElMessageBox.confirm(`${this.pack.ver} 生效后，${old ? old.ver + ' 自动作废；' : ''}以后新审核的订单用 ${this.pack.ver}。已经审核的订单锁定的是原来的版本，不会被改动。`, '生效资料包', { confirmButtonText: '生效', cancelButtonText: '取消' })
        .then(() => {
          if (old) old.status = '已作废'
          this.pack.status = '已生效'
          ;(db.logs[this.s.code] ||= []).unshift({ at: TODAY + ' 15:10', by: db.me.name, text: `资料包 ${this.pack.ver} 生效${old ? '，' + old.ver + ' 作废' : ''}` })
          ElMessage.success('已生效')
        })
        .catch(() => {})
    },
    sizesOfStyle,
  },
  template: `
  <div class="page" v-if="s">
    <PageHeader :title="s.code + ' ' + s.name" :description="s.cat + ' · ' + s.year + ' ' + s.season + ' · 波段 ' + s.wave + ' · 设计师 ' + s.designer">
      <template #title-extra><StatusTag :status="s.status" /></template>
      <template #actions>
        <el-button icon="CopyDocument" @click="proto('复制成新款：带出资料包，改款号和颜色')">复制为新款</el-button>
        <el-button icon="Printer" @click="printing = true">打印资料包</el-button>
        <el-button type="primary" icon="Edit" @click="proto('编辑基本信息；款号不能改')">编辑</el-button>
      </template>
    </PageHeader>

    <section class="panel"><div class="panel-body">
      <el-tabs v-model="tab" class="sub-tabs">
        <el-tab-pane label="基本信息" name="basic">
          <div style="display:flex;gap:24px;align-items:flex-start">
            <GarmentThumb :shape="s.shape" :color="s.colors[0]" :size="150" />
            <dl class="desc-grid" style="flex:1">
              <div><dt>款号</dt><dd class="code">{{ s.code }}</dd></div>
              <div><dt>款式名称</dt><dd>{{ s.name }}</dd></div>
              <div><dt>品类</dt><dd>{{ s.cat }}</dd></div>
              <div><dt>年份 / 季节 / 波段</dt><dd>{{ s.year }} / {{ s.season }} / {{ s.wave }}</dd></div>
              <div><dt>风格 / 性别</dt><dd>{{ s.styleTag }} / {{ s.gender }}</dd></div>
              <div><dt>来源</dt><dd>{{ s.source }}<template v-if="s.sample"> · <a class="link code" @click="go('/samples/' + s.sample)">{{ s.sample }}</a></template></dd></div>
              <div><dt>客户 / 客户款号</dt><dd>{{ custName(s.customer) }} <span class="code">{{ s.customerNo }}</span></dd></div>
              <div><dt>默认加工厂</dt><dd>{{ supName(s.factory) }}</dd></div>
              <div><dt>吊牌价</dt><dd class="num">{{ money(s.tagPrice) }}</dd></div>
              <div><dt>成分</dt><dd>{{ s.comp }}</dd></div>
              <div><dt>执行标准</dt><dd>{{ s.standard }}</dd></div>
              <div><dt>安全类别</dt><dd>{{ s.safety }}</dd></div>
              <div><dt>上市日期</dt><dd class="num">{{ s.launch }}</dd></div>
              <div class="wide"><dt>颜色</dt><dd><ColorChip v-for="c in s.colors" :key="c" :code="c" /></dd></div>
              <div><dt>尺码组</dt><dd>{{ sizeGroupOf(s.sizeGroup).name }}</dd></div>
              <div class="full"><dt>用到这个款的订单</dt><dd><a v-for="o in orders" :key="o.no" class="link code" style="margin-right:12px" @click="go('/orders/' + o.no)">{{ o.no }}</a><span v-if="!orders.length" class="muted">还没有</span></dd></div>
            </dl>
          </div>
        </el-tab-pane>

        <el-tab-pane label="颜色尺码 SKU" name="sku">
          <div class="inline-actions">
            <span>颜色</span>
            <el-select v-model="skuColors" multiple style="width:320px" placeholder="选这个款有的颜色">
              <el-option v-for="c in db.colors" :key="c.code" :label="c.name + ' ' + c.code" :value="c.code" />
            </el-select>
            <span style="margin-left:12px">尺码组</span>
            <el-select v-model="skuGroup" style="width:160px"><el-option v-for="g in db.sizeGroups" :key="g.id" :label="g.name" :value="g.id" /></el-select>
            <el-button type="primary" style="margin-left:12px" @click="saveSkus">保存，生成 {{ skuRows.length }} 个 SKU</el-button>
          </div>
          <div v-if="removedUsed.length" class="callout danger">{{ removedUsed.map((c) => colorOf(c).name).join('、') }} 已经有订单在用，不能去掉。不想再做的颜色请在下表里停用。</div>
          <el-table :data="skuRows" size="small" empty-text="先选颜色和尺码组，SKU 会按 颜色 × 尺码 自动列出来">
            <el-table-column prop="sku" label="SKU 码" width="170" class-name="code" />
            <el-table-column label="颜色" width="130"><template #default="{ row }"><ColorChip :code="row.color" /></template></el-table-column>
            <el-table-column prop="size" label="尺码" width="80" class-name="code" />
            <el-table-column label="吊牌价" width="100" align="right" class-name="num"><template #default>{{ money(s.tagPrice) }}</template></el-table-column>
            <el-table-column label="条码" width="150" class-name="num"><template #default="{ row }"><span class="muted">二期（国标码）</span></template></el-table-column>
            <el-table-column label="" min-width="140"><template #default="{ row }">
              <StatusTag v-if="!row.exists" tone="pending">保存后新增</StatusTag>
              <StatusTag v-else-if="row.used" tone="done">已有订单在用</StatusTag>
              <StatusTag v-else tone="done">已有</StatusTag>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="资料包（BOM + 工艺）" name="pack">
          <div v-if="!packs.length"><BlankState title="还没有资料包" description="资料包 = BOM + 工艺单。可以新建，也可以从样衣转大货时自动复制过来。"><el-button type="primary" @click="proto('新建资料包 V1')">新建资料包</el-button></BlankState></div>
          <div v-else class="versions">
            <aside>
              <div v-for="p in packs" :key="p.ver" class="version-item" :class="{ active: p.ver === ver }" role="button" tabindex="0" @click="ver = p.ver" @keydown.enter="ver = p.ver">
                <div class="top"><span class="code">{{ p.ver }}</span><StatusTag :status="p.status" /></div>
                <small>{{ p.date }} · {{ p.by }}</small>
                <small>{{ p.note }}</small>
              </div>
              <el-button style="width:100%" icon="CopyDocument" @click="newVersion">复制生效版，改成新版本</el-button>
              <p class="muted" style="font-size:12px;line-height:1.6">同一个款同时只有一个「已生效」版本。订单审核时锁定当时的版本，之后改资料包不会悄悄改掉在做的单。</p>
            </aside>
            <div v-if="pack">
              <div class="inline-actions">
                <strong class="code">{{ pack.ver }}</strong><StatusTag :status="pack.status" />
                <span class="muted">{{ pack.note }}</span>
                <span style="flex:1"></span>
                <el-button v-if="pack.status === '草稿'" type="primary" @click="activate">生效这个版本</el-button>
                <el-button v-if="pack.status === '草稿'" icon="Plus" @click="proto('加一行 BOM：选部位、物料、颜色方式、用量方式')">加一行物料</el-button>
              </div>
              <el-tabs v-model="packTab">
                <el-tab-pane label="BOM" name="bom">
                  <el-table :data="pack.bom" row-key="part" :expand-row-keys="bomOpen" size="small" empty-text="这个版本还没有物料">
                    <el-table-column type="expand">
                      <template #default="{ row }">
                        <div class="bom-expand">
                          <div>
                            <h4>配色：款式每个颜色用哪个物料 SKU</h4>
                            <table class="mini-table">
                              <thead><tr><th>款式颜色</th><th>物料 SKU</th><th>供应商色号</th></tr></thead>
                              <tbody><tr v-for="c in s.colors" :key="c"><td><ColorChip :code="c" /></td><td class="code">{{ skuOf(row, c) || '没配，算料会报错' }}</td><td>{{ colorNo(skuOf(row, c)) }}</td></tr></tbody>
                            </table>
                          </div>
                          <div>
                            <h4>{{ row.qtyMode === '分码' ? '分码用量（' + mat(row.mat).unit + ' / 件）' : '统一用量' }}</h4>
                            <table v-if="row.qtyMode === '分码'" class="mini-table sizes">
                              <thead><tr><th v-for="z in Object.keys(row.usage)" :key="z" class="code">{{ z }}</th></tr></thead>
                              <tbody><tr><td v-for="z in Object.keys(row.usage)" :key="z" class="num">{{ row.usage[z] }}</td></tr></tbody>
                            </table>
                            <p v-else style="margin:0">每件 {{ row.usage }} {{ mat(row.mat).unit }}，各尺码一样。</p>
                            <p class="muted" style="margin:6px 0 0;font-size:12px">损耗 {{ row.loss }}%：算料时 需求 = 件数 × 用量 × (1 + {{ row.loss }}%)</p>
                          </div>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column prop="part" label="部位" width="80" />
                    <el-table-column label="物料" min-width="170"><template #default="{ row }"><span class="code">{{ row.mat }}</span> {{ mat(row.mat).name }}</template></el-table-column>
                    <el-table-column label="颜色" width="84"><template #default="{ row }"><span class="mode-tag" :class="{ accent: row.colorMode === '随款色' }">{{ row.colorMode }}</span></template></el-table-column>
                    <el-table-column label="用量" width="140"><template #default="{ row }"><span class="mode-tag" :class="{ accent: row.qtyMode === '分码' }">{{ row.qtyMode }}</span> <span class="num">{{ usageText(row) }}</span> {{ mat(row.mat).unit }}</template></el-table-column>
                    <el-table-column label="损耗" width="64" align="right" class-name="num"><template #default="{ row }">{{ row.loss }}%</template></el-table-column>
                    <el-table-column label="物料来源" width="90"><template #default="{ row }"><span class="mode-tag" :class="{ accent: row.source === '客供' }">{{ row.source }}</span></template></el-table-column>
                    <el-table-column label="默认供应商" width="90"><template #default="{ row }">{{ row.source === '我方采购' ? supName(mat(row.mat).supplier) : '—' }}</template></el-table-column>
                    <el-table-column label="单件料成本" width="100" align="right" class-name="num"><template #default="{ row }">{{ lineCost(row) ? money(lineCost(row)) : '—' }}</template></el-table-column>
                  </el-table>
                  <p class="muted" style="text-align:right;margin:10px 0 0;font-size:13px">我方采购料参考成本（按平均尺码）：<b class="num">{{ money(bomCost) }}</b> / 件。客供料、加工厂自备料不计。</p>
                </el-tab-pane>
                <el-tab-pane label="工艺单" name="process">
                  <el-table :data="pack.process" size="small" empty-text="这个版本还没写工艺。工厂按资料包做货，工艺要求写在这里。">
                    <el-table-column type="index" label="序号" width="60" />
                    <el-table-column prop="name" label="工序" width="96" />
                    <el-table-column prop="type" label="类别" width="70" />
                    <el-table-column prop="req" label="工艺要求" min-width="300" />
                    <el-table-column label="图示" width="70"><template #default><el-button link type="primary" @click="proto('打开工序图片大图')">2 张</el-button></template></el-table-column>
                    <el-table-column label="标准工时" width="90" align="right" class-name="num"><template #default="{ row }">{{ row.sec }} 秒</template></el-table-column>
                    <el-table-column label="参考工价" width="90" align="right" class-name="num"><template #default="{ row }">{{ money(row.price) }}</template></el-table-column>
                  </el-table>
                  <p v-if="pack.process.length" class="muted" style="text-align:right;margin:10px 0 0;font-size:13px">合计 <b class="num">{{ processTotal.sec }}</b> 秒 · 参考加工价 <b class="num">{{ money(processTotal.price) }}</b> / 件（排单时给加工单做参考价）</p>
                </el-tab-pane>
                <el-tab-pane name="size"><template #label>尺寸表 <span class="phase-tag">二期</span></template><BlankState title="尺寸表二期做" description="部位 × 尺码的成衣尺寸和公差，质检和产前样对照用。" /></el-tab-pane>
                <el-tab-pane name="tag"><template #label>吊牌包装 <span class="phase-tag">二期</span></template><BlankState title="吊牌、水洗唛、包装资料二期做" description="一期先用款式上的成分、执行标准、安全类别字段打印吊牌。" /></el-tab-pane>
              </el-tabs>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="操作记录" name="log">
          <ul class="timeline-list" v-if="logs.length"><li v-for="l in logs" :key="l.at + l.text">{{ l.text }}<small>{{ l.at }} · {{ l.by }}</small></li></ul>
          <p v-else class="muted">还没有操作记录。</p>
        </el-tab-pane>
      </el-tabs>
    </div></section>

    <el-dialog v-model="printing" title="打印预览：生产资料包" width="760px">
      <div v-if="pack" style="font-size:13px">
        <div style="display:flex;justify-content:space-between;border-bottom:2px solid var(--lm-ink);padding-bottom:8px;margin-bottom:10px">
          <div><b style="font-size:18px">{{ s.code }} {{ s.name }}</b><div class="muted">资料包 {{ pack.ver }} · {{ pack.status }} · 打印于 {{ TODAY }}</div></div>
          <GarmentThumb :shape="s.shape" :color="s.colors[0]" :size="64" />
        </div>
        <p>颜色：<ColorChip v-for="c in s.colors" :key="c" :code="c" />　尺码：{{ sizeGroupOf(s.sizeGroup).sizes.join(' / ') }}　成分：{{ s.comp }}</p>
        <table class="mini-table"><thead><tr><th>部位</th><th>物料</th><th>用量</th><th>损耗</th><th>来源</th></tr></thead>
          <tbody><tr v-for="b in pack.bom" :key="b.part"><td>{{ b.part }}</td><td>{{ mat(b.mat).name }}</td><td class="num">{{ usageText(b) }} {{ mat(b.mat).unit }}</td><td class="num">{{ b.loss }}%</td><td>{{ b.source }}</td></tr></tbody></table>
        <table class="mini-table" style="margin-top:12px"><thead><tr><th>工序</th><th>工艺要求</th></tr></thead>
          <tbody><tr v-for="p in pack.process" :key="p.name"><td>{{ p.name }}</td><td>{{ p.req }}</td></tr></tbody></table>
        <p class="muted" style="margin-top:10px">一期：打印或导出 PDF 发给工厂。二期起工厂有账号后，在门户里直接看（待决问题 Q02）。</p>
      </div>
      <template #footer><el-button @click="printing = false">关闭</el-button><el-button type="primary" icon="Printer" @click="proto('调浏览器打印 / 导出 PDF')">打印</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.2 款式、§6.4 资料包">BOM 一行 = 部位 + 物料 + 颜色方式（固定 / 随款色）+ 用量方式（统一 / 分码）+ 损耗 + 物料来源（我方采购 / 客供 / 加工厂自备）。</ProtoNote>
  </div>
  <div v-else class="page"><BlankState title="没有这个款式" description="可能已经删除，或者链接里的款号写错了。"><el-button @click="go('/styles')">回到款式档案</el-button></BlankState></div>`,
})

/* ---------------- 研发打样（二期） ---------------- */
const SampleListPage = page({
  props: ['params', 'query'],
  methods: {
    current(s) {
      return s.rounds[s.rounds.length - 1]
    },
    curNode(r) {
      const n = r.nodes.find((x) => !x.at)
      return n ? n.name : '完成'
    },
  },
  template: `
  <div class="page">
    <PageHeader title="样衣与打样" phase="二期" description="样衣从打样需求开始，一版一版打，评审通过后「转大货」生成款式和资料包。">
      <template #actions><el-button type="primary" icon="Plus" @click="proto('新建样衣 + 第 1 版打样需求：打样方式（内部版房 / 外部工厂）、版师、需求日期')">新建打样需求</el-button></template>
    </PageHeader>
    <section class="panel"><div class="panel-body">
      <el-table :data="db.samples" empty-text="还没有样衣" @row-click="(r) => go('/samples/' + r.no)">
        <el-table-column width="60"><template #default="{ row }"><GarmentThumb :shape="row.shape" :color="row.colors[0]" :size="38" /></template></el-table-column>
        <el-table-column prop="no" label="样衣编号" width="150" class-name="code" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="designer" label="设计师" width="80" />
        <el-table-column label="版次" width="70" align="center"><template #default="{ row }">第 {{ current(row).round }} 版</template></el-table-column>
        <el-table-column label="当前打样" width="170"><template #default="{ row }"><StatusTag :status="current(row).status" /> <span class="muted" style="font-size:12px">{{ current(row).status === '打样中' ? '在「' + curNode(current(row)) + '」' : '' }}</span></template></el-table-column>
        <el-table-column label="打样方式" width="120"><template #default="{ row }">{{ row.way }}<span v-if="row.factory" class="muted"> · {{ supName(row.factory) }}</span></template></el-table-column>
        <el-table-column prop="fabric" label="面料来源" min-width="180" />
        <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="大货款号" width="96"><template #default="{ row }"><a v-if="row.style" class="link code" @click.stop="go('/styles/' + row.style)">{{ row.style }}</a><span v-else class="muted">—</span></template></el-table-column>
      </el-table>
    </div></section>
    <ProtoNote sec="§6.3 研发打样">一个样衣下面挂多版打样单（版次），每版走 接单 → 纸样 → 裁剪 → 车版 → 完成，完成后评审：通过 / 改版重做 / 淘汰。</ProtoNote>
  </div>`,
})

const SampleDetailPage = page({
  props: ['params', 'query'],
  data: () => ({ reviewing: false, review: { result: '通过', opinion: '', by: '林夏、何蔓' }, converting: false, conv: { name: '', copyBom: true, customer: '' } }),
  computed: {
    s() {
      return sampleOf(this.params.id)
    },
    last() {
      return this.s.rounds[this.s.rounds.length - 1]
    },
    newCode() {
      const n = db.styles.filter((x) => x.code.startsWith('27C')).length + 1
      return '27C' + (this.s.catCode || 'F') + String(n).padStart(3, '0')
    },
  },
  methods: {
    openReview() {
      this.review = { result: '通过', opinion: '', by: '林夏、何蔓' }
      this.reviewing = true
    },
    submitReview() {
      const r = this.review
      this.last.review = { at: TODAY, by: r.by, result: r.result === '改版重做' ? '改版' : r.result, opinion: r.opinion || '（没写意见）' }
      this.last.status = '已评审'
      if (r.result === '通过') this.s.status = '通过'
      else if (r.result === '淘汰') this.s.status = '已淘汰'
      else {
        this.s.status = '开发中'
        this.s.rounds.push({ no: nextNo('DY'), round: this.last.round + 1, kind: '改版样', status: '待派单', due: '', nodes: ['接单', '纸样', '裁剪', '车版', '完成'].map((name) => ({ name, at: '' })), review: null })
      }
      this.reviewing = false
      ElMessage.success(r.result === '改版重做' ? '已生成下一版打样单，改版原因带过去了' : '评审结果已保存')
    },
    // 随款色默认按颜色码配：款式颜色 YM → 物料里颜色也是 YM 的 SKU；配不上的留空，页面上标出来
    autoMap(mat) {
      return Object.fromEntries(this.s.colors.map((c) => [c, matOf(mat).skus.find((k) => k.color === c)?.sku]).filter(([, v]) => v))
    },
    openConvert() {
      this.conv = { name: this.s.name, copyBom: true, customer: '' }
      this.converting = true
    },
    convert() {
      const code = this.newCode
      db.styles.unshift({ code, name: this.conv.name, cat: this.s.cat, year: 2027, season: '春', wave: 'C2', styleTag: '休闲', gender: '女', designer: this.s.designer, source: '样衣转大货', sample: this.s.no, customer: this.conv.customer, customerNo: '', sizeGroup: this.s.sizeGroup, colors: [...this.s.colors], tagPrice: this.s.tagPrice, comp: '待补', standard: '待补', safety: '待补', launch: '', factory: this.s.factory || 'S004', status: '启用', shape: this.s.shape, disabledSkus: [] })
      db.packs[code] = this.conv.copyBom
        ? [{ ver: 'V1', status: '草稿', date: TODAY, by: db.me.name, note: '样衣 ' + this.s.no + ' 转大货自动生成，用量按大货复核后再生效', bom: [
            { part: '大身', mat: 'ML0001', colorMode: '随款色', colorMap: this.autoMap('ML0001'), qtyMode: '统一', usage: 1.2, loss: 5, source: '我方采购' },
            { part: '罗纹', mat: 'ML0004', colorMode: '随款色', colorMap: this.autoMap('ML0004'), qtyMode: '统一', usage: 0.15, loss: 5, source: '我方采购' },
          ], process: [] }]
        : []
      db.logs[code] = [{ at: TODAY + ' 16:00', by: db.me.name, text: `由样衣 ${this.s.no} 转大货，生成款号 ${code}` }]
      this.s.status = '已转大货'
      this.s.style = code
      this.converting = false
      ElMessage.success(`转大货完成，款号 ${code}`)
      go('/styles/' + code + '?tab=pack')
    },
  },
  template: `
  <div class="page" v-if="s">
    <PageHeader :title="s.no + ' ' + s.name" phase="二期" :description="s.cat + ' · ' + s.season + ' · 设计师 ' + s.designer + ' · 版师 ' + s.pattern">
      <template #title-extra><StatusTag :status="s.status" /></template>
      <template #actions>
        <el-button v-if="last.status === '待评审'" type="primary" @click="openReview">评审第 {{ last.round }} 版</el-button>
        <el-button v-if="s.status === '通过'" type="primary" icon="Promotion" @click="openConvert">转大货</el-button>
        <el-button v-if="s.status === '已转大货'" @click="go('/styles/' + s.style)">看大货款 {{ s.style }}</el-button>
        <el-button v-if="last.status === '打样中'" @click="proto('同步版师在手机上点的节点完成')">汇报打样进度</el-button>
      </template>
    </PageHeader>
    <FlowBar :steps="['开发中', '待评审', '通过', '已转大货']" :current="s.status === '已淘汰' ? '' : s.status" />
    <div class="columns" style="grid-template-columns:minmax(0,2fr) minmax(0,3fr)">
      <section class="panel block">
        <h2>样衣档案</h2>
        <div style="display:flex;gap:16px;align-items:flex-start">
          <GarmentThumb :shape="s.shape" :color="s.colors[0]" :size="96" />
          <dl class="desc-grid" style="grid-template-columns:repeat(2,minmax(0,1fr));flex:1">
            <div><dt>打样方式</dt><dd>{{ s.way }}</dd></div>
            <div><dt>打样工厂</dt><dd>{{ s.factory ? supName(s.factory) : '内部版房' }}</dd></div>
            <div class="wide"><dt>面料来源</dt><dd>{{ s.fabric }}</dd></div>
            <div class="wide"><dt>颜色</dt><dd><ColorChip v-for="c in s.colors" :key="c" :code="c" /></dd></div>
            <div><dt>尺码组</dt><dd>{{ s.sizeGroup }}</dd></div>
            <div><dt>预估吊牌价</dt><dd class="num">{{ money(s.tagPrice) }}</dd></div>
          </dl>
        </div>
      </section>
      <section class="panel block">
        <h2>打样版次</h2>
        <div v-for="r in [...s.rounds].reverse()" :key="r.no" style="border:1px solid var(--lm-line-soft);border-radius:6px;padding:12px 14px;margin-bottom:10px">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
            <div><b>第 {{ r.round }} 版</b> · {{ r.kind }} · <span class="code">{{ r.no }}</span></div>
            <StatusTag :status="r.status" />
          </div>
          <el-steps :active="r.nodes.filter((n) => n.at).length" finish-status="success" simple style="padding:10px 14px">
            <el-step v-for="n in r.nodes" :key="n.name" :title="n.name + (n.at ? ' ' + n.at : '')" />
          </el-steps>
          <div v-if="r.review" style="margin-top:10px;font-size:13px">评审 <StatusTag :status="r.review.result" />　{{ r.review.opinion }} <span class="muted">（{{ r.review.at }} · {{ r.review.by }}）</span></div>
          <div v-else-if="r.status === '打样中' && r.due < TODAY" class="late-text" style="margin-top:8px;font-size:13px">需求日期 {{ r.due }}，已过 {{ -daysLeft(r.due) }} 天</div>
        </div>
      </section>
    </div>

    <el-dialog v-model="reviewing" :title="'评审第 ' + last.round + ' 版'" width="480px">
      <el-form label-width="72px">
        <el-form-item label="结论"><el-radio-group v-model="review.result"><el-radio value="通过">通过</el-radio><el-radio value="改版重做">改版重做</el-radio><el-radio value="淘汰">淘汰</el-radio></el-radio-group></el-form-item>
        <el-form-item label="评审人"><el-input v-model="review.by" /></el-form-item>
        <el-form-item label="意见"><el-input v-model="review.opinion" type="textarea" :rows="3" :placeholder="review.result === '改版重做' ? '改哪里，会带到下一版打样单' : '可以不写'" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reviewing = false">取消</el-button><el-button type="primary" @click="submitReview">保存评审结果</el-button></template>
    </el-dialog>

    <el-dialog v-model="converting" title="样衣转大货" width="520px">
      <div class="callout info">会生成一个新款式，样衣信息带过去；样衣这边记下转大货的时间和款号，两边能互相点过去。</div>
      <el-form label-width="96px">
        <el-form-item label="款号"><span class="code" style="font-size:16px">{{ newCode }}</span><span class="muted" style="margin-left:8px;font-size:12px">27 年 · 春 C · 品类 {{ s.catCode }} · 流水</span></el-form-item>
        <el-form-item label="款式名称"><el-input v-model="conv.name" /></el-form-item>
        <el-form-item label="客户"><el-select v-model="conv.customer" clearable placeholder="自营款不选"><el-option v-for="c in db.customers" :key="c.id" :label="c.short" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="资料包"><el-checkbox v-model="conv.copyBom">把样衣的 BOM 复制成大货资料包 V1（草稿）</el-checkbox></el-form-item>
      </el-form>
      <template #footer><el-button @click="converting = false">取消</el-button><el-button type="primary" @click="convert">转大货，生成 {{ newCode }}</el-button></template>
    </el-dialog>
    <ProtoNote sec="§6.3 研发打样">「转大货」是甲方要求新增的节点：留住打样工厂、面料来源这些样衣信息，大货款能追溯到是哪一版样衣。</ProtoNote>
  </div>
  <div v-else class="page"><BlankState title="没有这个样衣" description="编号可能写错了。"><el-button @click="go('/samples')">回到样衣列表</el-button></BlankState></div>`,
})
