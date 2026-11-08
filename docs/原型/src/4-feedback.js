/* ============================================================
 * 意见：每个模块可以表态（保留 / 不需要 / 要改）、写意见、圈出页面上的位置。
 * 存在浏览器 localStorage（只在这台电脑这个浏览器里）；「意见汇总」页导出 Markdown / JSON 发回来，
 * JSON 可以再导入合并多个人的意见。页面上的区块用 data-c="名字" 标出来，圈位置时吸附到最近的区块。
 * ============================================================ */
const FB_KEY = 'scm-proto-feedback-v1'
const VERDICTS = ['保留', '不需要', '要改']
function loadFb() {
  const empty = { name: '', items: [], verdicts: {} }
  try { return { ...empty, ...JSON.parse(localStorage.getItem(FB_KEY) || '{}') } } catch { return empty }
}
const fb = reactive(loadFb())
watch(fb, () => { try { localStorage.setItem(FB_KEY, JSON.stringify(fb)) } catch { /* 隐私模式存不了，只在本次打开有效 */ } }, { deep: true })
const fbUi = reactive({ open: false, picking: false, draft: null, focus: null })

const navItem = (k) => NAV.flatMap((g) => g.items.map((it) => ({ ...it, g: g.g }))).find((it) => it.k === k)
const curMod = () => nav.r?.mod || 'home'
const nowStamp = () => new Date().toISOString()
const fmtStamp = (s) => { const d = new Date(s); return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}` }
const pinsHere = () => fb.items.filter((x) => x.anchor && x.pat === nav.r?.pat)
const verdictCount = (mod, v) => Object.values(fb.verdicts[mod] || {}).filter((x) => x === v).length

function addComment(text) {
  const t = text.trim()
  if (!t) return false
  fb.items.push({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), mod: curMod(), pat: nav.r?.pat || nav.path, path: nav.path, title: routeTitle(), anchor: fbUi.draft?.anchor || '', rx: fbUi.draft?.rx ?? 0, ry: fbUi.draft?.ry ?? 0, text: t, author: fb.name, at: nowStamp() })
  fbUi.draft = null
  return true
}
function setVerdict(mod, v) {
  const m = (fb.verdicts[mod] ||= {})
  if (m[fb.name] === v) delete m[fb.name]
  else m[fb.name] = v
}

/* ---------- 导出 / 导入 ---------- */
function download(name, text, type) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([text], { type }))
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
function fbMarkdown() {
  const lines = [`# SCM 原型意见`, ``, `导出：${fmtStamp(nowStamp())}${fb.name ? ' · ' + fb.name : ''}`, ``]
  NAV.forEach((g) => g.items.forEach((it) => {
    const items = fb.items.filter((x) => x.mod === it.k)
    const vs = VERDICTS.map((v) => [v, verdictCount(it.k, v)]).filter(([, n]) => n)
    if (!items.length && !vs.length) return
    lines.push(`## ${g.g ? g.g + ' / ' : ''}${it.t}（${it.ph}）`, ``)
    if (vs.length) lines.push(`结论：${vs.map(([v, n]) => `${v} ${n}`).join(' · ')}（${Object.entries(fb.verdicts[it.k] || {}).map(([a, v]) => `${a || '未署名'}：${v}`).join('，')}）`, ``)
    items.forEach((x) => lines.push(`- 【${x.title}${x.anchor ? ' · ' + x.anchor : ''}】${x.text.replace(/\n/g, ' ')} —— ${x.author || '未署名'} ${fmtStamp(x.at)}（#${x.path}）`))
    lines.push(``)
  }))
  return lines.join('\n')
}
const fbJson = () => JSON.stringify({ version: 1, exportedAt: nowStamp(), by: fb.name, items: fb.items, verdicts: fb.verdicts }, null, 2)
function fbImport(text) {
  const d = JSON.parse(text)
  if (!Array.isArray(d.items)) throw new Error('不是导出的意见文件')
  const have = new Set(fb.items.map((x) => x.id))
  const added = d.items.filter((x) => !have.has(x.id))
  fb.items.push(...added)
  Object.entries(d.verdicts || {}).forEach(([mod, m]) => Object.assign((fb.verdicts[mod] ||= {}), m))
  return added.length
}

