/* ============================================================
 * 资料：供应商、客户、物料、基础（颜色尺码、品类、仓库、主体、字典）；系统：设置
 * 改数据的函数在上面，页面在下面。供应商的准入 / 停用 / 拉黑走 2-store.js 的 FLOWS.supplier。
 * ============================================================ */

/* ---------- 供应商 ---------- */
// 证照：最早到期的那份；过期红、30 天内黄
function certInfo(s) {
  if (!s.certs.length) return { t: '—', tone: 'dim' }
  const left = Math.min(...s.certs.filter((c) => c.until).map((c) => daysLeft(c.until)))
  if (left < 0) return { t: '有证照已过期', tone: 'red' }
  if (left <= 30) return { t: left + ' 天后到期', tone: 'amber' }
  return { t: s.certs.length + ' 份', tone: 'dim' }
}
// 证照（PRD §6.8 资质子表）：c 不传 = 加一份；传了 = 改这份（换文件、改有效期、备注）
function saveCert(s, c, f) {
  if (!f.type || !f.until) fail('类型、有效期都要填')
  if (!f.file) fail('要传证照的照片或扫描件')
  if (!c) { s.certs.push({ type: f.type, until: f.until, file: f.file, note: f.note || '' }); act(s, '上传' + f.type + '，有效期至 ' + f.until); return }
  const ch = applyFields(c, f, [['type', '类型'], ['until', '有效期至'], ['note', '备注'], ['file', '文件', (v) => v?.name || '有']])
  if (!ch.length) fail('没有改动')
  act(s, '改证照 ' + c.type + '：' + ch.join('；'))
}
function removeCert(s, c) { s.certs.splice(s.certs.indexOf(c), 1); act(s, '删证照 ' + c.type + '（有效期至 ' + c.until + '）') }
const SUP_FIELDS = [['name', '全称'], ['short', '简称'], ['cats', '分类'], ['goodAt', '擅长品类', (v) => v.map((c) => catOf(c).name).join('、')], ['level', '等级'], ['contact', '联系人'], ['phone', '电话'], ['wechat', '微信'], ['addr', '地址'],
  ['settle', '结算方式'], ['term', '账期'], ['tax', '税率'], ['invoice', '发票类型'], ['bank', '开户行'], ['account', '账号'], ['buyer', '负责采购员', userName], ['note', '备注']]
function updateSupplier(s, f) {
  if (!f.short?.trim() || !f.name?.trim()) fail('全称、简称都要填')
  if (!f.cats?.length) fail('至少选一个分类')
  const ch = applyFields(s, f, SUP_FIELDS)
  if (!ch.length) fail('没有改动')
  act(s, '改：' + ch.join('；'))
  const w = ensureFactoryWh(s)
  return w ? '自动建了工厂仓「' + w.name + '」' : ''
}
// 被单据、物料、款式、资料包用过的不能删，只能停用（PRD §5.6）
function removeSupplier(s) {
  const w = factoryWh(s.code)
  const refs = [
    ...supplierDocs(s.code).map((d) => d.k + ' ' + d.no),
    ...db.materials.filter((m) => m.supplier === s.code).map((m) => '物料 ' + m.code + ' 的默认供应商'),
    ...db.styles.filter((x) => x.factory === s.code).map((x) => '款式 ' + x.code + ' 的默认加工厂'),
    ...db.techpacks.filter((t) => t.bom.some((r) => r.supplier === s.code)).map((t) => '资料包 ' + t.style + ' V' + t.ver),
    ...(w && whStock(w.code) ? [w.name + ' 里还有库存'] : []),
  ]
  if (refs.length) fail(`${s.short} 已经用过，不能删，只能停用`, refs.slice(0, 8))
  if (w) db.warehouses.splice(db.warehouses.indexOf(w), 1)
  db.suppliers.splice(db.suppliers.indexOf(s), 1)
}
function unblockSupplier(s, reason) {
  if (!reason) fail('要写解除原因')
  move('supplier', s, '解除黑名单', reason)
  s.blackReason = ''
}
function supplierDocs(code) {
  return [
    ...db.purchases.filter((p) => p.supplier === code).map((p) => ({ no: p.no, k: '采购单', date: p.date, qty: fq(poQty(p)), amt: poAmt(p), status: p.status, to: '/purchases/' + p.no })),
    ...db.productions.filter((p) => p.factory === code).map((p) => ({ no: p.no, k: '加工单', date: p.date, qty: prodQty(p) + ' 件', amt: prodAmt(p), status: p.status, to: '/production/' + p.no })),
    ...db.stockDocs.filter((d) => d.partner === code).map((d) => ({ no: d.no, k: d.type, date: d.date, qty: d.lines.length + ' 行', amt: null, status: d.status, to: '/stock-docs/' + d.no })),
    ...liveReturns().filter((r) => r.factory === code).map((r) => ({ no: r.no, k: '回货', date: r.date, qty: sum(r.lines, (x) => x.qty) + ' 件', amt: null, status: '', to: '/production/' + r.prod })),
  ].sort((a, b) => b.date.localeCompare(a.date))
}
// 绩效（12.04 待定）：准时 = 入库 / 回货日期不晚于交期
function supplierPerf(code) {
  const ins = db.stockDocs.filter((d) => d.type === '采购入库' && d.partner === code && d.status === '已审核')
  const insOk = ins.filter((d) => { const p = purchase(d.src); return !p || d.date <= p.due })
  const rets = liveReturns().filter((r) => r.factory === code)
  const retsOk = rets.filter((r) => r.date <= (production(r.prod)?.due || r.date))
  const n = ins.length + rets.length
  const prods = db.productions.filter((p) => p.factory === code && p.status !== '已作废')
  const pos = db.purchases.filter((p) => p.supplier === code && p.status !== '已作废')
  const ret = sum(prods, prodRet), def = sum(prods, prodDef)
  return {
    n, onTime: n ? fpct(insOk.length + retsOk.length, n) : null,
    late: arrivals().filter((a) => a.p.supplier === code && a.risk === 'late').length + prods.filter((p) => prodRisk(p).late).length,
    ret, def, defRate: ret ? rnd((def / ret) * 100, 1) : null,
    amt: rnd(sum(pos, poAmt) + sum(prods, prodAmt), 2), docs: pos.length + prods.length,
    issues: sum(prods, (p) => p.issues.length),
  }
}
// 询价记录（12.06 待定）：先用采购单上的成交价
const priceHistory = (code) => db.purchases.filter((p) => p.supplier === code && p.status !== '已作废')
  .flatMap((p) => p.lines.map((l) => ({ date: p.date, no: p.no, sku: l.sku, price: l.price, qty: l.qty, unit: matOfSku(l.sku)?.purUnit })))
  .sort((a, b) => b.date.localeCompare(a.date))

/* ---------- 客户 ---------- */
// f.code、f.status 给导入用（领猫里的老编码原样带过来）
function createCustomer({ imported, ...f }) {
  if (!f.name || !f.short) fail('全称、简称都要填')
  const code = (f.code || '').trim().toUpperCase() || nextCode('KH', db.customers)
  if (cust(code)) fail(`客户编码 ${code} 已经有了`)
  const c = add(db.customers, { contact: '', phone: '', addr: '', settle: '月结', term: 30, note: '', ...f, code, status: f.status || '启用', terms: { price: '', deduct: '', rebate: '' }, log: [] })
  act(c, imported ? '导入' : '新建')
  return c
}
const customerStock = (code) => Object.entries(db.stock).map(([k, q]) => { const [w, sku, o] = k.split('|'); return { w, sku, o, q } }).filter((x) => x.o === code && x.q > 0)
const CUST_FIELDS = [['name', '全称'], ['short', '简称'], ['contact', '联系人'], ['phone', '电话'], ['addr', '地址'], ['settle', '结算方式'], ['term', '账期'], ['note', '备注']]
function updateCustomer(c, f) {
  if (!f.name?.trim() || !f.short?.trim()) fail('全称、简称都要填')
  const ch = applyFields(c, f, CUST_FIELDS)
  if (!ch.length) fail('没有改动')
  act(c, '改：' + ch.join('；'))
}
function removeCustomer(c) {
  const refs = [...db.orders.filter((o) => o.customer === c.code).map((o) => '订单 ' + o.no), ...db.stockDocs.filter((d) => d.partner === c.code).map((d) => d.type + ' ' + d.no), ...customerStock(c.code).map((x) => whName(x.w) + ' 有它的客供料')]
  if (refs.length) fail(`${c.short} 已经用过，不能删，只能停用`, refs.slice(0, 8))
  db.customers.splice(db.customers.indexOf(c), 1)
}

/* ---------- 物料 ---------- */
const MAT_GROUPS = ['面料', '辅料', '包材']
const matCat = (c) => db.materialCategories.find((x) => x.code === c) || { code: c, name: c, parent: '' }
const nextMatCode = (cat) => cat + String(Math.max(0, ...db.materials.filter((m) => m.code.startsWith(cat)).map((m) => +m.code.slice(cat.length) || 0)) + 1).padStart(4, '0')
// 查重（PRD §6.1.5 规则 1）：同一分类下名称相同，或者同一供应商下供应商货号 + 色号相同；只提示，不拦
const dupMaterial = (f, self) => db.materials.find((m) => m !== self && ((m.cat === f.cat && m.name.trim() === (f.name || '').trim())
  || (f.supNo && f.supplier && m.supplier === f.supplier && m.supNo === f.supNo && (!(f.skus || []).some((k) => k.supColor) || m.skus.some((k) => k.supColor && f.skus.some((x) => x.supColor === k.supColor))))))
// 规格码：规格去掉空格和末尾的单位，"60cm" → 60，"18L" → 18L
const specCode = (spec) => (spec || '').replace(/\s+/g, '').replace(/(cm|mm|厘米|毫米|码)$/i, '').toUpperCase()
const skuCodeOf = (m, color, spec) => [m.code, color, specCode(spec)].filter(Boolean).join('-')
// f.skus = [{ color, spec, supColor, price, safety }]（新建时一起填）；不给 = 一个不带颜色的 SKU。f.code 给导入用
function createMaterial({ imported, ...f }) {
  if (!f.name) fail('物料名称要填')
  if (!(f.factor > 0)) fail('换算系数要大于 0')
  const code = (f.code || '').trim().toUpperCase() || nextMatCode(f.cat)
  if (mat(code)) fail(`物料编码 ${code} 已经有了`)
  const m = add(db.materials, { purUnit: f.unit, factor: 1, pack: 1, moq: 0, lead: 7, status: '启用', ...f, code, colors: undefined, skus: [], log: [] })
  const rows = f.skus?.length ? f.skus : (f.colors || []).map((color) => ({ color }))
  for (const x of rows.length ? rows : [{}]) {
    const k = skuCodeOf(m, x.color, x.spec)
    if (m.skus.some((y) => y.code === k)) fail(`SKU ${k} 重复了`)
    m.skus.push({ code: k, color: x.color || undefined, spec: x.spec || undefined, supColor: x.supColor || '', price: x.price ?? null, safety: x.safety ?? null, status: '启用' })
  }
  act(m, (imported ? '导入' : '新建') + '，' + m.skus.length + ' 个 SKU')
  return m
}
// 批量加 SKU（规则 2）：颜色、规格都能一次多选，两两组合；没有颜色的（不同长度的拉链）只填规格
function addMaterialSkus(m, colors, specs, silent) {
  const cs = colors.length ? colors : [null], ss = (specs || []).length ? specs : ['']
  if (!colors.length && !ss.some(Boolean)) fail('颜色、规格至少填一样')
  const made = []
  for (const c of cs) for (const sp of ss) {
    const code = skuCodeOf(m, c, sp)
    if (m.skus.some((k) => k.code === code)) continue
    m.skus.push({ code, color: c || undefined, spec: sp || undefined, supColor: '', price: null, safety: null, status: '启用' })
    made.push(code)
  }
  if (!made.length) fail('这些 SKU 已经有了')
  if (!silent) act(m, '加 SKU ' + made.join('、'))
  return made
}
function setMaterialStatus(m, st) { m.status = st; act(m, st) }
// SKU 被采购单、库存、BOM 用过就不能删，只能停用（规则 3）
function skuUsedBy(code) {
  const po = db.purchases.find((p) => p.lines.some((l) => l.sku === code))
  if (po) return '采购单 ' + po.no
  const sd = db.stockDocs.find((d) => d.lines.some((l) => l.sku === code))
  if (sd) return sd.type + ' ' + sd.no
  if (skuStock(code)) return '库存'
  const tp = db.techpacks.find((t) => t.bom.some((r) => r.sku === code || Object.values(r.map || {}).includes(code)))
  return tp ? '资料包 ' + tp.style + ' V' + tp.ver : ''
}
function removeMatSku(m, k) {
  const u = skuUsedBy(k.code)
  if (u) fail(`${k.code} 已经被${u}用过，不能删，只能停用`)
  if (m.skus.length === 1) fail('物料至少留一个 SKU')
  m.skus.splice(m.skus.indexOf(k), 1)
  act(m, '删 SKU ' + k.code)
}
function toggleMatSku(m, k) { k.status = k.status === '停用' ? '启用' : '停用'; act(m, k.code + ' ' + k.status) }
function setSkuImages(m, k, list) { k.images = list; act(m, k.code + ' 图片 ' + list.length + ' 张') }
function removeMaterial(m) {
  const u = matLockReason(m) || (matStock(m) ? '还有库存' : '')
  if (u) fail(`${m.code} ${u}，不能删，只能停用`)
  db.materials.splice(db.materials.indexOf(m), 1)
}
// 改物料（PRD §6.1.5）：编码不变；有过采购、出入库、资料包之后，单位和换算系数不能改（改了以前单据上的数量就对不上）
function matLockReason(m) {
  const skus = new Set(m.skus.map((k) => k.code))
  if (db.purchases.some((p) => p.lines.some((l) => skus.has(l.sku)))) return '已经有采购单用到它'
  if (db.stockDocs.some((d) => d.lines.some((l) => skus.has(l.sku)))) return '已经有出入库单用到它'
  if (db.techpacks.some((t) => t.bom.some((r) => r.mat === m.code))) return '已经有资料包用到它'
  return ''
}
const MAT_FIELDS = [['name', '名称'], ['cat', '分类', (v) => matCat(v).name], ['type', '类型'], ['unit', '库存单位'], ['purUnit', '采购单位'], ['factor', '换算系数'],
  ['width', '门幅'], ['weight', '克重'], ['comp', '成分'], ['texture', '组织'], ['supplier', '默认供应商', (v) => supName(v)], ['supNo', '供应商货号'], ['price', '参考单价'],
  ['moq', '起订量'], ['pack', '最小包装'], ['lead', '采购周期'], ['year', '年份'], ['season', '季节', (v) => seasonName(v)], ['note', '备注']]
function updateMaterial(m, f) {
  if (!f.name?.trim()) fail('物料名称要填')
  if (!(f.factor > 0)) fail('换算系数要大于 0')
  const lock = matLockReason(m)
  if (lock && (f.unit !== m.unit || f.purUnit !== m.purUnit || f.factor !== m.factor)) fail(lock + '，单位和换算系数不能改', ['改了以后，以前单据上的数量就对不上了', '确实要换单位：新建一个物料，把这个停用'])
  const changes = MAT_FIELDS.filter(([k]) => (f[k] ?? '') !== (m[k] ?? '')).map(([k, t, fmt]) => `${t} ${showVal(m[k], fmt)} → ${showVal(f[k], fmt)}`)
  const skus = f.skus || []
  for (const x of skus) {
    const k = m.skus.find((y) => y.code === x.code)
    if ((x.supColor || '') !== (k.supColor || '')) changes.push(`${k.code} 供应商色号 ${k.supColor || '空'} → ${x.supColor || '空'}`)
    if ((x.safety ?? '') !== (k.safety ?? '')) changes.push(`${k.code} 安全库存 ${k.safety ?? '空'} → ${x.safety ?? '空'}`)
    if ((x.price ?? '') !== (k.price ?? '')) changes.push(`${k.code} 单价 ${k.price ?? '空'} → ${x.price ?? '空'}`)
    if ((x.status || '启用') !== (k.status || '启用')) changes.push(`${k.code} ${x.status}`)
  }
  if (!changes.length) fail('没有改动')
  MAT_FIELDS.forEach(([k]) => { m[k] = f[k] })
  for (const x of skus) Object.assign(m.skus.find((y) => y.code === x.code), { supColor: x.supColor, safety: x.safety, price: x.price ?? null, status: x.status })
  act(m, '改：' + changes.join('；'))
}

