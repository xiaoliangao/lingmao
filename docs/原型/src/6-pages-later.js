/* ============================================================
 * 二期 / 待定模块：企划（M02）、样衣（M03）、核价（M05）、质检（M09）、成品仓（M10）、对账（M11）、报表（13.06）
 * 都从主线单据"长"出来，不另起一套数据：
 *   样衣评审通过 → 转大货生成款式；资料包 + 物料报价 → 核价 → 订单毛利
 *   回货 → 待检 → 成品入库 → 发货（→ 客户应收，TRADE 开时才有）；采购入库、回货 → 供应商对账 → 付款
 * 名字都带前缀（smp / plan / cost / qc / fg / fin），免得和别的文件的全局名撞上。
 * ============================================================ */
ST_KIND.done.push('已核销')
ST_KIND.dashed.push('未核销')
const seasonName = (c) => db.seasons.find((x) => x.code === c)?.name || c
const finMonthEnd = (m) => { const [y, mo] = m.split('-').map(Number); return ymd(new Date(y, mo, 0)) }

/* ============================== 企划 ============================== */
const PLAN = { year: 2027, season: 'C' }
db.plans = [
  { cat: 'F', theme: '城市通勤', band: [699, 999], styles: 3, qty: 2400 },
  { cat: 'J', theme: '城市通勤', band: [499, 799], styles: 2, qty: 1200 },
  { cat: 'K', theme: '城市通勤', band: [399, 499], styles: 3, qty: 1800 },
  { cat: 'T', theme: '轻松日常', band: [149, 249], styles: 6, qty: 6000 },
  { cat: 'W', theme: '轻松日常', band: [299, 459], styles: 2, qty: 1200 },
  { cat: 'L', theme: '春日约会', band: [459, 699], styles: 3, qty: 1500 },
]
db.planTasks = [
  { id: 'RW01', title: '风衣廓形与面料方向', cat: 'F', who: 'u4', due: '2026-08-10', status: '已完成', link: '/styles/27CF001' },
  { id: 'RW02', title: '短夹克改版评审', cat: 'J', who: 'u4', due: '2026-11-12', status: '进行中', link: '/samples/YY26100005' },
  { id: 'RW03', title: '针织开衫配色定样', cat: 'W', who: 'u8', due: '2026-11-10', status: '进行中', link: '/samples/YY26100008' },
  { id: 'RW04', title: 'T 恤 6 款图案与面料', cat: 'T', who: 'u4', due: '2026-11-20', status: '待开始', link: '/styles/27CT003' },
  { id: 'RW05', title: '连衣裙换平价面料重做', cat: 'L', who: 'u4', due: '2026-11-25', status: '待开始', link: '/samples/YY26100002' },
  { id: 'RW06', title: '阔腿裤版型', cat: 'K', who: 'u8', due: '2026-11-18', status: '待开始', link: '/samples/YY26110001' },
]
function planActual(r) {
  const ss = db.styles.filter((s) => s.year === PLAN.year && s.season === PLAN.season && s.cat === r.cat && s.status !== '停用')
  const codes = ss.map((s) => s.code)
  return {
    list: ss,
    styles: ss.length,
    qty: sum(db.orders.filter((o) => o.status !== '已取消').flatMap((o) => o.lines).filter((l) => codes.includes(l.style)), (l) => l.qty),
    outBand: ss.filter((s) => s.tagPrice && (s.tagPrice < r.band[0] || s.tagPrice > r.band[1])).length,
    dev: (db.samples || []).filter((x) => x.year === PLAN.year && x.season === PLAN.season && x.cat === r.cat && ['开发中', '已通过'].includes(x.status)),
  }
}
function planTaskNew(f) {
  if (!f.title) fail('任务要写内容')
  return add(db.planTasks, { id: 'RW' + String(db.planTasks.length + 1).padStart(2, '0'), status: '待开始', link: '', ...f })
}