/* ---------- 右侧意见面板 ---------- */
comp('FeedbackPanel', {
  setup() {
    useEsc(() => { if (!fbUi.picking) fbUi.open = false })
    const text = ref(''), nameDraft = ref(fb.name), box = ref(null)
    const mod = computed(curMod)
    const meta = computed(() => navItem(mod.value) || { t: routeTitle(), ph: '' })
    const items = computed(() => fb.items.filter((x) => x.mod === mod.value).slice().reverse())
    const pinNo = (x) => pinsHere().indexOf(x) + 1
    const mine = computed(() => fb.verdicts[mod.value]?.[fb.name])
    watch(() => fbUi.draft, (d) => { if (d) nextTick(() => box.value?.focus()) })
    function ensureName() {
      if (fb.name) return true
      if (!nameDraft.value.trim()) { toast('先写上你的名字'); return false }
      fb.name = nameDraft.value.trim()
      return true
    }
    function send() { if (ensureName() && addComment(text.value)) { text.value = ''; toast('意见已记下') } }
    function verdict(v) { if (ensureName()) setVerdict(mod.value, v) }
    function remove(x) { fb.items.splice(fb.items.indexOf(x), 1) }
    function open(x) { if (x.path !== nav.path) go(x.path); fbUi.focus = x.id }
    return { fb, fbUi, VERDICTS, text, nameDraft, box, mod, meta, items, pinNo, mine, send, verdict, remove, open, verdictCount, fmtStamp }
  },
  template: `<aside class="fb">
    <header><span>{{ meta.t }}</span><span v-if="meta.ph" class="pill" :class="{ amber: meta.ph === '待定', blue: meta.ph === '二期' }">{{ meta.ph }}</span>
      <button class="btn ghost icon" @click="fbUi.open = false"><Ico n="x"/></button></header>
    <div class="compose">
      <input v-if="!fb.name" class="inp" v-model="nameDraft" placeholder="你的名字">
      <div class="verdict"><button v-for="v in VERDICTS" :key="v" :class="{ on: mine === v }" @click="verdict(v)">{{ v }}<i v-if="verdictCount(mod, v)">{{ verdictCount(mod, v) }}</i></button></div>
      <textarea ref="box" class="inp" v-model="text" placeholder="这里怎么样？" @keydown.meta.enter="send" @keydown.ctrl.enter="send"></textarea>
      <div class="row">
        <span v-if="fbUi.draft" class="anchor-chip"><Ico n="pin" :s="14"/>{{ fbUi.draft.anchor || '页面' }}<button class="btn ghost icon sm" @click="fbUi.draft = null"><Ico n="x" :s="12"/></button></span>
        <button v-else class="btn ghost" @click="fbUi.picking = true"><Ico n="pin" :s="14"/>圈位置</button>
        <span style="flex:1"></span>
        <button class="btn pri" @click="send">发送</button>
      </div>
    </div>
    <div class="items">
      <div v-for="x in items" :key="x.id" class="item" :class="{ hl: fbUi.focus === x.id }">
        <div class="who"><i v-if="pinNo(x)" class="pin-no">{{ pinNo(x) }}</i><b>{{ x.author || '未署名' }}</b><span>{{ fmtStamp(x.at) }}</span>
          <button v-if="x.author === fb.name" @click="remove(x)"><Ico n="trash" :s="13"/></button></div>
        <p>{{ x.text }}</p>
        <a v-if="x.anchor || x.path !== nav.path" class="dim" style="font-size:12px;cursor:pointer" @click="open(x)">{{ x.title }}{{ x.anchor ? ' · ' + x.anchor : '' }}</a>
      </div>
      <div v-if="!items.length" class="empty" style="padding:40px 0">这个模块还没有意见</div>
    </div>
  </aside>`,
})

/* 当前页面上的意见编号点 */
comp('Pins', {
  setup() {
    const pos = ref([])
    const find = (label) => (label === '页面' ? document.querySelector('.main') : [...document.querySelectorAll('[data-c]')].find((e) => e.dataset.c === label))
    const tick = () => {
      pos.value = pinsHere().map((x, i) => {
        const el = find(x.anchor)
        if (!el) return null
        const r = el.getBoundingClientRect()
        if (!r.width) return null
        return { id: x.id, n: i + 1, left: r.left + x.rx * r.width, top: r.top + x.ry * r.height }
      }).filter(Boolean)
    }
    let t
    onMounted(() => { tick(); t = setInterval(tick, 200) })
    onUnmounted(() => clearInterval(t))
    return { pos, fbUi }
  },
  template: `<div class="pins"><i v-for="p in pos" :key="p.id" class="pin-no" :style="{ left: p.left + 'px', top: p.top + 'px' }" @click="fbUi.focus = p.id">{{ p.n }}</i></div>`,
})

