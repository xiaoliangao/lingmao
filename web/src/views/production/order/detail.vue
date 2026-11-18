<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { productionApi } from '@/api/production'
import { kittingApi } from '@/api/purchase'
import { techPackApi } from '@/api/techpack'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix, { type MatrixCell, type MatrixColor } from '@/components/SkuMatrix.vue'
import StatusTag from '@/components/StatusTag.vue'
import ApprovalPanel from '@/components/doc/ApprovalPanel.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import DocNoticesBanner from '@/components/doc/DocNoticesBanner.vue'
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
  PRODUCTION_PARTIAL,
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
import ProdReceiveDialog from './ProdReceiveDialog.vue'

/**
 * 外发加工单 / 成品采购单详情（/production/:id、/fp-purchases/:id，docs/api/05 §1.2、§1.4、§4）。两种单共用。
 * - 地址和单子的种类对不上（回货单、异常的 docRoute 一律先去 /production/:id）：换到对的地址，带上 ?tab=，这个页签关掉
 * - 页签 ?tab=progress|lines|materials|issues|receipts，keep-alive 下再从别处点进来也认（只认本页地址）
 * - 页头：编辑、提交、审核 / 驳回（不是我审时写"现在等××审"）、领料、登记回货、报进度；
 *   上报异常、改节点计划、退料、撤回、反审核、结案、取消结案、打印、导出工艺单、删除、作废在"更多"里
 * - 状态动作走 runDocAction：1002 重新取详情
 * - 领料、退料、异常、回货、齐料各自取，一个取不到不影响别的
 * - 打印：页面里的 A4 打印版（PrintSheet），带颜色 × 尺码数量表和签字栏；工艺单是后端出的 PDF，另外导出
 */
type TabName = 'progress' | 'lines' | 'materials' | 'issues' | 'receipts'
const TABS: TabName[] = ['progress', 'lines', 'materials', 'issues', 'receipts']
const pickTab = (v: unknown): TabName | null => (TABS.includes(v as TabName) ? (v as TabName) : null)

const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()

const id = Number(route.params.id)
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
const targetNodeId = ref<number | null>(null)
const planNodeVisible = ref(false)
const reportIssueVisible = ref(false)
const closeIssueVisible = ref(false)
const closingIssue = ref<ProductionIssue | null>(null)
const receiveCreateVisible = ref(false)
const receiveDetailVisible = ref(false)
const viewingReceipt = ref<ProductionReceipt | null>(null)
const invDocVisible = ref(false)
const invDocType = ref<'ISSUE_OUT' | 'RETURN_IN'>('ISSUE_OUT')

const can = (a: ProductionAction) => detail.value?.actions.includes(a) ?? false

const kindMeta = computed(() => PRODUCTION_KIND[detail.value?.kind ?? (path.startsWith(PRODUCTION_KIND.BUY.path) ? 'BUY' : 'OUTSOURCE')])
const statusTag = computed(() => {
  const d = detail.value
  if (!d) return PRODUCTION_STATUS.DRAFT
  return d.status === 'RUNNING' && d.returnedQty > 0 ? PRODUCTION_PARTIAL : PRODUCTION_STATUS[d.status]
})
/** 审核以后：明细格子下面写回货，侧栏多当前节点、齐料、领料、回货 */
const audited = computed(() => !!detail.value && !['DRAFT', 'PENDING', 'VOIDED'].includes(detail.value.status))
const priceChanged = computed(() => {
  const d = detail.value
  return !!d && d.archivePrice !== null && Math.abs(d.unitPrice - d.archivePrice) > 0.00005
})
/** 不是我审、在等别人审：页头写一句 */
const waitingText = computed(() => {
  const a = detail.value?.approval
  return detail.value?.status === 'PENDING' && a?.waitingFor && !a.canPass ? `现在等${a.waitingFor}审` : ''
})

