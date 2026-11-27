<script setup lang="ts">
import ThumbImage from '@/components/ThumbImage.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { productionApi as productionApiBase } from '@/api/production'
import { kittingApi } from '@/api/purchase'
import { fgDocApi } from '@/api/fg'
import type { FgReceiptStocked } from '@/types/fg'
import { techPackApi } from '@/api/techpack'
import ExcelPreviewDialog from '@/components/ExcelPreviewDialog.vue'
import { PACK_FILE_GROUPS, type PackFile } from '@/types/techpack'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix, { type MatrixCell, type MatrixColor } from '@/components/SkuMatrix.vue'
import StatusTag from '@/components/StatusTag.vue'
import ApprovalPanel from '@/components/doc/ApprovalPanel.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import DocNoticesBanner from '@/components/doc/DocNoticesBanner.vue'
import RejectNotice from '@/components/doc/RejectNotice.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import PrintSheet from '@/components/doc/PrintSheet.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import { isItem, reloadOnConflict, runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import type { DocAction } from '@/types/biz'
import { INV_DOC_STATUS, INV_DOC_TYPE } from '@/types/inventory'
import { RESPONSIBLE_PARTY } from '@/types/order'
import {
  ISSUE_STATUS,
  MATERIAL_ISSUE_STATUS,
  NODE_STATE,
  PRODUCTION_KIND,
  PRODUCTION_LOG_ACTION,
  productionStatusOf,
  PRODUCTION_STATUS,
  RECEIPT_STATUS,
  type NodeReport,
  type ProductionAction,
  type ProductionDetail,
  type ProductionIssue,
  type ProductionMaterials,
  type ProductionNode,
  type ProductionReceipt,
  type ProductionStockDoc,
  LEDGER_STATUS,
} from '@/types/production'
import { KITTING_STATUS, type OrderKitting } from '@/types/purchase'
import type { FileVO } from '@/types/system'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import ProdEditDialog from './ProdEditDialog.vue'
import ProdIssueDialogs from './ProdIssueDialogs.vue'
import ProdNodeDialogs from './ProdNodeDialogs.vue'
import ProdCutDialog from './ProdCutDialog.vue'
import ProdReceiveDialog from './ProdReceiveDialog.vue'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeProductionPrice, canSeeSupplierContact } = useFieldAccess()
const productionApi = bindCompanyApi(productionApiBase, () => detail.value?.companyId)

/**
 * 外发加工单 / 成品采购单详情（/production/:id、/fp-purchases/:id，docs/api/05 §1.2、§1.4、§4）。两种单共用。
 * - 地址和单子的种类对不上（回货单、异常的 docRoute 一律先去 /production/:id）：换到对的地址，带上 ?tab=，这个页签关掉
 * - 页签 ?tab=progress|lines|materials|issues|receipts，keep-alive 下再从别处点进来也认（只认本页地址）
 * - 页头：编辑、提交、审核 / 驳回（不是我审时写"现在等××审"）、领料、登记回货、报进度；
 *   上报异常、改节点计划、退料、撤回、反审核、结案、取消结案、打印、工艺单、删除、作废在"更多"里
 * - 状态动作走 runDocAction：1002 重新取详情
 * - 领料、退料、异常、回货、齐料各自取，一个取不到不影响别的
 * - 打印：页面里的 A4 打印版（PrintSheet），带颜色 × 尺码数量表和签字栏；工艺单是资料包上传的文件（RFC 011 §2.4），"工艺单"里列出来下载、预览
 */
// 操作记录放页签（和采购单一样）：侧栏 300px 放不下五列，驳回原因被挤到看不见的地方（走查 T-7d453c）
type TabName = 'progress' | 'lines' | 'materials' | 'issues' | 'receipts' | 'logs'
const TABS: TabName[] = ['progress', 'lines', 'materials', 'issues', 'receipts', 'logs']
const pickTab = (v: unknown): TabName | null => (TABS.includes(v as TabName) ? (v as TabName) : null)

const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()

const id = routeId(route.params.id)
const path = route.path
const modes = useDict('process_mode')
const settles = useDict('settle_method')
const issueTypes = useDict('issue_type')
const units = useDict('unit')

const detail = ref<ProductionDetail | null>(null)
const notFound = ref(false)
const matData = ref<ProductionMaterials | null>(null)
const issues = ref<ProductionIssue[]>([])
const receipts = ref<ProductionReceipt[]>([])
const kitting = ref<OrderKitting | null>(null)

const tab = ref<TabName>(pickTab(route.query.tab) ?? 'progress')
watch(
  () => route.query.tab,
  (t) => {
    if (route.path !== path) return
    const next = pickTab(t)
    if (next) tab.value = next
  },
)

const logRef = ref<InstanceType<typeof DocLogList>>()
const printRef = ref<InstanceType<typeof PrintSheet>>()

const editVisible = ref(false)
const reportNodeVisible = ref(false)
const targetNodeId = ref<string | null>(null)
const planNodeVisible = ref(false)
const cutVisible = ref(false)
const reportIssueVisible = ref(false)
const closeIssueVisible = ref(false)
const closingIssue = ref<ProductionIssue | null>(null)
const receiveCreateVisible = ref(false)
const receiveDetailVisible = ref(false)
const viewingReceipt = ref<ProductionReceipt | null>(null)
const invDocVisible = ref(false)
const invDocType = ref<'ISSUE_OUT' | 'RETURN_IN' | 'CONSUME_OUT'>('ISSUE_OUT')

const can = (a: ProductionAction) => detail.value?.actions.includes(a) ?? false

