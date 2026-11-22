<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { priceApi } from '@/api/price'
import { PRICE_TYPE_LABEL, type PriceRow } from '@/types/price'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { useUserStore } from '@/stores/user'
import { formatDate, formatPrice } from '@/utils/format'
import PriceForm from './PriceForm.vue'
import { EDIT_PERM, changePct, recordableTypes, shortDay, skuLabel, styleLabel, today, type PriceSubject } from './price'
import { priceReadPermission } from '@/utils/fieldAccess'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost, canSeeProductionPrice } = useFieldAccess()

/**
 * 价格记录页签（RFC 006 §9，原型 5e-price.js 的 PriceHistory）：物料、供应商、款式详情里共用。
 * 上面是现价（每组一行：供应商 × 物料 / SKU，工厂 × 款式 × 加工方式），带上一次的价和涨跌、以后生效的价；
 * 下面是全部历史，作废的划掉、写着作废原因，物料换过采购单位的旧价标"单位已变"（不参与取价）。
 * 右上角"录价格"、每行"调价""作废"按类型看权限（物料价 price:material:edit，加工价 price:process:edit）。
 * 页签本身要 price:price:list，由详情页判断（v-if，没有就看不到这个页签）
 *
 *   <el-tab-pane v-if="user.hasPerm('price:price:list')" label="价格记录" name="prices" lazy>
 *     <PriceHistory :subject="{ kind: 'material', id, code: m.code, name: m.name }" />
 *   </el-tab-pane>
 *
 * 款式详情：:subject="{ kind: 'style', id, code: 款号, name: 款名 }"，只有加工价
 */
const props = defineProps<{ subject: PriceSubject }>()

const router = useRouter()
const user = useUserStore()
const units = useDict('unit')
const modes = useDict('process_mode')

const filter = computed(() => {
  const { kind, id } = props.subject
  const target = kind === 'supplier' ? { supplierId: id } : kind === 'material' ? { materialId: id } : { styleId: id }
  const type = kind === 'material' || (!canSeeProductionPrice.value && canSeeCost.value) ? 'MATERIAL' as const
    : kind === 'style' || (!canSeeCost.value && canSeeProductionPrice.value) ? 'PROCESS' as const : undefined
  return { ...target, type }
})

const currentTable = useTable({
  fetch: (params) => priceApi.page({ ...params, ...filter.value, current: true }),
  query: {},
})
const historyTable = useTable({
  fetch: (params) => priceApi.page({ ...params, ...filter.value, includeVoided: true }),
  query: {},
})
const { list: loadedCurrent, total: currentTotal, page: currentPage, size: currentSize, loading: currentLoading } = currentTable
const { list: loadedHistory, total: historyTotal, page: historyPage, size: historySize, loading: historyLoading } = historyTable
const currentList = computed(() => loadedCurrent.value.filter((row) => user.hasPerm(priceReadPermission(row.type))))
const historyList = computed(() => loadedHistory.value.filter((row) => user.hasPerm(priceReadPermission(row.type))))

function reload() {
  currentTable.search()
  historyTable.search()
}
watch(filter, reload)

defineExpose({ reload })

const bySupplier = computed(() => props.subject.kind === 'supplier')
/** 物料价、加工价都有才显示类型列（只有供应商页会两种都有） */
const mixed = computed(() => new Set([...currentList.value, ...historyList.value].map((row) => row.type)).size > 1)
const canRecord = computed(() => recordableTypes(props.subject.kind, user.hasPerm).length > 0)
const canEdit = (row: PriceRow) => user.hasPerm(EDIT_PERM[row.type]) && user.hasPerm(priceReadPermission(row.type))

/** 第三列：供应商页是"物料 / 款式"，物料页是 SKU，款式页是加工方式 */
const whatLabel = computed(() => ({ supplier: '物料 / 款式', material: '颜色 / SKU', style: '加工方式' })[props.subject.kind])
function what(row: PriceRow): string {
  const mode = modes.labelOf(row.processMode)
  if (props.subject.kind === 'material') return row.materialSkuId ? skuLabel(row) : '所有颜色同价'
  if (props.subject.kind === 'style') return mode
  return row.type === 'MATERIAL'
    ? `${row.materialCode} ${row.materialName} · ${row.materialSkuId ? skuLabel(row) : '所有颜色'}`
    : `${styleLabel(row)} · ${mode}`
}
/** 作废对话框里说的是哪一条 */
function whole(row: PriceRow): string {
  const thing = row.type === 'MATERIAL'
    ? `${row.materialCode} ${row.materialName} · ${row.materialSkuId ? skuLabel(row) : '所有颜色'}`
    : `${styleLabel(row)} · ${modes.labelOf(row.processMode)}`
  return `${row.supplierName ?? ''} · ${thing}`
}

