/* ============================================================
 * 资料 › 合同（甲方 11-11 新增，排期待定）：合同模板 + 从已审核的采购单、生产单生成合同
 * - 生产单（甲方 11-14 拆成外发加工单、成品采购单）都出「加工合同」：同一套模板，{{单据名称}} 写的是哪种单；
 *   成品采购要另一份合同文本的，导一个加工合同模板专门给它用
 * - 模板：导入 Word（原型只记 .docx 文件名，正文贴进来），占位符写成 {{甲方}}；系统不认识的占位符不让保存。
 *   更新 = 出一个新版本，旧版本自动停用但能看；停用的模板生成不了新合同。
 * - 合同：审核过的单据 + 合作中的供应商 + 填了信用代码才能生成；一张单据同时只有一份有效（没作废）合同。
 *   生成时把模板版本和填好的正文存成快照，以后改模板、改供应商资料都不影响已经出的合同。
 *   导出 Word → 线下签字盖章 → 传签回件变「已签回」；写错了写原因作废，再从单据上重出。
 * - 采购单、生产单详情只放按钮和合同列表（5c、5d），逻辑都在这里。
 * ============================================================ */
FLOWS.contract = { 签回: ['待签回', '已签回'], 作废: [['待签回', '已签回'], '已作废'] }
const CT_KINDS = { purchase: '采购合同', production: '加工合同' }
const CT_DOC = { purchase: '采购单', production: '生产单' }
const ctDocName = (kind, d) => (kind === 'purchase' || !d ? CT_DOC[kind] : prodKind(d))
const CT_OK = { purchase: ['已审核', '部分到货', '已到齐', '已结案'], production: ['已下单', '生产中', '部分回货', '已完成', '已结案'] }
const ctDocOf = (kind, no) => (kind === 'purchase' ? purchase(no) : production(no))
const ctDocTo = (c) => (c.kind === 'purchase' ? '/purchases/' + c.docNo : production(c.docNo) ? prodLink(production(c.docNo)) : '/production/' + c.docNo)
const ctSupOf = (kind, d) => sup(kind === 'purchase' ? d.supplier : d.factory)
const liveContractOf = (no) => db.contracts.find((c) => c.docNo === no && c.status !== '已作废')
const contractsOfDoc = (no) => db.contracts.filter((c) => c.docNo === no).slice().reverse()
const contractsOfSupplier = (code) => db.contracts.filter((c) => c.supplier === code).slice().reverse()
const contract = (no) => db.contracts.find((c) => c.no === no)
const escH = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c])

// 金额大写（人民币）：12345.6 → 壹万贰仟叁佰肆拾伍元陆角整
function rmbUpper(v) {
  const n = Math.round(Math.abs(+v || 0) * 100)
  if (!n) return '零元整'
  const D = '零壹贰叁肆伍陆柒捌玖', U = ['', '拾', '佰', '仟'], B = ['', '万', '亿', '万亿']
  const four = (g) => { let out = '', zero = false; for (let j = 3; j >= 0; j--) { const d = Math.floor(g / 10 ** j) % 10; if (d) { out += (zero ? '零' : '') + D[d] + U[j]; zero = false } else if (out) zero = true } return out }
  const yuan = Math.floor(n / 100), jiao = Math.floor(n / 10) % 10, fen = n % 10
  const gs = []
  for (let y = yuan; y > 0; y = Math.floor(y / 10000)) gs.push(y % 10000)
  let s = '', gap = false
  for (let i = gs.length - 1; i >= 0; i--) {
    if (!gs[i]) { gap = true; continue }
    if (s && (gap || gs[i] < 1000)) s += '零'
    s += four(gs[i]) + B[i]
    gap = false
  }
  if (s) s += '元'
  if (jiao) s += D[jiao] + '角'
  else if (fen && s) s += '零'
  return s + (fen ? D[fen] + '分' : '整')
}

/* ---------- 占位符：模板里能用哪些，从单据和供应商上取什么 ----------
 * 和 PRD §6.11.1 的表是同一份清单，改了两边一起改。
 * k = 写在模板里的名字；only = 只有这类合同能用；f(x)：x = { kind, d 单据, s 供应商, co 甲方主体, no 合同号 } */
