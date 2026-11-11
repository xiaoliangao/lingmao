/* ============================================================
 * 价格档案（PRD §6.10，05.06 甲方 11-11 提前到一期）、研发 / 生产 BOM 对比（§6.4.1）、补货提醒（§6.6.7）
 * 物料价：供应商 × 物料（SKU 选填：不填 = 所有颜色同价）；加工价：工厂 × 款式 × 加工方式
 * 只增不改：调价加一条，录错了作废。下单默认取现价；采购单、加工单审核时，和现价不一样的价自动记进来
 * ============================================================ */
// 一条：{ id, seq, kind 物料价|加工价, sup, mat, sku, style, mode, price, tax, taxIncl, date 生效日期, why 调价原因, src 来源单号, by, at, void: { by, at, why } | null }
db.prices = []
let priceSeq = 0
const livePrices = () => db.prices.filter((x) => !x.void)
const byNewPrice = (a, b) => (a.date !== b.date ? (a.date < b.date ? 1 : -1) : b.seq - a.seq)
// 现价：生效日期不晚于 date 的最新一条，同一天有几条取最后录的（规则 2）
const latestPrice = (list, date) => list.filter((x) => x.date <= date).sort(byNewPrice)[0] || null
// 物料价先找这个 SKU 的，没有再找物料的
function curMatRec(supCode, matCode, sku, date = clock.today) {
  const xs = livePrices().filter((x) => x.kind === '物料价' && x.sup === supCode && x.mat === matCode)
  return (sku && latestPrice(xs.filter((x) => x.sku === sku), date)) || latestPrice(xs.filter((x) => !x.sku), date)
}
function curMatPrice(supCode, matCode, sku, date) { return curMatRec(supCode, matCode, sku, date || clock.today)?.price ?? null }
function curProcRec(fac, styleCode, mode, date = clock.today) { return latestPrice(livePrices().filter((x) => x.kind === '加工价' && x.sup === fac && x.style === styleCode && x.mode === mode), date) }
function curProcPrice(fac, styleCode, mode, date) { return curProcRec(fac, styleCode, mode, date || clock.today)?.price ?? null }
const samePrice = (a, b) => rnd(+a, 4) === rnd(+b, 4)
const priceKey = (x) => [x.kind, x.sup, x.mat || x.style, x.sku || x.mode || ''].join('|')
const priceWhat = (x) => (x.kind === '物料价' ? (x.sku ? skuName(x.sku) : mat(x.mat)?.name + ' · 所有颜色') : `${x.style} ${styleName(x.style)} · ${x.mode}`)
const priceUnit = (x) => (x.kind === '物料价' ? mat(x.mat)?.purUnit : '件')
const priceSrcLink = (no) => (no?.startsWith('CG') ? '#/purchases/' + no : no?.startsWith('JG') ? '#/production/' + no : null)
// price:edit（PRD §3.1）：采购录物料价，生产跟单录加工价，其他人只看；不传 kind = 两种能录一种就算
const PRICE_EDITOR = { 物料价: '采购', 加工价: '生产跟单' }
const canEditPrice = (kind) => (kind ? [PRICE_EDITOR[kind]] : Object.values(PRICE_EDITOR)).includes(me()?.role)

// 加一条（单据审核、种子用；人手录走 recordPrice）
function addPrice(f) {
  return add(db.prices, { id: 'JD' + String(++priceSeq).padStart(4, '0'), seq: priceSeq, kind: f.kind, sup: f.sup, mat: f.mat || null, sku: f.sku || null, style: f.style || null, mode: f.mode || null,
    price: rnd(+f.price, 4), tax: f.tax ?? sup(f.sup)?.tax ?? '', taxIncl: f.taxIncl ?? true, date: f.date || clock.today, why: (f.why || '').trim(), src: f.src || null, by: db.me, at: clock.today, void: null })
}
// 直接录、调价（规则 5）：有上一条价时要写原因（第一次报价、给某个 SKU 单独定价可以不写）；和现价一样的不用录
function recordPrice(f) {
  const k = f.kind
  if (!canEditPrice(k)) fail(`你没有录${k}的权限：${PRICE_EDITOR[k] || '采购、生产跟单'}能录`)
  if (!f.sup) fail(k === '加工价' ? '要选工厂' : '要选供应商')
  if (k === '物料价' && !f.mat) fail('要选物料')
  if (k === '加工价' && (!f.style || !f.mode)) fail('款式、加工方式都要选')
  if (!(+f.price > 0)) fail('单价要填')
  const date = f.date || clock.today, sku = k === '物料价' ? f.sku || null : null
  const cur = k === '物料价' ? curMatRec(f.sup, f.mat, sku, date) : curProcRec(f.sup, f.style, f.mode, date)
  const same = cur && (cur.sku || null) === sku
  if (same && samePrice(cur.price, f.price)) fail(`${fd(date)} 的现价已经是 ${fm(cur.price)}，不用再录`)
  if (same && !f.why?.trim()) fail('要写调价原因', [`现价 ${fm(cur.price)}（${fd(cur.date)} 起）→ ${fm(f.price)}`])
  return addPrice({ ...f, sku, date })
}
// 录错了作废：留着、标作废，不参与取价
function voidPrice(x, why) {
  if (!canEditPrice(x.kind)) fail(`你没有改${x.kind}的权限：${PRICE_EDITOR[x.kind]}能改`)
  if (x.void) fail('这条已经作废了')
  if (!why?.trim()) fail('要写作废原因')
  x.void = { by: db.me, at: clock.today, why: why.trim() }
}
const pricesOf = (q) => db.prices.filter((x) => (!q.sup || x.sup === q.sup) && (!q.mat || x.mat === q.mat) && (!q.style || x.style === q.style) && (!q.kind || x.kind === q.kind))

