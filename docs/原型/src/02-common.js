/* ============================================================
 * 工具函数 + 通用小组件
 * ============================================================ */
const { ElMessage, ElMessageBox } = ElementPlus

function proto(text) {
  ElMessage({ message: '原型：这里会' + text, type: 'info', grouping: true, duration: 3500 })
}

function go(path) {
  location.hash = '#' + path
}

const round4 = (n) => Math.round(n * 10000) / 10000
const fmt = (n, d = 0) =>
  n === null || n === undefined || n === '' || Number.isNaN(n)
    ? '—'
    : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d })
const fmtQ = (n) => (n === null || n === undefined ? '—' : Number.isInteger(round4(n)) ? fmt(n, 0) : fmt(n, 2))
const money = (n) => '¥' + fmt(n, 2)

const colorOf = (code) => db.colors.find((c) => c.code === code) || { code, name: code || '—', hex: 'transparent' }
const sup = (id) => db.suppliers.find((s) => s.id === id)
const supName = (id) => (id ? sup(id)?.short || id : '—')
const cust = (id) => db.customers.find((c) => c.id === id)
const custName = (id) => (id ? cust(id)?.short || id : '—')
const ownerName = (o) => (o === '自有' ? '自有' : '客供 · ' + custName(o))
const whOf = (code) => db.warehouses.find((w) => w.code === code)
const whName = (code) => (code ? whOf(code)?.name || code : '—')
const matOf = (code) => db.materials.find((m) => m.code === code)
const styleOf = (code) => db.styles.find((s) => s.code === code)
const orderOf = (no) => db.orders.find((o) => o.no === no)
const prodOf = (no) => db.production.find((p) => p.no === no)
const purchaseOf = (no) => db.purchases.find((p) => p.no === no)
const sampleOf = (no) => db.samples.find((s) => s.no === no)
const sizeGroupOf = (id) => db.sizeGroups.find((g) => g.id === id)
const sizesOfStyle = (code) => sizeGroupOf(styleOf(code)?.sizeGroup)?.sizes || []

function skuInfo(sku) {
  for (const m of db.materials) {
    const s = m.skus.find((x) => x.sku === sku)
    if (s) return { mat: m, sku: s }
  }
  return { mat: null, sku: null }
}

function skuLabel(sku) {
  const { mat, sku: s } = skuInfo(sku)
  if (!mat) return sku || '—'
  const tail = [s.color ? colorOf(s.color).name : '', s.spec].filter(Boolean).join(' ')
  return mat.name + (tail ? ' · ' + tail : '')
}

const sumValues = (obj) => Object.values(obj || {}).reduce((a, b) => a + (Number(b) || 0), 0)
const matrixTotal = (qty) => Object.values(qty || {}).reduce((a, sizes) => a + sumValues(sizes), 0)
const orderQty = (o) => o.lines.reduce((a, l) => a + matrixTotal(l.qty), 0)
const orderAmount = (o) => o.lines.reduce((a, l) => a + matrixTotal(l.qty) * l.price, 0)
const days = (a, b) => Math.round((new Date(b) - new Date(a)) / 86400000)
const daysLeft = (d) => days(TODAY, d)

function packsOf(style) {
  return db.packs[style] || []
}

function packOf(style, ver) {
  return packsOf(style).find((p) => p.ver === ver)
}

function effectivePack(style) {
  return packsOf(style).find((p) => p.status === '已生效')
}

const TONES = {
  done: ['已审核', '已完成', '已到齐', '合作中', '启用', '已生效', '通过', '已转大货', '已关闭', '已结案', '已评审', '已入库'],
  pending: ['待审核', '待排单', '待评审', '待准入', '待处理', '待派单', '改版'],
  doing: ['生产中', '打样中', '处理中', '部分到货', '部分回货', '开发中', '已下单'],
  off: ['草稿', '停用', '已作废', '已淘汰', '已取消', '淘汰'],
  danger: ['黑名单', '延期', '逾期'],
}

function toneOf(status) {
  for (const [tone, list] of Object.entries(TONES)) if (list.includes(status)) return tone
  return 'off'
}

/* 生产节点：是否延期、进度 */
function nodeState(n) {
  if (n.ae) return days(n.pe, n.ae) > 0 ? 'done-late' : 'done'
  if (n.as) return n.pe < TODAY ? 'late' : 'doing'
  if (n.ps < TODAY) return 'late'
  return 'todo'
}

function prodCurrentNode(p) {
  return p.nodes.find((n) => !n.ae) || null
}

function prodLate(p) {
  if (p.status === '已完成') return false
  return p.nodes.some((n) => nodeState(n) === 'late') || p.due < TODAY
}

function prodReceived(p) {
  return matrixTotal(p.received)
}

function prodProgress(p) {
  const total = matrixTotal(p.qty)
  if (!total) return 0
  const doneNodes = p.nodes.filter((n) => n.ae).length
  const cur = prodCurrentNode(p)
  const partial = cur && cur.as ? Math.min(cur.qty / total, 0.95) : 0
  return Math.round(((doneNodes + partial) / p.nodes.length) * 100)
}