const CT_VARS = [
  { k: '合同编号', t: '生成时自动编，HT + 日期 + 4 位', f: (x) => x.no },
  { k: '签订日期', t: '生成合同那天', f: () => clock.today },
  { k: '甲方', t: '单据上的主体公司全称', f: (x) => x.co?.name },
  { k: '甲方信用代码', t: '主体公司的统一社会信用代码', f: (x) => x.co?.credit },
  { k: '甲方地址', f: (x) => x.co?.addr },
  { k: '甲方电话', f: (x) => x.co?.phone },
  { k: '甲方经办人', t: '采购单的采购员 / 生产单的跟单员', f: (x) => userName(x.kind === 'purchase' ? x.d.buyer : x.d.merch) },
  { k: '乙方', t: '供应商全称', f: (x) => x.s.name },
  { k: '乙方信用代码', f: (x) => x.s.credit },
  { k: '乙方地址', f: (x) => x.s.addr },
  { k: '乙方联系人', f: (x) => x.s.contact },
  { k: '乙方电话', f: (x) => x.s.phone },
  { k: '乙方开户行', f: (x) => x.s.bank },
  { k: '乙方账号', f: (x) => x.s.account },
  { k: '单号', t: '采购单号 / 生产单号', f: (x) => x.d.no },
  { k: '单据名称', t: '采购单 / 外发加工单 / 成品采购单', f: (x) => ctDocName(x.kind, x.d) },
  { k: '下单日期', f: (x) => x.d.date },
  { k: '交货日期', f: (x) => x.d.due },
  { k: '结算方式', t: '单据上的结算方式：月结、现结、货到付款……', f: (x) => x.d.settle },
  { k: '账期', t: '供应商账期，如 30 天', f: (x) => (x.s.term != null ? x.s.term + ' 天' : '') },
  { k: '税率', t: '采购单取单据上的；生产单取供应商的', f: (x) => (x.kind === 'purchase' ? x.d.tax : x.s.tax) },
  { k: '发票类型', f: (x) => x.s.invoice },
  { k: '备注', t: '单据备注', f: (x) => x.d.note },
  { k: '含税', only: 'purchase', t: '含税 / 不含税', f: (x) => (x.d.taxIncl === false ? '不含税' : '含税') },
  { k: '交货地点', only: 'purchase', t: '收货仓库和地址', f: (x) => [whName(x.d.wh), wh(x.d.wh)?.addr].filter(Boolean).join('，') },
  { k: '款号', only: 'production', f: (x) => x.d.style },
  { k: '款式名称', only: 'production', f: (x) => styleName(x.d.style) },
  { k: '加工方式', only: 'production', t: '清加工、半包（外发加工单）/ 包工包料（成品采购单）', f: (x) => x.d.mode },
  { k: '加工单价', only: 'production', t: '元 / 件；成品采购单是成品单价（含料）', f: (x) => fmNum(x.d.price) },
  { k: '条款', only: 'production', t: '生产单上写的条款', f: (x) => x.d.terms },
  { k: '明细', t: '单独占一行，展开成明细表：采购单每行一个物料 SKU，生产单是颜色 × 尺码', f: (x) => ctDetail(x) },
  { k: '数量合计', t: '采购单按采购单位合计；生产单是件数', f: (x) => (x.kind === 'purchase' ? fq(poQty(x.d)) : prodQty(x.d) + ' 件') },
  { k: '金额', t: '数字，如 12,345.60', f: (x) => fmNum(x.kind === 'purchase' ? poAmt(x.d) : prodAmt(x.d)) },
  { k: '金额大写', f: (x) => rmbUpper(x.kind === 'purchase' ? poAmt(x.d) : prodAmt(x.d)) },
]
const fmNum = (v) => (+v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const ctKindKey = (name) => Object.keys(CT_KINDS).find((k) => CT_KINDS[k] === name)
const varsOf = (kindName) => CT_VARS.filter((v) => !v.only || CT_KINDS[v.only] === kindName)
const PH_RE = /\{\{\s*([^{}]*?)\s*\}\}/g
const phText = (k) => '{{' + k + '}}'
// 模板正文里系统不认识的占位符（含"这类合同用不了"的），原样列出来
function tplUnknown(kindName, body) {
  const ok = new Set(varsOf(kindName).map((v) => v.k)), all = new Set(CT_VARS.map((v) => v.k))
  return [...new Set([...String(body || '').matchAll(PH_RE)].map((m) => m[1]))].filter((k) => !ok.has(k))
    .map((k) => `{{${k}}}` + (all.has(k) ? `（只有${CT_KINDS[CT_VARS.find((v) => v.k === k).only]}能用）` : ''))
}
function ctDetail(x) {
  const td = (v, c = '') => `<td${c ? ` class="${c}"` : ''}>${escH(v)}</td>`
  if (x.kind === 'purchase') {
    const rows = x.d.lines.map((l, i) => { const p = skuParts(l.sku); return `<tr>${td(i + 1)}${td(l.sku)}${td(p.name)}${td(p.color)}${td(p.spec)}${td(purUnit(l.sku))}${td(fq(l.qty), 'num')}${td(fmNum(l.price), 'num')}${td(fmNum(l.qty * l.price), 'num')}${td(l.due || x.d.due)}</tr>` }).join('')
    return `<table class="ct-tbl"><thead><tr><th>#</th><th>物料编码</th><th>名称</th><th>颜色</th><th>规格</th><th>单位</th><th>数量</th><th>单价（元）</th><th>金额（元）</th><th>交期</th></tr></thead><tbody>${rows}</tbody>`
      + `<tfoot><tr><td colspan="6">合计</td>${td(fq(poQty(x.d)), 'num')}<td></td>${td(fmNum(poAmt(x.d)), 'num')}<td></td></tr></tfoot></table>`
  }
  const sizes = [...new Set(x.d.lines.map((l) => l.size))], colors = [...new Set(x.d.lines.map((l) => l.color))]
  const q = (c, z) => sum(x.d.lines.filter((l) => l.color === c && (!z || l.size === z)), (l) => l.qty)
  const rows = colors.map((c) => `<tr>${td(colorName(c) + ' ' + c)}${sizes.map((z) => td(q(c, z) || '', 'num')).join('')}${td(q(c), 'num')}</tr>`).join('')
  return `<table class="ct-tbl"><thead><tr><th>颜色</th>${sizes.map((z) => `<th>${escH(z)}</th>`).join('')}<th>合计（件）</th></tr></thead><tbody>${rows}</tbody>`
    + `<tfoot><tr><td>合计</td>${sizes.map((z) => td(sum(x.d.lines.filter((l) => l.size === z), (l) => l.qty), 'num')).join('')}${td(prodQty(x.d), 'num')}</tr></tfoot></table>`
}
// 填模板：第一行当标题；单独一行的 {{明细}} 展开成表；取不到值的留下划线，打印后手填。返回 { html, miss }
function renderContract(tpl, x) {
  const miss = new Set()
  const val = (k) => {
    const v = CT_VARS.find((y) => y.k === k)?.f(x)
    if (k === '明细') return v
    if (v == null || v === '') { miss.add(k); return '<span class="ct-miss">＿＿＿＿</span>' }
    return `<span class="ct-v">${escH(v)}</span>`
  }
  const fill = (line) => { let out = '', at = 0; for (const m of line.matchAll(PH_RE)) { out += escH(line.slice(at, m.index)) + val(m[1]); at = m.index + m[0].length } return out + escH(line.slice(at)) }
  const lines = String(tpl.body).split(/\r?\n/)
  const first = lines.findIndex((l) => l.trim())
  const html = lines.map((l, i) => (!l.trim() ? '' : i === first ? `<h2 class="ct-title">${fill(l.trim())}</h2>` : /^\s*\{\{\s*明细\s*\}\}\s*$/.test(l) ? val('明细') : `<p>${fill(l)}</p>`)).join('\n')
  return { html, miss: [...miss] }
}

/* ---------- 模板 ---------- */
const tplOf = (id) => db.contractTpls.find((t) => t.id === id)
const tplVersions = (t) => db.contractTpls.filter((x) => x.kind === t.kind && x.name === t.name).sort((a, b) => b.ver - a.ver)
const tplUsed = (t) => db.contracts.filter((c) => c.tpl.id === t.id).length
const liveTpls = (kindName) => db.contractTpls.filter((t) => t.kind === kindName && t.status === '启用')
function checkTplBody(kindName, f) {
  if (!/\.docx$/i.test(f.file || '')) fail('要选一个 .docx 文件（Word 2007 以后的格式）；.doc 先在 Word 里另存为 .docx')
  if (!String(f.body || '').trim()) fail('正文要贴进来：在 Word 里全选、复制，粘贴到这里')
  const bad = tplUnknown(kindName, f.body)
  if (bad.length) fail(`模板里有 ${bad.length} 个系统不认识的占位符，不能保存`, [...bad, '改成页面上列的名字（区分大小写和空格），或者删掉'])
}
function importTpl(f) {
  if (!Object.values(CT_KINDS).includes(f.kind)) fail('选合同类型：采购合同 / 加工合同')
  const name = (f.name || '').trim()
  if (!name) fail('模板名称要填')
  if (db.contractTpls.some((t) => t.kind === f.kind && t.name === name)) fail(`已经有「${name}」，要换新内容请在那一行点「更新」，会出一个新版本`)
  checkTplBody(f.kind, f)
  const t = add(db.contractTpls, { id: 'T' + String(db.contractTpls.length + 1).padStart(2, '0'), kind: f.kind, name, ver: 1, file: f.file, body: f.body, note: f.note || '', status: '启用', at: clock.today, by: db.me, log: [] })
  act(t, `导入 ${f.file}`)
  return t
}
// 更新：出一个新版本，旧版本自动停用（已经用旧版本出的合同不受影响）
function updateTpl(t, f) {
  if (t.status !== '启用') fail(`V${t.ver} 已经停用；要更新请在在用的版本上点`)
  checkTplBody(t.kind, f)
  if (f.body === t.body && f.file === t.file) fail('文件和正文都没变')
  const n = add(db.contractTpls, { ...t, id: 'T' + String(db.contractTpls.length + 1).padStart(2, '0'), ver: t.ver + 1, file: f.file, body: f.body, note: f.note || '', status: '启用', at: clock.today, by: db.me, from: t.id, log: [] })
  t.status = '停用'
  act(t, `出了新版本 V${n.ver}，这一版自动停用`)
  act(n, `由 V${t.ver} 更新，导入 ${f.file}${f.note ? '：' + f.note : ''}`)
  return n
}
function toggleTpl(t) {
  if (t.status === '启用') { t.status = '停用'; act(t, '停用'); return }
  const on = tplVersions(t).find((x) => x !== t && x.status === '启用')
  if (on) fail(`「${t.name}」V${on.ver} 在用，旧版本不能再启用`, ['要回到旧内容：在 V' + on.ver + ' 上点「更新」，把旧正文贴进去'])
  t.status = '启用'
  act(t, '启用')
}

/* ---------- 合同 ---------- */
// 能不能出合同：不能就 fail，说清楚为什么
function checkContractable(kind, d) {
  if (!CT_OK[kind].includes(d.status)) fail(`${d.no} 现在是「${d.status}」，审核以后才能生成合同`)
  const s = ctSupOf(kind, d)
  if (s.status !== '合作中') fail(`${s.short} 现在是「${s.status}」，合作中的供应商才能出合同`)
  if (!s.credit) fail(`${s.short} 没填统一社会信用代码，合同上要写`, ['去供应商资料里补上（准入过的供应商改信用代码要审批）'])
  const live = liveContractOf(d.no)
  if (live) fail(`${d.no} 已经有一份有效合同 ${live.no}（${live.status}），一张单据同时只能有一份`, ['要重出先把它作废（写明原因）'])
}
const ctCtx = (kind, d, no) => ({ kind, d, s: ctSupOf(kind, d), co: db.companies.find((c) => c.code === d.company) || db.companies[0], no })
const canGenContract = (kind, d) => !!d && CT_OK[kind].includes(d.status) && !liveContractOf(d.no)
function createContract(kind, d, tplId) {
  checkContractable(kind, d)
  const t = tplOf(tplId)
  if (!t || t.kind !== CT_KINDS[kind]) fail('选一个' + CT_KINDS[kind] + '模板')
  if (t.status !== '启用') fail(`模板「${t.name}」V${t.ver} 已经停用，选在用的版本`)
  const no = nextNo('HT'), x = ctCtx(kind, d, no)
  const { html, miss } = renderContract(t, x)
  const c = add(db.contracts, {
    no, kind, kindName: CT_KINDS[kind], docNo: d.no, supplier: x.s.code, company: x.co.code, amt: kind === 'purchase' ? poAmt(d) : prodAmt(d),
    tpl: { id: t.id, name: t.name, ver: t.ver, file: t.file }, html, miss, status: '待签回', at: clock.today, by: db.me, signed: [], log: [],
  })
  act(c, `由${ctDocName(kind, d)} ${d.no} 生成，模板「${t.name}」V${t.ver}${miss.length ? '；留空手填：' + miss.join('、') : ''}`)
  act(d, '生成合同 ' + no)
  return c
}
// 签回件：传了第一份就变「已签回」；已签回的至少留一份
function setSigned(c, list) {
  if (c.status === '已作废') fail('合同已作废，不能再传签回件')
  if (!list.length && c.status === '已签回') fail('已签回的合同至少留一份签回件；传错了先传对的再删')
  const n0 = c.signed.length
  c.signed = list
  if (c.status === '待签回' && list.length) {
    move('contract', c, '签回', list.map((f) => f.name).join('、'))
    Object.assign(c, { signedAt: clock.today, signedBy: db.me })
    act(ctDocOf(c.kind, c.docNo), `合同 ${c.no} 已签回`)
  } else act(c, `签回件 ${n0} → ${list.length} 份`)
}
function voidContract(c, reason) {
  if (!reason?.trim()) fail('要写作废原因')
  move('contract', c, '作废', reason.trim())
  Object.assign(c, { voidReason: reason.trim(), voidAt: clock.today, voidBy: db.me })
  act(ctDocOf(c.kind, c.docNo), `作废合同 ${c.no}：${reason.trim()}`)
}
// 导出 Word：HTML 存成 .doc（Word 能直接打开、编辑、打印）；真系统用 docx 模板引擎按原 .docx 的版式填
function exportContractDoc(c) {
  if (c.status === '已作废') fail('合同已作废，不能再导出；要用请从单据上重新生成')
  const doc = `<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word"><head><meta charset="utf-8"><title>${escH(c.no)}</title>
<style>body{font-family:"SimSun","宋体",serif;font-size:12pt;line-height:1.7} h2{text-align:center;font-size:18pt} table{border-collapse:collapse;width:100%;margin:6pt 0} th,td{border:1px solid #000;padding:2pt 5pt;font-size:10.5pt} td.num{text-align:right} .ct-miss{color:#999}</style></head><body>${c.html}</body></html>`
  saveBlob(new Blob(['﻿', doc], { type: 'application/msword' }), `${c.no} ${c.kindName}.doc`)
  act(c, '导出 Word')
}

/* ---------- 演示数据 ----------
 * 采购合同：9-01 导入 V1，10-15 更新成 V2（违约金、质量条款改了）；用 V1 出过的一份作废后按 V2 重出、已签回。加工合同一份待签回 */
const TPL_PO_V2 = `物料采购合同
合同编号：{{合同编号}}　　签订日期：{{签订日期}}
甲方（采购方）：{{甲方}}　统一社会信用代码：{{甲方信用代码}}
地址：{{甲方地址}}　电话：{{甲方电话}}
乙方（供应方）：{{乙方}}　统一社会信用代码：{{乙方信用代码}}
地址：{{乙方地址}}　联系人：{{乙方联系人}}　电话：{{乙方电话}}
甲乙双方经友好协商，就甲方向乙方采购下列物料一事订立本合同，共同遵守。
第一条　采购明细（对应甲方采购单 {{单号}}）
{{明细}}
合计金额：人民币{{金额大写}}（¥{{金额}}），{{含税}}，税率 {{税率}}；乙方开具{{发票类型}}。
第二条　交货：乙方于 {{交货日期}} 前将货物送到 {{交货地点}}，运费由乙方承担。面料每卷标明缸号、米数，送货单写明采购单号。
第三条　质量：按双方确认的样品、色卡和国家相关标准验收。有色差、疵点、短码的，甲方在收货后 7 日内书面提出，乙方负责退换或补足；因此影响甲方大货交期的，乙方承担相应损失。
第四条　结算：{{结算方式}}，账期 {{账期}}。乙方收款账户：{{乙方开户行}}，账号 {{乙方账号}}。
第五条　违约：乙方逾期交货的，每逾期一日按逾期部分货款的 0.5% 支付违约金；逾期超过 15 日的，甲方可以解除本合同。
第六条　争议：协商不成的，向甲方所在地人民法院起诉。
第七条　本合同一式两份，双方各执一份，签字盖章后生效。其他约定：{{备注}}
甲方（盖章）：{{甲方}}　　　　乙方（盖章）：{{乙方}}
经办人：{{甲方经办人}}　　　　经办人：`
const TPL_PO_V1 = TPL_PO_V2.replace('　统一社会信用代码：{{甲方信用代码}}', '').replace('　统一社会信用代码：{{乙方信用代码}}', '')
  .replace('；因此影响甲方大货交期的，乙方承担相应损失', '').replace('0.5%', '0.3%').replace('逾期超过 15 日', '逾期超过 30 日')
const TPL_PROD = `服装委托加工合同
合同编号：{{合同编号}}　　签订日期：{{签订日期}}
甲方（委托方）：{{甲方}}　统一社会信用代码：{{甲方信用代码}}
乙方（加工方）：{{乙方}}　统一社会信用代码：{{乙方信用代码}}
地址：{{乙方地址}}　联系人：{{乙方联系人}}　电话：{{乙方电话}}
第一条　加工内容（对应甲方{{单据名称}} {{单号}}）：款号 {{款号}}（{{款式名称}}），加工方式 {{加工方式}}，单价 ¥{{加工单价}} / 件。
{{明细}}
合计 {{数量合计}}，加工费人民币{{金额大写}}（¥{{金额}}）。
第二条　交期：{{交货日期}} 前全部交到甲方指定仓库；分批交货的，每批附装箱单。
第三条　质量：按甲方资料包（工艺单、尺寸表）和确认的产前样生产。甲方按批抽检，次品率超过 3% 的整批返工，返工费用由乙方承担。
第四条　物料：甲方提供的面辅料，乙方按资料包单耗领用，剩料在交货后 7 日内退回；超耗部分由乙方按甲方采购价赔偿。
第五条　结算：{{结算方式}}，账期 {{账期}}，按实际合格回货数量结算。乙方收款账户：{{乙方开户行}}，账号 {{乙方账号}}。
第六条　其他约定：{{条款}}
第七条　未经甲方书面同意，乙方不得将本合同的加工任务转给第三方；甲方的款式、版型、工艺资料乙方负有保密义务。
甲方（盖章）：{{甲方}}　　　　乙方（盖章）：{{乙方}}
经办人：{{甲方经办人}}　　　　经办人：`
db.contractTpls = []
db.contracts = []
as('u6', '2026-09-01', () => {
  importTpl({ kind: '采购合同', name: '物料采购合同', file: '物料采购合同.docx', body: TPL_PO_V1 })
  importTpl({ kind: '加工合同', name: '服装委托加工合同', file: '服装委托加工合同.docx', body: TPL_PROD })
})
;(() => {
  const auditAt = (d) => d.log.find((l) => /^审核/.test(l.text))?.at || d.date
  const ok = (kind, d) => { try { checkContractable(kind, d); return true } catch { return false } }
  // 10-15 之前审核的采购单先用 V1 出一份；10-15 模板更新；10-16 作废、按 V2 重出，10-18 签回
  const po = db.purchases.find((p) => ok('purchase', p) && auditAt(p) < '2026-10-15')
  const c0 = po && as('u2', auditAt(po), () => createContract('purchase', po, liveTpls('采购合同')[0].id))
  as('u6', '2026-10-15', () => updateTpl(liveTpls('采购合同')[0], { file: '物料采购合同（2026-10 修订）.docx', body: TPL_PO_V2, note: '违约金改成每日 0.5%，加上交期损失和双方信用代码' }))
  if (c0) {
    as('u2', '2026-10-16', () => voidContract(c0, '模板换了新版（违约金和信用代码），按 V2 重出'))
    const c = as('u2', '2026-10-16', () => createContract('purchase', po, liveTpls('采购合同')[0].id))
    as('u2', '2026-10-18', () => setSigned(c, [demoPic('license', null, '采购合同', c.no + ' 签回.jpg')]))
  }
  const pd = db.productions.find((p) => ok('production', p))
  if (pd) as('u1', '2026-11-06', () => createContract('production', pd, liveTpls('加工合同')[0].id))
})()

SEARCH.push(() => db.contracts.map((c) => ({ t: c.no + ' ' + c.kindName, sub: `合同 · ${supName(c.supplier)} · ${c.docNo} · ${c.status}`, to: '/contracts/' + c.no, i: 'pen' })))

expose({ phText, CT_KINDS, CT_DOC, ctDocName, CT_VARS, varsOf, tplUnknown, liveContractOf, contractsOfDoc, contractsOfSupplier, canGenContract, tplVersions, tplUsed, rmbUpper, fmNum, ctDocTo })

/* ============================================================
 * 页面
 * ============================================================ */
route('/contracts', {
  setup() {
    const s = reactive({ tab: nav.query.tab === 'tpls' ? 'tpls' : 'list', st: '全部', sup: '', kind: '', q: '', imp: false, upd: null, view: null })
    watch(() => nav.query.tab, (t) => { if (t && nav.path === '/contracts') s.tab = t })
    const sts = computed(() => [['全部', '全部', db.contracts.length], ...['待签回', '已签回', '已作废'].map((k) => [k, k, db.contracts.filter((c) => c.status === k).length])])
    const sups = computed(() => [...new Set(db.contracts.map((c) => c.supplier))])
    const rows = computed(() => db.contracts.filter((c) => (s.st === '全部' || c.status === s.st) && (!s.sup || c.supplier === s.sup) && (!s.kind || c.kindName === s.kind)
      && matchQ(s.q, c.no, c.docNo, supName(c.supplier), c.tpl.name)).slice().reverse())
    const tpls = computed(() => db.contractTpls.slice().sort((a, b) => a.kind.localeCompare(b.kind) || a.name.localeCompare(b.name) || b.ver - a.ver))
    async function toggle(t) {
      if (t.status === '启用' && !(await ask(`停用「${t.name}」V${t.ver}`, '停用后生成合同时选不到它；已经用它出的合同不受影响。', '停用', true))) return
      if (run(() => toggleTpl(t))) toast(`「${t.name}」V${t.ver} ${t.status}了`)
    }
    return { s, sts, sups, rows, tpls, toggle }
  },
  template: `<div>
    <Head t="合同"><button v-if="s.tab === 'tpls'" class="btn pri" @click="s.imp = true"><Ico n="upload" :s="14"/>导入模板</button></Head>
    <div class="page">
      <Tabs v-model="s.tab" :items="[['list', '合同', db.contracts.length], ['tpls', '模板', db.contractTpls.filter(t => t.status === '启用').length]]"/>
      <section v-if="s.tab === 'list'" data-c="合同列表">
        <div class="bar" data-c="筛选">
          <Seg v-model="s.st" :items="sts"/>
          <span class="sp"></span>
          <Pick class="sel" v-model="s.kind"><option value="">全部类型</option><option v-for="k in Object.values(CT_KINDS)" :key="k">{{ k }}</option></Pick>
          <Pick class="sel" v-model="s.sup"><option value="">全部供应商</option><option v-for="x in sups" :key="x" :value="x">{{ supName(x) }}</option></Pick>
          <Search v-model="s.q" ph="合同号、单据号、供应商"/>
        </div>
        <ContractList :rows="rows" full :empty="db.contracts.length ? '没有符合条件的合同' : '还没有合同；在已审核的采购单、生产单上点「生成合同」'"/>
      </section>
      <section v-if="s.tab === 'tpls'" data-c="合同模板">
        <Tbl inset flat :rows="tpls" empty="还没有模板，点右上角「导入模板」" :cols="[
          { k: 'name', t: '名称' }, { k: 'kind', t: '类型' }, { k: 'ver', t: '版本', f: t => 'V' + t.ver }, { k: 'file', t: '文件' },
          { k: 'at', t: '导入', f: t => fd(t.at) + ' · ' + userName(t.by) }, { k: 'used', t: '出过', c: 'num', f: t => tplUsed(t) ? tplUsed(t) + ' 份' : '—' }, { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
          <template #file="{ r }"><span class="code dim" style="font-size:12px">{{ r.file }}</span></template>
          <template #status="{ r }"><St :s="r.status"/></template>
          <template #act="{ r }"><span class="row g4" style="justify-content:flex-end"><button class="btn ghost sm" @click="s.view = r">看</button>
            <button v-if="r.status === '启用'" class="btn ghost sm" @click="s.upd = r">更新</button>
            <button class="btn ghost sm" @click="toggle(r)">{{ r.status === '启用' ? '停用' : '启用' }}</button></span></template>
        </Tbl>
        <CtVars/>
      </section>
    </div>
    <TplForm v-if="s.imp || s.upd" :t="s.upd" @close="s.imp = false; s.upd = null"/>
    <TplView v-if="s.view" :t="s.view" @close="s.view = null"/>
  </div>`,
}, { mod: 'contracts', t: '合同' })

// 能用的占位符，分采购、加工两类列出来
comp('CtVars', {
  template: `<div class="sec" data-c="占位符"><h3>模板里能用的占位符</h3>
    <div class="ct-vars">
      <div v-for="v in CT_VARS" :key="v.k" class="ct-var"><code>{{ phText(v.k) }}</code><span class="dim">{{ v.only ? '只有' + CT_KINDS[v.only] + '　' : '' }}{{ v.t || '' }}</span></div>
    </div></div>`,
})

comp('ContractList', {
  props: { rows: Array, empty: String, full: Boolean },
  template: `<Tbl inset flat :rows="rows" link @row="c => go('/contracts/' + c.no)" :empty="empty" :cols="[
      { k: 'no', t: '合同编号', c: 'code' }, { k: 'kindName', t: '类型' }, ...(full ? [{ k: 'sup', t: '供应商', f: c => supName(c.supplier) }] : []),
      { k: 'doc', t: '来源单据', c: 'code', f: c => c.docNo }, { k: 'amt', t: '金额', c: 'num', f: c => fm(c.amt) }, { k: 'tpl', t: '模板', f: c => c.tpl.name + ' V' + c.tpl.ver },
      { k: 'at', t: '生成', f: c => fd(c.at) + ' · ' + userName(c.by) }, { k: 'status', t: '状态' }]">
    <template #status="{ r }"><St :s="r.status"/></template>
  </Tbl>`,
})

/* 导入模板（t 不传）/ 更新模板（传 t：出新版本）。原型只认文件名，正文贴进来；边输边查占位符 */
comp('TplForm', {
  props: { t: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ kind: p.t?.kind || '采购合同', name: p.t?.name || '', file: '', body: p.t?.body || '', note: '' })
    const bad = computed(() => tplUnknown(f.kind, f.body))
    const used = computed(() => [...new Set([...f.body.matchAll(PH_RE)].map((m) => m[1]))].filter((k) => varsOf(f.kind).some((v) => v.k === k)))
    function pick(e) { const x = e.target.files[0]; if (!x) return; if (!/\.docx$/i.test(x.name)) { toast(x.name + ' 不是 .docx 文件', null, true); e.target.value = ''; return } f.file = x.name }
    function save() {
      const t = run(() => (p.t ? updateTpl(p.t, f) : importTpl(f)))
      if (t && t !== true) { emit('close'); toast(p.t ? `「${t.name}」更新成 V${t.ver}，V${p.t.ver} 已停用` : `导入了「${t.name}」V1`) }
    }
    return { f, bad, used, pick, save }
  },
  template: `<Modal :t="t ? '更新模板：' + t.name + '（现在 V' + t.ver + '）' : '导入合同模板'" :w="720" @close="$emit('close')">
    <div class="form">
      <label class="f">类型<Pick class="sel" v-model="f.kind" :disabled="!!t"><option v-for="k in Object.values(CT_KINDS)" :key="k">{{ k }}</option></Pick></label>
      <label class="f">名称<input class="inp" v-model="f.name" :disabled="!!t" placeholder="如：物料采购合同"></label>
      <label class="f full">Word 文件（.docx）<span class="row g8"><input type="file" accept=".docx" @change="pick" data-c="模板文件"><span v-if="f.file" class="code">{{ f.file }}</span></span>
</label>
      <label class="f full">正文<textarea class="inp ct-src" v-model="f.body" rows="12" placeholder="在 Word 里全选、复制，粘贴到这里；要系统填的地方写成 {{乙方}} 这样的占位符"></textarea>
        <small v-if="bad.length" class="red" data-c="不认识的占位符">系统不认识：{{ bad.join('、') }}，不能保存</small>
        <small v-else-if="used.length" class="dim">用到 {{ used.length }} 个占位符：{{ used.join('、') }}</small></label>
      <label v-if="t" class="f full">这次改了什么<input class="inp" v-model="f.note" placeholder="如：违约金改成每日 0.5%"></label>
    </div>
    <details class="ct-help"><summary>能用的占位符</summary><div class="ct-vars"><div v-for="v in varsOf(f.kind)" :key="v.k" class="ct-var"><code>{{ phText(v.k) }}</code><span class="dim">{{ v.t || '' }}</span></div></div></details>
    <template #foot><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ t ? '保存新版本' : '导入' }}</button></template>
  </Modal>`,
})