route('/plans', {
  setup() {
    const s = reactive({ tab: 'plan', edit: false, open: null, task: null, add: false })
    const total = computed(() => {
      const a = db.plans.map(planActual)
      return { styles: sum(db.plans, (r) => r.styles), qty: sum(db.plans, (r) => r.qty), as: sum(a, (x) => x.styles), aq: sum(a, (x) => x.qty) }
    })
    const cols = ['待开始', '进行中', '已完成']
    const tasks = (c) => db.planTasks.filter((t) => t.status === c)
    return { s, total, cols, tasks, PLAN }
  },
  template: `<div>
    <Head :t="PLAN.year + ' ' + seasonName(PLAN.season) + '季企划'">
      <button v-if="s.tab === 'plan'" class="btn" @click="s.edit = !s.edit">{{ s.edit ? '改完了' : '改计划' }}</button>
      <button v-else class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建任务</button>
    </Head>
    <div class="bar" data-c="切换"><Seg v-model="s.tab" :items="[['plan', '企划'], ['task', '任务', db.planTasks.filter(t => t.status !== '已完成').length]]"/></div>
    <div v-if="s.tab === 'plan'" data-c="企划表">
      <table class="tbl">
        <thead><tr><th>品类</th><th>主题</th><th>价格带</th><th>款数</th><th class="num">在开发</th><th>件数</th><th></th></tr></thead>
        <tbody>
          <template v-for="r in db.plans" :key="r.cat">
            <tr class="link" @click="s.open = s.open === r.cat ? null : r.cat">
              <td>{{ catOf(r.cat).name }}</td><td class="mut">{{ r.theme }}</td>
              <td><template v-if="!s.edit">¥{{ r.band[0] }}–{{ r.band[1] }}</template><span v-else class="row g4" @click.stop><input class="inp cell num" style="width:64px" v-model.number="r.band[0]">–<input class="inp cell num" style="width:64px" v-model.number="r.band[1]"></span></td>
              <td><span class="prog" :class="{ done: planActual(r).styles >= r.styles }"><b><i :style="{ width: fpct(planActual(r).styles, r.styles) + '%' }"></i></b>{{ planActual(r).styles }} / <input v-if="s.edit" class="inp cell num" style="width:48px" v-model.number="r.styles" @click.stop><template v-else>{{ r.styles }}</template></span></td>
              <td class="num">{{ planActual(r).dev.length || '' }}</td>
              <td><span class="prog" :class="{ done: planActual(r).qty >= r.qty }"><b><i :style="{ width: fpct(planActual(r).qty, r.qty) + '%' }"></i></b>{{ planActual(r).qty }} / <input v-if="s.edit" class="inp cell num" style="width:64px" v-model.number="r.qty" @click.stop><template v-else>{{ r.qty }}</template></span></td>
              <td><span v-if="planActual(r).outBand" class="flag amber">{{ planActual(r).outBand }} 款超出价格带</span></td>
            </tr>
            <tr v-if="s.open === r.cat"><td></td><td colspan="6" style="padding:10px 0 14px;white-space:normal">
              <div class="chips">
                <a v-for="x in planActual(r).list" :key="x.code" class="chip" :href="'#/styles/' + x.code"><Thumb :code="x.code" :s="18"/><span class="code">{{ x.code }}</span>{{ x.name }}<span class="dim">{{ fm(x.tagPrice) }}</span></a>
                <a v-for="x in planActual(r).dev" :key="x.no" class="chip" :href="'#/samples/' + x.no"><Thumb :code="x.no" :s="18"/><span class="code">{{ x.no }}</span>{{ x.name }}<span class="dim">样衣</span></a>
                <span v-if="!planActual(r).list.length && !planActual(r).dev.length" class="dim">这个品类还没有款，也没有在开发的样衣</span>
              </div>
            </td></tr>
          </template>
        </tbody>
        <tfoot><tr><td>合计</td><td></td><td></td><td>{{ total.as }} / {{ total.styles }}</td><td></td><td>{{ total.aq }} / {{ total.qty }}</td><td></td></tr></tfoot>
      </table>
    </div>
    <div v-else class="kanban" data-c="任务看板">
      <div v-for="c in cols" :key="c">
        <h4>{{ c }} <span class="dim">{{ tasks(c).length }}</span></h4>
        <div v-for="t in tasks(c)" :key="t.id" class="kcard" @click="s.task = t">
          <div class="row" style="align-items:flex-start"><span style="flex:1">{{ t.title }}</span><span v-if="t.images && t.images.length" class="thumb photo" style="width:40px;height:40px"><img :src="t.images[0].src" alt=""></span></div>
          <div class="row dim" style="font-size:12px;margin-top:6px">{{ catOf(t.cat).name }} · {{ userName(t.who) }}<span style="flex:1"></span><Days v-if="c !== '已完成'" :d="t.due"/><span v-else>{{ fd(t.due) }}</span></div>
        </div>
        <div v-if="!tasks(c).length" class="dim" style="padding:8px 4px;font-size:12px">没有任务</div>
      </div>
    </div>
    <Modal v-if="s.task" :t="s.task.title" :w="460" @close="s.task = null">
      <div class="form">
        <div class="f full">状态<Seg v-model="s.task.status" :items="cols"/></div>
        <label class="f">负责人<select class="sel" v-model="s.task.who"><option v-for="u in db.users.filter(u => ['开发', '版师'].includes(u.role))" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
        <label class="f">截止<input class="inp" type="date" v-model="s.task.due"></label>
      </div>
      <template #foot><a v-if="s.task.link" class="btn" :href="'#' + s.task.link" @click="s.task = null">打开{{ s.task.link.startsWith('/samples') ? '样衣' : '款式' }}</a><span class="sp"></span><button class="btn pri" @click="s.task = null">好</button></template>
    </Modal>
    <PlanTaskNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'plans', t: '企划' })

comp('PlanTaskNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ title: '', cat: 'T', who: 'u4', due: addDays(clock.today, 14), images: [] })
    const save = () => { if (run(() => planTaskNew({ ...f, images: [...f.images] }))) emit('close') }
    return { f, save }
  },
  template: `<Modal t="新建开发任务" :w="460" @close="$emit('close')">
    <div class="form">
      <label class="f full">内容<input class="inp" v-model="f.title"></label>
      <label class="f">品类<select class="sel" v-model="f.cat"><option v-for="c in liveCats()" :key="c.code" :value="c.code">{{ c.name }}</option></select></label>
      <label class="f">负责人<select class="sel" v-model="f.who"><option v-for="u in db.users.filter(u => ['开发', '版师'].includes(u.role))" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
      <label class="f">截止<input class="inp" type="date" v-model="f.due"></label>
      <div class="f full" data-c="参考图">参考图<Pics v-model="f.images" :s="64" :tip="(TRADE ? '灵感图、竞品图、客户发来的款都可以' : '灵感图、竞品图都可以') + '；Ctrl+V 能直接贴截图'"/></div>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建任务</button></template>
  </Modal>`,
})

/* ============================== 样衣 ============================== */
// 节点：接单 → 纸样 → 裁剪 → 车版 → 送样，从需求日期往前倒排
const SMP_NODES = [['接单', 1], ['纸样', 2], ['裁剪', 1], ['车版', 3], ['送样', 1]]
function smpPlan(need) {
  let end = need
  return SMP_NODES.slice().reverse().map(([name, days]) => { const plan = end; end = addDays(plan, -days); return { name, plan, done: null } }).reverse()
}
const smpCur = (s) => s.vers[s.vers.length - 1]
const smpState = (s) => (s.status === '开发中' ? smpCur(s).status : s.status)
const smpNode = (v) => v.nodes.find((n) => !n.done)
const smpWho = (v) => (v.way === '外部工厂' ? supName(v.factory) : v.maker ? userName(v.maker) : '—')
function smpNodeState(n) {
  if (n.done) return n.done > n.plan ? 'late' : 'done'
  if (clock.today > n.plan) return 'late'
  return diffDays(n.plan, clock.today) <= 1 ? 'warn' : ''
}
function smpVersion(s, f) {
  const v = add(s.vers, { ver: s.vers.length + 1, no: nextNo('DY'), type: '初样', way: '', factory: null, maker: null, qty: 1, reason: '', nodes: [], review: null, date: clock.today, ...f, status: '待派单' })
  act(s, `第 ${v.ver} 版打样单 ${v.no}${v.reason ? '，改版原因：' + v.reason : ''}`)
  return v
}
function smpNew(f) {
  if (!f.name) fail('样衣名称要填')
  if (!f.colors?.length) fail('至少选一个颜色')
  const p = 'YY' + clock.today.slice(2, 4) + clock.today.slice(5, 7)
  const s = add(db.samples, { no: p + String(db.samples.filter((x) => x.no.startsWith(p)).length + 1).padStart(4, '0'), brand: '织间', sizeGroup: 'W5', source: '自主开发', designer: db.me, tag: '通勤', wave: '第 1 波', fabric: '', tagPrice: null, estCost: null, style: null, ...f, status: '开发中', vers: [], log: [] })
  act(s, '新建')
  smpVersion(s, { type: '初样', need: f.need || addDays(clock.today, 10) })
  return s
}
function smpDispatch(s, f) {
  const v = smpCur(s)
  if (v.status !== '待派单') fail('这一版已经派过了')
  if (f.way === '外部工厂') {
    if (!f.factory) fail('要选打样工厂')
    if (!canUseSupplier(f.factory, '打样')) fail(`${supName(f.factory)} 现在是「${sup(f.factory).status}」，不能打样`)
  } else if (!f.maker) fail('要选版师')
  Object.assign(v, { way: f.way, factory: f.way === '外部工厂' ? f.factory : null, maker: f.way === '内部版房' ? f.maker : null, need: f.need || v.need, status: '打样中', nodes: smpPlan(f.need || v.need) })
  act(s, `第 ${v.ver} 版派给 ${smpWho(v)}，${fd(v.need)} 要样`)
}
function smpReport(s, i, date) {
  const v = smpCur(s)
  if (v.status !== '打样中') fail('打样中才能报节点')
  const n = v.nodes[i]
  n.done = date || clock.today
  act(s, `第 ${v.ver} 版 ${n.name}完成`)
  if (v.nodes.every((x) => x.done)) { v.status = '待评审'; act(s, `第 ${v.ver} 版已送样，待评审`) }
  return v.nodes.slice(0, i).some((x) => !x.done) ? '前面还有节点没报完成' : null
}
function smpReview(s, f) {
  const v = smpCur(s)
  if (v.status !== '待评审') fail('送样以后才能评审')
  if (!f.result) fail('选一个结论')
  if (f.result !== '通过' && !f.note) fail('改版、淘汰要写意见')
  v.review = { at: clock.today, by: f.by?.length ? [...f.by] : [db.me], result: f.result, note: f.note || '', photos: f.photos || [] }
  act(s, `第 ${v.ver} 版评审：${f.result}${f.note ? '，' + f.note : ''}`)
  if (f.result === '通过') { v.status = '已通过'; s.status = '已通过' }
  if (f.result === '淘汰') { v.status = '已淘汰'; s.status = '已淘汰' }
  if (f.result === '改版') { v.status = '需改版'; smpVersion(s, { type: '改版样', way: v.way, factory: v.factory, maker: v.maker, need: addDays(clock.today, 7), reason: f.note }) }
}
function smpConvert(s, f) {
  if (s.status !== '已通过') fail('评审通过的样衣才能转大货')
  if (!f.factory) fail('要选默认加工厂')
  const st = createStyle({ code: f.code || undefined, big: f.big, name: f.name || s.name, cat: s.cat, year: s.year, season: s.season, wave: s.wave, tag: s.tag, colors: [...s.colors], sizeGroup: s.sizeGroup, tagPrice: s.tagPrice, cost: s.estCost, factory: f.factory, source: s.source, ...ifTrade({ customer: s.customer, custNo: s.custNo }), designer: s.designer, sample: s.no, images: [...(s.images || [])] })
  Object.assign(s, { status: '已转大货', style: st.code, convertedAt: clock.today, convertedBy: db.me })
  act(s, '转大货 → ' + st.code)
  act(st, '由样衣 ' + s.no + ' 转大货')
  if (s.devBom) carryDevTp(st.code, s.devBom)
  return st
}

// 种子：一个已转大货（27CF001 的来源）、一个第 2 版待评审、一个车版延期、一个已淘汰、一个刚建待派单
// late[i]：第 i 个节点比计划晚几天完成（负数 = 提前），null = 还没完成
function smpSeedNodes(need, late) { return smpPlan(need).map((n, i) => ({ ...n, done: late[i] == null ? null : addDays(n.plan, late[i]) })) }
db.samples = [
  { no: 'YY26070003', name: '女式风衣', cat: 'F', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['KQ', 'ZQ', 'HE'], sizeGroup: 'W5', brand: '织间', source: '自主开发', designer: 'u4', fabric: '220g 斜纹棉，恒丰面料', tagPrice: 899, estCost: 330, status: '已转大货', style: '27CF001', convertedAt: '2026-08-20', convertedBy: 'u4',
    vers: [
      { ver: 1, no: 'DY202607150001', type: '初样', way: '外部工厂', factory: 'GYS0004', qty: 1, need: '2026-07-28', reason: '', status: '需改版', nodes: smpSeedNodes('2026-07-28', [0, 0, -1, 0, 0]), review: { at: '2026-07-29', by: ['u4', 'u6'], result: '改版', note: '领口偏大，肩宽收 1cm' } },
      { ver: 2, no: 'DY202607290001', type: '改版样', way: '外部工厂', factory: 'GYS0004', qty: 1, need: '2026-08-12', reason: '领口偏大，肩宽收 1cm', status: '已通过', nodes: smpSeedNodes('2026-08-12', [0, 0, 0, 0, 0]), review: { at: '2026-08-13', by: ['u4', 'u6'], result: '通过', note: '' } },
    ],
    log: [{ at: '2026-07-15', who: '许然', text: '新建' }, { at: '2026-07-29', who: '许然', text: '第 1 版评审：改版，领口偏大，肩宽收 1cm' }, { at: '2026-08-13', who: '许然', text: '第 2 版评审：通过' }, { at: '2026-08-20', who: '许然', text: '转大货 → 27CF001' }] },
  { no: 'YY26100005', name: '廓形短夹克', cat: 'J', year: 2027, season: 'C', wave: '第 1 波', tag: '休闲', colors: ['YM', 'HE'], sizeGroup: 'W5', brand: '织间', source: '自主开发', designer: 'u4', fabric: '棉涤帆布，面料商还没定', tagPrice: 659, estCost: 240, status: '开发中', style: null,
    vers: [
      { ver: 1, no: 'DY202610080001', type: '初样', way: '外部工厂', factory: 'GYS0006', qty: 1, need: '2026-10-20', reason: '', status: '需改版', nodes: smpSeedNodes('2026-10-20', [0, 0, 0, 0, 0]), review: { at: '2026-10-21', by: ['u4'], result: '改版', note: '下摆收短 3cm，袖口加袢' } },
      { ver: 2, no: 'DY202610210001', type: '改版样', way: '外部工厂', factory: 'GYS0006', qty: 1, need: '2026-11-06', reason: '下摆收短 3cm，袖口加袢', status: '待评审', nodes: smpSeedNodes('2026-11-06', [0, 0, 0, -1, 0]), review: null },
    ],
    log: [{ at: '2026-10-08', who: '许然', text: '新建' }, { at: '2026-10-21', who: '许然', text: '第 1 版评审：改版，下摆收短 3cm，袖口加袢' }, { at: '2026-11-06', who: '许然', text: '第 2 版已送样，待评审' }] },
  { no: 'YY26100008', name: '针织开衫', cat: 'W', year: 2027, season: 'C', wave: '第 2 波', tag: '通勤', colors: ['MB', 'WL'], sizeGroup: 'W5', brand: '织间', source: '自主开发', designer: 'u4', fabric: '羊毛混纺纱，工厂找', tagPrice: 429, estCost: 160, status: '开发中', style: null,
    vers: [
      { ver: 1, no: 'DY202610250001', type: '初样', way: '外部工厂', factory: 'GYS0005', qty: 2, need: '2026-11-08', reason: '', status: '打样中', nodes: smpSeedNodes('2026-11-08', [0, 0, 0, null, null]), review: null },
    ],
    log: [{ at: '2026-10-25', who: '许然', text: '新建' }] },
  { no: 'YY26100002', name: '印花连衣裙', cat: 'L', year: 2027, season: 'X', wave: '第 2 波', tag: '休闲', colors: ['JH'], sizeGroup: 'W5', brand: '织间', ...ifTrade({ source: '客户来样', customer: 'KH0001', custNo: 'HD-S27-D07', fabric: '印花雪纺，客户指定' }, { source: '选款', fabric: '印花雪纺' }), designer: 'u4', tagPrice: 599, estCost: 290, status: '已淘汰', style: null,
    vers: [
      { ver: 1, no: 'DY202610100001', type: '初样', way: '内部版房', maker: 'u8', qty: 1, need: '2026-10-22', reason: '', status: '已淘汰', nodes: smpSeedNodes('2026-10-22', [0, 1, 0, 0, 0]), review: { at: '2026-10-23', by: ['u4', 'u6'], result: '淘汰', note: '面料成本超目标 30%，印花效果一般' } },
    ],
    log: [{ at: '2026-10-10', who: '许然', text: '新建' }, { at: '2026-10-23', who: '赵启', text: '第 1 版评审：淘汰，面料成本超目标 30%，印花效果一般' }] },
  { no: 'YY26110001', name: '直筒阔腿裤', cat: 'K', year: 2027, season: 'C', wave: '第 1 波', tag: '通勤', colors: ['HE', 'MB'], sizeGroup: 'W5', brand: '织间', source: '自主开发', designer: 'u4', fabric: '', tagPrice: 459, estCost: null, status: '开发中', style: null,
    vers: [{ ver: 1, no: 'DY202611060001', type: '初样', way: '', factory: null, maker: null, qty: 1, need: '2026-11-20', reason: '', status: '待派单', nodes: [], review: null }],
    log: [{ at: '2026-11-06', who: '许然', text: '新建' }] },
]
if (style('27CF001')) style('27CF001').sample = 'YY26070003'
// 样衣的研发 BOM（打样估价用）：转大货时带到款式上，复制成生产 BOM V1 草稿（§6.3.3、§6.4.1）
{
  const s = db.samples.find((x) => x.no === 'YY26100005')
  if (s) s.devBom = {
    bom: [
      { part: '大身', mat: 'ML0018', mode: '随款色', map: { YM: 'ML0018-YM', HE: 'ML0018-HE' }, um: '分码', uses: SZ(1.5, 1.6, 1.7, 1.8, 1.9), loss: 3, src: '我方采购', supplier: 'GYS0001', price: 21.5 },
      { part: '纽扣', mat: 'FK0021', mode: '固定', sku: 'FK0021-HE', um: '统一', use: 6, loss: 2, src: '我方采购', supplier: 'GYS0002', price: 0.38 },
      ...['BX0001', 'BX0002', 'BP0001', 'BD0001'].map((k, i) => label(k, { part: ['主唛', '洗唛', '吊牌', '包装袋'][i] })),
    ],
    ops: [{ cat: '车缝', name: '整件加工（估）', req: '', sec: 2800, price: 36, note: '按打样工厂口头报价估' }],
    note: '打样估价：帆布按 21.5 估',
  }
}

SEARCH.push(() => db.samples.map((s) => ({ t: s.no + ' ' + s.name, sub: '样衣', to: '/samples/' + s.no, i: 'scissors' })))
TODOS.push(() => [{ t: '样衣待评审', n: db.samples.filter((s) => smpState(s) === '待评审').length, to: '/samples?s=待评审', i: 'scissors' }])

route('/samples', {
  setup() {
    const s = reactive({ tab: nav.query.s || '全部', q: '', add: false })
    const tabs = computed(() => [['全部', '全部', db.samples.length], ...['待派单', '打样中', '待评审', '已通过', '已转大货', '已淘汰'].map((k) => [k, k, db.samples.filter((x) => smpState(x) === k).length]).filter((x) => x[2] || x[0] === s.tab)])
    const rows = computed(() => db.samples.filter((x) => (s.tab === '全部' || smpState(x) === s.tab) && (!s.q || (x.no + x.name).toLowerCase().includes(s.q.toLowerCase()))).slice().reverse())
    return { s, tabs, rows }
  },
  template: `<div>
    <Head t="样衣"><button class="btn pri" @click="s.add = true"><Ico n="plus" :s="14"/>新建样衣</button></Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="样衣列表">
      <Tbl :rows="rows" link @row="x => go('/samples/' + x.no)" empty="没有符合条件的样衣" :cols="[
        { k: 'no', t: '样衣' }, { k: 'name', t: '名称' }, { k: 'season', t: '季节', f: x => x.year + ' ' + seasonName(x.season) + ' · ' + catOf(x.cat).name },
        { k: 'ver', t: '版次', f: x => '第 ' + smpCur(x).ver + ' 版 · ' + smpCur(x).type }, { k: 'node', t: '当前节点' }, { k: 'who', t: '打样', f: x => smpWho(smpCur(x)) },
        { k: 'need', t: '要样', f: x => fd(smpCur(x).need) }, { k: 'status', t: '状态' }]">
        <template #no="{ r }"><span class="row"><Thumb :code="r.no" :s="26"/><span class="code">{{ r.no }}</span></span></template>
        <template #node="{ r }"><template v-if="smpCur(r).status === '打样中' && smpNode(smpCur(r))"><span :class="{ red: smpNodeState(smpNode(smpCur(r))) === 'late' }">{{ smpNode(smpCur(r)).name }}</span><span v-if="smpNodeState(smpNode(smpCur(r))) === 'late'" class="flag red" style="margin-left:6px">延期</span></template><span v-else class="dim">—</span></template>
        <template #status="{ r }"><St :s="smpState(r)"/></template>
      </Tbl>
    </div>
    <SmpNew v-if="s.add" @close="s.add = false"/>
  </div>`,
}, { mod: 'samples', t: '样衣' })

comp('SmpNew', {
  emits: ['close'],
  setup(_, { emit }) {
    const f = reactive({ name: '', cat: 'T', year: 2027, season: 'C', colors: [], source: '自主开发', customer: '', tagPrice: null, need: addDays(clock.today, 10), images: [] })
    const toggle = (c) => { const i = f.colors.indexOf(c); i >= 0 ? f.colors.splice(i, 1) : f.colors.push(c) }
    function save() { const s = run(() => smpNew({ ...f, colors: [...f.colors], images: [...f.images], customer: (TRADE && f.customer) || null })); if (s?.no) { emit('close'); go('/samples/' + s.no) } }
    return { f, toggle, save }
  },
  template: `<Modal t="新建样衣" :w="640" @close="$emit('close')">
    <div class="form">
      <label class="f full">名称<input class="inp" v-model="f.name"></label>
      <label class="f">品类<select class="sel" v-model="f.cat"><option v-for="c in liveCats()" :key="c.code" :value="c.code">{{ c.path }} / {{ c.name }}</option></select></label>
      <label class="f">季节<span class="row g4"><select class="sel" v-model.number="f.year"><option>2026</option><option>2027</option></select><select class="sel" v-model="f.season"><option v-for="x in liveSeasons(f.season)" :key="x.code" :value="x.code">{{ x.name }}</option></select></span></label>
      <label class="f">来源<select class="sel" v-model="f.source"><option>自主开发</option><option v-if="TRADE">客户来样</option><option>选款</option></select></label>
      <label v-if="TRADE" class="f">客户<select class="sel" v-model="f.customer" :disabled="f.source !== '客户来样'"><option value="">—</option><option v-for="c in db.customers.filter(c => c.status !== '停用' || c.code === f.customer)" :key="c.code" :value="c.code">{{ c.short }}</option></select></label>
      <div class="f full">颜色<div class="chips"><button v-for="c in db.colors.filter(c => c.status !== '停用' || f.colors.includes(c.code))" :key="c.code" class="chip" :class="{ on: f.colors.includes(c.code) }" @click="toggle(c.code)"><Sw :c="c.code"/></button></div></div>
      <label class="f">预估吊牌价<input class="inp num" type="number" v-model.number="f.tagPrice"></label>
      <label class="f">要样日期<input class="inp" type="date" v-model="f.need"></label>
      <div class="f full" data-c="样衣图片">图片<Pics v-model="f.images" :colors="f.colors" :tip="(TRADE ? '设计稿、参考图、客户来样的照片' : '设计稿、参考图') + '；转大货时带到款式上'"/></div>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">建样衣</button></template>
  </Modal>`,
})

route('/samples/:no', {
  props: ['no'],
  setup(p) {
    const s = computed(() => db.samples.find((x) => x.no === p.no))
    const v = computed(() => s.value && smpCur(s.value))
    const m = reactive({ dispatch: false, node: false, review: false, convert: false })
    const vers = computed(() => (s.value ? [...s.value.vers].reverse() : []))
    return { s, v, m, vers }
  },
  template: `<div v-if="s">
    <Head :t="s.no" :back="['样衣', '/samples']">
      <button v-if="s.status === '开发中' && v.status === '待派单'" class="btn pri" @click="m.dispatch = true">派单</button>
      <button v-if="s.status === '开发中' && v.status === '打样中'" class="btn pri" @click="m.node = true">报节点</button>
      <button v-if="s.status === '开发中' && v.status === '待评审'" class="btn pri" @click="m.review = true">评审</button>
      <button v-if="s.status === '已通过'" class="btn pri" @click="m.convert = true">转大货</button>
      <a v-if="s.style" class="btn" :href="'#/styles/' + s.style">款式 {{ s.style }}</a>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ s.name }}<St :s="smpState(s)"/></div>
        <div class="sub"><span>{{ catOf(s.cat).path }} / {{ catOf(s.cat).name }}</span><span>{{ s.year }} {{ seasonName(s.season) }} · {{ s.wave }}</span><span class="row g4"><Sw v-for="c in s.colors" :key="c" :c="c" :n="false"/></span></div>
        <section v-for="x in vers" :key="x.no" class="sec" :data-c="'第 ' + x.ver + ' 版'">
          <h3>第 {{ x.ver }} 版 · {{ x.type }}<span class="code dim" style="font-weight:400">{{ x.no }}</span><St :s="x.status"/></h3>
          <dl class="props" style="grid-template-columns:64px 1fr 64px 1fr;margin-bottom:10px">
            <dt>打样</dt><dd>{{ smpWho(x) }}<span v-if="x.way" class="dim"> · {{ x.way }}</span></dd>
            <dt>要样</dt><dd>{{ fd(x.need) }}</dd>
            <template v-if="x.reason"><dt>改版原因</dt><dd style="grid-column:span 3">{{ x.reason }}</dd></template>
          </dl>
          <ul v-if="x.nodes.length" class="tl">
            <li v-for="n in x.nodes" :key="n.name" :class="smpNodeState(n) || (n === smpNode(x) && x.status === '打样中' ? 'run' : '')"><span class="dot"></span><b>{{ n.name }}</b>
              <small>计划 {{ fd(n.plan) }}<template v-if="n.done"> · 完成 {{ fd(n.done) }}</template></small>
              <span><Days v-if="!n.done && x.status === '打样中'" :d="n.plan" :warn="1"/></span></li>
          </ul>
          <p v-if="x.review" style="margin-top:10px"><b>评审{{ x.review.result }}</b><span v-if="x.review.note" class="mut">　{{ x.review.note }}</span><span class="dim">　{{ x.review.by.map(userName).join('、') }} · {{ fd(x.review.at) }}</span></p>
          <Pics v-if="x.review && x.review.photos && x.review.photos.length" :model-value="x.review.photos" readonly :s="56" style="margin-top:6px"/>
        </section>
      </div>
      <aside class="aside" data-c="样衣属性">
        <Gallery :code="s.no" :colors="s.colors" :owner="s" edit/>
        <dl class="props">
          <dt>品牌</dt><dd>{{ s.brand }}</dd>
          <dt>来源</dt><dd>{{ s.source }}<template v-if="TRADE && s.customer"> · {{ custName(s.customer) }}</template></dd>
          <dt v-if="TRADE && s.custNo">客户款号</dt><dd v-if="TRADE && s.custNo" class="code">{{ s.custNo }}</dd>
          <dt>设计师</dt><dd>{{ userName(s.designer) }}</dd>
          <dt>尺码组</dt><dd>{{ db.sizeGroups.find(g => g.id === s.sizeGroup).name }}</dd>
          <dt>面料</dt><dd>{{ s.fabric || '—' }}</dd>
          <dt>预估吊牌价</dt><dd>{{ fm(s.tagPrice) }}</dd>
          <dt>预估成本</dt><dd>{{ fm(s.estCost) }}</dd>
          <dt>研发 BOM</dt><dd>{{ s.devBom ? s.devBom.bom.length + ' 行物料 · ' + s.devBom.ops.length + ' 道工序（转大货时复制成生产 BOM 草稿）' : '没有' }}</dd>
          <template v-if="s.style"><dt>转大货</dt><dd><a class="code" :href="'#/styles/' + s.style">{{ s.style }}</a> · {{ fd(s.convertedAt) }}</dd></template>
        </dl>
        <DocFiles :doc="s"/>
        <h4>操作记录</h4><Log :items="s.log"/>
      </aside>
    </div>
    <SmpDispatch v-if="m.dispatch" :s="s" @close="m.dispatch = false"/>
    <SmpNodeReport v-if="m.node" :s="s" @close="m.node = false"/>
    <SmpReview v-if="m.review" :s="s" @close="m.review = false"/>
    <SmpConvert v-if="m.convert" :s="s" @close="m.convert = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'samples', t: (p) => '样衣 ' + p.no })

