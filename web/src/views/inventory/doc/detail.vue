<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, onActivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { invDocApi as invDocApiBase } from '@/api/inventory'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import { runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import type { DocAction } from '@/types/biz'
import { INV_DOC_KIND_LABEL, INV_DOC_LOG_ACTION, INV_DOC_STATUS, INV_DOC_TYPE, type InvDocAction, type InvDocDetail, type InvDocItem } from '@/types/inventory'
import type { FileVO } from '@/types/system'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import InvDocFormDialog from './InvDocFormDialog.vue'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()
const invDocApi = bindCompanyApi(invDocApiBase, () => detail.value?.companyId)

/**
 * 物料入库单、出库单详情（/stock-in/:id、/stock-out/:id 共用，docs/api/04 §1.3、§1.5）。
 * - 地址和单据的 kind 对不上（手敲了 /stock-in/ 去看出库单）：换到对的地址
 * - 页头按 actions：编辑、审核过账、反审核、删除（没过过账的草稿）或作废（过过账的）。审核没过（1001 库存不足、1004 超上限）弹清单；1002 重新取
 * - 草稿且有出仓：多一列"出仓库存"，不够的标红
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

const id = routeId(route.params.id)
const path = route.path
const units = useDict('unit')
const inReasons = useDict('stock_in_reason')
const outReasons = useDict('stock_out_reason')

const detail = ref<InvDocDetail | null>(null)
const notFound = ref(false)
const editVisible = ref(false)
const logRef = ref<InstanceType<typeof DocLogList>>()

const can = (a: InvDocAction) => detail.value?.actions.includes(a) ?? false
const listPath = computed(() => (path.startsWith('/stock-out') ? '/stock-out' : '/stock-in'))
const kindLabel = computed(() => (detail.value ? INV_DOC_KIND_LABEL[detail.value.kind] : listPath.value === '/stock-out' ? '物料出库单' : '物料入库单'))
const showStock = computed(() => detail.value?.status === 'DRAFT' && !!detail.value.fromWarehouseId)
const isPurchase = computed(() => detail.value?.docType === 'PURCHASE_IN' || detail.value?.docType === 'PURCHASE_RETURN')
const short = (it: InvDocItem) => it.fromStockQty !== null && it.qty > it.fromStockQty

const reasonText = computed(() => {
  const d = detail.value
  if (!d?.reason) return '—'
  const dict = INV_DOC_TYPE[d.docType].reasonDict
  if (dict === 'stock_in_reason') return inReasons.labelOf(d.reason)
  if (dict === 'stock_out_reason') return outReasons.labelOf(d.reason)
  return d.reason
})

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  try {
    const d = await read.run(() => invDocApi.get(id))
    if (!read.current()) return
    const right = docRoute({ type: 'INV_DOC', kind: d.kind, id, parentId: null })
    if (right && right !== path) {
      // 地址和 kind 对不上：换到对的地址（那边会重新取），这个页签关掉
      await router.replace(right)
      tabs.close(path, right)
      return
    }
    detail.value = d
    tabs.rename(path, `${d.kind === 'OUT' ? '出库' : '入库'} ${d.no}`)
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

const NOTES: Partial<Record<DocAction, string>> = {
  audit: '审核后马上改库存。',
  unaudit: '这张单改的库存改回去，回到草稿。',
  void: '库存不受影响（反审核时已经改回去了），库存日志里的进出都留着。',
}

async function act(action: Extract<DocAction, 'audit' | 'unaudit' | 'void'>) {
  const d = detail.value
  if (!d) return
  // 反审核：出入库单没有"提交"，只说自己的那句，不叠"回到草稿，改完要重新提交审核"（走查 T-33e438）
  const own = action === 'unaudit' ? { confirmText: NOTES.unaudit } : { note: NOTES[action] }
  const ok = await runDocAction(action, d.no, d.version, (a, body, o) => invDocApi.act(id, a, body, o), { ...own, reload: refresh })
  if (ok) await refresh()
}

async function remove() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, '草稿删除后找不回来，库存不受影响。')
  await invDocApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push(listPath.value)
  tabs.close(path, listPath.value)
}

function goSource() {
  const src = detail.value?.source
  const to = src ? docRoute(src) : null
  if (to) void router.push(to)
}

// 传给子组件的取数函数在 script 里定义（模板里写引用 import 的箭头函数 vue-tsc 会报错）
const loadLogs = () => invDocApi.logs(id)
const loadFiles = () => invDocApi.attachments(id)
const saveFiles = (files: FileVO[]) => invDocApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(INV_DOC_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? kindLabel" :back="{ label: kindLabel, to: listPath }">
      <template v-if="detail" #title>
        <span class="dim type">{{ INV_DOC_TYPE[detail.docType].label }}</span>
        <StatusTag :tone="INV_DOC_STATUS[detail.status].tone">{{ INV_DOC_STATUS[detail.status].label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('remove')" @click="remove">删除</el-button>
        <el-button v-if="can('void')" @click="act('void')">作废</el-button>
        <el-button v-if="can('unaudit')" @click="act('unaudit')">反审核</el-button>
        <el-button v-if="can('edit')" @click="editVisible = true">编辑</el-button>
        <el-button v-if="can('audit')" type="primary" @click="act('audit')">审核过账</el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张单不存在，可能已经删了">
      <el-button @click="router.push(listPath)">回列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <p class="sub">
          <span v-if="detail.fromWarehouseName || detail.toWarehouseName">
            {{ detail.fromWarehouseName ?? '' }}<template v-if="detail.fromWarehouseName && detail.toWarehouseName"> → </template>{{ detail.toWarehouseName ?? '' }}
          </span>
          <span class="num">{{ formatQty(detail.totalQty) }}<template v-if="canSeeCost && detail.totalAmount != null"> · {{ formatMoney(detail.totalAmount) }}</template></span>
        </p>

        <el-table :data="detail.items" row-key="id">
          <el-table-column label="#" prop="seq" width="56" class-name="num" />
          <el-table-column label="物料" min-width="200">
            <template #default="{ row }">
              <span class="code">{{ row.skuCode }}</span>
              <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
            </template>
          </el-table-column>
          <el-table-column v-if="showStock" label="出仓库存" width="110" class-name="num" align="right">
            <template #default="{ row }">
              <span :class="{ danger: short(row as InvDocItem) }">{{ row.fromStockQty === null ? '—' : formatQty(row.fromStockQty) }}</span>
              <div v-if="short(row as InvDocItem)" class="danger small">库存不够</div>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="110" class-name="num" align="right">
            <template #default="{ row }">{{ formatQty(row.qty) }} <span class="dim">{{ units.labelOf(row.stockUnit) }}</span></template>
          </el-table-column>
          <el-table-column v-if="isPurchase" label="采购单位数量" width="120" class-name="num" align="right">
            <template #default="{ row }">
              <template v-if="row.purchaseQty !== null">{{ formatQty(row.purchaseQty) }} <span class="dim">{{ units.labelOf(row.purchaseUnit ?? '') }}</span></template>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="单价" width="90" class-name="num" align="right">
            <template #default="{ row }">{{ row.price === null ? '—' : formatPrice(row.price) }}</template>
          </el-table-column>
          <el-table-column v-if="canSeeCost" label="金额" width="100" class-name="num" align="right">
            <template #default="{ row }">{{ row.amount === null ? '—' : formatMoney(row.amount) }}</template>
          </el-table-column>
          <el-table-column label="缸号" width="110">
            <template #default="{ row }">{{ row.dyeLot || '—' }}</template>
          </el-table-column>
          <el-table-column label="卷数" width="64" class-name="num" align="right">
            <template #default="{ row }">{{ row.rolls ?? '—' }}</template>
          </el-table-column>
          <el-table-column v-if="detail.docType === 'ISSUE_OUT' || detail.docType === 'CONSUME_OUT'" :label="detail.docType === 'CONSUME_OUT' ? '超耗原因' : '超领原因'" min-width="120">
            <template #default="{ row }"><span :class="row.overReason ? 'warn' : 'dim'">{{ row.overReason || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">{{ row.remark || '—' }}</template>
          </el-table-column>
        </el-table>

        <!-- 操作记录放正文：侧栏只有 300px，五列的表只看得到时间、操作人（走查 T-33e438） -->
        <section class="body-logs">
          <h3>操作记录</h3>
          <DocLogList ref="logRef" :load="loadLogs" :actions="INV_DOC_LOG_ACTION" :statuses="LOG_STATUSES" />
        </section>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">单据信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>主体</dt>
              <dd>{{ detail.companyName }}</dd>
              <dt>类型</dt>
              <dd>{{ INV_DOC_TYPE[detail.docType].label }}</dd>
              <dt>源单</dt>
              <dd>
                <button v-if="detail.source" type="button" class="link-btn code" @click="goSource">{{ detail.source.typeLabel }} {{ detail.source.no }}</button>
                <template v-else>—</template>
              </dd>
              <template v-if="INV_DOC_TYPE[detail.docType].from">
                <dt>出仓</dt>
                <dd>{{ detail.fromWarehouseName ?? '—' }}</dd>
              </template>
              <template v-if="INV_DOC_TYPE[detail.docType].to">
                <dt>入仓</dt>
                <dd>{{ detail.toWarehouseName ?? '—' }}</dd>
              </template>
              <template v-if="INV_DOC_TYPE[detail.docType].partner">
                <dt>往来</dt>
                <dd>{{ detail.partnerName ?? '—' }}</dd>
              </template>
              <dt>日期</dt>
              <dd class="num">{{ formatDate(detail.docDate) }}</dd>
              <dt>原因</dt>
              <dd>{{ reasonText }}</dd>
              <template v-if="detail.docType === 'PURCHASE_IN'">
                <dt>送货单号</dt>
                <dd class="code">{{ detail.supplierDeliveryNo || '—' }}</dd>
              </template>
              <dt>物流单号</dt>
              <dd class="code">{{ detail.logisticsNo || '—' }}</dd>
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

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="!detail?.filesEditable" />
          </div>
        </section>
      </aside>
    </div>

    <InvDocFormDialog v-if="detail" v-model="editVisible" :doc-type="detail.docType" :editing-doc="detail" @saved="refresh" />
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1180px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.body {
  min-width: 0;
}

.body-logs {
  margin-top: 24px;
}

.body-logs h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.sub {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin: 0 0 10px;
  font-size: 13px;
}

.type {
  font-size: 13px;
}

.small {
  font-size: 11.5px;
}

.warn {
  color: var(--lm-pending-text);
}

.danger {
  color: var(--lm-danger-text);
}

.link-btn {
  padding: 0;
  font: inherit;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}
</style>