const kindMeta = computed(() => PRODUCTION_KIND[detail.value?.kind ?? (path.startsWith(PRODUCTION_KIND.BUY.path) ? 'BUY' : 'OUTSOURCE')])
const statusTag = computed(() => {
  const d = detail.value
  if (!d) return PRODUCTION_STATUS.DRAFT
  return productionStatusOf(d)
})
/** 审核以后：明细格子下面写回货，侧栏多当前节点、齐料、领料、回货 */
const audited = computed(() => !!detail.value && !['DRAFT', 'PENDING', 'VOIDED'].includes(detail.value.status))
const priceChanged = computed(() => {
  const d = detail.value
  return canSeeProductionPrice.value && !!d && d.unitPrice != null && d.archivePrice != null && Math.abs(d.unitPrice - d.archivePrice) > 0.00005
})
/** 不是我审、在等别人审：页头写一句 */
const waitingText = computed(() => {
  const a = detail.value?.approval
  return detail.value?.status === 'PENDING' && a?.waitingFor && !a.canPass ? `现在等${a.waitingFor}审` : ''
})

// ---------- 取数 ----------
const detailRequest = useLatestRequest()
const subsRequest = useLatestRequest()
const kittingRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  subsRequest.cancel()
  kittingRequest.cancel()
  try {
    const d = await read.run(() => productionApi.get(id))
    if (!read.current()) return
    const right = `${PRODUCTION_KIND[d.kind].path}/${id}`
    if (right !== path) {
      // 种类对不上：换到对的地址（那边会重新取），?tab= 带过去，这个页签关掉
      await router.replace({ path: right, query: route.query })
      tabs.close(path, right)
      return
    }
    detail.value = d
    tabs.rename(path, `${PRODUCTION_KIND[d.kind].short} ${d.no}`)
    void loadSubs(d)
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

/** 页签和侧栏的数：各取各的，一个出错（request.ts 已经提示）不影响别的 */
async function loadSubs(d: ProductionDetail) {
  const read = subsRequest.begin()
  await read.run(() => Promise.allSettled([
    productionApi.materials(id).then((m) => { if (read.current()) matData.value = m }),
    productionApi.issues(id).then((list) => { if (read.current()) issues.value = list }),
    productionApi.receipts(id).then((list) => { if (read.current()) { receipts.value = list; void loadStocked(list) } }),
    loadKitting(d),
  ]))
}

/** 二期成品（07 §3.7）：每张回货单入了哪几张成品入库单；没权限、取不到就不显示这一列 */
const canSeeFg = computed(() => user.hasPerm('fg:doc:list'))
const stocked = ref(new Map<string, FgReceiptStocked[]>())
async function loadStocked(list: ProductionReceipt[]) {
  const ids = list.filter((r) => r.status === 'APPROVED').map((r) => r.id)
  if (!canSeeFg.value || !ids.length) {
    stocked.value = new Map()
    return
  }
  try {
    const map = new Map<string, FgReceiptStocked[]>()
    for (const r of await fgDocApi.byReceipts(ids)) map.set(r.receiptId, [...(map.get(r.receiptId) ?? []), r])
    stocked.value = map
  } catch {
    stocked.value = new Map()
  }
}

async function loadKitting(d: ProductionDetail) {
  const read = kittingRequest.begin()
  kitting.value = null
  if (!['APPROVED', 'RUNNING', 'DONE'].includes(d.status)) return
  try {
    const list = await read.run(() => kittingApi.byOrders([d.orderId]))
    if (read.current()) kitting.value = list[0] ?? null
  } catch {
    // 取不到写"—"
  }
}

async function refresh() {
  await load()
  logRef.value?.reload()
}

onMounted(load)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (!notFound.value) void refresh()
})

// ---------- 动作 ----------
async function act(action: DocAction) {
  const d = detail.value
  if (!d) return
  // 审核时单价和价格档案对不上的先说一句（甲方 11-21 问 Q25：审核时直接提示），和采购单一样，不拦
  const note = action === 'audit' && priceChanged.value
    ? `${kindMeta.value.priceLabel} ${formatPrice(d.unitPrice)} 和价格档案 ${formatPrice(d.archivePrice)} 不一样（改价原因：${d.priceChangeReason || '没写'}），审核后记进价格档案。`
    : undefined
  const ok = await runDocAction(action, d.no, d.version, (a, body, o) => productionApi.act(id, a, body, o), { note, reload: refresh, approval: d.approval })
  if (ok) await refresh()
}

async function remove() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, `草稿删除后找不回来，${formatQty(d.totalQty)} 件退回需求订单的"待排单"。`)
  const back = kindMeta.value.path
  await productionApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push(back)
  tabs.close(path, back)
}

function openReport(node?: ProductionNode) {
  targetNodeId.value = node?.id ?? null
  reportNodeVisible.value = true
}

function onNodeClick(node: ProductionNode) {
  if (can('report')) openReport(node)
}

function openMatDoc(docType: 'ISSUE_OUT' | 'RETURN_IN' | 'CONSUME_OUT') {
  invDocType.value = docType
  invDocVisible.value = true
}

/** 核销耗料（RFC 015 §4.1）：已完成、已结案，还没开核销单，有新建出入库单的权限 */
const canSettle = computed(() => matData.value?.ledgerStatus === 'TO_SETTLE' && user.hasPerm('inventory:doc:add'))

function goSettleDoc() {
  const d = matData.value?.settleDoc
  if (d) void router.push(`/stock-out/${d.id}`)
}

function onInvCreated() {
  tab.value = 'materials'
  void refresh()
}

async function startIssue(issue: ProductionIssue) {
  try {
    await productionApi.startIssue(id, issue.id)
  } catch (e) {
    if (reloadOnConflict(e, refresh)) return
    throw e
  }
  ElMessage.success(`${issue.no} 开始处理`)
  await refresh()
}

