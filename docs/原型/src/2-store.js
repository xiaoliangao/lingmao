/* ============================================================
 * 业务动作：所有改数据的地方都在这里，页面只调这些函数。
 * - 状态流转只看 FLOWS 一张表：按钮显示哪些、能不能做，都查它
 * - 动作出错抛 Err(消息, 明细列表)，页面统一弹出来
 * - 文件末尾 seed() 用这些动作"演"出 9 月到今天的业务，库存、回写才对得上
 * ============================================================ */
class Err extends Error { constructor(msg, list) { super(msg); this.list = list || [] } }
const fail = (msg, list) => { throw new Err(msg, list) }
const add = (list, obj) => { list.push(obj); return list[list.length - 1] } // 返回响应式对象

function nextNo(prefix) {
  const k = prefix + clock.today.replace(/-/g, '')
  db.seq[k] = (db.seq[k] || 0) + 1
  return k + String(db.seq[k]).padStart(4, '0')
}
function act(doc, text) { (doc.log ||= []).push({ at: clock.today, who: userName(db.me), text }) }

/* ---------- 状态流转表：动作 → [从哪些状态, 到什么状态] ---------- */
const FLOWS = {
  order: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已审核'], 反审核: ['已审核', '草稿'], 结案: [['已审核', '生产中'], '已结案'], 作废: [['草稿', '待审核'], '已取消'] },
  change: { 驳回: ['待审核', '已驳回'], 审核: ['待审核', '已审核'] },
  mrp: { 确认: ['草稿', '已确认'], 关闭: [['草稿', '已确认'], '已关闭'] },
  purchase: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已审核'], 反审核: ['已审核', '草稿'], 结案: [['已审核', '部分到货'], '已结案'], 作废: [['草稿', '待审核'], '已作废'] },
  stock: { 审核: ['草稿', '已审核'], 反审核: ['已审核', '草稿'], 作废: ['草稿', '已作废'] },
  production: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已下单'], 结案: [['已下单', '生产中', '部分回货'], '已结案'], 作废: [['草稿', '待审核'], '已作废'] },
  techpack: { 生效: ['草稿', '已生效'] },
  supplier: { 准入: ['待准入', '合作中'], 停用: ['合作中', '停用'], 启用: ['停用', '合作中'], 拉黑: [['待准入', '合作中', '停用'], '黑名单'], 解除黑名单: ['黑名单', '停用'] },
  style: { 启用: ['草稿', '启用'], 停用: ['启用', '停用'], 恢复: ['停用', '启用'] },
}
const can = (kind, doc, a) => { const f = FLOWS[kind]?.[a]; return !!f && [].concat(f[0]).includes(doc.status) }
const actionsOf = (kind, doc) => Object.keys(FLOWS[kind]).filter((a) => can(kind, doc, a))
function move(kind, doc, a, note) {
  if (!can(kind, doc, a)) fail(`${doc.no || doc.code || ''} 现在是「${doc.status}」，不能${a}`)
  doc.status = FLOWS[kind][a][1]
  act(doc, a + (note ? '：' + note : ''))
}
/* 带额外规则的动作；没列在这里的直接 move */
const RULES = {
  order: { 提交: submitOrder, 反审核: unauditOrder },
  purchase: { 反审核: unauditPurchase },
  production: { 审核: auditProduction },
  stock: { 审核: postStockDoc, 反审核: unpostStockDoc },
  techpack: { 生效: publishTp },
  change: { 审核: auditChange },
  supplier: { 准入: admitSupplier },
}
function doAction(kind, doc, a, ...args) {
  const f = RULES[kind]?.[a]
  return f ? f(doc, ...args) : move(kind, doc, a)
}

/* ---------- 供应商 ---------- */
// purpose：采购 / 生产 只能用合作中的；打样 待准入也行
function canUseSupplier(code, purpose) {
  const s = sup(code)
  if (!s) return false
  return purpose === '打样' ? ['待准入', '合作中'].includes(s.status) : s.status === '合作中'
}
const isFactory = (s) => s.cats.some((c) => c === '成衣厂' || c === '加工厂')
function createSupplier(f) {
  if (!f.short || !f.name) fail('全称、简称都要填')
  if (!f.cats?.length) fail('至少选一个分类')
  const code = 'GYS' + String(db.suppliers.length + 1).padStart(4, '0')
  const s = add(db.suppliers, { code, level: 'B', settle: '月结', term: 30, tax: '13%', certs: [], admitted: null, ...f, status: '待准入', log: [] })
  act(s, '新建')
  if (isFactory(s) && !factoryWh(code)) add(db.warehouses, { code: 'GC' + code.slice(3), name: s.short + '仓', type: '工厂仓', factory: code, status: '启用' })
  return s
}
function admitSupplier(s) {
  move('supplier', s, '准入')
  s.admitted = clock.today
  return s.certs.some((c) => c.type === '营业执照') ? null : '还没传营业执照'
}
function openDocsOfSupplier(code) {
  return [
    ...db.purchases.filter((p) => p.supplier === code && ['草稿', '待审核', '已审核', '部分到货'].includes(p.status)).map((p) => p.no),
    ...db.productions.filter((p) => p.factory === code && ['草稿', '待审核', '已下单', '生产中', '部分回货'].includes(p.status)).map((p) => p.no),
  ]
}
function blacklistSupplier(s, reason) {
  if (!reason) fail('要写拉黑原因')
  move('supplier', s, '拉黑', reason)
  s.blackReason = reason
}
const expiringCerts = () => db.suppliers.flatMap((s) => s.certs.filter((c) => c.until && daysLeft(c.until) <= 30 && daysLeft(c.until) >= 0).map((c) => ({ s, c })))

/* ---------- 款式 ---------- */
function createStyle(f) {
  if (!f.name) fail('款式名称要填')
  if (!f.colors?.length) fail('至少选一个颜色')
  const prefix = String(f.year).slice(2) + f.season + f.cat
  const code = f.code || prefix + String(db.styles.filter((s) => s.code.startsWith(prefix)).length + 1).padStart(3, '0')
  if (style(code)) fail(`款号 ${code} 已经有了`)
  const s = add(db.styles, { brand: '织间', gender: '女', source: '自主开发', wave: '第 1 波', tag: '通勤', designer: db.me, sizeGroup: 'W5', ...f, code, colors: [], skus: [], status: f.status || '草稿', log: [] })
  f.colors.forEach((c) => addStyleColor(s, c, true))
  act(s, '新建')
  return s
}
function addStyleColor(s, c, silent) {
  if (s.colors.includes(c)) return
  s.colors.push(c)
  sizesOf(s.sizeGroup).forEach((z) => s.skus.push({ code: `${s.code}-${c}-${z}`, color: c, size: z, status: '启用' }))
  if (!silent) act(s, '加颜色 ' + colorName(c) + '，新增 ' + sizesOf(s.sizeGroup).length + ' 个 SKU')
}
const tpsOf = (code) => db.techpacks.filter((t) => t.style === code).sort((a, b) => b.ver - a.ver)
const activeTp = (code) => db.techpacks.find((t) => t.style === code && t.status === '已生效')
const tpOf = (code, ver) => db.techpacks.find((t) => t.style === code && t.ver === ver)
const tpState = (code) => (activeTp(code) ? '已生效' : tpsOf(code).length ? '草稿' : '无')
// 资料包已生效、但有"随款色"行没给这个颜色配料
function unmatchedColors(s) {
  const tp = activeTp(s.code)
  if (!tp) return []
  return s.colors.filter((c) => tp.bom.some((r) => r.mode === '随款色' && !r.map[c]))
}

/* ---------- 资料包 ---------- */
const rowSku = (r, color) => (r.mode === '固定' ? r.sku : r.map[color])
const rowUse = (r, size) => (r.um === '统一' ? +r.use || 0 : +r.uses[size] || 0)
const rowMidUse = (r, sizes) => (r.um === '统一' ? +r.use || 0 : +r.uses[sizes[Math.floor((sizes.length - 1) / 2)]] || 0)
const unitPrice = (r) => { const m = mat(r.mat); return (r.price ?? m.price) / m.factor } // 折成库存单位
const rowCost = (r, sizes) => rnd(rowMidUse(r, sizes) * (1 + r.loss / 100) * unitPrice(r), 2)
// 只算我方采购的料：客供的料客户出，工厂自备的算在加工费里
const tpMaterialCost = (tp) => { const sz = sizesOf(style(tp.style).sizeGroup); return rnd(sum(tp.bom.filter((r) => r.src === '我方采购'), (r) => rowCost(r, sz)), 2) }
const tpLabor = (tp) => rnd(sum(tp.ops, (o) => o.price), 2)