/* ---------------- 算料 ---------------- */
function availableStock(sku, owner) {
  return db.stock
    .filter((r) => r.sku === sku && r.owner === owner && whOf(r.wh)?.type === '物料仓')
    .reduce((a, r) => a + r.qty, 0)
}

function inTransit(sku) {
  return db.purchases
    .filter((p) => ['已审核', '部分到货'].includes(p.status))
    .flatMap((p) => p.lines)
    .filter((l) => l.sku === sku)
    .reduce((a, l) => a + Math.max(0, l.qty - l.inQty), 0)
}

function computeMrp(orderNos) {
  const rows = new Map()
  const issues = []
  for (const no of orderNos) {
    const o = orderOf(no)
    if (!o) continue
    for (const line of o.lines) {
      const pack = line.pack ? packOf(line.style, line.pack) : effectivePack(line.style)
      if (!pack) {
        issues.push(`${line.style} 还没有已生效的资料包，这一款没算进来`)
        continue
      }
      for (const [color, sizes] of Object.entries(line.qty)) {
        for (const [size, q] of Object.entries(sizes)) {
          if (!q) continue
          for (const b of pack.bom) {
            const sku = b.colorMode === '随款色' ? b.colorMap[color] : b.fixedSku
            if (!sku) {
              const msg = `${line.style} 的「${b.part}」没配 ${colorOf(color).name} 用哪个物料颜色`
              if (!issues.includes(msg)) issues.push(msg)
              continue
            }
            const u = b.qtyMode === '分码' ? b.usage[size] : b.usage
            const owner = b.source === '客供' ? o.customer || '自有' : '自有'
            const key = sku + '|' + b.source + '|' + owner
            let r = rows.get(key)
            if (!r) {
              const { mat } = skuInfo(sku)
              r = { key, sku, mat, source: b.source, owner, parts: new Set(), orders: new Set(), gross: 0, withLoss: 0, loss: b.loss, prep: 0 }
              rows.set(key, r)
            }
            r.parts.add(b.part)
            r.orders.add(no)
            r.gross += q * u
            r.withLoss += q * u * (1 + b.loss / 100)
          }
        }
      }
    }
  }
  const list = [...rows.values()].map((r) => {
    const available = r.source === '加工厂自备' ? 0 : availableStock(r.sku, r.owner)
    const transit = r.source === '我方采购' ? inTransit(r.sku) : 0
    return reactive({
      ...r,
      parts: [...r.parts].join('、'),
      orders: [...r.orders],
      gross: round4(r.gross),
      withLoss: round4(r.withLoss),
      available,
      transit,
      useTransit: 0, // 在途只作参考，默认不扣（PRD §6.6.1）
      use: null, // null = 默认取 min(可用库存, 需求)
      checked: true,
    })
  })
  return { rows: list, issues }
}

function mrpNeed(r) {
  return round4(r.withLoss * (1 + (r.prep || 0) / 100))
}

function mrpUse(r) {
  return r.use === null ? Math.min(r.available, mrpNeed(r)) : r.use
}

function mrpNet(r) {
  return Math.max(0, round4(mrpNeed(r) - mrpUse(r) - (r.useTransit || 0)))
}

function mrpSuggest(r) {
  const net = mrpNet(r)
  if (net <= 0 || !r.mat) return 0
  const inBuyUnit = Math.ceil(round4(net / (r.mat.rate || 1)))
  return Math.max(inBuyUnit, r.mat.moq || 0)
}

/* ---------------- 通用组件 ---------------- */
const StatusTag = {
  props: { status: String, tone: String },
  computed: {
    t() {
      return this.tone || toneOf(this.status)
    },
  },
  template: `<span class="status-tag" :class="t"><slot>{{ status }}</slot></span>`,
}

const PageHeader = {
  props: { title: String, description: String, phase: String },
  template: `
    <header class="page-header">
      <div>
        <h1>{{ title }}<span v-if="phase" class="phase-tag">{{ phase }}</span><slot name="title-extra" /></h1>
        <p v-if="description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </header>`,
}

const ProtoNote = {
  props: { sec: String },
  template: `<p class="proto-note"><b>原型说明</b> · 对应 PRD {{ sec }}。<slot /></p>`,
}

const ColorChip = {
  props: { code: String },
  computed: {
    c() {
      return colorOf(this.code)
    },
  },
  template: `<span class="color-chip"><i class="swatch" :style="{ background: c.hex }"></i>{{ c.name }}<span class="muted code" style="margin-left:4px;font-size:12px">{{ c.code }}</span></span>`,
}

const GARMENT_PATHS = {
  tee: 'M30 20 L46 10 Q60 20 74 10 L90 20 L106 42 L90 50 L84 42 L84 102 L36 102 L36 42 L30 50 L14 42 Z',
  coat: 'M36 12 L50 6 L60 26 L70 6 L84 12 L104 50 L92 56 L86 46 L90 108 L30 108 L34 46 L28 56 L16 50 Z',
  hoodie: 'M30 26 L44 16 Q46 4 60 4 Q74 4 76 16 L90 26 L106 48 L90 56 L84 48 L84 104 L36 104 L36 48 L30 56 L14 48 Z',
  cardigan: 'M32 18 L48 10 L60 34 L72 10 L88 18 L104 46 L90 54 L84 44 L84 104 L36 104 L36 44 L30 54 L16 46 Z',
  pants: 'M36 8 L84 8 L92 108 L68 108 L60 40 L52 108 L28 108 Z',
}

