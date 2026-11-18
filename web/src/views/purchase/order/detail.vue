<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, onActivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { purchaseApi as purchaseApiBase } from '@/api/purchase'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ApprovalPanel from '@/components/doc/ApprovalPanel.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import DocNoticesBanner from '@/components/doc/DocNoticesBanner.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import PrintSheet from '@/components/doc/PrintSheet.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import { isItem, runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import type { DocAction } from '@/types/biz'
import { INV_DOC_STATUS, INV_DOC_TYPE } from '@/types/inventory'
import {
  PURCHASE_LOG_ACTION,
  PURCHASE_STATUS,
  PURCHASE_TYPE_LABEL,
  type PurchaseAction,
  type PurchaseDetail,
  type PurchaseItem,
  type PurchaseStockDoc,
} from '@/types/purchase'
import type { FileVO } from '@/types/system'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
const purchaseApi = bindCompanyApi(purchaseApiBase, () => detail.value?.companyId)

/**
 * 采购单详情（/purchases/:id，docs/api/03 §3.3、§4）。
 * - 页头：编辑、提交、审核、驳回、到货入库；退货、撤回、反审核、结案、取消结案、打印、删除、作废在"更多"里
 * - 审核：单价和档案不一样的行在明细里标出来，确认框写"有 n 行单价和价格档案不一样，审核后记进价格档案"
 * - 1002（别人刚改过）：重新取详情
 * - 打印：页面里的 A4 打印版（PrintSheet），只印单据本身
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

const id = routeId(route.params.id)
const path = route.path
const units = useDict('unit')
const settles = useDict('settle_method')

const detail = ref<PurchaseDetail | null>(null)
const notFound = ref(false)
const tab = ref<'items' | 'docs' | 'logs'>('items')
const logRef = ref<InstanceType<typeof DocLogList>>()
const printRef = ref<InstanceType<typeof PrintSheet>>()

const invDialogVisible = ref(false)
const invDocType = ref<'PURCHASE_IN' | 'PURCHASE_RETURN'>('PURCHASE_IN')

const can = (a: PurchaseAction) => detail.value?.actions.includes(a) ?? false

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  try {
    const d = await read.run(() => purchaseApi.get(id))
    if (!read.current()) return
    detail.value = d
    tabs.rename(path, `采购 ${d.no}`)
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
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

/** 单价和价格档案不一样（档案里有价）的行：审核时提醒，明细里标出来 */
const priceDiffers = (it: PurchaseItem) => it.archivePrice !== null && Math.abs(it.price - it.archivePrice) > 0.00005
const diffCount = computed(() => detail.value?.items.filter(priceDiffers).length ?? 0)
const lineClass = ({ row }: { row: PurchaseItem }) => (detail.value?.status === 'PENDING' && priceDiffers(row) ? 'price-diff' : '')
/** 到货进度：草稿、待审核、作废没有 */
const showProgress = computed(() => !!detail.value && !['DRAFT', 'PENDING', 'VOIDED'].includes(detail.value.status))

async function act(action: DocAction) {
  const d = detail.value
  if (!d) return
  const note = action === 'audit' && diffCount.value ? `有 ${diffCount.value} 行单价和价格档案不一样，审核后记进价格档案。` : undefined
  const ok = await runDocAction(action, d.no, d.version, (a, body, o) => purchaseApi.act(id, a, body, o), { note, reload: refresh })
  if (ok) await refresh()
}

async function remove() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, '草稿删除后找不回来；从算料单生成的行，算料单上的"已转采购"退回去，能重新生成。')
  await purchaseApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push('/purchases')
  tabs.close(path, '/purchases')
}

function openInvDoc(docType: 'PURCHASE_IN' | 'PURCHASE_RETURN') {
  invDocType.value = docType
  invDialogVisible.value = true
}