/* ---------- 品类、物料分类（PRD §6.1.2、§6.1.4）：编码定了不改；用过的不能删，只能停用 ---------- */
const catUsed = (c) => db.styles.filter((s) => s.cat === c).length
const matCatUsed = (c) => db.materials.filter((m) => m.cat === c).length
const liveCats = () => db.categories.filter((c) => c.status !== '停用')
const liveMatCats = () => db.materialCategories.filter((c) => c.status !== '停用')
function addCategory(f) {
  const code = (f.code || '').trim().toUpperCase(), name = (f.name || '').trim(), path = (f.path || '').trim().replace(/\s*\/\s*/g, ' / ')
  if (!/^[^/]+ \/ [^/]+$/.test(path)) fail('上级写成「大类 / 中类」，如 女装 / 外套')
  if (!/^[A-Z0-9]$/.test(code)) fail('品类编码是 1 位字母或数字，会进款号（如 F = 风衣）')
  if (!name) fail('名称要填')
  if (db.categories.some((c) => c.code === code)) fail('编码 ' + code + ' 已经有了')
  if (db.categories.some((c) => c.path === path && c.name === name)) fail(path + ' 下已经有「' + name + '」')
  db.categories.push({ code, name, path, shape: db.categories.find((c) => c.path === path)?.shape || 'tee', status: '启用' })
}
function addMatCategory(f) {
  const code = (f.code || '').trim().toUpperCase(), name = (f.name || '').trim()
  if (!MAT_GROUPS.includes(f.parent)) fail('选一个大类')
  if (!/^[A-Z]{2}$/.test(code)) fail('物料分类编码是 2 位字母，会作为物料编码的前缀（如 ML0012）')
  if (!name) fail('名称要填')
  if (db.materialCategories.some((c) => c.code === code)) fail('编码 ' + code + ' 已经有了')
  if (db.materialCategories.some((c) => c.parent === f.parent && c.name === name)) fail(f.parent + ' 下已经有「' + name + '」')
  db.materialCategories.push({ code, name, parent: f.parent, status: '启用' })
}
function renameCat(list, c, name, sibling, where = '同一个上级下') {
  name = (name || '').trim()
  if (!name) fail('名称要填')
  if (list.some((x) => x !== c && sibling(x) === sibling(c) && x.name === name)) fail(where + '已经有「' + name + '」')
  c.name = name
}
function removeCat(list, c, used, what) {
  if (used) fail(`已经有 ${used} 个${what}用这个分类，不能删，只能停用`)
  list.splice(list.indexOf(c), 1)
}
const toggleCat = (c) => { c.status = c.status === '停用' ? '启用' : '停用' }
// 季节（PRD §6.1.2）：字典带 1 位编码，进款号（27CF001 里的 C）；用过的不能删、编码不能改，只能停用
const seasonUsed = (c) => db.styles.filter((s) => s.season === c).length + db.materials.filter((m) => m.season === c).length + (db.samples || []).filter((x) => x.season === c).length
const liveSeasons = (keep) => db.seasons.filter((x) => x.status !== '停用' || x.code === keep)
function addSeason(f) {
  const code = (f.code || '').trim().toUpperCase(), name = (f.name || '').trim()
  if (!/^[A-Z0-9]$/.test(code)) fail('季节编码是 1 位字母或数字，会进款号（如 C = 春）')
  if (!name) fail('名称要填')
  if (db.seasons.some((x) => x.code === code)) fail('编码 ' + code + ' 已经有了')
  if (db.seasons.some((x) => x.name === name)) fail('已经有「' + name + '」')
  db.seasons.push({ code, name, status: '启用' })
}
// 库存：type 不传 = 所有仓
const skuStock = (sku, type) => rnd(sum(Object.entries(db.stock).filter(([k]) => { const [w, s] = k.split('|'); return s === sku && (!type || wh(w)?.type === type) }), ([, v]) => v), 3)
const matStock = (m) => rnd(sum(m.skus, (k) => skuStock(k.code)), 3)
const matUsedBy = (code) => db.techpacks.filter((t) => t.status === '已生效').flatMap((t) => t.bom.filter((r) => r.mat === code).map((r) => ({ style: t.style, ver: t.ver, part: r.part, src: r.src })))

/* ---------- 基础 ---------- */
function addColor(f) {
  const code = (f.code || '').trim().toUpperCase()
  if (!/^[A-Z0-9]{2,3}$/.test(code)) fail('颜色编码是 2-3 位字母或数字')
  if (!f.name) fail('颜色名称要填')
  if (db.colors.some((c) => c.code === code)) fail('编码 ' + code + ' 已经有了')
  if (db.colors.some((c) => c.name === f.name)) fail('颜色 ' + f.name + ' 已经有了')
  db.colors.push({ code, name: f.name, family: f.family, hex: f.hex, status: '启用' })
}
// 颜色编码进了 SKU 码，不能改；名称、色系、色块可以改
function editColor(c, f) {
  const name = (f.name || '').trim()
  if (!name) fail('颜色名称要填')
  if (db.colors.some((x) => x !== c && x.name === name)) fail('颜色 ' + name + ' 已经有了')
  if (name === c.name && f.family === c.family && f.hex === c.hex) fail('没有改动')
  Object.assign(c, { name, family: f.family, hex: f.hex })
}
const sizeGroupUsed = (id) => db.styles.filter((s) => s.sizeGroup === id).length
function appendSize(g, z) {
  z = (z || '').trim().toUpperCase()
  if (!z) fail('尺码要填')
  if (g.sizes.includes(z)) fail(g.name + ' 里已经有 ' + z)
  g.sizes.push(z)
}
function removeSize(g, z) {
  if (sizeGroupUsed(g.id)) fail('已经有款式用这个尺码组，只能在末尾追加尺码')
  if (g.sizes.length === 1) fail('至少留一个尺码；整组不要了就删除尺码组')
  g.sizes.splice(g.sizes.indexOf(z), 1)
  if (g.base === z) g.base = g.sizes[Math.floor((g.sizes.length - 1) / 2)]
}
// 改名、换基码、删除都只能在没有款式用的时候（基码决定核价取哪个码的用量）
function renameSizeGroup(g, name) {
  name = (name || '').trim()
  if (sizeGroupUsed(g.id)) fail('已经有款式用这个尺码组，不能改名')
  if (!name) fail('尺码组名称要填')
  if (db.sizeGroups.some((x) => x !== g && x.name === name)) fail('已经有「' + name + '」')
  g.name = name
}
function setBaseSize(g, z) {
  if (sizeGroupUsed(g.id)) fail('已经有款式用这个尺码组，基码不能换')
  g.base = z
}
function removeSizeGroup(g) {
  if (sizeGroupUsed(g.id)) fail(`已经有 ${sizeGroupUsed(g.id)} 个款式用这个尺码组，不能删，只能停用`)
  db.sizeGroups.splice(db.sizeGroups.indexOf(g), 1)
}
// 停用的颜色、尺码组不出现在新建款式、物料的选项里（PRD §5.6）；已经用了的不受影响
const liveColors = () => db.colors.filter((c) => c.status !== '停用')
const colorUsed = (c) => db.styles.filter((s) => s.colors.includes(c)).length + db.materials.filter((m) => m.skus.some((k) => k.color === c)).length
function addSizeGroup(f) {
  const name = (f.name || '').trim(), sizes = (f.sizes || '').trim().toUpperCase().split(/[\s,，、]+/).filter(Boolean)
  if (!name) fail('尺码组名称要填')
  if (db.sizeGroups.some((g) => g.name === name)) fail('已经有「' + name + '」')
  if (!sizes.length) fail('至少一个尺码，用空格隔开，如 S M L XL')
  if (new Set(sizes).size < sizes.length) fail('尺码重复了')
  const base = (f.base || '').trim().toUpperCase()
  if (base && !sizes.includes(base)) fail('基码 ' + base + ' 不在尺码里')
  db.sizeGroups.push({ id: 'G' + (db.sizeGroups.length + 1), name, sizes, base: base || sizes[Math.floor((sizes.length - 1) / 2)], status: '启用' })
}

/* ---------- 仓库（PRD §6.1.6）、主体公司（§6.1.1）、字典（§6.1.7） ---------- */
const whStock = (code) => rnd(sum(Object.entries(db.stock).filter(([k, v]) => k.startsWith(code + '|') && v > 0), ([, v]) => v), 3)
function saveWarehouse(w, f) {
  const code = (f.code || '').trim().toUpperCase()
  if (!code || !f.name?.trim()) fail('编码、名称都要填')
  if (db.warehouses.some((x) => x !== w && x.code === code)) fail('仓库编码 ' + code + ' 已经有了')
  if (f.type === '工厂仓' && !f.factory) fail('工厂仓要绑定加工厂')
  if (f.type === '工厂仓' && db.warehouses.some((x) => x !== w && x.factory === f.factory)) fail(supName(f.factory) + ' 已经有工厂仓了，一个加工厂对应一个工厂仓')
  const v = { code, name: f.name.trim(), type: f.type, factory: f.type === '工厂仓' ? f.factory : undefined, addr: f.addr || '', keeper: f.keeper || '', phone: f.phone || '' }
  if (w) { if (w.code !== code && whStock(w.code)) fail('仓库里有库存，编码不能改'); Object.assign(w, v) }
  else db.warehouses.push({ ...v, status: '启用' })
}
function toggleWarehouse(w) {
  if (w.status === '启用' && whStock(w.code)) fail(`${w.name} 里还有库存，先调走或出库再停用`)
  w.status = w.status === '启用' ? '停用' : '启用'
}
function saveCompany(c, f) {
  const code = (f.code || '').trim().toUpperCase()
  if (!/^[A-Z0-9]{2,6}$/.test(code)) fail('主体编码是 2-6 位字母或数字')
  if (!f.name?.trim() || !f.short?.trim()) fail('名称、简称都要填')
  if (db.companies.some((x) => x !== c && x.code === code)) fail('编码 ' + code + ' 已经有了')
  const v = { code, name: f.name.trim(), short: f.short.trim(), credit: f.credit || '', addr: f.addr || '', phone: f.phone || '' }
  if (c) { if (c.code !== code && [...db.orders, ...db.purchases, ...db.productions, ...db.stockDocs].some((d) => d.company === c.code)) fail('已经有单据用这个主体，编码不能改'); Object.assign(c, v) }
  else db.companies.push({ ...v, status: '启用' })
}
function toggleCompany(c) {
  if (c.status === '启用' && db.companies.filter((x) => x.status === '启用').length === 1) fail('至少要有一个启用的主体')
  c.status = c.status === '启用' ? '停用' : '启用'
}
function addDictValue(k, v) {
  v = (v || '').trim()
  if (!v) fail('值要填')
  if (db.dicts[k].some((x) => (Array.isArray(x) ? x[0] : x) === v)) fail(k + ' 里已经有「' + v + '」')
  db.dicts[k].push(k === '生产节点' ? [v, 3] : v)
}
function removeDictValue(k, v) {
  if (db.dicts[k].length === 1) fail(k + ' 至少留一个值')
  db.dicts[k].splice(db.dicts[k].indexOf(v), 1)
}

/* ---------- 设置 ---------- */
const ROLES = ['开发', '业务', '生产跟单', '采购', '仓管', '财务', '管理层']
const PERM_CYCLE = ['—', '看', '改', '审']
Object.assign(db, {
  // PRD §3.1 预置角色模板：模块 × 角色
  perms: [
    { m: '基础资料', v: ['改', '看', '看', '看', '看', '看', '看'] },
    { m: '物料档案', v: ['改', '看', '看', '改', '看', '看', '看'] }, // PRD §3.1：采购"改（物料）"
    { m: '供应商', v: ['看', '看', '看', '改', '看', '看', '审'] },
    { m: '款式、资料包', v: ['审', '看', '看', '看', '看', '看', '看'] },
    { m: '订单', v: ['看', '改', '看', '看', '—', '看', '审'] },
    { m: '排单、加工单', v: ['看', '看', '改', '看', '看', '看', '审'] },
    { m: '算料、采购单', v: ['—', '看', '看', '改', '看', '看', '审'] },
    { m: '出入库、库存', v: ['—', '—', '看', '看', '审', '看', '看'] },
    { m: '进度、异常、回货', v: ['—', '看', '改', '看', '看', '—', '看'] },
  ],
  approvalRules: [
    { doc: '采购单', over: 50000, role: '管理层' },
    { doc: '加工单', over: 100000, role: '管理层' },
  ],
  notify: { 站内待办: true, 钉钉: false, 企业微信: false, 飞书: false, 邮件: false },
  notifyEvents: { 待我审核: true, 加工单逾期: true, 节点延期: true, 到料逾期: true, 上游订单变更: true, 证照到期: false },
  integration: { jst: '', fin: '' },
})
const AUDITS = [
  { t: '订单', p: 'order:audit', m: '订单', n: () => db.orders.filter((o) => o.status === '待审核').length },
  { t: '订单变更', p: 'change:audit', m: '订单', n: () => db.changes.filter((c) => c.status === '待审核').length },
  { t: '资料包生效', p: 'techpack:publish', m: '款式、资料包', n: () => db.techpacks.filter((t) => t.status === '草稿').length },
  { t: '采购单', p: 'purchase:audit', m: '算料、采购单', n: () => db.purchases.filter((p) => p.status === '待审核').length },
  { t: '加工单', p: 'production:audit', m: '排单、加工单', n: () => db.productions.filter((p) => p.status === '待审核').length },
  { t: '出入库单', p: 'stock:audit', m: '出入库、库存', n: () => db.stockDocs.filter((d) => d.status === '草稿').length },
  { t: '供应商准入、拉黑', p: 'supplier:admit', m: '供应商', n: () => db.suppliers.filter((s) => s.status === '待准入').length },
]
// 数据范围（PRD §3.2）挂在角色上：本人 = 只看制单人、跟单员、采购员、业务员是自己的单据
const roleOf = (name) => db.roles.find((r) => r.name === name) || { name, scope: '全部' }
const scopeOf = (uid) => roleOf(db.users.find((u) => u.id === uid)?.role).scope
const auditRoles = (m) => { const row = db.perms.find((x) => x.m === m); return ROLES.filter((r, i) => row?.v[i] === '审') }
function cyclePerm(row, i) { row.v[i] = PERM_CYCLE[(PERM_CYCLE.indexOf(row.v[i]) + 1) % PERM_CYCLE.length] }
// 操作日志：各单据的记录汇总，按时间倒序
function allLogs() {
  const src = [
    ['订单', db.orders, (d) => d.no, (d) => '/orders/' + d.no],
    ['变更', db.changes, (d) => d.no, (d) => '/orders/' + d.order],
    ['算料', db.mrps, (d) => d.no, (d) => '/mrp/' + d.no],
    ['采购', db.purchases, (d) => d.no, (d) => '/purchases/' + d.no],
    ['出入库', db.stockDocs, (d) => d.no, (d) => '/stock-docs/' + d.no],
    ['加工单', db.productions, (d) => d.no, (d) => '/production/' + d.no],
    ['回货', db.returns, (d) => d.no, (d) => '/production/' + d.prod],
    ['供应商', db.suppliers, (d) => d.code, (d) => '/suppliers/' + d.code],
    ['客户', db.customers, (d) => d.code, (d) => '/customers/' + d.code],
    ['物料', db.materials, (d) => d.code, (d) => '/materials/' + d.code],
    ['款式', db.styles, (d) => d.code, (d) => '/styles/' + d.code],
    ['资料包', db.techpacks, (d) => d.style + ' V' + d.ver, (d) => '/styles/' + d.style],
  ]
  let n = 0
  return src.flatMap(([k, list, id, to]) => list.flatMap((d) => (d.log || []).map((x) => ({ ...x, k, id: id(d), to: to(d), n: n++ }))))
    .sort((a, b) => b.at.localeCompare(a.at) || b.n - a.n)
}