function createTechpack(styleCode, bom, ops, note) {
  const ver = (tpsOf(styleCode)[0]?.ver || 0) + 1
  const tp = add(db.techpacks, { id: styleCode + '-V' + ver, style: styleCode, ver, status: '草稿', note: note || '', util: null, bom, ops, log: [] })
  act(tp, ver === 1 ? '新建' : '由 V' + (ver - 1) + ' 修订')
  return tp
}
function tpCheck(tp) {
  const s = style(tp.style), errs = []
  if (!tp.bom.length) errs.push('至少要有一行 BOM')
  if (tp.ver > 1 && !tp.note) errs.push('V2 起要写本版说明')
  tp.bom.forEach((r, i) => {
    if (r.mode === '随款色') s.colors.forEach((c) => { if (!r.map[c]) errs.push(`第 ${i + 1} 行（${r.part}）：${colorName(c)}未配料`) })
    if (r.um === '分码') sizesOf(s.sizeGroup).forEach((z) => { if (!(r.uses[z] > 0)) errs.push(`第 ${i + 1} 行（${r.part}）：${z} 码没有用量`) })
    if (r.um === '统一' && !(r.use > 0)) errs.push(`第 ${i + 1} 行（${r.part}）：没有用量`)
    if (mat(r.mat)?.status === '停用') errs.push(`第 ${i + 1} 行：${mat(r.mat).name} 已停用`)
  })
  return errs
}
function publishTp(tp) {
  const errs = tpCheck(tp)
  if (errs.length) fail('资料包还不能生效', errs)
  db.techpacks.filter((t) => t.style === tp.style && t.status === '已生效').forEach((t) => { t.status = '已作废'; act(t, '被 V' + tp.ver + ' 替代') })
  move('techpack', tp, '生效')
  tp.publishedAt = clock.today
  tp.publishedBy = db.me
}
function reviseTp(tp) {
  if (tpsOf(tp.style).some((t) => t.status === '草稿')) fail('已经有一个草稿版本，先把它改完')
  const copy = JSON.parse(JSON.stringify({ bom: tp.bom, ops: tp.ops }))
  return createTechpack(tp.style, copy.bom, copy.ops, '')
}

/* ---------- 订单 ---------- */
const lineKey = (l) => `${l.style}|${l.color}|${l.size}`
const orderQty = (o) => sum(o.lines, (l) => l.qty)
const orderAmt = (o) => rnd(sum(o.lines, (l) => l.qty * (l.price || 0)), 2)
const orderStyles = (o) => [...new Set(o.lines.map((l) => l.style))]
const prodsOf = (o) => db.productions.filter((p) => p.order === o.no && p.status !== '已作废')
const mrpsOf = (o) => db.mrps.filter((m) => m.orders.includes(o.no))
const sumProdLine = (o, l, f) => sum(prodsOf(o).filter((p) => p.style === l.style).flatMap((p) => p.lines.filter((x) => x.color === l.color && x.size === l.size)), f)
const scheduledQty = (o, l) => sumProdLine(o, l, (x) => x.qty)
const returnedQty = (o, l) => sumProdLine(o, l, (x) => x.ret)
const orderSched = (o) => sum(o.lines, (l) => scheduledQty(o, l))
const orderRet = (o) => sum(o.lines, (l) => returnedQty(o, l))
const orderLive = (o) => ['已审核', '生产中'].includes(o.status)

function createOrder(f) {
  const lines = (f.lines || []).filter((l) => l.qty > 0)
  if (!lines.length) fail('至少录一个数量')
  if (f.type === '客户订单' && !f.customer) fail('客户订单要选客户')
  if (!f.due) fail('客户交期要填')
  const o = add(db.orders, { no: nextNo('DD'), company: 'ZJ', currency: '人民币', date: clock.today, commit: null, sales: 'u3', merch: 'u1', ship: '', custNo: '', ...f, lines: lines.map((l) => ({ ver: null, price: null, ...l })), status: '草稿', log: [] })
  act(o, '新建')
  return o
}
function submitOrder(o) {
  const errs = []
  orderStyles(o).forEach((c) => {
    const s = style(c)
    if (s.status !== '启用') errs.push(`${c} 是「${s.status}」，不能下单`)
    else if (!activeTp(c)) errs.push(`${c} 的资料包还没生效`)
    else unmatchedColors(s).forEach((col) => { if (o.lines.some((l) => l.style === c && l.color === col)) errs.push(`${c} 的 ${colorName(col)} 还没配料`) })
  })
  if (errs.length) fail('订单还不能提交', errs)
  move('order', o, '提交')
}
function auditOrder(o, commit) {
  if (!commit) fail('要填承诺交期')
  const miss = orderStyles(o).filter((c) => !activeTp(c))
  if (miss.length) fail('资料包没生效的款不能审核', miss)
  move('order', o, '审核', '承诺交期 ' + commit)
  o.lines.forEach((l) => { l.ver = activeTp(l.style).ver })
  o.commit = commit
}
function unauditOrder(o) {
  const down = [...prodsOf(o).map((p) => p.no), ...mrpsOf(o).filter((m) => m.status !== '已关闭').map((m) => m.no)]
  if (down.length) fail('已经有下游单据，不能反审核', down)
  move('order', o, '反审核')
  o.lines.forEach((l) => { l.ver = null })
}
// 审核对话框：资料包、缺口最大的 5 种料、默认加工厂在手件数
function reviewInfo(o) {
  const rows = calcNeeds([o.no], true).filter((a) => a.src !== '工厂自备')
  const short = rows.map((a) => ({ ...a, avail: availFor(a.sku, a.owner), gap: 0 })).map((a) => ({ ...a, gap: rnd(a.need - a.avail, 3) }))
    .filter((a) => a.gap > 0).sort((a, b) => b.gap * unitPriceOfSku(b.sku) - a.gap * unitPriceOfSku(a.sku)).slice(0, 5)
  const loads = orderStyles(o).map((c) => style(c).factory).filter(Boolean)
  return {
    packs: orderStyles(o).map((c) => ({ style: c, tp: activeTp(c) })),
    short,
    loads: [...new Set(loads)].map((f) => ({ factory: f, qty: factoryLoad(f) })),
  }
}
const unitPriceOfSku = (s) => { const m = matOfSku(s); return m.price / m.factor }
const factoryLoad = (f) => sum(db.productions.filter((p) => p.factory === f && ['已下单', '生产中', '部分回货'].includes(p.status)), (p) => prodQty(p) - prodRet(p))

// 排单：blocks = [{ style, factory, mode, price, due, qty: { 'KQ|S': 80 } }]，一块生成一张加工单
const OVER = 0.03 // 溢装 / 短装比例（PRD Q09 默认 3%）
function scheduleOrder(o, blocks) {
  if (!orderLive(o)) fail('订单审核后才能排单')
  const errs = [], extra = {}
  blocks.forEach((b) => {
    const n = sum(Object.values(b.qty))
    if (!n) return
    if (!b.factory) errs.push(`${b.style}：要选加工厂`)
    else if (!canUseSupplier(b.factory, '生产')) errs.push(`${supName(b.factory)} 现在是「${sup(b.factory).status}」，不能下加工单`)
    if (!(b.price > 0)) errs.push(`${b.style}：加工单价要填`)
    Object.entries(b.qty).forEach(([k, q]) => { if (q > 0) extra[b.style + '|' + k] = (extra[b.style + '|' + k] || 0) + q })
  })
  if (!Object.keys(extra).length) errs.push('没有要排的数量')
  o.lines.forEach((l) => {
    const q = extra[lineKey(l)]
    if (!q) return
    const cap = Math.floor(l.qty * (1 + OVER)), done = scheduledQty(o, l)
    if (done + q > cap) errs.push(`${l.style}-${l.color}-${l.size}：订单 ${l.qty} 件，已排 ${done}，最多还能排 ${Math.max(0, cap - done)} 件`)
  })
  if (errs.length) fail('排单没通过', errs)
  return blocks.filter((b) => sum(Object.values(b.qty)) > 0).map((b) => createProduction(o, b))
}

