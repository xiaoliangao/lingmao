/* ============================================================
 * 图片和附件（PRD §5.8；架构 §10.3）
 * 加图三种方式：点「加图片」选文件、把文件拖进框里、点一下框再 Ctrl+V 粘贴截图。
 * 图片在浏览器里压到长边 1600px、JPEG 0.8 再存；原型只存在内存里，刷新页面就没了。
 * 第一张是主图；鼠标移到图上能「设为主图」「删除」；点图看大图（← → 翻页，Esc 关）。
 * 用法：
 *   <Pics v-model="f.images"/>                              多张图，默认最多 9 张
 *   <Pics v-model="f.images" :colors="f.colors"/>            每张图能标颜色（订单、加工单里按颜色出图）
 *   <Pics v-model="d.files" files/>                          附件：图片以外的文件也收，点了下载
 *   <Pics v-model="f.photos" camera/>                       报工、异常：按钮写「拍照」，手机上点了可以选拍照或相册
 *   <Pics :model-value="x.photos" readonly :s="28" :limit="3"/>   表格里只看
 * ============================================================ */
let picSeq = 0
const PIC_MAX = 9
const FILE_MAX = 10 * 1024 * 1024
const isImg = (x) => /^data:image\//.test(x?.src || '')
const extOf = (name) => (/\.([a-z0-9]{1,5})$/i.exec(name || '')?.[1] || '文件').toUpperCase()
const fsize = (n) => (n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB')

function readPic(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file), img = new Image()
    img.onload = () => {
      const k = Math.min(1, 1600 / Math.max(img.naturalWidth, img.naturalHeight))
      const c = document.createElement('canvas')
      c.width = Math.max(1, Math.round(img.naturalWidth * k))
      c.height = Math.max(1, Math.round(img.naturalHeight * k))
      const g = c.getContext('2d')
      g.fillStyle = '#fff' // 透明 PNG 转 JPEG 时底色用白的
      g.fillRect(0, 0, c.width, c.height)
      g.drawImage(img, 0, 0, c.width, c.height)
      URL.revokeObjectURL(url)
      const src = c.toDataURL('image/jpeg', 0.8)
      resolve({ id: 'P' + ++picSeq, name: file.name || '截图.jpg', size: Math.round(src.length * 0.75), src, at: clock.today })
    }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error(`${file.name} 打不开，不是图片`)) }
    img.src = url
  })
}
function readFile(file) {
  if (file.size > FILE_MAX) return Promise.reject(new Error(`${file.name} 超过 10MB`))
  return new Promise((resolve) => {
    const r = new FileReader()
    r.onload = () => resolve({ id: 'P' + ++picSeq, name: file.name, size: file.size, src: r.result, at: clock.today })
    r.readAsDataURL(file)
  })
}
function downloadPic(x) {
  const a = document.createElement('a')
  a.href = x.src
  a.download = x.name || '附件'
  a.click()
}

/* ---------- 看大图 ---------- */
const viewer = reactive({ list: null, i: 0 })
function viewPics(list, i = 0) {
  const imgs = (list || []).filter(isImg)
  if (!imgs.length) return
  viewer.list = imgs
  viewer.i = Math.max(0, imgs.indexOf(list[i]))
}
comp('PicViewer', {
  setup() {
    useEsc(() => { viewer.list = null })
    const cur = computed(() => viewer.list[viewer.i])
    const step = (d) => { viewer.i = (viewer.i + d + viewer.list.length) % viewer.list.length }
    const key = (e) => { if (e.key === 'ArrowLeft') step(-1); if (e.key === 'ArrowRight') step(1) }
    onMounted(() => window.addEventListener('keydown', key))
    onUnmounted(() => window.removeEventListener('keydown', key))
    return { viewer, cur, step }
  },
  template: `<Teleport to="body"><div class="viewer" @mousedown.self="viewer.list = null">
    <img :src="cur.src" :alt="cur.name">
    <button v-if="viewer.list.length > 1" class="vnav l" title="上一张" @click="step(-1)"><Ico n="left" :s="22"/></button>
    <button v-if="viewer.list.length > 1" class="vnav r" title="下一张" @click="step(1)"><Ico n="right" :s="22"/></button>
    <div class="vbar"><span>{{ cur.name }}</span><span v-if="cur.color">{{ colorName(cur.color) }}</span><span v-if="cur.at" class="dim">{{ fd(cur.at) }}</span><span class="sp"></span>
      <span>{{ viewer.i + 1 }} / {{ viewer.list.length }}</span><button class="btn ghost icon" title="下载" @click="downloadPic(cur)"><Ico n="download"/></button><button class="btn ghost icon" title="关闭（Esc）" @click="viewer.list = null"><Ico n="x"/></button></div>
  </div></Teleport>`,
})