SEARCH.push(() => [
  ...db.suppliers.map((s) => ({ t: s.code + ' ' + s.short, sub: '供应商 · ' + s.cats.join('、'), to: '/suppliers/' + s.code, i: 'store' })),
  ...db.customers.map((c) => ({ t: c.code + ' ' + c.short, sub: '客户', to: '/customers/' + c.code, i: 'user' })),
  ...db.materials.map((m) => ({ t: m.code + ' ' + m.name, sub: '物料', to: '/materials/' + m.code, i: 'box' })),
])

expose({
  createSupplier, admitSupplier, blacklistSupplier, unblockSupplier, openDocsOfSupplier, expiringCerts, certInfo, saveCert, removeCert, updateSupplier, removeSupplier, supplierDocs, supplierPerf, priceHistory,
  createCustomer, updateCustomer, removeCustomer, customerStock, MAT_GROUPS, matCat, nextMatCode, dupMaterial, createMaterial, addMaterialSkus, setMaterialStatus, skuStock, matStock, matUsedBy,
  specCode, skuCodeOf, skuUsedBy, removeMatSku, toggleMatSku, setSkuImages, removeMaterial, roleOf, scopeOf, nextCode,
  matLockReason, updateMaterial, catUsed, matCatUsed, liveCats, liveMatCats, addCategory, addMatCategory, renameCat, removeCat, toggleCat,
  addColor, editColor, sizeGroupUsed, appendSize, removeSize, renameSizeGroup, setBaseSize, removeSizeGroup, liveColors, colorUsed, addSizeGroup, whStock,
  seasonUsed, liveSeasons, addSeason, saveWarehouse, toggleWarehouse, saveCompany, toggleCompany, addDictValue, removeDictValue,
  ROLES, AUDITS, auditRoles, cyclePerm, allLogs, factoryWh,
})

/* ============================================================
 * 页面
 * ============================================================ */
route('/suppliers', {
  setup() {
    const s = reactive({ cat: '全部', st: '', q: '', cert: nav.query.f === '证照', add: false, imp: false })
    const tabs = computed(() => [['全部', '全部', db.suppliers.length], ...db.dicts.供应商分类.map((c) => [c, c, db.suppliers.filter((x) => x.cats.includes(c)).length])])
    const soon = computed(() => new Set(expiringCerts().map((x) => x.s.code)))
    const rows = computed(() => db.suppliers.filter((x) => (s.cat === '全部' || x.cats.includes(s.cat)) && (!s.st || x.status === s.st) && (!s.cert || soon.value.has(x.code))
      && matchQ(s.q, x.code, x.short, x.name, x.contact, x.phone)))
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="供应商"><button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入</button><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建供应商</button></Head>
    <div class="bar" data-c="筛选">
      <Seg v-model="s.cat" :items="tabs"/>
      <button v-if="s.cert" class="chip on" @click="s.cert = false">证照 30 天内到期<Ico n="x" :s="12"/></button>
      <span class="sp"></span>
      <select class="sel" v-model="s.st"><option value="">全部状态</option><option v-for="x in ['合作中', '待准入', '停用', '黑名单']" :key="x">{{ x }}</option></select>
      <Search v-model="s.q"/>
    </div>
    <div data-c="供应商列表">
      <Tbl :rows="rows" link @row="x => go('/suppliers/' + x.code)" empty="没有符合条件的供应商" :cols="[
        { k: 'code', t: '编码', c: 'code' }, { k: 'short', t: '简称' }, { k: 'cats', t: '分类', f: x => x.cats.join('、') }, { k: 'level', t: '等级' },
        { k: 'status', t: '状态' }, { k: 'contact', t: '联系人' }, { k: 'settle', t: '结算', f: x => x.settle + (x.term ? ' ' + x.term + ' 天' : '') },
        { k: 'buyer', t: '负责人', f: x => userName(x.buyer) }, { k: 'cert', t: '证照' }]">
        <template #short="{ r }"><span>{{ r.short }}</span> <span class="dim">{{ r.name }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
        <template #cert="{ r }"><span :class="certInfo(r).tone">{{ certInfo(r).t }}</span></template>
      </Tbl>
    </div>
    <SupplierForm v-if="s.add" @close="s.add = false"/>
    <ImportBox v-if="s.imp" kind="suppliers" @close="s.imp = false"/>
  </div>`,
}, { mod: 'suppliers', t: '供应商' })

/* 新建、编辑供应商共用：传 s 就是编辑（编码、状态不在这里改；状态走准入、停用、拉黑） */
comp('SupplierForm', {
  props: { s: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const keys = SUP_FIELDS.map(([k]) => k)
    const f = reactive(p.s ? Object.fromEntries(keys.map((k) => [k, Array.isArray(p.s[k]) ? [...p.s[k]] : p.s[k] ?? ''])) : { name: '', short: '', cats: [], goodAt: [], level: 'B', contact: '', phone: '', wechat: '', addr: '', settle: '月结', term: 30, tax: '13%', invoice: '增值税专用发票', bank: '', account: '', buyer: 'u2', note: '' })
    const toggle = (list, c) => { const i = list.indexOf(c); i >= 0 ? list.splice(i, 1) : list.push(c) }
    const cats = computed(() => db.categories.filter((c) => c.status !== '停用' || f.goodAt.includes(c.code)))
    function save() {
      if (p.s) { const r = run(() => updateSupplier(p.s, f)); if (r !== undefined) { emit('close'); toast(p.s.short + ' 已保存' + (r ? '；' + r : '')) } return }
      const s = run(() => createSupplier({ ...f, cats: [...f.cats], goodAt: [...f.goodAt] }))
      if (!s) return
      emit('close')
      go('/suppliers/' + s.code)
      const w = factoryWh(s.code)
      toast(s.short + ' 已建，待准入' + (w ? '；自动建了工厂仓「' + w.name + '」' : ''))
    }
    return { f, toggle, cats, save }
  },
  template: `<Modal :t="s ? '编辑供应商 ' + s.code : '新建供应商'" :w="680" @close="$emit('close')">
    <div class="form" style="grid-template-columns:1fr 1fr 1fr">
      <label class="f full">全称<input class="inp" v-model="f.name"></label>
      <label class="f">简称<input class="inp" v-model="f.short"></label>
      <label class="f">编码<input class="inp code" :value="s ? s.code : nextCode('GYS', db.suppliers)" disabled :title="s ? '编码定了不改' : '自动编'"></label>
      <label class="f">等级<select class="sel" v-model="f.level"><option v-for="x in ['A', 'B', 'C', 'D']" :key="x">{{ x }}</option></select></label>
      <div class="f full">分类<div class="chips"><button v-for="c in db.dicts.供应商分类" :key="c" class="chip" :class="{ on: f.cats.includes(c) }" @click="toggle(f.cats, c)">{{ c }}</button></div></div>
      <div class="f full" data-c="擅长品类">擅长品类<span class="dim" style="font-weight:400">（成衣厂、加工厂选；排单时参考）</span><div class="chips"><button v-for="c in cats" :key="c.code" class="chip" :class="{ on: f.goodAt.includes(c.code) }" @click="toggle(f.goodAt, c.code)">{{ c.name }}</button></div></div>
      <label class="f">联系人<input class="inp" v-model="f.contact"></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
      <label class="f">微信<input class="inp" v-model="f.wechat"></label>
      <label class="f full">地址<input class="inp" v-model="f.addr"></label>
      <label class="f">结算方式<select class="sel" v-model="f.settle"><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></select></label>
      <label class="f">账期（天）<input class="inp num" type="number" v-model.number="f.term"></label>
      <label class="f">负责采购员<select class="sel" v-model="f.buyer"><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }} · {{ u.role }}</option></select></label>
      <label class="f">发票类型<select class="sel" v-model="f.invoice"><option value="">—</option><option v-for="x in db.dicts.发票类型" :key="x">{{ x }}</option></select></label>
      <label class="f">税率<select class="sel" v-model="f.tax"><option v-for="x in db.dicts.税率" :key="x">{{ x }}</option></select></label>
      <span></span>
      <label class="f">开户行<input class="inp" v-model="f.bank"></label>
      <label class="f" style="grid-column:span 2">账号<input class="inp code" v-model="f.account"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <template #foot><span v-if="!s" class="dim" style="font-size:12px">建好是「待准入」，准入后才能下采购单、加工单</span><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ s ? '保存' : '建供应商' }}</button></template>
  </Modal>`,
})

route('/suppliers/:code', {
  props: ['code'],
  setup(p) {
    const s = computed(() => sup(p.code))
    const tab = ref('certs')
    const box = ref(null) // { mode: '拉黑' | '解除黑名单' }
    const certAdd = ref(false), certEdit = ref(null), editing = ref(false)
    const docs = computed(() => supplierDocs(p.code))
    const perf = computed(() => supplierPerf(p.code))
    const prices = computed(() => priceHistory(p.code))
    function admit() {
      const r = run(() => doAction('supplier', s.value, '准入'))
      if (r) toast(typeof r === 'string' ? '已准入；' + r : '已准入，现在能选进采购单、加工单')
    }
    async function disable() {
      if (await ask('停用 ' + s.value.short, '停用后不能再选进新的采购单、加工单；已有单据照常入库、回货。', '停用', true)) run(() => doAction('supplier', s.value, '停用'))
    }
    async function remove() {
      if (await ask('删除 ' + s.value.short, '没有单据、物料、款式用过它才能删；删了找不回来。用过的只能停用。', '删除', true) && run(() => removeSupplier(s.value))) { go('/suppliers'); toast('删了') }
    }
    async function delCert(c) { if (await ask('删除这份' + c.type, '删了找不回来；证照换新的可以点「换」。', '删除', true)) removeCert(s.value, c) }
    const more = computed(() => s.value ? [
      can('supplier', s.value, '停用') && { t: '停用', fn: disable },
      can('supplier', s.value, '拉黑') && { t: '拉黑', danger: true, fn: () => { box.value = { mode: '拉黑' } } },
      { t: '删除', danger: true, fn: remove },
    ].filter(Boolean) : [])
    return { s, tab, box, certAdd, certEdit, editing, docs, perf, prices, admit, more, delCert }
  },
  template: `<div v-if="s">
    <Head :t="s.short" :back="['供应商', '/suppliers']">
      <button v-if="can('supplier', s, '准入')" class="btn pri" @click="admit">准入</button>
      <button v-if="can('supplier', s, '启用')" class="btn pri" @click="run(() => doAction('supplier', s, '启用'))">恢复合作</button>
      <button v-if="can('supplier', s, '解除黑名单')" class="btn" @click="box = { mode: '解除黑名单' }">解除黑名单</button>
      <button class="btn" @click="editing = true">编辑</button>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ s.short }}<St :s="s.status"/></div>
        <div class="sub"><span>{{ s.name }}</span><span>{{ s.cats.join('、') }}</span><span>{{ s.level }} 级</span></div>
        <div v-if="s.status === '黑名单'" class="banner red" data-c="黑名单原因"><Ico n="alert"/>{{ s.blackReason }}<span v-if="s.blackAt" class="dim" style="margin-left:auto;font-size:12px">{{ userName(s.blackBy) }} · {{ fd(s.blackAt) }}</span></div>
        <Tabs v-model="tab" :items="[['certs', '资质', s.certs.length], ['docs', '往来单据', docs.length], ['perf', '绩效（待定）'], ['price', '询价记录（待定）', prices.length]]"/>

        <section v-if="tab === 'certs'" data-c="资质">
          <Tbl inset flat :rows="s.certs" empty="还没传证照" :cols="[{ k: 'type', t: '类型' }, { k: 'until', t: '有效期至', f: c => c.until }, { k: 'left', t: '' }, { k: 'file', t: '文件' }, { k: 'note', t: '备注', f: c => c.note || '—' }, { k: 'act', t: '' }]">
            <template #left="{ r }"><Days :d="r.until" :warn="30"/></template>
            <template #file="{ r }"><Pics v-if="r.file" :model-value="[r.file]" readonly :s="36"/><span v-else class="dim">没传</span></template>
            <template #act="{ r }"><span class="row g4"><button class="btn ghost sm" @click="certEdit = r">换</button><button class="btn ghost sm danger" @click="delCert(r)">删除</button></span></template>
          </Tbl>
          <button class="btn" style="margin-top:12px" @click="certAdd = true"><Ico n="plus" :s="14"/>加一份证照</button>
        </section>

        <section v-if="tab === 'docs'" data-c="往来单据">
          <Tbl inset flat :rows="docs" link @row="d => go(d.to)" empty="和这家还没有往来单据" :cols="[
            { k: 'no', t: '单号', c: 'code' }, { k: 'k', t: '类型' }, { k: 'date', t: '日期', f: d => fd(d.date) },
            { k: 'qty', t: '数量', c: 'num' }, { k: 'amt', t: '金额', c: 'num', f: d => d.amt == null ? '—' : fm(d.amt) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St v-if="r.status" :s="r.status"/><span v-else class="dim">—</span></template>
          </Tbl>
        </section>

        <section v-if="tab === 'perf'" data-c="绩效">
          <p class="dim tip"><Phase p="待定"/> 供应商绩效（12.04）是待定模块。这里先用一期记下的入库、回货日期和次品数算给甲方看，确认要做再细化指标。</p>
          <div class="stats" style="border:1px solid var(--line);border-radius:10px;overflow:hidden">
            <div class="stat"><span>准时交货</span><b>{{ perf.onTime == null ? '—' : perf.onTime + '%' }}<small>{{ perf.n }} 次</small></b></div>
            <div class="stat"><span>逾期未交</span><b :class="{ red: perf.late }">{{ perf.late }}</b></div>
            <div class="stat"><span>回货次品率</span><b>{{ perf.defRate == null ? '—' : perf.defRate + '%' }}<small v-if="perf.ret">{{ perf.def }} / {{ perf.ret }}</small></b></div>
            <div class="stat"><span>异常</span><b>{{ perf.issues }}</b></div>
            <div class="stat"><span>往来金额</span><b>¥{{ Math.round(perf.amt).toLocaleString('zh-CN') }}<small>{{ perf.docs }} 单</small></b></div>
          </div>
        </section>

        <section v-if="tab === 'price'" data-c="询价记录">
          <p class="dim tip"><Phase p="待定"/> 询价比价（12.06）是待定模块。一期没有询价单，这里先列采购单上的成交价。</p>
          <Tbl inset flat :rows="prices" link @row="x => go('/purchases/' + x.no)" empty="还没从这家买过东西" :cols="[
            { k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'no', t: '采购单', c: 'code' },
            { k: 'qty', t: '数量', c: 'num', f: x => fq(x.qty) + ' ' + x.unit }, { k: 'price', t: '单价', c: 'num', f: x => fm(x.price) }]"/>
        </section>
      </div>
      <aside class="aside" data-c="供应商属性">
        <dl class="props">
          <dt>编码</dt><dd class="code">{{ s.code }}</dd>
          <dt>全称</dt><dd>{{ s.name }}</dd>
          <dt>擅长品类</dt><dd>{{ (s.goodAt || []).map(c => catOf(c).name).join('、') || '—' }}</dd>
          <dt>联系人</dt><dd>{{ s.contact || '—' }}</dd>
          <dt>电话</dt><dd>{{ s.phone || '—' }}</dd>
          <dt>微信</dt><dd>{{ s.wechat || '—' }}</dd>
          <dt>地址</dt><dd class="wrap">{{ s.addr || '—' }}</dd>
          <dt>结算</dt><dd>{{ s.settle }}{{ s.term ? ' · ' + s.term + ' 天' : '' }}</dd>
          <dt>发票</dt><dd>{{ s.invoice || '—' }} · 税率 {{ s.tax }}</dd>
          <dt>开户行</dt><dd>{{ s.bank || '—' }}</dd>
          <dt>账号</dt><dd class="code">{{ s.account || '—' }}</dd>
          <dt>负责采购员</dt><dd>{{ userName(s.buyer) }}</dd>
          <dt>准入</dt><dd>{{ s.admitted ? (s.admittedBy ? userName(s.admittedBy) + ' · ' : '') + s.admitted : '—' }}</dd>
          <dt v-if="factoryWh(s.code)">工厂仓</dt><dd v-if="factoryWh(s.code)"><a :href="'#/stock?wh=' + factoryWh(s.code).code">{{ factoryWh(s.code).name }}</a></dd>
          <dt>备注</dt><dd><NoteEdit :d="s"/></dd>
        </dl>
        <DocFiles :doc="s"/>
        <h4>操作记录</h4><Log :items="s.log"/>
      </aside>
    </div>
    <SupplierBlock v-if="box" :s="s" :mode="box.mode" @close="box = null"/>
    <CertForm v-if="certAdd || certEdit" :s="s" :c="certEdit" @close="certAdd = false; certEdit = null"/>
    <SupplierForm v-if="editing" :s="s" @close="editing = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'suppliers', t: (p) => '供应商 ' + (sup(p.code)?.short || p.code) })

