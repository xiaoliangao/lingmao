<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fgDocApi as fgDocApiBase } from '@/api/fg'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import StatusTag from '@/components/StatusTag.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import { useUserStore } from '@/stores/user'
import { runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useFieldAccess } from '@/composables/useFieldAccess'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import FgReadFeedback from '../FgReadFeedback.vue'
import { fgReadError, useFgRead } from '../fgRead'
import { fromScaledInteger, toScaledInteger } from '@/utils/fixedPoint'
import { sessionEpoch } from '@/utils/token'
import { useTabsStore } from '@/stores/tabs'
import type { DocAction } from '@/types/biz'
import { FG_DOC_KIND_LABEL, FG_DOC_LOG_ACTION, FG_DOC_STATUS, FG_DOC_TYPE, type FgDocAction, type FgDocDetail, type FgDocItem, type FgStyleOption } from '@/types/fg'
import { PRODUCTION_KIND } from '@/types/production'
import type { FileVO } from '@/types/system'
import { bindCompanyApi } from '@/utils/companyScope'
import { docRoute } from '@/utils/docRoute'
import { routeId } from '@/utils/entityId'
import { formatDate, formatDateTime, formatMoney, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import ReceiveDialog from '../stock-in/ReceiveDialog.vue'
import FgDocFormDialog from './FgDocFormDialog.vue'
import { fgCells, fgColors, fgSizes } from './fgMatrix'

const user = useUserStore()
const canEditFiles = computed(() => user.hasPerm('fg:doc:edit'))

/**
 * 成品入库单、出库单详情（/fg-in/:id、/fg-out/:id 共用，docs/api/07 §1.3）。
 * - 地址和单据的 kind 对不上：换到对的地址
 * - 页头按 actions：编辑、审核过账、反审核、删除（没过过账的草稿）或作废（过过账的）；审核没过（1001 库存不够、1004 超上限）弹清单
 * - 每款一个只读矩阵；草稿出库单格子下面"库存 n"，不够、超过能发的标红
 * - 编辑：回货入库开 ReceiveDialog，别的开 FgDocFormDialog；其他入库的草稿能"从 Excel 导入"明细
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const { canSeeOrderPrice } = useFieldAccess()

const id = routeId(route.params.id)
const path = route.path
const inReasons = useDict('fg_in_reason')
const outReasons = useDict('fg_out_reason')

const companyId = ref<string | null>(typeof route.query.companyId === 'string' ? route.query.companyId : null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const actionBusy = ref(false)
const detail = ref<FgDocDetail | null>(null)
const notFound = ref(false)
const editVisible = ref(false)
const receiveVisible = ref(false)
const importVisible = ref(false)
const logRef = ref<InstanceType<typeof DocLogList>>()

const fgDocApi = bindCompanyApi(fgDocApiBase, () => detail.value?.companyId ?? companyId.value)

const can = (a: FgDocAction) => detail.value?.actions.includes(a) ?? false
const listPath = computed(() => `${path.startsWith('/fg-out') ? '/fg-out' : '/fg-in'}${companyId.value ? `?companyId=${companyId.value}&tab=docs` : ''}`)
const kindLabel = computed(() => (detail.value ? FG_DOC_KIND_LABEL[detail.value.kind] : path.startsWith('/fg-out') ? '成品出库单' : '成品入库单'))
const isDraft = computed(() => detail.value?.status === 'DRAFT')
const showPrice = computed(() => detail.value?.docType === 'SHIP_OUT' && canSeeOrderPrice.value)
const canImport = computed(() => detail.value?.docType === 'OTHER_IN' && can('edit'))

const reasonText = computed(() => {
  const d = detail.value
  if (!d?.reason) return '—'
  return FG_DOC_TYPE[d.docType].reasonDict === 'fg_in_reason' ? inReasons.labelOf(d.reason) : outReasons.labelOf(d.reason)
})

const items = computed(() => new Map((detail.value?.items ?? []).map((it) => [it.styleSkuId, it])))
const qtys = computed(() => Object.fromEntries((detail.value?.items ?? []).map((it) => [it.styleSkuId, it.qty])))
const remarked = computed(() => (detail.value?.items ?? []).filter((it) => it.remark))

/** 单上有的格子才画 */
const blocks = computed(() =>
  (detail.value?.styles ?? []).map((st: FgStyleOption) => {
    const cells = fgCells(st, (sku) => items.value.has(sku))
    const lines = cells.map((c) => items.value.get(c.id)).filter((it): it is FgDocItem => !!it)
    return {
      style: st,
      cells,
      qty: lines.reduce((s, it) => s + it.qty, 0),
      amount: lines.length > 0 && lines.every((it) => it.amount != null) ? fromScaledInteger(lines.reduce((sum, it) => sum + (it.amount == null ? 0n : toScaledInteger(it.amount, 2)), 0n), 2) : null,
    }
  }),
)

function subOf(sku: string): string | null {
  const it = items.value.get(sku)
  if (!it || !isDraft.value) return null
  if (it.stockQty != null) return `库存 ${formatQty(it.stockQty)}`
  if (it.maxQty != null) return `最多 ${formatQty(it.maxQty)}`
  return null
}

function errorOf(sku: string, qty: number): string | null {
  const it = items.value.get(sku)
  if (!it || !isDraft.value) return null
  if (it.stockQty != null && qty > it.stockQty) return `库存只有 ${formatQty(it.stockQty)}，审核会失败`
  if (it.maxQty != null && qty > it.maxQty) return `最多 ${formatQty(it.maxQty)}，审核会失败`
  return null
}

const detailRequest = useFgRead(() => {
  detail.value = null; companyId.value = null; editVisible.value = false; receiveVisible.value = false; importVisible.value = false
})
async function load() {
  const read = detailRequest.begin()
  const company = companyId.value
  loading.value = false; loadError.value = null; notFound.value = false
  if (!company) { detail.value = null; return }
  loading.value = true
  try {
    const d = await fgDocApiBase.get(id, { companyId: company, signal: read.signal, silent: true })
    if (!read.current()) return
    if (d.companyId !== company) throw new ApiError(409, '单据主体不一致，请重新查询')
    const right = docRoute({ type: 'FG_DOC', kind: d.kind, id, parentId: null, companyId: company })
    if (right && right.split('?')[0] !== path) { await router.replace(right); tabs.close(path, right); return }
    detail.value = d
    tabs.rename(path, `${d.kind === 'OUT' ? '出库' : '入库'} ${d.no}`)
    if (route.path === path && route.query.companyId !== company) void router.replace({ path, query: { ...route.query, companyId: company } })
  } catch (cause) {
    if (!read.current()) return
    detail.value = null
    notFound.value = cause instanceof ApiError && [403, 404].includes(cause.code)
    loadError.value = fgReadError(cause)
  } finally { if (read.current()) loading.value = false }
}
watch(companyId, () => {
  detailRequest.cancel(); detail.value = null; notFound.value = false; loadError.value = null
  editVisible.value = false; receiveVisible.value = false; importVisible.value = false
  void load()
}, { flush: 'sync' })
watch(() => route.query.companyId, (value) => { if (route.path === path && typeof value === 'string') companyId.value = value })

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
  audit: '审核后马上改成品库存。',
  unaudit: '这张单改的库存改回去，回到草稿；入库单的货已经发走了会扣不回来，反审核不了。',
  void: '库存不受影响（反审核时已经改回去了），库存日志里的进出都留着。',
}

