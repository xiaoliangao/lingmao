/* ============================================================
 * Excel：导出当前筛选结果、下载导入模板、读导入文件（PRD §5.4、§6.5.2、§10；RFC 001 §9）
 * 原型在浏览器里直接生成、读取 .xlsx，不用联网：
 *   <ExportBtn :rows="rows" :cols="cols" name="物料"/>   导出列表当前筛选出来的行，cols 同 Tbl（t 表头，f 取值；x 可单独给导出用的取值）
 *   <ImportBox kind="orders" @close="..."/>              下载模板 → 选 .xlsx → 开始导入；有一行不对整批不导，列出全部错误
 * 导入的定义写在 IMPORTS[kind]：cols 列定义（表头、必填、说明、例子、可选值），check 逐行查业务规则，save 写入；
 * head 是整批共用、在对话框里选的值（如订单导入的客户、跟单员）。
 * 规则照真系统（RFC 001 §9.3）：只收 .xlsx、5000 行以内；表头按名字对，列的顺序可以调；空行跳过。
 * ============================================================ */

/* ---------- 写 .xlsx：zip（不压缩）+ 最少的几个 xml ---------- */
const CRC_T = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } return t })()
const crc32 = (u8) => { let c = 0xffffffff; for (let i = 0; i < u8.length; i++) c = CRC_T[(c ^ u8[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function zipStore(files) {
  const enc = new TextEncoder(), parts = [], dir = []
  let off = 0
  files.forEach((f) => {
    const name = enc.encode(f.name), data = enc.encode(f.data), crc = crc32(data)
    const h = new DataView(new ArrayBuffer(30))
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(12, 0x21, true)
    h.setUint32(14, crc, true); h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, name.length, true)
    parts.push(h, name, data)
    const c = new DataView(new ArrayBuffer(46))
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true); c.setUint16(14, 0x21, true)
    c.setUint32(16, crc, true); c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, off, true)
    dir.push(c, name)
    off += 30 + name.length + data.length
  })
  const size = dir.reduce((n, x) => n + x.byteLength, 0)
  const e = new DataView(new ArrayBuffer(22))
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, files.length, true); e.setUint16(10, files.length, true); e.setUint32(12, size, true); e.setUint32(16, off, true)
  return new Blob([...parts, ...dir, e], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
}
const xesc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const colL = (i) => { let s = ''; for (i++; i; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s; return s }
const colI = (l) => [...l].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1
// sheets: [{ name, rows: [[...]], text: Set(列号，存成文本，0012 不变成 12), lists: { 列号: [下拉可选值] }, widths: [列宽] }]；第一行加粗、冻结
function xlsxWrite(sheets) {
  const NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main', R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
  const sheetXml = (s) => {
    const n = Math.max(1, ...s.rows.map((r) => r.length))
    const cols = Array.from({ length: n }, (_, i) => `<col min="${i + 1}" max="${i + 1}" width="${s.widths?.[i] || 14}" customWidth="1"${s.text?.has(i) ? ' style="1"' : ''}/>`).join('')
    const rows = s.rows.map((r, ri) => `<row r="${ri + 1}">${r.map((v, ci) => {
      if (v == null || v === '') return ''
      const ref = colL(ci) + (ri + 1)
      if (typeof v === 'number' && !s.text?.has(ci)) return `<c r="${ref}"><v>${v}</v></c>`
      return `<c r="${ref}" t="inlineStr" s="${ri === 0 ? 2 : s.text?.has(ci) ? 1 : 0}"><is><t xml:space="preserve">${xesc(v)}</t></is></c>`
    }).join('')}</row>`).join('')
    const dv = Object.entries(s.lists || {}).filter(([, l]) => l.length && l.join(',').length < 250)
      .map(([ci, l]) => `<dataValidation type="list" allowBlank="1" showErrorMessage="1" sqref="${colL(+ci)}2:${colL(+ci)}5001"><formula1>"${xesc(l.join(','))}"</formula1></dataValidation>`).join('')
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="${NS}"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${cols}</cols><sheetData>${rows}</sheetData>${dv ? `<dataValidations count="${dv.split('<dataValidation ').length - 1}">${dv}</dataValidations>` : ''}</worksheet>`
  }
  const head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
  return zipStore([
    { name: '[Content_Types].xml', data: `${head}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>` },
    { name: '_rels/.rels', data: `${head}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${R}/officeDocument" Target="xl/workbook.xml"/></Relationships>` },
    { name: 'xl/workbook.xml', data: `${head}<workbook xmlns="${NS}" xmlns:r="${R}"><sheets>${sheets.map((s, i) => `<sheet name="${xesc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>` },
    { name: 'xl/_rels/workbook.xml.rels', data: `${head}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${R}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId${sheets.length + 1}" Type="${R}/styles" Target="styles.xml"/></Relationships>` },
    { name: 'xl/styles.xml', data: `${head}<styleSheet xmlns="${NS}"><fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><sz val="11"/><name val="等线"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyNumberFormat="1"/></cellXfs></styleSheet>` },
    ...sheets.map((s, i) => ({ name: `xl/worksheets/sheet${i + 1}.xml`, data: sheetXml(s) })),
  ])
}
function saveBlob(blob, name) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}

/* ---------- 读 .xlsx：只读第一个工作表，每格取显示的文本 ---------- */
async function unzipEntries(buf) {
  const dv = new DataView(buf), u8 = new Uint8Array(buf), dec = new TextDecoder()
  let e = buf.byteLength - 22
  while (e >= 0 && dv.getUint32(e, true) !== 0x06054b50) e--
  if (e < 0) throw new Error('文件打不开：不是 .xlsx，或者文件坏了')
  const out = {}
  for (let i = 0, n = dv.getUint16(e + 10, true), p = dv.getUint32(e + 16, true); i < n; i++) {
    const nl = dv.getUint16(p + 28, true), name = dec.decode(u8.subarray(p + 46, p + 46 + nl)), lo = dv.getUint32(p + 42, true)
    const start = lo + 30 + dv.getUint16(lo + 26, true) + dv.getUint16(lo + 28, true)
    out[name] = { method: dv.getUint16(p + 10, true), data: u8.subarray(start, start + dv.getUint32(p + 20, true)) }
    p += 46 + nl + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true)
  }
  return out
}
async function xlsxRead(file) {
  const z = await unzipEntries(await file.arrayBuffer())
  const text = async (name) => {
    const x = z[name]
    if (!x) return ''
    const raw = x.method === 0 ? x.data : new Uint8Array(await new Response(new Blob([x.data]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer())
    return new TextDecoder().decode(raw)
  }
  const xml = (s) => new DOMParser().parseFromString(s || '<x/>', 'application/xml')
  const tags = (node, t) => [...node.getElementsByTagName(t)]
  const first = tags(xml(await text('xl/workbook.xml')), 'sheet')[0]
  if (!first) throw new Error('文件打不开：里面没有工作表')
  const rid = first.getAttribute('r:id')
  let target = tags(xml(await text('xl/_rels/workbook.xml.rels')), 'Relationship').find((r) => r.getAttribute('Id') === rid)?.getAttribute('Target') || 'worksheets/sheet1.xml'
  target = target.replace(/^\/?(xl\/)?/, '')
  const runs = (n) => tags(n, 't').filter((t) => t.parentNode.nodeName !== 'rPh').map((t) => t.textContent).join('')
  const shared = tags(xml(await text('xl/sharedStrings.xml')), 'si').map(runs)
  const rows = []
  for (const r of tags(xml(await text('xl/' + target)), 'row')) {
    const row = []
    for (const c of tags(r, 'c')) {
      const t = c.getAttribute('t'), v = tags(c, 'v')[0]?.textContent ?? ''
      row[colI(c.getAttribute('r').replace(/\d+/g, ''))] = t === 's' ? shared[+v] ?? '' : t === 'inlineStr' ? runs(c) : t === 'b' ? (v === '1' ? '是' : '否') : v
    }
    rows[+r.getAttribute('r') - 1] = Array.from(row, (x) => String(x ?? '').trim())
  }
  return Array.from(rows, (r) => r || [])
}
// Excel 日期：单元格是日期时存的是天数（1900 起），文本也认 2026-11-05、2026/11/5
function xlsxDate(v) {
  if (/^\d{5}(\.\d+)?$/.test(v)) return ymd(new Date(Date.UTC(1899, 11, 30) + Math.floor(+v) * 864e5 + new Date().getTimezoneOffset() * 6e4))
  const m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/.exec(v)
  return m ? `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}` : null
}

/* ---------- 导出：当前筛选结果 ---------- */
comp('ExportBtn', {
  props: { rows: Array, cols: Array, name: String },
  setup(p) {
    function exp() {
      const cols = p.cols.filter((c) => c.t && c.x !== false)
      const val = (c, r) => { const v = c.x ? c.x(r) : c.f ? c.f(r) : r[c.k]; return v == null ? '' : typeof v === 'object' ? JSON.stringify(v) : v }
      const rows = [cols.map((c) => c.t), ...p.rows.map((r) => cols.map((c) => val(c, r)))]
      const text = new Set(cols.map((c, i) => (c.c === 'code' ? i : -1)).filter((i) => i >= 0))
      saveBlob(xlsxWrite([{ name: p.name, rows, text, widths: cols.map((c) => (c.c === 'code' ? 18 : 14)) }]), `${p.name}-${clock.today.replace(/-/g, '')}.xlsx`)
      toast(`导出了 ${p.rows.length} 条`)
    }
    return { exp }
  },
  template: `<button class="btn" @click="exp" :disabled="!rows.length" title="导出当前筛选出来的行"><Ico n="download" :s="14"/>导出</button>`,
})

/* ---------- 导入 ---------- */
const IMPORTS = {}
const IMPORT_MAX_ROWS = 5000
const headOf = (t) => t.replace(/[*＊\s]/g, '')
function importTemplate(def, example) {
  const head = def.cols.map((c) => (c.req ? '*' : '') + c.t)
  const lists = Object.fromEntries(def.cols.map((c, i) => [i, c.opts ? c.opts() : []]))
  const text = new Set(def.cols.map((c, i) => (c.text ? i : -1)).filter((i) => i >= 0))
  const data = { name: '数据', rows: [head, ...(example ? def.example().map((r) => def.cols.map((c) => r[c.k] ?? '')) : [])], text, lists, widths: def.cols.map((c) => c.w || 14) }
  const help = { name: '填写说明', rows: [['列', '必填', '怎么填', '例子'], ...def.cols.map((c) => [c.t, c.req ? '必填' : '选填', c.tip || '', c.ex ?? '']), [], ['说明', '', def.note || '', ''], ['', '', '表头别改名，列的顺序可以调；带 * 的列必填；空行跳过。有一行不对，整批都不导入。', '']], text: new Set([3]), widths: [16, 8, 60, 20] }
  saveBlob(xlsxWrite([data, help]), `${def.name}${example ? '导入示例' : '导入模板'}.xlsx`)
}
// 读出来的行 → [{ _r: Excel 行号, k: 值 }]；表头、必填、可选值、数字、日期在这里统一查
async function importParse(def, file) {
  const errs = [], bad = (r, c, m) => errs.push({ r, c, m })
  const table = await xlsxRead(file)
  const hi = table.findIndex((r) => r.some(Boolean))
  if (hi < 0) return { errs: [{ r: '—', c: '—', m: '文件是空的' }], rows: [] }
  const head = table[hi].map(headOf)
  const idx = Object.fromEntries(def.cols.map((c) => [c.k, head.indexOf(headOf(c.t))]))
  def.cols.filter((c) => c.req && idx[c.k] < 0).forEach((c) => bad('表头', c.t, '缺这一列。表头别改名，可以下载模板对一下'))
  if (errs.length) return { errs, rows: [] }
  const rows = []
  table.slice(hi + 1).forEach((r, i) => {
    if (!r.some(Boolean)) return
    const row = { _r: hi + i + 2 }
    def.cols.forEach((c) => {
      let v = idx[c.k] >= 0 ? r[idx[c.k]] || '' : ''
      if (!v) { if (c.req) bad(row._r, c.t, '必填，没填'); row[c.k] = c.num || c.int ? null : ''; return }
      if (c.int && !(/^\d+$/.test(v) && +v > 0)) bad(row._r, c.t, `「${v}」不是正整数`)
      else if (c.num && !(/^\d+(\.\d+)?$/.test(v))) bad(row._r, c.t, `「${v}」不是数字`)
      if (c.date) { const d = xlsxDate(v); if (!d) bad(row._r, c.t, `「${v}」不是日期，写成 2026-11-05`); else v = d }
      if (c.opts && !c.free && !c.opts().includes(v)) bad(row._r, c.t, `「${v}」不在可选值里：${c.opts().slice(0, 8).join('、')}${c.opts().length > 8 ? '……' : ''}`)
      row[c.k] = c.num || c.int ? +v : v
    })
    rows.push(row)
  })
  if (!rows.length) bad('—', '—', '除了表头没有数据')
  if (rows.length > IMPORT_MAX_ROWS) bad('—', '—', `有 ${rows.length} 行，超过 ${IMPORT_MAX_ROWS} 行，请拆成几个文件分批导入`)
  return { errs, rows }
}
comp('ImportBox', {
  props: { kind: String },
  emits: ['close', 'done'],
  setup(p, { emit }) {
    const def = IMPORTS[p.kind]
    const s = reactive({ file: null, msg: '', errs: [], done: null, busy: false, over: false })
    const head = reactive(Object.fromEntries((def.head || []).map((h) => [h.k, h.def ? h.def() : ''])))
    const input = ref(null)
    function pick(f) {
      s.msg = ''; s.errs = []; s.file = null
      if (!f) return
      if (!/\.xlsx$/i.test(f.name)) { s.msg = `${f.name} 不是 .xlsx 文件。.xls、.csv 请先在 Excel 里另存为 .xlsx`; return }
      if (f.size > 10 * 1024 * 1024) { s.msg = `文件有 ${fsize(f.size)}，超过 10MB。请拆成几个文件分批导入`; return }
      s.file = f
    }
    async function go() {
      s.busy = true; s.msg = ''; s.errs = []
      try {
        const { errs, rows } = await importParse(def, s.file)
        const plan = errs.length ? null : def.check(rows, (r, c, m) => errs.push({ r: r._r ?? r, c, m }), head)
        if (errs.length) { s.errs = errs; s.msg = `有 ${errs.length} 处不对，整批都没导入。改好以后重新选文件`; s.file = null; return }
        s.done = def.save(plan, head)
        emit('done', s.done)
      } catch (e) { s.msg = e.message; s.file = null } finally { s.busy = false }
    }
    return { def, s, head, input, pick, go, importTemplate, fsize }
  },
  template: `<Modal :t="def.title" :w="640" @close="$emit('close')">
    <div v-if="s.done" class="col" style="align-items:center;padding:24px 0;gap:8px" data-c="导入完成">
      <span class="flag green" style="font-size:14px"><Ico n="check" :s="16"/> 导入完成</span><span>{{ s.done.text }}</span>
      <a v-if="s.done.to" class="btn" :href="'#' + s.done.to" @click="$emit('close')">去看看</a>
    </div>
    <template v-else>
      <div v-if="def.head" class="form" style="grid-template-columns:repeat(4,1fr);margin-bottom:16px" data-c="导入：整批共用">
        <label v-for="h in def.head" :key="h.k" class="f">{{ h.t }}
          <input v-if="h.type === 'date'" class="inp" type="date" v-model="head[h.k]">
          <select v-else class="sel" v-model="head[h.k]"><option v-for="o in h.opts()" :key="o[0]" :value="o[0]">{{ o[1] }}</option></select></label>
      </div>
      <ol class="isteps">
        <li><b>下载模板，按模板填好</b><p class="mut">带 * 的列必填；表头别改名，列的顺序可以调。模板第二页「填写说明」里有每一列的格式和例子。{{ def.note }}</p>
          <span class="row"><button class="btn" @click="importTemplate(def)"><Ico n="download" :s="14"/>下载模板</button><a v-if="def.example" class="dim" style="font-size:12px;cursor:pointer" @click="importTemplate(def, true)">没有现成的文件？下载一份填好的示例</a></span></li>
        <li><b>选择填好的文件</b>
          <div class="idrop" :class="{ over: s.over }" @click="input.click()" @dragover.prevent="s.over = true" @dragleave="s.over = false" @drop.prevent="s.over = false; pick($event.dataTransfer.files[0])" data-c="导入：选文件">
            <Ico n="upload" :s="22"/>
            <span v-if="s.file"><b>{{ s.file.name }}</b> <span class="dim num">{{ fsize(s.file.size) }}</span><br><span class="dim">点击或拖进来可以换一个</span></span>
            <span v-else-if="s.errs.length">改好以后把文件重新拖进来，或<a>点击选择</a></span>
            <span v-else>把 .xlsx 文件拖到这里，或<a>点击选择</a></span>
            <input ref="input" type="file" accept=".xlsx" hidden @change="pick($event.target.files[0]); $event.target.value = ''">
          </div>
          <p class="dim" style="font-size:12px;margin-top:6px">只收 .xlsx，10MB、5000 行以内。有一行不对，整批都不导入。</p></li>
      </ol>
      <div v-if="s.msg" class="banner red" style="margin-top:12px"><Ico n="alert"/><span>{{ s.msg }}</span></div>
      <div v-if="s.errs.length" style="max-height:240px;overflow:auto;margin-top:8px" data-c="导入：错误清单">
        <Tbl inset flat :rows="s.errs" :cols="[{ k: 'r', t: '行', w: '60px' }, { k: 'c', t: '列', w: '110px' }, { k: 'm', t: '问题' }]"/>
      </div>
    </template>
    <template #foot><span class="sp"></span>
      <button v-if="s.done" class="btn pri" @click="$emit('close')">完成</button>
      <template v-else><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!s.file || s.busy" @click="go">{{ s.busy ? '导入中……' : '开始导入' }}</button></template></template>
  </Modal>`,
})

expose({ IMPORTS, xlsxWrite, saveBlob })