comp('SmpDispatch', {
  props: { s: Object }, emits: ['close'],
  setup(p, { emit }) {
    const v = smpCur(p.s)
    const f = reactive({ way: v.way || '外部工厂', factory: v.factory || '', maker: v.maker || 'u8', need: v.need })
    const factories = computed(() => db.suppliers.filter((x) => isFactory(x) && canUseSupplier(x.code, '打样')))
    const save = () => { if (run(() => smpDispatch(p.s, { ...f }))) emit('close') }
    return { f, factories, save }
  },
  template: `<Modal t="派单" :w="460" @close="$emit('close')">
    <div class="form">
      <div class="f full">方式<Seg v-model="f.way" :items="['外部工厂', '内部版房']"/></div>
      <label v-if="f.way === '外部工厂'" class="f">打样工厂<select class="sel" v-model="f.factory"><option value="">选工厂</option><option v-for="x in factories" :key="x.code" :value="x.code">{{ x.short }}{{ x.status === '待准入' ? '（待准入）' : '' }}</option></select></label>
      <label v-else class="f">版师<select class="sel" v-model="f.maker"><option v-for="u in db.users.filter(u => ['开发', '版师'].includes(u.role))" :key="u.id" :value="u.id">{{ u.name }}</option></select></label>
      <label class="f">要样日期<input class="inp" type="date" v-model="f.need"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">派单</button></template>
  </Modal>`,
})
comp('SmpNodeReport', {
  props: { s: Object }, emits: ['close'],
  setup(p, { emit }) {
    const v = smpCur(p.s)
    const f = reactive({ i: v.nodes.findIndex((n) => !n.done), date: clock.today })
    function save() { const w = run(() => smpReport(p.s, f.i, f.date)); if (w) { if (typeof w === 'string') toast(w); emit('close') } }
    return { v, f, save }
  },
  template: `<Modal t="报节点" :w="440" @close="$emit('close')">
    <div class="form">
      <div class="f full">节点<Seg v-model="f.i" :items="v.nodes.map((n, i) => [i, n.name + (n.done ? ' ✓' : '')])"/></div>
      <label class="f">完成日期<input class="inp" type="date" v-model="f.date"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">报完成</button></template>
  </Modal>`,
})
comp('SmpReview', {
  props: { s: Object }, emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ result: '通过', note: '', by: [db.me], photos: [] })
    const toggle = (u) => { const i = f.by.indexOf(u); i >= 0 ? f.by.splice(i, 1) : f.by.push(u) }
    function save() { if (run(() => smpReview(p.s, { ...f }))) { emit('close'); toast(f.result === '改版' ? '已生成下一版打样单' : '评审已记下') } }
    return { f, toggle, save }
  },
  template: `<Modal t="样衣评审" :w="500" @close="$emit('close')">
    <div class="form">
      <div class="f full">结论<Seg v-model="f.result" :items="['通过', '改版', '淘汰']"/></div>
      <label class="f full">意见<textarea class="inp" v-model="f.note"></textarea></label>
      <div class="f full">评审人<div class="chips"><button v-for="u in db.users" :key="u.id" class="chip" :class="{ on: f.by.includes(u.id) }" @click="toggle(u.id)">{{ u.name }}</button></div></div>
      <div class="f full" data-c="评审照片">试穿、问题部位照片<Pics v-model="f.photos" :s="64" camera/></div>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">{{ f.result === '改版' ? '改版，生成下一版' : f.result }}</button></template>
  </Modal>`,
})
comp('SmpConvert', {
  props: { s: Object }, emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ code: '', name: p.s.name, big: liveBigCats()[0]?.code || '', factory: 'GYS0004' })
    // 款号按新规则：大类 + 今天 + 样衣设计师的编号 + 流水；编不出来就提示，手填照样能转
    const auto = computed(() => { try { return { code: nextStyleCode(f.big, p.s.designer) } } catch (e) { return { err: e.message + (e.list?.[0] ? '：' + e.list[0] : '') } } })
    const factories = computed(() => db.suppliers.filter((x) => isFactory(x) && x.status === '合作中'))
    function save() { const st = run(() => smpConvert(p.s, { ...f })); if (st?.code) { emit('close'); go('/styles/' + st.code); toast(`${st.code} 已生成，${st.skus.length} 个 SKU，` + (p.s.devBom ? '研发 BOM 已复制成生产 BOM V1 草稿，核对后发布' : '样衣没有研发 BOM，资料包还要建')) } }
    return { f, auto, factories, save }
  },
  template: `<Modal t="转大货" :w="460" @close="$emit('close')">
    <div class="form">
      <label class="f">款号<input class="inp code" v-model="f.code" :placeholder="auto.code ? '不填自动编：' + auto.code : '编不出来，要手填'"></label>
      <label class="f">大类<select class="sel" v-model="f.big"><option v-for="x in liveBigCats()" :key="x.code" :value="x.code">{{ x.code }} {{ x.name }}</option></select></label>
      <p v-if="!f.code.trim() && auto.err" class="f full amber" style="margin:0;font-size:12px">{{ auto.err }}</p>
      <label class="f full">名称<input class="inp" v-model="f.name"></label>
      <label class="f full">默认加工厂<select class="sel" v-model="f.factory"><option v-for="x in factories" :key="x.code" :value="x.code">{{ x.short }}</option></select></label>
    </div>
    <template #foot><span class="dim">{{ s.colors.length }} 色 × {{ sizesOf(s.sizeGroup).length }} 码</span><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">生成款式</button></template>
  </Modal>`,
})

/* ============================== 核价 ============================== */
// 每款的费率和报价：包装运杂（元/件）、管理费率（% × 料工）、税率（报价含税）、对客报价
db.costCfg = {}
db.quotes = [
  { id: 'BJ01', mat: 'ML0012', sup: 'GYS0001', price: 18.5, lead: 20, moq: 100, at: '2026-08-12', chosen: true },
  { id: 'BJ02', mat: 'ML0012', sup: 'GYS0009', price: 19.8, lead: 15, moq: 200, at: '2026-08-12' },
  { id: 'BJ03', mat: 'ML0012', sup: 'GYS0008', price: 16.9, lead: 25, moq: 500, at: '2026-08-10' },
  { id: 'BJ04', mat: 'ML0015', sup: 'GYS0001', price: 6.8, lead: 10, moq: 200, at: '2026-08-12', chosen: true },
  { id: 'BJ05', mat: 'ML0015', sup: 'GYS0009', price: 7.2, lead: 12, moq: 300, at: '2026-08-14' },
  { id: 'BJ06', mat: 'ML0018', sup: 'GYS0001', price: 22, lead: 15, moq: 100, at: '2026-08-20', chosen: true },
  { id: 'BJ07', mat: 'ML0018', sup: 'GYS0009', price: 23.5, lead: 12, moq: 100, at: '2026-08-21' },
  { id: 'BJ08', mat: 'FK0021', sup: 'GYS0002', price: 0.35, lead: 5, moq: 1000, at: '2026-07-30', chosen: true },
  { id: 'BJ09', mat: 'FK0021', sup: 'GYS0003', price: 0.42, lead: 7, moq: 2000, at: '2026-07-30' },
  { id: 'BJ10', mat: 'FL0031', sup: 'GYS0002', price: 2.6, lead: 7, moq: 200, at: '2026-08-02', chosen: true },
  { id: 'BJ11', mat: 'MZ0003', sup: 'GYS0009', price: 46, lead: 12, moq: 50, at: '2026-09-01', chosen: true },
  { id: 'BJ12', mat: 'MZ0003', sup: 'GYS0001', price: 48.5, lead: 18, moq: 100, at: '2026-09-02' },
  { id: 'BJ13', mat: 'BX0001', sup: 'GYS0003', price: 0.18, lead: 7, moq: 2000, at: '2026-06-18', chosen: true },
]
// 调价记录不再单独记，都在价格档案里（§6.10，5e-price.js）；核价用生产 BOM，还没有就用研发 BOM 估
const costTp = (code) => activeTp(code) || tpsOf(code)[0] || activeTp(code, '研发') || tpsOf(code, '研发')[0]
function costDefaults(code) {
  const s = style(code)
  const prices = db.orders.filter((o) => o.status !== '已取消').flatMap((o) => o.lines).filter((l) => l.style === code && l.price).map((l) => l.price)
  return { pack: 3, mgmt: 8, tax: 13, quote: prices.length ? prices[prices.length - 1] : Math.round((s?.tagPrice || 0) * 0.45) }
}
const costCfgOf = (code) => db.costCfg[code] || costDefaults(code)
function costEnsure(code) { if (!db.costCfg[code]) db.costCfg[code] = costDefaults(code); return db.costCfg[code] }
// 总成本 = 物料 + 加工 + 包装运杂 + 管理费；毛利 = 不含税报价 − 总成本
function costOf(code) {
  const tp = costTp(code)
  if (!tp) return null
  const c = costCfgOf(code)
  const matC = tpMaterialCost(tp), labor = tpLabor(tp)
  const mgmt = rnd((matC + labor) * (c.mgmt || 0) / 100, 2)
  const total = rnd(matC + labor + (+c.pack || 0) + mgmt, 2)
  const net = rnd((+c.quote || 0) / (1 + (c.tax || 0) / 100), 2)
  const margin = rnd(net - total, 2)
  return { tp, mat: matC, labor, pack: +c.pack || 0, mgmt, other: rnd((+c.pack || 0) + mgmt, 2), total, quote: +c.quote || 0, net, margin, rate: net ? margin / net : 0 }
}
const costRate = (x) => (x == null ? '—' : Math.round(x * 1000) / 10 + '%')
function quoteChoose(q) {
  if (!canUseSupplier(q.sup, '采购')) fail(`${supName(q.sup)} 现在是「${sup(q.sup).status}」，不能选`)
  // 选定的报价记进价格档案（这家 × 物料，所有颜色），和现价一样就不记；物料的默认供应商换成这家
  const cur = curMatRec(q.sup, q.mat, null)
  if (!cur || !samePrice(cur.price, q.price)) recordPrice({ kind: '物料价', sup: q.sup, mat: q.mat, price: q.price, why: '选定报价 ' + q.id, src: q.id })
  db.quotes.filter((x) => x.mat === q.mat).forEach((x) => { x.chosen = false })
  q.chosen = true
  const m = mat(q.mat)
  m.price = q.price
  m.supplier = q.sup
}
function quoteAdd(f) {
  if (!f.mat || !f.sup) fail('物料、供应商都要选')
  if (!(f.price > 0)) fail('报价要填')
  return add(db.quotes, { id: 'BJ' + String(db.quotes.length + 1).padStart(2, '0'), lead: null, moq: null, at: clock.today, chosen: false, ...f })
}