const GarmentThumb = {
  props: { shape: { type: String, default: 'tee' }, color: String, size: { type: Number, default: 40 } },
  computed: {
    hex() {
      return colorOf(this.color).hex
    },
    d() {
      return GARMENT_PATHS[this.shape] || GARMENT_PATHS.tee
    },
  },
  template: `
    <span class="thumb" :style="{ width: size + 'px', height: size + 'px' }">
      <svg :width="size * 0.8" :height="size * 0.8" viewBox="0 0 120 112" aria-hidden="true">
        <path :d="d" :fill="hex" stroke="rgb(28 34 48 / 35%)" stroke-width="2" stroke-linejoin="round" />
        <path v-if="shape === 'coat' || shape === 'cardigan'" d="M60 30 L60 104" stroke="rgb(28 34 48 / 30%)" stroke-width="2" />
      </svg>
    </span>`,
}

const BlankState = {
  props: { title: String, description: String },
  template: `
    <div class="blank-state">
      <div class="hang-tag" aria-hidden="true"></div>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.default" style="margin-top:16px"><slot /></div>
    </div>`,
}

/* 颜色 × 尺码矩阵。editable 时可以直接改数量；received 传进来时每格下面小字显示已回货 */
const QtyMatrix = {
  props: { qty: Object, sizes: Array, editable: Boolean, received: Object, colorOrder: Array },
  computed: {
    colors() {
      return this.colorOrder || Object.keys(this.qty || {})
    },
    total() {
      return matrixTotal(this.qty)
    },
  },
  methods: {
    rowTotal(c) {
      return sumValues(this.qty[c])
    },
    colTotal(s) {
      return this.colors.reduce((a, c) => a + (Number(this.qty[c]?.[s]) || 0), 0)
    },
    rec(c, s) {
      return this.received?.[c]?.[s] ?? 0
    },
    fmtQ,
  },
  components: { ColorChip },
  template: `
    <table class="matrix">
      <thead>
        <tr>
          <th>颜色 \\ 尺码</th>
          <th v-for="s in sizes" :key="s">{{ s }}</th>
          <th>合计</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="c in colors" :key="c">
          <td><ColorChip :code="c" /></td>
          <td v-for="s in sizes" :key="s">
            <el-input-number v-if="editable" v-model="qty[c][s]" :min="0" :step="10" size="small" controls-position="right" />
            <template v-else>
              {{ fmtQ(qty[c]?.[s] ?? 0) }}
              <span v-if="received" class="sub">回 {{ rec(c, s) }}</span>
            </template>
          </td>
          <td class="total">{{ fmtQ(rowTotal(c)) }}</td>
        </tr>
        <tr>
          <td class="total">合计</td>
          <td v-for="s in sizes" :key="s" class="total">{{ fmtQ(colTotal(s)) }}</td>
          <td class="total">{{ fmtQ(total) }}</td>
        </tr>
      </tbody>
    </table>`,
}

/* 单据状态流：steps = ['草稿','待审核',...]，current = 当前状态名 */
const FlowBar = {
  props: { steps: Array, current: String },
  computed: {
    idx() {
      return this.steps.indexOf(this.current)
    },
  },
  template: `
    <div class="flow-bar" role="list" aria-label="单据状态">
      <template v-for="(s, i) in steps" :key="s">
        <span v-if="i > 0" class="flow-line" :class="{ done: i <= idx }"></span>
        <span class="flow-step" role="listitem" :class="{ done: i < idx || (i === idx && i === steps.length - 1), current: i === idx && i < steps.length - 1 }"><i class="dot"></i>{{ s }}</span>
      </template>
    </div>`,
}

// 列表里一张单据的明细太长：只列前两行，后面写「等 N 行」
const linesBrief = (lines, withQty = true) => {
  const t = lines.slice(0, 2).map((l) => skuLabel(l.sku) + (withQty ? ' ' + fmtQ(l.qty) : '')).join('；')
  return lines.length > 2 ? `${t} 等 ${lines.length} 行` : t
}

const SHARED = { StatusTag, PageHeader, ProtoNote, ColorChip, GarmentThumb, BlankState, QtyMatrix, FlowBar }
// 页面组件统一挂上这些工具，模板里直接用
const H = {
  fmt, fmtQ, money, colorOf, supName, custName, ownerName, whName, skuLabel, skuInfo, matOf, styleOf,
  orderQty, orderAmount, matrixTotal, daysLeft, toneOf, go, proto,
  sizeGroupOf, sizesOfStyle, effectivePack, whOf, prodOf, cust, linesBrief,
}
function page(def) {
  return {
    ...def,
    data() {
      return { db, TODAY, ...(def.data ? def.data.call(this, this) : {}) }
    },
    components: { ...SHARED, ...(def.components || {}) },
    methods: { ...H, ...(def.methods || {}) },
  }
}