/* ---------- 单据上改了价（规则 4）：和现价不一样要写改价原因；审核时记一条（来源 = 这张单，生效日期 = 下单日期） ---------- */
// 采购单：新建、改、审核都查；档案里还没有这家的价，是第一次报价，不用写
function poPriceIssues(supCode, date, lines) {
  return lines.map((l, i) => {
    const cur = l.price == null || l.price === '' ? null : curMatPrice(supCode, matOfSku(l.sku)?.code, l.sku, date)
    return cur != null && !samePrice(cur, l.price) && !l.priceWhy?.trim() ? `第 ${i + 1} 行 ${skuName(l.sku)}：${fm(l.price)}，档案现价 ${fm(cur)}` : ''
  }).filter(Boolean)
}
function checkPoPrices(supCode, date, lines) { const e = poPriceIssues(supCode, date, lines); if (e.length) fail('改了单价要写改价原因', e) }
function poToArchive(p) {
  const got = []
  p.lines.forEach((l) => {
    const m = matOfSku(l.sku), cur = curMatRec(p.supplier, m.code, l.sku, p.date)
    if (l.price == null || (cur && samePrice(cur.price, l.price))) return
    // 现价是这个 SKU 单独定的，就记到 SKU 上；否则记物料（所有颜色）
    addPrice({ kind: '物料价', sup: p.supplier, mat: m.code, sku: cur?.sku || null, price: l.price, tax: p.tax, taxIncl: p.taxIncl, date: p.date, why: l.priceWhy || (cur ? '' : '第一次报价'), src: p.no })
    got.push(`${skuName(l.sku)} ${fm(l.price)}`)
  })
  if (got.length) act(p, '单价记进价格档案：' + got.join('、'))
}
function auditPurchase(p) {
  checkPoPrices(p.supplier, p.date, p.lines)
  move('purchase', p, '审核')
  poToArchive(p)
}
// 加工单：排单、改单、审核都查；b = { factory, style, mode, price, why }
function procPriceProblem(b, date) {
  const cur = b.price == null || b.price === '' ? null : curProcPrice(b.factory, b.style, b.mode || '清加工', date)
  return cur != null && !samePrice(cur, b.price) && !b.why?.trim() ? `加工单价 ${fm(b.price)} 和档案现价 ${fm(cur)} 不一样，要写改价原因` : ''
}
function procToArchive(p) {
  const cur = curProcRec(p.factory, p.style, p.mode, p.date)
  if (!(p.price > 0) || (cur && samePrice(cur.price, p.price))) return
  addPrice({ kind: '加工价', sup: p.factory, style: p.style, mode: p.mode, price: p.price, tax: sup(p.factory)?.tax, date: p.date, why: p.priceWhy || (cur ? '' : '第一次报价'), src: p.no })
  act(p, `加工单价 ${fm(p.price)} 记进价格档案`)
}
RULES.purchase.审核 = auditPurchase
RULES.production.审核 = (p) => {
  const e = procPriceProblem({ ...p, why: p.priceWhy }, p.date)
  if (e) fail(e)
  auditProduction(p)
  procToArchive(p)
}

/* ---------- 研发 BOM → 生产 BOM ---------- */
// 样衣转大货（PRD §6.3.3）：样衣的研发 BOM 挂到新款上（研发 V1，已生效），再复制成生产 BOM V1 草稿
function carryDevTp(styleCode, d) {
  const c = JSON.parse(JSON.stringify(d))
  const dev = createTechpack(styleCode, c.bom, c.ops, c.note || '', null, '研发')
  Object.assign(dev, { util: c.util ?? null, status: '已生效', publishedAt: clock.today, publishedBy: db.me })
  act(dev, '由样衣的研发 BOM 带过来')
  return toProdTp(dev)
}
// 研发 vs 生产（以后核价拿来比"研发估的 vs 生产定的 vs 实际花的"）：按部位 + 物料对行，比基码用量、单价、单件成本
function tpCompare(dev, prod) {
  const g = style(prod.style).sizeGroup, sizes = sizesOf(g), base = baseSizeOf(g)
  const key = (r) => r.part + '|' + r.mat
  const side = (r, st) => r && { r, use: rnd(rowMidUse(r, sizes, base), 3), loss: r.loss, price: r.price ?? rowRefPrice(r, st), cost: r.src === '我方采购' ? rowCost(r, sizes, base, st) : 0, src: r.src }
  const rows = [...new Set([...dev.bom, ...prod.bom].map(key))].map((k) => {
    const x = side(dev.bom.find((r) => key(r) === k), '研发'), y = side(prod.bom.find((r) => key(r) === k), '生产')
    return { part: (x || y).r.part, mat: (x || y).r.mat, dev: x, prod: y, dUse: x && y ? rnd(y.use - x.use, 3) : null, dPrice: x && y ? rnd(y.price - x.price, 4) : null, dCost: rnd((y?.cost || 0) - (x?.cost || 0), 2) }
  })
  const t = (tp) => ({ mat: tpMaterialCost(tp), labor: tpLabor(tp), all: rnd(tpMaterialCost(tp) + tpLabor(tp), 2) })
  const a = t(dev), b = t(prod)
  return { rows, dev: a, prod: b, d: { mat: rnd(b.mat - a.mat, 2), labor: rnd(b.labor - a.labor, 2), all: rnd(b.all - a.all, 2) } }
}
// 实际花的：最近一张审核过的采购单的成交价；最近一张加工单的加工单价
function lastBuyOf(r) {
  const skus = r.mode === '固定' ? [r.sku] : Object.values(r.map || {}).filter(Boolean)
  return db.purchases.filter((p) => !['草稿', '待审核', '已作废'].includes(p.status)).flatMap((p) => p.lines.filter((l) => skus.includes(l.sku)).map((l) => ({ p, l })))
    .sort((a, b) => (a.p.date < b.p.date ? 1 : a.p.date > b.p.date ? -1 : 0))[0] || null
}
const lastProdOf = (code) => db.productions.filter((p) => p.style === code && !['草稿', '待审核', '已作废'].includes(p.status)).sort((a, b) => (a.date < b.date ? 1 : -1))[0] || null