/* ---------- 上传框 ---------- */
comp('Pics', {
  props: {
    modelValue: { type: Array, default: () => [] }, max: { type: Number, default: PIC_MAX }, s: { type: Number, default: 72 },
    colors: Array, readonly: Boolean, files: Boolean, camera: Boolean, limit: Number, bare: Boolean, tip: String,
  },
  emits: ['update:modelValue'],
  setup(p, { emit }) {
    const over = ref(false), busy = ref(false), inp = ref(null)
    const list = computed(() => p.modelValue || [])
    const shown = computed(() => (p.limit ? list.value.slice(0, p.limit) : list.value))
    const unit = p.files ? '个' : '张'
    async function take(picked) {
      const fs = [...picked].filter((f) => p.files || f.type.startsWith('image/'))
      if (!fs.length) return toast(p.files ? '没有能加的文件' : '只能加图片（jpg、png……）', null, true)
      const room = p.max - list.value.length
      if (room <= 0) return toast(`最多 ${p.max} ${unit}`, null, true)
      if (fs.length > room) toast(`最多 ${p.max} ${unit}，多出来的 ${fs.length - room} ${unit}没加`, null, true)
      busy.value = true
      const got = [], errs = []
      for (const f of fs.slice(0, room)) {
        try { got.push(await (f.type.startsWith('image/') ? readPic(f) : readFile(f))) } catch (e) { errs.push(e.message) }
      }
      busy.value = false
      if (errs.length) toast(errs.join('；'), null, true)
      if (got.length) emit('update:modelValue', [...list.value, ...got])
    }
    const pick = (e) => { take(e.target.files); e.target.value = '' }
    const drop = (e) => { over.value = false; if (!p.readonly) take(e.dataTransfer.files) }
    const paste = (e) => { const fs = [...(e.clipboardData?.files || [])]; if (fs.length && !p.readonly) { e.preventDefault(); take(fs) } }
    const remove = (i) => emit('update:modelValue', list.value.filter((_, k) => k !== i))
    const toFront = (i) => { const a = [...list.value]; a.unshift(...a.splice(i, 1)); emit('update:modelValue', a) }
    const tag = (i, c) => emit('update:modelValue', list.value.map((x, k) => (k === i ? { ...x, color: c || undefined } : x)))
    const open = (i) => { const x = list.value[i]; if (isImg(x)) viewPics(list.value, i); else downloadPic(x) }
    const tipText = computed(() => p.tip ?? (p.files
      ? '合同、报价单、截图都可以；拖进来也行，单个不超过 10MB'
      : `拖进来、或点一下这里再 Ctrl+V 粘贴截图也行；最多 ${p.max} 张${p.max > 1 ? '，第一张是主图' : ''}`))
    return { over, busy, inp, list, shown, take, pick, drop, paste, remove, toFront, tag, open, tipText, isImg, extOf, fsize }
  },
  template: `<div class="pics" :class="{ over, ro: readonly }" :tabindex="readonly ? null : 0" @paste="paste" @dragover.prevent="over = !readonly" @dragleave="over = false" @drop.prevent="drop">
    <div class="pgrid">
      <div v-for="(x, i) in shown" :key="x.id" class="pic" :class="{ file: !isImg(x) }" :style="{ width: (isImg(x) ? s : Math.max(s, 150)) + 'px' }">
        <div class="pimg" :style="{ height: s + 'px' }" :title="x.name + (isImg(x) ? '（点开看大图）' : '（点了下载）')" @click="open(i)">
          <img v-if="isImg(x)" :src="x.src" alt="">
          <span v-else class="pfile"><b>{{ extOf(x.name) }}</b><span>{{ x.name }}</span><small v-if="x.size">{{ fsize(x.size) }}</small></span>
          <span v-if="i === 0 && !files && !readonly && list.length > 1" class="ptag">主图</span>
        </div>
        <div v-if="!readonly" class="pops"><button v-if="i && !files" type="button" title="设为主图" @click="toFront(i)">主图</button><button type="button" title="删除" @click="remove(i)"><Ico n="x" :s="12"/></button></div>
        <select v-if="colors && colors.length && !readonly" class="pcolor" :value="x.color || ''" @change="tag(i, $event.target.value)"><option value="">不分色</option><option v-for="c in colors" :key="c" :value="c">{{ colorName(c) }}</option></select>
      </div>
      <span v-if="limit && list.length > limit" class="pmore" :style="{ height: s + 'px', minWidth: s + 'px' }" @click="viewPics(list, limit)">+{{ list.length - limit }}</span>
      <button v-if="!readonly && list.length < max" type="button" class="padd" :style="{ width: s + 'px', height: s + 'px' }" @click="inp.click()">
        <Ico :n="files ? 'upload' : 'camera'" :s="s < 56 ? 15 : 18"/><span v-if="s >= 56">{{ busy ? '处理中…' : files ? '加附件' : camera ? '拍照' : '加图片' }}</span></button>
    </div>
    <input ref="inp" type="file" :accept="files ? null : 'image/*'" multiple hidden @change="pick">
  </div>`,
})