const unitOf = (row: PriceRow) => units.labelOf(row.unit)
const taxText = (row: PriceRow) =>
  `${formatPrice(row.taxPct)}% ${row.taxIncluded ? '含税' : '不含税'}${row.freightIncluded ? '' : ' · 不含运费'}`

const voidClass = ({ row }: { row: PriceRow }) => (row.voided ? 'is-void' : '')

/** 详情页的地址；用户没有那个列表菜单（没挂详情路由）就不给链接 */
function detailLink(path: string): string | null {
  return router.resolve(path).meta.detail ? path : null
}

// ---------- 录价格、调价、作废 ----------
const formDialog = ref<InstanceType<typeof PriceForm>>()

async function voidRow(row: PriceRow) {
  const { value } = await ElMessageBox.prompt(
    `${whole(row)}，${shortDay(row.effectiveDate)} 生效。作废后这条不参与取价，记录还留着。`,
    `作废 ${formatPrice(row.price)} 元 / ${unitOf(row)}`,
    {
      confirmButtonText: '作废',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
      inputPlaceholder: '作废原因，如：录错了，应该是 18.50',
      inputValidator: (text) => (text?.trim() ? (text.trim().length <= 255 ? true : '原因最多 255 个字') : '请填写作废原因'),
    },
  )
  try {
    await priceApi.void(row.id, value.trim())
    ElMessage.success('作废了这条价格')
  } finally {
    // 别人先作废了（1002）也刷新，看到的就是最新的
    reload()
  }
}
</script>