async function act(action: Extract<DocAction, 'audit' | 'unaudit' | 'void'>) {
  const d = detail.value
  if (!d || actionBusy.value || loading.value) return
  const epoch = sessionEpoch()
  actionBusy.value = true
  try {
    const ok = await runDocAction(action, d.no, d.version, (a, body, options) =>
      fgDocApiBase.act(id, a, body, { ...options, companyId: d.companyId, authEpoch: epoch }), { note: NOTES[action], reload: refresh })
    if (ok && companyId.value === d.companyId) await refresh()
  } finally { actionBusy.value = false }
}
async function remove() {
  const d = detail.value
  if (!d || actionBusy.value || loading.value) return
  const epoch = sessionEpoch()
  actionBusy.value = true
  try {
    await confirmRemove(`删除 ${d.no}`, '草稿删除后找不回来，库存不受影响。')
    await fgDocApiBase.remove(id, d.version, { companyId: d.companyId, authEpoch: epoch })
    if (companyId.value !== d.companyId) return
    ElMessage.success(`已删除 ${d.no}`)
    await router.push(listPath.value); tabs.close(path, listPath.value)
  } finally { actionBusy.value = false }
}

function edit() {
  if (detail.value?.docType === 'RECEIPT_IN') receiveVisible.value = true
  else editVisible.value = true
}