/* ---------- 补货提醒（PRD §6.6.7） ---------- */
// 设了安全库存的物料 SKU：物料仓库存 + 全部在途 − 已被占用 < 安全库存。这里在途不看赶不赶得上开裁：补货看的是会不会断
function reorderRows() {
  return db.materials.filter((m) => m.status !== '停用').flatMap((m) => m.skus.filter((k) => k.safety > 0 && k.status !== '停用').map((k) => {
    const stock = rnd(sum(materialWhs(), (w) => stockQty(w, k.code, '自有')), 3), tr = transitRows(k.code, '自有')
    const transit = rnd(sum(tr, (x) => x.left), 3), held = rnd(heldStock(k.code, '自有') + sum(tr, (x) => x.held), 3)
    const gap = rnd(k.safety - (stock + transit - held), 3)
    return { sku: k.code, mat: m.code, sup: m.supplier || '', stock, transit, held, safety: k.safety, gap, qty: suggestPur(k.code, gap) }
  })).filter((r) => r.gap > 0)
}
const belowSafety = (sku) => reorderRows().some((r) => r.sku === sku)
// 勾选的行按供应商拆成备货采购单草稿；单价按价格档案（createPurchase 取现价）。rows = [{ sku, sup, qty 采购单位 }]
function reorderToPurchases(rows) {
  if (!rows.length) fail('先勾选要补的物料')
  const miss = rows.filter((r) => !r.sup)
  if (miss.length) fail('有物料没设默认供应商，先在行上选一家', miss.map((r) => skuName(r.sku)))
  const bad = [...new Set(rows.map((r) => r.sup))].filter((s) => !canUseSupplier(s, '采购'))
  if (bad.length) fail('有供应商现在不能下单，先在行上换一家', bad.map((s) => `${supName(s)}：${sup(s)?.status}`))
  const zero = rows.filter((r) => !(r.qty > 0))
  if (zero.length) fail('补货量要大于 0', zero.map((r) => skuName(r.sku)))
  const by = {}
  rows.forEach((r) => (by[r.sup] ||= []).push(r))
  return Object.entries(by).map(([s, rs]) => {
    const p = createPurchase({ supplier: s, type: '备货采购', note: '补货：低于安全库存', lines: rs.map((r) => ({ sku: r.sku, qty: r.qty })) })
    act(p, '由补货提醒生成')
    return p
  })
}
// 补货清单里每个 SKU 已经开了、还没审核的备货采购单（防止重复生成）
const reorderDrafts = (sku) => db.purchases.filter((p) => p.type === '备货采购' && ['草稿', '待审核'].includes(p.status) && p.lines.some((l) => l.sku === sku))
TODOS.push(() => [(homeBoss() || me()?.role === '采购') && { t: '物料低于安全库存', n: reorderRows().length, to: '/reorder', i: 'box', tone: 'amber' }].filter(Boolean))

