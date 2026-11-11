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
// 新单据的主体公司：当前用户的默认主体（PRD §5.7），没设或停用了就取第一个启用的
const myCompany = () => { const c = me()?.company; return db.companies.find((x) => x.code === c && x.status === '启用')?.code || db.companies.find((x) => x.status === '启用')?.code }
// 单据上的业务员、跟单员、采购员默认填自己；自己不是这个角色就填这个角色的第一个人
const roleUser = (role) => (me()?.role === role ? db.me : db.users.find((u) => u.role === role)?.id)
/* 改档案、改草稿：fields = [[字段, 名字, 显示函数?]]，只改 f 里给了的字段；返回改了什么（"交期 11-20 → 11-25"），调用方没改动时报错、有改动时 act(doc, '改：' + …) */
const showVal = (v, fmt) => (v == null || v === '' || (Array.isArray(v) && !v.length) ? '空' : fmt ? fmt(v) : Array.isArray(v) ? v.join('、') : v)
const sameVal = (a, b) => JSON.stringify(a ?? '') === JSON.stringify(b ?? '')
function applyFields(doc, f, fields) {
  const ch = fields.filter(([k]) => k in f && !sameVal(f[k], doc[k])).map(([k, t, fmt]) => `${t} ${showVal(doc[k], fmt)} → ${showVal(f[k], fmt)}`)
  fields.forEach(([k]) => { if (k in f) doc[k] = Array.isArray(f[k]) ? [...f[k]] : f[k] })
  return ch
}
// 只有草稿能删（PRD §5.1），别的状态只能作废或结案
function removeDraft(list, d) {
  if (d.status !== '草稿') fail(`${d.no || d.code} 是「${d.status}」，只有草稿能删，其他状态只能作废或结案`)
  list.splice(list.indexOf(d), 1)
}
// 图片（款式、物料、样衣）和附件（单据）：整组换掉，操作记录里写加了 / 删了什么
const PHOTO_TYPES = ['面料质量', '做工质量', '工艺问题'] // 这几类异常要带照片
function setImages(o, list) {
  const n0 = (o.images || []).length
  o.images = list
  act(o, `图片 ${n0} → ${list.length} 张${list.length ? '，主图 ' + list[0].name : ''}`)
}
function setFiles(o, list) {
  const old = o.files || [], ids = new Set(list.map((x) => x.id)), was = new Set(old.map((x) => x.id))
  const added = list.filter((x) => !was.has(x.id)), gone = old.filter((x) => !ids.has(x.id))
  o.files = list
  if (added.length) act(o, '加附件 ' + added.map((x) => x.name).join('、'))
  if (gone.length) act(o, '删附件 ' + gone.map((x) => x.name).join('、'))
}

/* ---------- 状态流转表：动作 → [从哪些状态, 到什么状态] ---------- */
const FLOWS = {
  order: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已审核'], 反审核: ['已审核', '草稿'], 结案: [['已审核', '生产中', '已完成'], '已结案'], 取消结案: ['已结案', '生产中'], 作废: [['草稿', '待审核'], '已取消'] },
  change: { 驳回: ['待审核', '已驳回'], 重新提交: ['已驳回', '待审核'], 审核: ['待审核', '已审核'] },
  mrp: { 确认: ['草稿', '已确认'], 关闭: [['草稿', '已确认'], '已关闭'] },
  purchase: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已审核'], 反审核: ['已审核', '草稿'], 结案: [['已审核', '部分到货', '已到齐'], '已结案'], 取消结案: ['已结案', '已审核'], 作废: [['草稿', '待审核'], '已作废'] },
  stock: { 审核: ['草稿', '已审核'], 反审核: ['已审核', '草稿'], 作废: ['草稿', '已作废'] },
  production: { 提交: ['草稿', '待审核'], 撤回: ['待审核', '草稿'], 驳回: ['待审核', '草稿'], 审核: ['待审核', '已下单'], 反审核: ['已下单', '草稿'], 结案: [['已下单', '生产中', '部分回货', '已完成'], '已结案'], 取消结案: ['已结案', '生产中'], 作废: [['草稿', '待审核'], '已作废'] },
  techpack: { 生效: ['草稿', '已生效'] },
  // 供应商准入要审批（甲方 11-11 反馈 1d）：申请 → 有准入权限的另一个人通过或驳回
  supplier: { 申请准入: ['待准入', '准入审批中'], 通过: ['准入审批中', '合作中'], 驳回: ['准入审批中', '待准入'], 停用: ['合作中', '停用'], 启用: ['停用', '合作中'], 拉黑: [['待准入', '准入审批中', '合作中', '停用'], '黑名单'], 解除黑名单: ['黑名单', '停用'] },
  style: { 启用: ['草稿', '启用'], 停用: ['启用', '停用'], 恢复: ['停用', '启用'] },
}
const can = (kind, doc, a) => { const f = FLOWS[kind]?.[a]; return !!f && [].concat(f[0]).includes(doc.status) }
const actionsOf = (kind, doc) => Object.keys(FLOWS[kind]).filter((a) => can(kind, doc, a))
function move(kind, doc, a, note) {
  if (!can(kind, doc, a)) fail(`${doc.no || doc.code || ''} 现在是「${doc.status}」，不能${a}`)
  doc.status = FLOWS[kind][a][1]
  act(doc, a + (note ? '：' + note : ''))
}
/* 单据公共动作（PRD §5.1）：驳回要写原因；撤回只有制单人；结案记下从哪个状态结的，取消结案回到那里 */
const creatorOf = (d) => d.log?.[0]?.who
const rejectDoc = (kind) => (d, reason) => { if (!reason?.trim()) fail('要写驳回原因，制单人照着改'); move(kind, d, '驳回', reason.trim()) }
const withdrawDoc = (kind) => (d) => { if (creatorOf(d) && creatorOf(d) !== userName(db.me)) fail(`只有制单人 ${creatorOf(d)} 能撤回`); move(kind, d, '撤回') }
const closeDoc = (kind) => (d, note) => { const from = d.status; move(kind, d, '结案', note); d.closedFrom = from }
const reopenDoc = (kind, back) => (d) => {
  move(kind, d, '取消结案')
  d.status = back(d)
  delete d.closedFrom
}
const docRules = (kind, back) => ({ 驳回: rejectDoc(kind), 撤回: withdrawDoc(kind), 结案: closeDoc(kind), 取消结案: reopenDoc(kind, back) })
/* 带额外规则的动作；没列在这里的直接 move */
const RULES = {
  order: { ...docRules('order', (o) => (o.closedFrom === '已完成' ? '已完成' : prodsOf(o).length ? '生产中' : '已审核')), 提交: submitOrder, 反审核: unauditOrder },
  purchase: { ...docRules('purchase', (p) => { p.status = '已审核'; refreshPoStatus(p); return p.status }), 反审核: unauditPurchase },
  production: { ...docRules('production', (p) => (prodRet(p) >= prodQty(p) * (1 - OVER) ? '已完成' : prodRet(p) ? '部分回货' : p.nodes.some((n) => n.as) ? '生产中' : '已下单')), 审核: auditProduction, 反审核: unauditProduction },
  stock: { 审核: postStockDoc, 反审核: unpostStockDoc },
  techpack: { 生效: publishTp },
  change: { 驳回: rejectDoc('change'), 审核: auditChange },
  supplier: { 申请准入: requestAdmit, 通过: approveAdmit, 驳回: rejectAdmit },
}
function doAction(kind, doc, a, ...args) {
  const f = RULES[kind]?.[a]
  return f ? f(doc, ...args) : move(kind, doc, a)
}

/* ---------- 供应商 ---------- */
// purpose：采购 / 生产 只能用合作中的；打样 待准入、准入审批中也行
function canUseSupplier(code, purpose) {
  const s = sup(code)
  if (!s) return false
  return purpose === '打样' ? ['待准入', '准入审批中', '合作中'].includes(s.status) : s.status === '合作中'
}
// 统一社会信用代码：18 位，第 1-2 位登记管理部门和机构类别、3-8 位行政区划、9-18 位主体代码和校验码（不含 I O S V Z）；唯一
const CREDIT_RE = /^[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}$/
const normCredit = (v) => String(v || '').replace(/\s+/g, '').toUpperCase()
function creditProblem(v, self) {
  if (!v) return ''
  if (!CREDIT_RE.test(v)) return `统一社会信用代码「${v}」格式不对：18 位数字和大写字母，不含 I、O、S、V、Z`
  const dup = db.suppliers.find((x) => x !== self && (x.credit === v || x.pending?.items.some((i) => i.k === 'credit' && i.to === v)))
  return dup ? `统一社会信用代码 ${v} 已经是 ${dup.short}（${dup.code}）的` : ''
}
const checkCredit = (v, self) => { const m = creditProblem(v, self); if (m) fail(m) }
const isFactory = (s) => s.cats.some((c) => c === '成衣厂' || c === '加工厂')
// 编码流水：前缀 + 4 位，取现有最大号 + 1（删过的号不重用；导入时手填的老编码也不会撞）
const nextCode = (prefix, list) => prefix + String(Math.max(0, ...list.filter((x) => x.code.startsWith(prefix)).map((x) => +x.code.slice(prefix.length) || 0)) + 1).padStart(4, '0')
// 成衣厂、加工厂自动有一个工厂仓（PRD §6.1.6），名字跟着供应商简称
function ensureFactoryWh(s) {
  if (!isFactory(s) || factoryWh(s.code)) return null
  let code = 'GC' + s.code.replace(/^GYS/, '')
  if (wh(code)) code = nextCode('GC', db.warehouses)
  return add(db.warehouses, { code, name: s.short + '仓', type: '工厂仓', factory: s.code, addr: s.addr || '', keeper: '', phone: s.phone || '', status: '启用' })
}
// f.code、f.status 给导入用（领猫里的老编码、合作状态原样带过来）；新建只能是待准入，只有 Excel 导入能直接建成合作中（上线搬历史数据）
function createSupplier({ imported, ...f }) {
  if (!f.short || !f.name) fail('全称、简称都要填')
  if (!f.cats?.length) fail('至少选一个分类')
  const code = (f.code || '').trim().toUpperCase() || nextCode('GYS', db.suppliers)
  if (sup(code)) fail(`供应商编码 ${code} 已经有了`)
  f.credit = normCredit(f.credit)
  checkCredit(f.credit)
  const st = imported ? f.status || '待准入' : '待准入'
  if (st === '合作中' && !canAdmit()) fail(`直接导入成「合作中」要有供应商审批权限（${auditRoles('供应商').join('、')}），${me().name} 没有`)
  const s = add(db.suppliers, { level: 'B', settle: '月结', term: 30, tax: '13%', invoice: '', goodAt: [], wechat: '', addr: '', bank: '', account: '', note: '', certs: [], admitted: null, ...f, code, status: st, pending: null, riskChecks: [], riskNew: null, log: [] })
  if (st === '合作中' && !s.admitted) Object.assign(s, { admitted: clock.today, admittedBy: db.me })
  act(s, imported ? '导入，状态「' + st + '」' : '新建')
  ensureFactoryWh(s)
  return s
}
/* 准入审批（甲方 11-11 反馈 1d）：申请前资料要补齐；审批人要有供应商的「审」权限（设置 › 角色权限），而且不能是申请人
 * 申请时自动查一次企查查（riskCheck，在 7-pages-master.js），通过时有风险要审批人写明为什么仍然通过 */