const loadLogs = () => fgDocApi.logs(id)
const loadFiles = () => fgDocApi.attachments(id)
const saveFiles = (files: FileVO[]) => fgDocApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(FG_DOC_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? kindLabel" :back="{ label: kindLabel, to: listPath }">
      <template v-if="detail" #title>
        <span class="dim type">{{ FG_DOC_TYPE[detail.docType].label }}</span>
        <StatusTag :tone="FG_DOC_STATUS[detail.status].tone">{{ FG_DOC_STATUS[detail.status].label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('remove')" :disabled="actionBusy || loading" @click="remove">删除</el-button>
        <el-button v-if="can('void')" :disabled="actionBusy || loading" @click="act('void')">作废</el-button>
        <el-button v-if="can('unaudit')" :disabled="actionBusy || loading" @click="act('unaudit')">反审核</el-button>
        <el-button v-if="canImport" icon="Upload" @click="importVisible = true">从 Excel 导入</el-button>
        <el-button v-if="can('edit')" :disabled="actionBusy || loading" @click="edit">编辑</el-button>
        <el-button v-if="can('audit')" type="primary" :disabled="actionBusy || loading" @click="act('audit')">审核过账</el-button>
      </template>
    </PageHeader>

    <div v-if="!detail" class="page">
      <label class="dim">单据主体</label>
      <CompanySelect v-model="companyId" style="width: 200px; margin-left: 12px" placeholder="选择这张单的主体" aria-label="单据主体" />
      <p v-if="!companyId" class="dim">先选择主体，再读取这张成品单。</p>
      <FgReadFeedback v-if="!notFound" :error="loadError" :loading="loading" label="成品单据" @retry="load" />
    </div>
    <BlankState v-if="notFound" title="无权限或这张单已不可见">
      <el-button @click="load">重试</el-button>
      <el-button @click="router.push(listPath)">回列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <p class="sub">
          <span>{{ detail.warehouseName }}</span>
          <span class="num">
            {{ formatQty(detail.totalQty) }} 件<template v-if="showPrice && detail.totalAmount != null"> · {{ formatMoney(detail.totalAmount) }}</template>
          </span>
        </p>

        <div v-for="b in blocks" :key="b.style.styleId" class="block">
          <div class="block-head">
            <ThumbImage v-if="b.style.image" :src="b.style.image.thumbUrl" alt="" class="thumb" loading="lazy" />
            <router-link v-if="user.hasPerm('style:style:list')" class="code link" :to="`/styles/${b.style.styleId}`">{{ b.style.code }}</router-link><span v-else class="code">{{ b.style.code }}</span>
            <span class="dim">{{ b.style.name }}</span>
            <span class="num">{{ formatQty(b.qty) }} 件</span>
            <span v-if="showPrice" class="num dim">{{ b.amount == null ? '—' : formatMoney(b.amount) }}</span>
          </div>
          <SkuMatrix
            :max-digits="8"
            :model-value="qtys"
            :sizes="fgSizes(b.style, b.cells)"
            :colors="fgColors(b.style, b.cells)"
            :skus="b.cells"
            readonly
            :sub="subOf"
            :error="errorOf"
          />
        </div>

        <section v-if="remarked.length" class="remarks">
          <div class="dim small">明细备注</div>
          <div v-for="it in remarked" :key="it.id" class="remark-row">
            <span class="code">{{ it.skuCode }}</span>
            <span>{{ it.remark }}</span>
          </div>
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
              <dd>{{ FG_DOC_TYPE[detail.docType].label }}</dd>
              <dt>{{ detail.kind === 'OUT' ? '出仓' : '入仓' }}</dt>
              <dd>{{ detail.warehouseName }}</dd>
              <template v-if="detail.receipt">
                <dt>回货单</dt>
                <dd>
                  <router-link v-if="user.hasPerm('production:order:list')" class="code link" :to="`${PRODUCTION_KIND[detail.receipt.prdOrderKind].path}/${detail.receipt.prdOrderId}?tab=receipts`">
                    {{ detail.receipt.no }}
                  </router-link><span v-else class="code">{{ detail.receipt.no }}</span>
                  <span class="dim small receipt-date">{{ formatDate(detail.receipt.receiptDate) }}</span>
                </dd>
                <dt>{{ PRODUCTION_KIND[detail.receipt.prdOrderKind].label }}</dt>
                <dd>
                  <router-link v-if="user.hasPerm('production:order:list')" class="code link" :to="`${PRODUCTION_KIND[detail.receipt.prdOrderKind].path}/${detail.receipt.prdOrderId}`">
                    {{ detail.receipt.prdOrderNo }}
                  </router-link><span v-else class="code">{{ detail.receipt.prdOrderNo }}</span>
                </dd>
                <dt>加工厂</dt>
                <dd>{{ detail.receipt.factoryName }}</dd>
              </template>
              <template v-if="detail.order">
                <dt>需求订单</dt>
                <dd><router-link v-if="user.hasPerm('order:order:list')" class="code link" :to="`/orders/${detail.order.id}`">{{ detail.order.no }}</router-link><span v-else class="code">{{ detail.order.no }}</span></dd>
              </template>
              <template v-if="FG_DOC_TYPE[detail.docType].reasonDict">
                <dt>原因</dt>
                <dd>{{ reasonText }}</dd>
              </template>
              <template v-if="detail.kind === 'OUT'">
                <dt>发到</dt>
                <dd>{{ detail.shipTo || '—' }}</dd>
                <dt>物流单号</dt>
                <dd class="code">{{ detail.logisticsNo || '—' }}</dd>
              </template>
              <dt>日期</dt>
              <dd class="num">{{ formatDate(detail.docDate) }}</dd>
              <dt>备注</dt>
              <dd>{{ detail.remark || '—' }}</dd>
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
              <template v-if="detail.auditByName">
                <dt>审核</dt>
                <dd>{{ detail.auditByName }}<template v-if="detail.auditTime"> · {{ formatDateTime(detail.auditTime) }}</template></dd>
              </template>
            </dl>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <!-- 换附件要编辑权限（后端兜底 403）：只能看、只能审的人不出"上传附件"（走查 T-120e64） -->
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="!canEditFiles" />
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">操作记录</div>
          <div class="panel-body">
            <DocLogList ref="logRef" :load="loadLogs" :actions="FG_DOC_LOG_ACTION" :statuses="LOG_STATUSES" compact />
          </div>
        </section>
      </aside>
    </div>

    <template v-if="detail">
      <FgDocFormDialog
        v-if="detail.docType !== 'RECEIPT_IN'"
        v-model="editVisible"
        :doc-type="detail.docType"
        :editing-doc="detail"
        @saved="refresh"
      />
      <ReceiveDialog v-else v-model="receiveVisible" :editing-doc="detail" @saved="refresh" />
      <ImportDialog
        v-if="canImport"
        v-model="importVisible"
        title="导入明细"
        :template-url="fgDocApiBase.itemsTemplateUrl(detail.companyId)"
        :import-url="fgDocApiBase.importUrl(id, detail.companyId, detail.version)"
        :company-id="detail.companyId"
        @success="refresh"
      />
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

@media (max-width: 1180px) {
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
  gap: 6px 16px;
  margin: 0 0 12px;
  font-size: 13px;
}

.type {
  font-size: 13px;
}

.block {
  margin-bottom: 18px;
}

.block-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 13px;
}

.thumb {
  flex: none;
  width: 24px;
  height: 24px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.remarks {
  display: grid;
  gap: 4px;
  font-size: 13px;
}

.remark-row {
  display: flex;
  gap: 12px;
}

.small {
  font-size: 11.5px;
}

.receipt-date {
  display: block;
  margin-top: 2px;
}

.link {
  color: var(--lm-accent);
  text-decoration: none;
}
</style>