/* 款式、物料、样衣详情页：改图片（第一张是主图） */
comp('PicsEdit', {
  props: { owner: Object, colors: Array, t: String },
  emits: ['close'],
  setup(p, { emit }) {
    const list = ref([...(p.owner.images || [])])
    function save() { setImages(p.owner, list.value); emit('close'); toast('图片已保存') }
    return { list, save }
  },
  template: `<Modal :t="t || '图片'" :w="660" @close="$emit('close')">
    <Pics v-model="list" :colors="colors" :s="96"/>
    <template #foot><span class="dim">{{ list.length }} 张</span><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">保存</button></template>
  </Modal>`,
})

/* 单据附件（订单、采购单、加工单、出入库单详情右侧） */
comp('DocFiles', {
  props: { doc: Object },
  template: `<div class="docfiles" data-c="附件"><h4>附件<span v-if="doc.files && doc.files.length" class="dim" style="font-weight:400"> {{ doc.files.length }}</span></h4>
    <Pics :model-value="doc.files || []" files :s="56" :max="20" @update:model-value="v => setFiles(doc, v)"/></div>`,
})

expose({ viewer, viewPics, setImages, setFiles, PHOTO_TYPES })

/* ---------- 演示数据用的"照片"：SVG 画的，像拍出来的样子 ----------
 * kind：product 款式上身照（影棚底 + 衣架）/ fabric 布面特写 / defect 瑕疵特写（红圈标出来）/ cutting 裁床 / license 证照 */