const admitMissing = (s) => [!s.credit && '统一社会信用代码', !s.certs.some((c) => c.type === '营业执照') && '营业执照（资质里传一份「营业执照」）',
  !s.contact && '联系人', !s.bank && '开户行', !s.account && '账号'].filter(Boolean)
const canAdmit = () => auditRoles('供应商').includes(me()?.role)
function checkApprover(by, what) {
  if (by === db.me) fail(`${what}是你自己提的，不能自己批，换一个有审批权限的人`)
  if (!canAdmit()) fail(`${me().name}（${me().role}）没有供应商审批权限，要 ${auditRoles('供应商').join('、') || '有「审」权限的角色'} 来批`)
}
function requestAdmit(s) {
  const miss = admitMissing(s)
  if (miss.length) fail('资料没补齐，不能申请准入', miss.map((x) => '缺' + x))
  move('supplier', s, '申请准入')
  Object.assign(s, { applyBy: db.me, applyAt: clock.today, rejectNote: '' })
  return riskCheck(s, '申请准入')
}
// ackRisk：最近一次企查查不是"无风险"时，审批人写的理由（页面上先弹框写清楚有哪些风险）
function approveAdmit(s, ackRisk) {
  checkApprover(s.applyBy, '准入申请')
  const r = lastRisk(s) // 最近一次查成功的；查询失败不拦审批
  if (r && r.result !== '无风险' && !ackRisk?.trim()) fail(`企查查结果是「${r.result}」，要写明为什么仍然通过`, r.items.map((x) => `${x.type}（${x.date}）：${x.text}`))
  move('supplier', s, '通过', ackRisk?.trim() ? `企查查「${r.result}」，仍然通过：${ackRisk.trim()}` : '')
  Object.assign(s, { admitted: clock.today, admittedBy: db.me })
}
function rejectAdmit(s, reason) {
  if (!reason?.trim()) fail('要写驳回原因，申请人照着补')
  checkApprover(s.applyBy, '准入申请')
  move('supplier', s, '驳回', reason.trim())
  s.rejectNote = reason.trim()
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
  Object.assign(s, { blackReason: reason, blackAt: clock.today, blackBy: db.me })
}
const expiringCerts = () => db.suppliers.flatMap((s) => s.certs.filter((c) => c.until && daysLeft(c.until) <= 30 && daysLeft(c.until) >= 0).map((c) => ({ s, c })))

/* ---------- 款式 ---------- */
/* 默认款号（甲方 11-11 反馈 3）：大类 2 位 + 建款当天 yyMMdd + 设计师编号 2 位 + 2 位流水，如 FS2611080701
 * 同大类、同设计师、同一天从 01 编，最多 99 个；接着最大的流水往后编，删过、手填过的号不会撞。
 * 设计师编号在「设置 › 用户」里填，进过款号就锁定（db.dnoUsed：编号 → 人）。演示数据里 27CF001 这种是导入的历史款号，不改 */