// 订单变更：items = [{ k: '27CF001|KQ|M', to: 100 }]，due = 新客户交期
function createChange(o, f) {
  if (!orderLive(o)) fail('只有已审核的订单才走变更')
  if (!f.reason) fail('要写变更原因')
  const items = (f.items || []).map((i) => { const l = o.lines.find((x) => lineKey(x) === i.k); return { k: i.k, label: i.k.replace(/\|/g, '-'), from: l ? l.qty : 0, to: +i.to || 0 } }).filter((i) => i.to !== i.from)
  const due = f.due && f.due !== o.due ? { from: o.due, to: f.due } : null
  if (!items.length && !due) fail('没有改动')
  const errs = []
  items.forEach((i) => {
    const l = o.lines.find((x) => lineKey(x) === i.k)
    const done = l ? scheduledQty(o, l) : 0
    if (i.to < done) errs.push(`${i.label}：已排产 ${done} 件，先调整加工单 ${prodsOf(o).filter((p) => p.style === l.style).map((p) => p.no).join('、')}`)
  })
  if (errs.length) fail('变更不能提交', errs)
  const types = [items.length && '数量', due && '交期'].filter(Boolean)
  const c = add(db.changes, { no: nextNo('BG'), order: o.no, types, items, due, reason: f.reason, party: f.party || '客户', fee: f.fee || '', date: clock.today, by: db.me, status: '待审核', log: [] })
  act(c, '提交')
  act(o, '提交变更单 ' + c.no)
  return c
}
const describeChange = (c) => [...c.items.map((i) => `${i.label}：${i.from} → ${i.to}`), c.due && `客户交期：${fd(c.due.from)} → ${fd(c.due.to)}`].filter(Boolean).join('；')
function auditChange(c) {
  move('change', c, '审核')
  const o = order(c.order)
  c.items.forEach((i) => {
    const l = o.lines.find((x) => lineKey(x) === i.k)
    if (l) l.qty = i.to
    else { const [st, co, sz] = i.k.split('|'); const ref = o.lines.find((x) => x.style === st); o.lines.push({ style: st, color: co, size: sz, qty: i.to, price: ref?.price ?? null, ver: ref?.ver ?? null }) }
  })
  if (c.due) o.due = c.due.to
  const text = describeChange(c)
  act(o, `变更单 ${c.no} 生效：${text}`)
  const note = { no: c.no, order: o.no, at: clock.today, text }
  prodsOf(o).forEach((p) => { p.upstream = { ...note } })
  db.mrps.filter((m) => m.orders.includes(o.no) && m.status !== '已关闭').forEach((m) => { m.upstream = { ...note } })
  db.purchases.filter((p) => p.lines.some((l) => l.src?.orders?.includes(o.no)) && ['草稿', '待审核', '已审核', '部分到货'].includes(p.status)).forEach((p) => { p.upstream = { ...note } })
}
const upstreamPending = () => [...db.productions, ...db.mrps, ...db.purchases].filter((d) => d.upstream && !d.upstream.done)

/* ---------- 算料 ---------- */
// 毛需求 = Σ 订单 SKU 数 × 尺码用量；需求 = Σ 毛需求 ×（1 + 损耗）（备料比默认 0，PRD §4.3 的口径）。按 来源 + 物料 SKU + 物权 汇总
function calcNeeds(orderNos, useActive) {
  const acc = {}
  orderNos.map(order).forEach((o) => o.lines.forEach((l) => {
    const tp = useActive ? activeTp(l.style) : tpOf(l.style, l.ver)
    if (!tp) return
    tp.bom.forEach((r) => {
      const sku = rowSku(r, l.color)
      if (!sku) return
      const owner = r.src === '客供' ? o.customer || '自有' : '自有'
      const a = (acc[r.src + '|' + sku + '|' + owner] ||= { sku, owner, src: r.src, supplier: r.supplier || matOfSku(sku).supplier, price: r.price ?? matOfSku(sku).price, gross: 0, need: 0, orders: [], styles: [] })
      a.gross += l.qty * rowUse(r, l.size)
      a.need += l.qty * rowUse(r, l.size) * (1 + r.loss / 100)
      if (!a.orders.includes(o.no)) a.orders.push(o.no)
      if (!a.styles.includes(l.style)) a.styles.push(l.style)
    })
  }))
  return Object.values(acc).map((a) => ({ ...a, gross: rnd(a.gross, 3), need: rnd(a.need, 3) }))
}
const materialWhs = () => db.warehouses.filter((w) => w.type === '物料仓').map((w) => w.code)
// 可用库存 = 物料仓同物权库存 − 其他已确认算料单还没领走的"本次使用库存"
function availFor(sku, owner, exceptMrp) {
  const have = sum(materialWhs(), (w) => stockQty(w, sku, owner))
  const held = sum(db.mrps.filter((m) => m.status === '已确认' && m.no !== exceptMrp), (m) =>
    sum(m.rows.filter((r) => r.sku === sku && r.owner === owner), (r) => Math.max(0, (r.useStock || 0) - issuedFor(m.orders, sku, owner))))
  return Math.max(0, rnd(have - held, 3))
}
const issuedFor = (orderNos, sku, owner) => sum(db.stockDocs.filter((d) => d.status === '已审核' && (d.type === '领料' || d.type === '退料') && orderNos.includes(production(d.src)?.order))
  .flatMap((d) => d.lines.filter((l) => l.sku === sku && l.owner === owner).map((l) => (d.type === '退料' ? -l.qty : l.qty))))
// 在途 = 已审核采购单里还没入库的（换成库存单位），只做参考
const transitQty = (sku, owner) => rnd(sum(db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status)).flatMap((p) => p.lines).filter((l) => l.sku === sku && (l.owner || '自有') === owner), (l) => (l.qty - l.received) * matOfSku(sku).factor), 3)
// 建议采购量：净需求换成采购单位，向上取整到最小包装，不小于起订量
function suggestPur(sku, net) {
  if (net <= 0) return 0
  const m = matOfSku(sku)
  const q = Math.ceil(rnd(net / m.factor / m.pack, 6)) * m.pack
  return Math.max(rnd(q, 3), m.moq || 0)
}
function createMrp(orderNos) {
  if (!orderNos.length) fail('先选订单')
  orderNos.forEach((no) => { const o = order(no); if (!orderLive(o)) fail(`${no} 是「${o.status}」，只有已审核的订单能算料`) })
  const no = nextNo('SL')
  const rows = calcNeeds(orderNos).map((a) => {
    const need = a.need
    if (a.src === '工厂自备') return a
    const avail = availFor(a.sku, a.owner, no)
    const useStock = Math.min(avail, need)
    const net = rnd(need - useStock, 3)
    return { ...a, need, avail, useStock, transit: transitQty(a.sku, a.owner), useTransit: 0, net, purQty: a.src === '我方采购' ? suggestPur(a.sku, net) : 0, converted: 0 }
  })
  const m = add(db.mrps, { no, orders: [...orderNos], date: clock.today, status: '草稿', rows, upstream: null, log: [] })
  act(m, '算料：' + orderNos.join('、'))
  return m
}
function updateMrpRow(r, field, v) {
  v = Math.max(0, +v || 0)
  if (field === 'useStock') r.useStock = Math.min(v, r.avail, r.need)
  if (field === 'useTransit') r.useTransit = v
  r.net = rnd(r.need - r.useStock - (r.useTransit || 0), 3)
  if (r.src === '我方采购') r.purQty = Math.max(suggestPur(r.sku, r.net), r.converted)
}
function mrpToPurchases(m, idxs) {
  if (m.status === '已关闭') fail('算料单已关闭')
  const rows = idxs.map((i) => m.rows[i]).filter((r) => r && r.src === '我方采购' && r.purQty - r.converted > 0)
  if (!rows.length) fail('勾选的行没有要买的')
  const bad = [...new Set(rows.map((r) => r.supplier))].filter((s) => !canUseSupplier(s, '采购'))
  if (bad.length) fail('有供应商现在不能下单，先在行上换一家', bad.map((s) => `${supName(s)}：${sup(s)?.status || '未选'}`))
  if (m.status === '草稿') move('mrp', m, '确认')
  const bySup = {}
  rows.forEach((r) => (bySup[r.supplier] ||= []).push(r))
  const pos = Object.entries(bySup).map(([s, rs]) => createPurchase({
    supplier: s, type: '订单采购', wh: 'WL01',
    lines: rs.map((r) => ({ sku: r.sku, owner: r.owner, qty: rnd(r.purQty - r.converted, 3), price: r.price, src: { mrp: m.no, orders: r.orders, styles: r.styles } })),
  }))
  rows.forEach((r) => { r.converted = r.purQty })
  act(m, '生成采购单 ' + pos.map((p) => p.no).join('、'))
  return pos
}
// 齐料：订单关联算料单"我方采购"每一行，已入库 + 本次使用库存 ≥ 需求
function kitState(o) {
  const ms = mrpsOf(o)
  if (!ms.length) return { t: '未算料', k: 'none' }
  const rows = ms.flatMap((m) => m.rows.filter((r) => r.src === '我方采购').map((r) => ({ m, r })))
  const ok = rows.filter(({ m, r }) => {
    const recv = sum(db.purchases.flatMap((p) => p.lines).filter((l) => l.src?.mrp === m.no && l.sku === r.sku), (l) => l.received * matOfSku(r.sku).factor)
    return recv + r.useStock >= r.need - 0.001
  }).length
  if (ok === rows.length) return { t: '齐料', k: 'ok' }
  return ok ? { t: `部分到料 ${ok}/${rows.length}`, k: 'part' } : { t: '未到料', k: 'no' }
}