/* ---------- 种子：报价、调价历史（和物料参考单价、种子单据的价对得上），再把种子里审核过的单据补记一遍 ---------- */
as('u2', '2026-03-02', () => {
  recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0012', price: 19.2, why: '' })
  recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0015', price: 6.8 })
  recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0018', price: 21 })
  recordPrice({ kind: '物料价', sup: 'GYS0002', mat: 'FL0031', price: 2.4 })
  recordPrice({ kind: '物料价', sup: 'GYS0002', mat: 'FK0021', price: 0.38 })
  recordPrice({ kind: '物料价', sup: 'GYS0002', mat: 'FX0002', price: 4.5 })
})
as('u2', '2026-04-11', () => [['BX0001', 0.18], ['BX0002', 0.08], ['BP0001', 0.25], ['BD0001', 0.3]].forEach(([m, price]) => recordPrice({ kind: '物料价', sup: 'GYS0003', mat: m, price })))
as('u2', '2026-04-02', () => { recordPrice({ kind: '物料价', sup: 'GYS0009', mat: 'MZ0003', price: 46 }); recordPrice({ kind: '物料价', sup: 'GYS0009', mat: 'MZ0007', price: 15 }) })
as('u2', '2026-07-30', () => recordPrice({ kind: '物料价', sup: 'GYS0002', mat: 'FK0021', price: 0.35, why: '比价后鸿达降到 0.35，跟新雅的 0.42 比', src: 'BJ08' }))
as('u2', '2026-08-02', () => recordPrice({ kind: '物料价', sup: 'GYS0002', mat: 'FL0031', price: 2.6, why: '拉头换成自锁款', src: 'BJ10' }))
as('u2', '2026-08-12', () => recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0012', price: 18.5, why: '年度比价，恒丰降到 18.50', src: 'BJ01' }))
as('u2', '2026-08-20', () => recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0018', price: 22, why: '换了染厂，色牢度提一级', src: 'BJ06' }))
as('u1', '2026-06-01', () => recordPrice({ kind: '加工价', sup: 'GYS0004', style: '26QW008', mode: '包工包料', price: 120 }))
as('u1', '2026-08-25', () => recordPrice({ kind: '加工价', sup: 'GYS0005', style: '26QJ012', mode: '清加工', price: 40 }))
as('u1', '2026-10-01', () => recordPrice({ kind: '加工价', sup: 'GYS0004', style: '26QW008', mode: '包工包料', price: 128, why: '毛纱涨价，包工包料价跟着调' }))
as('u1', '2026-10-25', () => recordPrice({ kind: '加工价', sup: 'GYS0004', style: '27CF001', mode: '清加工', price: 38 })) // PRD §6.10 验收 4
{
  const p2 = db.productions.find((p) => p.style === '26QJ012')
  if (p2) p2.priceWhy = '拉链款车缝工时比报价时多，工厂加 2 元'
  const audited = (d) => { const x = d.log.find((e) => e.text === '审核'); return x && { who: db.users.find((u) => u.name === x.who)?.id || db.me, at: x.at } }
  ;[...db.purchases.map((d) => ({ d, f: poToArchive })), ...db.productions.map((d) => ({ d, f: procToArchive }))]
    .map((x) => ({ ...x, a: !['草稿', '待审核', '已作废'].includes(x.d.status) && audited(x.d) })).filter((x) => x.a)
    .sort((x, y) => (x.a.at < y.a.at ? -1 : x.a.at > y.a.at ? 1 : 0))
    .forEach(({ d, f, a }) => as(a.who, a.at, () => f(d)))
}
// 研发 BOM 例子：26QJ012 先估过价（研发 V1），生产 V1 是从它转来的，用量、单价都改过；新款 FS 的研发 V1 刚估完价，等转生产
as('u4', '2026-08-25', () => {
  const dev = createTechpack('26QJ012', [
    { part: '大身', mat: 'ML0018', mode: '随款色', map: { HE: 'ML0018-HE', KQ: 'ML0018-KQ' }, um: '分码', uses: SZ(1.75, 1.85, 1.95, 2.05, 2.15), loss: 3, src: '我方采购', supplier: 'GYS0001', price: 20.5 },
    { part: '拉链', mat: 'FL0031', mode: '固定', sku: 'FL0031-HE-60', um: '统一', use: 1, loss: 1, src: '我方采购', supplier: 'GYS0002', price: 2.4 },
    { part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 5, loss: 2, src: '我方采购', supplier: 'GYS0002', price: 0.38 },
    ...['BX0001', 'BX0002', 'BP0001', 'BD0001'].map((k, i) => label(k, { part: ['主唛', '洗唛', '吊牌', '包装袋'][i] })),
  ], [{ cat: '车缝', name: '整件加工（估）', req: '', sec: 3000, price: 40, note: '按工厂口头报价估' }], '打样估价：面料按 20.5 估', null, '研发')
  publishTp(dev)
  const prod = tpOf('26QJ012', 1)
  if (prod) prod.source = { stage: '研发', ver: 1 }
})
{
  const fs = db.styles.find((s) => s.big === 'FS' && s.name === '落肩短风衣')
  if (fs) as('u4', '2026-11-07', () => publishTp(createTechpack(fs.code, [
    { part: '大身', mat: 'ML0018', mode: '随款色', map: { YM: 'ML0018-YM', HE: 'ML0018-HE' }, um: '分码', uses: SZ(1.9, 2, 2.1, 2.2, 2.3), loss: 3, src: '我方采购', supplier: 'GYS0001', price: 21.5 },
    { part: '里布', mat: 'ML0015', mode: '随款色', map: { YM: 'ML0015-MB', HE: 'ML0015-HE' }, um: '统一', use: 1.5, loss: 2, src: '我方采购', supplier: 'GYS0001', price: 6.5 },
    { part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 6, loss: 2, src: '我方采购', supplier: 'GYS0002' },
    ...['BX0001', 'BX0002', 'BP0001', 'BD0001'].map((k, i) => label(k, { part: ['主唛', '洗唛', '吊牌', '包装袋'][i] })),
  ], [{ cat: '车缝', name: '整件加工（估）', req: '', sec: 3200, price: 42, note: '' }], '打样估价', null, '研发')))
}

/* ---------- 价格记录：物料、供应商、款式详情里共用。<PriceHistory :sup="供应商" /> 或 :mat / :style ---------- */
comp('PriceHistory', {
  props: { sup: String, mat: String, style: String },
  setup(p) {
    const s = reactive({ form: null, all: false })
    const mine = computed(() => pricesOf({ sup: p.sup, mat: p.mat, style: p.style }))
    // 现价：每个（供应商 × 物料 / SKU）、（工厂 × 款式 × 加工方式）一行，带上一条和以后生效的
    const cur = computed(() => {
      const by = {}
      mine.value.filter((x) => !x.void).forEach((x) => (by[priceKey(x)] ||= []).push(x))
      return Object.values(by).map((xs) => {
        const o = xs.slice().sort(byNewPrice), now = o.find((x) => x.date <= clock.today)
        return { x: now || o[o.length - 1], prev: now ? o[o.indexOf(now) + 1] : null, next: o.filter((x) => x.date > clock.today).pop() || null, n: xs.length }
      }).sort((a, b) => (a.x.kind + a.x.sup + priceWhat(a.x) < b.x.kind + b.x.sup + priceWhat(b.x) ? -1 : 1))
    })
    const hist = computed(() => mine.value.slice().sort(byNewPrice))
    const shown = computed(() => (s.all ? hist.value : hist.value.slice(0, 12)))
    const kindDefault = () => (p.style || (p.sup && isFactory(sup(p.sup))) ? '加工价' : '物料价')
    const newOne = () => { s.form = { kind: canEditPrice(kindDefault()) ? kindDefault() : Object.keys(PRICE_EDITOR).find((k) => canEditPrice(k)), sup: p.sup || '', mat: p.mat || '', style: p.style || '' } }
    const adjust = (x) => { s.form = { kind: x.kind, sup: x.sup, mat: x.mat || '', sku: x.sku || '', style: x.style || '', mode: x.mode || '清加工', adjust: true } }
    async function drop(x) {
      const why = await askText('作废 ' + fm(x.price), `${supName(x.sup)} · ${priceWhat(x)}，${fd(x.date)} 生效。作废后这条不参与取价，记录还留着。`, '作废原因，如：录错了，应该是 18.50', '作废')
      if (why != null && run(() => voidPrice(x, why))) toast('作废了这条价格')
    }
    const pct = (a, b) => (a && b ? Math.round(((a - b) / b) * 1000) / 10 : null)
    return { s, cur, hist, shown, newOne, adjust, drop, pct, canEditPrice }
  },
  template: `<section data-c="价格记录">
    <div class="row" style="margin-bottom:10px"><span class="dim" style="font-size:12px">现价 = 生效日期不晚于今天的最新一条。下采购单、排单默认带现价；单据上改了价要写原因，审核时自动记进来</span><span class="sp" style="flex:1"></span>
      <button v-if="canEditPrice()" class="btn" @click="newOne"><Ico n="plus" :s="14"/>录价格</button></div>
    <table class="tbl inset flat" data-c="现价">
      <thead><tr><th>类型</th><th v-if="!sup">{{ style ? '工厂' : '供应商' }}</th><th v-if="sup || !mat">{{ style ? '款式 · 加工方式' : '物料 / 款式' }}</th><th v-if="mat && !sup">SKU</th><th class="num">现价</th><th class="num">上一次</th><th>生效</th><th>调价原因</th><th>来源</th><th></th></tr></thead>
      <tbody>
        <tr v-for="c in cur" :key="c.x.id">
          <td class="mut">{{ c.x.kind }}</td>
          <td v-if="!sup"><a :href="'#/suppliers/' + c.x.sup">{{ supName(c.x.sup) }}</a></td>
          <td v-if="sup || !mat">{{ priceWhat(c.x) }}</td>
          <td v-if="mat && !sup">{{ c.x.sku ? skuName(c.x.sku) : '所有颜色' }}</td>
          <td class="num"><b style="font-weight:600">{{ fm(c.x.price) }}</b><span class="dim">/{{ priceUnit(c.x) }}</span>
            <div v-if="c.next" class="amber" style="font-size:12px">{{ fd(c.next.date) }} 起 {{ fm(c.next.price) }}</div></td>
          <td class="num"><template v-if="c.prev">{{ fm(c.prev.price) }} <span :class="c.x.price > c.prev.price ? 'red' : 'green'" style="font-size:12px">{{ c.x.price > c.prev.price ? '+' : '' }}{{ pct(c.x.price, c.prev.price) }}%</span></template><span v-else class="dim">—</span></td>
          <td>{{ fd(c.x.date) }}</td>
          <td class="wrap">{{ c.x.why || '—' }}</td>
          <td><a v-if="priceSrcLink(c.x.src)" class="code" :href="priceSrcLink(c.x.src)">{{ c.x.src }}</a><span v-else class="mut">{{ c.x.src ? '报价 ' + c.x.src : '手工录' }}</span></td>
          <td class="num"><button v-if="canEditPrice(c.x.kind)" class="btn sm" @click="adjust(c.x)">调价</button></td>
        </tr>
        <tr v-if="!cur.length"><td colspan="10" class="empty">还没有价格记录：采购单、加工单审核时会自动记进来，也可以点「录价格」直接录供应商的报价</td></tr>
      </tbody>
    </table>
    <div v-if="hist.length" class="sec" data-c="价格历史"><h3>历史<span class="dim" style="font-weight:400;font-size:12px">只增不改，录错的作废</span></h3>
      <table class="tbl inset flat">
        <thead><tr><th>生效日期</th><th>类型</th><th v-if="!sup">供应商 / 工厂</th><th>物料 / 款式</th><th class="num">单价</th><th>税率</th><th>调价原因</th><th>来源</th><th>记录</th><th></th></tr></thead>
        <tbody>
          <tr v-for="x in shown" :key="x.id" :class="{ off: x.void }">
            <td>{{ fd(x.date) }}</td><td class="mut">{{ x.kind }}</td>
            <td v-if="!sup">{{ supName(x.sup) }}</td>
            <td>{{ priceWhat(x) }}</td>
            <td class="num"><s v-if="x.void">{{ fm(x.price) }}</s><template v-else>{{ fm(x.price) }}</template><span class="dim">/{{ priceUnit(x) }}</span></td>
            <td class="mut">{{ x.tax || '—' }}{{ x.taxIncl ? ' 含税' : ' 不含税' }}</td>
            <td class="wrap">{{ x.why || '—' }}<div v-if="x.void" class="red" style="font-size:12px">已作废：{{ x.void.why }}（{{ userName(x.void.by) }} · {{ fd(x.void.at) }}）</div></td>
            <td><a v-if="priceSrcLink(x.src)" class="code" :href="priceSrcLink(x.src)">{{ x.src }}</a><span v-else class="mut">{{ x.src ? '报价 ' + x.src : '手工录' }}</span></td>
            <td class="mut">{{ userName(x.by) }} · {{ fd(x.at) }}</td>
            <td class="num"><button v-if="!x.void && canEditPrice(x.kind)" class="btn ghost sm danger" @click="drop(x)">作废</button></td>
          </tr>
        </tbody>
      </table>
      <p v-if="hist.length > 12" style="margin-top:8px"><button class="btn ghost sm" @click="s.all = !s.all">{{ s.all ? '收起' : '看全部 ' + hist.length + ' 条' }}</button></p>
    </div>
    <PriceForm v-if="s.form" :init="s.form" @close="s.form = null"/>
  </section>`,
})

// 录价格 / 调价：有现价时显示现价，要写调价原因
comp('PriceForm', {
  props: { init: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ kind: '物料价', sup: '', mat: '', sku: '', style: '', mode: '清加工', price: null, tax: '', taxIncl: true, date: clock.today, why: '', ...p.init })
    const sups = computed(() => db.suppliers.filter((x) => (f.kind === '加工价' ? isFactory(x) : !isFactory(x)) && x.status !== '黑名单'))
    const skus = computed(() => mat(f.mat)?.skus.filter((k) => k.status !== '停用') || [])
    const cur = computed(() => (f.kind === '物料价' ? (f.sup && f.mat ? curMatRec(f.sup, f.mat, f.sku || null, f.date) : null) : f.sup && f.style && f.mode ? curProcRec(f.sup, f.style, f.mode, f.date) : null))
    const needWhy = computed(() => cur.value && (cur.value.sku || null) === (f.kind === '物料价' ? f.sku || null : null))
    watch(() => f.sup, (v) => { f.tax = sup(v)?.tax || '' }, { immediate: !p.init.tax })
    function save() {
      const x = run(() => recordPrice({ ...f }))
      if (x?.id) { toast(`${supName(x.sup)} · ${priceWhat(x)}：${fm(x.price)}，${fd(x.date)} 起生效`); emit('close') }
    }
    return { f, sups, skus, cur, needWhy, save, canEditPrice }
  },
  template: `<Modal :t="init.adjust ? '调价' : '录价格'" :w="560" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<select class="sel" v-model="f.kind" :disabled="init.adjust"><option v-for="k in ['物料价', '加工价']" :key="k" :disabled="!canEditPrice(k)">{{ k }}</option></select></label>
      <label class="f">{{ f.kind === '加工价' ? '工厂' : '供应商' }}<select class="sel" v-model="f.sup" :disabled="init.adjust"><option value="">选一家</option><option v-for="x in sups" :key="x.code" :value="x.code">{{ x.short }}{{ x.status !== '合作中' ? '（' + x.status + '）' : '' }}</option></select></label>
      <template v-if="f.kind === '物料价'">
        <label class="f">物料<select class="sel" v-model="f.mat" :disabled="init.adjust" @change="f.sku = ''"><option value="">选物料</option><option v-for="m in db.materials" :key="m.code" :value="m.code">{{ m.code }} {{ m.name }}</option></select></label>
        <label class="f">SKU<select class="sel" v-model="f.sku" :disabled="init.adjust"><option value="">不选 = 所有颜色同价</option><option v-for="k in skus" :key="k.code" :value="k.code">{{ skuName(k.code) }}</option></select></label>
      </template>
      <template v-else>
        <label class="f">款式<select class="sel" v-model="f.style" :disabled="init.adjust"><option value="">选款式</option><option v-for="x in db.styles.filter(x => x.status !== '停用')" :key="x.code" :value="x.code">{{ x.code }} {{ x.name }}</option></select></label>
        <label class="f">加工方式<select class="sel" v-model="f.mode" :disabled="init.adjust"><option v-for="x in db.dicts.加工方式" :key="x">{{ x }}</option></select></label>
      </template>
      <label class="f">单价（元/{{ f.kind === '物料价' ? (mat(f.mat)?.purUnit || '采购单位') : '件' }}）<input class="inp num" type="number" v-model.number="f.price"></label>
      <label class="f">税率<input class="inp" v-model="f.tax" placeholder="如 13%"></label>
      <label class="f">生效日期<input class="inp" type="date" v-model="f.date"></label>
      <label class="f">含税<select class="sel" v-model="f.taxIncl"><option :value="true">含税</option><option :value="false">不含税</option></select></label>
      <div v-if="cur" class="f full dim" style="font-size:12px">{{ fd(f.date) }} 的现价 {{ fm(cur.price) }}（{{ fd(cur.date) }} 起{{ cur.sku ? '，这个 SKU 单独定的' : '' }}）{{ needWhy ? '：调价要写原因' : '：给这个 SKU 单独定价，原因可以不写' }}</div>
      <label class="f full">调价原因<input class="inp" v-model="f.why" :placeholder="needWhy ? '必填，如：原料涨价' : '第一次报价可以不写'"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">记下这条价格</button></template>
  </Modal>`,
})