// 看模板：占位符高亮；同名的各个版本
comp('TplView', {
  props: { t: Object },
  emits: ['close'],
  setup(p) {
    const cur = ref(p.t)
    const html = computed(() => escH(cur.value.body).replace(PH_RE, (m) => `<b class="ct-ph">${m}</b>`).replace(/\n/g, '<br>'))
    return { cur, html }
  },
  template: `<Modal :t="cur.name + ' V' + cur.ver" :w="760" @close="$emit('close')">
    <div class="row g8" style="margin-bottom:10px;flex-wrap:wrap"><button v-for="v in tplVersions(t)" :key="v.id" class="chip" :class="{ on: v === cur }" @click="cur = v">V{{ v.ver }} · {{ v.status }} · {{ fd(v.at) }}</button></div>
    <div class="dim" style="font-size:12px;margin-bottom:8px">{{ cur.file }} · {{ userName(cur.by) }} 导入<template v-if="cur.note"> · {{ cur.note }}</template> · 出过 {{ tplUsed(cur) }} 份合同</div>
    <div class="ct-paper ct-srcview" v-html="html"></div>
    <template #foot><span class="sp"></span><button class="btn" @click="$emit('close')">关闭</button></template>
  </Modal>`,
})

/* 从采购单 / 生产单生成合同：选模板 → 预览（明细展开、留空的列出来）→ 确认 */
comp('ContractGen', {
  props: { kind: String, d: Object },
  emits: ['close'],
  setup(p, { emit }) {
    const tpls = computed(() => liveTpls(CT_KINDS[p.kind]))
    const tid = ref(tpls.value[0]?.id || '')
    const block = computed(() => { try { checkContractable(p.kind, p.d); return null } catch (e) { return e } })
    const pre = computed(() => (tplOf(tid.value) ? renderContract(tplOf(tid.value), ctCtx(p.kind, p.d, '（确认后自动编号）')) : null))
    function ok() {
      const c = run(() => createContract(p.kind, p.d, tid.value))
      if (c && c !== true) { emit('close'); toast(`生成了合同 ${c.no}，导出 Word 去签`, ['打开合同', '/contracts/' + c.no]) }
    }
    return { tpls, tid, block, pre, ok }
  },
  template: `<Modal :t="'生成' + CT_KINDS[kind] + '：' + d.no" :w="820" @close="$emit('close')">
    <div v-if="block" class="banner red" data-c="不能生成"><Ico n="alert"/><span>{{ block.message }}<template v-if="block.list && block.list.length">；{{ block.list.join('；') }}</template></span></div>
    <div v-if="!tpls.length" class="banner"><Ico n="alert"/><span>还没有在用的{{ CT_KINDS[kind] }}模板，先到 <a href="#/contracts?tab=tpls">资料 › 合同 › 模板</a> 导入</span></div>
    <div v-else class="row g8" style="margin-bottom:10px;flex-wrap:wrap" data-c="选模板"><span class="dim">模板</span>
      <button v-for="t in tpls" :key="t.id" class="chip" :class="{ on: t.id === tid }" @click="tid = t.id">{{ t.name }} V{{ t.ver }}</button></div>
    <p v-if="pre && pre.miss.length" class="amber" style="font-size:12px;margin:0 0 10px" data-c="留空">待手填项：{{ pre.miss.join('、') }}</p>
    <div v-if="pre" class="ct-paper" data-c="合同预览" v-html="pre.html"></div>
    <template #foot><span class="sp"></span>
      <button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!!block || !pre" @click="ok">确认生成合同</button></template>
  </Modal>`,
})