/* ---------- 采购 ---------- */
const poQty = (p) => rnd(sum(p.lines, (l) => l.qty), 3)
const poAmt = (p) => rnd(sum(p.lines, (l) => l.qty * l.price), 2)
const poRecv = (p) => rnd(sum(p.lines, (l) => l.received), 3)
function createPurchase(f) {
  if (!canUseSupplier(f.supplier, '采购')) fail(`${supName(f.supplier)} 现在是「${sup(f.supplier)?.status || '未选'}」，不能下采购单`)
  const lines = (f.lines || []).filter((l) => l.sku && l.qty > 0)
  if (!lines.length) fail('至少一行物料')
  const lead = Math.max(...lines.map((l) => matOfSku(l.sku).lead || 7))
  const s = sup(f.supplier)
  const p = add(db.purchases, {
    no: nextNo('CG'), company: 'ZJ', type: '订单采购', buyer: 'u2', wh: 'WL01', date: clock.today, due: addDays(clock.today, lead), settle: s.settle, tax: s.tax, upstream: null, ...f,
    lines: lines.map((l) => ({ owner: '自有', due: null, expect: null, note: '', received: 0, returned: 0, src: null, ...l })), status: '草稿', log: [],
  })
  act(p, '新建')
  return p
}
function unauditPurchase(p) {
  if (poRecv(p) > 0) fail('已经有入库，不能反审核')
  move('purchase', p, '反审核')
}
function refreshPoStatus(p) {
  if (!['已审核', '部分到货', '已到齐'].includes(p.status)) return
  const all = p.lines.every((l) => l.received >= l.qty - 0.0005)
  const any = p.lines.some((l) => l.received > 0)
  const ns = all ? '已到齐' : any ? '部分到货' : '已审核'
  if (ns !== p.status) { p.status = ns; act(p, ns) }
}
// lines = [{ i: 采购行号, qty: 采购单位数量, lot: 缸号 }]
function receivePurchase(p, f) {
  if (!['已审核', '部分到货'].includes(p.status)) fail('采购单审核后才能入库')
  const ls = (f.lines || []).filter((x) => x.qty > 0)
  if (!ls.length) fail('至少收一行')
  const errs = []
  ls.forEach((x) => {
    const l = p.lines[x.i], cap = rnd((l.qty - l.received) + l.qty * 0.05, 3)
    if (x.qty > cap) errs.push(`${skuName(l.sku)}：最多还能收 ${fq(cap)} ${matOfSku(l.sku).purUnit}`)
  })
  if (errs.length) fail('超过可收数量（溢收 5%）', errs)
  const d = createStockDoc({
    type: '采购入库', src: p.no, partner: p.supplier, to: f.wh || p.wh, supNo: f.supNo || '',
    lines: ls.map((x) => { const l = p.lines[x.i], m = matOfSku(l.sku); return { sku: l.sku, owner: l.owner, qty: rnd(x.qty * m.factor, 3), purQty: x.qty, price: l.price, lot: x.lot || '', poLine: x.i } }),
  })
  postStockDoc(d)
  return d
}
// 到料跟踪：未入齐的采购行
function arrivals() {
  return db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status)).flatMap((p) => p.lines.map((l, i) => ({ p, l, i })))
    .filter(({ l }) => l.received < l.qty - 0.0005)
    .map((x) => { const d = x.l.expect || x.l.due || x.p.due; const left = daysLeft(d); return { ...x, date: d, left, risk: left < 0 ? 'late' : left <= 3 ? 'near' : '' } })
}

/* ---------- 库存单据 ---------- */
const STOCK_TYPES = { 采购入库: 'RK', 客供入库: 'RK', 其他入库: 'RK', 领料: 'LL', 退料: 'LL', 调拨: 'DB', 采购退货: 'CK', 其他出库: 'CK' }
function createStockDoc(f) {
  const lines = (f.lines || []).filter((l) => l.sku && l.qty > 0)
  if (!lines.length) fail('至少一行物料')
  if (f.type === '客供入库' && !f.partner) fail('客供入库要选客户')
  if (['调拨', '领料', '退料'].includes(f.type) && (!f.from || !f.to)) fail('出仓、入仓都要选')
  if (f.from && f.from === f.to) fail('出仓和入仓不能是同一个')
  const owner = f.type === '客供入库' ? f.partner : null
  const d = add(db.stockDocs, {
    no: nextNo(STOCK_TYPES[f.type]), company: 'ZJ', src: '', from: null, to: null, partner: '', reason: '', date: clock.today, ...f,
    lines: lines.map((l) => ({ owner: owner || '自有', price: null, lot: '', ...l })), status: '草稿', log: [],
  })
  act(d, '新建')
  return d
}
function stockShortage(whCode, lines) {
  const need = {}
  lines.forEach((l) => { const k = stockKey(whCode, l.sku, l.owner); need[k] = (need[k] || 0) + l.qty })
  return Object.entries(need).filter(([k, q]) => (db.stock[k] || 0) < q - 0.0005)
    .map(([k, q]) => { const [, s, o] = k.split('|'); const have = db.stock[k] || 0; return `${skuName(s)}${o !== '自有' ? '（' + ownerName(o) + '）' : ''}：${whName(whCode)} 库存 ${fq(have)}，差 ${fq(rnd(q - have, 3))}` })
}
function changeStock(w, l, delta, d) {
  const k = stockKey(w, l.sku, l.owner)
  db.stock[k] = rnd((db.stock[k] || 0) + delta, 3)
  db.stockLogs.unshift({ at: clock.today, type: d.type, no: d.no, wh: w, sku: l.sku, owner: l.owner || '自有', delta: rnd(delta, 3), bal: db.stock[k], lot: l.lot || '', who: db.me })
}
// 审核即过账：库存不够整张不过，列出差多少
function postStockDoc(d) {
  if (!can('stock', d, '审核')) fail(`${d.no} 现在是「${d.status}」，不能审核`)
  if (d.from) { const s = stockShortage(d.from, d.lines); if (s.length) fail('库存不够，没过账', s) }
  d.lines.forEach((l) => { if (d.from) changeStock(d.from, l, -l.qty, d); if (d.to) changeStock(d.to, l, l.qty, d) })
  if (d.type === '采购入库' && d.src) { const p = purchase(d.src); d.lines.forEach((l) => { p.lines[l.poLine].received = rnd(p.lines[l.poLine].received + l.purQty, 3) }); act(p, '入库 ' + d.no); refreshPoStatus(p) }
  move('stock', d, '审核')
  d.auditor = db.me
}
function unpostStockDoc(d) {
  if (d.to) { const s = stockShortage(d.to, d.lines); if (s.length) fail('料已经被用掉或调走，改不回去', s) }
  d.lines.forEach((l) => { if (d.to) changeStock(d.to, l, -l.qty, d); if (d.from) changeStock(d.from, l, l.qty, d) })
  if (d.type === '采购入库' && d.src) {
    const p = purchase(d.src)
    d.lines.forEach((l) => { p.lines[l.poLine].received = rnd(p.lines[l.poLine].received - l.purQty, 3) })
    if (['部分到货', '已到齐'].includes(p.status)) { p.status = '已审核'; refreshPoStatus(p) }
  }
  move('stock', d, '反审核')
}
const stockDir = (d) => (d.from && d.to ? '移' : d.to ? '入' : '出')