type MoreCommand = 'return' | 'withdraw' | 'unaudit' | 'close' | 'reopen' | 'print' | 'remove' | 'void'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('return') && { command: 'return', label: '退货' },
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('unaudit') && { command: 'unaudit', label: '反审核' },
    can('close') && { command: 'close', label: '结案' },
    can('reopen') && { command: 'reopen', label: '取消结案' },
    { command: 'print', label: '打印' },
    can('remove') && { command: 'remove', label: '删除', danger: true },
    can('void') && { command: 'void', label: '作废', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'return') openInvDoc('PURCHASE_RETURN')
  else if (command === 'print') void printRef.value?.print()
  else if (command === 'remove') void remove()
  else void act(command)
}

function goStockDoc(doc: PurchaseStockDoc) {
  const to = docRoute({ type: 'INV_DOC', kind: doc.kind, id: doc.id, parentId: null })
  if (to) void router.push(to)
}

// 传给子组件的取数函数在 script 里定义（模板里写引用 import 的箭头函数 vue-tsc 会报错）
const loadNotices = () => purchaseApi.notices(id)
const handleNotice = (noticeId: string, remark: string | null) => purchaseApi.handleNotice(id, noticeId, remark)
const loadLogs = () => purchaseApi.logs(id)
const loadFiles = () => purchaseApi.attachments(id)
const saveFiles = (files: FileVO[]) => purchaseApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(PURCHASE_STATUS).map(([k, v]) => [k, v.label]))
const planStyleText = computed(() => detail.value?.planStyles.map((s) => s.styleCode).join('、') ?? '')
const sourceText = (it: PurchaseItem) => [...it.orderNos, ...it.styleCodes].join(' · ')
const colorSpec = (it: PurchaseItem) => [it.colorName, it.spec].filter(Boolean).join(' ')
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? '采购单'" :back="{ label: '采购单', to: '/purchases' }">
      <template v-if="detail" #title>
        <StatusTag :tone="PURCHASE_STATUS[detail.status].tone">{{ PURCHASE_STATUS[detail.status].label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('edit')" @click="router.push(`/purchases/${id}/edit`)">编辑</el-button>
        <el-button v-if="can('submit')" type="primary" @click="act('submit')">提交</el-button>
        <el-button v-if="can('reject')" @click="act('reject')">驳回</el-button>
        <el-button v-if="can('audit')" type="primary" @click="act('audit')">审核</el-button>
        <el-button v-if="can('receive')" type="primary" @click="openInvDoc('PURCHASE_IN')">到货入库</el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张采购单不存在，可能已经删了">
      <el-button @click="router.push('/purchases')">回采购单列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <DocNoticesBanner :pending-count="detail.pendingNotices" :load="loadNotices" :handle="handleNotice" @handled="refresh" />

        <p class="sub">
          <span>{{ detail.supplierShortName }}<span v-if="detail.supplierStatus !== 'ACTIVE'" class="danger">（供应商已停用）</span></span>
          <span>{{ PURCHASE_TYPE_LABEL[detail.purchaseType] }}</span>
          <span class="num">{{ formatQty(detail.totalQty) }} · {{ formatMoney(detail.totalAmount) }}</span>
          <span v-if="showProgress" class="progress">到货 <QtyProgress :done="detail.receivedQty" :total="detail.totalQty" tone="done" /></span>
        </p>

        <el-tabs v-model="tab">
          <el-tab-pane :label="`明细 ${detail.items.length}`" name="items">
            <el-table :data="detail.items" row-key="id" :row-class-name="lineClass">
              <el-table-column label="#" prop="seq" width="56" class-name="num" />
              <el-table-column label="物料" min-width="200">
                <template #default="{ row }">
                  <span class="code">{{ row.skuCode }}</span>
                  <div class="dim small">{{ row.materialName }} {{ colorSpec(row as PurchaseItem) }}</div>
                  <div v-if="row.supplierItemNo || row.supplierColorNo" class="dim small">
                    货号 {{ row.supplierItemNo ?? '—' }} · 色号 {{ row.supplierColorNo ?? '—' }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="来源" min-width="210">
                <template #default="{ row }">
                  <router-link v-if="row.calcId" :to="`/mrp/${row.calcId}`" class="code">{{ row.calcNo }}</router-link>
                  <div v-if="sourceText(row as PurchaseItem)" class="dim small code">{{ sourceText(row as PurchaseItem) }}</div>
                  <span v-if="!row.calcId" class="dim">手建</span>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="104" class-name="num" align="right">
                <template #default="{ row }">{{ formatQty(row.qty) }} <span class="dim">{{ units.labelOf(row.purchaseUnit) }}</span></template>
              </el-table-column>
              <el-table-column label="单价" width="110" class-name="num" align="right">
                <template #default="{ row }">
                  {{ formatPrice(row.price) }}
                  <div v-if="priceDiffers(row as PurchaseItem)" class="amber small">档案 {{ formatPrice(row.archivePrice) }}</div>
                  <div v-if="row.priceChangeReason" class="dim small">{{ row.priceChangeReason }}</div>
                </template>
              </el-table-column>
              <el-table-column label="金额" width="100" class-name="num" align="right">
                <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="已入库" width="84" class-name="num" align="right">
                <template #default="{ row }">{{ formatQty(row.receivedQty) }}</template>
              </el-table-column>
              <el-table-column label="已退货" width="76" class-name="num" align="right">
                <template #default="{ row }">{{ row.returnedQty ? formatQty(row.returnedQty) : '—' }}</template>
              </el-table-column>
              <el-table-column label="未入库" width="84" class-name="num" align="right">
                <template #default="{ row }"><strong>{{ formatQty(row.pendingQty) }}</strong></template>
              </el-table-column>
              <el-table-column label="行交期" width="104" class-name="num">
                <template #default="{ row }">
                  {{ formatDate(row.dueDate) }}
                </template>
              </el-table-column>
              <el-table-column label="预计到货" min-width="140" class-name="num">
                <template #default="{ row }">
                  {{ row.expectDate ? formatDate(row.expectDate) : '—' }}
                  <div v-if="row.followNote" class="dim small">{{ row.followNote }}</div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`入库、退货 ${detail.stockDocs.length}`" name="docs">
            <el-table :data="detail.stockDocs" row-key="id" row-class-name="clickable" empty-text="还没有入库、退货：货到了点「到货入库」" @row-click="goStockDoc">
              <el-table-column label="单号" prop="no" min-width="150" class-name="code" />
              <el-table-column label="类型" width="100">
                <template #default="{ row }">{{ INV_DOC_TYPE[(row as PurchaseStockDoc).docType].label }}</template>
              </el-table-column>
              <el-table-column label="日期" width="100" class-name="num">
                <template #default="{ row }">{{ formatDate(row.docDate) }}</template>
              </el-table-column>
              <el-table-column label="仓库" prop="warehouseName" min-width="120" />
              <el-table-column label="数量" width="100" class-name="num" align="right">
                <template #default="{ row }">{{ formatQty(row.purchaseQty) }}</template>
              </el-table-column>
              <el-table-column label="缸号" min-width="120">
                <template #default="{ row }">{{ (row as PurchaseStockDoc).dyeLots.join('、') || '—' }}</template>
              </el-table-column>
              <el-table-column label="物流单号" min-width="120">
                <template #default="{ row }">{{ row.logisticsNo ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <StatusTag :tone="INV_DOC_STATUS[(row as PurchaseStockDoc).status].tone">{{ INV_DOC_STATUS[(row as PurchaseStockDoc).status].label }}</StatusTag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logRef" :load="loadLogs" :actions="PURCHASE_LOG_ACTION" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">采购单信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>主体</dt>
              <dd>{{ detail.companyName }}</dd>
              <dt>供应商</dt>
              <dd>{{ detail.supplierName }}</dd>
              <dt>来源算料</dt>
              <dd>
                <router-link v-if="detail.calcId" :to="`/mrp/${detail.calcId}`" class="code">{{ detail.calcNo }}</router-link>
                <template v-else>—</template>
              </dd>
              <dt>采购员</dt>
              <dd>{{ detail.buyerName }}</dd>
              <dt>下单</dt>
              <dd class="num">{{ formatDate(detail.orderDate) }}</dd>
              <dt>交货</dt>
              <dd class="num">{{ formatDate(detail.deliveryDate) }}</dd>
              <dt>交货地点</dt>
              <dd>{{ detail.warehouseName }}<span v-if="detail.warehouseType === 'FACTORY'" class="amber small">（直发工厂）</span></dd>
              <dt>结算</dt>
              <dd>{{ detail.settleMethod ? settles.labelOf(detail.settleMethod) : '—' }}</dd>
              <dt>税率</dt>
              <dd>
                {{ detail.taxPct === null ? '—' : `${detail.taxPct}%` }} · {{ detail.taxIncluded ? '含税' : '不含税' }} ·
                {{ detail.freightIncluded ? '含运费' : '不含运费' }}
              </dd>
              <template v-if="detail.planStyles.length">
                <dt>计划用于</dt>
                <dd class="code">{{ planStyleText }}</dd>
              </template>
              <dt>备注</dt>
              <dd>{{ detail.remark || '—' }}</dd>
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
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
      </aside>
    </div>

    <SourceInvDocDialog v-if="detail" v-model="invDialogVisible" source-kind="PURCHASE" :source-id="id" :doc-type="invDocType" @created="refresh" />

    <PrintSheet v-if="detail" ref="printRef" title="采购订单" :no="detail.no">
      <template #meta>
        <span>需方：{{ detail.companyName }}</span>
        <span>供方：{{ detail.supplierName }}</span>
        <span>采购员：{{ detail.buyerName }}</span>
        <span>供方联系人：{{ detail.supplierContact ?? '—' }} {{ detail.supplierPhone ?? '' }}</span>
        <span>下单日期：{{ formatDate(detail.orderDate) }}</span>
        <span>交货日期：{{ formatDate(detail.deliveryDate) }}</span>
        <span>交货地点：{{ detail.warehouseName }} {{ detail.warehouseAddress ?? '' }}</span>
        <span>
          结算：{{ detail.settleMethod ? settles.labelOf(detail.settleMethod) : '—' }} · 税率 {{ detail.taxPct === null ? '—' : `${detail.taxPct}%` }}
          {{ detail.taxIncluded ? '含税' : '不含税' }} · {{ detail.freightIncluded ? '含运费' : '不含运费' }}
        </span>
      </template>
      <table>
        <thead>
          <tr>
            <th>#</th>
            <th>物料编码</th>
            <th>名称、颜色、规格</th>
            <th>供方货号 / 色号</th>
            <th class="r">数量</th>
            <th>单位</th>
            <th class="r">单价</th>
            <th class="r">金额</th>
            <th>交期</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="it in detail.items" :key="it.id">
            <td>{{ it.seq }}</td>
            <td>{{ it.skuCode }}</td>
            <td>{{ it.materialName }} {{ colorSpec(it) }}</td>
            <td>{{ [it.supplierItemNo, it.supplierColorNo].filter(Boolean).join(' / ') || '—' }}</td>
            <td class="r">{{ formatQty(it.qty) }}</td>
            <td>{{ units.labelOf(it.purchaseUnit) }}</td>
            <td class="r">{{ formatPrice(it.price) }}</td>
            <td class="r">{{ formatMoney(it.amount) }}</td>
            <td>{{ formatDate(it.dueDate) }}</td>
          </tr>
          <tr>
            <td colspan="4">合计</td>
            <td class="r">{{ formatQty(detail.totalQty) }}</td>
            <td colspan="2" />
            <td class="r">{{ formatMoney(detail.totalAmount) }}</td>
            <td />
          </tr>
        </tbody>
      </table>
      <p v-if="detail.remark">备注：{{ detail.remark }}</p>
      <template #sign>
        <span>采购员签字</span>
        <span>审核</span>
        <span>供方确认（签字盖章）</span>
      </template>
    </PrintSheet>
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

.body {
  min-width: 0;
}

.sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 16px;
  margin: 0 0 6px;
  font-size: 13px;
}

.progress {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 160px;
}

.small {
  font-size: 11.5px;
}

.amber {
  color: var(--lm-pending-text);
}

.danger {
  color: var(--lm-danger-text);
}

:deep(.price-diff) td {
  background: var(--lm-pending-bg) !important;
}
</style>