/* 圈位置：鼠标移到哪个区块就高亮哪个，点一下记下来 */
comp('Picker', {
  setup() {
    const hl = ref(null)
    useEsc(() => { fbUi.picking = false })
    const target = (e) => {
      const el = document.elementsFromPoint(e.clientX, e.clientY).find((x) => !x.closest('.picking') && !x.closest('.fb'))
      if (!el) return null
      const a = el.closest('[data-c]')
      const box = a || document.querySelector('.main')
      return { label: a ? a.dataset.c : '页面', r: box.getBoundingClientRect() }
    }
    function move(e) { hl.value = target(e) }
    function pick(e) {
      const t = target(e)
      fbUi.picking = false
      if (!t) return
      fbUi.draft = { anchor: t.label, rx: (e.clientX - t.r.left) / t.r.width, ry: (e.clientY - t.r.top) / t.r.height }
    }
    return { hl, move, pick }
  },
  template: `<div class="picking" @mousemove="move" @click="pick"><div v-if="hl" class="hl" :style="{ left: hl.r.left + 'px', top: hl.r.top + 'px', width: hl.r.width + 'px', height: hl.r.height + 'px' }"><span>{{ hl.label }}</span></div></div>`,
})

/* 意见汇总 */
route('/feedback', {
  setup() {
    const file = ref(null)
    const groups = computed(() => NAV.map((g) => ({ g: g.g, items: g.items.map((it) => ({ ...it, list: fb.items.filter((x) => x.mod === it.k), vs: VERDICTS.map((v) => [v, verdictCount(it.k, v)]) })) })))
    const total = computed(() => fb.items.length)
    function imp(e) {
      const f = e.target.files[0]
      if (!f) return
      f.text().then((t) => { try { toast(`导入 ${fbImport(t)} 条意见`) } catch (err) { toast(err.message, null, true) } })
      e.target.value = ''
    }
    async function clear() { if (await ask('清空意见', '这台电脑上记的意见会全部删掉，删了找不回来。先导出备份。', '清空', true)) { fb.items.splice(0); Object.keys(fb.verdicts).forEach((k) => delete fb.verdicts[k]) } }
    const exportMd = () => download('SCM原型意见.md', fbMarkdown(), 'text/markdown')
    const exportJson = () => download('SCM原型意见.json', fbJson(), 'application/json')
    return { groups, total, file, imp, clear, exportMd, exportJson, fmtStamp, fb }
  },
  template: `<div>
    <Head t="意见汇总">
      <input ref="file" type="file" accept=".json" hidden @change="imp">
      <button class="btn ghost" @click="file.click()"><Ico n="upload" :s="14"/>导入</button>
      <button class="btn" @click="exportJson">导出 JSON</button>
      <button class="btn pri" @click="exportMd"><Ico n="download" :s="14"/>导出 Markdown</button>
      <More :items="[{ t: '清空意见', danger: true, fn: clear }]"/>
    </Head>
    <div class="page narrow" data-c="意见汇总">
      <template v-for="g in groups" :key="g.g">
        <div class="h2">{{ g.g || '首页' }}</div>
        <div class="list">
          <div v-for="it in g.items" :key="it.k" style="border-bottom:1px solid var(--line);padding:10px 4px">
            <div class="row"><Ico :n="it.i" :s="15"/><a :href="'#' + it.to" style="font-weight:500">{{ it.t }}</a>
              <span class="pill" :class="{ amber: it.ph === '待定', blue: it.ph === '二期' }">{{ it.ph }}</span>
              <span class="dim" style="font-size:12px">{{ it.ref }}</span>
              <span style="flex:1"></span>
              <span v-for="[v, n] in it.vs.filter(x => x[1])" :key="v" class="pill" :class="{ green: v === '保留', red: v === '不需要', amber: v === '要改' }">{{ v }} {{ n }}</span>
              <span v-if="it.list.length" class="dim" style="font-size:12px">{{ it.list.length }} 条</span>
            </div>
            <div v-for="x in it.list" :key="x.id" style="padding:8px 0 0 25px">
              <div>{{ x.text }}</div>
              <div class="dim" style="font-size:12px">{{ x.author || '未署名' }} · {{ fmtStamp(x.at) }} · <a :href="'#' + x.path">{{ x.title }}{{ x.anchor ? ' · ' + x.anchor : '' }}</a></div>
            </div>
          </div>
        </div>
      </template>
      <p v-if="!total" class="empty">还没有意见。在任何页面点左下角「意见」写。</p>
    </div>
  </div>`,
}, { mod: 'feedback', t: '意见汇总' })