function openCloseIssue(issue: ProductionIssue) {
  closingIssue.value = issue
  closeIssueVisible.value = true
}

function openReceipt(r: ProductionReceipt) {
  viewingReceipt.value = r
  receiveDetailVisible.value = true
}

function goStockDoc(doc: ProductionStockDoc) {
  const to = docRoute({ type: 'INV_DOC', kind: doc.kind, id: doc.id, parentId: null })
  if (to) void router.push(to)
}

// ---------- 汇报记录 ----------
const historyNode = ref<ProductionNode | null>(null)
const historyRows = ref<NodeReport[]>([])
const historyLoading = ref(false)
const historyVisible = ref(false)
const historyRequest = useLatestRequest()
watch(historyVisible, (visible) => { if (!visible) historyRequest.cancel() })
async function openHistory(node: ProductionNode) {
  const read = historyRequest.begin()
  historyNode.value = node
  historyRows.value = []
  historyVisible.value = true
  historyLoading.value = true
  try {
    const rows = await read.run(() => productionApi.nodeReports(id, node.id))
    if (read.current()) historyRows.value = rows
  } finally {
    if (read.current()) historyLoading.value = false
  }
}

// ---------- 工艺单：锁定版本上传的文件（导入的 BOM 表不列），发工厂就发它 ----------
const canSeeCraft = computed(() => user.hasPerm('techpack:pack:list'))
const craftVisible = ref(false)
const craftLoading = ref(false)
const craftFiles = ref<PackFile[]>([])
async function openCraft() {
  const d = detail.value
  if (!d) return
  craftVisible.value = true
  craftLoading.value = true
  craftFiles.value = []
  try {
    craftFiles.value = (await techPackApi.files(d.techPackId)).filter((f) => f.category !== 'BOM_SOURCE')
  } finally {
    craftLoading.value = false
  }
}
const craftGroupLabel = (f: PackFile) => PACK_FILE_GROUPS.find((g) => g.category === f.category)?.label ?? ''
const isExcel = (name: string) => /\.(xls|xlsx)$/i.test(name)
const previewing = ref(false)
const previewFile = ref<PackFile | null>(null)
function previewCraft(f: PackFile) {
  previewFile.value = f
  previewing.value = true
}
const loadCraftPreview = () => techPackApi.preview(detail.value!.techPackId, previewFile.value!.id)

// ---------- 更多 ----------
type MoreCommand = 'reportIssue' | 'editPlan' | 'cut' | 'returnMaterial' | 'withdraw' | 'unaudit' | 'close' | 'reopen' | 'print' | 'craft' | 'remove' | 'void'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('reportIssue') && { command: 'reportIssue', label: '上报异常' },
    can('editPlan') && { command: 'editPlan', label: '改节点计划' },
    can('cut') && { command: 'cut', label: '登记裁床数' },
    can('returnMaterial') && { command: 'returnMaterial', label: '退料' },
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('unaudit') && { command: 'unaudit', label: '反审核' },
    can('close') && { command: 'close', label: '结案' },
    can('reopen') && { command: 'reopen', label: '取消结案' },
    { command: 'print', label: '打印' },
    canSeeCraft.value && { command: 'craft', label: '工艺单' },
    can('remove') && { command: 'remove', label: '删除', danger: true },
    can('void') && { command: 'void', label: '作废', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'reportIssue') reportIssueVisible.value = true
  else if (command === 'editPlan') planNodeVisible.value = true
  else if (command === 'cut') cutVisible.value = true
  else if (command === 'returnMaterial') openMatDoc('RETURN_IN')
  else if (command === 'print') void printRef.value?.print()
  else if (command === 'craft') void openCraft()
  else if (command === 'remove') void remove()
  else void act(command)
}

// ---------- 颜色 × 尺码 ----------
const matrixColors = computed<MatrixColor[]>(() => {
  const seen = new Map<string, MatrixColor>()
  for (const it of detail.value?.items ?? []) {
    if (!seen.has(it.colorId)) seen.set(it.colorId, { id: it.colorId, name: it.colorName, code: it.colorCode, hex: it.colorHex })
  }
  return [...seen.values()]
})
const matrixCells = computed<MatrixCell[]>(() => (detail.value?.items ?? []).map((it) => ({ id: it.id, colorId: it.colorId, sizeCode: it.sizeCode })))
const matrixQtys = computed(() => Object.fromEntries((detail.value?.items ?? []).map((it) => [it.id, it.qty])))
const lineSub = (itemId: string) => {
  if (!audited.value) return null
  const it = detail.value?.items.find((x) => x.id === itemId)
  return it ? `${it.cutQty != null ? `裁 ${it.cutQty} · ` : ''}回 ${it.returnedQty} · 次 ${it.defectQty} · 未 ${it.remainQty}` : null
}

/** 打印表：颜色 × 尺码 */
const qtyAt = (colorId: string, size: string) => detail.value?.items.find((i) => i.colorId === colorId && i.sizeCode === size)?.qty ?? 0
const colorTotal = (colorId: string) => detail.value?.items.filter((i) => i.colorId === colorId).reduce((s, i) => s + i.qty, 0) ?? 0
const sizeTotal = (size: string) => detail.value?.items.filter((i) => i.sizeCode === size).reduce((s, i) => s + i.qty, 0) ?? 0

// ---------- 其他显示 ----------
const issueBannerText = (i: ProductionDetail['openIssues'][number]) =>
  `${issueTypes.labelOf(i.issueType)}：${i.description}${i.newExpectDate ? `（预计交期 ${formatDate(i.newExpectDate).slice(5)}）` : ''}`