/* ---------- 加工单 ---------- */
const prodQty = (p) => sum(p.lines, (l) => l.qty)
const prodRet = (p) => sum(p.lines, (l) => l.ret)
const prodDef = (p) => sum(p.lines, (l) => l.def)
const prodAmt = (p) => rnd(prodQty(p) * p.price, 2)
const prodLive = (p) => ['已下单', '生产中', '部分回货'].includes(p.status)
function createProduction(o, b) {
  const s = style(b.style)
  const lines = s.colors.flatMap((c) => sizesOf(s.sizeGroup).map((z) => ({ color: c, size: z, qty: b.qty[c + '|' + z] || 0, ret: 0, def: 0 }))).filter((l) => l.qty > 0)
  const p = add(db.productions, {
    no: nextNo('JG'), company: o.company, order: o.no, style: b.style, ver: o.lines.find((l) => l.style === b.style)?.ver, factory: b.factory, mode: b.mode || '清加工',
    price: +b.price, date: clock.today, due: b.due, expect: null, merch: o.merch, settle: sup(b.factory).settle, terms: '', status: '草稿', lines, nodes: [], issues: [], upstream: null, log: [],
  })
  act(p, '由订单 ' + o.no + ' 排单生成')
  return p
}
// 审核：从交期往前倒排节点计划
function auditProduction(p) {
  move('production', p, '审核')
  let end = p.due
  p.nodes = db.dicts.生产节点.slice().reverse().map(([name, days]) => {
    const pf = end, ps = addDays(pf, -(days - 1))
    end = addDays(ps, -1)
    return { name, ps, pf, as: null, af: null, qty: null, note: '', by: null }
  }).reverse()
  const o = order(p.order)
  if (o?.status === '已审核') { o.status = '生产中'; act(o, '加工单 ' + p.no + ' 已下单') }
}
// 节点状态：done / late（延期）/ warn（临期）/ run / todo
function nodeState(n) {
  if (n.af) return n.af > n.pf ? 'late' : 'done'
  if (clock.today > n.pf) return 'late'
  if (diffDays(n.pf, clock.today) <= 2) return 'warn'
  return n.as ? 'run' : 'todo'
}
const curNode = (p) => p.nodes.find((n) => !n.af)
function reportNode(p, i, f) {
  if (!prodLive(p)) fail('加工单审核后才能报进度')
  const n = p.nodes[i]
  if (!f.as && !f.af) fail('开始、完成至少填一个')
  if (f.af && f.as && f.af < f.as) fail('完成时间不能早于开始时间')
  Object.assign(n, { as: f.as || n.as || f.af, af: f.af || null, qty: f.qty ?? n.qty, note: f.note || n.note, by: db.me })
  if (p.status === '已下单') p.status = '生产中'
  act(p, `报进度：${n.name}${f.af ? '完成' : '开始'}${f.qty ? ' ' + f.qty + ' 件' : ''}`)
  return p.nodes.slice(0, i).some((x) => !x.af) ? '前面还有节点没报完成' : null
}
const expectDue = (p) => p.expect || p.due
// 风险：late 已逾期 / near 临期（7 天内）/ node 节点延期 / issue 有未关闭异常
function prodRisk(p) {
  const r = { late: false, near: false, node: false, issue: false, score: 0 }
  if (!['已下单', '生产中', '部分回货'].includes(p.status)) return r
  const left = daysLeft(expectDue(p))
  r.late = left < 0
  r.near = !r.late && left <= 7
  r.node = p.nodes.some((n) => !n.af && clock.today > n.pf)
  r.issue = p.issues.some((i) => i.status !== '已关闭')
  r.score = (r.late ? 8 : 0) + (r.node ? 4 : 0) + (r.issue ? 2 : 0) + (r.near ? 1 : 0)
  return r
}
function reportIssue(p, f) {
  if (!f.type || !f.desc) fail('类型、描述都要填')
  const i = add(p.issues, { id: nextNo('YC'), type: f.type, desc: f.desc, newDate: f.newDate || null, party: f.party || '', status: '待处理', at: clock.today, by: db.me, measure: '' })
  if (f.newDate) p.expect = f.newDate
  act(p, `上报异常：${f.type}${f.newDate ? '，预计交期改为 ' + fd(f.newDate) : ''}`)
  return i
}
function closeIssue(p, i, measure) {
  if (!measure) fail('关闭前要写处理措施')
  Object.assign(i, { measure, status: '已关闭', closedAt: clock.today, closedBy: db.me })
  act(p, '关闭异常：' + i.type)
}
// 领料建议 = 加工数 × BOM 用量 ×（1 + 损耗）− 已领；只算我方采购和客供
function issuePlan(p) {
  const tp = tpOf(p.style, p.ver)
  if (!tp) return []
  const cust = order(p.order)?.customer, acc = {}
  p.lines.forEach((l) => tp.bom.forEach((r) => {
    if (r.src === '工厂自备') return
    const sku = rowSku(r, l.color)
    if (!sku) return
    const owner = r.src === '客供' ? cust || '自有' : '自有'
    const a = (acc[sku + '|' + owner] ||= { sku, owner, src: r.src, need: 0 })
    a.need += l.qty * rowUse(r, l.size) * (1 + r.loss / 100)
  }))
  const docs = db.stockDocs.filter((d) => d.src === p.no && d.status === '已审核')
  return Object.values(acc).map((a) => {
    const issued = rnd(sum(docs.flatMap((d) => d.lines.filter((l) => l.sku === a.sku && l.owner === a.owner).map((l) => (d.type === '退料' ? -l.qty : l.qty)))), 3)
    const need = rnd(a.need, 3)
    return { ...a, need, issued, suggest: Math.max(0, rnd(need - issued, 3)), avail: stockQty('WL01', a.sku, a.owner) }
  })
}
function issueState(p) {
  const plan = issuePlan(p)
  if (!plan.length) return '—'
  if (plan.every((r) => r.issued >= r.need - 0.001)) return '已领齐'
  return plan.some((r) => r.issued > 0) ? '部分领料' : '未领料'
}
function issueMaterials(p, lines) {
  if (!prodLive(p)) fail('加工单审核后才能领料')
  const fw = factoryWh(p.factory)
  if (!fw) fail('这个加工厂还没有工厂仓')
  const ls = lines.filter((l) => l.qty > 0)
  if (!ls.length) fail('至少领一行')
  const short = stockShortage('WL01', ls)
  if (short.length) fail('库存不够，没领成', short)
  const d = createStockDoc({ type: '领料', src: p.no, from: 'WL01', to: fw.code, partner: p.factory, lines: ls })
  postStockDoc(d)
  act(p, '领料 ' + d.no)
  // 订单的加工单都领齐了，算料单自动关闭，释放"本次使用库存"的占用
  const o = order(p.order)
  if (o && o.lines.every((l) => scheduledQty(o, l) >= l.qty) && prodsOf(o).every((x) => issueState(x) === '已领齐'))
    mrpsOf(o).filter((m) => m.status === '已确认').forEach((m) => { m.status = '已关闭'; act(m, '加工单都已领齐料，自动关闭') })
  return d
}
// 回货：lines = { 'KQ|S': { qty, def } }
function registerReturn(p, f) {
  if (!prodLive(p)) fail('加工单审核后才能登记回货')
  const errs = [], got = []
  p.lines.forEach((l) => {
    const x = f.lines[l.color + '|' + l.size]
    const q = +x?.qty || 0, d = +x?.def || 0
    if (!q) return
    if (d > q) errs.push(`${l.color}-${l.size}：次品数不能大于回货数`)
    const cap = Math.floor(l.qty * (1 + OVER))
    if (l.ret + q > cap) errs.push(`${p.style}-${l.color}-${l.size}：加工 ${l.qty} 件，已回 ${l.ret}，最多还能回 ${Math.max(0, cap - l.ret)} 件`)
    got.push({ l, q, d })
  })
  if (!got.length) errs.push('至少登记一个数量')
  if (errs.length) fail('回货没登记上', errs)
  got.forEach(({ l, q, d }) => { l.ret += q; l.def += d })
  const r = add(db.returns, { no: nextNo('HH'), prod: p.no, order: p.order, style: p.style, factory: p.factory, date: f.date || clock.today, wh: f.wh || 'CP01', logistics: f.logistics || '', lines: got.map(({ l, q, d }) => ({ color: l.color, size: l.size, qty: q, def: d })), log: [] })
  act(r, '登记')
  p.status = prodRet(p) >= prodQty(p) * (1 - OVER) ? '已完成' : '部分回货'
  act(p, `回货 ${r.no}：${sum(r.lines, (x) => x.qty)} 件，次品 ${sum(r.lines, (x) => x.def)}${p.status === '已完成' ? '，加工单完成' : ''}`)
  checkOrderDone(order(p.order))
  return r
}
function checkOrderDone(o) {
  if (!o || !orderLive(o)) return
  const ps = prodsOf(o)
  if (ps.length && o.lines.every((l) => scheduledQty(o, l) >= l.qty) && ps.every((p) => ['已完成', '已结案'].includes(p.status))) {
    o.status = '已完成'
    act(o, '全部加工单完成，订单完成')
  }
}