const bigCatOf = (c) => db.bigCats.find((x) => x.code === c)
function nextStyleCode(big, designer, date = clock.today) {
  if (!bigCatOf(big)) fail('先选大类')
  const u = db.users.find((x) => x.id === designer)
  if (!u) fail('先选设计师：款号里要用设计师编号')
  if (!u.dno) fail(`${u.name} 还没有设计师编号，不能自动编款号`, [`去设置 › 用户里给 ${u.name} 填设计师编号`, '或者手填款号，系统查重'])
  const prefix = big + date.slice(2).replace(/-/g, '') + u.dno
  const n = Math.max(0, ...db.styles.filter((s) => s.code.startsWith(prefix) && s.code.length === prefix.length + 2).map((s) => +s.code.slice(prefix.length) || 0))
  if (n >= 99) fail(`${bigCatOf(big).name}、${u.name} 今天的款号编到 ${prefix}99 了，满了`, ['今天再建只能手填款号，或者换一个大类'])
  return prefix + String(n + 1).padStart(2, '0')
}
const lockDno = (uid) => { const u = db.users.find((x) => x.id === uid); if (u?.dno) db.dnoUsed[u.dno] ||= u.id }
function createStyle(f) {
  if (!f.name?.trim()) fail('款式名称要填')
  if (!f.cat) fail('要选品类')
  if (!f.year || !f.season) fail('年份、季节要填')
  if (!f.colors?.length) fail('至少选一个颜色')
  const big = f.big || 'FS', designer = f.designer === undefined ? db.me : f.designer
  const auto = !(f.code || '').trim()
  const code = auto ? nextStyleCode(big, designer) : f.code.trim().toUpperCase()
  if (!/^[A-Z0-9-]{3,20}$/.test(code)) fail(`款号「${code}」只能用字母、数字和 -，3-20 位`)
  if (style(code)) fail(`款号 ${code} 已经有了`)
  if (auto) lockDno(designer)
  const s = add(db.styles, { brand: '织间', gender: '女', source: '自主开发', wave: '第 1 波', tag: '通勤', sizeGroup: 'W5', ...f, big, designer, code, colors: [], skus: [], status: f.status || '草稿', log: [] })
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
// 资料包分研发、生产两个阶段（PRD §6.4.1）：研发 BOM 只估价；订单、算料、加工单只认生产 BOM。没写 stage 的是生产
// 版本号按阶段各自从 V1 编；不传 stage 的查找都只看生产，stage = '全部' 两个阶段都要
const tpStage = (t) => t.stage || '生产'
const tpName = (t) => (tpStage(t) === '研发' ? '研发 ' : '') + 'V' + t.ver
const tpsOf = (code, stage = '生产') => db.techpacks.filter((t) => t.style === code && (stage === '全部' || tpStage(t) === stage)).sort((a, b) => b.ver - a.ver)
const activeTp = (code, stage = '生产') => db.techpacks.find((t) => t.style === code && tpStage(t) === stage && t.status === '已生效')
const tpOf = (code, ver, stage = '生产') => db.techpacks.find((t) => t.style === code && tpStage(t) === stage && t.ver === ver)
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
// 单件成本按基码（女装 M）的用量算（PRD §6.4.2）；sizes 是尺码组的尺码，基码不在里面时取中间那个
const rowMidUse = (r, sizes, base) => (r.um === '统一' ? +r.use || 0 : +r.uses[base && sizes.includes(base) ? base : sizes[Math.floor((sizes.length - 1) / 2)]] || 0)
// 参考单价（每采购单位，PRD §6.4.2）：行上没改过时，生产 BOM 带价格档案里这行供应商的现价（5e-price.js），
// 没有、或者是研发 BOM，带物料 SKU 的单价，SKU 没单价用物料的参考单价
const rowRefPrice = (r, stage = '生产') => {
  const k = r.mode === '固定' ? r.sku : Object.values(r.map || {}).find(Boolean)
  const arch = stage === '生产' && typeof curMatPrice === 'function' ? curMatPrice(r.supplier || mat(r.mat)?.supplier, r.mat, k) : null
  return arch ?? (k ? skuPrice(k) : null) ?? mat(r.mat)?.price
}
const unitPrice = (r, stage) => { const m = mat(r.mat); return (r.price ?? rowRefPrice(r, stage)) / m.factor } // 折成库存单位
const rowCost = (r, sizes, base, stage) => rnd(rowMidUse(r, sizes, base) * (1 + r.loss / 100) * unitPrice(r, stage), 2)
// 只算我方采购的料：客供的料客户出，工厂自备的算在加工费里
const tpMaterialCost = (tp) => { const g = style(tp.style).sizeGroup; return rnd(sum(tp.bom.filter((r) => r.src === '我方采购'), (r) => rowCost(r, sizesOf(g), baseSizeOf(g), tpStage(tp))), 2) }
const tpLabor = (tp) => rnd(sum(tp.ops, (o) => o.price), 2)

// 尺寸表：rows = [{ pt 部位, how 量法, tol 公差, step 档差, vals: { 尺码: cm } }]，从基码按档差往两边放
function gradeRow(r, sizes, base, v) {
  const bi = sizes.indexOf(base)
  sizes.forEach((z, i) => { r.vals[z] = rnd(v + (i - bi) * r.step, 1) })
}
function specTemplate(styleCode) {
  const s = style(styleCode), sizes = sizesOf(s.sizeGroup), base = baseSizeOf(s.sizeGroup)
  return { base, rows: (SPEC_POINTS[catOf(s.cat).shape] || SPEC_POINTS.tee).map(([pt, how, v, step, tol]) => {
    const r = { pt, how, tol, step, vals: {} }
    gradeRow(r, sizes, base, v)
    return r
  }) }
}
function createTechpack(styleCode, bom, ops, note, spec, stage = '生产') {
  const ver = (tpsOf(styleCode, stage)[0]?.ver || 0) + 1
  // req = 工艺单的整体要求（文字 + 插图）；ops 每行 { cat, name, req 工艺要求, pics, sec, price, note }；source = 从哪个研发版转来的
  const tp = add(db.techpacks, { id: styleCode + (stage === '研发' ? '-R' : '-V') + ver, style: styleCode, stage, ver, source: null, status: '草稿', note: note || '', util: null, req: { text: '', pics: [] }, bom, ops, spec: spec || specTemplate(styleCode), log: [] })
  act(tp, ver === 1 ? '新建' : '由 V' + (ver - 1) + ' 修订')
  return tp
}
// 转生产 BOM（PRD §6.4.1、架构 §4.7）：生效的研发版复制成生产版草稿（没有生产版就是 V1），记下来源；
// 行上的手填估价不带过去，参考单价按价格档案重新取。之后两边各改各的
function toProdTp(dev) {
  if (tpStage(dev) !== '研发' || dev.status !== '已生效') fail('研发 BOM 生效以后才能转生产 BOM')
  const draft = tpsOf(dev.style).find((t) => t.status === '草稿')
  if (draft) fail(`生产 BOM 已经有草稿 V${draft.ver}，先把它改完或删掉`)
  const c = JSON.parse(JSON.stringify({ bom: dev.bom, ops: dev.ops, spec: dev.spec, req: dev.req || { text: '', pics: [] } }))
  c.bom.forEach((r) => { r.price = null })
  const t = createTechpack(dev.style, c.bom, c.ops, '', c.spec, '生产')
  Object.assign(t, { req: c.req, util: dev.util, source: { stage: '研发', ver: dev.ver } })
  act(t, '由研发 V' + dev.ver + ' 转生产 BOM')
  return t
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
    else [...new Set(r.mode === '固定' ? [r.sku] : s.colors.map((c) => r.map[c]))].filter(Boolean).forEach((k) => { if (skuOf(k)?.status === '停用') errs.push(`第 ${i + 1} 行：物料 SKU ${k} 已停用`) })
    if (r.mode === '固定' && !r.sku) errs.push(`第 ${i + 1} 行（${r.part}）：没选物料 SKU`)
    if (!db.dicts.物料来源.includes(r.src)) errs.push(`第 ${i + 1} 行（${r.part}）：来源「${r.src}」现在不能用，改成 ${db.dicts.物料来源.join(' / ')}`) // TRADE 关着时没有客供
  })
  // 尺寸表是二期（PRD 04.05），一期生效不查它
  return errs
}
function publishTp(tp) {
  const errs = tpCheck(tp)
  if (errs.length) fail('资料包还不能生效', errs)
  db.techpacks.filter((t) => t.style === tp.style && tpStage(t) === tpStage(tp) && t.status === '已生效').forEach((t) => { t.status = '已作废'; act(t, '被 ' + tpName(tp) + ' 替代') }) // 同款同阶段只留一个生效版
  move('techpack', tp, '生效')
  tp.publishedAt = clock.today
  tp.publishedBy = db.me
}
function reviseTp(tp) {
  if (tpsOf(tp.style, tpStage(tp)).some((t) => t.status === '草稿')) fail('已经有一个草稿版本，先把它改完')
  const copy = JSON.parse(JSON.stringify({ bom: tp.bom, ops: tp.ops, spec: tp.spec, req: tp.req || { text: '', pics: [] } }))
  const t = createTechpack(tp.style, copy.bom, copy.ops, '', copy.spec, tpStage(tp))
  Object.assign(t, { req: copy.req, util: tp.util })
  return t
}
// 删掉草稿版本（修订出来不想要了）；生效过、作废了的版本只能看
function removeTpDraft(tp) {
  if (tp.status !== '草稿') fail(`${tpName(tp)} 是「${tp.status}」，只有草稿版本能删`)
  db.techpacks.splice(db.techpacks.indexOf(tp), 1)
  act(style(tp.style), `删除资料包草稿 ${tpName(tp)}`)
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
// 作废的回货单不算数（统计、对账、看板都用这个）
const liveReturns = () => db.returns.filter((r) => r.status !== '已作废')
const orderSched = (o) => sum(o.lines, (l) => scheduledQty(o, l))
const orderRet = (o) => sum(o.lines, (l) => returnedQty(o, l))
const orderLive = (o) => ['已审核', '生产中'].includes(o.status)

// 停用的 SKU（款式 SKU 停用了，或者整个颜色停用了）不能下单（PRD §6.2）
function checkOrderLines(lines) {
  const bad = lines.map((l) => {
    const k = style(l.style)?.skus.find((x) => x.color === l.color && x.size === l.size)
    return !k ? `${l.style}-${l.color}-${l.size} 已删除` : k.status === '停用' ? `${k.code} 已停用` : colorOf(l.color).status === '停用' ? `${k.code} 的颜色已停用` : ''
  }).filter(Boolean)
  if (bad.length) fail('停用或删掉的 SKU 不能下单', bad)
}
function createOrder(f) {
  const lines = (f.lines || []).filter((l) => l.qty > 0)
  if (!lines.length) fail('至少录一个数量')
  checkOrderLines(lines)
  if (TRADE && f.type !== '自营备货' && !f.customer) fail(`${f.type || '客户订单'}要选客户`)
  if (!f.due) fail(DUE_T + '要填')
  // 客户、客户订单号：TRADE 关着时不填，字段留着（以后接代工用）
  const o = add(db.orders, { no: nextNo('DD'), company: myCompany(), currency: '人民币', date: clock.today, commit: null, sales: roleUser('业务'), merch: roleUser('生产跟单'), ship: '', customer: null, custNo: '', contract: '', brand: '', src: '手工', note: '', ...f, lines: lines.map((l) => ({ ver: null, price: null, ...l })), status: '草稿', log: [] })
  act(o, '新建')
  return o
}
function submitOrder(o) {
  const errs = []
  orderStyles(o).forEach((c) => {
    const s = style(c)
    if (s.status !== '启用') errs.push(`${c} 是「${s.status}」，不能下单`)
    else if (!activeTp(c)) errs.push(`${c} 没有生效的生产 BOM` + (activeTp(c, '研发') ? '：研发 BOM 只用来估价，先在资料包里点「转生产 BOM」' : '')) // 研发 BOM 订单不能用（PRD §6.4.1）
    else unmatchedColors(s).forEach((col) => { if (o.lines.some((l) => l.style === c && l.color === col)) errs.push(`${c} 的 ${colorName(col)} 还没配料`) })
  })
  if (errs.length) fail('订单还不能提交', errs)
  checkOrderLines(o.lines)
  move('order', o, '提交')
}
function auditOrder(o, commit) {
  if (!commit) fail('要填承诺交期')
  const miss = orderStyles(o).filter((c) => !activeTp(c))
  if (miss.length) fail('没有生效的生产 BOM 的款不能审核', miss)
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
const unitPriceOfSku = (s) => skuPrice(s) / matOfSku(s).factor
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
    else if (typeof procPriceProblem === 'function' && procPriceProblem(b)) errs.push(`${b.style}：${procPriceProblem(b)}`) // 和档案现价不一样要写改价原因（5e-price.js）
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

/* 订单变更（PRD §6.5.6）：f = { items: [{ k: '27CF001|KQ|M', to: 100 }]（数量；原来没有的颜色 = 配色配码）, due: 新交期,
 *   prices: [{ style, to }] 单价, vers: [{ style, to }] 换资料包版本, cancel: true 取消订单 } */
const CHANGE_PARTIES = TRADE ? ['客户', '我方', '工厂', '供应商'] : ['我方', '工厂', '供应商'] // 变更责任方，第一个是默认
const orderDown = (o) => [...prodsOf(o).map((p) => p.no), ...mrpsOf(o).filter((m) => m.status !== '已关闭').map((m) => m.no)]
function buildChange(o, f) {
  if (!f.reason?.trim()) fail('要写变更原因')
  if (f.cancel) {
    if (orderDown(o).length) fail('订单已经有下游单据，先把它们作废或结案，再取消订单', orderDown(o))
    return { types: ['取消订单'], cancel: true, items: [], due: null, prices: [], vers: [] }
  }
  const items = (f.items || []).map((i) => { const l = o.lines.find((x) => lineKey(x) === i.k); return { k: i.k, label: i.k.replace(/\|/g, '-'), from: l ? l.qty : 0, to: +i.to || 0 } }).filter((i) => i.to !== i.from)
  const due = f.due && f.due !== o.due ? { from: o.due, to: f.due } : null
  const firstLine = (st) => o.lines.find((l) => l.style === st)
  const prices = (f.prices || []).map((x) => ({ style: x.style, from: firstLine(x.style)?.price ?? null, to: x.to === '' || x.to == null ? null : +x.to })).filter((x) => x.to !== x.from)
  const vers = (f.vers || []).map((x) => ({ style: x.style, from: firstLine(x.style)?.ver ?? null, to: +x.to || null })).filter((x) => x.to && x.to !== x.from)
  if (!items.length && !due && !prices.length && !vers.length) fail('没有改动')
  const errs = []
  items.forEach((i) => {
    const l = o.lines.find((x) => lineKey(x) === i.k)
    const done = l ? scheduledQty(o, l) : 0
    if (i.to < done) errs.push(`${i.label}：已排产 ${done} 件，先调整加工单 ${prodsOf(o).filter((p) => p.style === l.style).map((p) => p.no).join('、')}`)
    const [st, co] = i.k.split('|')
    const ver = vers.find((v) => v.style === st)?.to ?? firstLine(st)?.ver, tp = tpOf(st, ver)
    if (!l && tp?.bom.some((r) => r.mode === '随款色' && !r.map[co])) errs.push(`${st} 的${colorName(co)}在资料包 V${ver} 里没配料：先修订资料包并生效，这次变更把资料包一起换到新版本`)
  })
  vers.forEach((v) => { if (tpOf(v.style, v.to)?.status !== '已生效') errs.push(`${v.style} 的 V${v.to} 不是已生效的版本`) })
  if (errs.length) fail('变更不能提交', [...new Set(errs)])
  const types = [items.some((i) => i.from > 0) && '数量', items.some((i) => !i.from) && '配色配码', due && '交期', prices.length && '单价', vers.length && '资料包版本'].filter(Boolean)
  return { types, cancel: false, items, due, prices, vers }
}
function createChange(o, f) {
  if (!orderLive(o)) fail('只有已审核的订单才走变更')
  const c = add(db.changes, { no: nextNo('BG'), order: o.no, ...buildChange(o, f), reason: f.reason.trim(), party: f.party || CHANGE_PARTIES[0], fee: f.fee || '', files: f.files || [], date: clock.today, by: db.me, status: '待审核', log: [] })
  act(c, '提交')
  act(o, '提交变更单 ' + c.no)
  return c
}
// 被驳回的变更单改了再提交
function resubmitChange(c, f) {
  const o = order(c.order)
  if (!orderLive(o)) fail(`订单现在是「${o.status}」，不能再变更`)
  Object.assign(c, buildChange(o, f), { reason: f.reason.trim(), party: f.party || CHANGE_PARTIES[0], fee: f.fee || '', files: f.files || [] })
  move('change', c, '重新提交')
}
const describeChange = (c) => [
  c.cancel && '取消订单',
  ...c.items.map((i) => `${i.label}：${i.from} → ${i.to}${i.from ? '' : '（新增）'}`),
  c.due && `${DUE_T}：${fd(c.due.from)} → ${fd(c.due.to)}`,
  ...(c.prices || []).map((x) => `${x.style} 单价：${fm(x.from)} → ${fm(x.to)}`),
  ...(c.vers || []).map((x) => `${x.style} 资料包：V${x.from} → V${x.to}`),
].filter(Boolean).join('；')
function auditChange(c) {
  const o = order(c.order)
  if (c.cancel && orderDown(o).length) fail('订单已经有下游单据，不能取消', orderDown(o))
  move('change', c, '审核')
  if (c.cancel) { o.status = '已取消'; act(o, `变更单 ${c.no} 生效：取消订单`); return }
  c.items.forEach((i) => {
    const l = o.lines.find((x) => lineKey(x) === i.k)
    if (l) l.qty = i.to
    else { const [st, co, sz] = i.k.split('|'); const ref = o.lines.find((x) => x.style === st); o.lines.push({ style: st, color: co, size: sz, qty: i.to, price: ref?.price ?? null, ver: ref?.ver ?? null }) }
  })
  if (c.due) o.due = c.due.to
  for (const x of c.prices || []) o.lines.filter((l) => l.style === x.style).forEach((l) => { l.price = x.to })
  for (const x of c.vers || []) o.lines.filter((l) => l.style === x.style).forEach((l) => { l.ver = x.to })
  const text = describeChange(c)
  act(o, `变更单 ${c.no} 生效：${text}`)
  const note = { no: c.no, order: o.no, at: clock.today, text }
  prodsOf(o).forEach((p) => { p.upstream = { ...note } })
  db.mrps.filter((m) => m.orders.includes(o.no) && m.status !== '已关闭').forEach((m) => { m.upstream = { ...note } })
  db.purchases.filter((p) => p.lines.some((l) => l.src?.orders?.includes(o.no)) && ['草稿', '待审核', '已审核', '部分到货'].includes(p.status)).forEach((p) => { p.upstream = { ...note } })
}
const upstreamPending = () => [...db.productions, ...db.mrps, ...db.purchases].filter((d) => d.upstream && !d.upstream.done)

/* ---------- 算料 ---------- */
// 毛需求 = Σ 订单 SKU 数 × 尺码用量；需求 = Σ 毛需求 ×（1 + 损耗）×（1 + 备料比）。按 来源 + 物料 SKU + 物权 汇总
// 备料比按物料类型设（算料页「备料比」，存在 db.reserve，单位 %），默认 0，PRD §4.3 的例子就是 0
const reserveOf = (sku) => +(db.reserve?.[matOfSku(sku)?.type] || 0)
function calcNeeds(orderNos, useActive) {
  const acc = {}
  orderNos.map(order).forEach((o) => o.lines.forEach((l) => {
    const tp = useActive ? activeTp(l.style) : tpOf(l.style, l.ver)
    if (!tp) return
    tp.bom.forEach((r) => {
      const sku = rowSku(r, l.color)
      if (!sku) return
      const owner = r.src === '客供' ? o.customer || '自有' : '自有' // 客供料记在订单客户名下；TRADE 关着时没有客供，全是自有
      const supplier = r.supplier || matOfSku(sku).supplier // 参考单价和生成的采购单一样，取这家的现价
      const a = (acc[r.src + '|' + sku + '|' + owner] ||= { sku, owner, src: r.src, supplier, price: poDefaultPrice(supplier, sku), gross: 0, need: 0, orders: [], styles: [] })
      a.gross += l.qty * rowUse(r, l.size)
      a.need += l.qty * rowUse(r, l.size) * (1 + r.loss / 100)
      if (!a.orders.includes(o.no)) a.orders.push(o.no)
      if (!a.styles.includes(l.style)) a.styles.push(l.style)
    })
  }))
  return Object.values(acc).map((a) => { const rv = reserveOf(a.sku); return { ...a, gross: rnd(a.gross, 3), need: rnd(a.need * (1 + rv / 100), 3), reserve: rv } })
}
const materialWhs = () => db.warehouses.filter((w) => w.type === '物料仓').map((w) => w.code)
/* 可用量 = 库存 + 交期赶得上的在途 − 别的算料单占着的（PRD §6.6.1，11-11 起在途自动扣）
 * 占着的：别的已确认、没关闭的算料单的本次使用库存、本次使用在途（记到具体采购行上），和它们生成的采购单（送物料仓的）。
 * 在途到了货就从"占着的在途"变成"占着的库存"；它们的订单领走多少，占着的库存就减多少。备货采购单没人占，谁先算谁用 */
const liveMrps = (except) => db.mrps.filter((m) => m.status === '已确认' && m.no !== except)
const poLineLeft = (l) => Math.max(0, rnd((l.qty - l.received) * matOfSku(l.sku).factor, 3)) // 换成库存单位
const poLineGot = (l) => rnd(l.received * matOfSku(l.sku).factor, 3)
// 算料单这一行"本次使用在途"里已经到货的（got）和还在路上的（left）
function useTransitSplit(r) {
  let got = 0, left = 0
  for (const u of r.transitUse || []) {
    const l = purchase(u.po)?.lines[u.i]
    const g = l ? Math.min(u.qty, poLineGot(l)) : 0
    got += g; left += u.qty - g
  }
  return { got: rnd(got, 3), left: rnd(left, 3) }
}
const ownPoLines = (m, sku, owner) => { const nos = mrpChain(m); return db.purchases.filter((p) => !['草稿', '待审核', '已作废'].includes(p.status) && materialWhs().includes(p.wh)).flatMap((p) => p.lines).filter((l) => nos.includes(l.src?.mrp) && l.sku === sku && (l.owner || '自有') === owner) }
function mrpHeldStock(m, sku, owner) {
  const rs = m.rows.filter((r) => r.sku === sku && r.owner === owner)
  if (!rs.length) return 0
  const got = sum(rs, (r) => (r.useStock || 0) + useTransitSplit(r).got) + sum(ownPoLines(m, sku, owner), poLineGot)
  return Math.max(0, rnd(got - issuedFor(m.orders, sku, owner), 3))
}
const heldStock = (sku, owner, except) => rnd(sum(liveMrps(except), (m) => mrpHeldStock(m, sku, owner)), 3)
function availFor(sku, owner, exceptMrp) {
  const have = sum(materialWhs(), (w) => stockQty(w, sku, owner))
  return Math.max(0, rnd(have - heldStock(sku, owner, exceptMrp), 3))
}
const issuedFor = (orderNos, sku, owner) => sum(db.stockDocs.filter((d) => d.status === '已审核' && (d.type === '领料' || d.type === '退料') && orderNos.includes(production(d.src)?.order))
  .flatMap((d) => d.lines.filter((l) => l.sku === sku && l.owner === owner).map((l) => (d.type === '退料' ? -l.qty : l.qty))))
// 在途行：已审核、没入齐、送物料仓的采购行（直发工厂仓的是给那张加工单的，不进公共的数）
//   left 没到的（库存单位）、held 别的算料单占着的、free 还能用的、date 预计到货（没填用交期）
function transitRows(sku, owner, except) {
  const live = liveMrps(except)
  return db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status) && materialWhs().includes(p.wh)).flatMap((p) => p.lines.map((l, i) => ({ p, l, i })))
    .filter(({ l }) => l.sku === sku && (l.owner || '自有') === owner && l.received < l.qty - 0.0005)
    .map(({ p, l, i }) => {
      const left = poLineLeft(l), by = l.src?.mrp ? live.find((m) => mrpChain(m).includes(l.src.mrp)) : null // 算料生成的：整行归那张算料单
      const used = sum(live.flatMap((m) => m.rows.flatMap((r) => (r.transitUse || []).filter((u) => u.po === p.no && u.i === i))), (u) => u.qty)
      const held = by ? left : Math.min(left, Math.max(0, rnd(used - poLineGot(l), 3)))
      return { po: p.no, i, type: p.type, date: l.expect || l.due || p.due, left, held, free: rnd(left - held, 3), by: by?.no || null }
    })
}
const transitQty = (sku, owner) => rnd(sum(transitRows(sku, owner), (x) => x.left), 3)
const heldTransit = (sku, owner) => rnd(sum(transitRows(sku, owner), (x) => x.held), 3)
// 要料日期（PRD §6.6.1）：订单已排单的取加工单"裁剪"的计划开始；没排单的按承诺交期倒推（加工单交期 = 承诺交期 − 10 天，再按节点默认天数推到裁剪开始）
const PROD_LEAD = 10
function cutStartFrom(due) {
  let end = due
  for (const [name, days] of db.dicts.生产节点.slice().reverse()) { const ps = addDays(end, -(days - 1)); if (name === '裁剪') return ps; end = addDays(ps, -1) }
  return due
}
const prodCutDate = (p) => p.nodes.find((n) => n.name === '裁剪')?.ps || (p.due ? cutStartFrom(p.due) : null)
function orderNeedDate(o) {
  if (!o) return null
  const cut = prodsOf(o).filter((p) => !['已完成', '已结案'].includes(p.status)).map(prodCutDate).filter(Boolean).sort()
  if (cut.length) return cut[0]
  const d = o.commit || o.due
  return d ? cutStartFrom(addDays(d, -PROD_LEAD)) : null
}
const needDateOf = (orderNos) => (orderNos || []).map((n) => orderNeedDate(order(n))).filter(Boolean).sort()[0] || null
// 本次使用在途：v 不超过可用在途、需求 − 本次使用库存，按预计到货先后分到具体采购行上；跟着重算净需求、建议采购
function setUseTransit(r, v) {
  r.useTransit = rnd(Math.max(0, Math.min(+v || 0, r.availTransit || 0, r.need - r.useStock)), 3)
  let left = r.useTransit
  r.transitUse = (r.transitOk || []).map((x) => { const q = rnd(Math.min(left, x.free), 3); left = rnd(left - q, 3); return { po: x.po, i: x.i, qty: q, date: x.date } }).filter((u) => u.qty > 0)
  r.net = rnd(r.need - r.useStock - r.useTransit, 3)
  if (r.src === '我方采购') r.purQty = Math.max(suggestPur(r.sku, r.net), r.converted || 0)
}
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
  const no = nextNo('SL'), needDate = needDateOf(orderNos)
  const rows = calcNeeds(orderNos).map((a) => {
    const need = a.need
    if (a.src === '工厂自备') return a
    const avail = availFor(a.sku, a.owner, no)
    const useStock = Math.min(avail, need)
    // 在途：赶得上要料日期的自动扣（先到的先用），赶不上的单独列出来、不扣
    const tr = transitRows(a.sku, a.owner, no).filter((x) => x.free > 0)
    const ok = tr.filter((x) => needDate && x.date <= needDate).sort((x, y) => (x.date < y.date ? -1 : x.date > y.date ? 1 : 0))
    const late = tr.filter((x) => !ok.includes(x)).map(({ po, i, date, free }) => ({ po, i, date, free }))
    const r = { ...a, need, avail, useStock, transitOk: ok.map(({ po, i, date, free }) => ({ po, i, date, free })), availTransit: rnd(sum(ok, (x) => x.free), 3),
      transitLate: late, lateTransit: rnd(sum(late, (x) => x.free), 3), useTransit: 0, transitUse: [], net: need, purQty: 0, converted: 0 }
    setUseTransit(r, r.availTransit)
    if (a.src !== '我方采购') r.purQty = 0
    return r
  })
  const m = add(db.mrps, { no, company: myCompany(), orders: [...orderNos], date: clock.today, needDate, status: '草稿', rows, upstream: null, note: '', log: [] })
  act(m, '算料：' + orderNos.join('、'))
  return m
}
// 改本次用库存、本次用在途：重算净需求和建议采购；直接改建议采购量（特殊情况，PRD §6.6.1）不小于已转采购，记操作记录
function updateMrpRow(r, field, v, m) {
  v = Math.max(0, +v || 0)
  if (field === 'purQty') {
    const was = r.purQty
    r.purQty = Math.max(rnd(v, 3), r.converted)
    if (m && r.purQty !== was) act(m, `改建议采购：${skuName(r.sku)} ${fq(was)} → ${fq(r.purQty)} ${matOfSku(r.sku).purUnit}`)
    return
  }
  if (field === 'useStock') r.useStock = Math.min(v, r.avail, r.need)
  setUseTransit(r, field === 'useTransit' ? v : r.useTransit || 0) // 本次用在途只能改小，不超过可用在途
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
    lines: rs.map((r) => ({ sku: r.sku, owner: r.owner, qty: rnd(r.purQty - r.converted, 3), src: { mrp: m.no, orders: r.orders, styles: r.styles } })), // 单价按价格档案取现价
  }))
  rows.forEach((r) => { r.converted = r.purQty })
  act(m, '生成采购单 ' + pos.map((p) => p.no).join('、'))
  return pos
}
// 算料单某一行已入库多少（库存单位）：它和它的前身（重新算料换掉的旧单）生成的采购单行的已入库
const mrpChain = (m) => (m ? [m.no, ...(m.prev ? mrpChain(mrp(m.prev.no)) : [])] : [])
function mrpRecv(m, r) {
  const nos = mrpChain(m)
  return rnd(sum(db.purchases.flatMap((p) => p.lines).filter((l) => nos.includes(l.src?.mrp) && l.sku === r.sku && (l.owner || '自有') === r.owner), (l) => l.received * matOfSku(r.sku).factor), 3)
}
// 齐料（PRD §6.6.3）：订单关联算料单"我方采购"每一行，自己采购的已入库 + 本次使用在途里已经到货的 + 本次使用库存 ≥ 需求；重新算料换掉的旧单不算
function kitState(o) {
  const ms = mrpsOf(o).filter((m) => !m.next)
  if (!ms.length) return { t: '未算料', k: 'none' }
  const rows = ms.flatMap((m) => m.rows.filter((r) => r.src === '我方采购').map((r) => ({ m, r })))
  const ok = rows.filter(({ m, r }) => mrpRecv(m, r) + useTransitSplit(r).got + r.useStock >= r.need - 0.001).length
  if (ok === rows.length) return { t: '齐料', k: 'ok' }
  return ok ? { t: `部分到料 ${ok}/${rows.length}`, k: 'part' } : { t: '未到料', k: 'no' }
}