/* 研发 vs 生产对比：<TpCompare :dev="研发版" :prod="生产版" actual/>（actual：再加一列"实际花的"：最近成交价、最近加工单价） */
comp('TpCompare', {
  props: { dev: Object, prod: Object, actual: Boolean },
  setup(p) {
    const c = computed(() => tpCompare(p.dev, p.prod))
    const s = computed(() => style(p.prod.style))
    const sizes = computed(() => sizesOf(s.value.sizeGroup)), base = computed(() => baseSizeOf(s.value.sizeGroup))
    // 实际：生产 BOM 这一行的料最近一次成交价，按生产用量算单件
    const act = (x) => { if (!x.prod || x.prod.src !== '我方采购') return null; const b = lastBuyOf(x.prod.r); if (!b) return null; const m = mat(x.mat); return { b, price: b.l.price, cost: rnd(x.prod.use * (1 + x.prod.loss / 100) * b.l.price / m.factor, 2) } }
    const acts = computed(() => c.value.rows.map(act))
    const lastProd = computed(() => lastProdOf(p.prod.style))
    const actAll = computed(() => { const m = rnd(c.value.rows.reduce((t, x, i) => t + (x.prod?.src === '我方采购' ? acts.value[i]?.cost ?? x.prod.cost : 0), 0), 2); const l = lastProd.value?.price ?? c.value.prod.labor; return { mat: m, labor: l, all: rnd(m + l, 2), miss: c.value.rows.some((x, i) => x.prod?.src === '我方采购' && !acts.value[i]) } })
    const dc = (d) => (d > 0 ? 'red' : d < 0 ? 'green' : 'dim')
    const ds = (d, f = fq) => (d == null ? '' : (d > 0 ? '+' : d < 0 ? '−' : '±') + f(Math.abs(d)))
    return { c, s, sizes, base, acts, lastProd, actAll, dc, ds }
  },
  template: `<div data-c="研发 vs 生产">
    <table class="tbl inset flat">
      <thead><tr><th>部位</th><th>物料</th><th class="num">用量（{{ base }} 码）<div class="dim" style="font-weight:400">研发 → 生产</div></th><th class="num">单价<div class="dim" style="font-weight:400">研发 → 生产</div></th><th class="num">单件成本<div class="dim" style="font-weight:400">研发 → 生产</div></th><th class="num">差</th>
        <th v-if="actual" class="num">实际成交价<div class="dim" style="font-weight:400">最近一张采购单</div></th><th v-if="actual" class="num">实际单件</th></tr></thead>
      <tbody>
        <tr v-for="(x, i) in c.rows" :key="i">
          <td>{{ x.part }}</td><td>{{ mat(x.mat).name }}<span v-if="!x.dev" class="flag amber" style="margin-left:6px">生产新加</span><span v-if="!x.prod" class="flag" style="margin-left:6px">生产去掉了</span><span v-if="(x.prod || x.dev).src !== '我方采购'" class="dim">　{{ (x.prod || x.dev).src }}</span></td>
          <td class="num">{{ x.dev ? fq(x.dev.use) : '—' }} → {{ x.prod ? fq(x.prod.use) : '—' }} <span v-if="x.dUse" :class="dc(x.dUse)" style="font-size:12px">{{ ds(x.dUse) }}</span></td>
          <td class="num">{{ x.dev ? fm(x.dev.price) : '—' }} → {{ x.prod ? fm(x.prod.price) : '—' }} <span v-if="x.dPrice" :class="dc(x.dPrice)" style="font-size:12px">{{ ds(x.dPrice, fm) }}</span></td>
          <td class="num">{{ x.dev ? fm(x.dev.cost) : '—' }} → {{ x.prod ? fm(x.prod.cost) : '—' }}</td>
          <td class="num"><b v-if="x.dCost" :class="dc(x.dCost)" style="font-weight:500">{{ ds(x.dCost, fm) }}</b><span v-else class="dim">—</span></td>
          <td v-if="actual" class="num"><template v-if="acts[i]">{{ fm(acts[i].price) }} <a class="code dim" style="font-size:11px" :href="'#/purchases/' + acts[i].b.p.no">{{ acts[i].b.p.no }}</a></template><span v-else class="dim">{{ x.prod && x.prod.src === '我方采购' ? '还没买过' : '—' }}</span></td>
          <td v-if="actual" class="num">{{ acts[i] ? fm(acts[i].cost) : '—' }}</td>
        </tr>
        <tr><td>加工</td><td class="mut">工艺单工价合计</td><td></td><td></td><td class="num">{{ fm(c.dev.labor) }} → {{ fm(c.prod.labor) }}</td><td class="num"><b v-if="c.d.labor" :class="dc(c.d.labor)" style="font-weight:500">{{ ds(c.d.labor, fm) }}</b><span v-else class="dim">—</span></td>
          <td v-if="actual" class="num"><template v-if="lastProd">{{ fm(lastProd.price) }} <a class="code dim" style="font-size:11px" :href="'#/production/' + lastProd.no">{{ lastProd.no }}</a></template><span v-else class="dim">还没下过加工单</span></td><td v-if="actual" class="num">{{ fm(actAll.labor) }}</td></tr>
      </tbody>
      <tfoot><tr><td>单件合计</td><td class="mut">物料 {{ fm(c.dev.mat) }} → {{ fm(c.prod.mat) }}</td><td></td><td></td><td class="num"><b>{{ fm(c.dev.all) }} → {{ fm(c.prod.all) }}</b></td><td class="num"><b :class="dc(c.d.all)">{{ ds(c.d.all, fm) || '—' }}</b></td>
        <td v-if="actual"></td><td v-if="actual" class="num"><b>{{ fm(actAll.all) }}</b></td></tr></tfoot>
    </table>
    <p class="dim" style="font-size:12px;margin-top:8px">按部位 + 物料对行；用量按 {{ base }} 码，单件成本 = 用量 ×（1 + 损耗）× 单价，只算我方采购的料。研发单价是打样时手填的估价，生产单价默认取价格档案现价。<template v-if="actual">实际：没买过的料按生产 BOM 的单价算{{ actAll.miss ? '（有料还没买过）' : '' }}。完整的核价对比报表跟着 M05 核价（待定）。</template></p>
  </div>`,
})

