/* ---------- 勾选后的批量操作、从已有数据里挑 ---------- */

/* 列表勾了行，底部浮出这条：<BatchBar :n="s.picked.length" @clear="s.picked = []"> 放按钮 </BatchBar> */
comp('BatchBar', {
  props: { n: Number },
  emits: ['clear'],
  template: `<div v-if="n" class="batch-bar" role="toolbar" data-c="批量操作"><span class="bb-n">已选 <b>{{ n }}</b> 条</span><span class="bb-sep"></span><slot/><span class="bb-sep"></span><button type="button" class="btn ghost sm" @click="$emit('clear')">取消选择</button></div>`,
})

/* 批量提交 / 审核 / 停用……：只做现在能做的（和详情页按钮同一套判断），其余跳过；一张出错不影响别的，最后一起报
 *   args(d) 给 doAction 的额外参数（订单审核要承诺交期）；返回做成了的单号（列表拿去取消勾选，没成的留着） */
const BATCH_ASK = {
  提交: '提交后等审核，提交了的不能再改，要改先撤回。',
  审核: '每张单按自己的审批流走：轮到你这一级的才审得了；多级审批的单审完这一级还要等下一级。',
  停用: '停用后新建单据里选不到，已有单据不受影响，随时可以恢复。',
}
const docId = (d) => d.no ?? d.code
async function batchAct(kind, docs, a, { args = () => [], note = '' } = {}) {
  const list = docs.filter((d) => mayAct(kind, d, a))
  const u = kind === 'style' ? '条' : '张'
  if (!list.length) { toast(`选中的现在都不能${a}`, null, true); return [] }
  const skip = docs.length - list.length
  const text = [BATCH_ASK[a] || ACT_ASK[a]?.[0] || '', note, skip ? `另外 ${skip} ${u}现在不能${a}，跳过。` : ''].filter(Boolean).join(' ')
  if (!(await ask(`${a} ${list.length} ${u}`, text, a, !!ACT_ASK[a]?.[1]))) return []
  const done = [], half = [], bad = []
  for (const d of list) {
    try {
      const st = d.status
      doAction(kind, d, a, ...args(d))
      ;(a === '审核' && d.status === st ? half : done).push(docId(d))
    } catch (e) { bad.push(`${docId(d)}：${e.message}${e.list?.length ? '（' + e.list.join('；') + '）' : ''}`) }
  }
  const msg = [done.length && `${a}了 ${done.length} ${u}`, half.length && `${half.length} 张这一级审过了，等下一级`].filter(Boolean).join('，')
  if (bad.length) ui.err = { msg: `${msg ? msg + '；' : ''}${bad.length} ${u}没成功`, list: bad }
  else toast(msg)
  return [...done, ...half]
}

/* 新建单据加明细：从物料库 / 款式库里搜、按分类筛、一次勾多条
 *   <DataPick v-if="picking" kind="sku" :sup="f.supplier" :date="f.date" :have="f.lines.map(l => l.sku)" @pick="addSkus" @close="picking = false"/>
 *   kind：sku = 物料 SKU，style = 款式。have = 单上已经有的（灰掉不能再选）
 *   sup 给了：默认只看这家的料，单价按这家的价格档案；wh 给了：库存列显示这个仓的，否则显示物料仓合计 */