// ---------- 取数 ----------
async function load() {
  try {
    const d = await productionApi.get(id)
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
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

/** 页签和侧栏的数：各取各的，一个出错（request.ts 已经提示）不影响别的 */
async function loadSubs(d: ProductionDetail) {
  await Promise.allSettled([
    productionApi.materials(id).then((m) => (matData.value = m)),
    productionApi.issues(id).then((list) => (issues.value = list)),
    productionApi.receipts(id).then((list) => (receipts.value = list)),
    loadKitting(d),
  ])
}

async function loadKitting(d: ProductionDetail) {
  kitting.value = null
  if (!['APPROVED', 'RUNNING', 'DONE'].includes(d.status)) return
  try {
    kitting.value = (await kittingApi.byOrders([d.orderId]))[0] ?? null
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
  const ok = await runDocAction(action, d.no, d.version, (a, body, o) => productionApi.act(id, a, body, o), { reload: refresh })
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

function openMatDoc(docType: 'ISSUE_OUT' | 'RETURN_IN') {
  invDocType.value = docType
  invDocVisible.value = true
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
async function openHistory(node: ProductionNode) {
  historyNode.value = node
  historyRows.value = []
  historyVisible.value = true
  historyLoading.value = true
  try {
    historyRows.value = await productionApi.nodeReports(id, node.id)
  } finally {
    historyLoading.value = false
  }
}

// ---------- 导出工艺单 ----------
const canExportCraft = computed(() => user.hasPerm('techpack:pack:list'))
const craftVisible = ref(false)
const craftAppendix = ref(true)
const exportingCraft = ref(false)
async function exportCraft() {
  const d = detail.value
  if (!d) return
  exportingCraft.value = true
  try {
    await techPackApi.pdf(d.techPackId, { price: false, appendix: craftAppendix.value }, `${d.styleCode}-${d.styleName}-工艺单-V${d.techPackVersion}.pdf`)
    craftVisible.value = false
  } finally {
    exportingCraft.value = false
  }
}

// ---------- 更多 ----------
type MoreCommand = 'reportIssue' | 'editPlan' | 'returnMaterial' | 'withdraw' | 'unaudit' | 'close' | 'reopen' | 'print' | 'craft' | 'remove' | 'void'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('reportIssue') && { command: 'reportIssue', label: '上报异常' },
    can('editPlan') && { command: 'editPlan', label: '改节点计划' },
    can('returnMaterial') && { command: 'returnMaterial', label: '退料' },
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('unaudit') && { command: 'unaudit', label: '反审核' },
    can('close') && { command: 'close', label: '结案' },
    can('reopen') && { command: 'reopen', label: '取消结案' },
    { command: 'print', label: '打印' },
    canExportCraft.value && { command: 'craft', label: '导出工艺单' },
    can('remove') && { command: 'remove', label: '删除', danger: true },
    can('void') && { command: 'void', label: '作废', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'reportIssue') reportIssueVisible.value = true
  else if (command === 'editPlan') planNodeVisible.value = true
  else if (command === 'returnMaterial') openMatDoc('RETURN_IN')
  else if (command === 'print') void printRef.value?.print()
  else if (command === 'craft') craftVisible.value = true
  else if (command === 'remove') void remove()
  else void act(command)
}

// ---------- 颜色 × 尺码 ----------
const matrixColors = computed<MatrixColor[]>(() => {
  const seen = new Map<number, MatrixColor>()
  for (const it of detail.value?.items ?? []) {
    if (!seen.has(it.colorId)) seen.set(it.colorId, { id: it.colorId, name: it.colorName, code: it.colorCode, hex: it.colorHex })
  }
  return [...seen.values()]
})
const matrixCells = computed<MatrixCell[]>(() => (detail.value?.items ?? []).map((it) => ({ id: it.id, colorId: it.colorId, sizeCode: it.sizeCode })))
const matrixQtys = computed(() => Object.fromEntries((detail.value?.items ?? []).map((it) => [it.id, it.qty])))
const lineSub = (itemId: number) => {
  if (!audited.value) return null
  const it = detail.value?.items.find((x) => x.id === itemId)
  return it ? `回 ${it.returnedQty} · 次 ${it.defectQty} · 未 ${it.remainQty}` : null
}

/** 打印表：颜色 × 尺码 */
const qtyAt = (colorId: number, size: string) => detail.value?.items.find((i) => i.colorId === colorId && i.sizeCode === size)?.qty ?? 0
const colorTotal = (colorId: number) => detail.value?.items.filter((i) => i.colorId === colorId).reduce((s, i) => s + i.qty, 0) ?? 0
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
const handleNotice = (noticeId: number, remark: string | null) => productionApi.handleNotice(id, noticeId, remark)
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
                      <img :src="p.thumbUrl ?? p.url" alt="" />
                    </a>
                  </div>
                </div>
              </li>
            </ul>
          </el-tab-pane>

          <el-tab-pane label="明细" name="lines">
            <SkuMatrix :model-value="matrixQtys" :sizes="detail.sizes" :colors="matrixColors" :skus="matrixCells" readonly :sub="lineSub" />
          </el-tab-pane>

          <el-tab-pane :label="`领料 ${detail.stockDocCount || ''}`" name="materials">
            <BlankState v-if="detail.issueStatus === null" title="资料包里没有标「我方采购」的料，不用领料" />
            <template v-else>
              <el-table :data="matData?.rows ?? []" row-key="materialSkuId" empty-text="资料包里没有我方采购的料">
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
              </el-table>

              <h4 class="sub-h">领料、退料单</h4>
              <el-table :data="matData?.docs ?? []" row-key="id" row-class-name="clickable" empty-text="还没有领料、退料单" @row-click="goStockDoc">
                <el-table-column label="单号" prop="no" width="150" class-name="code" />
                <el-table-column label="类型" width="100">
                  <template #default="{ row }">{{ INV_DOC_TYPE[(row as ProductionStockDoc).docType].label }}</template>
                </el-table-column>
                <el-table-column label="仓库" min-width="180">
                  <template #default="{ row }">{{ row.fromWarehouseName ?? '—' }} → {{ row.toWarehouseName ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="物料" width="70" class-name="num" align="right">
                  <template #default="{ row }">{{ row.itemCount }} 种</template>
                </el-table-column>
                <el-table-column label="日期" width="100" class-name="num">
                  <template #default="{ row }">{{ formatDate(row.docDate) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="96">
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
                      <img :src="p.thumbUrl ?? p.url" alt="" />
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
            <el-table :data="receipts" row-key="id" row-class-name="clickable" empty-text="还没有登记回货" @row-click="openReceipt">
              <el-table-column label="单号" prop="no" width="150" class-name="code" />
              <el-table-column label="日期" width="100" class-name="num">
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
              <el-table-column label="登记" min-width="150" class-name="num">
                <template #default="{ row }">{{ row.createByName ?? '—' }} {{ formatDateTime(row.createTime) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <StatusTag :tone="RECEIPT_STATUS[(row as ProductionReceipt).status].tone">{{ RECEIPT_STATUS[(row as ProductionReceipt).status].label }}</StatusTag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="doc-aside">
        <img v-if="detail.image" :src="detail.image.url" alt="" class="style-img" />

        <section class="panel">
          <div class="panel-head">单据信息</div>
          <div class="panel-body">
            <dl class="doc-props">
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
              <dt>{{ kindMeta.priceLabel }}</dt>
              <dd class="num">
                {{ formatPrice(detail.unitPrice) }}
                <div v-if="priceChanged" class="warn small">改价：{{ detail.priceChangeReason ?? '—' }}（档案 {{ formatPrice(detail.archivePrice) }}）</div>
              </dd>
              <dt>金额</dt>
              <dd class="num">{{ formatMoney(detail.totalAmount) }}</dd>
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
            </dl>
          </div>
        </section>

        <ApprovalPanel :approval="detail.approval" :status="detail.status" />

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="detail.status === 'VOIDED'" />
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">操作记录</div>
          <div class="panel-body">
            <DocLogList ref="logRef" :load="loadLogs" :actions="PRODUCTION_LOG_ACTION" :statuses="LOG_STATUSES" />
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
        :base-due-date="detail.expectDate ?? detail.dueDate"
        :closing-issue="closingIssue"
        @updated="refresh"
      />
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
              <strong>{{ h.action === 'FINISH' ? '报完成' : '报开始' }}</strong>
              <span class="num small">
                {{ h.actualStart ? formatDate(h.actualStart) : '' }}<template v-if="h.actualFinish"> ~ {{ formatDate(h.actualFinish) }}</template>
                <template v-if="h.doneQty !== null"> · {{ formatQty(h.doneQty) }} 件</template>
              </span>
              <span class="dim small"> · {{ h.createByName ?? '—' }} {{ formatDateTime(h.createTime) }}</span>
            </div>
            <div v-if="h.remark" class="small">{{ h.remark }}</div>
            <div v-if="h.photos.length" class="pics">
              <a v-for="p in h.photos" :key="p.id" :href="p.url" target="_blank" rel="noopener"><img :src="p.thumbUrl ?? p.url" alt="" /></a>
            </div>
          </li>
        </ul>
      </el-dialog>

      <el-dialog v-model="craftVisible" title="导出工艺单" width="400px">
        <p class="small dim">生产 V{{ detail.techPackVersion }}，全部颜色，不含价格（发给工厂的）。</p>
        <el-checkbox v-model="craftAppendix">附工艺标准</el-checkbox>
        <template #footer>
          <el-button @click="craftVisible = false">取消</el-button>
          <el-button type="primary" :loading="exportingCraft" @click="exportCraft">导出</el-button>
        </template>
      </el-dialog>

      <PrintSheet ref="printRef" :title="kindMeta.label" :no="detail.no">
        <template #meta>
          <span>加工厂：{{ detail.factoryName }}</span>
          <span>联系人：{{ detail.factoryContact ?? '—' }} {{ detail.factoryPhone ?? '' }}</span>
          <span>款式：{{ detail.styleCode }} {{ detail.styleName }}</span>
          <span>加工方式：{{ modes.labelOf(detail.processMode) }}</span>
          <span>需求订单：{{ detail.orderNo }}</span>
          <span>资料包：生产 V{{ detail.techPackVersion }}</span>
          <span>下单日期：{{ formatDate(detail.orderDate) }}</span>
          <span>交期：{{ formatDate(detail.expectDate ?? detail.dueDate) }}</span>
          <span>生产跟单：{{ detail.merchName ?? '—' }}</span>
          <span>{{ kindMeta.priceLabel }}：{{ formatPrice(detail.unitPrice) }} 元/件</span>
          <span>数量：{{ formatQty(detail.totalQty) }} 件</span>
          <span>
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
</style>