/* 款式详情「价格」页签：研发估的 / 生产定的 / 实际花的，下面是这个款的加工价记录 */
comp('StylePrice', {
  props: { code: String },
  setup(p) {
    const dev = computed(() => activeTp(p.code, '研发') || tpsOf(p.code, '研发')[0])
    const prod = computed(() => activeTp(p.code) || tpsOf(p.code)[0])
    return { dev, prod }
  },
  template: `<div>
    <section class="sec" style="margin-top:0" data-c="成本对比"><h3>研发估的 · 生产定的 · 实际花的
      <span class="dim" style="font-weight:400;font-size:12px" v-if="dev && prod">研发 V{{ dev.ver }}{{ dev.status === '已生效' ? '' : '（' + dev.status + '）' }} 对 生产 V{{ prod.ver }}{{ prod.status === '已生效' ? '' : '（' + prod.status + '）' }}</span></h3>
      <TpCompare v-if="dev && prod" :dev="dev" :prod="prod" actual/>
      <p v-else-if="prod" class="dim">这个款没有研发 BOM（导入的老款或直接下大货的款），只有生产 BOM：物料 {{ fm(tpMaterialCost(prod)) }}，加工 {{ fm(tpLabor(prod)) }}，单件 {{ fm(tpMaterialCost(prod) + tpLabor(prod)) }}。</p>
      <p v-else-if="dev" class="dim">只有研发 BOM，估价单件 {{ fm(tpMaterialCost(dev) + tpLabor(dev)) }}；转生产 BOM 以后能对比。</p>
      <p v-else class="dim">这个款还没有资料包，没有成本可比。</p>
    </section>
    <section class="sec" data-c="加工价"><h3>加工价<span class="dim" style="font-weight:400;font-size:12px">各工厂、各加工方式的现价和历史</span></h3>
      <PriceHistory :style="code"/></section>
  </div>`,
})