const PHOTO_DEFS = PIC_DEFS.match(/<defs>[\s\S]*<\/defs>/)[0]
const svgUri = (w, h, body) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w * 8}" height="${h * 8}">${PHOTO_DEFS}${body}</svg>`)
function fakePhoto(kind, a, b) {
  if (kind === 'product') {
    const st = garmentOf(a), hex = colorOf(b || st.colors[0]).hex
    return svgUri(100, 120, `<defs><radialGradient id="ph-bg" cx=".5" cy=".3" r=".95"><stop offset="0" stop-color="#f7f5f0"/><stop offset="1" stop-color="#d6d3c9"/></radialGradient></defs>
      <rect width="100" height="120" fill="url(#ph-bg)"/><rect y="104" width="100" height="16" fill="#cfcbc0" opacity=".55"/><ellipse cx="50" cy="111" rx="36" ry="2.6" fill="#000" opacity=".1"/>
      <path d="M50 4.5c-2.6 0-3 3.2-.4 4.3.9.4.9 1.4.9 2.4V15" fill="none" stroke="#8d8d8d" stroke-width="1"/><path d="M29 22.5L50 14.5L71 22.5" fill="none" stroke="#7a6548" stroke-width="2.4" stroke-linecap="round"/>
      <g transform="translate(0 13)">${garmentSvg(catOf(st.cat).shape, hex)}</g>`)
  }
  const hex = a || '#c8b48a', tex = hexLum(hex) < 0.45 ? 'l' : 'd'
  const cloth = (w, h) => `<rect width="${w}" height="${h}" fill="${hex}"/><rect width="${w}" height="${h}" fill="url(#pg-twill-${tex})"/>
    <path d="M-5 ${h * 0.3}C${w * 0.3} ${h * 0.2} ${w * 0.6} ${h * 0.5} ${w + 5} ${h * 0.35}V${h * 0.55}C${w * 0.6} ${h * 0.7} ${w * 0.3} ${h * 0.4} -5 ${h * 0.5}Z" fill="#fff" opacity=".09"/>
    <path d="M-5 ${h * 0.75}C${w * 0.35} ${h * 0.6} ${w * 0.7} ${h * 0.9} ${w + 5} ${h * 0.7}V${h + 5}H-5Z" fill="#000" opacity=".1"/>
    <rect width="${w}" height="${h}" fill="url(#pg-shade)"/>`
  if (kind === 'fabric') return svgUri(100, 75, cloth(100, 75))
  if (kind === 'defect') {
    return svgUri(100, 75, `${cloth(100, 75)}<ellipse cx="56" cy="36" rx="9" ry="6" fill="#3a2a10" opacity=".28"/><ellipse cx="58" cy="35" rx="5" ry="3.5" fill="#3a2a10" opacity=".25"/>
      <circle cx="57" cy="36" r="14" fill="none" stroke="#e0322a" stroke-width="1.6"/><path d="M68 26L80 15" stroke="#e0322a" stroke-width="1.2"/>
      <rect x="72" y="7" width="24" height="10" rx="2" fill="#e0322a"/><text x="84" y="14.3" text-anchor="middle" font-size="6" fill="#fff" font-family="sans-serif">${b || '污渍'}</text>`)
  }
  if (kind === 'cutting') {
    const layers = Array.from({ length: 9 }, (_, i) => `<path d="M14 ${52 - i * 2.2}H86L90 ${55 - i * 2.2}H10Z" fill="${hex}" stroke="#000" stroke-opacity=".12" stroke-width=".3"/>`).join('')
    return svgUri(100, 75, `<rect width="100" height="75" fill="#e9e4d8"/><rect y="40" width="100" height="35" fill="#c9b796"/><rect y="40" width="100" height="35" fill="url(#pg-twill-d)" opacity=".5"/>
      ${layers}<path d="M24 35C30 30 40 31 44 35M54 34C62 28 72 30 78 35" fill="none" stroke="#fff" stroke-width=".8" stroke-dasharray="1.5 1"/>
      <rect x="62" y="20" width="4" height="16" rx="1" fill="#555"/><rect x="57" y="14" width="14" height="7" rx="1.5" fill="#777"/>`)
  }
  if (kind === 'license') {
    return svgUri(100, 72, `<rect width="100" height="72" fill="#5c6670"/><g filter="url(#pg-soft)"><rect x="12" y="6" width="76" height="60" fill="#fbf8f0"/></g>
      <rect x="15" y="9" width="70" height="54" fill="none" stroke="#c8a24a" stroke-width=".8"/><text x="50" y="21" text-anchor="middle" font-size="7" font-weight="700" fill="#a3221c" font-family="serif">${b || '营业执照'}</text>
      ${[29, 34, 39, 44, 49].map((y) => `<path d="M22 ${y}H${y === 49 ? 50 : 66}" stroke="#666" stroke-width="1.1" opacity=".55"/>`).join('')}
      <circle cx="70" cy="50" r="7.5" fill="none" stroke="#c8261d" stroke-width="1.2" opacity=".85"/><path d="M70 45.5L71.3 49.2H75.1L72 51.4L73.2 55L70 52.8L66.8 55L68 51.4L64.9 49.2H68.7Z" fill="#c8261d" opacity=".85"/>`)
  }
  return svgUri(100, 75, cloth(100, 75))
}
const demoPic = (kind, a, b, name, extra) => ({ id: 'P' + ++picSeq, name, src: fakePhoto(kind, a, b), at: '2026-09-15', ...extra })

// 种子：几张示意照片，让列表、详情一打开就有图（款式主图按颜色、面料图、异常和报工照片、证照）
;(() => {
  const cf = style('27CF001')
  if (cf) cf.images = cf.colors.map((c) => demoPic('product', cf.code, c, `${cf.code}-${colorName(c)}.jpg`, { color: c }))
  ;['26DS021', '26QJ012', '26DY009'].forEach((code) => { const st = style(code); if (st) st.images = [demoPic('product', code, st.colors[0], code + '.jpg', { color: st.colors[0] })] })
  const ml = mat('ML0012')
  if (ml) ml.images = [...new Set(ml.skus.map((k) => k.color))].filter(Boolean).map((c) => demoPic('fabric', colorOf(c).hex, null, `${ml.name}-${colorName(c)}.jpg`, { color: c }))
  const p2 = db.productions.find((p) => p.style === '26QJ012')
  const cut = p2?.nodes.find((n) => n.name.includes('裁') && n.af)
  if (cut) cut.photos = [demoPic('cutting', colorOf('HE').hex, null, '裁床.jpg', { at: cut.af })]
  const p4 = db.productions.find((p) => p.style === '26DS021')
  if (p4) {
    const iss = as('u1', '2026-10-22', () => reportIssue(p4, { type: '面料质量', desc: '黑色大身有 3 卷布面污渍，挑出来约 40 件的料', party: '供应商', photos: [demoPic('defect', colorOf('HE').hex, '污渍', '布面污渍1.jpg'), demoPic('defect', colorOf('HE').hex, '污渍', '布面污渍2.jpg')] }))
    as('u1', '2026-10-24', () => closeIssue(p4, iss, '供应商补发 3 卷，污渍布退回'))
  }
  const gs = sup('GYS0001')
  if (gs?.certs?.[0]) gs.certs[0].file = demoPic('license', null, gs.certs[0].type, gs.certs[0].type + '.jpg')
})()