const nodeDateText = (n: ProductionNode) => {
  if (!n.actualStart) return '—'
  return `${formatDate(n.actualStart).slice(5)} ~ ${n.actualFinish ? formatDate(n.actualFinish).slice(5) : '进行中'}`
}
const nodeLastText = (n: ProductionNode) => (n.lastReportByName && n.lastReportTime ? `${n.lastReportByName} ${formatDate(n.lastReportTime).slice(5)} 报的` : '')
const partyText = (i: ProductionIssue) => (i.responsibleParty ? RESPONSIBLE_PARTY[i.responsibleParty] : '待定')

// 传给子组件的取数函数在 script 里定义（模板里写引用 import 的箭头函数 vue-tsc 会报错）
const loadNotices = () => productionApi.notices(id)
const handleNotice = (noticeId: string, remark: string | null) => productionApi.handleNotice(id, noticeId, remark)
const loadLogs = () => productionApi.logs(id)
const loadFiles = () => productionApi.attachments(id)
const saveFiles = (files: FileVO[]) => productionApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(PRODUCTION_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail?.factoryShortName ?? kindMeta.label" :back="{ label: kindMeta.label, to: kindMeta.path }">
      <template v-if="detail" #title>
        <StatusTag :tone="statusTag.tone">{{ statusTag.label }}</StatusTag>
        <span class="code dim">{{ detail.no }}</span>
      </template>
      <template v-if="detail" #actions>
        <span v-if="waitingText" class="dim waiting">{{ waitingText }}</span>
        <el-button v-if="can('edit')" @click="editVisible = true">编辑</el-button>
        <el-button v-if="can('submit')" type="primary" @click="act('submit')">提交</el-button>
        <el-button v-if="can('reject')" @click="act('reject')">驳回</el-button>
        <el-button v-if="can('audit')" type="primary" @click="act('audit')">审核</el-button>
        <el-button v-if="can('issue')" @click="openMatDoc('ISSUE_OUT')">领料</el-button>
        <el-button v-if="can('receive')" @click="receiveCreateVisible = true">登记回货</el-button>
        <el-button v-if="can('report')" type="primary" @click="openReport()">报进度</el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" :title="`这张${kindMeta.label}不存在，可能已经删了`">
      <el-button @click="router.push(kindMeta.path)">回{{ kindMeta.label }}列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <DocNoticesBanner :pending-count="detail.pendingNotices" :load="loadNotices" :handle="handleNotice" @handled="refresh" />
        <RejectNotice :status="detail.status" :version="detail.version" :load="loadLogs" />

        <div v-for="i in detail.openIssues" :key="i.id" class="issue-banner">
          <span>{{ issueBannerText(i) }}</span>
          <el-button size="small" @click="tab = 'issues'">去处理</el-button>
        </div>

        <div class="summary num">
          <router-link :to="`/styles/${detail.styleId}`" class="code link">{{ detail.styleCode }}</router-link>
          <span>{{ detail.styleName }}</span>
          <span class="dim">{{ kindMeta.short }} · {{ modes.labelOf(detail.processMode) }}</span>
          <span>{{ formatQty(detail.totalQty) }} 件</span>
          <span>
            交期 {{ formatDate(detail.expectDate ?? detail.dueDate) }}
            <del v-if="detail.expectDate" class="dim">{{ formatDate(detail.dueDate) }}</del>
          </span>
          <DaysLeftTag :days-left="detail.daysLeft" :risk="detail.risk" />
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="进度" name="progress">
            <BlankState v-if="!detail.nodes.length" title="审核下单后按交期往前倒排节点" />
            <ul v-else class="tl">
              <li
                v-for="n in detail.nodes"
                :key="n.id"
                :class="[`st-${n.state.toLowerCase()}`, { clickable: can('report') }]"
                @click="onNodeClick(n)"
              >
                <div class="tl-dot" />
                <div class="tl-card">
                  <div class="tl-head">
                    <strong>{{ n.nodeName }}</strong>
                    <span class="dim num small">计划 {{ formatDate(n.planStart).slice(5) }} ~ {{ formatDate(n.planFinish).slice(5) }}</span>
                    <span class="num small">实际 {{ nodeDateText(n) }}</span>
                    <span v-if="n.doneQty !== null" class="num small">完成 {{ formatQty(n.doneQty) }} 件</span>
                    <span class="gap" />
                    <span v-if="n.state === 'LATE'" class="danger small">{{ n.actualFinish ? `晚了 ${n.lateDays ?? 0} 天` : `延期 ${n.lateDays ?? 0} 天` }}</span>
                    <span v-else-if="n.state === 'WARN'" class="warn small">临期</span>
                    <el-icon v-else-if="n.state === 'DONE'" class="ok-mark"><CircleCheckFilled /></el-icon>
                    <StatusTag v-else :tone="NODE_STATE[n.state].tone">{{ NODE_STATE[n.state].label }}</StatusTag>
                  </div>
                  <div v-if="nodeLastText(n) || n.reportCount > 1" class="tl-sub dim">
                    {{ nodeLastText(n) }}
                    <button v-if="n.reportCount > 1" type="button" class="link-btn" @click.stop="openHistory(n)">汇报记录 {{ n.reportCount }} 次</button>
                  </div>
                  <p v-if="n.remark" class="tl-rmk">{{ n.remark }}</p>
                  <div v-if="n.photos.length" class="pics">
                    <a v-for="p in n.photos.slice(0, 4)" :key="p.id" :href="p.url" target="_blank" rel="noopener" @click.stop>
                      <ThumbImage :src="p.thumbUrl" alt="" />
                    </a>
                  </div>
                </div>
              </li>
            </ul>
          </el-tab-pane>

          <el-tab-pane label="明细" name="lines">
            <SkuMatrix :model-value="matrixQtys" :sizes="detail.sizes" :colors="matrixColors" :skus="matrixCells" readonly :sub="lineSub" />
          </el-tab-pane>

          <el-tab-pane :label="`领料 · 料账 ${detail.stockDocCount || ''}`" name="materials">
            <BlankState v-if="detail.issueStatus === null" title="资料包里没有标「自购」的料，不用领料" />
            <template v-else>
              <div v-if="matData?.ledgerStatus" class="ledger-line">
                <span>良品 <strong class="num">{{ matData.goodQty }}</strong> 件</span>
                <StatusTag :tone="LEDGER_STATUS[matData.ledgerStatus].tone">{{ LEDGER_STATUS[matData.ledgerStatus].label }}</StatusTag>
                <a v-if="matData.settleDoc" class="code link" @click="goSettleDoc">{{ matData.settleDoc.no }}</a>
                <span class="dim small">应耗 = 良品 × 用量 ×（1 + 损耗）；结余大于 0 是该退回来的</span>
                <el-button v-if="canSettle" size="small" type="primary" class="ledger-btn" @click="openMatDoc('CONSUME_OUT')">核销耗料</el-button>
              </div>
              <el-table :data="matData?.rows ?? []" row-key="materialSkuId" scrollbar-always-on empty-text="资料包里没有自购的料">
                <el-table-column label="物料" min-width="200">
                  <template #default="{ row }">
                    <span class="code">{{ row.skuCode }}</span>
                    <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
                  </template>
                </el-table-column>
                <el-table-column label="单位" width="60">
                  <template #default="{ row }">{{ units.labelOf(row.stockUnit) }}</template>
                </el-table-column>
                <el-table-column label="应领" width="100" class-name="num" align="right">
                  <template #default="{ row }">{{ formatQty(row.needQty) }}</template>
                </el-table-column>
                <el-table-column label="已领" width="100" class-name="num" align="right">
                  <template #default="{ row }">{{ formatQty(row.issuedQty) }}</template>
                </el-table-column>
                <el-table-column label="还差" width="100" class-name="num" align="right">
                  <template #default="{ row }">
                    <span v-if="row.remainQty <= 0" class="ok-text">领齐</span>
                    <strong v-else class="warn">{{ formatQty(row.remainQty) }}</strong>
                  </template>
                </el-table-column>
                <el-table-column label="物料仓库存" width="110" class-name="num" align="right">
                  <template #default="{ row }">{{ formatQty(row.stockQty) }}</template>
                </el-table-column>
                <el-table-column label="应耗" width="100" class-name="num" align="right">
                  <template #default="{ row }">{{ formatQty(row.consumeQty) }}</template>
                </el-table-column>
                <el-table-column label="实耗" width="100" class-name="num" align="right">
                  <template #default="{ row }">
                    <span v-if="row.settledQty == null" class="dim">—</span>
                    <strong v-else-if="row.settledQty > row.consumeQty" class="warn" :title="`超耗 ${formatQty(row.settledQty - row.consumeQty)}`">{{ formatQty(row.settledQty) }}</strong>
                    <template v-else>{{ formatQty(row.settledQty) }}</template>
                  </template>
                </el-table-column>
                <el-table-column label="结余" width="120" class-name="num" align="right">
                  <template #default="{ row }">
                    <span v-if="row.balanceQty > 0" class="warn">该退 {{ formatQty(row.balanceQty) }}</span>
                    <span v-else-if="row.balanceQty < 0" class="dim">多用 {{ formatQty(-row.balanceQty) }}</span>
                    <span v-else class="dim">0</span>
                  </template>
                </el-table-column>
              </el-table>

              <h4 class="sub-h">领料、退料、耗料核销单</h4>
              <!-- 日期、状态放得下，列多了横滚（走查 T-7d453c：日期截成"2026-11-..."、状态被切） -->
              <el-table :data="matData?.docs ?? []" row-key="id" row-class-name="clickable" scrollbar-always-on empty-text="还没有领料、退料、核销单" @row-click="goStockDoc">
                <el-table-column label="单号" prop="no" width="150" class-name="code" fixed="left" />
                <el-table-column label="类型" width="100">
                  <template #default="{ row }">{{ INV_DOC_TYPE[(row as ProductionStockDoc).docType].label }}</template>
                </el-table-column>
                <el-table-column label="仓库" min-width="180">
                  <template #default="{ row }">{{ row.fromWarehouseName ?? '—' }} → {{ row.toWarehouseName ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="物料" width="70" class-name="num" align="right">
                  <template #default="{ row }">{{ row.itemCount }} 种</template>
                </el-table-column>
                <el-table-column label="日期" width="116" class-name="num">
                  <template #default="{ row }">{{ formatDate(row.docDate) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="120" fixed="right">
                  <template #default="{ row }">
                    <StatusTag :tone="INV_DOC_STATUS[(row as ProductionStockDoc).status].tone">
                      {{ INV_DOC_STATUS[(row as ProductionStockDoc).status].label }}
                    </StatusTag>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-tab-pane>

          <el-tab-pane :label="`异常 ${detail.issueCount || ''}`" name="issues">
            <el-table :data="issues" row-key="id" empty-text="没有上报过异常">
              <el-table-column label="单号" prop="no" width="140" class-name="code" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">{{ issueTypes.labelOf(row.issueType) }}</template>
              </el-table-column>
              <el-table-column label="描述" min-width="220">
                <template #default="{ row }">
                  <div>{{ row.description }}</div>
                  <div v-if="(row as ProductionIssue).photos.length" class="pics">
                    <a v-for="p in (row as ProductionIssue).photos" :key="p.id" :href="p.url" target="_blank" rel="noopener">
                      <ThumbImage :src="p.thumbUrl" alt="" />
                    </a>
                  </div>
                  <div class="dim small">{{ row.createByName ?? '—' }} {{ formatDateTime(row.createTime) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="交期" width="110" class-name="num">
                <template #default="{ row }">
                  <span v-if="row.newExpectDate" class="danger">改到 {{ formatDate(row.newExpectDate) }}</span>
                  <span v-else class="dim">不影响</span>
                </template>
              </el-table-column>
              <el-table-column label="责任方" width="76">
                <template #default="{ row }">{{ partyText(row as ProductionIssue) }}</template>
              </el-table-column>
              <el-table-column label="处理措施" min-width="150">
                <template #default="{ row }">{{ row.measure || '—' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <StatusTag :tone="ISSUE_STATUS[(row as ProductionIssue).status].tone">{{ ISSUE_STATUS[(row as ProductionIssue).status].label }}</StatusTag>
                </template>
              </el-table-column>
              <el-table-column label="" width="130" align="right">
                <template #default="{ row }">
                  <el-button v-if="(row as ProductionIssue).actions.includes('start')" link size="small" @click="startIssue(row as ProductionIssue)">开始处理</el-button>
                  <el-button v-if="(row as ProductionIssue).actions.includes('close')" link type="primary" size="small" @click="openCloseIssue(row as ProductionIssue)">关闭</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`回货 ${detail.receiptCount || ''}`" name="receipts">
            <el-table :data="receipts" row-key="id" row-class-name="clickable" scrollbar-always-on empty-text="还没有登记回货" @row-click="openReceipt">
              <el-table-column label="单号" prop="no" width="150" class-name="code" fixed="left" />
              <el-table-column label="日期" width="116" class-name="num">
                <template #default="{ row }">{{ formatDate(row.receiptDate) }}</template>
              </el-table-column>
              <el-table-column label="收货仓" min-width="120">
                <template #default="{ row }">{{ row.warehouseName ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="回货" width="90" class-name="num" align="right">
                <template #default="{ row }">{{ formatQty(row.qty) }}</template>
              </el-table-column>
              <el-table-column label="次品" width="80" class-name="num" align="right">
                <template #default="{ row }">
                  <span :class="{ danger: row.defectQty > 0 }">{{ formatQty(row.defectQty) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="物流单号" width="140" class-name="code">
                <template #default="{ row }">{{ row.logisticsNo || '—' }}</template>
              </el-table-column>
              <el-table-column label="登记" min-width="240" class-name="num">
                <template #default="{ row }">{{ row.createByName ?? '—' }} {{ formatDateTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column v-if="canSeeFg" label="成品仓" width="160">
                <template #default="{ row }">
                  <template v-if="stocked.get(row.id)?.length">
                    <router-link
                      v-for="fg in stocked.get(row.id)"
                      :key="fg.docId"
                      :to="`/fg-in/${fg.docId}`"
                      class="code link fg-link"
                      @click.stop
                    >{{ fg.no }}<template v-if="fg.status === 'DRAFT'">（草稿）</template></router-link>
                  </template>
                  <span v-else-if="row.status === 'APPROVED'" class="dim">待入库</span>
                  <span v-else class="dim">—</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="120" fixed="right">
                <template #default="{ row }">
                  <StatusTag :tone="RECEIPT_STATUS[(row as ProductionReceipt).status].tone">{{ RECEIPT_STATUS[(row as ProductionReceipt).status].label }}</StatusTag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logRef" :load="loadLogs" :actions="PRODUCTION_LOG_ACTION" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="doc-aside">
        <img v-if="detail.image" :src="detail.image.url" alt="" class="style-img" />

        <section class="panel">
          <div class="panel-head">单据信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>主体</dt>
              <dd>{{ detail.companyName }}</dd>
              <dt>需求订单</dt>
              <dd><router-link :to="`/orders/${detail.orderId}`" class="code link">{{ detail.orderNo }}</router-link></dd>
              <dt>资料包</dt>
              <dd>生产 V{{ detail.techPackVersion }}</dd>
              <dt>加工厂</dt>
              <dd>
                {{ detail.factoryName }}
                <div v-if="detail.factoryStatus !== 'ACTIVE'" class="danger small">现在不是合作中，提交、审核会被拦</div>
              </dd>
              <dt>加工方式</dt>
              <dd>{{ modes.labelOf(detail.processMode) }}</dd>
              <template v-if="canSeeProductionPrice"><dt>{{ kindMeta.priceLabel }}</dt>
              <dd class="num">
                {{ formatPrice(detail.unitPrice) }}
                <div v-if="priceChanged" class="warn small">改价：{{ detail.priceChangeReason ?? '—' }}（档案 {{ formatPrice(detail.archivePrice) }}）</div>
                <!-- 审核后新价记进了档案，和档案一样了，原因照样留着（走查 T-7d453c：原来审完就看不到为什么改价） -->
                <div v-else-if="detail.priceChangeReason" class="dim small">改价：{{ detail.priceChangeReason }}</div>
              </dd>
              <dt>金额</dt>
              <dd class="num">{{ formatMoney(detail.totalAmount) }}</dd></template>
              <dt>税率</dt>
              <dd class="num">{{ detail.taxPct }}%（{{ detail.taxIncluded ? '含税' : '不含税' }}）</dd>
              <dt>下单日期</dt>
              <dd class="num">{{ formatDate(detail.orderDate) }}</dd>
              <dt>交期</dt>
              <dd class="num">
                {{ formatDate(detail.dueDate) }}<template v-if="detail.expectDate"> → {{ formatDate(detail.expectDate) }}</template>
              </dd>
              <dt>生产跟单</dt>
              <dd>{{ detail.merchName ?? '—' }}</dd>
              <dt>结算方式</dt>
              <dd>{{ settles.labelOf(detail.settleMethod) }}</dd>
              <template v-if="audited">
                <dt>当前节点</dt>
                <dd>{{ detail.currentNodeName ?? '都完成了' }}</dd>
                <dt>齐料</dt>
                <dd>
                  <StatusTag v-if="kitting" :tone="KITTING_STATUS[kitting.status].tone">{{ kitting.label }}</StatusTag>
                  <span v-else class="dim">—</span>
                </dd>
                <dt>领料</dt>
                <dd>
                  <StatusTag v-if="detail.issueStatus" :tone="MATERIAL_ISSUE_STATUS[detail.issueStatus].tone">
                    {{ MATERIAL_ISSUE_STATUS[detail.issueStatus].label }}
                  </StatusTag>
                  <span v-else class="dim">不用领料</span>
                </dd>
                <dt>回货 / 次品</dt>
                <dd class="num">{{ formatQty(detail.returnedQty) }} / {{ formatQty(detail.defectQty) }}</dd>
              </template>
              <dt>条款</dt>
              <dd class="pre-wrap">{{ detail.terms || '—' }}</dd>
              <dt>备注</dt>
              <dd>{{ detail.remark || '—' }}</dd>
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
              <template v-if="detail.auditTime">
                <dt>审核</dt>
                <dd>{{ detail.auditByName ?? '—' }} · {{ formatDateTime(detail.auditTime) }}</dd>
              </template>
            </dl>
          </div>
        </section>

        <ApprovalPanel :approval="detail.approval" :status="detail.status" />

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <!-- 换附件要 production:order:edit（后端兜底 403）：仓管这类只能看的不出"上传附件"（走查 T-7d453c） -->
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="detail.status === 'VOIDED' || !user.hasPerm('production:order:edit')" />
          </div>
        </section>
      </aside>
    </div>

    <template v-if="detail">
      <ProdEditDialog v-model="editVisible" :doc="detail" @saved="refresh" />
      <ProdNodeDialogs
        v-model:report-visible="reportNodeVisible"
        v-model:plan-visible="planNodeVisible"
        :order-id="id"
        :company-id="detail.companyId"
        :total-qty="detail.totalQty"
        :nodes="detail.nodes"
        :plan-revision="detail.planRevision"
        :initial-node-id="targetNodeId"
        @updated="refresh"
      />
      <ProdIssueDialogs
        v-model:report-visible="reportIssueVisible"
        v-model:close-visible="closeIssueVisible"
        :order-id="id"
        :company-id="detail.companyId"
        :base-due-date="detail.expectDate ?? detail.dueDate"
        :closing-issue="closingIssue"
        @updated="refresh"
      />
      <ProdCutDialog v-model="cutVisible" :doc="detail" @updated="refresh" />
      <ProdReceiveDialog
        v-model:create-visible="receiveCreateVisible"
        v-model:detail-visible="receiveDetailVisible"
        :doc="detail"
        :viewing-receipt="viewingReceipt"
        @updated="refresh"
      />
      <SourceInvDocDialog v-model="invDocVisible" source-kind="PRODUCTION" :source-id="id" :doc-type="invDocType" @created="onInvCreated" />

      <el-dialog v-model="historyVisible" :title="`${historyNode?.nodeName ?? ''}的汇报记录`" width="560px">
        <ul v-loading="historyLoading" class="hist">
          <li v-for="h in historyRows" :key="h.id">
            <div>
              <strong class="hist-act">{{ h.action === 'FINISH' ? '报完成' : '报开始' }}</strong>
              <span class="num small">
                {{ h.actualStart ? formatDate(h.actualStart) : '' }}<template v-if="h.actualFinish"> ~ {{ formatDate(h.actualFinish) }}</template>
                <template v-if="h.doneQty !== null"> · {{ formatQty(h.doneQty) }} 件</template>
              </span>
              <span class="dim small"> · {{ h.createByName ?? '—' }} {{ formatDateTime(h.createTime) }}</span>
            </div>
            <div v-if="h.remark" class="small">{{ h.remark }}</div>
            <div v-if="h.photos.length" class="pics">
              <a v-for="p in h.photos" :key="p.id" :href="p.url" target="_blank" rel="noopener"><ThumbImage :src="p.thumbUrl" alt="" /></a>
            </div>
          </li>
        </ul>
      </el-dialog>

      <el-dialog v-model="craftVisible" title="工艺单" width="520px">
        <div v-loading="craftLoading">
          <p class="small dim">资料包生产 V{{ detail.techPackVersion }}上传的文件，下载以后发给工厂。</p>
          <p v-if="!craftLoading && !craftFiles.length" class="dim">这一版资料包没有传工艺单：到款式的资料包里传</p>
          <ul v-else class="craft-files">
            <li v-for="f in craftFiles" :key="f.id">
              <span class="dim small">{{ craftGroupLabel(f) }}</span>
              <span class="craft-name" :title="f.name">{{ f.name }}</span>
              <el-button v-if="isExcel(f.name)" link type="primary" size="small" @click="previewCraft(f)">预览</el-button>
              <a :href="f.url" :download="f.name" class="craft-dl">下载</a>
            </li>
          </ul>
        </div>
        <template #footer>
          <el-button @click="craftVisible = false">关闭</el-button>
        </template>
      </el-dialog>
      <ExcelPreviewDialog v-if="previewFile" v-model="previewing" :title="previewFile.name" :load="loadCraftPreview" :download-url="previewFile.url" />

      <PrintSheet ref="printRef" :title="kindMeta.label" :no="detail.no">
        <template #meta>
          <span>加工厂：{{ detail.factoryName }}</span>
          <span v-if="canSeeSupplierContact">联系人：{{ detail.factoryContact ?? '—' }} {{ detail.factoryPhone ?? '' }}</span>
          <span>款式：{{ detail.styleCode }} {{ detail.styleName }}</span>
          <span>加工方式：{{ modes.labelOf(detail.processMode) }}</span>
          <span>需求订单：{{ detail.orderNo }}</span>
          <span>资料包：生产 V{{ detail.techPackVersion }}</span>
          <span>下单日期：{{ formatDate(detail.orderDate) }}</span>
          <span>交期：{{ formatDate(detail.expectDate ?? detail.dueDate) }}</span>
          <span>生产跟单：{{ detail.merchName ?? '—' }}</span>
          <span v-if="canSeeProductionPrice">{{ kindMeta.priceLabel }}：{{ formatPrice(detail.unitPrice) }} 元/件</span>
          <span>数量：{{ formatQty(detail.totalQty) }} 件</span>
          <span v-if="canSeeProductionPrice">
            金额：{{ formatMoney(detail.totalAmount) }} 元（{{ settles.labelOf(detail.settleMethod) }} · 税率 {{ detail.taxPct }}%
            {{ detail.taxIncluded ? '含税' : '不含税' }}）
          </span>
        </template>
        <table>
          <thead>
            <tr>
              <th>颜色</th>
              <th v-for="s in detail.sizes" :key="s" class="r">{{ s }}</th>
              <th class="r">合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in matrixColors" :key="c.id">
              <td>{{ c.name }}</td>
              <td v-for="s in detail.sizes" :key="s" class="r">{{ qtyAt(c.id, s) || '' }}</td>
              <td class="r">{{ formatQty(colorTotal(c.id)) }}</td>
            </tr>
            <tr>
              <td>合计</td>
              <td v-for="s in detail.sizes" :key="s" class="r">{{ formatQty(sizeTotal(s)) }}</td>
              <td class="r">{{ formatQty(detail.totalQty) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="detail.terms" class="pre-wrap">条款：{{ detail.terms }}</p>
        <p v-if="detail.remark">备注：{{ detail.remark }}</p>
        <template #sign>
          <span>制单：{{ detail.createByName ?? '' }}</span>
          <span>审核：{{ detail.auditByName ?? '' }}</span>
          <span>加工厂确认（签字盖章）</span>
        </template>
      </PrintSheet>
    </template>
  </div>
</template>

<style scoped>
.hist-act {
  margin-right: 8px;
}

.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1200px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.waiting {
  margin-right: 4px;
  font-size: 12.5px;
}

.issue-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 14px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
  border-radius: var(--lm-radius);
}

.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 16px;
  margin: 4px 0 8px;
  font-size: 13px;
}

.tl {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 0 4px 12px;
  margin: 0;
  list-style: none;
  border-left: 2px solid var(--lm-line);
}

.tl li {
  position: relative;
  padding-left: 14px;
}

.tl li.clickable {
  cursor: pointer;
}

.tl-dot {
  position: absolute;
  top: 14px;
  left: -7px;
  width: 12px;
  height: 12px;
  background: var(--lm-paper);
  border: 2px solid var(--lm-line-strong);
  border-radius: 99px;
}

.tl li.st-done .tl-dot {
  background: var(--lm-done-text);
  border-color: var(--lm-done-text);
}

.tl li.st-late .tl-dot {
  background: var(--lm-danger-text);
  border-color: var(--lm-danger-text);
}

.tl li.st-warn .tl-dot {
  background: var(--lm-pending-text);
  border-color: var(--lm-pending-text);
}

.tl li.st-running .tl-dot {
  background: var(--lm-accent);
  border-color: var(--lm-accent);
}

.tl-card {
  padding: 10px 14px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.tl li.clickable .tl-card:hover {
  border-color: var(--lm-line-strong);
}

.tl-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}

.tl-sub {
  margin-top: 4px;
  font-size: 12px;
}

.tl-rmk {
  margin: 6px 0 0;
  font-size: 12.5px;
}

.pics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.pics img {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.ok-mark,
.ok-text {
  color: var(--lm-done-text);
}

.sub-h {
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.gap {
  flex: 1;
}

.small {
  font-size: 11.5px;
}

.danger {
  color: var(--lm-danger-text);
}

.warn {
  color: var(--lm-pending-text);
}

.link,
.link-btn {
  padding: 0;
  margin-left: 6px;
  font: inherit;
  color: var(--lm-accent);
  text-decoration: none;
  cursor: pointer;
  background: none;
  border: none;
}

.link:hover,
.link-btn:hover {
  text-decoration: underline;
}

.style-img {
  width: 100%;
  max-height: 260px;
  object-fit: contain;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-panel);
}

.pre-wrap {
  white-space: pre-wrap;
}

.hist {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 40px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.hist li + li {
  padding-top: 10px;
  border-top: 1px solid var(--lm-line-soft);
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
.craft-files {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
}

.craft-files li {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 13px;
}

.craft-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.craft-dl {
  font-size: 12.5px;
  color: var(--lm-accent);
  text-decoration: none;
}

.fg-link + .fg-link {
  margin-left: 8px;
}
.ledger-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 8px;
}

.ledger-line .link {
  color: var(--lm-accent);
  cursor: pointer;
}

.ledger-btn {
  margin-left: auto;
}
</style>