/* ---------- 补货清单：首页待办"物料低于安全库存"点进来 ---------- */
route('/reorder', {
  setup() {
    const rows = computed(() => reorderRows())
    const ed = reactive({}) // sku → { on 勾选, sup, qty }：默认供应商、建议补货量，可以改；已经开了备货单的默认不勾
    watch(rows, (rs) => rs.forEach((r) => { if (!ed[r.sku]) ed[r.sku] = { on: !reorderDrafts(r.sku).length, sup: r.sup, qty: r.qty } }), { immediate: true })
    const st = (r) => ed[r.sku] || { on: false, sup: r.sup, qty: r.qty }
    const picked = computed(() => rows.value.filter((r) => st(r).on))
    const sups = computed(() => db.suppliers.filter((x) => !isFactory(x) && x.status === '合作中'))
    function gen() {
      const pos = run(() => reorderToPurchases(picked.value.map((r) => ({ sku: r.sku, sup: st(r).sup, qty: +st(r).qty }))))
      if (pos?.length) { picked.value.forEach((r) => { st(r).on = false }); toast(`按供应商拆成 ${pos.length} 张备货采购单草稿，审核后在途补上，这几行就不再提醒`, ['打开 ' + pos[0].no, '/purchases/' + pos[0].no]) }
    }
    return { rows, st, picked, sups, gen, reorderDrafts }
  },
  template: `<div>
    <Head t="补货提醒" :back="['库存', '/stock']"><button class="btn pri" :disabled="!picked.length" @click="gen">生成备货采购单<span v-if="picked.length">（{{ picked.length }}）</span></button></Head>
    <div class="page">
      <p class="dim tip">设了安全库存的物料 SKU，物料仓库存 + 全部在途 − 已被占用 低于安全库存就列在这里；在途不看赶不赶得上开裁，补货看的是会不会断。勾选后按供应商拆成备货采购单草稿：数量 = 差多少换成采购单位、按最小包装和起订量取整，单价按价格档案。补上以后自己消失。安全库存在物料详情的 SKU 上设。</p>
      <table class="tbl inset flat" data-c="补货清单">
        <thead><tr><th style="width:28px"></th><th>物料</th><th>供应商</th><th class="num">库存</th><th class="num">在途</th><th class="num">已被占用</th><th class="num">安全库存</th><th class="num">差多少</th><th class="num">补货量</th><th class="num">单价</th><th>已开的备货单</th></tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.sku" :class="{ sel: st(r).on }">
            <td><input type="checkbox" v-model="st(r).on" style="accent-color:var(--accent)"></td>
            <td><span class="row"><Thumb :code="r.sku" :s="30"/><span class="col"><a :href="'#/materials/' + r.mat">{{ skuName(r.sku) }}</a><small class="code dim">{{ r.sku }}</small></span></span></td>
            <td><select class="sel" style="height:26px" v-model="st(r).sup"><option value="">先选供应商</option><option v-for="x in sups" :key="x.code" :value="x.code">{{ x.short }}</option></select><div v-if="!r.sup" class="amber" style="font-size:12px">物料没设默认供应商</div></td>
            <td class="num">{{ fq(r.stock) }}</td><td class="num">{{ fq(r.transit) }}</td><td class="num">{{ fq(r.held) }}</td>
            <td class="num">{{ fq(r.safety) }} <span class="dim">{{ unitOf(r.sku) }}</span></td>
            <td class="num"><b class="amber">{{ fq(r.gap) }}</b></td>
            <td class="num"><input class="inp cell num" style="width:80px" type="number" v-model.number="st(r).qty"> <span class="dim">{{ purUnit(r.sku) }}</span></td>
            <td class="num">{{ st(r).sup ? fm(poDefaultPrice(st(r).sup, r.sku)) : '—' }}</td>
            <td><a v-for="p in reorderDrafts(r.sku)" :key="p.no" class="code" :href="'#/purchases/' + p.no" style="margin-right:6px">{{ p.no }}<span class="dim">（{{ p.status }}）</span></a><span v-if="!reorderDrafts(r.sku).length" class="dim">—</span></td>
          </tr>
          <tr v-if="!rows.length"><td colspan="11" class="empty">没有低于安全库存的物料：库存加在途都在安全库存以上</td></tr>
        </tbody>
      </table>
    </div>
  </div>`,
}, { mod: 'stock', t: '补货提醒' })

expose({ curMatPrice, curProcPrice, curMatRec, curProcRec, pricesOf, priceWhat, priceUnit, priceSrcLink, recordPrice, voidPrice, canEditPrice, poPriceIssues, procPriceProblem,
  tpCompare, carryDevTp, reorderRows, belowSafety, reorderToPurchases, reorderDrafts, poDefaultPrice, toProdTp, tpStage, tpName, transitRows, useTransitSplit, orderNeedDate, needDateOf })