/* ---------- 采购 ---------- */
const poQty = (p) => rnd(sum(p.lines, (l) => l.qty), 3)
const poAmt = (p) => rnd(sum(p.lines, (l) => l.qty * l.price), 2)
const poRecv = (p) => rnd(sum(p.lines, (l) => l.received), 3)
// 采购默认单价（PRD §6.10 规则 3）：价格档案里这家供应商在下单日的现价（5e-price.js），没有就带物料 SKU 单价、物料参考单价
const poDefaultPrice = (supCode, sku, date) => (typeof curMatPrice === 'function' ? curMatPrice(supCode, matOfSku(sku)?.code, sku, date) : null) ?? skuPrice(sku) ?? null
function createPurchase(f) {
  if (!canUseSupplier(f.supplier, '采购')) fail(`${supName(f.supplier)} 现在是「${sup(f.supplier)?.status || '未选'}」，不能下采购单`)
  const lines = (f.lines || []).filter((l) => l.sku && l.qty > 0)
  if (!lines.length) fail('至少一行物料')
  const lead = Math.max(...lines.map((l) => matOfSku(l.sku).lead || 7))
  const s = sup(f.supplier)
  // 交货日期默认 = 下单日 + 各行最长的采购周期（PRD §6.6.2）
  const date = f.date || clock.today, due = f.due || addDays(date, lead)
  if (due < date) fail('交货日期不能早于下单日期')
  const ls = lines.map((l) => ({ owner: '自有', due: null, expect: null, note: '', priceWhy: '', received: 0, returned: 0, src: null, ...l, price: l.price ?? poDefaultPrice(f.supplier, l.sku, date) }))
  if (typeof checkPoPrices === 'function') checkPoPrices(f.supplier, date, ls) // 和档案现价不一样的行要写改价原因（5e-price.js）
  // plan = 计划用于款号（备货采购选填，只是备注，算料不看它，PRD §6.6.7）
  const p = add(db.purchases, {
    no: nextNo('CG'), company: myCompany(), type: '订单采购', buyer: roleUser('采购'), wh: 'WL01', settle: s.settle, tax: s.tax, taxIncl: true, note: '', plan: [], upstream: null, ...f, date, due,
    lines: ls, status: '草稿', log: [],
  })
  act(p, '新建')
  return p
}
function unauditPurchase(p) {
  if (poRecv(p) > 0) fail('已经有入库，不能反审核')
  if (liveContractOf(p.no)) fail(`先作废合同 ${liveContractOf(p.no).no}，再反审核`) // 合同在 7b-contracts.js
  move('purchase', p, '反审核')
}
function refreshPoStatus(p) {
  if (!['已审核', '部分到货', '已到齐'].includes(p.status)) return
  const all = p.lines.every((l) => l.received >= l.qty - 0.0005)
  const any = p.lines.some((l) => l.received > 0)
  const ns = all ? '已到齐' : any ? '部分到货' : '已审核'
  if (ns !== p.status) { p.status = ns; act(p, ns) }
}
// lines = [{ i: 采购行号, qty: 采购单位数量, lot: 缸号, rolls: 卷数, note }]；date 是实际到货日期（第二天补录时填前一天，PRD §2.5）
function receivePurchase(p, f) {
  if (!['已审核', '部分到货'].includes(p.status)) fail('采购单审核后才能入库')
  const ls = (f.lines || []).filter((x) => x.qty > 0)
  if (!ls.length) fail('至少收一行')
  if (f.date && f.date < p.date) fail('入库日期不能早于下单日期 ' + fd(p.date))
  const errs = []
  ls.forEach((x) => {
    const l = p.lines[x.i], cap = rnd((l.qty - l.received) + l.qty * 0.05, 3)
    if (x.qty > cap) errs.push(`${skuName(l.sku)}：最多还能收 ${fq(cap)} ${matOfSku(l.sku).purUnit}`)
  })
  if (errs.length) fail('超过可收数量（溢收 5%）', errs)
  const d = createStockDoc({
    type: '采购入库', src: p.no, partner: p.supplier, to: f.wh || p.wh, supNo: f.supNo || '', logistics: f.logistics || '', note: f.note || '', ...(f.date ? { date: f.date } : {}),
    lines: ls.map((x) => { const l = p.lines[x.i], m = matOfSku(l.sku); return { sku: l.sku, owner: l.owner, qty: rnd(x.qty * m.factor, 3), purQty: x.qty, price: l.price, lot: x.lot || '', rolls: x.rolls || null, note: x.note || '', poLine: x.i } }),
  })
  if (f.files?.length) d.files = f.files
  postStockDoc(d)
  return d
}
// 采购行的要料日期（PRD §6.6.3）：订单采购看它来源的订单；备货采购被算料用了（本次使用在途），看用它的那张算料单的订单；没人用的没有
function poLineNeed(p, i) {
  const l = p.lines[i]
  if (l.src?.orders?.length) return needDateOf(l.src.orders)
  const users = liveMrps().filter((m) => m.rows.some((r) => (r.transitUse || []).some((u) => u.po === p.no && u.i === i)))
  return needDateOf(users.flatMap((m) => m.orders))
}
// 到料跟踪：未入齐的采购行；cutLate = 预计到货（没填用交期）比要料日期晚几天（赶不上开裁），0 = 赶得上或没有要料日期
function arrivals() {
  return db.purchases.filter((p) => ['已审核', '部分到货'].includes(p.status)).flatMap((p) => p.lines.map((l, i) => ({ p, l, i })))
    .filter(({ l }) => l.received < l.qty - 0.0005)
    .map((x) => {
      const d = x.l.expect || x.l.due || x.p.due, left = daysLeft(d), need = poLineNeed(x.p, x.i)
      return { ...x, date: d, left, risk: left < 0 ? 'late' : left <= 3 ? 'near' : '', need, cutLate: need && d > need ? diffDays(d, need) : 0 }
    })
}