comp('SupplierBlock', {
  props: { s: Object, mode: String },
  emits: ['close'],
  setup(p, { emit }) {
    const reason = ref('')
    const open = computed(() => (p.mode === '拉黑' ? openDocsOfSupplier(p.s.code) : []))
    function ok() {
      const r = run(() => (p.mode === '拉黑' ? blacklistSupplier(p.s, reason.value.trim()) : unblockSupplier(p.s, reason.value.trim())))
      if (r) { emit('close'); toast(p.mode === '拉黑' ? p.s.short + ' 已拉黑，新的采购单、加工单选不到它' : p.s.short + ' 已解除黑名单，现在是停用') }
    }
    return { reason, open, ok }
  },
  template: `<Modal :t="mode + ' ' + s.short" :w="480" @close="$emit('close')">
    <div v-if="open.length" class="banner" style="display:block"><div>有 {{ open.length }} 张单据还没完成，拉黑后可以继续入库、回货：</div><div class="code" style="margin-top:4px">{{ open.join('、') }}</div></div>
    <label class="f">原因<textarea class="inp" v-model="reason"></textarea></label>
    <template #foot><button class="btn" @click="$emit('close')">取消</button>
      <button class="btn pri" :style="mode === '拉黑' ? 'background:var(--red);border-color:var(--red)' : ''" @click="ok">{{ mode }}</button></template>
  </Modal>`,
})

comp('CertForm', {
  props: { s: Object, c: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive(p.c ? { type: p.c.type, until: p.c.until, note: p.c.note || '', files: p.c.file ? [p.c.file] : [] } : { type: '营业执照', until: '', note: '', files: [] })
    function ok() { if (run(() => saveCert(p.s, p.c, { type: f.type, until: f.until, note: f.note, file: f.files[0] }))) emit('close') }
    return { f, ok }
  },
  template: `<Modal :t="c ? '换证照：' + c.type : '加一份证照'" :w="460" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in db.dicts.证照类型" :key="x">{{ x }}</option></select></label>
      <label class="f">有效期至<input class="inp" type="date" v-model="f.until"></label>
      <div class="f full">照片或扫描件<Pics v-model="f.files" files :max="1" :s="64" tip="拍照、扫描件、PDF 都行；拖进来也可以"/></div>
      <label class="f full">备注<input class="inp" v-model="f.note" placeholder="如：年检后换新"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ c ? '保存' : '加上' }}</button></template>
  </Modal>`,
})

/* ---------- 客户 ---------- */
route('/customers', {
  setup() {
    const s = reactive({ q: '', st: '启用', add: false, imp: false })
    const rows = computed(() => db.customers.filter((c) => (s.st === '全部' || c.status === s.st) && matchQ(s.q, c.code, c.name, c.short, c.contact, c.phone)))
    const orders = (c) => db.orders.filter((o) => o.customer === c.code && !['已取消'].includes(o.status))
    const tabs = computed(() => ['启用', '停用', '全部'].map((k) => [k, k, k === '全部' ? db.customers.length : db.customers.filter((c) => c.status === k).length]))
    return { s, rows, orders, tabs }
  },
  template: `<div>
    <Head t="客户"><button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入</button><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建客户</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.st" :items="tabs"/><span class="sp"></span><Search v-model="s.q" ph="编码、名称、联系人"/></div>
    <div data-c="客户列表">
      <Tbl :rows="rows" link @row="c => go('/customers/' + c.code)" empty="没有符合条件的客户" :cols="[
        { k: 'code', t: '编码', c: 'code' }, { k: 'short', t: '简称' }, { k: 'contact', t: '联系人' }, { k: 'phone', t: '电话' },
        { k: 'settle', t: '结算', f: c => c.settle + (c.term ? ' ' + c.term + ' 天' : '') },
        { k: 'n', t: '订单', c: 'num', f: c => orders(c).length }, { k: 'amt', t: '订单金额', c: 'num', f: c => fm(sum(orders(c), orderAmt)) }, { k: 'status', t: '状态' }]">
        <template #short="{ r }"><span>{{ r.short }}</span> <span class="dim">{{ r.name }}</span></template>
        <template #status="{ r }"><St :s="r.status"/></template>
      </Tbl>
    </div>
    <CustomerForm v-if="s.add" @close="s.add = false"/>
    <ImportBox v-if="s.imp" kind="customers" @close="s.imp = false"/>
  </div>`,
}, { mod: 'customers', t: '客户' })

/* 新建、编辑客户共用（PRD §6.1.8 最小档案） */
comp('CustomerForm', {
  props: { c: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive(p.c ? Object.fromEntries(CUST_FIELDS.map(([k]) => [k, p.c[k] ?? ''])) : { name: '', short: '', contact: '', phone: '', addr: '', settle: '月结', term: 30, note: '' })
    function save() {
      if (p.c) { if (run(() => updateCustomer(p.c, f))) { emit('close'); toast(p.c.short + ' 已保存') } return }
      const c = run(() => createCustomer({ ...f })); if (c) { emit('close'); go('/customers/' + c.code); toast(c.short + ' 已建') }
    }
    return { f, save }
  },
  template: `<Modal :t="c ? '编辑客户 ' + c.code : '新建客户'" :w="560" @close="$emit('close')">
    <div class="form">
      <label class="f full">全称<input class="inp" v-model="f.name"></label>
      <label class="f">简称<input class="inp" v-model="f.short"></label>
      <label class="f">编码<input class="inp code" :value="c ? c.code : nextCode('KH', db.customers)" disabled :title="c ? '编码定了不改' : '自动编'"></label>
      <label class="f">联系人<input class="inp" v-model="f.contact"></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
      <label class="f full">地址<input class="inp" v-model="f.addr"></label>
      <label class="f">结算方式<select class="sel" v-model="f.settle"><option v-for="x in db.dicts.结算方式" :key="x">{{ x }}</option></select></label>
      <label class="f">账期（天）<input class="inp num" type="number" v-model.number="f.term"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ c ? '保存' : '建客户' }}</button></template>
  </Modal>`,
})