// 单据详情右边：这张单出过的合同（作废的也列，划掉）
comp('DocContracts', {
  props: { kind: String, d: Object },
  template: `<div class="docfiles" data-c="合同"><h4>合同</h4>
    <div v-for="c in contractsOfDoc(d.no)" :key="c.no" class="ct-mini" :class="{ off: c.status === '已作废' }"><a class="code" :href="'#/contracts/' + c.no">{{ c.no }}</a><St :s="c.status"/><span class="dim">V{{ c.tpl.ver }}</span></div>
    <p v-if="!contractsOfDoc(d.no).length" class="dim" style="font-size:12px;margin:0">{{ canGenContract(kind, d) ? '还没出合同，点上面「生成合同」' : '审核以后能生成合同' }}</p></div>`,
  setup: () => ({ PHASE_TIP }),
})

route('/contracts/:no', {
  props: ['no'],
  setup(p) {
    const c = computed(() => contract(p.no))
    const d = computed(() => c.value && ctDocOf(c.value.kind, c.value.docNo))
    const signing = ref(false)
    function exp() { if (run(() => exportContractDoc(c.value))) toast('导出了 ' + c.value.no + ' ' + c.value.kindName + '.doc，打印签字盖章后把扫描件传回来') }
    async function doVoid() {
      const why = await askText('作废合同 ' + c.value.no, '作废后这份合同只读、不能导出，合同号不再用；来源单据可以重新生成一份。', '写清楚为什么作废，如：数量改了要重出', '作废')
      if (why && run(() => voidContract(c.value, why))) toast('已作废；要重出到 ' + c.value.docNo + ' 上点「生成合同」', ['去 ' + c.value.docNo, ctDocTo(c.value)])
    }
    function sign(list) { if (run(() => setSigned(c.value, list)) && c.value.status === '已签回') { signing.value = false } }
    const more = computed(() => (c.value && can('contract', c.value, '作废') ? [{ t: '作废', danger: true, fn: doVoid }] : []))
    return { c, d, signing, exp, doVoid, sign, more }
  },
  template: `<div v-if="c">
    <Head :t="c.no" :back="['合同', '/contracts']">
      <button v-if="c.status !== '已作废'" class="btn" @click="exp"><Ico n="download" :s="14"/>导出 Word</button>
      <button v-if="c.status === '待签回'" class="btn pri" @click="signing = true"><Ico n="upload" :s="14"/>上传签回件</button>
      <a v-if="c.status === '已作废' && canGenContract(c.kind, d)" class="btn pri" :href="'#' + ctDocTo(c)">去 {{ c.docNo }} 重新生成</a>
      <More :items="more"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div v-if="c.status === '已作废'" class="banner red" data-c="作废原因"><Ico n="alert"/><span>{{ fd(c.voidAt) }} {{ userName(c.voidBy) }} 作废：{{ c.voidReason }}</span></div>
        <div class="title">{{ c.kindName }}<St :s="c.status"/></div>
        <div class="sub"><span>{{ supName(c.supplier) }}</span><span>{{ ctDocName(c.kind, c.kind === 'purchase' ? purchase(c.docNo) : production(c.docNo)) }} <a class="code" :href="'#' + ctDocTo(c)">{{ c.docNo }}</a></span><span>{{ fm(c.amt) }}</span><span>模板「{{ c.tpl.name }}」V{{ c.tpl.ver }}</span></div>
        <p v-if="c.miss && c.miss.length" class="dim" style="font-size:12px;margin:0 0 12px">待手填项：{{ c.miss.join('、') }}</p>
        <article class="ct-paper" :class="{ void: c.status === '已作废' }" data-c="合同正文" v-html="c.html"></article>
      </div>
      <aside class="aside" data-c="合同属性">
        <dl class="props">
          <dt>合同编号</dt><dd class="code">{{ c.no }}</dd>
          <dt>类型</dt><dd>{{ c.kindName }}</dd>
          <dt>甲方</dt><dd>{{ companyName(c.company) }}</dd>
          <dt>乙方</dt><dd><a :href="'#/suppliers/' + c.supplier + '?tab=contracts'">{{ sup(c.supplier)?.name }}</a></dd>
          <dt>来源单据</dt><dd><a class="code" :href="'#' + ctDocTo(c)">{{ c.docNo }}</a><span v-if="d" class="dim"> · {{ d.status }}</span></dd>
          <dt>金额</dt><dd>{{ fm(c.amt) }}</dd>
          <dt>模板</dt><dd>{{ c.tpl.name }} V{{ c.tpl.ver }}<div class="dim code" style="font-size:12px">{{ c.tpl.file }}</div></dd>
          <dt>生成</dt><dd>{{ userName(c.by) }} · {{ fd(c.at) }}</dd>
          <dt>签回</dt><dd>{{ c.signedAt ? userName(c.signedBy) + ' · ' + fd(c.signedAt) : '—' }}</dd>
        </dl>
        <div class="docfiles" data-c="签回件"><h4>签回件<span v-if="c.signed.length" class="dim" style="font-weight:400"> {{ c.signed.length }}</span></h4>
          <Pics :model-value="c.signed" files :s="56" :max="10" :readonly="c.status === '已作废'" @update:model-value="sign" tip="签字盖章后的扫描件、照片、PDF"/></div>
        <h4>操作记录</h4><Log :items="c.log"/>
      </aside>
    </div>
    <Modal v-if="signing" :t="'上传签回件：' + c.no" :w="480" @close="signing = false">
      <Pics :model-value="c.signed" files :s="64" :max="10" @update:model-value="sign" tip="拖进来、粘贴、点选都行"/>
      <template #foot><span class="sp"></span><button class="btn" @click="signing = false">关闭</button></template>
    </Modal>
  </div><NotFound v-else/>`,
}, { mod: 'contracts', t: (p) => '合同 ' + p.no })