/* ---------- 用真实动作演出历史数据 ---------- */
function as(uid, date, fn) {
  const [u, d] = [db.me, clock.today]
  db.me = uid; clock.today = date
  try { return fn() } finally { db.me = u; clock.today = d }
}
const SZ = (s, m, l, xl, xxl) => ({ S: s, M: m, L: l, XL: xl, XXL: xxl })
const mxLines = (st, colors, price) => Object.entries(colors).flatMap(([c, q]) => Object.entries(q).map(([z, n]) => ({ style: st, color: c, size: z, qty: n, price })))
const allQty = (o, st) => Object.fromEntries(o.lines.filter((l) => l.style === st).map((l) => [l.color + '|' + l.size, l.qty]))
const label = (sku, extra) => ({ part: extra.part, mat: sku, mode: '固定', sku, um: '统一', use: 1, loss: 1, src: '我方采购', ...extra })

function seed() {
  as('u4', '2026-08-20', () => {
    createStyle({ code: '26QJ012', name: '短款工装夹克', cat: 'J', year: 2026, season: 'Q', wave: '第 2 波', tag: '休闲', colors: ['HE', 'KQ'], tagPrice: 699, cost: 260, factory: 'GYS0005', source: '客户来样', customer: 'KH0002', custNo: 'NF-J2611', status: '启用' })
    createStyle({ code: '26QW008', name: '圆领针织衫', cat: 'W', year: 2026, season: 'Q', wave: '第 3 波', tag: '通勤', colors: ['MB', 'HH'], tagPrice: 399, cost: 150, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '26DS021', name: '连帽卫衣', cat: 'S', year: 2026, season: 'D', wave: '第 1 波', tag: '运动', colors: ['HE', 'HH', 'WL'], tagPrice: 459, cost: 120, factory: 'GYS0004', source: '客户来样', customer: 'KH0003', status: '启用' })
    createStyle({ code: '27CF001', name: '女式风衣', cat: 'F', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['KQ', 'ZQ', 'HE'], tagPrice: 899, cost: 330, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '27CK002', name: '直筒西裤', cat: 'K', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['HE', 'ZQ'], tagPrice: 459, cost: 160, factory: 'GYS0005', status: '启用' })
    createStyle({ code: '27CT003', name: '基础圆领 T 恤', cat: 'T', year: 2027, season: 'C', wave: '第 2 波', tag: '休闲', colors: ['BB', 'HE'], tagPrice: 199, factory: 'GYS0004' })
  })
  // 只有款式档案、还没下过单的款，让款式库像个样子
  as('u4', '2026-09-15', () => {
    createStyle({ code: '26DY009', name: '短款羽绒服', cat: 'Y', year: 2026, season: 'D', wave: '第 2 波', tag: '通勤', colors: ['HE', 'MB', 'JH'], tagPrice: 1299, cost: 520, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '26QK015', name: '阔腿休闲裤', cat: 'K', year: 2026, season: 'Q', wave: '第 3 波', tag: '休闲', colors: ['KQ', 'HE'], tagPrice: 399, cost: 140, factory: 'GYS0005', status: '启用' })
    createStyle({ code: '27CC004', name: '垂感长袖衬衫', cat: 'C', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['BB', 'WL'], tagPrice: 359, cost: 128, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '27CR005', name: 'A 字半身裙', cat: 'R', year: 2027, season: 'C', wave: '第 2 波', tag: '通勤', colors: ['HE', 'KQ'], tagPrice: 329, cost: 110, factory: 'GYS0005', status: '启用' })
    createStyle({ code: '27CL006', name: '收腰连衣裙', cat: 'L', year: 2027, season: 'C', wave: '第 2 波', tag: '约会', colors: ['JH', 'ZQ'], tagPrice: 599, cost: 210, factory: 'GYS0004' })
    createStyle({ code: '27CW007', name: '短款针织开衫', cat: 'W', year: 2027, season: 'C', wave: '第 1 波', tag: '轻松', colors: ['YM', 'MB'], tagPrice: 459, cost: 165, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '25DS011', name: '男式连帽卫衣', cat: 'S', year: 2025, season: 'D', wave: '第 1 波', tag: '运动', gender: '男', sizeGroup: 'M4', colors: ['ZQ', 'HE'], tagPrice: 429, cost: 118, factory: 'GYS0004', status: '停用' })
  })
  const labels = [label('BX0001', { part: '主唛' }), label('BX0002', { part: '洗唛' }), label('BP0001', { part: '吊牌' }), label('BD0001', { part: '包装袋' })]
  const thread = { part: '缝纫线', mat: 'FX0002', mode: '固定', sku: 'FX0002', um: '统一', use: 0.3, loss: 0, src: '工厂自备' }
  const cf001 = (m) => [
    { part: '大身', mat: 'ML0012', mode: '随款色', map: { KQ: 'ML0012-KQ', ZQ: 'ML0012-ZQ', HE: 'ML0012-HE' }, um: '分码', uses: SZ(2.25, m, 2.45, 2.55, 2.65), loss: 3, src: '我方采购', supplier: 'GYS0001', note: '注意对格' },
    { part: '里布', mat: 'ML0015', mode: '随款色', map: { KQ: 'ML0015-MB', ZQ: 'ML0015-HE', HE: 'ML0015-HE' }, um: '统一', use: 1.6, loss: 2, src: '客供' },
    { part: '纽扣', mat: 'FK0021', mode: '随款色', map: { KQ: 'FK0021-KQ', ZQ: 'FK0021-HE', HE: 'FK0021-HE' }, um: '统一', use: 8, loss: 2, src: '我方采购', supplier: 'GYS0002' },
    ...labels.map((x) => ({ ...x })), { ...thread },
  ]
  const cfOps = [['裁剪', '裁剪', 180, 2.5], ['车缝', '做领、上领', 420, 6], ['车缝', '做袖、上袖', 480, 6.5], ['车缝', '前后片拼合、做门襟', 600, 9], ['车缝', '装里布', 420, 6], ['整烫', '整烫', 240, 3], ['后道', '钉扣、剪线、查衫', 240, 3], ['包装', '挂吊牌、折叠入袋', 120, 2]]
  const ops = (a) => a.map(([cat, name, sec, price]) => ({ cat, name, sec, price }))
  as('u4', '2026-09-10', () => {
    publishTp(createTechpack('27CF001', cf001(2.3), ops(cfOps)))
    publishTp(createTechpack('26QJ012', [
      { part: '大身', mat: 'ML0018', mode: '随款色', map: { HE: 'ML0018-HE', KQ: 'ML0018-KQ' }, um: '分码', uses: SZ(1.85, 1.95, 2.05, 2.15, 2.25), loss: 3, src: '我方采购', supplier: 'GYS0001' },
      { part: '拉链', mat: 'FL0031', mode: '固定', sku: 'FL0031-HE-60', um: '统一', use: 1, loss: 1, src: '我方采购', supplier: 'GYS0002' },
      { part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 4, loss: 2, src: '我方采购', supplier: 'GYS0002' },
      ...labels.map((x) => ({ ...x })), { ...thread },
    ], ops([['裁剪', '裁剪', 160, 2.5], ['车缝', '前后片拼合', 540, 10], ['车缝', '上拉链', 360, 6], ['车缝', '做袖、上袖', 420, 7], ['车缝', '做领', 360, 6], ['整烫', '整烫', 200, 3], ['后道', '钉扣、查衫', 300, 4.5], ['包装', '包装', 150, 3]])))
    publishTp(createTechpack('26QW008', [
      { part: '大身', mat: 'MZ0003', mode: '随款色', map: { MB: 'MZ0003-MB', HH: 'MZ0003-HH' }, um: '统一', use: 1.4, loss: 5, src: '工厂自备' },
      ...labels.map((x) => ({ ...x })),
    ], ops([['车缝', '套口', 300, 8], ['车缝', '缝合', 360, 10], ['后道', '后整', 200, 6], ['包装', '包装', 120, 3]])))
    publishTp(createTechpack('26DS021', [
      { part: '大身', mat: 'MZ0003', mode: '随款色', map: { HE: 'MZ0003-HE', HH: 'MZ0003-HH', WL: 'MZ0003-WL' }, um: '分码', uses: SZ(1.5, 1.6, 1.7, 1.8, 1.9), loss: 4, src: '我方采购', supplier: 'GYS0009' },
      { part: '领', mat: 'MZ0007', mode: '随款色', map: { HE: 'MZ0007-HE', HH: 'MZ0007-HH', WL: 'MZ0007-WL' }, um: '统一', use: 0.25, loss: 3, src: '工厂自备' },
      ...labels.map((x) => ({ ...x })),
    ], ops([['裁剪', '裁剪', 120, 2], ['车缝', '拼缝、上帽', 600, 12], ['后道', '后整', 200, 5], ['包装', '包装', 120, 3]])))
    createTechpack('27CK002', [
      { part: '大身', mat: 'ML0012', mode: '随款色', map: { HE: 'ML0012-HE' }, um: '分码', uses: SZ(1.2, 1.25, 1.3, 1.35, 1.4), loss: 3, src: '我方采购', supplier: 'GYS0001' },
      { part: '拉链', mat: 'FL0031', mode: '固定', sku: 'FL0031-HE-15', um: '统一', use: 1, loss: 1, src: '我方采购' },
      { part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 1, loss: 2, src: '我方采购' },
      ...labels.map((x) => ({ ...x })),
    ], ops([['裁剪', '裁剪', 120, 2], ['车缝', '做腰、上腰', 420, 7], ['车缝', '上拉链、合裆', 480, 8], ['后道', '锁眼钉扣', 160, 2], ['包装', '包装', 100, 2]]))
  })
  as('u4', '2026-10-20', () => {
    const v2 = reviseTp(activeTp('27CF001'))
    v2.bom[0].uses.M = 2.35
    v2.note = 'M 码大身用量 2.30 → 2.35（放码后实测）'
    publishTp(v2)
  })
  // 期初库存、客供料
  as('u5', '2026-10-01', () => postStockDoc(createStockDoc({ type: '其他入库', reason: '期初', to: 'WL01', lines: [
    ['ML0012-KQ', 120], ['ML0012-ZQ', 60], ['ML0012-HE', 300], ['ML0015-MB', 80], ['FK0021-HE', 3000], ['FK0021-KQ', 1000], ['FL0031-HE-15', 300],
    ['BX0001', 3000], ['BX0002', 3000], ['BP0001', 2500], ['BD0001', 2500], ['FX0002', 200],
  ].map(([sku, qty]) => ({ sku, qty, price: matOfSku(sku).price / matOfSku(sku).factor })) })))
  as('u5', '2026-10-28', () => postStockDoc(createStockDoc({ type: '客供入库', partner: 'KH0001', to: 'WL01', lines: [{ sku: 'ML0015-HE', qty: 200, lot: 'G2610-07' }] })))

  // 订单 1：北方某电商 卫衣 900 件，已逾期、部分回货
  const o4 = as('u3', '2026-09-28', () => createOrder({ type: '客户订单', customer: 'KH0003', custNo: 'BF-20260928', due: '2026-11-05', lines: mxLines('26DS021', { HE: SZ(60, 110, 120, 80, 30), HH: SZ(40, 80, 90, 60, 30), WL: SZ(30, 50, 60, 40, 20) }, 128) }))
  as('u3', '2026-09-28', () => submitOrder(o4))
  as('u6', '2026-09-28', () => auditOrder(o4, '2026-11-05'))
  as('u2', '2026-09-29', () => {
    const m = createMrp([o4.no])
    const pos = mrpToPurchases(m, m.rows.map((_, i) => i))
    pos.forEach((p) => { move('purchase', p, '提交'); as('u6', '2026-09-29', () => move('purchase', p, '审核')) })
  })
  as('u5', '2026-10-08', () => { const p = db.purchases[db.purchases.length - 1]; receivePurchase(p, { lines: p.lines.map((l, i) => ({ i, qty: l.qty, lot: 'H1008-' + (i + 1) })) }) })
  const p4 = as('u1', '2026-09-30', () => scheduleOrder(o4, [{ style: '26DS021', factory: 'GYS0004', mode: '半包', price: 66, due: '2026-11-03', qty: allQty(o4, '26DS021') }])[0])
  as('u1', '2026-09-30', () => move('production', p4, '提交'))
  as('u6', '2026-09-30', () => auditProduction(p4))
  as('u5', '2026-10-09', () => issueMaterials(p4, issuePlan(p4).map((r) => ({ sku: r.sku, owner: r.owner, qty: r.suggest }))))
  as('u1', '2026-10-10', () => reportNode(p4, 0, { af: '2026-10-10' }))
  as('u1', '2026-10-15', () => reportNode(p4, 1, { as: '2026-10-11', af: '2026-10-15', qty: 905 }))
  as('u1', '2026-10-28', () => reportNode(p4, 2, { as: '2026-10-16', af: '2026-10-28', qty: 900 }))
  as('u1', '2026-10-30', () => reportNode(p4, 3, { as: '2026-10-28', af: '2026-10-30' }))
  as('u1', '2026-11-01', () => reportNode(p4, 4, { as: '2026-10-30', af: '2026-11-01' }))
  as('u1', '2026-11-01', () => reportNode(p4, 5, { as: '2026-11-01' }))
  as('u1', '2026-11-01', () => registerReturn(p4, { lines: { 'HE|S': { qty: 60, def: 1 }, 'HE|M': { qty: 110, def: 2 }, 'HE|L': { qty: 120, def: 2 }, 'HE|XL': { qty: 80, def: 1 }, 'HE|XXL': { qty: 30 } }, logistics: 'SF1029384756' }))
  as('u1', '2026-11-06', () => registerReturn(p4, { lines: { 'HH|S': { qty: 40 }, 'HH|M': { qty: 80, def: 2 }, 'HH|L': { qty: 80, def: 1 } } }))

  // 订单 2：南方某品牌 夹克 500 件，拉链缺料、裁剪延期、客户改过交期
  const o2 = as('u3', '2026-10-12', () => createOrder({ type: '客户订单', customer: 'KH0002', custNo: 'NF-PO-1012', due: '2026-11-25', lines: mxLines('26QJ012', { HE: SZ(60, 90, 90, 40, 20), KQ: SZ(40, 60, 60, 30, 10) }, 210) }))
  as('u3', '2026-10-12', () => submitOrder(o2))
  as('u6', '2026-10-13', () => auditOrder(o2, '2026-11-25'))
  const [po21, po22] = as('u2', '2026-10-14', () => {
    const m = createMrp([o2.no])
    const pos = mrpToPurchases(m, m.rows.map((_, i) => i))
    pos.forEach((p) => { move('purchase', p, '提交'); as('u6', '2026-10-14', () => move('purchase', p, '审核')) })
    return pos
  })
  po22.due = '2026-11-03'
  const p2 = as('u1', '2026-10-15', () => scheduleOrder(o2, [{ style: '26QJ012', factory: 'GYS0005', mode: '清加工', price: 42, due: '2026-11-18', qty: allQty(o2, '26QJ012') }])[0])
  as('u1', '2026-10-15', () => move('production', p2, '提交'))
  as('u6', '2026-10-16', () => auditProduction(p2))
  as('u3', '2026-10-20', () => createChange(o2, { due: '2026-11-30', reason: '客户门店开业推迟，交期顺延 5 天', party: '客户' }))
  as('u6', '2026-10-20', () => auditChange(db.changes[db.changes.length - 1]))
  ;[...db.mrps, ...db.purchases].forEach((d) => { if (d.upstream) d.upstream.done = true }) // 采购那边当时已处理，只留加工单上的提示
  as('u5', '2026-10-24', () => receivePurchase(po21, { lines: po21.lines.map((l, i) => ({ i, qty: l.qty, lot: 'K1024-' + (i + 1) })), supNo: 'HF-SH-8812' }))
  as('u5', '2026-10-25', () => issueMaterials(p2, issuePlan(p2).filter((r) => !r.sku.startsWith('FL')).map((r) => ({ sku: r.sku, owner: r.owner, qty: r.suggest }))))
  as('u1', '2026-10-28', () => reportNode(p2, 0, { as: '2026-10-26', af: '2026-10-28' }))
  as('u5', '2026-10-30', () => receivePurchase(po22, { lines: [{ i: 0, qty: 300 }] }))
  Object.assign(po22.lines[0], { expect: '2026-11-10', note: '供应商说剩下的 11-10 发' })
  as('u5', '2026-10-31', () => issueMaterials(p2, [{ sku: 'FL0031-HE-60', owner: '自有', qty: 300 }]))
  as('u1', '2026-11-03', () => reportNode(p2, 1, { as: '2026-10-30', af: '2026-11-03', qty: 502 }))
  as('u1', '2026-11-04', () => reportNode(p2, 2, { as: '2026-11-04' }))
  as('u1', '2026-11-05', () => reportIssue(p2, { type: '缺料', desc: '拉链只到 300 条，剩下 205 件等拉链', newDate: '2026-11-22', party: '供应商' }))
  p2.issues[0].status = '处理中'

  // 订单 3：自营备货 针织衫 600 件，包工包料，车缝临期
  const o3 = as('u3', '2026-10-20', () => createOrder({ type: '自营备货', due: '2026-11-20', brand: '织间', lines: mxLines('26QW008', { MB: SZ(50, 80, 80, 60, 30), HH: SZ(50, 80, 80, 60, 30) }, null) }))
  as('u3', '2026-10-20', () => submitOrder(o3))
  as('u6', '2026-10-21', () => auditOrder(o3, '2026-11-20'))
  as('u2', '2026-10-21', () => move('mrp', createMrp([o3.no]), '确认'))
  const p3 = as('u1', '2026-10-21', () => scheduleOrder(o3, [{ style: '26QW008', factory: 'GYS0004', mode: '包工包料', price: 128, due: '2026-11-15', qty: allQty(o3, '26QW008') }])[0])
  as('u1', '2026-10-21', () => move('production', p3, '提交'))
  as('u6', '2026-10-22', () => auditProduction(p3))
  as('u5', '2026-10-23', () => issueMaterials(p3, issuePlan(p3).map((r) => ({ sku: r.sku, owner: r.owner, qty: r.suggest }))))
  as('u1', '2026-10-25', () => reportNode(p3, 0, { af: '2026-10-25' }))
  as('u1', '2026-10-29', () => reportNode(p3, 1, { as: '2026-10-27', af: '2026-10-29', qty: 605 }))
  as('u1', '2026-10-31', () => reportNode(p3, 2, { as: '2026-10-31' }))

  // 备货采购：一张逾期未到，一张待审核
  as('u2', '2026-10-30', () => {
    const p = createPurchase({ supplier: 'GYS0003', type: '备货采购', lines: [{ sku: 'BP0001', qty: 5000, price: 0.25 }, { sku: 'BX0002', qty: 5000, price: 0.08 }] })
    p.due = '2026-11-06'
    move('purchase', p, '提交')
    as('u6', '2026-10-30', () => move('purchase', p, '审核'))
  })
  as('u2', '2026-11-06', () => move('purchase', createPurchase({ supplier: 'GYS0001', type: '备货采购', lines: [{ sku: 'ML0012-HE', qty: 500, price: 18.5 }] }), '提交'))

  // 订单 4：PRD 贯穿例子，已审核，等排单、算料
  const o1 = as('u3', '2026-11-05', () => createOrder({ type: '客户订单', customer: 'KH0001', custNo: 'HD-2027SS-018', due: '2027-03-20', ship: '送客户杭州仓，按色按码装箱', lines: mxLines('27CF001', { KQ: SZ(80, 120, 120, 60, 20), ZQ: SZ(80, 120, 120, 60, 20), HE: SZ(80, 120, 120, 60, 20) }, 365) }))
  as('u3', '2026-11-05', () => submitOrder(o1))
  as('u6', '2026-11-05', () => auditOrder(o1, '2027-03-20'))
  // 订单 5：待审核；订单 6：草稿
  const o6 = as('u3', '2026-11-06', () => createOrder({ type: '客户订单', customer: 'KH0001', custNo: 'HD-2026W-033', due: '2026-12-20', lines: mxLines('26QW008', { MB: SZ(40, 70, 70, 40, 20), HH: SZ(40, 70, 70, 40, 20) }, 96) }))
  as('u3', '2026-11-06', () => submitOrder(o6))
  as('u3', '2026-11-07', () => createOrder({ type: '翻单', customer: 'KH0002', due: '2026-12-25', lines: mxLines('26QJ012', { HE: SZ(40, 60, 60, 30, 10) }, 210) }))
  as('u5', '2026-11-07', () => createStockDoc({ type: '其他出库', reason: '报废', from: 'WL01', lines: [{ sku: 'FX0002', qty: 5 }] }))
}
seed()