const whSkuQty = (w, s) => rnd(sum(Object.entries(db.stock).filter(([k]) => k.startsWith(w + '|' + s + '|')), ([, v]) => v), 3)
comp('DataPick', {
  props: { kind: { type: String, default: 'sku' }, sup: String, date: String, wh: String, have: { type: Array, default: () => [] } },
  emits: ['pick', 'close'],
  setup(p, { emit }) {
    const q = ref(''), grp = ref(''), mine = ref(!!p.sup), chosen = ref([])
    const isSku = p.kind !== 'style'
    const rows = computed(() => (isSku
      ? db.materials.filter((m) => m.status === '启用').flatMap((m) => m.skus.filter((k) => k.status !== '停用').map((k) => ({ code: k.code, name: skuName(k.code), g: matCat(m.cat).parent || m.type, m })))
      : db.styles.filter((s) => s.status === '启用').map((s) => ({ code: s.code, name: s.name, g: catOf(s.cat).name, s }))
    ).map((r) => ({ ...r, had: p.have.includes(r.code) })))
    const scoped = computed(() => rows.value.filter((r) => (!isSku || !mine.value || r.m.supplier === p.sup) && matchQ(q.value, r.code, r.name, r.g, isSku ? r.m.supNo || '' : r.s.brand || '')))
    const groups = computed(() => [{ v: '', t: '全部', n: scoped.value.length }, ...[...new Set(rows.value.map((r) => r.g))].map((g) => ({ v: g, t: g, n: scoped.value.filter((r) => r.g === g).length }))])
    const shown = computed(() => scoped.value.filter((r) => !grp.value || r.g === grp.value))
    const free = computed(() => shown.value.filter((r) => !r.had))
    const allOn = computed(() => free.value.length > 0 && free.value.every((r) => chosen.value.includes(r.code)))
    const someOn = computed(() => free.value.some((r) => chosen.value.includes(r.code)))
    function toggle(r) {
      if (r.had) return
      const i = chosen.value.indexOf(r.code)
      if (i >= 0) chosen.value.splice(i, 1); else chosen.value.push(r.code)
    }
    function toggleAll() {
      const ks = free.value.map((r) => r.code)
      chosen.value = allOn.value ? chosen.value.filter((k) => !ks.includes(k)) : [...new Set([...chosen.value, ...ks])]
    }
    const stockOf = (r) => (p.wh ? whSkuQty(p.wh, r.code) : skuStock(r.code, '物料仓'))
    const priceOf = (r) => (p.sup ? poDefaultPrice(p.sup, r.code, p.date || clock.today) : skuPrice(r.code))
    const ok = () => { emit('pick', [...chosen.value]); emit('close') }
    const title = isSku ? '从物料库选' : '从款式库选'
    return { isSku, q, grp, mine, chosen, groups, shown, allOn, someOn, toggle, toggleAll, stockOf, priceOf, ok, title }
  },
  template: `<Modal :t="title" :w="880" @close="$emit('close')">
    <div class="dpk-top" data-c="选数据：搜索">
      <Search v-model="q" :ph="isSku ? '物料名称、编码、颜色、供应商货号' : '款号、名称、品牌'"/>
      <label v-if="isSku && sup" class="chk"><input type="checkbox" v-model="mine">只看{{ supName(sup) }}的料</label>
    </div>
    <div class="dpk-chips" data-c="选数据：分类"><button v-for="g in groups" :key="g.v" type="button" :class="{ on: grp === g.v }" @click="grp = g.v">{{ g.t }}<i>{{ g.n }}</i></button></div>
    <div class="dpk-list" data-c="选数据：列表"><table class="tbl flat">
      <thead><tr><th class="ck"><input type="checkbox" :checked="allOn" :indeterminate.prop="someOn && !allOn" :disabled="!shown.length" title="勾选 / 取消下面全部" @change="toggleAll"></th>
        <template v-if="isSku"><th>物料</th><th>分类</th><th>供应商</th><th class="num">{{ wh ? '这个仓库存' : '物料仓库存' }}</th><th class="num">单价</th></template>
        <template v-else><th>款式</th><th>品类</th><th>季节</th><th>颜色</th><th>生产资料包</th><th class="num">吊牌价</th></template></tr></thead>
      <tbody>
        <tr v-for="r in shown" :key="r.code" :class="{ sel: chosen.includes(r.code), off: r.had }" @click="toggle(r)">
          <td class="ck"><input type="checkbox" :checked="r.had || chosen.includes(r.code)" :disabled="r.had" @click.stop @change="toggle(r)"></td>
          <template v-if="isSku">
            <td><span class="row"><Thumb :code="r.code" :s="30"/><span class="col"><span>{{ r.name }}</span><small class="code dim">{{ r.code }}<template v-if="r.had"> · 已在单上</template></small></span></span></td>
            <td class="mut">{{ matCat(r.m.cat).name }}</td>
            <td class="mut">{{ supName(r.m.supplier) }}</td>
            <td class="num">{{ fq(stockOf(r)) }} <small class="dim">{{ r.m.unit }}</small></td>
            <td class="num">{{ priceOf(r) == null ? '—' : fm(priceOf(r)) }}</td>
          </template>
          <template v-else>
            <td><span class="row"><Thumb :code="r.code" :s="30"/><span class="col"><span>{{ r.name }}</span><small class="code dim">{{ r.code }}<template v-if="r.had"> · 已在单上</template></small></span></span></td>
            <td class="mut">{{ r.g }}</td>
            <td class="mut">{{ r.s.year }} {{ seasonName(r.s.season) }}</td>
            <td><span class="row g4"><Sw v-for="c in r.s.colors" :key="c" :c="c" :n="false"/></span></td>
            <td><span v-if="activeTp(r.code)" class="mut">已生效</span><span v-else class="amber">未生效</span></td>
            <td class="num">{{ r.s.tagPrice == null ? '—' : fm(r.s.tagPrice) }}</td>
          </template>
        </tr>
        <tr v-if="!shown.length"><td :colspan="isSku ? 6 : 7" class="empty">{{ q ? '没有匹配「' + q + '」的' + (isSku ? '物料' : '款式') : isSku && mine ? supName(sup) + '没有这类料，取消「只看」看全部' : '这一类还没有' + (isSku ? '启用的物料' : '启用的款式') }}</td></tr>
      </tbody></table></div>
    <template #foot><span class="dim">已选 <b style="color:var(--fg)">{{ chosen.length }}</b> 条</span><a v-if="chosen.length" style="font-size:12px" @click="chosen = []">清空</a><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!chosen.length" @click="ok">加入{{ chosen.length ? ' ' + chosen.length + ' 条' : '' }}</button></template>
  </Modal>`,
})