route('/customers/:code', {
  props: ['code'],
  setup(p) {
    const c = computed(() => cust(p.code))
    const tab = ref('orders')
    const orders = computed(() => db.orders.filter((o) => o.customer === p.code).slice().reverse())
    const stock = computed(() => customerStock(p.code))
    const editing = ref(false)
    async function toggle() {
      if (c.value.status === '启用' && !(await ask('停用 ' + c.value.short, '停用后新建订单选不到它；已有订单照常生产、发货。', '停用', true))) return
      c.value.status = c.value.status === '启用' ? '停用' : '启用'; act(c.value, c.value.status)
    }
    async function remove() { if (await ask('删除 ' + c.value.short, '没下过单、没有客供料的客户才能删；删了找不回来。', '删除', true) && run(() => removeCustomer(c.value))) { go('/customers'); toast('删了') } }
    return { c, tab, orders, stock, toggle, remove, editing }
  },
  template: `<div v-if="c">
    <Head :t="c.short" :back="['客户', '/customers']">
      <button class="btn" @click="editing = true">编辑</button>
      <a v-if="c.status === '启用'" class="btn pri" :href="'#/orders?new=1&customer=' + c.code"><Ico n="plus" :s="14"/>下订单</a>
      <More :items="[{ t: c.status === '启用' ? '停用' : '恢复启用', danger: c.status === '启用', fn: toggle }, { t: '删除', danger: true, fn: remove }]"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ c.short }}<St :s="c.status"/></div>
        <div class="sub"><span>{{ c.name }}</span></div>
        <Tabs v-model="tab" :items="[['orders', '订单', orders.length], ['stock', '客供料', stock.length], ['terms', '商务条款（待定）']]"/>
        <section v-if="tab === 'orders'" data-c="客户订单">
          <Tbl inset flat :rows="orders" link @row="o => go('/orders/' + o.no)" empty="这个客户还没下过订单" :cols="[
            { k: 'no', t: '订单号', c: 'code' }, { k: 'custNo', t: '客户单号', f: o => o.custNo || '—' }, { k: 'styles', t: '款式', f: o => orderStyles(o).join('、') },
            { k: 'qty', t: '数量', c: 'num', f: o => orderQty(o) }, { k: 'amt', t: '金额', c: 'num', f: o => fm(orderAmt(o)) }, { k: 'due', t: '客户交期', f: o => fd(o.due) }, { k: 'status', t: '状态' }]">
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </section>
        <section v-if="tab === 'terms'" data-c="商务条款">
          <p class="dim tip"><Phase p="待定"/> 价格协议、扣点、返点（12.03）是待定模块（PRD §6.1.8），以后在客户档案上加。结算方式、账期在「编辑」里改。</p>
          <div class="form" style="max-width:560px">
            <label class="f full">价格协议<input class="inp" v-model="c.terms.price"></label>
            <label class="f">扣点<input class="inp" v-model="c.terms.deduct"></label>
            <label class="f">返点<input class="inp" v-model="c.terms.rebate"></label>
          </div>
        </section>
        <section v-if="tab === 'stock'" data-c="客供料库存">
          <Tbl inset flat :rows="stock" empty="这个客户没有放在我们这里的料" :cols="[
            { k: 'w', t: '仓库', f: x => whName(x.w) }, { k: 'sku', t: '物料', f: x => skuName(x.sku), img: x => x.sku }, { k: 'code', t: 'SKU', c: 'code', f: x => x.sku },
            { k: 'q', t: '数量', c: 'num', f: x => fq(x.q) + ' ' + unitOf(x.sku) }]"/>
        </section>
      </div>
      <aside class="aside" data-c="客户属性">
        <dl class="props">
          <dt>编码</dt><dd class="code">{{ c.code }}</dd>
          <dt>全称</dt><dd>{{ c.name }}</dd>
          <dt>联系人</dt><dd>{{ c.contact || '—' }}</dd>
          <dt>电话</dt><dd>{{ c.phone || '—' }}</dd>
          <dt>地址</dt><dd class="wrap">{{ c.addr || '—' }}</dd>
          <dt>结算</dt><dd>{{ c.settle }}{{ c.term ? ' · ' + c.term + ' 天' : '' }}</dd>
          <dt>创建</dt><dd>{{ c.log && c.log[0] ? c.log[0].who + ' · ' + fd(c.log[0].at) : '—' }}</dd>
          <dt>备注</dt><dd><NoteEdit :d="c"/></dd>
        </dl>
        <DocFiles :doc="c"/>
        <h4>操作记录</h4><Log :items="c.log"/>
      </aside>
    </div>
    <CustomerForm v-if="editing" :c="c" @close="editing = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'customers', t: (p) => '客户 ' + (cust(p.code)?.short || p.code) })

/* ---------- 物料 ---------- */
route('/materials', {
  setup() {
    const s = reactive({ cat: '', st: '启用', q: '', add: false, imp: false })
    const groups = computed(() => MAT_GROUPS.map((g) => ({ g, cats: db.materialCategories.filter((c) => c.parent === g) })))
    const inCat = (m) => !s.cat || m.cat === s.cat || matCat(m.cat).parent === s.cat
    const rows = computed(() => db.materials.filter((m) => inCat(m) && (s.st === '全部' || m.status === s.st) && matchQ(s.q, m.code, m.name, m.supNo, m.comp, supName(m.supplier))))
    const count = (c) => db.materials.filter((m) => m.cat === c || matCat(m.cat).parent === c).length
    const cols = [
      { k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称', img: (m) => m.code }, { k: 'cat', t: '分类', f: (m) => matCat(m.cat).name }, { k: 'type', t: '类型' }, { k: 'unit', t: '单位', x: (m) => m.unit + (m.purUnit !== m.unit ? `（1 ${m.purUnit} = ${m.factor} ${m.unit}）` : '') },
      { k: 'spec', t: '门幅 · 克重', f: (m) => (m.width ? m.width + 'cm · ' + m.weight + 'g' : '—') }, { k: 'supplier', t: '默认供应商', f: (m) => supName(m.supplier) }, { k: 'supNo', t: '供应商货号', c: 'code', f: (m) => m.supNo || '—' },
      { k: 'price', t: '参考单价', c: 'num', f: (m) => fm(m.price) + '/' + m.purUnit }, { k: 'skus', t: 'SKU', c: 'num', f: (m) => m.skus.length },
      { k: 'stock', t: '库存', c: 'num', f: (m) => fq(matStock(m)) + ' ' + m.unit }, { k: 'status', t: '状态' }]
    return { s, groups, rows, count, cols }
  },
  template: `<div>
    <Head t="物料"><button class="btn" @click="s.imp = true"><Ico n="upload" :s="14"/>导入</button><ExportBtn :rows="rows" :cols="cols" name="物料"/><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建物料</button></Head>
    <div style="display:flex;min-height:calc(100vh - 48px)">
      <div style="width:184px;flex:none;border-right:1px solid var(--line);padding:10px 8px" data-c="物料分类">
        <a class="nav-i" :class="{ on: !s.cat }" @click="s.cat = ''"><span>全部</span><span class="n">{{ db.materials.length }}</span></a>
        <div v-for="g in groups" :key="g.g" class="grp">
          <b @click="s.cat = g.g">{{ g.g }}</b>
          <a v-for="c in g.cats" :key="c.code" class="nav-i" :class="{ on: s.cat === c.code }" :style="c.status === '停用' ? 'opacity:.55' : ''" @click="s.cat = c.code"><span class="code dim">{{ c.code }}</span><span>{{ c.name }}{{ c.status === '停用' ? '（停用）' : '' }}</span><span class="n">{{ count(c.code) || '' }}</span></a>
        </div>
        <a class="nav-i dim" style="margin-top:8px;font-size:12px" href="#/basics?tab=matcats"><Ico n="sliders" :s="13"/><span>管理分类</span></a>
      </div>
      <div style="flex:1;min-width:0">
        <div class="bar" data-c="筛选"><Seg v-model="s.st" :items="['启用', '停用', '全部']"/><span class="sp"></span><Search v-model="s.q"/></div>
        <div data-c="物料列表">
          <Tbl :rows="rows" link @row="m => go('/materials/' + m.code)" empty="这个分类下没有物料" :cols="cols">
            <template #unit="{ r }"><span v-if="r.purUnit === r.unit">{{ r.unit }}</span><span v-else>{{ r.unit }} <span class="dim">1 {{ r.purUnit }} = {{ fq(r.factor) }} {{ r.unit }}</span></span></template>
            <template #status="{ r }"><St :s="r.status"/></template>
          </Tbl>
        </div>
      </div>
    </div>
    <MaterialForm v-if="s.add" :cat="s.cat && s.cat.length === 2 ? s.cat : 'ML'" @close="s.add = false"/>
    <ImportBox v-if="s.imp" kind="materials" @close="s.imp = false"/>
  </div>`,
}, { mod: 'materials', t: '物料' })

/* 新建、编辑物料共用：传 m 就是编辑（编码不变；SKU 只改供应商色号、单价、安全库存、停用，加 SKU 用「批量加 SKU」）
 * 新建时选了颜色，每个颜色一行，可以顺手填规格、供应商色号、单价、安全库存 */
comp('MaterialForm', {
  props: { cat: String, m: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const pick = (o, ks) => Object.fromEntries(ks.map((k) => [k, o[k] ?? null]))
    const f = reactive(p.m
      ? { ...pick(p.m, MAT_FIELDS.map(([k]) => k)), skus: p.m.skus.map((k) => ({ code: k.code, color: k.color, spec: k.spec, supColor: k.supColor || '', price: k.price ?? null, safety: k.safety ?? null, status: k.status || '启用' })) }
      : { cat: liveMatCats().some((c) => c.code === p.cat) ? p.cat : liveMatCats()[0]?.code, name: '', type: '面料', unit: '米', purUnit: '米', factor: 1, width: null, weight: null, comp: '', texture: '', supplier: '', supNo: '', price: null, moq: null, pack: 1, lead: 7, year: +clock.today.slice(0, 4), season: '', note: '', colors: [], images: [] })
    const rows = reactive({}) // 新建：颜色 → { spec, supColor, price, safety }
    const skuRows = () => f.colors.map((c) => ({ color: c, ...rows[c] }))
    const code = computed(() => (p.m ? p.m.code : nextMatCode(f.cat)))
    const lock = computed(() => (p.m ? matLockReason(p.m) : ''))
    const sups = computed(() => db.suppliers.filter((s) => s.status === '合作中' || s.code === f.supplier))
    const cats = computed(() => db.materialCategories.filter((c) => c.status !== '停用' || c.code === f.cat))
    const toggle = (c) => { const i = f.colors.indexOf(c); if (i >= 0) f.colors.splice(i, 1); else { f.colors.push(c); rows[c] ||= { spec: '', supColor: '', price: null, safety: null } } }
    watch(() => f.unit, (u, old) => { if (!lock.value && f.purUnit === old) { f.purUnit = u; f.factor = 1 } })
    const colors = computed(() => liveColors())
    const preview = computed(() => (f.colors.length ? skuRows().map((x) => [code.value, x.color, specCode(x.spec)].filter(Boolean).join('-')) : [code.value]))
    async function save() {
      if (p.m) { if (run(() => updateMaterial(p.m, f))) { emit('close'); toast(p.m.code + ' 已保存') } return }
      const body = { ...f, colors: undefined, skus: skuRows(), images: [...f.images] }
      const d = dupMaterial(body)
      if (d && !(await ask('可能重复', '可能和 ' + d.code + ' ' + d.name + (d.supNo ? '（' + supName(d.supplier) + ' ' + d.supNo + '）' : '') + ' 重复，确定新建？', '仍然新建'))) return
      const m = run(() => createMaterial(body))
      if (m) { emit('close'); go('/materials/' + m.code); toast(m.code + ' 已建，生成 ' + m.skus.length + ' 个 SKU') }
    }
    return { f, code, lock, sups, cats, toggle, save, rows, colors, preview }
  },
  template: `<Modal :t="m ? '编辑物料 ' + m.code : '新建物料'" :w="720" @close="$emit('close')">
    <div class="form" style="grid-template-columns:1fr 1fr 1fr">
      <label class="f">分类<select class="sel" v-model="f.cat"><option v-for="c in cats" :key="c.code" :value="c.code">{{ c.parent }} / {{ c.name }}</option></select></label>
      <label class="f">编码<input class="inp code" :value="code" disabled :title="m ? '编码定了不改' : '按分类自动编'"></label>
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in db.dicts.物料类型" :key="x">{{ x }}</option></select></label>
      <label class="f full">名称<input class="inp" v-model="f.name" placeholder="如：220g 斜纹棉"></label>
      <label class="f">库存单位<select class="sel" v-model="f.unit" :disabled="!!lock"><option v-for="x in db.dicts.单位" :key="x">{{ x }}</option></select></label>
      <label class="f">采购单位<select class="sel" v-model="f.purUnit" :disabled="!!lock"><option v-for="x in db.dicts.单位" :key="x">{{ x }}</option></select></label>
      <label class="f">1 {{ f.purUnit }} = ? {{ f.unit }}<input class="inp num" type="number" v-model.number="f.factor" :disabled="!!lock || f.purUnit === f.unit"></label>
      <p v-if="lock" class="full dim" style="margin:-4px 0 0;font-size:12px">{{ lock }}，单位和换算系数不能改了</p>
      <label class="f">门幅 cm<input class="inp num" type="number" v-model.number="f.width"></label>
      <label class="f">克重 g/㎡<input class="inp num" type="number" v-model.number="f.weight"></label>
      <label class="f">成分<input class="inp" v-model="f.comp" placeholder="如 100% 棉"></label>
      <label class="f">组织 / 纹理<input class="inp" v-model="f.texture" placeholder="如 斜纹、平纹"></label>
      <label class="f">年份<input class="inp num" type="number" v-model.number="f.year"></label>
      <label class="f">季节<select class="sel" v-model="f.season"><option value="">—</option><option v-for="x in liveSeasons(f.season)" :key="x.code" :value="x.code">{{ x.name }}</option></select></label>
      <label class="f">默认供应商<select class="sel" v-model="f.supplier"><option value="">不填</option><option v-for="s in sups" :key="s.code" :value="s.code">{{ s.short }}</option></select></label>
      <label class="f">供应商货号<input class="inp" v-model="f.supNo"></label>
      <label class="f">参考单价（每{{ f.purUnit }}，含税）<input class="inp num" type="number" v-model.number="f.price"></label>
      <label class="f">起订量（{{ f.purUnit }}）<input class="inp num" type="number" v-model.number="f.moq"></label>
      <label class="f">最小包装（{{ f.purUnit }}）<input class="inp num" type="number" v-model.number="f.pack"></label>
      <label class="f">采购周期（天）<input class="inp num" type="number" v-model.number="f.lead"></label>
      <label class="f full">备注<input class="inp" v-model="f.note"></label>
      <template v-if="!m">
        <div class="f full">颜色<span class="dim" style="font-weight:400">（一个颜色一个 SKU；不选 = 1 个不带颜色的 SKU，如透明胶袋）</span><div class="chips"><button v-for="c in colors" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
        <div v-if="f.colors.length" class="f full" data-c="新建 SKU">
          <table class="tbl inset flat"><thead><tr><th>颜色</th><th>规格</th><th>供应商色号</th><th class="num">单价（不填 = 参考单价）</th><th class="num">安全库存（{{ f.unit }}）</th></tr></thead>
            <tbody><tr v-for="c in f.colors" :key="c"><td><Sw :c="c"/></td><td><input class="inp" style="height:26px;width:90px" v-model="rows[c].spec" placeholder="如 15cm"></td>
              <td><input class="inp" style="height:26px;width:100px" v-model="rows[c].supColor"></td><td class="num"><input class="inp num" style="height:26px;width:90px" type="number" v-model.number="rows[c].price" :placeholder="f.price ?? ''"></td>
              <td class="num"><input class="inp num" style="height:26px;width:90px" type="number" v-model.number="rows[c].safety"></td></tr></tbody></table></div>
        <div class="f full" data-c="物料图片">物料图片<Pics v-model="f.images" :colors="f.colors" tip="布样、色卡、辅料实物都可以拍；给图标上颜色，按颜色出图"/></div>
      </template>
      <div v-else class="f full" data-c="编辑 SKU">SKU<span class="dim" style="font-weight:400">（加颜色、规格用详情页的「批量加 SKU」；用过的 SKU 不能删，只能停用）</span>
        <table class="tbl inset flat" style="margin-top:4px"><thead><tr><th>SKU</th><th>颜色</th><th>规格</th><th>供应商色号</th><th class="num">单价（每{{ f.purUnit }}）</th><th class="num">安全库存（{{ f.unit }}）</th><th>状态</th></tr></thead>
          <tbody><tr v-for="k in f.skus" :key="k.code"><td class="code">{{ k.code }}</td><td><Sw v-if="k.color" :c="k.color"/><span v-else class="dim">—</span></td><td>{{ k.spec || '—' }}</td>
            <td><input class="inp" style="height:26px;width:100px" v-model="k.supColor"></td><td class="num"><input class="inp num" style="height:26px;width:90px" type="number" v-model.number="k.price" :placeholder="f.price ?? ''"></td>
            <td class="num"><input class="inp num" style="height:26px;width:90px" type="number" v-model.number="k.safety"></td>
            <td><select class="sel" style="height:26px" v-model="k.status"><option>启用</option><option>停用</option></select></td></tr></tbody></table></div>
    </div>
    <template #foot><span v-if="!m" class="dim code">{{ preview.length }} 个 SKU：{{ preview.join('、') }}</span><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ m ? '保存' : '建物料' }}</button></template>
  </Modal>`,
})

route('/materials/:code', {
  props: ['code'],
  setup(p) {
    const m = computed(() => mat(p.code))
    const dlg = ref('')
    const used = computed(() => matUsedBy(p.code))
    async function toggle() {
      if (m.value.status === '启用') { if (await ask('停用 ' + m.value.name, '停用后它的 SKU 不能再选进新的资料包、采购单；已有库存照常出库。', '停用', true)) setMaterialStatus(m.value, '停用') }
      else setMaterialStatus(m.value, '启用')
    }
    async function remove() { if (await ask('删除 ' + m.value.code, '没被采购单、出入库单、资料包用过，也没有库存的物料才能删；删了找不回来。', '删除', true) && run(() => removeMaterial(m.value))) { go('/materials'); toast('删了') } }
    async function skuToggle(k) {
      if (k.status !== '停用' && !(await ask('停用 ' + k.code, '停用后这个 SKU 不能再选进新的资料包、采购单；已有库存照常出库。', '停用', true))) return
      toggleMatSku(m.value, k)
    }
    async function skuDel(k) { if (await ask('删除 ' + k.code, '没用过的 SKU 才能删；用过的只能停用。', '删除', true)) run(() => removeMatSku(m.value, k)) }
    return { m, dlg, used, toggle, remove, skuToggle, skuDel }
  },
  template: `<div v-if="m">
    <Head :t="m.code" :back="['物料', '/materials']">
      <button class="btn" @click="dlg = 'edit'">编辑</button>
      <button class="btn" @click="dlg = 'sku'"><Ico n="plus" :s="14"/>批量加 SKU</button>
      <More :items="[{ t: m.status === '启用' ? '停用' : '恢复启用', danger: m.status === '启用', fn: toggle }, { t: '删除', danger: true, fn: remove }]"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ m.name }}<St :s="m.status"/></div>
        <div class="sub"><span>{{ matCat(m.cat).parent }} / {{ matCat(m.cat).name }}</span><span>{{ m.type }}</span><span v-if="m.comp">{{ m.comp }}</span><span v-if="m.texture">{{ m.texture }}</span></div>
        <section data-c="物料SKU">
          <Tbl inset flat :rows="m.skus" :cols="[
            { k: 'code', t: 'SKU', c: 'code', img: k => k.code }, { k: 'color', t: '颜色' }, { k: 'spec', t: '规格', f: k => k.spec || '—' }, { k: 'supColor', t: '供应商色号', f: k => k.supColor || '—' },
            { k: 'price', t: '单价', c: 'num', f: k => k.price != null ? fm(k.price) : '同参考价' }, { k: 'safety', t: '安全库存', c: 'num', f: k => k.safety ? fq(k.safety) : '—' },
            { k: 'wl', t: '物料仓', c: 'num', f: k => fq(skuStock(k.code, '物料仓')) }, { k: 'gc', t: '工厂仓', c: 'num', f: k => fq(skuStock(k.code, '工厂仓')) },
            { k: 'img', t: '图片' }, { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
            <template #color="{ r }"><Sw v-if="r.color" :c="r.color"/><span v-else class="dim">—</span></template>
            <template #img="{ r }"><Pics :model-value="r.images || []" @update:model-value="v => setSkuImages(m, r, v)" :max="1" :s="32"/></template>
            <template #status="{ r }"><St :s="r.status || '启用'"/></template>
            <template #act="{ r }"><span class="row g4"><button class="btn ghost sm" @click="skuToggle(r)">{{ r.status === '停用' ? '启用' : '停用' }}</button><button v-if="!skuUsedBy(r.code)" class="btn ghost sm danger" @click="skuDel(r)">删除</button></span></template>
          </Tbl>
        </section>
        <div class="sec" data-c="用到它的款式">
          <h3>用到它的款式</h3>
          <Tbl inset flat :rows="used" link @row="x => go('/styles/' + x.style)" empty="还没有生效的资料包用到它" :cols="[
            { k: 'style', t: '款号', c: 'code' }, { k: 'name', t: '名称', f: x => styleName(x.style) }, { k: 'ver', t: '资料包', f: x => 'V' + x.ver }, { k: 'part', t: '部位' }, { k: 'src', t: '来源' }]"/>
        </div>
      </div>
      <aside class="aside" data-c="物料属性">
        <Gallery :code="m.code" :colors="[...new Set(m.skus.map(k => k.color).filter(Boolean))]" :owner="m" edit/>
        <dl class="props">
          <dt>分类</dt><dd>{{ matCat(m.cat).parent }} / {{ matCat(m.cat).name }} <span class="code dim">{{ m.cat }}</span></dd>
          <dt>类型</dt><dd>{{ m.type }}</dd>
          <dt>库存单位</dt><dd>{{ m.unit }}</dd>
          <dt>采购单位</dt><dd>{{ m.purUnit }}<template v-if="m.purUnit !== m.unit"> · 1 {{ m.purUnit }} = {{ fq(m.factor) }} {{ m.unit }}</template></dd>
          <dt>门幅</dt><dd>{{ m.width ? m.width + ' cm' : '—' }}</dd>
          <dt>克重</dt><dd>{{ m.weight ? m.weight + ' g/㎡' : '—' }}</dd>
          <dt>成分</dt><dd>{{ m.comp || '—' }}</dd>
          <dt>组织 / 纹理</dt><dd>{{ m.texture || '—' }}</dd>
          <dt>默认供应商</dt><dd><a v-if="m.supplier" :href="'#/suppliers/' + m.supplier">{{ supName(m.supplier) }}</a><span v-else>—</span></dd>
          <dt>供应商货号</dt><dd>{{ m.supNo || '—' }}</dd>
          <dt>参考单价</dt><dd>{{ fm(m.price) }} / {{ m.purUnit }}</dd>
          <dt>起订量</dt><dd>{{ m.moq ? fq(m.moq) + ' ' + m.purUnit : '—' }}</dd>
          <dt>最小包装</dt><dd>{{ fq(m.pack) }} {{ m.purUnit }}</dd>
          <dt>采购周期</dt><dd>{{ m.lead }} 天</dd>
          <dt>年份 · 季节</dt><dd>{{ [m.year, m.season && seasonName(m.season)].filter(Boolean).join(' · ') || '—' }}</dd>
          <dt>库存</dt><dd>{{ fq(matStock(m)) }} {{ m.unit }}</dd>
          <dt>创建</dt><dd>{{ m.log && m.log[0] ? m.log[0].who + ' · ' + fd(m.log[0].at) : '—' }}</dd>
          <dt>备注</dt><dd><NoteEdit :d="m"/></dd>
        </dl>
        <DocFiles :doc="m"/>
        <h4>操作记录</h4><Log :items="m.log"/>
      </aside>
    </div>
    <MaterialForm v-if="dlg === 'edit'" :m="m" @close="dlg = ''"/>
    <SkuAdd v-if="dlg === 'sku'" :m="m" @close="dlg = ''"/>
  </div><NotFound v-else/>`,
}, { mod: 'materials', t: (p) => '物料 ' + p.code })

comp('SkuAdd', {
  props: { m: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ colors: [], specs: '' })
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    const specs = computed(() => f.specs.split(/[\s,，、]+/).filter(Boolean))
    const preview = computed(() => (f.colors.length ? f.colors : [null]).flatMap((c) => (specs.value.length ? specs.value : ['']).map((sp) => skuCodeOf(p.m, c, sp))).filter((x) => x !== p.m.code || !p.m.skus.length))
    function ok() {
      const made = run(() => addMaterialSkus(p.m, f.colors, specs.value))
      if (made) { emit('close'); toast('加了 ' + made.length + ' 个 SKU') }
    }
    return { f, toggle, preview, ok }
  },
  template: `<Modal t="批量加 SKU" :w="560" @close="$emit('close')">
    <div class="form">
      <div class="f full">颜色<span class="dim" style="font-weight:400">（可以不选：只有规格的，如不同长度的拉链）</span><div class="chips"><button v-for="c in liveColors()" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
      <label class="f full">规格<input class="inp" v-model="f.specs" placeholder="可以填多个，空格隔开：15cm 18cm 60cm"></label>
    </div>
    <template #foot><span class="dim code" style="font-size:12px">{{ preview.length ? preview.length + ' 个：' + preview.join('、') : '选颜色或填规格' }}</span><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">加上</button></template>
  </Modal>`,
})

/* ---------- 基础 ---------- */
route('/basics', {
  setup() {
    const tab = ref(nav.query.tab || 'colors')
    watch(() => nav.query.tab, (t) => { if (t && nav.path === '/basics') tab.value = t })
    const nc = reactive({ code: '', name: '', family: '黑白灰', hex: '#888888' })
    // 色系是字典（PRD §6.1.3）；老颜色上的色系不在字典里也留着
    const families = computed(() => [...new Set([...db.dicts.色系, ...db.colors.map((c) => c.family).filter(Boolean)])])
    const ce = reactive({ c: null, name: '', family: '', hex: '' })
    const openColor = (c) => Object.assign(ce, { c, name: c.name, family: c.family || '', hex: c.hex || '#888888' })
    function doEditColor() { if (run(() => editColor(ce.c, ce))) { toast('改好了'); ce.c = null } }
    const sg = reactive({ g: null, v: '' })
    function doRenameGroup() { if (run(() => renameSizeGroup(sg.g, sg.v))) sg.g = null }
    async function delGroup(g) { if (await ask('删除尺码组「' + g.name + '」', '没有款式用它。删除后新建款式选不到它。', '删除', true)) run(() => removeSizeGroup(g)) }
    const ns = reactive({ code: '', name: '' })
    function saveSeason() { if (run(() => addSeason(ns))) { toast('加了季节 ' + ns.name); Object.assign(ns, { code: '', name: '' }) } }
    const newSize = reactive({})
    const ng = reactive({ name: '', sizes: '', base: '' })
    const whType = ref('全部')
    const catGroups = computed(() => [...new Set(db.categories.map((c) => c.path))].map((p) => ({ p, cats: db.categories.filter((c) => c.path === p) })))
    const matGroups = computed(() => MAT_GROUPS.map((g) => ({ g, cats: db.materialCategories.filter((c) => c.parent === g) })))
    const ncat = reactive({ path: '女装 / 外套', code: '', name: '' })
    const nmc = reactive({ parent: '面料', code: '', name: '' })
    const paths = computed(() => [...new Set(db.categories.map((c) => c.path))])
    const ren = reactive({ c: null, v: '' })
    const rename = (c) => { ren.c = c; ren.v = c.name }
    function doRename(list, sib, where) { if (run(() => renameCat(list, ren.c, ren.v, sib, where))) ren.c = null }
    async function del(list, c, used, what) { if (await ask('删除「' + c.name + '」', '没有' + what + '用它，删除后编码 ' + c.code + ' 可以再用。', '删除', true)) run(() => removeCat(list, c, used, what)) }
    function saveColor() { if (run(() => addColor(nc))) { toast('加了颜色 ' + nc.name); Object.assign(nc, { code: '', name: '' }) } }
    function addSize(g) { if (run(() => appendSize(g, newSize[g.id]))) newSize[g.id] = '' }
    function saveGroup() { if (run(() => addSizeGroup(ng))) { toast('加了尺码组 ' + ng.name); Object.assign(ng, { name: '', sizes: '', base: '' }) } }
    function saveCat() { if (run(() => addCategory(ncat))) { toast('加了品类 ' + ncat.name); Object.assign(ncat, { code: '', name: '' }) } }
    function saveMatCat() { if (run(() => addMatCategory(nmc))) { toast('加了物料分类 ' + nmc.name); Object.assign(nmc, { code: '', name: '' }) } }
    const whs = computed(() => db.warehouses.filter((w) => whType.value === '全部' || w.type === whType.value))
    const edit = reactive({ kind: '', row: null })
    const nd = reactive({})
    function addDict(k) { if (run(() => addDictValue(k, nd[k]))) nd[k] = '' }
    async function delDict(k, v) { if (await ask('删除「' + dictText(v) + '」', '以后新建的单据选不到它；已经用了它的单据不受影响。', '删除', true)) run(() => removeDictValue(k, v)) }
    const dictText = (v) => (Array.isArray(v) ? v[0] + ' ' + v[1] + ' 天' : v)
    return { tab, nc, families, ce, openColor, doEditColor, sg, doRenameGroup, delGroup, ns, saveSeason, newSize, ng, whType, catGroups, matGroups, ncat, nmc, paths, ren, rename, doRename, del, saveColor, addSize, saveGroup, saveCat, saveMatCat, whs, edit, nd, addDict, delDict, dictText }
  },
  template: `<div>
    <Head t="基础"/>
    <div class="page">
      <Tabs v-model="tab" :items="[['colors', '颜色', db.colors.length], ['sizes', '尺码组', db.sizeGroups.length], ['cats', '品类', db.categories.length], ['seasons', '季节', db.seasons.length], ['matcats', '物料分类', db.materialCategories.length], ['whs', '仓库', db.warehouses.length], ['companies', '主体公司', db.companies.length], ['dicts', '字典']]"/>

      <section v-if="tab === 'colors'" data-c="颜色">
        <p class="dim tip">颜色编码会进 SKU 码（27CF001-KQ-M 里的 KQ），定了不改；不用的颜色停用，新建款式、物料就选不到它。</p>
        <Tbl inset flat :rows="db.colors" :cols="[{ k: 'sw', t: '' }, { k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'family', t: '色系' }, { k: 'used', t: '用在', c: 'num', f: c => colorUsed(c.code) ? colorUsed(c.code) + ' 个款 / 物料' : '—' }, { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
          <template #sw="{ r }"><input v-if="ce.c === r" type="color" v-model="ce.hex" title="色块" style="width:26px;height:26px;padding:0;border:1px solid var(--line-strong);border-radius:6px;background:none"><Sw v-else :c="r.code" :n="false"/></template>
          <template #name="{ r }"><input v-if="ce.c === r" class="inp" style="height:26px;width:110px" v-model="ce.name" @keydown.enter="doEditColor"><span v-else>{{ r.name }}</span></template>
          <template #family="{ r }"><select v-if="ce.c === r" class="sel" style="height:26px" v-model="ce.family"><option value="">—</option><option v-for="x in families" :key="x">{{ x }}</option></select><span v-else>{{ r.family || '—' }}</span></template>
          <template #status="{ r }"><St :s="r.status || '启用'"/></template>
          <template #act="{ r }"><span class="row g4" style="justify-content:flex-end">
            <template v-if="ce.c === r"><button class="btn sm pri" @click="doEditColor">保存</button><button class="btn ghost sm" @click="ce.c = null">取消</button></template>
            <template v-else><button class="btn ghost sm" @click="openColor(r)">改</button><button class="btn ghost sm" @click="toggleCat(r)">{{ r.status === '停用' ? '启用' : '停用' }}</button><button v-if="!colorUsed(r.code)" class="btn ghost sm danger" @click="del(db.colors, r, 0, '款式或物料')">删除</button></template>
          </span></template>
        </Tbl>
        <div class="row" style="margin-top:14px" data-c="加颜色">
          <input type="color" v-model="nc.hex" style="width:30px;height:30px;padding:0;border:1px solid var(--line-strong);border-radius:6px;background:none">
          <input class="inp code" style="width:80px" v-model="nc.code" placeholder="编码">
          <input class="inp" style="width:120px" v-model="nc.name" placeholder="名称">
          <select class="sel" v-model="nc.family"><option v-for="x in families" :key="x">{{ x }}</option></select>
          <button class="btn" @click="saveColor"><Ico n="plus" :s="14"/>加颜色</button>
        </div>
      </section>

      <section v-if="tab === 'sizes'" data-c="尺码组">
        <p class="dim tip">已经有款式用的尺码组，只能在末尾追加尺码，不能删、不能改名、不能调顺序、不能换基码（PRD §6.1.3）；不用了就停用，新建款式选不到它。基码是核价取用量的那个码。</p>
        <div class="list">
          <div v-for="g in db.sizeGroups" :key="g.id" class="li" :class="{ off: g.status === '停用' }" style="height:auto;padding:12px 4px;gap:14px">
            <span v-if="sg.g === g" class="row g4" style="width:180px"><input class="inp" style="height:26px;width:130px" v-model="sg.v" @keydown.enter="doRenameGroup"><button class="btn sm" @click="doRenameGroup">好</button></span>
            <b v-else style="width:96px;font-weight:500">{{ g.name }}</b>
            <St v-if="g.status === '停用'" s="停用"/>
            <span class="chips"><span v-for="z in g.sizes" :key="z" class="chip" style="cursor:default">{{ z }}<Ico v-if="!sizeGroupUsed(g.id)" n="x" :s="11" style="cursor:pointer" @click="run(() => removeSize(g, z))"/></span></span>
            <span class="dim row g4" style="font-size:12px">基码
              <select v-if="!sizeGroupUsed(g.id)" class="sel" style="height:24px" :value="baseSizeOf(g.id)" @change="run(() => setBaseSize(g, $event.target.value))"><option v-for="z in g.sizes" :key="z">{{ z }}</option></select>
              <template v-else>{{ baseSizeOf(g.id) }} · {{ sizeGroupUsed(g.id) }} 个款在用</template></span>
            <span style="flex:1"></span>
            <input class="inp" style="width:72px" v-model="newSize[g.id]" @keydown.enter="addSize(g)">
            <button class="btn sm" @click="addSize(g)">{{ sizeGroupUsed(g.id) ? '追加尺码' : '加尺码' }}</button>
            <span class="row g4">
              <button v-if="!sizeGroupUsed(g.id)" class="btn ghost sm" @click="sg.g = g; sg.v = g.name">改名</button>
              <button class="btn ghost sm" @click="toggleCat(g)">{{ g.status === '停用' ? '启用' : '停用' }}</button>
              <button v-if="!sizeGroupUsed(g.id)" class="btn ghost sm danger" @click="delGroup(g)">删除</button></span>
          </div>
        </div>
        <div class="row" style="margin-top:14px" data-c="加尺码组">
          <input class="inp" style="width:140px" v-model="ng.name" placeholder="名称，如 童装身高码">
          <input class="inp" style="width:220px" v-model="ng.sizes" placeholder="尺码，空格隔开：110 120 130 140">
          <input class="inp" style="width:80px" v-model="ng.base" placeholder="基码">
          <button class="btn" @click="saveGroup"><Ico n="plus" :s="14"/>加尺码组</button>
        </div>
      </section>

      <section v-if="tab === 'cats'" data-c="品类">
        <p class="dim tip">三级：大类 / 中类 / 品类。品类编码 1 位，会进款号（27CF001 里的 F）；已经有款用的品类不能删、编码不能改，只能停用（PRD §6.1.2）。</p>
        <div v-for="g in catGroups" :key="g.p" style="margin-bottom:18px">
          <div class="dim" style="font-size:12px;margin-bottom:6px">{{ g.p }}</div>
          <div class="list"><div v-for="c in g.cats" :key="c.code" class="li" :class="{ off: c.status === '停用' }">
            <span class="code dim" style="width:24px">{{ c.code }}</span>
            <template v-if="ren.c === c"><input class="inp" style="height:26px;width:140px" v-model="ren.v" @keydown.enter="doRename(db.categories, x => x.path)"><button class="btn sm" @click="doRename(db.categories, x => x.path)">好</button></template>
            <span v-else>{{ c.name }}</span><St v-if="c.status === '停用'" s="停用"/>
            <span class="dim" style="font-size:12px">{{ catUsed(c.code) ? catUsed(c.code) + ' 个款' : '' }}</span>
            <span class="row g4" style="margin-left:auto"><button class="btn ghost sm" @click="rename(c)">改名</button><button class="btn ghost sm" @click="toggleCat(c)">{{ c.status === '停用' ? '启用' : '停用' }}</button><button v-if="!catUsed(c.code)" class="btn ghost sm danger" @click="del(db.categories, c, 0, '款')">删除</button></span>
          </div></div>
        </div>
        <div class="row" data-c="加品类"><input class="inp" style="width:170px" v-model="ncat.path" list="cat-paths" placeholder="上级：女装 / 外套"><datalist id="cat-paths"><option v-for="x in paths" :key="x" :value="x"/></datalist>
          <input class="inp code" style="width:64px" v-model="ncat.code" placeholder="编码"><input class="inp" style="width:120px" v-model="ncat.name" placeholder="名称"><button class="btn" @click="saveCat"><Ico n="plus" :s="14"/>加品类</button></div>
      </section>

      <section v-if="tab === 'seasons'" data-c="季节">
        <p class="dim tip">季节编码 1 位，会进款号（27CF001 里的 C）；已经有款式、物料用的季节不能删、编码不能改，只能停用（PRD §6.1.2）。年份是 4 位数字，不用维护。</p>
        <div class="list"><div v-for="c in db.seasons" :key="c.code" class="li" :class="{ off: c.status === '停用' }">
          <span class="code dim" style="width:24px">{{ c.code }}</span>
          <template v-if="ren.c === c"><input class="inp" style="height:26px;width:140px" v-model="ren.v" @keydown.enter="doRename(db.seasons, () => 0, '')"><button class="btn sm" @click="doRename(db.seasons, () => 0, '')">好</button></template>
          <span v-else>{{ c.name }}</span><St v-if="c.status === '停用'" s="停用"/>
          <span class="dim" style="font-size:12px">{{ seasonUsed(c.code) ? seasonUsed(c.code) + ' 个款式、样衣或物料' : '' }}</span>
          <span class="row g4" style="margin-left:auto"><button class="btn ghost sm" @click="rename(c)">改名</button><button class="btn ghost sm" @click="toggleCat(c)">{{ c.status === '停用' ? '启用' : '停用' }}</button><button v-if="!seasonUsed(c.code)" class="btn ghost sm danger" @click="del(db.seasons, c, 0, '款式、样衣或物料')">删除</button></span>
        </div></div>
        <div class="row" style="margin-top:14px" data-c="加季节"><input class="inp code" style="width:64px" v-model="ns.code" placeholder="编码"><input class="inp" style="width:120px" v-model="ns.name" placeholder="名称，如 春夏"><button class="btn" @click="saveSeason"><Ico n="plus" :s="14"/>加季节</button></div>
      </section>

      <section v-if="tab === 'matcats'" data-c="物料分类">
        <p class="dim tip">两级：面料 / 辅料 / 包材下面挂小类。小类编码 2 位字母，是物料编码的前缀（ML0012 里的 ML）；有物料的分类不能删、编码不能改，只能停用（PRD §6.1.4）。</p>
        <div v-for="g in matGroups" :key="g.g" style="margin-bottom:18px">
          <div class="dim" style="font-size:12px;margin-bottom:6px">{{ g.g }}</div>
          <div class="list"><div v-for="c in g.cats" :key="c.code" class="li" :class="{ off: c.status === '停用' }">
            <span class="code dim" style="width:28px">{{ c.code }}</span>
            <template v-if="ren.c === c"><input class="inp" style="height:26px;width:140px" v-model="ren.v" @keydown.enter="doRename(db.materialCategories, x => x.parent)"><button class="btn sm" @click="doRename(db.materialCategories, x => x.parent)">好</button></template>
            <span v-else>{{ c.name }}</span><St v-if="c.status === '停用'" s="停用"/>
            <a class="dim" style="font-size:12px" :href="'#/materials'">{{ matCatUsed(c.code) ? matCatUsed(c.code) + ' 个物料' : '' }}</a>
            <span class="row g4" style="margin-left:auto"><button class="btn ghost sm" @click="rename(c)">改名</button><button class="btn ghost sm" @click="toggleCat(c)">{{ c.status === '停用' ? '启用' : '停用' }}</button><button v-if="!matCatUsed(c.code)" class="btn ghost sm danger" @click="del(db.materialCategories, c, 0, '物料')">删除</button></span>
          </div></div>
        </div>
        <div class="row" data-c="加物料分类"><select class="sel" v-model="nmc.parent"><option v-for="g in MAT_GROUPS" :key="g">{{ g }}</option></select>
          <input class="inp code" style="width:64px" v-model="nmc.code" placeholder="编码"><input class="inp" style="width:120px" v-model="nmc.name" placeholder="名称，如 牛仔"><button class="btn" @click="saveMatCat"><Ico n="plus" :s="14"/>加分类</button></div>
      </section>

      <section v-if="tab === 'whs'" data-c="仓库">
        <div class="row" style="margin-bottom:10px"><Seg v-model="whType" :items="['全部', '物料仓', '工厂仓', '成品仓']"/><span class="sp"></span><button class="btn" @click="edit.kind = 'wh'; edit.row = null"><Ico n="plus" :s="14"/>加仓库</button></div>
        <Tbl inset flat :rows="whs" :cols="[{ k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'type', t: '类型' },
          { k: 'factory', t: '绑定加工厂', f: w => w.factory ? supName(w.factory) : '—' }, { k: 'addr', t: '地址', f: w => w.addr || '—' }, { k: 'keeper', t: '负责人', f: w => w.keeper ? userName(w.keeper) : '—' }, { k: 'phone', t: '电话', f: w => w.phone || '—' },
          { k: 'stock', t: '在库', c: 'num', f: w => whStock(w.code) ? fq(whStock(w.code)) : '—' }, { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
          <template #status="{ r }"><St :s="r.status"/></template>
          <template #act="{ r }"><span class="row g4"><button class="btn ghost sm" @click="edit.kind = 'wh'; edit.row = r">编辑</button><button class="btn ghost sm" @click="run(() => toggleWarehouse(r))">{{ r.status === '启用' ? '停用' : '启用' }}</button></span></template>
        </Tbl>
        <p class="dim tip" style="margin-top:10px">新建「成衣厂」「加工厂」类的供应商时，自动建一个同名工厂仓。仓库里有库存不能停用。</p>
      </section>

      <section v-if="tab === 'companies'" data-c="主体公司">
        <div class="row" style="margin-bottom:10px"><span class="dim tip" style="margin:0">每张单据记是哪个主体做的；打印采购单、加工单时用它的名称、地址、电话做抬头。至少要有一个启用的主体。</span><span class="sp"></span><button class="btn" @click="edit.kind = 'company'; edit.row = null"><Ico n="plus" :s="14"/>加主体</button></div>
        <Tbl inset flat :rows="db.companies" :cols="[{ k: 'code', t: '编码', c: 'code' }, { k: 'name', t: '名称' }, { k: 'short', t: '简称' }, { k: 'credit', t: '统一社会信用代码', c: 'code', f: c => c.credit || '—' }, { k: 'addr', t: '地址', f: c => c.addr || '—' }, { k: 'phone', t: '电话', f: c => c.phone || '—' }, { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
          <template #status="{ r }"><St :s="r.status"/></template>
          <template #act="{ r }"><span class="row g4"><button class="btn ghost sm" @click="edit.kind = 'company'; edit.row = r">编辑</button><button class="btn ghost sm" @click="run(() => toggleCompany(r))">{{ r.status === '启用' ? '停用' : '启用' }}</button></span></template>
        </Tbl>
      </section>

      <section v-if="tab === 'dicts'" data-c="字典">
        <p class="dim tip">这些下拉框的选项甲方可以自己增删；删掉的值，以后新建的单据选不到，已经用了它的单据不受影响。</p>
        <div class="list">
          <div v-for="(vals, k) in db.dicts" :key="k" class="li" style="height:auto;padding:10px 4px;align-items:flex-start">
            <b style="width:96px;font-weight:500;flex:none">{{ k }}</b>
            <span class="chips" style="flex:1"><span v-for="v in vals" :key="dictText(v)" class="pill">{{ dictText(v) }}<Ico n="x" :s="11" style="cursor:pointer;margin-left:4px" @click="delDict(k, v)"/></span></span>
            <input class="inp" style="width:110px;height:26px" v-model="nd[k]" placeholder="加一个值" @keydown.enter="addDict(k)">
          </div>
        </div>
      </section>
    </div>
    <WarehouseEdit v-if="edit.kind === 'wh'" :w="edit.row" @close="edit.kind = ''"/>
    <CompanyEdit v-if="edit.kind === 'company'" :c="edit.row" @close="edit.kind = ''"/>
  </div>`,
}, { mod: 'basics', t: '基础' })

comp('WarehouseEdit', {
  props: { w: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive(p.w ? { ...p.w } : { code: '', name: '', type: '物料仓', factory: '', addr: '', keeper: '', phone: '' })
    const factories = computed(() => db.suppliers.filter(isFactory))
    function ok() { if (run(() => saveWarehouse(p.w, f))) { emit('close'); toast((p.w ? '已保存 ' : '加了仓库 ') + f.name) } }
    return { f, factories, ok }
  },
  template: `<Modal :t="w ? '编辑仓库 ' + w.code : '加仓库'" :w="520" @close="$emit('close')">
    <div class="form">
      <label class="f">编码<input class="inp code" v-model="f.code" placeholder="如 WL02"></label>
      <label class="f">名称<input class="inp" v-model="f.name"></label>
      <label class="f">类型<select class="sel" v-model="f.type"><option v-for="x in ['物料仓', '成品仓', '工厂仓']" :key="x">{{ x }}</option></select></label>
      <label class="f" v-if="f.type === '工厂仓'">绑定加工厂<select class="sel" v-model="f.factory"><option value="">选一个</option><option v-for="s in factories" :key="s.code" :value="s.code">{{ s.short }}</option></select></label>
      <label class="f full">地址<input class="inp" v-model="f.addr"></label>
      <label class="f">负责人<select class="sel" v-model="f.keeper"><option value="">—</option><option v-for="u in db.users" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ w ? '保存' : '加上' }}</button></template>
  </Modal>`,
})
comp('CompanyEdit', {
  props: { c: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive(p.c ? { ...p.c } : { code: '', name: '', short: '', credit: '', addr: '', phone: '' })
    function ok() { if (run(() => saveCompany(p.c, f))) { emit('close'); toast((p.c ? '已保存 ' : '加了主体 ') + f.short) } }
    return { f, ok }
  },
  template: `<Modal :t="c ? '编辑主体 ' + c.code : '加主体公司'" :w="560" @close="$emit('close')">
    <div class="form">
      <label class="f">编码<input class="inp code" v-model="f.code" placeholder="2-6 位"></label>
      <label class="f">简称<input class="inp" v-model="f.short" placeholder="列表和单据上显示"></label>
      <label class="f full">名称<input class="inp" v-model="f.name" placeholder="公司全称"></label>
      <label class="f full">统一社会信用代码<input class="inp code" v-model="f.credit"></label>
      <label class="f full">地址<input class="inp" v-model="f.addr" placeholder="打印采购单、加工单时用作抬头"></label>
      <label class="f">电话<input class="inp" v-model="f.phone"></label>
    </div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="ok">{{ c ? '保存' : '加上' }}</button></template>
  </Modal>`,
})

/* ---------- 设置 ---------- */
route('/settings', {
  setup() {
    const tab = ref('users')
    const who = ref('')
    const logs = computed(() => allLogs().filter((x) => !who.value || x.who === who.value))
    const permTone = (v) => ({ 看: '', 改: 'blue', 审: 'green' }[v])
    const addRule = () => db.approvalRules.push({ doc: '采购单', over: 0, role: '管理层' })
    const JST = ['回货登记后推送聚水潭生成成品入库单，成品库存以聚水潭为准', '双向同步：入库推送聚水潭，出库和库存回写本系统', '暂不对接，两边各自录单']
    const FIN = ['金蝶', '用友', '系统内闭环，不对接']
    return { tab, who, logs, permTone, addRule, JST, FIN }
  },
  template: `<div>
    <Head t="设置"/>
    <div class="page">
      <Tabs v-model="tab" :items="[['users', '用户', db.users.length], ['perms', '角色权限'], ['audit', '审批'], ['notify', '通知'], ['integ', '集成'], ['log', '操作日志']]"/>

      <section v-if="tab === 'users'" data-c="用户">
        <p class="dim tip">登录名、密码、启用停用在管理后台（已有）；这里是业务上要用的：部门、岗位、角色、默认主体（新单据的主体公司默认取它，PRD §5.7）。</p>
        <Tbl inset flat :rows="db.users" :cols="[{ k: 'name', t: '姓名' }, { k: 'dept', t: '部门' }, { k: 'post', t: '岗位' }, { k: 'role', t: '角色' }, { k: 'scope', t: '数据范围', f: u => roleOf(u.role).scope }, { k: 'company', t: '默认主体' }]">
          <template #dept="{ r }"><input class="inp" style="height:26px;width:96px" v-model="r.dept"></template>
          <template #post="{ r }"><input class="inp" style="height:26px;width:96px" v-model="r.post"></template>
          <template #role="{ r }"><select class="sel" style="height:26px" v-model="r.role"><option v-for="x in ROLES" :key="x">{{ x }}</option></select></template>
          <template #company="{ r }"><CompanyPick style="height:26px" :model-value="r.company" @update:model-value="v => r.company = v"/></template>
        </Tbl>
      </section>

      <section v-if="tab === 'perms'" data-c="角色权限">
        <p class="dim tip">点格子切换：— 看不到、看 = 能打开、改 = 新建编辑提交、审 = 审核反审核结案（PRD §3.1）。数据范围按角色设：「本人」只看制单人、跟单员、采购员、业务员是自己的单据；「全部」的人列表上有「我的 / 全部」切换（§3.2）。</p>
        <table class="tbl inset flat">
          <thead><tr><th>模块</th><th v-for="r in ROLES" :key="r" style="text-align:center">{{ r }}</th></tr></thead>
          <tbody><tr data-c="数据范围"><td>数据范围</td><td v-for="r in ROLES" :key="r" style="text-align:center"><button class="pill" :class="roleOf(r).scope === '全部' ? 'blue' : ''" style="border:none;cursor:pointer" @click="roleOf(r).scope = roleOf(r).scope === '全部' ? '本人' : '全部'">{{ roleOf(r).scope }}</button></td></tr>
            <tr v-for="row in db.perms" :key="row.m"><td>{{ row.m }}</td>
            <td v-for="(v, i) in row.v" :key="i" style="text-align:center"><button class="pill" :class="permTone(v)" style="border:none;cursor:pointer;min-width:30px;justify-content:center" @click="cyclePerm(row, i)">{{ v }}</button></td></tr></tbody>
        </table>
      </section>

      <section v-if="tab === 'audit'" data-c="审批">
        <Tbl inset flat :rows="AUDITS" :cols="[{ k: 't', t: '单据' }, { k: 'p', t: '权限点', c: 'code' }, { k: 'roles', t: '谁能审', f: a => auditRoles(a.m).join('、') || '—' }, { k: 'lv', t: '级数', f: () => '单级' }, { k: 'n', t: '待审', c: 'num', f: a => a.n() || '—' }]"/>
        <div class="sec" data-c="多级审批">
          <h3>多级审批<span class="pill blue">二期</span><span class="acts"><button class="btn sm" @click="addRule"><Ico n="plus" :s="12"/>加一条</button></span></h3>
          <table class="tbl inset flat"><tbody>
            <tr v-for="(r, i) in db.approvalRules" :key="i">
              <td style="width:140px"><select class="sel" style="height:26px" v-model="r.doc"><option v-for="a in AUDITS" :key="a.t">{{ a.t }}</option></select></td>
              <td>金额超过 <input class="inp num" style="width:110px;height:26px" type="number" v-model.number="r.over"> 元，再加一级</td>
              <td style="width:140px"><select class="sel" style="height:26px" v-model="r.role"><option v-for="x in ROLES" :key="x">{{ x }}</option></select></td>
              <td class="num" style="width:40px"><button class="btn ghost icon sm" @click="db.approvalRules.splice(i, 1)"><Ico n="trash" :s="13"/></button></td>
            </tr>
            <tr v-if="!db.approvalRules.length"><td colspan="4" class="empty">没有多级规则，所有单据都是单级审核</td></tr>
          </tbody></table>
        </div>
      </section>

      <section v-if="tab === 'notify'" data-c="通知">
        <div class="list">
          <label v-for="(on, k) in db.notify" :key="k" class="li chk"><input type="checkbox" v-model="db.notify[k]" :disabled="k === '站内待办'">{{ k }}<span v-if="k !== '站内待办'" class="pill blue" style="margin-left:auto">二期</span></label>
        </div>
        <div class="h2">推送哪些事</div>
        <div class="list">
          <label v-for="(on, k) in db.notifyEvents" :key="k" class="li chk"><input type="checkbox" v-model="db.notifyEvents[k]">{{ k }}</label>
        </div>
      </section>

      <section v-if="tab === 'integ'" data-c="集成">
        <div class="list">
          <div class="li" style="height:auto;padding:14px 4px;display:block">
            <div class="row"><b style="font-weight:600">聚水潭</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill amber">待 Q01</span></div>
            <div class="col" style="margin-top:10px;gap:6px">
              <label v-for="x in JST" :key="x" class="chk"><input type="radio" name="jst" :value="x" v-model="db.integration.jst">{{ x }}</label>
            </div>
          </div>
          <div class="li" style="height:auto;padding:14px 4px;display:block">
            <div class="row"><b style="font-weight:600">财务软件</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill amber">待 Q04</span></div>
            <div class="row" style="margin-top:10px;gap:16px"><label v-for="x in FIN" :key="x" class="chk"><input type="radio" name="fin" :value="x" v-model="db.integration.fin">{{ x }}</label></div>
          </div>
          <div class="li" style="height:auto;padding:14px 4px"><b style="font-weight:600">电子签章</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill">三期</span></div>
          <div class="li" style="height:auto;padding:14px 4px"><b style="font-weight:600">开放 API</b><St s="未开始" t="未连接"/><span style="flex:1"></span><span class="pill">三期</span></div>
        </div>
      </section>

      <section v-if="tab === 'log'" data-c="操作日志">
        <div class="row" style="margin-bottom:8px"><select class="sel" v-model="who"><option value="">所有人</option><option v-for="u in db.users" :key="u.id" :value="u.name">{{ u.name }}</option></select><span class="dim">{{ logs.length }} 条</span></div>
        <Tbl inset flat :rows="logs" link @row="x => go(x.to)" empty="这个人还没有操作" :cols="[{ k: 'at', t: '日期', f: x => fd(x.at) }, { k: 'who', t: '人' }, { k: 'k', t: '对象' }, { k: 'id', t: '单号', c: 'code' }, { k: 'text', t: '做了什么' }]">
          <template #text="{ r }"><span class="wrap">{{ r.text }}</span></template>
        </Tbl>
      </section>
    </div>
  </div>`,
}, { mod: 'settings', t: '设置' })

/* ---------- Excel 导入（PRD §10）：客户、供应商、物料 + SKU。有一行不对整批不导，列出全部错误 ----------
 * 编码可以不填（自动编），领猫里的老编码也可以照填；先建填了编码的，再建自动编的，免得自动编号撞上后面行填的老编码 */
const codeFirst = (list) => [...list.filter((x) => x.code), ...list.filter((x) => !x.code)]
const splitList = (v) => String(v || '').split(/[,，、;；\s]+/).filter(Boolean)
// 编码查重：和系统里已有的、和文件里前面的行
function checkCodes(rows, bad, exists, col = '编码') {
  const seen = new Map()
  for (const r of rows) {
    r.code = String(r.code || '').trim().toUpperCase()
    if (!r.code) continue
    if (exists(r.code)) bad(r, col, `${r.code} 系统里已经有了`)
    else if (seen.has(r.code)) bad(r, col, `和第 ${seen.get(r.code)} 行重复`)
    seen.set(r.code, r._r)
  }
}
IMPORTS.customers = {
  title: '导入客户', name: '客户',
  note: '一行一个客户。',
  cols: [
    { k: 'code', t: '编码', tip: '选填；不填自动编（KH + 4 位）。填了不能和已有的重复', ex: 'KH0101', text: true, w: 10 },
    { k: 'name', t: '全称', req: true, tip: '公司全称，不能和已有客户重名', ex: '华南某服饰有限公司', w: 24 },
    { k: 'short', t: '简称', req: true, tip: '列表、单据上显示', ex: '华南某服饰' },
    { k: 'contact', t: '联系人', ex: '黄小姐' },
    { k: 'phone', t: '电话', text: true, ex: '13900000000' },
    { k: 'addr', t: '地址', ex: '深圳市某区某路 8 号', w: 24 },
    { k: 'settle', t: '结算方式', opts: () => db.dicts.结算方式, tip: '不填 = 月结', ex: '月结' },
    { k: 'term', t: '账期（天）', num: true, tip: '不填 = 30', ex: 30, w: 10 },
    { k: 'status', t: '状态', opts: () => ['启用', '停用'], tip: '不填 = 启用', ex: '启用', w: 8 },
    { k: 'note', t: '备注', w: 20 },
  ],
  example: () => [
    { name: '华南某服饰有限公司', short: '华南某服饰', contact: '黄小姐', phone: '13900000000', addr: '深圳市某区某路 8 号', settle: '月结', term: 30, status: '启用' },
    { code: 'KH0102', name: '西南某品牌管理有限公司', short: '西南某品牌', contact: '杨先生', phone: '13700000000', addr: '成都市某区某街 3 号', settle: '预付', term: 0, status: '启用', note: '老客户，领猫里的编码照用' },
  ],
  check(rows, bad) {
    checkCodes(rows, bad, cust)
    const seen = new Map()
    for (const r of rows) {
      if (db.customers.some((c) => c.name === r.name)) bad(r, '全称', `已经有客户叫「${r.name}」`)
      else if (seen.has(r.name)) bad(r, '全称', `和第 ${seen.get(r.name)} 行重复`)
      seen.set(r.name, r._r)
    }
    return rows
  },
  save(rows) {
    const made = codeFirst(rows).map((r) => createCustomer({ imported: true, code: r.code, name: r.name, short: r.short, contact: r.contact, phone: r.phone, addr: r.addr, settle: r.settle || '月结', term: r.term ?? 30, status: r.status || '启用', note: r.note }))
    return { text: `导入了 ${made.length} 个客户：${made.map((c) => c.code).join('、')}`, to: '/customers' }
  },
}
IMPORTS.suppliers = {
  title: '导入供应商', name: '供应商',
  note: '一行一家供应商。成衣厂、加工厂导进来会自动建一个工厂仓。证照在供应商详情页一份份传。',
  cols: [
    { k: 'code', t: '编码', tip: '选填；不填自动编（GYS + 4 位）', ex: 'GYS0101', text: true, w: 10 },
    { k: 'name', t: '全称', req: true, tip: '不能和已有供应商重名', ex: '绍兴某纺织有限公司', w: 24 },
    { k: 'short', t: '简称', req: true, ex: '某纺织' },
    { k: 'cats', t: '分类', req: true, tip: '可以填多个，用顿号或逗号隔开：' + '面料商、辅料商、成衣厂、加工厂、外协、包材商、其他', ex: '面料商', w: 16 },
    { k: 'level', t: '等级', opts: () => ['A', 'B', 'C', 'D'], tip: '不填 = B', ex: 'B', w: 6 },
    { k: 'status', t: '状态', opts: () => ['合作中', '待准入', '停用'], tip: '不填 = 待准入；领猫里在合作的填「合作中」。黑名单导进来以后在详情页拉黑（要写原因）', ex: '合作中', w: 8 },
    { k: 'goodAt', t: '擅长品类', tip: '成衣厂、加工厂填；品类名称或编码，多个用顿号隔开', ex: '风衣、夹克', w: 14 },
    { k: 'contact', t: '联系人', ex: '吴总' },
    { k: 'phone', t: '电话', text: true, ex: '13500000000' },
    { k: 'wechat', t: '微信', text: true },
    { k: 'addr', t: '地址', w: 24 },
    { k: 'settle', t: '结算方式', opts: () => db.dicts.结算方式, tip: '不填 = 月结', ex: '月结' },
    { k: 'term', t: '账期（天）', num: true, tip: '不填 = 30', ex: 30, w: 10 },
    { k: 'tax', t: '税率', opts: () => db.dicts.税率, tip: '不填 = 13%', ex: '13%', w: 8, text: true },
    { k: 'invoice', t: '发票类型', opts: () => db.dicts.发票类型, ex: '增值税专用发票' },
    { k: 'bank', t: '开户行', w: 18 },
    { k: 'account', t: '账号', text: true, w: 22 },
    { k: 'buyer', t: '负责采购员', tip: '系统里的用户姓名', ex: '林舟' },
    { k: 'note', t: '备注', w: 20 },
  ],
  example: () => [
    { name: '绍兴某纺织有限公司', short: '某纺织', cats: '面料商', level: 'A', status: '合作中', contact: '吴总', phone: '13500000000', settle: '月结', term: 30, tax: '13%', invoice: '增值税专用发票', buyer: '林舟' },
    { code: 'GYS0102', name: '湖州某制衣有限公司', short: '某制衣', cats: '成衣厂', level: 'B', status: '合作中', goodAt: '风衣、夹克', contact: '孙厂长', phone: '13800000000', settle: '月结', term: 45, tax: '6%', invoice: '增值税专用发票', buyer: '陈晓' },
  ],
  check(rows, bad) {
    checkCodes(rows, bad, sup)
    const seen = new Map()
    for (const r of rows) {
      if (db.suppliers.some((x) => x.name === r.name)) bad(r, '全称', `已经有供应商叫「${r.name}」`)
      else if (seen.has(r.name)) bad(r, '全称', `和第 ${seen.get(r.name)} 行重复`)
      seen.set(r.name, r._r)
      r.cats = splitList(r.cats)
      const badCat = r.cats.filter((c) => !db.dicts.供应商分类.includes(c))
      if (badCat.length) bad(r, '分类', `没有分类「${badCat.join('、')}」，可选：${db.dicts.供应商分类.join('、')}`)
      r.goodAt = splitList(r.goodAt).map((x) => db.categories.find((c) => c.code === x.toUpperCase() || c.name === x)?.code || '?' + x)
      const badGood = r.goodAt.filter((c) => c.startsWith('?'))
      if (badGood.length) bad(r, '擅长品类', `没有品类「${badGood.map((x) => x.slice(1)).join('、')}」`)
      const u = r.buyer ? db.users.find((x) => x.name === r.buyer) : null
      if (r.buyer && !u) bad(r, '负责采购员', `系统里没有用户「${r.buyer}」`)
      r.buyer = u?.id || ''
    }
    return rows
  },
  save(rows) {
    const made = codeFirst(rows).map((r) => createSupplier({ imported: true, code: r.code, name: r.name, short: r.short, cats: r.cats, goodAt: r.goodAt, level: r.level || 'B', status: r.status || '待准入',
      contact: r.contact, phone: r.phone, wechat: r.wechat, addr: r.addr, settle: r.settle || '月结', term: r.term ?? 30, tax: r.tax || '13%', invoice: r.invoice, bank: r.bank, account: r.account, buyer: r.buyer, note: r.note }))
    const whs = made.filter((s) => factoryWh(s.code)).length
    return { text: `导入了 ${made.length} 家供应商${whs ? '，自动建了 ' + whs + ' 个工厂仓' : ''}`, to: '/suppliers' }
  },
}
// 物料 + SKU：一行一个 SKU，物料信息重复填；同一个物料编码（没填编码时：同分类 + 同名称）的行合成一个物料
const MAT_IMPORT_FIELDS = ['cat', 'name', 'type', 'unit', 'purUnit', 'factor', 'width', 'weight', 'comp', 'texture', 'supplier', 'supNo', 'price', 'moq', 'pack', 'lead', 'note']
IMPORTS.materials = {
  title: '导入物料', name: '物料',
  note: '一行一个 SKU，物料信息每行重复填（不填的列沿用这个物料第一行的）；同一个物料编码的行合成一个物料，没填编码的按「分类 + 名称」合。',
  cols: [
    { k: 'code', t: '物料编码', tip: '选填；不填按分类自动编（如 ML0013）', ex: '', text: true, w: 10 },
    { k: 'cat', t: '分类编码', req: true, tip: '物料分类的 2 位编码，见「基础 › 物料分类」', ex: 'ML', text: true, w: 8 },
    { k: 'name', t: '名称', req: true, ex: '240g 斜纹棉', w: 18 },
    { k: 'type', t: '类型', req: true, opts: () => db.dicts.物料类型, ex: '面料', w: 8 },
    { k: 'unit', t: '库存单位', req: true, opts: () => db.dicts.单位, ex: '米', w: 8 },
    { k: 'purUnit', t: '采购单位', opts: () => db.dicts.单位, tip: '不填 = 库存单位', ex: '米', w: 8 },
    { k: 'factor', t: '换算系数', num: true, tip: '1 采购单位 = 几个库存单位；不填 = 1', ex: 1, w: 8 },
    { k: 'width', t: '门幅', num: true, tip: '厘米', ex: 148, w: 6 },
    { k: 'weight', t: '克重', num: true, tip: 'g/㎡', ex: 240, w: 6 },
    { k: 'comp', t: '成分', ex: '100% 棉' },
    { k: 'texture', t: '组织', ex: '斜纹', w: 8 },
    { k: 'supplier', t: '默认供应商', tip: '供应商编码或简称，要是合作中的', ex: '恒丰面料' },
    { k: 'supNo', t: '供应商货号', text: true, ex: 'HF-T240' },
    { k: 'price', t: '参考单价', num: true, tip: '按采购单位，含税', ex: 19.8, w: 8 },
    { k: 'moq', t: '起订量', num: true, w: 8 },
    { k: 'pack', t: '最小包装', num: true, tip: '不填 = 1', w: 8 },
    { k: 'lead', t: '采购周期', num: true, tip: '天；不填 = 7', ex: 15, w: 8 },
    { k: 'color', t: '颜色', tip: '颜色名称或编码；没有颜色的物料空着', ex: '卡其', w: 8 },
    { k: 'spec', t: '规格', tip: '如 15cm、18L；填了生成规格码', text: true, w: 8 },
    { k: 'supColor', t: '供应商色号', text: true, ex: 'C-1032' },
    { k: 'skuPrice', t: 'SKU 单价', num: true, tip: '不填 = 参考单价', w: 8 },
    { k: 'safety', t: '安全库存', num: true, tip: '库存单位', w: 8 },
    { k: 'note', t: '备注', w: 20 },
  ],
  example: () => [
    { cat: 'ML', name: '240g 斜纹棉', type: '面料', unit: '米', purUnit: '米', factor: 1, width: 148, weight: 240, comp: '100% 棉', texture: '斜纹', supplier: '恒丰面料', supNo: 'HF-T240', price: 19.8, lead: 15, color: '卡其', supColor: 'C-1032' },
    { cat: 'ML', name: '240g 斜纹棉', type: '面料', unit: '米', color: '藏青', supColor: 'C-2210' },
    { cat: 'FL', name: '3# 尼龙拉链', type: '辅料', unit: '条', supplier: '鸿达辅料', price: 0.8, lead: 7, color: '黑色', spec: '15cm' },
    { cat: 'FL', name: '3# 尼龙拉链', type: '辅料', unit: '条', color: '黑色', spec: '18cm' },
  ],
  check(rows, bad) {
    const groups = new Map()
    for (const r of rows) {
      r.code = String(r.code || '').trim().toUpperCase()
      r.cat = String(r.cat).trim().toUpperCase()
      const k = r.code || r.cat + '|' + r.name.trim()
      if (!groups.has(k)) groups.set(k, [])
      groups.get(k).push(r)
    }
    const plans = []
    for (const [, rs] of groups) {
      const r0 = rs[0]
      // 同一个物料的行，物料信息要么空着、要么和第一行一样
      for (const r of rs.slice(1)) for (const f of MAT_IMPORT_FIELDS) {
        if (r[f] !== '' && r[f] != null && String(r[f]) !== String(r0[f] ?? '')) bad(r, IMPORTS.materials.cols.find((c) => c.k === f).t, `和第 ${r0._r} 行（同一个物料）不一样：「${r0[f] ?? ''}」→「${r[f]}」`)
      }
      if (r0.code && mat(r0.code)) bad(r0, '物料编码', `${r0.code} 系统里已经有了`)
      const cat = db.materialCategories.find((c) => c.code === r0.cat)
      if (!cat) bad(r0, '分类编码', `没有物料分类 ${r0.cat}`)
      else if (cat.status === '停用') bad(r0, '分类编码', `${r0.cat} ${cat.name} 已停用`)
      const same = db.materials.find((m) => m.cat === r0.cat && m.name === r0.name.trim())
      if (!r0.code && same) bad(r0, '名称', `${cat?.name || r0.cat} 下已经有「${r0.name}」（${same.code}）。确实是两个物料就把名称写出区别，或者填物料编码`)
      let supplier = ''
      if (r0.supplier) {
        const s = db.suppliers.find((x) => x.code === String(r0.supplier).toUpperCase() || x.short === r0.supplier)
        if (!s) bad(r0, '默认供应商', `没有供应商「${r0.supplier}」`)
        else if (s.status !== '合作中') bad(r0, '默认供应商', `${s.short} 现在是「${s.status}」，默认供应商只能选合作中的`)
        else supplier = s.code
      }
      const skus = [], seen = new Map()
      for (const r of rs) {
        let color = ''
        if (r.color) {
          const c = db.colors.find((x) => x.code === String(r.color).toUpperCase() || x.name === r.color)
          if (!c) { bad(r, '颜色', `没有颜色「${r.color}」，先在「基础 › 颜色」里加`); continue }
          if (c.status === '停用') { bad(r, '颜色', `${c.name} 已停用`); continue }
          color = c.code
        }
        const key = color + '|' + specCode(r.spec)
        if (seen.has(key)) { bad(r, '颜色', `和第 ${seen.get(key)} 行是同一个 SKU（颜色 + 规格一样）`); continue }
        seen.set(key, r._r)
        skus.push({ color, spec: r.spec, supColor: r.supColor, price: r.skuPrice ?? null, safety: r.safety ?? null })
      }
      const g = (f) => rs.find((r) => r[f] !== '' && r[f] != null)?.[f]
      plans.push({ code: r0.code, cat: r0.cat, name: r0.name.trim(), type: r0.type, unit: r0.unit, purUnit: g('purUnit') || r0.unit, factor: g('factor') || 1, width: g('width') ?? null, weight: g('weight') ?? null,
        comp: g('comp') || '', texture: g('texture') || '', supplier, supNo: g('supNo') || '', price: g('price') ?? null, moq: g('moq') ?? null, pack: g('pack') || 1, lead: g('lead') || 7, note: g('note') || '', skus })
    }
    return plans
  },
  save(plans) {
    const made = codeFirst(plans).map((f) => createMaterial({ imported: true, ...f, year: null, season: '', images: [] }))
    return { text: `导入了 ${made.length} 个物料、${sum(made, (m) => m.skus.length)} 个 SKU：${made.map((m) => m.code).join('、')}`, to: made.length === 1 ? '/materials/' + made[0].code : '/materials' }
  },
}