/* ---------- 自检：打开 index.html?selftest 跑 PRD §4.3 的例子 ---------- */
function selftest() {
  const out = [], ok = (name, cond, got) => out.push({ name, ok: !!cond, got })
  const err = (fn) => { try { fn(); return '' } catch (e) { return e.message + ' ' + e.list.join('；') } }
  const o = db.orders.find((x) => x.custNo === 'HD-2027SS-018')
  const m = createMrp([o.no])
  const kq = m.rows.find((r) => r.sku === 'ML0012-KQ')
  ok('算料：卡其布毛需求 962', kq.gross === 962, kq.gross)
  ok('算料：需求 990.86', kq.need === 990.86, kq.need)
  ok('算料：可用 120、净需求 870.86、建议 871', kq.avail === 120 && kq.net === 870.86 && kq.purQty === 871, [kq.avail, kq.net, kq.purQty])
  move('mrp', m, '确认')
  ok('算料：第二张单可用库存为 0', availFor('ML0012-KQ', '自有') === 0, availFor('ML0012-KQ', '自有'))
  const [po] = mrpToPurchases(m, [m.rows.indexOf(kq)])
  ok('采购：生成 871 米', po.lines[0].qty === 871, po.lines[0].qty)
  doAction('purchase', po, '提交'); doAction('purchase', po, '审核')
  receivePurchase(po, { lines: [{ i: 0, qty: 500 }] })
  ok('入库 500 → 部分到货', po.status === '部分到货', po.status)
  receivePurchase(po, { lines: [{ i: 0, qty: 371 }] })
  ok('入库 371 → 已到齐', po.status === '已到齐', po.status)
  const [p] = scheduleOrder(o, [{ style: '27CF001', factory: 'GYS0004', mode: '清加工', price: 38, due: '2027-03-10', qty: { 'KQ|S': 80, 'KQ|M': 120, 'KQ|L': 120, 'KQ|XL': 60, 'KQ|XXL': 20 } }])
  ok('排单：再排 10 件被拒（最多 3 件）', err(() => scheduleOrder(o, [{ style: '27CF001', factory: 'GYS0004', price: 38, due: '2027-03-10', qty: { 'KQ|M': 10 } }])).includes('最多还能排 3 件'))
  doAction('production', p, '提交'); doAction('production', p, '审核')
  ok('加工单：6 个节点，入仓 = 03-10', p.nodes.length === 6 && p.nodes[5].pf === '2027-03-10', p.nodes.map((n) => n.pf).join(' '))
  ok('订单变"生产中"', o.status === '生产中', o.status)
  const fab = issuePlan(p).find((r) => r.sku === 'ML0012-KQ')
  ok('领料建议 990.86 米', fab.suggest === 990.86, fab.suggest)
  issueMaterials(p, [{ sku: 'ML0012-KQ', owner: '自有', qty: fab.suggest }])
  ok('领料后物料仓剩 0.14、工厂仓 990.86', stockQty('WL01', 'ML0012-KQ') === 0.14 && stockQty('GC0004', 'ML0012-KQ') === 990.86, [stockQty('WL01', 'ML0012-KQ'), stockQty('GC0004', 'ML0012-KQ')])
  ok('再领 1 米被拒（差 0.86）', err(() => issueMaterials(p, [{ sku: 'ML0012-KQ', owner: '自有', qty: 1 }])).includes('差 0.86'))
  reportNode(p, 1, { af: clock.today, qty: 402 })
  ok('报工后加工单"生产中"', p.status === '生产中', p.status)
  ok('变更：改到小于已排产被拒', err(() => createChange(o, { items: [{ k: '27CF001|KQ|M', to: 100 }], reason: '客户减量' })).includes('先调整加工单'))
  registerReturn(p, { lines: Object.fromEntries(p.lines.map((l, i) => [l.color + '|' + l.size, { qty: l.qty, def: i === 0 ? 4 : 0 }])) })
  ok('回货 400 件 → 加工单完成', p.status === '已完成', p.status)
  ok('回货超量被拒', err(() => registerReturn(p, { lines: { 'KQ|M': { qty: 10 } } })) !== '')
  ok('黑名单供应商不能下采购单', err(() => createPurchase({ supplier: 'GYS0008', lines: [{ sku: 'ML0012-HE', qty: 1, price: 1 }] })).includes('黑名单'))
  ok('资料包缺色不能生效', err(() => publishTp(tpsOf('27CK002')[0])).includes('藏青未配料'))
  return out
}
window.SCM = { db, FLOWS, doAction, selftest }