route('/costing', {
  setup() {
    const s = reactive({ q: '' })
    const rows = computed(() => db.styles.filter((x) => !s.q || (x.code + x.name).toLowerCase().includes(s.q.toLowerCase())).map((x) => ({ s: x, c: costOf(x.code) })))
    return { s, rows }
  },
  template: `<div>
    <Head t="核价"/>
    <div class="bar" data-c="筛选"><span class="sp"></span><Search v-model="s.q"/></div>
    <div data-c="核价列表">
      <Tbl :rows="rows" link @row="x => go('/costing/' + x.s.code)" empty="没有符合条件的款式" :cols="[
        { k: 'code', t: '款式' }, { k: 'tp', t: '资料包', f: x => x.c ? tpName(x.c.tp) + (x.c.tp.status === '已生效' ? '' : ' 草稿') : '—' },
        { k: 'mat', t: '物料', c: 'num', f: x => x.c ? fm(x.c.mat) : '—' }, { k: 'labor', t: '加工', c: 'num', f: x => x.c ? fm(x.c.labor) : '—' },
        { k: 'other', t: '其他', c: 'num', f: x => x.c ? fm(x.c.other) : '—' }, { k: 'total', t: '总成本', c: 'num' },
        { k: 'quote', t: '报价', c: 'num', f: x => x.c ? fm(x.c.quote) : '—' }, { k: 'rate', t: '毛利率', c: 'num' }]">
        <template #code="{ r }"><span class="row"><Thumb :code="r.s.code" :s="26"/><span class="code">{{ r.s.code }}</span><span class="mut">{{ r.s.name }}</span></span></template>
        <template #total="{ r }"><b v-if="r.c" style="font-weight:500">{{ fm(r.c.total) }}</b><span v-else class="dim">没有资料包</span></template>
        <template #rate="{ r }"><span v-if="r.c" :class="{ red: r.c.rate < 0.1 }">{{ costRate(r.c.rate) }}</span><span v-else class="dim">—</span></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'costing', t: '核价' })

route('/costing/:code', {
  props: ['code'],
  setup(p) {
    const s = computed(() => style(p.code))
    const cfg = s.value ? costEnsure(p.code) : null
    const tab = ref('cost'), adding = ref(false)
    const c = computed(() => costOf(p.code))
    const sizes = computed(() => sizesOf(s.value.sizeGroup))
    const mats = computed(() => (c.value ? [...new Set(c.value.tp.bom.filter((r) => r.src !== '工厂自备').map((r) => r.mat))] : []))
    const quotesOf = (m) => db.quotes.filter((q) => q.mat === m).sort((a, b) => a.price - b.price)
    const logOf = (m) => pricesOf({ kind: '物料价', mat: m }).filter((x) => !x.void).sort(byNewPrice).slice(0, 5)
    const lines = computed(() => db.orders.filter((o) => !['已取消', '草稿'].includes(o.status) && o.lines.some((l) => l.style === p.code)).map((o) => {
      const ls = o.lines.filter((l) => l.style === p.code)
      const qty = sum(ls, (l) => l.qty), price = ls[0].price
      if (!price || !c.value) return { o, qty, price: null }
      const net = rnd(qty * price / (1 + cfg.tax / 100), 2), cost = rnd(qty * c.value.total, 2)
      return { o, qty, price, net, cost, margin: rnd(net - cost, 2), rate: (net - cost) / net }
    }))
    function choose(q) { if (run(() => quoteChoose(q))) toast(`${mat(q.mat).name} 改用 ${supName(q.sup)}，参考价 ${fm(q.price)}`) }
    return { s, cfg, tab, adding, c, sizes, mats, quotesOf, logOf, lines, choose }
  },
  template: `<div v-if="s">
    <Head :t="s.code" :back="['核价', '/costing']">
      <button v-if="tab === 'quote'" class="btn pri" @click="adding = true"><Ico n="plus" :s="14"/>加报价</button>
      <a class="btn" :href="'#/styles/' + s.code">款式</a>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title"><Thumb :code="s.code" :s="44"/>{{ s.name }}</div>
        <div class="sub"><span v-if="c">资料包 {{ tpName(c.tp) }}{{ c.tp.status === '已生效' ? '' : ' 草稿' }}<template v-if="tpStage(c.tp) === '研发'">（还没有生产 BOM，按研发估）</template></span><span>吊牌价 {{ fm(s.tagPrice) }}</span></div>
        <Tabs v-model="tab" :items="[['cost', '成本'], ['quote', '询价比价'], ['margin', '订单毛利', lines.length]]"/>
        <div v-if="!c" class="empty">这个款还没有资料包，算不了成本<p style="margin-top:14px"><a class="btn" :href="'#/styles/' + s.code">去建资料包</a></p></div>
        <template v-else>
          <section v-if="tab === 'cost'" data-c="成本明细">
            <table class="tbl inset flat">
              <thead><tr><th>部位</th><th>物料</th><th class="num">用量（{{ baseSizeOf(s.sizeGroup) }} 码）</th><th class="num">损耗</th><th class="num">单价</th><th class="num">单件</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in c.tp.bom.filter(r => r.src !== '工厂自备')" :key="i"><td>{{ r.part }}</td><td>{{ mat(r.mat).name }}<span v-if="r.src === '客供'" class="amber">　客供</span></td>
                  <td class="num">{{ fq(rowMidUse(r, sizes, baseSizeOf(s.sizeGroup))) }} {{ mat(r.mat).unit }}</td><td class="num">{{ r.loss }}%</td><td class="num">{{ fm(unitPrice(r, tpStage(c.tp))) }}</td><td class="num">{{ r.src === '我方采购' ? fm(rowCost(r, sizes, baseSizeOf(s.sizeGroup), tpStage(c.tp))) : '—' }}</td></tr>
                <tr><td>加工</td><td>工艺单 {{ c.tp.ops.length }} 道工序</td><td></td><td></td><td></td><td class="num">{{ fm(c.labor) }}</td></tr>
              </tbody>
              <tfoot><tr><td>料工合计</td><td></td><td></td><td></td><td></td><td class="num">{{ fm(c.mat + c.labor) }}</td></tr></tfoot>
            </table>
          </section>
          <section v-if="tab === 'quote'" data-c="询价比价">
            <div v-for="m in mats" :key="m" class="sec" style="margin-top:0;margin-bottom:28px">
              <h3>{{ mat(m).name }}<span class="dim" style="font-weight:400">{{ mat(m).code }} · 按{{ mat(m).purUnit }}</span></h3>
              <Tbl inset flat :rows="quotesOf(m)" empty="还没有报价" :cols="[
                { k: 'sup', t: '供应商' }, { k: 'price', t: '报价', c: 'num', f: q => fm(q.price) }, { k: 'lead', t: '交期', c: 'num', f: q => q.lead ? q.lead + ' 天' : '—' },
                { k: 'moq', t: '起订', c: 'num', f: q => q.moq ?? '—' }, { k: 'at', t: '报价日期', f: q => fd(q.at) }, { k: 'act', t: '' }]">
                <template #sup="{ r }"><span class="row">{{ supName(r.sup) }}<St v-if="sup(r.sup).status !== '合作中'" :s="sup(r.sup).status"/></span></template>
                <template #act="{ r }"><span v-if="r.chosen" class="row g4 green"><Ico n="check" :s="14"/>在用</span><button v-else-if="sup(r.sup).status === '合作中'" class="btn sm" @click="choose(r)">选这家</button></template>
              </Tbl>
              <ul v-if="logOf(m).length" class="log" style="margin-top:8px"><li v-for="x in logOf(m)" :key="x.id"><span>{{ fd(x.date) }}</span><div>{{ supName(x.sup) }}<template v-if="x.sku"> · {{ skuName(x.sku) }}</template> {{ fm(x.price) }}<span class="dim">{{ x.why ? '　' + x.why : '' }}{{ x.src ? '　' + x.src : '' }}</span></div></li></ul>
              <p v-if="logOf(m).length" class="dim" style="font-size:12px;margin-top:4px">价格档案里最近 5 条，全部在 <a :href="'#/materials/' + m">物料详情 › 价格记录</a></p>
            </div>
          </section>
          <section v-if="tab === 'margin'" data-c="订单毛利">
            <Tbl inset flat :rows="lines" link @row="x => go('/orders/' + x.o.no)" empty="这个款还没有审核过的订单" :cols="[
              { k: 'no', t: '订单', c: 'code', f: x => x.o.no }, { k: 'cust', t: TRADE ? '客户' : '类型', f: x => custOrDash(x.o) },
              { k: 'qty', t: '数量', c: 'num' }, { k: 'price', t: '单价', c: 'num', f: x => x.price ? fm(x.price) : '—' },
              { k: 'net', t: '收入（不含税）', c: 'num', f: x => x.price ? fm(x.net) : '—' }, { k: 'cost', t: '成本', c: 'num', f: x => x.price ? fm(x.cost) : '—' },
              { k: 'margin', t: '毛利', c: 'num' }]">
              <template #margin="{ r }"><span v-if="r.price" :class="{ red: r.margin < 0 }">{{ fm(r.margin) }} <span class="dim">{{ costRate(r.rate) }}</span></span><span v-else class="dim">自营无售价</span></template>
            </Tbl>
          </section>
        </template>
      </div>
      <aside v-if="c" class="aside" data-c="成本单">
        <dl class="props" style="align-items:center">
          <dt>物料</dt><dd class="num">{{ fm(c.mat) }}</dd>
          <dt>加工</dt><dd class="num">{{ fm(c.labor) }}</dd>
          <dt>包装运杂</dt><dd><input class="inp cell num" type="number" v-model.number="cfg.pack"></dd>
          <dt>管理费率 %</dt><dd><input class="inp cell num" type="number" v-model.number="cfg.mgmt"></dd>
          <dt><b style="color:var(--fg)">总成本</b></dt><dd class="num"><b>{{ fm(c.total) }}</b></dd>
          <dt>目标成本</dt><dd class="num">{{ fm(s.cost) }}<span v-if="s.cost" :class="c.total > s.cost ? 'red' : 'green'" style="margin-left:6px">{{ c.total > s.cost ? '超' : '省' }} {{ fm(Math.abs(c.total - s.cost)) }}</span></dd>
        </dl>
        <h4>报价</h4>
        <dl class="props" style="align-items:center">
          <dt>对客报价</dt><dd><input class="inp cell num" type="number" v-model.number="cfg.quote"></dd>
          <dt>税率 %</dt><dd><input class="inp cell num" type="number" v-model.number="cfg.tax"></dd>
          <dt>不含税</dt><dd class="num">{{ fm(c.net) }}</dd>
          <dt><b style="color:var(--fg)">毛利</b></dt><dd class="num"><b :class="{ red: c.margin < 0 }">{{ fm(c.margin) }}</b></dd>
          <dt>毛利率</dt><dd class="num" :class="{ red: c.rate < 0.1 }">{{ costRate(c.rate) }}</dd>
        </dl>
      </aside>
    </div>
    <QuoteNew v-if="adding" :mats="mats" @close="adding = false"/>
  </div><NotFound v-else/>`,
}, { mod: 'costing', t: (p) => '核价 ' + p.code })

comp('QuoteNew', {
  props: { mats: Array }, emits: ['close'],
  setup(p, { emit }) {
    const f = reactive({ mat: p.mats[0], sup: '', price: null, lead: null, moq: null })
    const sups = computed(() => db.suppliers.filter((x) => !isFactory(x) && x.status !== '黑名单'))
    const save = () => { if (run(() => quoteAdd({ ...f }))) emit('close') }
    return { f, sups, save }
  },
  template: `<Modal t="加报价" :w="480" @close="$emit('close')">
    <div class="form">
      <label class="f full">物料<select class="sel" v-model="f.mat"><option v-for="m in mats" :key="m" :value="m">{{ m }} {{ mat(m).name }}</option></select></label>
      <label class="f full">供应商<select class="sel" v-model="f.sup"><option value="">选供应商</option><option v-for="x in sups" :key="x.code" :value="x.code">{{ x.short }}{{ x.status !== '合作中' ? '（' + x.status + '）' : '' }}</option></select></label>
      <label class="f">报价（元/{{ mat(f.mat).purUnit }}）<input class="inp num" type="number" v-model.number="f.price"></label>
      <label class="f">交期（天）<input class="inp num" type="number" v-model.number="f.lead"></label>
      <label class="f">起订量<input class="inp num" type="number" v-model.number="f.moq"></label>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">加上</button></template>
  </Modal>`,
})

/* ============================== 质检 ============================== */
// AQL 2.5，一般检验水平 II：批量上限 → [抽检数, 允收数]
const QC_AQL = [[8, 2, 0], [15, 3, 0], [25, 5, 0], [50, 8, 0], [90, 13, 1], [150, 20, 1], [280, 32, 2], [500, 50, 3], [1200, 80, 5], [3200, 125, 7], [10000, 200, 10], [Infinity, 315, 14]]
const qcAql = (lot) => { const [, n, ac] = QC_AQL.find(([max]) => lot <= max); return { n: Math.min(n, Math.max(1, Math.round(lot))), ac } }
db.qcs = []
function qcPending() {
  const done = new Set(db.qcs.map((q) => q.src))
  return [
    ...liveReturns().filter((r) => !done.has(r.no)).map((r) => ({ kind: '尾期验货', src: r.no, date: r.date, party: r.factory, what: `${r.style} ${styleName(r.style)}`, lot: sum(r.lines, (l) => l.qty), unit: '件', to: '/production/' + r.prod })),
    ...db.stockDocs.filter((d) => d.type === '采购入库' && d.status === '已审核' && !done.has(d.no)).map((d) => ({ kind: '来料检验', src: d.no, date: d.date, party: d.partner, what: d.lines.map((l) => skuName(l.sku)).join('、'), lot: rnd(sum(d.lines, (l) => l.purQty ?? l.qty), 3), unit: matOfSku(d.lines[0].sku)?.purUnit || '', to: '/stock-docs/' + d.no })),
  ].sort((a, b) => b.date.localeCompare(a.date))
}
function qcInspect(item, f) {
  if (!(f.sample > 0)) fail('抽检数要填')
  if (f.bad > f.sample) fail('不合格数不能大于抽检数')
  if (!f.result) fail('选一个结论')
  if (f.result !== '合格' && !f.note) fail('不合格、让步接收要写问题')
  if (f.result !== '合格' && !f.photos?.length) fail('不合格、让步接收要拍问题照片')
  return add(db.qcs, { no: nextNo('QC'), kind: item.kind, src: item.src, party: item.party, what: item.what, lot: item.lot, unit: item.unit, to: item.to, date: clock.today, sample: f.sample, bad: f.bad || 0, ac: qcAql(item.lot).ac, result: f.result, note: f.note || '', photos: f.photos || [], by: db.me })
}
const qcOf = (src) => db.qcs.find((q) => q.src === src)
function qcSupplierStats() {
  const codes = [...new Set([...db.productions.map((p) => p.factory), ...db.qcs.map((q) => q.party)])]
  return codes.map((c) => {
    const ps = db.productions.filter((p) => p.factory === c)
    const ret = sum(ps, prodRet), def = sum(ps, prodDef), qs = db.qcs.filter((q) => q.party === c)
    return { c, ret, def, rate: ret ? def / ret : null, n: qs.length, bad: qs.filter((q) => q.result === '不合格').length, soft: qs.filter((q) => q.result === '让步接收').length }
  }).filter((x) => x.ret || x.n)
}
// 种子：第一批回货验过（合格），两张面料入库验过（一张合格、一张让步接收）
as('u1', '2026-11-02', () => { const r = db.returns[0]; if (r) qcInspect(qcPending().find((x) => x.src === r.no), { sample: 50, bad: 2, result: '合格' }) })
as('u5', '2026-10-08', () => { const d = db.stockDocs.find((x) => x.type === '采购入库' && x.partner === 'GYS0009'); if (d) qcInspect(qcPending().find((x) => x.src === d.no), { sample: 20, bad: 0, result: '合格' }) })
as('u5', '2026-10-24', () => { const d = db.stockDocs.find((x) => x.type === '采购入库' && x.partner === 'GYS0001'); if (d) qcInspect(qcPending().find((x) => x.src === d.no), { sample: 80, bad: 6, result: '让步接收', note: '卡其色与确认样轻微色差，' + ifTrade('客户同意接收', '开发确认能用'), photos: [demoPic('defect', colorOf('KQ').hex, '色差', '色差对比.jpg')] }) })

TODOS.push(() => [{ t: '待检的回货和来料', n: qcPending().length, to: '/qc', i: 'shield' }])

route('/qc', {
  setup() {
    const s = reactive({ tab: '待检', q: '', item: null })
    const pend = computed(qcPending)
    const done = computed(() => db.qcs.slice().reverse())
    const stats = computed(qcSupplierStats)
    const tabs = computed(() => [['待检', '待检', pend.value.length], ['已检', '已检', db.qcs.length], ['质量', '供应商质量']])
    const filt = (rows, f) => rows.filter((x) => !s.q || f(x).toLowerCase().includes(s.q.toLowerCase()))
    return { s, pend, done, stats, tabs, filt }
  },
  template: `<div>
    <Head t="质检"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/><span class="sp"></span><Search v-if="s.tab !== '质量'" v-model="s.q"/></div>
    <div v-if="s.tab === '待检'" data-c="待检">
      <Tbl :rows="filt(pend, x => x.src + supName(x.party) + x.what)" empty="没有待检的回货和来料" :cols="[
        { k: 'kind', t: '类型' }, { k: 'src', t: '来源单' }, { k: 'party', t: '供应商', f: x => supName(x.party) }, { k: 'what', t: '货品' },
        { k: 'lot', t: '批量', c: 'num', f: x => fq(x.lot) + ' ' + x.unit }, { k: 'n', t: '抽检', c: 'num', f: x => qcAql(x.lot).n }, { k: 'date', t: '到货', f: x => fd(x.date) }, { k: 'act', t: '' }]">
        <template #src="{ r }"><a class="code" :href="'#' + r.to">{{ r.src }}</a></template>
        <template #what="{ r }"><span class="ell" style="max-width:260px;display:inline-block;vertical-align:middle">{{ r.what }}</span></template>
        <template #act="{ r }"><button class="btn sm" @click="s.item = r">验货</button></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '已检'" data-c="验货记录">
      <Tbl :rows="filt(done, x => x.no + x.src + supName(x.party) + x.what)" empty="还没有验货记录" :cols="[
        { k: 'no', t: '单号', c: 'code' }, { k: 'kind', t: '类型' }, { k: 'src', t: '来源单' }, { k: 'party', t: '供应商', f: x => supName(x.party) },
        { k: 'lot', t: '批量', c: 'num', f: x => fq(x.lot) + ' ' + x.unit }, { k: 'bad', t: '抽检 / 不合格', c: 'num', f: x => x.sample + ' / ' + x.bad },
        { k: 'result', t: '结论' }, { k: 'note', t: '问题' }, { k: 'date', t: '日期', f: x => fd(x.date) }]">
        <template #src="{ r }"><a class="code" :href="'#' + r.to">{{ r.src }}</a></template>
        <template #result="{ r }"><St :s="r.result"/></template>
        <template #note="{ r }"><span class="row"><Pics v-if="r.photos && r.photos.length" :model-value="r.photos" readonly :s="28" :limit="2"/><span class="mut ell" style="max-width:240px;display:inline-block;vertical-align:middle">{{ r.note || '—' }}</span></span></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '质量'" data-c="供应商质量">
      <Tbl :rows="stats" empty="还没有回货和验货记录" :cols="[
        { k: 'c', t: '供应商', f: x => supName(x.c) }, { k: 'cat', t: '类别', f: x => sup(x.c)?.cats.join('、') }, { k: 'ret', t: '回货件数', c: 'num', f: x => x.ret || '—' },
        { k: 'def', t: '次品', c: 'num', f: x => x.ret ? x.def : '—' }, { k: 'rate', t: '次品率', c: 'num' }, { k: 'n', t: '验货批次', c: 'num' }, { k: 'bad', t: '不合格', c: 'num' }, { k: 'soft', t: '让步接收', c: 'num' }]">
        <template #rate="{ r }"><span v-if="r.rate != null" :class="{ red: r.rate > 0.02 }">{{ costRate(r.rate) }}</span><span v-else class="dim">—</span></template>
        <template #bad="{ r }"><span :class="{ red: r.bad }">{{ r.bad }}</span></template>
      </Tbl>
    </div>
    <QcInspect v-if="s.item" :item="s.item" @close="s.item = null"/>
  </div>`,
}, { mod: 'qc', t: '质检' })

comp('QcInspect', {
  props: { item: Object }, emits: ['close'],
  setup(p, { emit }) {
    const a = qcAql(p.item.lot)
    const f = reactive({ sample: a.n, bad: 0, result: '合格', note: '', photos: [] })
    watch(() => f.bad, (b) => { if (f.result !== '让步接收') f.result = b > a.ac ? '不合格' : '合格' })
    function save() { const q = run(() => qcInspect(p.item, { ...f })); if (q?.no) { emit('close'); toast(`${q.no} ${q.result}`) } }
    return { a, f, save }
  },
  template: `<Modal :t="item.kind + ' · ' + item.src" :w="520" @close="$emit('close')">
    <dl class="props" style="margin-bottom:16px"><dt>供应商</dt><dd>{{ supName(item.party) }}</dd><dt>货品</dt><dd>{{ item.what }}</dd><dt>批量</dt><dd>{{ fq(item.lot) }} {{ item.unit }} · AQL 2.5 抽 {{ a.n }}，允收 {{ a.ac }}</dd></dl>
    <div class="form">
      <label class="f">抽检数<input class="inp num" type="number" v-model.number="f.sample"></label>
      <label class="f">不合格数<input class="inp num" type="number" v-model.number="f.bad"></label>
      <div class="f full">结论<Seg v-model="f.result" :items="['合格', '不合格', '让步接收']"/></div>
      <label class="f full">问题<textarea class="inp" v-model="f.note"></textarea></label>
      <div class="f full" data-c="验货照片">照片<span :class="f.result === '合格' ? 'dim' : 'red'" style="font-weight:400">{{ f.result === '合格' ? '（选填）' : '（' + f.result + '必须拍问题照片）' }}</span><Pics v-model="f.photos" :s="64" camera/></div>
    </div>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">记下结果</button></template>
  </Modal>`,
})

/* ============================== 成品仓 ============================== */
// 回货 → 良品入成品仓（库存按款 × 色 × 码）→ 按订单发货；入库、发货都推聚水潭（这里只模拟状态）
db.fgIn = []
db.fgStock = {}
db.shipments = []
const fgKey = (st, c, z) => `${st}|${c}|${z}`
const fgQty = (st, c, z) => db.fgStock[fgKey(st, c, z)] || 0
const fgPending = () => { const done = new Set(db.fgIn.map((x) => x.ret)); return liveReturns().filter((r) => !done.has(r.no)).map((r) => ({ r, qc: qcOf(r.no) })) }
function fgReceive(r) {
  const qc = qcOf(r.no)
  if (qc?.result === '不合格') fail('验货不合格，不能入成品仓', ['先返修，或者改成让步接收'])
  if (db.fgIn.some((x) => x.ret === r.no)) fail('这批回货已经入过库')
  const lines = r.lines.map((l) => ({ color: l.color, size: l.size, qty: l.qty - l.def })).filter((l) => l.qty > 0)
  lines.forEach((l) => { const k = fgKey(r.style, l.color, l.size); db.fgStock[k] = (db.fgStock[k] || 0) + l.qty })
  const d = add(db.fgIn, { no: nextNo('CR'), ret: r.no, prod: r.prod, style: r.style, order: r.order, factory: r.factory, date: clock.today, wh: r.wh || 'CP01', lines, jst: '待推送', log: [] })
  act(d, `良品入库 ${sum(lines, (l) => l.qty)} 件`)
  return d
}
const fgShipped = (o, l) => sum(db.shipments.filter((s) => s.order === o.no).flatMap((s) => s.lines).filter((x) => x.style === l.style && x.color === l.color && x.size === l.size), (x) => x.qty)
// qty = { '27CF001|KQ|M': 100 }
function fgShip(o, f) {
  const errs = [], lines = []
  Object.entries(f.qty).forEach(([k, n]) => {
    if (!(n > 0)) return
    const [st, c, z] = k.split('|')
    const l = o.lines.find((x) => x.style === st && x.color === c && x.size === z)
    if (!l) return
    if (n > fgQty(st, c, z)) errs.push(`${st}-${c}-${z}：成品库存 ${fgQty(st, c, z)}，不够 ${n}`)
    const cap = Math.floor(l.qty * (1 + OVER)) - fgShipped(o, l)
    if (n > cap) errs.push(`${st}-${c}-${z}：订单 ${l.qty} 件，最多还能发 ${Math.max(0, cap)} 件`)
    lines.push({ style: st, color: c, size: z, qty: n, price: l.price })
  })
  if (!lines.length) errs.push('至少发一件')
  if (errs.length) fail('发货没成', errs)
  lines.forEach((l) => { db.fgStock[fgKey(l.style, l.color, l.size)] -= l.qty })
  const s = add(db.shipments, { no: nextNo('FH'), order: o.no, customer: o.customer, date: clock.today, logistics: f.logistics || '', lines, jst: '待推送', log: [] })
  act(s, `发货 ${sum(lines, (l) => l.qty)} 件`)
  act(o, `发货 ${s.no}：${sum(lines, (l) => l.qty)} 件`)
  return s
}
function fgPush(d) { if (d.jst === '已推送') return; d.jst = '已推送'; d.pushedAt = clock.today; act(d, '推送聚水潭') }
const fgOrders = () => db.orders.filter((o) => ['已审核', '生产中', '已完成'].includes(o.status) && o.lines.some((l) => fgQty(l.style, l.color, l.size) > 0))
// 种子：第一批回货入库，部分发货（卫衣那张订单）
as('u5', '2026-11-02', () => { const r = db.returns[0]; if (r) fgPush(fgReceive(r)) })
as('u5', '2026-11-03', () => {
  const o = db.orders.find((x) => orderStyles(x).includes('26DS021'))
  if (o && fgQty('26DS021', 'HE', 'M')) fgShip(o, { logistics: 'SF1029385521', qty: { '26DS021|HE|S': 50, '26DS021|HE|M': 100, '26DS021|HE|L': 90, '26DS021|HE|XL': 40, '26DS021|HE|XXL': 20 } })
})

SEARCH.push(() => db.shipments.map((s) => ({ t: s.no + ' ' + (TRADE ? custName(s.customer) : s.order), sub: '发货单', to: '/fg?tab=发货', i: 'warehouse' })))
TODOS.push(() => [{ t: '回货待入成品仓', n: fgPending().filter((x) => x.qc?.result !== '不合格').length, to: '/fg', i: 'warehouse' }])

route('/fg', {
  setup() {
    const s = reactive({ tab: nav.query.tab || '待入库', ship: false })
    const pend = computed(fgPending)
    const stockStyles = computed(() => [...new Set(Object.entries(db.fgStock).filter(([, q]) => q > 0).map(([k]) => k.split('|')[0]))])
    const stockModel = (st) => Object.fromEntries(Object.entries(db.fgStock).filter(([k]) => k.startsWith(st + '|')).map(([k, q]) => [k.split('|').slice(1).join('|'), q]))
    const stockColors = (st) => style(st).colors.filter((c) => sizesOf(style(st).sizeGroup).some((z) => fgQty(st, c, z) > 0))
    const jst = computed(() => [...db.fgIn.map((d) => ({ d, kind: '成品入库', party: supName(d.factory), qty: sum(d.lines, (l) => l.qty) })), ...db.shipments.map((d) => ({ d, kind: '发货', party: TRADE ? custName(d.customer) : custOrDash(order(d.order)), qty: sum(d.lines, (l) => l.qty) }))].sort((a, b) => b.d.date.localeCompare(a.d.date)))
    const tabs = computed(() => [['待入库', '待入库', pend.value.length], ['库存', '库存'], ['发货', '发货', db.shipments.length], ['聚水潭', '聚水潭', jst.value.filter((x) => x.d.jst === '待推送').length || null]])
    function receive(r) { const d = run(() => fgReceive(r)); if (d?.no) toast(`${d.no} 入库 ${sum(d.lines, (l) => l.qty)} 件`) }
    const pushAll = () => { jst.value.forEach((x) => fgPush(x.d)); toast('已全部推送') }
    return { s, pend, stockStyles, stockModel, stockColors, jst, tabs, receive, pushAll }
  },
  template: `<div>
    <Head t="成品仓">
      <button v-if="s.tab === '聚水潭' && jst.some(x => x.d.jst === '待推送')" class="btn" @click="pushAll">全部推送</button>
      <button class="btn pri" @click="s.ship = true"><Ico n="truck" :s="14"/>发货</button>
    </Head>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div v-if="s.tab === '待入库'" data-c="待入库">
      <Tbl :rows="pend" empty="回货都入库了" :cols="[
        { k: 'no', t: '回货单', c: 'code', f: x => x.r.no }, { k: 'style', t: '款式' }, { k: 'factory', t: '加工厂', f: x => supName(x.r.factory) },
        { k: 'qty', t: '回货', c: 'num', f: x => sum(x.r.lines, l => l.qty) }, { k: 'def', t: '次品', c: 'num', f: x => sum(x.r.lines, l => l.def) },
        { k: 'good', t: '良品', c: 'num', f: x => sum(x.r.lines, l => l.qty - l.def) }, { k: 'qc', t: '验货' }, { k: 'date', t: '回货日期', f: x => fd(x.r.date) }, { k: 'act', t: '' }]">
        <template #style="{ r }"><span class="row"><Thumb :code="r.r.style" :s="24"/><span class="code">{{ r.r.style }}</span></span></template>
        <template #qc="{ r }"><St v-if="r.qc" :s="r.qc.result"/><a v-else class="dim" href="#/qc">待检</a></template>
        <template #act="{ r }"><button class="btn sm" :disabled="r.qc && r.qc.result === '不合格'" @click="receive(r.r)">入库</button></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '库存'" class="page" data-c="成品库存">
      <div v-for="st in stockStyles" :key="st" style="margin-bottom:28px">
        <div class="row" style="margin-bottom:8px"><Thumb :code="st" :s="28"/><a class="code" :href="'#/styles/' + st">{{ st }}</a><span>{{ styleName(st) }}</span></div>
        <Matrix :st="st" :model-value="stockModel(st)" :colors="stockColors(st)" ro/>
      </div>
      <p v-if="!stockStyles.length" class="empty">成品仓是空的，回货入库后才有库存</p>
    </div>
    <div v-if="s.tab === '发货'" data-c="发货单">
      <Tbl :rows="db.shipments.slice().reverse()" empty="还没有发过货" :cols="[
        { k: 'no', t: '发货单', c: 'code' }, { k: 'order', t: '订单', c: 'code' }, ...(TRADE ? [{ k: 'customer', t: '客户', f: x => custName(x.customer) }] : []),
        { k: 'qty', t: '件数', c: 'num', f: x => sum(x.lines, l => l.qty) }, { k: 'amt', t: '金额', c: 'num', f: x => fm(sum(x.lines, l => l.qty * (l.price || 0))) },
        { k: 'logistics', t: '物流单号', c: 'code', f: x => x.logistics || '—' }, { k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'jst', t: '聚水潭' }]">
        <template #order="{ r }"><a class="code" :href="'#/orders/' + r.order">{{ r.order }}</a></template>
        <template #jst="{ r }"><St :s="r.jst"/></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '聚水潭'" data-c="聚水潭推送">
      <Tbl :rows="jst" empty="没有要推送的单据" :cols="[
        { k: 'no', t: '单号', c: 'code', f: x => x.d.no }, { k: 'kind', t: '类型' }, { k: 'party', t: '往来' }, { k: 'qty', t: '件数', c: 'num' },
        { k: 'date', t: '日期', f: x => fd(x.d.date) }, { k: 'jst', t: '状态' }, { k: 'act', t: '' }]">
        <template #jst="{ r }"><St :s="r.d.jst"/></template>
        <template #act="{ r }"><button v-if="r.d.jst === '待推送'" class="btn sm" @click="fgPush(r.d)">推送</button><span v-else class="dim">{{ fd(r.d.pushedAt) }}</span></template>
      </Tbl>
    </div>
    <FgShip v-if="s.ship" @close="s.ship = false"/>
  </div>`,
}, { mod: 'fg', t: '成品仓' })

comp('FgShip', {
  emits: ['close'],
  setup(_, { emit }) {
    const orders = computed(fgOrders)
    const f = reactive({ order: orders.value[0]?.no || '', logistics: '', qty: {} })
    const o = computed(() => order(f.order))
    const styles = computed(() => (o.value ? orderStyles(o.value).filter((st) => o.value.lines.some((l) => l.style === st && fgQty(l.style, l.color, l.size) > 0)) : []))
    const models = reactive({})
    watch(() => f.order, () => { Object.keys(models).forEach((k) => delete models[k]); styles.value.forEach((st) => { models[st] = {} }) }, { immediate: true })
    const colorsOf = (st) => [...new Set(o.value.lines.filter((l) => l.style === st).map((l) => l.color))]
    const sub = (st) => (c, z) => '库存 ' + fgQty(st, c, z)
    function save() {
      const qty = {}
      Object.entries(models).forEach(([st, m]) => Object.entries(m).forEach(([k, n]) => { qty[st + '|' + k] = n }))
      const sh = run(() => fgShip(o.value, { logistics: f.logistics, qty }))
      if (sh?.no) { emit('close'); toast(`${sh.no} 已发 ${sum(sh.lines, (l) => l.qty)} 件`) }
    }
    return { orders, f, o, styles, models, colorsOf, sub, save }
  },
  template: `<Modal t="发货" :w="720" @close="$emit('close')">
    <div v-if="!orders.length" class="empty">成品仓里没有能发的货</div>
    <template v-else>
      <div class="form" style="margin-bottom:16px">
        <label class="f">订单<select class="sel" v-model="f.order"><option v-for="x in orders" :key="x.no" :value="x.no">{{ x.no }} · {{ custOrDash(x) }}</option></select></label>
        <label class="f">物流单号<input class="inp code" v-model="f.logistics"></label>
      </div>
      <div v-for="st in styles" :key="st" style="margin-bottom:16px">
        <div class="row" style="margin-bottom:6px"><Thumb :code="st" :s="22"/><span class="code">{{ st }}</span><span class="mut">{{ styleName(st) }}</span></div>
        <Matrix :st="st" v-model="models[st]" :colors="colorsOf(st)" :sub="sub(st)"/>
      </div>
    </template>
    <template #foot><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="!orders.length" @click="save">发货</button></template>
  </Modal>`,
})

/* ============================== 对账 ============================== */
// 供应商对账单：已审核的采购入库（采购单位数量 × 单价）+ 回货（件数 × 加工单价，次品按加工单价扣）
db.statements = []
db.receipts = []
db.invoices = []
Object.assign(FLOWS, { statement: { 发送: ['草稿', '已发送'], 确认: ['已发送', '已确认'], 付款: ['已确认', '已付款'], 作废: ['草稿', '已作废'] } })
const finMonth = (d) => d.slice(0, 7)
function finPayables() {
  const billed = new Set(db.statements.filter((s) => s.status !== '已作废').flatMap((s) => s.items.map((i) => i.no)))
  const rk = db.stockDocs.filter((d) => d.type === '采购入库' && d.status === '已审核' && !billed.has(d.no)).map((d) => ({
    type: '采购入库', no: d.no, sup: d.partner, date: d.date, desc: d.lines.map((l) => skuName(l.sku)).join('、'),
    qty: rnd(sum(d.lines, (l) => l.purQty ?? l.qty), 3), amt: rnd(sum(d.lines, (l) => (l.purQty ?? l.qty) * (l.price || 0)), 2), deduct: 0,
  }))
  // 采购退货冲减应付：金额记负数
  const th = db.stockDocs.filter((d) => d.type === '采购退货' && d.status === '已审核' && !billed.has(d.no)).map((d) => ({
    type: '采购退货', no: d.no, sup: d.partner || purchase(d.src)?.supplier, date: d.date, desc: d.lines.map((l) => skuName(l.sku)).join('、'),
    qty: -rnd(sum(d.lines, (l) => l.purQty ?? l.qty), 3), amt: -rnd(sum(d.lines, (l) => (l.purQty ?? l.qty) * (l.price ?? purchase(d.src)?.lines[l.poLine]?.price ?? 0)), 2), deduct: 0,
  }))
  const hh = liveReturns().filter((r) => !billed.has(r.no)).map((r) => {
    const p = production(r.prod), q = sum(r.lines, (l) => l.qty), d = sum(r.lines, (l) => l.def)
    return { type: '回货', no: r.no, prod: r.prod, sup: r.factory, date: r.date, desc: `${r.style} ${styleName(r.style)}，次品 ${d}`, qty: q, amt: rnd(q * p.price, 2), deduct: rnd(d * p.price, 2) }
  })
  return [...rk, ...th, ...hh]
}
function finGroups() {
  const g = {}
  finPayables().forEach((x) => { const k = x.sup + '|' + finMonth(x.date); (g[k] ||= { sup: x.sup, month: finMonth(x.date), items: [] }).items.push(x) })
  return Object.values(g).map((x) => ({ ...x, amt: rnd(sum(x.items, (i) => i.amt - i.deduct), 2) })).sort((a, b) => a.month.localeCompare(b.month))
}
function finCreate(supCode, month) {
  const items = finPayables().filter((x) => x.sup === supCode && finMonth(x.date) === month)
  if (!items.length) fail('这个月没有要对的单据')
  const s = add(db.statements, { no: nextNo('DZ'), sup: supCode, month, items, date: clock.today, status: '草稿', paidAt: null, log: [] })
  act(s, `生成，${items.length} 张单据`)
  return s
}
const finAmt = (s) => rnd(sum(s.items, (i) => i.amt) - sum(s.items, (i) => i.deduct), 2)
const finDue = (s) => addDays(finMonthEnd(s.month), sup(s.sup)?.term || 0)
function finStmtAction(s, a) {
  doAction('statement', s, a)
  if (a === '付款') s.paidAt = clock.today
}
function finInvoice(f) {
  if (!(f.amt > 0)) fail('发票金额要填')
  const inv = add(db.invoices, { no: f.no || 'FP' + String(db.invoices.length + 1).padStart(8, '0'), dir: f.dir, party: f.party, ref: f.ref, amt: f.amt, rate: f.rate || '13%', date: clock.today, status: '未核销' })
  return inv
}
function finVerify(inv) {
  const ref = inv.dir === '收票' ? db.statements.find((s) => s.no === inv.ref) : db.shipments.find((s) => s.no === inv.ref)
  const due = ref ? (inv.dir === '收票' ? finAmt(ref) : finShipAmt(ref)) : null
  if (due != null && Math.abs(due - inv.amt) > 0.01) fail(`发票金额 ${fm(inv.amt)} 和单据金额 ${fm(due)} 对不上`)
  inv.status = '已核销'
}
// 客户应收：按发货 × 订单单价，到期日 = 发货日 + 客户账期；页签只在 TRADE 开时有
const finShipAmt = (sh) => rnd(sum(sh.lines, (l) => l.qty * (l.price || 0)), 2)
function finReceivables() {
  return db.shipments.map((sh) => {
    const amt = finShipAmt(sh), got = rnd(sum(db.receipts.filter((r) => r.ship === sh.no), (r) => r.amt), 2)
    return { sh, amt, got, due: addDays(sh.date, cust(sh.customer)?.term || 0), status: got >= amt - 0.005 ? '已收款' : got > 0 ? '部分收款' : '待收款', inv: db.invoices.find((i) => i.ref === sh.no) }
  })
}
function finReceive(sh, amt) {
  if (!(amt > 0)) fail('收款金额要填')
  add(db.receipts, { ship: sh.no, amt, at: clock.today, by: db.me })
  act(sh, `收款 ${fm(amt)}`)
}
// 种子：宏利针织 10 月的面料对账单已确认、收了票
as('u7', '2026-11-02', () => {
  if (!finPayables().some((x) => x.sup === 'GYS0009' && finMonth(x.date) === '2026-10')) return
  const s = finCreate('GYS0009', '2026-10')
  finStmtAction(s, '发送')
  as('u7', '2026-11-04', () => finStmtAction(s, '确认'))
  as('u7', '2026-11-05', () => finVerify(finInvoice({ no: 'FP00031876', dir: '收票', party: 'GYS0009', ref: s.no, amt: finAmt(s), rate: '13%' })))
})

/* ---------- 上传工厂对账单（对账 M11 待定）：识别成表格，和我方单据逐行比，差异写说明，再生成对账单 ----------
 * 原型里识别结果是编的（照我方单据改几处）；真系统用 MinerU（自部署）或第三方 OCR API，agent 也能通过 MCP 调用同一个解析工具 */
const OCR_NOTE = '原型里识别结果是编的；真系统用 MinerU（自部署）或第三方 OCR API，agent 也能通过 MCP 调用同一个解析工具'
const OCR_CATS = { 一致: ['一致', 'green'], 不一致: ['数量或金额不一致', 'amber'], 对方有: ['对方有我方没有', 'red'], 我方有: ['我方有对方没有', 'red'] }
// 我方这边：每张待对账单据一行（货款），有次品扣款的再单独一行（扣款，金额记负数）
function ocrOurs(supCode, month) {
  return finPayables().filter((x) => x.sup === supCode && finMonth(x.date) === month).flatMap((x) => {
    const r = x.type === '回货' ? liveReturns().find((y) => y.no === x.no) : null
    const base = { no: x.no, type: x.type, date: x.date, style: r?.style || '', supNo: db.stockDocs.find((d) => d.no === x.no)?.supNo || '', desc: r ? r.style + ' ' + styleName(r.style) : x.desc }
    const rows = [{ ...base, k: '货款', qty: x.qty, price: x.qty ? rnd(x.amt / x.qty, 4) : 0, amt: x.amt }]
    if (x.deduct) { const pr = production(x.prod)?.price || 0; rows.push({ ...base, k: '扣款', desc: '次品扣款', qty: -sum(r.lines, (l) => l.def), price: pr, amt: -x.deduct }) }
    return rows
  })
}
// 编一张工厂对账单：第 1 张照抄（连扣款），第 2 张按新单价算、没列扣款，再多一张我方没登记的送货
function ocrFake(supCode, month) {
  const ours = ocrOurs(supCode, month), goods = ours.filter((o) => o.k === '货款'), out = []
  const theirNo = (o) => o.supNo || 'SH' + o.date.slice(5).replace('-', '')
  goods.forEach((o, i) => {
    const t = { date: o.date, no: theirNo(o), style: o.style, desc: o.desc, k: '货款', qty: o.qty, price: o.price, amt: o.amt }
    if (i === 1) { t.price = rnd(o.price + (o.type === '回货' ? 2 : 0.5), 4); t.amt = rnd(t.qty * t.price, 2) }
    out.push(t)
    const d = ours.find((x) => x.k === '扣款' && x.no === o.no)
    if (d && i === 0) out.push({ date: o.date, no: theirNo(o), style: o.style, desc: '次品扣款', k: '扣款', qty: d.qty, price: d.price, amt: d.amt })
  })
  const last = goods[goods.length - 1]
  if (last) {
    const date = [addDays(last.date, 3), finMonthEnd(month)].sort()[0], qty = last.type === '回货' ? 150 : Math.max(1, Math.round(last.qty * 0.3))
    out.push({ date, no: 'SH' + date.slice(5).replace('-', ''), style: last.style, desc: last.desc, k: '货款', qty, price: last.price, amt: rnd(qty * last.price, 2) })
  }
  return out
}
// 比对：先按单号（我方单号、供应商送货单号）对，对不上再按日期（前后 3 天内）+ 款号对；同类（货款对货款、扣款对扣款）才对
function ocrCompare(theirs, ours) {
  const used = new Set(), hit = new Map()
  const pick = (t, ok) => { let best = -1, bd = Infinity; ours.forEach((o, i) => { if (used.has(i) || o.k !== t.k) return; const d = ok(o, t); if (d != null && d < bd) { bd = d; best = i } }); return best }
  const byNo = (o, t) => (t.no && (t.no === o.no || t.no === o.supNo) ? Math.abs(diffDays(t.date, o.date)) : null)
  const byDay = (o, t) => { const d = Math.abs(diffDays(t.date, o.date)); return d <= 3 && (!t.style || !o.style || t.style === o.style) ? d : null }
  ;[byNo, byDay].forEach((ok) => theirs.forEach((t, ti) => { if (hit.has(ti)) return; const i = pick(t, ok); if (i >= 0) { used.add(i); hit.set(ti, i) } }))
  const out = theirs.map((t, ti) => {
    const o = hit.has(ti) ? ours[hit.get(ti)] : null
    if (!o) return { cat: '对方有', t, o: null, da: t.amt, note: '' }
    const same = Math.abs(o.qty - t.qty) < 0.001 && Math.abs(o.amt - t.amt) < 0.005
    return { cat: same ? '一致' : '不一致', t, o, dq: rnd(t.qty - o.qty, 3), da: rnd(t.amt - o.amt, 2), note: '' }
  })
  ours.forEach((o, i) => { if (!used.has(i)) out.push({ cat: '我方有', t: null, o, da: -o.amt, note: '' }) })
  return out
}
// 按比对结果生成：金额按我方单据算；差异每处都要写说明（和工厂谈的结果），原件和识别结果挂在对账单上
function ocrCreate(f) {
  const miss = f.cmp.filter((x) => x.cat !== '一致' && !x.note?.trim())
  if (miss.length) fail(`还有 ${miss.length} 处差异没写说明`, miss.map((x) => `${OCR_CATS[x.cat][0]}：${(x.t || x.o).desc} ${fd((x.t || x.o).date)}`))
  const s = finCreate(f.sup, f.month)
  const n = (c) => f.cmp.filter((x) => x.cat === c).length
  s.files = f.files.map((x) => ({ ...x }))
  s.ocr = { file: f.files[0]?.name || '', at: clock.today, rows: f.rows.map((x) => ({ ...x })), cmp: f.cmp.map((x) => ({ ...x, note: (x.note || '').trim() })), theirAmt: rnd(sum(f.rows, (x) => x.amt), 2) }
  act(s, `按工厂对账单比对生成：一致 ${n('一致')} 行，差异 ${f.cmp.length - n('一致')} 处（都写了说明）`)
  return s
}
// 没有文件时用的示例对账单（一张拍的纸质对账单）
function ocrDemoFile() {
  const ls = [26, 33, 40, 47, 54, 61, 68].map((y) => `<path d="M14 ${y}H86" stroke="#9a968c" stroke-width=".5"/>`).join('')
  const src = svgUri(100, 130, `<rect width="100" height="130" fill="#6b7178"/><g filter="url(#pg-soft)"><rect x="8" y="6" width="84" height="118" fill="#fbf9f3" transform="rotate(-1.5 50 65)"/></g>
    <g transform="rotate(-1.5 50 65)"><text x="50" y="18" text-anchor="middle" font-size="6" font-weight="700" fill="#333" font-family="sans-serif">对 账 单</text>${ls}
    <path d="M30 26V68M48 26V68M62 26V68M75 26V68" stroke="#9a968c" stroke-width=".4"/><path d="M58 96h24" stroke="#555" stroke-width=".6"/>
    <circle cx="74" cy="100" r="8" fill="none" stroke="#c8261d" stroke-width="1" opacity=".75"/></g>`)
  return { id: 'OCR' + Date.now(), name: '工厂对账单-拍照.jpg', size: 486000, src, at: clock.today }
}

SEARCH.push(() => db.statements.map((s) => ({ t: s.no + ' ' + supName(s.sup) + ' ' + s.month, sub: '对账单', to: '/finance/' + s.no, i: 'receipt' })))
TODOS.push(() => [{ t: '待生成的供应商对账单', n: finGroups().length, to: '/finance', i: 'receipt' }])

// 上传工厂对账单：选供应商、月份、传照片或 PDF → 识别中 → 比对结果，差异写说明 → 按比对结果生成对账单
comp('FinOcr', {
  emits: ['close'],
  setup(p, { emit }) {
    const gs = finGroups(), g0 = gs.find((g) => isFactory(sup(g.sup))) || gs[0]
    const f = reactive({ sup: g0?.sup || '', month: g0?.month || clock.today.slice(0, 7), files: [] })
    const s = reactive({ step: 'form', rows: [], cmp: [], cat: '全部' })
    const sups = computed(() => db.suppliers)
    const months = computed(() => [...new Set([...gs.filter((g) => g.sup === f.sup).map((g) => g.month), clock.today.slice(0, 7), f.month])].sort().reverse())
    const ours = computed(() => ocrOurs(f.sup, f.month))
    const cats = computed(() => [['全部', '全部', s.cmp.length], ...Object.keys(OCR_CATS).map((k) => [k, OCR_CATS[k][0], s.cmp.filter((x) => x.cat === k).length])])
    const shown = computed(() => s.cmp.filter((x) => s.cat === '全部' || x.cat === s.cat))
    const tot = (side) => rnd(sum(s.cmp.filter((x) => x[side]), (x) => x[side].amt), 2)
    function demo() { f.files = [ocrDemoFile()] }
    function recognize() {
      if (!f.sup) return toast('先选供应商', null, true)
      if (!f.files.length) return toast('先传工厂的对账单：照片、扫描件或 PDF', null, true)
      s.step = 'busy'
      setTimeout(() => { s.rows = ocrFake(f.sup, f.month); s.cmp = ocrCompare(s.rows, ocrOurs(f.sup, f.month)); s.cat = '全部'; s.step = 'done' }, 900)
    }
    function save() {
      const st = run(() => ocrCreate({ ...f, rows: s.rows, cmp: s.cmp }))
      if (st?.no) { emit('close'); go('/finance/' + st.no); toast(`${st.no} 已生成，原件和比对结果挂在对账单上`) }
    }
    return { f, s, sups, months, ours, cats, shown, tot, demo, recognize, save, OCR_CATS, OCR_NOTE }
  },
  template: `<Modal t="上传工厂对账单" :w="s.step === 'done' ? 1080 : 520" @close="$emit('close')">
    <template v-if="s.step !== 'done'">
      <div class="form">
        <label class="f">供应商<select class="sel" v-model="f.sup" :disabled="s.step === 'busy'"><option v-for="x in sups" :key="x.code" :value="x.code">{{ x.short || x.name }}</option></select></label>
        <label class="f">月份<select class="sel" v-model="f.month" :disabled="s.step === 'busy'"><option v-for="m in months" :key="m" :value="m">{{ m }}</option></select></label>
        <div class="f full">对账单原件<Pics v-model="f.files" files :max="5" :s="64" tip="工厂发来的对账单：拍照、扫描件、PDF、Excel 都行"/>
          <a v-if="!f.files.length" href="javascript:;" style="font-size:12px" @click="demo">手边没有文件？用示例对账单</a></div>
      </div>
      <p class="mut" style="margin:4px 0 0">我方这个月待对账：{{ ours.filter(x => x.k === '货款').length }} 张单据，{{ fm(rnd(sum(ours, x => x.amt), 2)) }}</p>
      <p v-if="s.step === 'busy'" class="row g8" data-c="识别中" style="margin-top:12px"><span class="spin"></span>识别中：读表格、对单号……</p>
    </template>
    <template v-else>
      <div class="row g8" style="margin-bottom:10px"><Seg v-model="s.cat" :items="cats"/><span class="sp"></span>
        <span class="mut">工厂合计 {{ fm(tot('t')) }} · 我方合计 {{ fm(tot('o')) }} · 差 <b :class="{ red: tot('t') !== tot('o') }">{{ fm(rnd(tot('t') - tot('o'), 2)) }}</b></span></div>
      <table class="tbl inset flat" data-c="比对结果">
        <thead><tr><th>结果</th><th>工厂对账单</th><th class="num">数量</th><th class="num">单价</th><th class="num">金额</th><th>我方单据</th><th class="num">数量</th><th class="num">金额</th><th class="num">差额</th><th style="width:210px">说明</th></tr></thead>
        <tbody>
          <tr v-for="(x, i) in shown" :key="i">
            <td><span :class="OCR_CATS[x.cat][1]">{{ OCR_CATS[x.cat][0] }}</span></td>
            <template v-if="x.t"><td><div>{{ fd(x.t.date) }} <span class="code">{{ x.t.no }}</span></div><small class="dim">{{ x.t.desc }}</small></td><td class="num">{{ fq(x.t.qty) }}</td><td class="num">{{ fm(x.t.price) }}</td><td class="num">{{ fm(x.t.amt) }}</td></template>
            <template v-else><td colspan="4" class="dim">工厂对账单上没有</td></template>
            <template v-if="x.o"><td><div class="code">{{ x.o.no }}</div><small class="dim">{{ x.o.k === '扣款' ? '次品扣款' : fd(x.o.date) + ' ' + x.o.type }}</small></td><td class="num">{{ fq(x.o.qty) }}</td><td class="num">{{ fm(x.o.amt) }}</td></template>
            <template v-else><td colspan="3" class="dim">我方没有这张单据</td></template>
            <td class="num" :class="{ red: x.da }">{{ x.da ? fm(x.da) : '—' }}</td>
            <td><input v-if="x.cat !== '一致'" class="inp cell" style="min-width:200px" v-model="x.note" :placeholder="x.cat === '不一致' ? '比如：按我方单价结，工厂认了' : x.cat === '对方有' ? '比如：回货还没登记，下月对' : '比如：扣款工厂没列，已电话确认'"><span v-else class="dim">—</span></td>
          </tr>
          <tr v-if="!shown.length"><td colspan="10" class="dim">{{ s.cmp.length ? '这一类没有' : '没识别出明细，换一张清楚的照片再试' }}</td></tr>
        </tbody>
      </table>
      <p class="mut" style="margin:8px 0 0">对账单金额按我方单据算；有差异的每一行写清楚怎么处理（和工厂谈的结果），写完才能生成。原件和识别结果都挂在对账单上。</p>
    </template>
    <p class="dim" style="font-size:12px;margin:10px 0 0" data-c="识别说明">{{ OCR_NOTE }}</p>
    <template #foot>
      <template v-if="s.step === 'done'"><button class="btn" @click="s.step = 'form'">重新上传</button><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" @click="save">按比对结果生成对账单</button></template>
      <template v-else><span class="sp"></span><button class="btn" @click="$emit('close')">取消</button><button class="btn pri" :disabled="s.step === 'busy'" @click="recognize">{{ s.step === 'busy' ? '识别中…' : '识别并比对' }}</button></template>
    </template>
  </Modal>`,
})

route('/finance', {
  setup() {
    const s = reactive({ tab: (!TRADE && nav.query.tab === '应收' ? '' : nav.query.tab) || '对账', inv: null, ocr: false })
    const groups = computed(finGroups)
    const stmts = computed(() => db.statements.slice().reverse())
    const recv = computed(finReceivables)
    const plan = computed(() => [
      ...db.statements.filter((x) => ['草稿', '已发送', '已确认'].includes(x.status)).map((x) => ({ sup: x.sup, ref: x.no, to: '/finance/' + x.no, amt: finAmt(x), due: finDue(x), st: x.status })),
      ...groups.value.map((g) => ({ sup: g.sup, ref: g.items.length + ' 张单据未对账', to: null, amt: g.amt, due: addDays(finMonthEnd(g.month), sup(g.sup)?.term || 0), st: '待对账' })),
    ].sort((a, b) => a.due.localeCompare(b.due)))
    const tabs = computed(() => [['对账', '供应商对账', groups.value.length || null], ...(TRADE ? [['应收', '客户应收', recv.value.filter((x) => x.status !== '已收款').length || null]] : []), ['付款', '付款计划'], ['发票', '发票', db.invoices.length]])
    function create(g) { const st = run(() => finCreate(g.sup, g.month)); if (st?.no) go('/finance/' + st.no) }
    function collect(x) { if (run(() => finReceive(x.sh, rnd(x.amt - x.got, 2)))) toast(`${x.sh.no} 收款 ${fm(x.amt - x.got)}`) }
    function bill(x) { if (run(() => finInvoice({ dir: '开票', party: x.sh.customer, ref: x.sh.no, amt: x.amt }))) toast('已登记开票') }
    return { s, groups, stmts, recv, plan, tabs, create, collect, bill }
  },
  template: `<div>
    <Head t="对账"><button v-if="s.tab === '对账'" class="btn" @click="s.ocr = true"><Ico n="upload" :s="14"/>上传工厂对账单</button></Head>
    <FinOcr v-if="s.ocr" @close="s.ocr = false"/>
    <div class="bar" data-c="筛选"><Seg v-model="s.tab" :items="tabs"/></div>
    <div v-if="s.tab === '对账'">
      <div data-c="待对账">
        <Tbl :rows="groups" empty="入库、回货都已对账" :cols="[
          { k: 'sup', t: '供应商', f: x => supName(x.sup) }, { k: 'month', t: '月份' }, { k: 'n', t: '单据', c: 'num', f: x => x.items.length },
          { k: 'deduct', t: '扣款', c: 'num', f: x => sum(x.items, i => i.deduct) ? fm(sum(x.items, i => i.deduct)) : '—' }, { k: 'amt', t: '应付', c: 'num', f: x => fm(x.amt) }, { k: 'st', t: '状态' }, { k: 'act', t: '' }]">
          <template #st="{ r }"><St s="待对账"/></template>
          <template #act="{ r }"><button class="btn sm" @click="create(r)">生成对账单</button></template>
        </Tbl>
      </div>
      <div data-c="对账单" style="margin-top:24px">
        <Tbl :rows="stmts" link @row="x => go('/finance/' + x.no)" empty="还没有对账单" :cols="[
          { k: 'no', t: '对账单', c: 'code' }, { k: 'sup', t: '供应商', f: x => supName(x.sup) }, { k: 'month', t: '月份' }, { k: 'n', t: '单据', c: 'num', f: x => x.items.length },
          { k: 'amt', t: '应付', c: 'num', f: x => fm(finAmt(x)) }, { k: 'due', t: '到期', f: x => fd(finDue(x)) }, { k: 'status', t: '状态' }]">
          <template #status="{ r }"><St :s="r.status"/></template>
        </Tbl>
      </div>
    </div>
    <div v-if="TRADE && s.tab === '应收'" data-c="客户应收">
      <Tbl :rows="recv" empty="还没有发货，没有应收" :cols="[
        { k: 'no', t: '发货单', c: 'code', f: x => x.sh.no }, { k: 'cust', t: '客户', f: x => custName(x.sh.customer) }, { k: 'order', t: '订单', c: 'code', f: x => x.sh.order },
        { k: 'amt', t: '应收', c: 'num', f: x => fm(x.amt) }, { k: 'got', t: '已收', c: 'num', f: x => fm(x.got) }, { k: 'due', t: '到期', f: x => fd(x.due) }, { k: 'left', t: '' },
        { k: 'status', t: '状态' }, { k: 'act', t: '' }]">
        <template #left="{ r }"><Days :d="r.due" :done="r.status === '已收款'" :warn="7"/></template>
        <template #status="{ r }"><St :s="r.status"/></template>
        <template #act="{ r }"><span class="row g4"><button v-if="r.status !== '已收款'" class="btn sm" @click="collect(r)">收款</button><button v-if="!r.inv" class="btn sm ghost" @click="bill(r)">开票</button></span></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '付款'" data-c="付款计划">
      <Tbl :rows="plan" empty="没有要付的款" :cols="[
        { k: 'due', t: '到期', f: x => fd(x.due) }, { k: 'left', t: '' }, { k: 'sup', t: '供应商', f: x => supName(x.sup) }, { k: 'ref', t: '来源' },
        { k: 'amt', t: '金额', c: 'num', f: x => fm(x.amt) }, { k: 'st', t: '状态' }]">
        <template #left="{ r }"><Days :d="r.due" :warn="7"/></template>
        <template #ref="{ r }"><a v-if="r.to" class="code" :href="'#' + r.to">{{ r.ref }}</a><span v-else class="mut">{{ r.ref }}</span></template>
        <template #st="{ r }"><St :s="r.st"/></template>
      </Tbl>
    </div>
    <div v-if="s.tab === '发票'" data-c="发票">
      <Tbl :rows="db.invoices.slice().reverse()" empty="还没有登记发票" :cols="[
        { k: 'no', t: '发票号', c: 'code' }, { k: 'dir', t: '方向' }, { k: 'party', t: '往来', f: x => x.dir === '收票' ? supName(x.party) : custName(x.party) },
        { k: 'ref', t: '对应单据', c: 'code' }, { k: 'amt', t: '金额', c: 'num', f: x => fm(x.amt) }, { k: 'rate', t: '税率' }, { k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'status', t: '核销' }, { k: 'act', t: '' }]">
        <template #ref="{ r }"><a class="code" :href="r.dir === '收票' ? '#/finance/' + r.ref : '#/fg?tab=发货'">{{ r.ref }}</a></template>
        <template #status="{ r }"><St :s="r.status"/></template>
        <template #act="{ r }"><button v-if="r.status === '未核销'" class="btn sm" @click="run(() => finVerify(r))">核销</button></template>
      </Tbl>
    </div>
  </div>`,
}, { mod: 'finance', t: '对账' })

route('/finance/:no', {
  props: ['no'],
  setup(p) {
    const st = computed(() => db.statements.find((x) => x.no === p.no))
    const inv = computed(() => db.invoices.find((i) => i.ref === p.no))
    const acts = computed(() => (st.value ? actionsOf('statement', st.value).filter((a) => a !== '作废') : []))
    function doIt(a) { if (run(() => finStmtAction(st.value, a))) toast(`${st.value.no} ${st.value.status}`) }
    async function voidIt() { if (await ask('作废对账单', '作废后里面的单据回到"待对账"，可以重新生成。', '作废', true)) run(() => finStmtAction(st.value, '作废')) }
    function invoice() { const i = run(() => finInvoice({ dir: '收票', party: st.value.sup, ref: st.value.no, amt: finAmt(st.value) })); if (i?.no) toast('已登记收票，金额和对账单一致') }
    return { st, inv, acts, doIt, voidIt, invoice }
  },
  template: `<div v-if="st">
    <Head :t="st.no" :back="['对账', '/finance']">
      <button v-for="a in acts" :key="a" class="btn pri" @click="doIt(a)">{{ a === '确认' ? '供应商已确认' : a === '付款' ? '登记付款' : '发给供应商' }}</button>
      <button v-if="['已确认', '已付款'].includes(st.status) && !inv" class="btn" @click="invoice">登记收票</button>
      <More :items="can('statement', st, '作废') ? [{ t: '作废', danger: true, fn: voidIt }] : []"/>
    </Head>
    <div class="detail">
      <div class="body">
        <div class="title">{{ supName(st.sup) }} · {{ st.month }}<St :s="st.status"/></div>
        <div class="sub"><span>{{ st.items.length }} 张单据</span><span>应付 {{ fm(finAmt(st)) }}</span></div>
        <section data-c="对账明细">
          <Tbl inset flat :rows="st.items" :cols="[
            { k: 'type', t: '类型' }, { k: 'no', t: '单号' }, { k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'desc', t: '内容' },
            { k: 'qty', t: '数量', c: 'num', f: x => fq(x.qty) }, { k: 'amt', t: '金额', c: 'num', f: x => fm(x.amt) }, { k: 'deduct', t: '次品扣款', c: 'num', f: x => x.deduct ? '−' + fm(x.deduct) : '—' }]">
            <template #no="{ r }"><a class="code" :href="r.type === '回货' ? '#/production/' + r.prod : '#/stock-docs/' + r.no">{{ r.no }}</a></template>
            <template #desc="{ r }"><span class="ell mut" style="max-width:300px;display:inline-block;vertical-align:middle">{{ r.desc }}</span></template>
            <template #foot><tr><td>合计</td><td></td><td></td><td></td><td></td><td class="num">{{ fm(sum(st.items, i => i.amt)) }}</td><td class="num">{{ sum(st.items, i => i.deduct) ? '−' + fm(sum(st.items, i => i.deduct)) : '—' }}</td></tr></template>
          </Tbl>
        </section>
        <section v-if="st.ocr" class="sec" data-c="工厂对账单比对">
          <h3>工厂对账单比对<span class="dim" style="font-weight:400">{{ st.ocr.file }} · {{ fd(st.ocr.at) }} 识别 {{ st.ocr.rows.length }} 行 · 工厂合计 {{ fm(st.ocr.theirAmt) }}</span></h3>
          <p class="row g8" style="margin:0 0 8px"><span v-for="(v, k) in OCR_CATS" :key="k" :class="v[1]">{{ v[0] }} {{ st.ocr.cmp.filter(x => x.cat === k).length }}</span></p>
          <Tbl inset flat :rows="st.ocr.cmp.filter(x => x.cat !== '一致')" empty="和工厂的对账单完全一致" :cols="[
            { k: 'cat', t: '结果' }, { k: 'their', t: '工厂对账单' }, { k: 'our', t: '我方单据' }, { k: 'da', t: '差额', c: 'num', f: x => fm(x.da) }, { k: 'note', t: '说明', c: 'wrap' }]">
            <template #cat="{ r }"><span :class="OCR_CATS[r.cat][1]">{{ OCR_CATS[r.cat][0] }}</span></template>
            <template #their="{ r }"><template v-if="r.t"><div>{{ fd(r.t.date) }} <span class="code">{{ r.t.no }}</span></div><small class="dim">{{ r.t.desc }} · {{ fq(r.t.qty) }} × {{ fm(r.t.price) }} = {{ fm(r.t.amt) }}</small></template><span v-else class="dim">没有</span></template>
            <template #our="{ r }"><template v-if="r.o"><div class="code">{{ r.o.no }}</div><small class="dim">{{ r.o.k === '扣款' ? '次品扣款 · ' : '' }}{{ fq(r.o.qty) }} · {{ fm(r.o.amt) }}</small></template><span v-else class="dim">没有</span></template>
          </Tbl>
          <details style="margin-top:8px"><summary class="mut" style="cursor:pointer">识别结果（{{ st.ocr.rows.length }} 行）</summary>
            <Tbl inset flat :rows="st.ocr.rows" :cols="[{ k: 'date', t: '日期', f: x => fd(x.date) }, { k: 'no', t: '送货单号', c: 'code' }, { k: 'desc', t: '内容' }, { k: 'qty', t: '数量', c: 'num', f: x => fq(x.qty) }, { k: 'price', t: '单价', c: 'num', f: x => fm(x.price) }, { k: 'amt', t: '金额', c: 'num', f: x => fm(x.amt) }]"/>
          </details>
          <p class="dim" style="font-size:12px;margin-top:6px">{{ OCR_NOTE }}</p>
        </section>
      </div>
      <aside class="aside" data-c="对账单属性">
        <dl class="props">
          <dt>供应商</dt><dd>{{ sup(st.sup).name }}</dd>
          <dt>结算方式</dt><dd>{{ sup(st.sup).settle }} · 账期 {{ sup(st.sup).term }} 天</dd>
          <dt>到期</dt><dd>{{ fd(finDue(st)) }} <Days v-if="st.status !== '已付款'" :d="finDue(st)" :warn="7"/></dd>
          <dt>付款</dt><dd>{{ st.paidAt ? fd(st.paidAt) : '—' }}</dd>
          <dt>发票</dt><dd><template v-if="inv"><span class="code">{{ inv.no }}</span> <St :s="inv.status"/></template><template v-else>—</template></dd>
        </dl>
        <DocFiles :doc="st"/>
        <h4>操作记录</h4><Log :items="st.log"/>
      </aside>
    </div>
  </div><NotFound v-else/>`,
}, { mod: 'finance', t: (p) => '对账单 ' + p.no })

/* ============================== 报表 ============================== */
// 单系列横条：一个颜色（主色），数值写在条旁边，鼠标悬停看原值
function rptOrders() {
  return db.orders.filter((o) => !['草稿', '待审核', '已取消'].includes(o.status)).map((o) => {
    const due = o.commit || o.due
    const lastRet = liveReturns().filter((r) => r.order === o.no).map((r) => r.date).sort().pop()
    const res = o.status === '已完成' ? (lastRet && lastRet > due ? '延期完成' : '准时完成') : clock.today > due ? '已延期' : '进行中'
    return { o, due, res, qty: orderQty(o), ret: orderRet(o) }
  })
}
function rptSuppliers() {
  const by = {}
  db.stockDocs.filter((d) => d.type === '采购入库' && d.status === '已审核' && d.src).forEach((d) => {
    const p = purchase(d.src)
    const x = (by[d.partner] ||= { c: d.partner, n: 0, ok: 0, late: 0 })
    d.lines.forEach((l) => { const due = p.lines[l.poLine].due || p.due; x.n++; if (d.date <= due) x.ok++ })
  })
  arrivals().filter((a) => a.risk === 'late').forEach((a) => { (by[a.p.supplier] ||= { c: a.p.supplier, n: 0, ok: 0, late: 0 }).late++ })
  return Object.values(by).map((x) => ({ ...x, rate: x.n ? x.ok / x.n : null }))
}
function rptFactories() {
  const codes = [...new Set(db.productions.filter((p) => p.status !== '已作废').map((p) => p.factory))]
  return codes.map((c) => {
    const live = db.productions.filter((p) => p.factory === c && prodLive(p))
    const all = db.productions.filter((p) => p.factory === c)
    return { c, n: live.length, wip: sum(live, (p) => prodQty(p) - prodRet(p)), late: live.filter((p) => prodRisk(p).late).length, rate: sum(all, prodRet) ? sum(all, prodDef) / sum(all, prodRet) : null }
  })
}
route('/reports', {
  setup() {
    const orders = computed(rptOrders)
    const sups = computed(rptSuppliers)
    const facs = computed(rptFactories)
    const margins = computed(() => db.styles.map((s) => ({ s, c: costOf(s.code) })).filter((x) => x.c && x.c.quote).sort((a, b) => b.c.rate - a.c.rate))
    const maxWip = computed(() => Math.max(1, ...facs.value.map((x) => x.wip)))
    const onTime = computed(() => { const d = orders.value.filter((x) => x.res.endsWith('完成')); return { n: d.length, ok: d.filter((x) => x.res === '准时完成').length, late: orders.value.filter((x) => x.res === '已延期').length } })
    const W = 140
    return { orders, sups, facs, margins, maxWip, onTime, W }
  },
  template: `<div>
    <Head t="报表"/>
    <div class="page narrow">
      <div class="h2" style="margin-top:0">订单交付<span class="dim" style="font-weight:400">已完成 {{ onTime.n }} 单，准时 {{ onTime.ok }}；进行中已延期 {{ onTime.late }}</span></div>
      <div data-c="订单交付">
        <Tbl inset flat :rows="orders" link @row="x => go('/orders/' + x.o.no)" empty="还没有审核过的订单" :cols="[
          { k: 'no', t: '订单', c: 'code', f: x => x.o.no }, { k: 'cust', t: TRADE ? '客户' : '类型', f: x => custOrDash(x.o) },
          { k: 'qty', t: '数量', c: 'num' }, { k: 'ret', t: '回货' }, { k: 'due', t: '承诺交期', f: x => fd(x.due) }, { k: 'res', t: '交付' }]">
          <template #ret="{ r }"><Prog :a="r.ret" :b="r.qty"/></template>
          <template #res="{ r }"><span :class="{ red: r.res === '已延期' || r.res === '延期完成', green: r.res === '准时完成' }">{{ r.res }}</span></template>
        </Tbl>
      </div>
      <div class="h2">供应商到货准时率</div>
      <div data-c="到货准时率">
        <Tbl inset flat :rows="sups" empty="还没有采购入库" :cols="[{ k: 'c', t: '供应商', f: x => supName(x.c) }, { k: 'n', t: '到货行', c: 'num' }, { k: 'ok', t: '准时', c: 'num' }, { k: 'rate', t: '准时率' }, { k: 'late', t: '现逾期行', c: 'num' }]">
          <template #rate="{ r }"><span v-if="r.rate != null" class="hbar" :title="costRate(r.rate)"><b :style="{ width: r.rate * W + 'px' }"></b>{{ costRate(r.rate) }}</span><span v-else class="dim">—</span></template>
          <template #late="{ r }"><span :class="{ red: r.late }">{{ r.late || '—' }}</span></template>
        </Tbl>
      </div>
      <div class="h2">工厂在制</div>
      <div data-c="工厂在制">
        <Tbl inset flat :rows="facs" empty="没有加工单" :cols="[{ k: 'c', t: '加工厂', f: x => supName(x.c) }, { k: 'n', t: '在制单', c: 'num' }, { k: 'wip', t: '在制件数' }, { k: 'late', t: '逾期单', c: 'num' }, { k: 'rate', t: '次品率', c: 'num' }]">
          <template #wip="{ r }"><span class="hbar" :title="r.wip + ' 件'"><b :style="{ width: r.wip / maxWip * W + 'px' }"></b>{{ r.wip }}</span></template>
          <template #late="{ r }"><span :class="{ red: r.late }">{{ r.late || '—' }}</span></template>
          <template #rate="{ r }">{{ r.rate == null ? '—' : costRate(r.rate) }}</template>
        </Tbl>
      </div>
      <div class="h2">单款毛利</div>
      <div data-c="单款毛利">
        <Tbl inset flat :rows="margins" link @row="x => go('/costing/' + x.s.code)" empty="还没有能算成本的款" :cols="[
          { k: 'code', t: '款式' }, { k: 'total', t: '总成本', c: 'num', f: x => fm(x.c.total) }, { k: 'quote', t: '报价', c: 'num', f: x => fm(x.c.quote) }, { k: 'rate', t: '毛利率' }]">
          <template #code="{ r }"><span class="row"><Thumb :code="r.s.code" :s="22"/><span class="code">{{ r.s.code }}</span><span class="mut">{{ r.s.name }}</span></span></template>
          <template #rate="{ r }"><span v-if="r.c.rate > 0" class="hbar" :title="costRate(r.c.rate)"><b :style="{ width: Math.min(1, r.c.rate) * W + 'px' }"></b>{{ costRate(r.c.rate) }}</span><span v-else class="red">{{ costRate(r.c.rate) }}</span></template>
        </Tbl>
      </div>
    </div>
  </div>`,
}, { mod: 'reports', t: '报表' })

expose({
  seasonName, PLAN, planActual, smpCur, smpState, smpNode, smpWho, smpNodeState, costOf, costRate, rowMidUse, unitPrice,
  qcAql, qcOf, fgQty, fgPush, finAmt, finDue, finVerify, OVER,
  OCR_CATS, OCR_NOTE, ocrOurs, ocrFake, ocrCompare, ocrCreate, ocrDemoFile, costTp,
})