<template>
  <section class="price-history">
    <div v-if="canRecord" class="bar">
      <el-button icon="Plus" @click="formDialog?.open(subject)">录价格</el-button>
    </div>

    <!-- 单号（CG + 12 位）、税率说明放得下；作废说明换行显示全（走查 T-120e64） -->
    <el-table v-loading="currentLoading" :data="currentList" row-key="id" scrollbar-always-on :empty-text="canRecord ? '还没有价格记录，点「录价格」录供应商的报价' : '还没有价格记录'">
      <el-table-column v-if="mixed" label="类型" width="72">
        <template #default="{ row }"><span class="dim">{{ PRICE_TYPE_LABEL[(row as PriceRow).type] }}</span></template>
      </el-table-column>
      <el-table-column v-if="!bySupplier" :label="subject.kind === 'style' ? '工厂' : '供应商'" min-width="110">
        <template #default="{ row }">
          <router-link v-if="detailLink(`/suppliers/${row.supplierId}`)" :to="`/suppliers/${row.supplierId}`">{{ row.supplierName }}</router-link>
          <span v-else>{{ row.supplierName }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="whatLabel" :min-width="bySupplier ? 230 : 150">
        <template #default="{ row }">
          <router-link v-if="bySupplier && row.materialId && detailLink(`/materials/${row.materialId}`)" :to="`/materials/${row.materialId}`">{{ what(row as PriceRow) }}</router-link>
          <span v-else>{{ what(row as PriceRow) }}</span>
          <span v-if="subject.kind === 'material' && row.skuCode" class="code dim sku-code">{{ row.skuCode }}</span>
        </template>
      </el-table-column>
      <el-table-column label="现价" min-width="110" class-name="num" align="right">
        <template #default="{ row }">
          <b class="price">{{ formatPrice(row.price) }}</b><span class="dim"> / {{ unitOf(row as PriceRow) }}</span>
          <div v-if="row.effectiveDate > today()" class="note pending">{{ shortDay(row.effectiveDate) }} 起生效</div>
          <div v-else-if="row.nextPrice != null" class="note pending">{{ shortDay(row.nextDate) }} 起 {{ formatPrice(row.nextPrice) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="上一次" min-width="100" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.prevPrice != null">
            {{ formatPrice(row.prevPrice) }}
            <span :class="['pct', changePct(row.price, row.prevPrice)! > 0 ? 'up' : 'down']">
              {{ changePct(row.price, row.prevPrice)! > 0 ? '+' : '' }}{{ changePct(row.price, row.prevPrice) }}%
            </span>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="生效" width="116" class-name="num nowrap">
        <template #default="{ row }">{{ formatDate(row.effectiveDate) }}</template>
      </el-table-column>
      <el-table-column label="调价原因" min-width="120">
        <template #default="{ row }">{{ row.reason || '—' }}</template>
      </el-table-column>
      <el-table-column label="来源" min-width="144">
        <template #default="{ row }">
          <span v-if="row.sourceNo" class="code">{{ row.sourceNo }}</span>
          <span v-else class="dim">手工录</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="76" align="right" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit(row as PriceRow)" link @click="formDialog?.open(subject, row as PriceRow)">调价</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="currentTotal > currentSize" v-model:current-page="currentPage" :page-size="currentSize" :total="currentTotal" layout="total, prev, pager, next" size="small" class="pagination" />

    <template v-if="historyTotal > 0">
      <h3 class="sec">调价历史</h3>
      <el-table v-loading="historyLoading" :data="historyList" row-key="id" :row-class-name="voidClass" scrollbar-always-on>
        <el-table-column label="生效日期" width="116" class-name="num nowrap">
          <template #default="{ row }">{{ formatDate(row.effectiveDate) }}</template>
        </el-table-column>
        <el-table-column v-if="mixed" label="类型" width="72">
          <template #default="{ row }">{{ PRICE_TYPE_LABEL[(row as PriceRow).type] }}</template>
        </el-table-column>
        <el-table-column v-if="!bySupplier" :label="subject.kind === 'style' ? '工厂' : '供应商'" min-width="100" prop="supplierName" />
        <el-table-column :label="whatLabel" :min-width="bySupplier ? 220 : 130">
          <template #default="{ row }">{{ what(row as PriceRow) }}</template>
        </el-table-column>
        <el-table-column label="单价" min-width="110" class-name="num" align="right">
          <template #default="{ row }">
            <s v-if="row.voided">{{ formatPrice(row.price) }}</s><template v-else>{{ formatPrice(row.price) }}</template>
            <span class="dim"> / {{ unitOf(row as PriceRow) }}</span>
            <div v-if="row.unitChanged" class="note pending" title="物料后来换了采购单位，这条不参与取价">单位已变</div>
          </template>
        </el-table-column>
        <!-- "13% 含税 · 不含运费"实测 143px，加页边 24 -->
        <el-table-column label="税率" min-width="168">
          <template #default="{ row }"><span class="dim">{{ taxText(row as PriceRow) }}</span></template>
        </el-table-column>
        <el-table-column label="调价原因" min-width="160" :show-overflow-tooltip="false">
          <template #default="{ row }">
            {{ row.reason || '—' }}
            <div v-if="row.voided" class="note danger">已作废：{{ row.voidReason }}（{{ row.voidByName }} · {{ shortDay(formatDate(row.voidTime)) }}）</div>
          </template>
        </el-table-column>
        <el-table-column label="来源、记录" min-width="144">
          <template #default="{ row }">
            <span v-if="row.sourceNo" class="code">{{ row.sourceNo }}</span>
            <span v-else>手工录</span>
            <div class="note dim">{{ row.createByName }} · {{ shortDay(formatDate(row.createTime)) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="" width="76" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.voided && canEdit(row as PriceRow)" link type="danger" @click="voidRow(row as PriceRow)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="historyTotal > historySize" v-model:current-page="historyPage" :page-size="historySize" :total="historyTotal" layout="total, prev, pager, next" size="small" class="pagination" />
    </template>

    <PriceForm ref="formDialog" @saved="reload" />
  </section>
</template>

<style scoped>
.bar {
  display: flex;
  justify-content: flex-end;
  min-height: 32px;
  margin-bottom: 10px;
}

.sec {
  margin: 24px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.price {
  font-weight: 600;
}

.note {
  font-size: 12px;
}

.note.pending {
  color: var(--lm-pending-text);
}

.note.danger {
  color: var(--lm-danger-text);
}

.pct {
  font-size: 12px;
}

/* 涨价红、降价绿（原型一样） */
.pct.up {
  color: var(--lm-danger-text);
}

.pct.down {
  color: var(--lm-done-text);
}

.price-history :deep(.el-table__row.is-void) {
  color: var(--lm-muted);
}

.price-history :deep(td.nowrap .cell) {
  white-space: nowrap;
}

.sku-code {
  margin-left: 6px;
}

.price-history a {
  color: var(--lm-accent);
  text-decoration: none;
}

.price-history a:hover {
  text-decoration: underline;
}
</style>