/* ---------- 库存单据 ---------- */
const STOCK_TYPES = { 采购入库: 'RK', ...(TRADE ? { 客供入库: 'RK' } : {}), 其他入库: 'RK', 领料: 'LL', 退料: 'LL', 调拨: 'DB', 采购退货: 'CK', 其他出库: 'CK' }
// 新建、改草稿都过这一遍（PRD §6.6.5 类型表），返回有数量的行
function checkStockDoc(f) {
  const lines = (f.lines || []).filter((l) => l.sku && l.qty > 0)
  if (!lines.length) fail('至少一行物料')
  if (f.type === '客供入库' && !f.partner) fail('客供入库要选客户')
  if (['调拨', '领料', '退料'].includes(f.type) && (!f.from || !f.to)) fail('出仓、入仓都要选')
  if (f.from && f.from === f.to) fail('出仓和入仓不能是同一个')
  if (['其他入库', '其他出库'].includes(f.type) && !f.reason) fail(f.type + '要选原因（期初、盘盈、盘亏、报废……）')
  if (f.type === '退料' && (wh(f.from)?.type !== '工厂仓' || wh(f.to)?.type !== '物料仓')) fail('退料是工厂剩料退回：出仓选工厂仓，入仓选物料仓')
  if (f.date && f.date > clock.today) fail('单据日期不能晚于今天')
  return lines
}
function createStockDoc(f) {
  const lines = checkStockDoc(f)
  const owner = f.type === '客供入库' ? f.partner : null
  const d = add(db.stockDocs, {
    no: nextNo(STOCK_TYPES[f.type]), company: myCompany(), src: '', from: null, to: null, partner: '', reason: '', logistics: '', note: '', date: clock.today, ...f,
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
  // 采购退货回写采购单的已退货（采购单位）
  if (d.type === '采购退货' && d.src) { const p = purchase(d.src); d.lines.forEach((l) => { if (l.poLine != null) p.lines[l.poLine].returned = rnd(p.lines[l.poLine].returned + l.purQty, 3) }); act(p, '退货 ' + d.no) }
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
  if (d.type === '采购退货' && d.src) { const p = purchase(d.src); d.lines.forEach((l) => { if (l.poLine != null) p.lines[l.poLine].returned = rnd(p.lines[l.poLine].returned - l.purQty, 3) }); act(p, '退货单 ' + d.no + ' 反审核，已退货改回') }
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
    price: +b.price, priceWhy: b.why || '', date: clock.today, due: b.due, expect: null, merch: b.merch || o.merch, settle: b.settle || sup(b.factory).settle, terms: b.terms || '', note: b.note || '', status: '草稿', lines, nodes: [], issues: [], upstream: null, log: [],
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
// 反审核：还没开工（没报进度、没领料、没回货、没异常）才行，节点计划清掉，订单没有别的在做的加工单就回到已审核
function unauditProduction(p) {
  const why = []
  if (p.nodes.some((n) => n.as || n.af)) why.push('已经报过进度')
  const issued = db.stockDocs.filter((d) => d.src === p.no && d.status === '已审核').map((d) => d.no)
  if (issued.length) why.push('已经领过料：' + issued.join('、'))
  if (prodRet(p)) why.push('已经回过货 ' + prodRet(p) + ' 件')
  if (p.issues.length) why.push('已经上报过异常')
  if (why.length) fail('加工单已经开工，不能反审核', why)
  if (liveContractOf(p.no)) fail(`先作废合同 ${liveContractOf(p.no).no}，再反审核`) // 合同在 7b-contracts.js
  move('production', p, '反审核')
  p.nodes = []
  const o = order(p.order)
  if (o?.status === '生产中' && !prodsOf(o).some(prodLive)) { o.status = '已审核'; act(o, '加工单 ' + p.no + ' 反审核，订单回到已审核') }
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
  Object.assign(n, { as: f.as || n.as || f.af, af: f.af || null, qty: f.qty ?? n.qty, note: f.note || n.note, by: db.me, at: clock.today, photos: [...(n.photos || []), ...(f.photos || [])] })
  // 每次汇报留一条（谁、哪天报的、报了什么），节点字段只留最新的（PRD §7.2 prd_node_report）
  n.reports = [...(n.reports || []), { at: clock.today, by: db.me, as: f.as || null, af: f.af || null, qty: f.qty ?? null, note: f.note || '', photos: f.photos?.length || 0 }]
  if (p.status === '已下单') p.status = '生产中'
  act(p, `报进度：${n.name}${f.af ? '完成' : '开始'}${f.qty ? ' ' + f.qty + ' 件' : ''}${f.photos?.length ? '，照片 ' + f.photos.length + ' 张' : ''}`)
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
  // PRD §6.7.4 字段表：描述、照片都必填（每种类型都要拍：问题部位、到货短缺、现场）
  if (!f.photos?.length) fail(`「${f.type}」要传至少一张照片`, ['拍问题部位或现场情况，工厂和供应商对责任时要用'])
  const i = add(p.issues, { id: nextNo('YC'), type: f.type, desc: f.desc, photos: f.photos || [], newDate: f.newDate || null, party: f.party || '', status: '待处理', at: clock.today, by: db.me, measure: '' })
  if (f.newDate) p.expect = f.newDate
  act(p, `上报异常：${f.type}${f.newDate ? '，预计交期改为 ' + fd(f.newDate) : ''}`)
  return i
}
// 关闭：处理措施必填；上报时没定的责任方可以这时补上（质量、绩效以后要用）
function closeIssue(p, i, measure, party) {
  if (!measure) fail('关闭前要写处理措施')
  if (party !== undefined && party !== i.party) i.party = party
  Object.assign(i, { measure, status: '已关闭', closedAt: clock.today, closedBy: db.me })
  act(p, '关闭异常：' + i.type + (i.party ? '，责任方 ' + i.party : ''))
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
    return { ...a, need, issued, suggest: Math.max(0, rnd(need - issued, 3)), avail: rnd(sum(materialWhs(), (w) => stockQty(w, a.sku, a.owner)), 3) }
  })
}
function issueState(p) {
  const plan = issuePlan(p)
  if (!plan.length) return '—'
  if (plan.every((r) => r.issued >= r.need - 0.001)) return '已领齐'
  return plan.some((r) => r.issued > 0) ? '部分领料' : '未领料'
}
// 领料：从选的物料仓（默认主料仓）发到加工厂的工厂仓
function issueMaterials(p, lines, from = 'WL01') {
  if (!prodLive(p)) fail('加工单审核后才能领料')
  const fw = factoryWh(p.factory)
  if (!fw) fail('这个加工厂还没有工厂仓')
  if (wh(from)?.type !== '物料仓') fail('领料要从物料仓发')
  const ls = lines.filter((l) => l.qty > 0)
  if (!ls.length) fail('至少领一行')
  const short = stockShortage(from, ls)
  if (short.length) fail('库存不够，没领成', short)
  const d = createStockDoc({ type: '领料', src: p.no, from, to: fw.code, partner: p.factory, lines: ls })
  postStockDoc(d)
  act(p, '领料 ' + d.no)
  // 订单的加工单都领齐了，算料单自动关闭，释放"本次使用库存"的占用
  const o = order(p.order)
  if (o && o.lines.every((l) => scheduledQty(o, l) >= l.qty) && prodsOf(o).every((x) => issueState(x) === '已领齐'))
    mrpsOf(o).filter((m) => m.status === '已确认').forEach((m) => { m.status = '已关闭'; act(m, '加工单都已领齐料，自动关闭') })
  return d
}
// 回货：lines = { 'KQ|S': { qty, def, note } }；wh 收货仓（成品仓，只记录）
function registerReturn(p, f) {
  if (!prodLive(p)) fail('加工单审核后才能登记回货')
  if (f.date && f.date > clock.today) fail('回货日期不能晚于今天')
  const errs = [], got = []
  p.lines.forEach((l) => {
    const x = f.lines[l.color + '|' + l.size]
    const q = +x?.qty || 0, d = +x?.def || 0
    if (!q) return
    if (d > q) errs.push(`${l.color}-${l.size}：次品数不能大于回货数`)
    const cap = Math.floor(l.qty * (1 + OVER))
    if (l.ret + q > cap) errs.push(`${p.style}-${l.color}-${l.size}：加工 ${l.qty} 件，已回 ${l.ret}，最多还能回 ${Math.max(0, cap - l.ret)} 件`)
    got.push({ l, q, d, note: x?.note || '' })
  })
  if (!got.length) errs.push('至少登记一个数量')
  if (errs.length) fail('回货没登记上', errs)
  got.forEach(({ l, q, d }) => { l.ret += q; l.def += d })
  const r = add(db.returns, { no: nextNo('HH'), prod: p.no, order: p.order, style: p.style, factory: p.factory, date: f.date || clock.today, wh: f.wh || 'CP01', logistics: f.logistics || '', lines: got.map(({ l, q, d, note }) => ({ color: l.color, size: l.size, qty: q, def: d, note })), photos: f.photos || [], by: db.me, status: '已登记', log: [] })
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
// 种子按开关演：TRADE 开（代工）用 a，关（只做自营）用 b；单号、款、数量两边一样
const ifTrade = (a, b = {}) => (TRADE ? a : b)
const label = (sku, extra) => ({ part: extra.part, mat: sku, mode: '固定', sku, um: '统一', use: 1, loss: 1, src: '我方采购', ...extra })

function seed() {
  as('u4', '2026-08-20', () => {
    createStyle({ code: '26QJ012', name: '短款工装夹克', cat: 'J', year: 2026, season: 'Q', wave: '第 2 波', tag: '休闲', colors: ['HE', 'KQ'], tagPrice: 699, cost: 260, factory: 'GYS0005', ...ifTrade({ source: '客户来样', customer: 'KH0002', custNo: 'NF-J2611' }), status: '启用',
      patterner: 'u8', mainMat: 'ML0018', product: '夹克', comp: '65% 棉 35% 聚酯纤维', standard: 'GB/T 2665-2017 女西服、大衣', safety: 'B 类', launch: '2026-09-25' })
    createStyle({ code: '26QW008', name: '圆领针织衫', cat: 'W', year: 2026, season: 'Q', wave: '第 3 波', tag: '通勤', colors: ['MB', 'HH'], tagPrice: 399, cost: 150, factory: 'GYS0004', status: '启用' })
    createStyle({ code: '26DS021', name: '连帽卫衣', cat: 'S', year: 2026, season: 'D', wave: '第 1 波', tag: '运动', colors: ['HE', 'HH', 'WL'], tagPrice: 459, cost: 120, factory: 'GYS0004', ...ifTrade({ source: '客户来样', customer: 'KH0003' }), status: '启用',
      gender: '中性', mainMat: 'MZ0003', product: '卫衣', comp: '100% 棉', standard: 'FZ/T 73020-2019 针织休闲服装', safety: 'B 类' })
    createStyle({ code: '27CF001', name: '女式风衣', cat: 'F', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['KQ', 'ZQ', 'HE'], tagPrice: 899, cost: 330, factory: 'GYS0004', status: '启用',
      series: '城市通勤', patterner: 'u8', mainMat: 'ML0012', product: '风衣', comp: '面料：100% 棉；里料：100% 聚酯纤维', standard: 'GB/T 2665-2017 女西服、大衣', safety: 'B 类', launch: '2027-02-20', note: '大身面料对格，' + ifTrade('样衣已过客户确认', '样衣已确认') })
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
  // 新款号规则（甲方 11-11）：许然（设计师编号 07）11-06 建的款自动编成 FS2611060701；上面 27CF001 这些当作导入的历史款号，不改
  as('u4', '2026-11-06', () => createStyle({ big: 'FS', name: '落肩短风衣', cat: 'F', year: 2027, season: 'C', wave: '第 2 波', tag: '通勤', colors: ['YM', 'HE'], tagPrice: 829, cost: 300, factory: 'GYS0004', mainMat: 'ML0018', product: '风衣' }))
  const labels = [label('BX0001', { part: '主唛' }), label('BX0002', { part: '洗唛' }), label('BP0001', { part: '吊牌' }), label('BD0001', { part: '包装袋' })]
  const thread = { part: '缝纫线', mat: 'FX0002', mode: '固定', sku: 'FX0002', um: '统一', use: 0.3, loss: 0, src: '工厂自备' }
  const cf001 = (m) => [
    { part: '大身', mat: 'ML0012', mode: '随款色', map: { KQ: 'ML0012-KQ', ZQ: 'ML0012-ZQ', HE: 'ML0012-HE' }, um: '分码', uses: SZ(2.25, m, 2.45, 2.55, 2.65), loss: 3, src: '我方采购', supplier: 'GYS0001', note: '注意对格' },
    { part: '里布', mat: 'ML0015', mode: '随款色', map: { KQ: 'ML0015-MB', ZQ: 'ML0015-HE', HE: 'ML0015-HE' }, um: '统一', use: 1.6, loss: 2, src: ifTrade('客供', '我方采购') },
    { part: '纽扣', mat: 'FK0021', mode: '随款色', map: { KQ: 'FK0021-KQ', ZQ: 'FK0021-HE', HE: 'FK0021-HE' }, um: '统一', use: 8, loss: 2, src: '我方采购', supplier: 'GYS0002' },
    ...labels.map((x) => ({ ...x })), { ...thread },
  ]
  const cfOps = [['裁剪', '裁剪', 180, 2.5, '格子对齐后再开裁，左右前片对条对格'], ['车缝', '做领、上领', 420, 6, '领尖左右对称，止口 0.1cm 明线'], ['车缝', '做袖、上袖', 480, 6.5, '袖山吃势均匀，不起皱'], ['车缝', '前后片拼合、做门襟', 600, 9, '门襟顺直，不起涟'], ['车缝', '装里布', 420, 6, '里布下摆留 1cm 坐势'], ['整烫', '整烫', 240, 3, '面料不能烫出极光，垫布熨烫'], ['后道', '钉扣、剪线、查衫', 240, 3], ['包装', '挂吊牌、折叠入袋', 120, 2]]
  const ops = (a) => a.map(([cat, name, sec, price, req]) => ({ cat, name, req: req || '', sec, price, note: '' }))
  as('u4', '2026-09-10', () => {
    const cf = createTechpack('27CF001', cf001(2.3), ops(cfOps))
    Object.assign(cf, { util: 84, req: { text: '缝份：大身 1cm，领、门襟 0.8cm\n针距：明线 3cm 12 针，暗线 3cm 13 针\n大身对条对格，左右前片、袖子格子对齐\n里布下摆比面布短 2cm，坐势 1cm', pics: [] } })
    publishTp(cf)
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
    gradeRow(v2.spec.rows[0], sizesOf('W5'), 'M', 110)
    v2.note = 'M 码大身用量 2.30 → 2.35（放码后实测）；衣长各码加长 2cm'
    publishTp(v2)
  })
  // 期初库存、客供料（TRADE 关着时没有客供，这批黑色里布算自有，并进期初）
  as('u5', '2026-10-01', () => postStockDoc(createStockDoc({ type: '其他入库', reason: '期初', to: 'WL01', lines: [
    ['ML0012-KQ', 120], ['ML0012-ZQ', 60], ['ML0012-HE', 300], ['ML0015-MB', 80], ['FK0021-HE', 3000], ['FK0021-KQ', 1000], ['FL0031-HE-15', 300],
    ['BX0001', 3000], ['BX0002', 3000], ['BP0001', 2500], ['BD0001', 2500], ['FX0002', 200], ...ifTrade([], [['ML0015-HE', 200]]),
  ].map(([sku, qty]) => ({ sku, qty, price: skuPrice(sku) / matOfSku(sku).factor })) })))
  if (TRADE) as('u5', '2026-10-28', () => postStockDoc(createStockDoc({ type: '客供入库', partner: 'KH0001', to: 'WL01', lines: [{ sku: 'ML0015-HE', qty: 200, lot: 'G2610-07' }] })))

  // 订单 1：卫衣 900 件，已逾期、部分回货
  const o4 = as('u3', '2026-09-28', () => createOrder({ ...ifTrade({ type: '客户订单', customer: 'KH0003', custNo: 'BF-20260928' }, { type: '自营备货', brand: 'ZJ Basic' }), due: '2026-11-05', lines: mxLines('26DS021', { HE: SZ(60, 110, 120, 80, 30), HH: SZ(40, 80, 90, 60, 30), WL: SZ(30, 50, 60, 40, 20) }, 128) }))
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

  // 订单 2：夹克 500 件，拉链缺料、裁剪延期、改过交期
  const o2 = as('u3', '2026-10-12', () => createOrder({ ...ifTrade({ type: '客户订单', customer: 'KH0002', custNo: 'NF-PO-1012' }, { type: '自营备货', brand: '织间' }), due: '2026-11-25', lines: mxLines('26QJ012', { HE: SZ(60, 90, 90, 40, 20), KQ: SZ(40, 60, 60, 30, 10) }, 210) }))
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
  as('u3', '2026-10-20', () => createChange(o2, { due: '2026-11-30', ...ifTrade({ reason: '客户门店开业推迟，交期顺延 5 天', party: '客户' }, { reason: '门店开业推迟，交期顺延 5 天', party: '我方' }) }))
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
  // 异常要带照片（PRD §6.7.4）：缺料拍的是到货的拉链箱，SVG 画的示意图
  const zipBox = '<rect width="100" height="75" fill="#d9d4c8"/><path d="M14 30h72v36H14z" fill="#b89263"/><path d="M14 30l8-10h56l8 10z" fill="#c9a576"/>'
    + [0, 1, 2, 3, 4].map((i) => `<path d="M${22 + i * 13} 26v34" stroke="#2a2a2a" stroke-width="2.4"/><path d="M${22 + i * 13} 26v34" stroke="#9a9a9a" stroke-width="1" stroke-dasharray="1 1.2"/>`).join('')
    + '<rect x="58" y="54" width="26" height="9" rx="1.5" fill="#fff" opacity=".9"/><text x="71" y="60.6" text-anchor="middle" font-size="5.5" fill="#333" font-family="sans-serif">300 条</text>'
  const zipPhoto = { id: 'P-seed-zip', name: '拉链到货.jpg', at: '2026-11-05', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 75" width="800" height="600">${zipBox}</svg>`) }
  as('u1', '2026-11-05', () => reportIssue(p2, { type: '缺料', desc: '拉链只到 300 条，剩下 205 件等拉链', newDate: '2026-11-22', party: '供应商', photos: [zipPhoto] }))
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
  const o1 = as('u3', '2026-11-05', () => createOrder({ ...ifTrade({ type: '客户订单', customer: 'KH0001', custNo: 'HD-2027SS-018', ship: '送客户杭州仓，按色按码装箱' }, { type: '自营备货', brand: '织间', ship: '送成品仓，按色按码装箱' }), due: '2027-03-20', lines: mxLines('27CF001', { KQ: SZ(80, 120, 120, 60, 20), ZQ: SZ(80, 120, 120, 60, 20), HE: SZ(80, 120, 120, 60, 20) }, 365) }))
  as('u3', '2026-11-05', () => submitOrder(o1))
  as('u6', '2026-11-05', () => auditOrder(o1, '2027-03-20'))
  // 订单 5：待审核；订单 6：草稿
  const o6 = as('u3', '2026-11-06', () => createOrder({ ...ifTrade({ type: '客户订单', customer: 'KH0001', custNo: 'HD-2026W-033' }, { type: '补单', brand: '织间' }), due: '2026-12-20', lines: mxLines('26QW008', { MB: SZ(40, 70, 70, 40, 20), HH: SZ(40, 70, 70, 40, 20) }, 96) }))
  as('u3', '2026-11-06', () => submitOrder(o6))
  as('u3', '2026-11-07', () => createOrder({ type: '翻单', ...ifTrade({ customer: 'KH0002' }, { brand: '织间' }), due: '2026-12-25', lines: mxLines('26QJ012', { HE: SZ(40, 60, 60, 30, 10) }, 210) }))
  as('u5', '2026-11-07', () => createStockDoc({ type: '其他出库', reason: '报废', from: 'WL01', lines: [{ sku: 'FX0002', qty: 5 }] }))
}
seed()

/* ---------- 自检：打开 index.html?selftest 跑 PRD §4.3 的例子 ---------- */
function selftest() {
  const out = [], ok = (name, cond, got) => out.push({ name, ok: !!cond, got })
  const err = (fn) => { try { fn(); return '' } catch (e) { return e.message + ' ' + e.list.join('；') } }
  const o = order('DD202611050001') // 订单 4，PRD 贯穿例子
  // 补货提醒（§6.6.7，函数在 5e-price.js）：卡其布库存 120、安全库存 300 → 缺 180；生成备货采购单审核后算上在途，不再提醒。撤掉，不影响下面算料
  const rk = reorderRows().find((r) => r.sku === 'ML0012-KQ')
  ok('补货：卡其布低于安全库存，缺 180、建议补 180', rk?.gap === 180 && rk.qty === 180, rk)
  const [rp] = reorderToPurchases([rk])
  ok('补货：一键生成备货采购单，单价取档案价 18.5', rp.type === '备货采购' && rp.lines[0].qty === 180 && rp.lines[0].price === 18.5, rp.lines[0])
  doAction('purchase', rp, '提交'); doAction('purchase', rp, '审核')
  ok('补货：审核后算上在途，不再提醒', !reorderRows().some((r) => r.sku === 'ML0012-KQ'), reorderRows().map((r) => r.sku))
  doAction('purchase', rp, '反审核'); removeDraft(db.purchases, rp)
  const m = createMrp([o.no])
  const kq = m.rows.find((r) => r.sku === 'ML0012-KQ')
  ok('算料：卡其布毛需求 962', kq.gross === 962, kq.gross)
  ok('算料：需求 990.86', kq.need === 990.86, kq.need)
  ok('算料：可用 120、净需求 870.86、建议 871', kq.avail === 120 && kq.net === 870.86 && kq.purQty === 871, [kq.avail, kq.net, kq.purQty])
  ok(TRADE ? '算料：里布客供，不用买' : '算料：里布我方采购（不做客供）', m.rows.filter((r) => r.sku.startsWith('ML0015')).every((r) => r.src === (TRADE ? '客供' : '我方采购')), m.rows.filter((r) => r.sku.startsWith('ML0015')).map((r) => r.src))
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
  ok('变更：改到小于已排产被拒', err(() => createChange(o, { items: [{ k: '27CF001|KQ|M', to: 100 }], reason: '减量' })).includes('先调整加工单'))
  registerReturn(p, { lines: Object.fromEntries(p.lines.map((l, i) => [l.color + '|' + l.size, { qty: l.qty, def: i === 0 ? 4 : 0 }])) })
  ok('回货 400 件 → 加工单完成', p.status === '已完成', p.status)
  ok('回货超量被拒', err(() => registerReturn(p, { lines: { 'KQ|M': { qty: 10 } } })) !== '')
  ok('黑名单供应商不能下采购单', err(() => createPurchase({ supplier: 'GYS0008', lines: [{ sku: 'ML0012-HE', qty: 1, price: 1 }] })).includes('黑名单'))
  ok('资料包缺色不能生效', err(() => publishTp(tpsOf('27CK002')[0])).includes('藏青未配料'))
  // 贸易代工开关：关着时补单、翻单都不选客户，出入库没有客供入库
  const e = err(() => removeDraft(db.orders, createOrder({ type: '补单', due: '2026-12-31', lines: [{ style: '27CF001', color: 'KQ', size: 'M', qty: 10 }] })))
  ok(TRADE ? '补单要选客户' : '补单不选客户也能建', TRADE ? e.includes('要选客户') : e === '', e)
  ok(TRADE ? '出入库有客供入库' : '出入库没有客供入库', ('客供入库' in STOCK_TYPES) === TRADE)
  // 研发 / 生产 BOM（§6.4.1）：订单只认生产 BOM；研发 BOM 转生产 BOM 记来源，单价按价格档案重新取
  const s9 = style('26DY009')
  const d9 = createTechpack(s9.code, [{ part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 5, loss: 2, src: '我方采购', supplier: 'GYS0002', price: 0.4 }], [{ cat: '车缝', name: '整件加工（估）', req: '', sec: 1000, price: 20, note: '' }], '自检估价', null, '研发')
  publishTp(d9)
  const o9 = createOrder({ type: '自营备货', due: '2027-03-31', lines: [{ style: s9.code, color: s9.colors[0], size: sizesOf(s9.sizeGroup)[0], qty: 10 }] })
  ok('研发 BOM：订单锁研发 BOM 被拦，提示先转生产 BOM', ((e) => e.includes('没有生效的生产 BOM') && e.includes('转生产 BOM'))(err(() => submitOrder(o9))))
  removeDraft(db.orders, o9)
  const p9 = toProdTp(d9)
  ok('研发 BOM：转成生产 BOM V1 草稿，记来源，单价按档案重新取', p9.status === '草稿' && p9.ver === 1 && p9.source?.stage === '研发' && p9.source.ver === 1 && p9.bom[0].price == null && rowRefPrice(p9.bom[0]) === 0.35 && d9.status === '已生效', [p9.status, p9.source, rowRefPrice(p9.bom[0])])
  // 价格档案（§6.10，函数在 5e-price.js）：验收 1-4
  ok('价格档案：采购单默认取档案现价 18.5', createPurchase({ supplier: 'GYS0001', lines: [{ sku: 'ML0012-ZQ', qty: 100 }] }).lines[0].price === 18.5)
  ok('价格档案：改成 19 不写改价原因被拦', err(() => createPurchase({ supplier: 'GYS0001', lines: [{ sku: 'ML0012-ZQ', qty: 100, price: 19 }] })).includes('改价原因'))
  const pa = createPurchase({ supplier: 'GYS0001', lines: [{ sku: 'ML0012-ZQ', qty: 100, price: 19, priceWhy: '原料涨价' }] })
  doAction('purchase', pa, '提交'); doAction('purchase', pa, '审核')
  const pr = curMatRec('GYS0001', 'ML0012', 'ML0012-ZQ')
  ok('价格档案：审核后记一条 19，来源 = 这张采购单，原因带过来', pr.price === 19 && pr.src === pa.no && pr.why === '原料涨价', [pr.price, pr.src, pr.why])
  ok('价格档案：之后新采购单默认 19', createPurchase({ supplier: 'GYS0001', lines: [{ sku: 'ML0012-HE', qty: 100 }] }).lines[0].price === 19)
  as('u2', clock.today, () => recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0012', sku: 'ML0012-KQ', price: 19.5 }))
  ok('价格档案：卡其单独定 19.5，其他颜色还是 19', poDefaultPrice('GYS0001', 'ML0012-KQ') === 19.5 && poDefaultPrice('GYS0001', 'ML0012-ZQ') === 19, [poDefaultPrice('GYS0001', 'ML0012-KQ'), poDefaultPrice('GYS0001', 'ML0012-ZQ')])
  ok('价格档案：再调卡其不写原因被拦', as('u2', clock.today, () => err(() => recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0012', sku: 'ML0012-KQ', price: 20 }))).includes('调价原因'))
  ok('价格档案：跟单不能录物料价、采购不能录加工价，管理层只看（PRD §3.1）', as('u1', clock.today, () => err(() => recordPrice({ kind: '物料价', sup: 'GYS0001', mat: 'ML0012', price: 21, why: 'x' }))).includes('采购能录')
    && as('u2', clock.today, () => err(() => recordPrice({ kind: '加工价', sup: 'GYS0004', style: '27CF001', mode: '清加工', price: 39, why: 'x' }))).includes('生产跟单能录') && as('u6', clock.today, () => !canEditPrice()))
  ok('加工价：锦程 × 27CF001 × 清加工 38，包工包料不带 38',curProcPrice('GYS0004', '27CF001', '清加工') === 38 && curProcPrice('GYS0004', '27CF001', '包工包料') == null)
  // 在途自动扣（§6.6.2）：27CF001 黑色 120 件，审核那张待审核的备货采购单（黑布 500 米，11-26 到）
  const ob = createOrder({ type: '自营备货', due: '2027-03-31', lines: ['S', 'M', 'L', 'XL', 'XXL'].map((z, i) => ({ style: '27CF001', color: 'HE', size: z, qty: [20, 30, 30, 20, 20][i] })) })
  submitOrder(ob); auditOrder(ob, '2027-03-31')
  const bp = db.purchases.find((x) => x.type === '备货采购' && x.supplier === 'GYS0001' && x.status === '待审核' && x.lines[0].sku === 'ML0012-HE')
  doAction('purchase', bp, '审核')
  const mb = createMrp([ob.no]), he = mb.rows.find((r) => r.sku === 'ML0012-HE')
  ok('在途：赶得上开裁的在途自动扣，够了就不用买', he.useTransit > 0 && he.useTransit === rnd(he.need - he.useStock, 3) && he.purQty === 0 && he.transitUse[0]?.po === bp.no, [he.useStock, he.useTransit, he.purQty])
  move('mrp', mb, '确认')
  const mb2 = createMrp([ob.no]), he2 = mb2.rows.find((r) => r.sku === 'ML0012-HE')
  ok('在途：确认后被占住，下一张算料只剩没用的，不重复扣', he2.availTransit === rnd(500 - he.useTransit, 3), [he2.availTransit, he.useTransit])
  removeDraft(db.mrps, mb2)
  bp.lines[0].expect = addDays(mb.needDate, 5)
  const mb3 = createMrp([ob.no]), he3 = mb3.rows.find((r) => r.sku === 'ML0012-HE')
  ok('在途：预计到货晚于要料日期的不扣，列成"赶不上的在途"，照样要买', he3.availTransit === 0 && he3.lateTransit === rnd(500 - he.useTransit, 3) && he3.purQty > 0, [he3.availTransit, he3.lateTransit, he3.purQty])
  removeDraft(db.mrps, mb3)
  bp.lines[0].expect = null
  // 赶不上开裁（§6.6.3）：排单后要料日期 = 加工单的裁剪计划开始；排单改了加工价要写原因
  const blk = { style: '27CF001', factory: 'GYS0004', mode: '清加工', price: 40, due: '2027-03-10', qty: { 'HE|S': 20, 'HE|M': 30, 'HE|L': 30, 'HE|XL': 20, 'HE|XXL': 20 } }
  ok('加工价：排单改了单价不写原因被拦', err(() => scheduleOrder(ob, [blk])).includes('改价原因'))
  const [pb] = scheduleOrder(ob, [{ ...blk, price: 38 }])
  doAction('production', pb, '提交'); doAction('production', pb, '审核')
  const cut = prodCutDate(pb), arr = () => arrivals().find((x) => x.p === bp && x.i === 0)
  bp.lines[0].expect = addDays(cut, 1)
  ok('到料：预计到货比开裁晚 1 天，标出"赶不上开裁"', arr()?.need === cut && arr().cutLate === 1, [cut, arr()?.need, arr()?.cutLate])
  pb.nodes.find((n) => n.name === '裁剪').ps = addDays(cut, 2)
  ok('到料：开裁往后挪了就不标', arr()?.cutLate === 0, arr()?.cutLate)
  // 甲方 11-11 反馈：款号规则、设计师编号、供应商准入审批、关键信息改动、企查查、合同（后几个的函数在 7-pages-master.js、7b-contracts.js）
  ok('款号：许然今天第一个 = FS2611080701', nextStyleCode('FS', 'u4') === 'FS2611080701', nextStyleCode('FS', 'u4'))
  const ns = as('u4', clock.today, () => createStyle({ name: '自检款', cat: 'T', year: 2027, season: 'C', colors: ['HE'] }))
  ok('款号：建一个后接着编 02，鞋子另起 01', ns.code === 'FS2611080701' && nextStyleCode('FS', 'u4') === 'FS2611080702' && nextStyleCode('XZ', 'u4') === 'XZ2611080701', [ns.code, nextStyleCode('FS', 'u4')])
  createStyle({ code: 'FS2611080799', name: '自检满号', cat: 'T', year: 2027, season: 'C', colors: ['HE'], designer: 'u4' })
  ok('款号：同一天编到 99 提示满了', err(() => nextStyleCode('FS', 'u4')).includes('满了'))
  ok('款号：设计师没编号不能自动编，提示去设置 › 用户', err(() => createStyle({ name: 'x', cat: 'T', year: 2027, season: 'C', colors: ['HE'], designer: 'u6' })).includes('去设置 › 用户里给 赵启 填设计师编号'))
  const u4 = db.users.find((u) => u.id === 'u4'), u8 = db.users.find((u) => u.id === 'u8')
  ok('设计师编号：进过款号的不能改', err(() => setDesignerNo(u4, '17')).includes('不能改'), u4.dno)
  ok('设计师编号：不能给别人，没进过款号的能改', err(() => setDesignerNo(u8, '07')).includes('07') && err(() => { setDesignerNo(u8, '08'); setDesignerNo(u8, '18') }) === '' && u8.dno === '18', u8.dno)
  ok('准入：资料没补齐不能申请，缺哪项列哪项', ((e) => e.includes('营业执照') && e.includes('开户行') && e.includes('账号'))(err(() => doAction('supplier', sup('GYS0006'), '申请准入'))))
  const ns2 = as('u2', clock.today, () => {
    const s = createSupplier({ name: '自检织带有限公司', short: '自检织带', cats: ['辅料商'], credit: '91330100MA0000999X', contact: '王经理', bank: '某银行', account: '6222 0000 0000 0999' })
    saveCert(s, null, { type: '营业执照', until: '2030-01-01', file: { id: 'x', name: '营业执照.jpg' } })
    doAction('supplier', s, '申请准入')
    return s
  })
  ok('准入：自己提的不能自己批，没审批权限的也不能批', as('u2', clock.today, () => err(() => doAction('supplier', ns2, '通过'))).includes('自己') && as('u7', clock.today, () => err(() => doAction('supplier', ns2, '通过'))).includes('没有供应商审批权限'))
  as('u6', clock.today, () => doAction('supplier', ns2, '通过'))
  ok('准入：换管理层批，变合作中、能下采购单', ns2.status === '合作中' && canUseSupplier(ns2.code, '采购'), ns2.status)
  ok('信用代码：格式不对、重复都被拦', err(() => createSupplier({ name: 'a', short: 'a', cats: ['其他'], credit: '91330100MA000099' })).includes('格式不对') && err(() => createSupplier({ name: 'b', short: 'b', cats: ['其他'], credit: '91330100MA0000999X' })).includes('自检织带'))
  const g1 = sup('GYS0001'), bank0 = g1.bank
  as('u2', clock.today, () => updateSupplier(g1, { name: g1.name, short: g1.short, cats: g1.cats, bank: '某银行杭州分行', contact: '吴经理' }))
  ok('关键信息改动：开户行挂起待批，联系人直接生效', g1.bank === bank0 && g1.contact === '吴经理' && g1.pending?.items[0]?.k === 'bank', [g1.bank, g1.pending])
  ok('关键信息改动：自己提的不能自己批', as('u2', clock.today, () => err(() => approveSupChange(g1))).includes('自己'))
  as('u6', clock.today, () => approveSupChange(g1))
  ok('关键信息改动：另一个人批准后才生效', g1.bank === '某银行杭州分行' && !g1.pending, g1.bank)
  ok('企查查：每周复查比上次多出一条，记成新增风险', sup('GYS0005').riskNew?.ids.length === 1 && sup('GYS0005').riskChecks.at(-1).result === '高风险', sup('GYS0005').riskNew)
  const g2 = sup('GYS0002'), r1 = riskCheck(g2, '手动'), n1 = g2.riskChecks.length, r2 = riskCheck(g2, '手动')
  ok('企查查：同一家一天只查一次，再查给当天那次的结果', r2.again && r2.at === r1.at && g2.riskChecks.length === n1, [r2.again, n1, g2.riskChecks.length])
  const g6 = sup('GYS0006')
  g6.certs.push({ type: '营业执照', until: '2030-01-01' })
  Object.assign(g6, { bank: '某银行桐乡支行', account: '6222 0000 0000 0606' })
  const r6 = as('u1', clock.today, () => doAction('supplier', g6, '申请准入'))
  ok('企查查：查询失败只记一条，不拦申请准入', r6.failed && g6.status === '准入审批中' && g6.riskChecks.length === 1, [r6.result, g6.status])
  const po2 = createPurchase({ supplier: 'GYS0002', lines: [{ sku: 'FK0021-HE', qty: 1000, price: 0.35 }] })
  doAction('purchase', po2, '提交'); doAction('purchase', po2, '审核')
  const tpl = db.contractTpls.find((t) => t.kind === '采购合同' && t.status === '启用')
  const c1 = createContract('purchase', po2, tpl.id)
  ok('合同：一张单据同时只有一份有效合同', err(() => createContract('purchase', po2, tpl.id)).includes(c1.no))
  ok('合同：有有效合同的采购单不能反审核', err(() => doAction('purchase', po2, '反审核')).includes('先作废合同 ' + c1.no))
  voidContract(c1, '金额写错，重出')
  ok('合同：作废后能重出，明细展开成表、金额大写', ((c) => c.html.includes('<table') && c.html.includes(rmbUpper(poAmt(po2))) && c.tpl.ver === tpl.ver)(createContract('purchase', po2, tpl.id)))
  ok('金额大写', rmbUpper(12345.6) === '壹万贰仟叁佰肆拾伍元陆角整' && rmbUpper(100010000) === '壹亿零壹万元整' && rmbUpper(1000.05) === '壹仟元零伍分', [rmbUpper(12345.6), rmbUpper(100010000), rmbUpper(1000.05)])
  ok('合同模板：有系统不认识的占位符不让保存，列出是哪几个', ((e) => e.includes('{{乙方传真}}') && e.includes('{{开票抬头}}'))(err(() => importTpl({ kind: '采购合同', name: '自检模板', file: '自检.docx', body: '{{甲方}} {{乙方传真}} {{开票抬头}}' }))))
  return out
}
window.SCM = { db, FLOWS, doAction, selftest }
