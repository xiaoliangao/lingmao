<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { orderApi, orderReviewApi } from '@/api/order'
import StatusTag from '@/components/StatusTag.vue'
import { reloadOnConflict, showSilentError } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { OrderDetail, OrderReview } from '@/types/order'
import { formatDate, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'

/**
 * 订单审核（docs/api/02 §3.7、§3.8、§4.4，原型 OrderAudit）：三块评审信息只是参考，加一个承诺交期（每一级都填）。
 * 有款资料包没生效时"通过"灰掉。审核请求静默：资料包检查没过（1000 + 清单）弹表格；1002 保留承诺交期，用户确认放弃后才关窗重取。
 *
 *   <OrderAuditDialog v-model="auditVisible" :order="detail" @changed="refresh" />
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ order: OrderDetail }>()
const emit = defineEmits<{ changed: [] }>()

const units = useDict('unit')
const loading = ref(false)
const submitting = ref(false)
const review = ref<OrderReview | null>(null)
const reviewError = ref(false)
let reviewSeq = 0
const form = reactive({ commitDate: '' })
const guard = useUnsavedChanges(form, visible)

const blocked = computed(() => (review.value?.packs ?? []).some((p) => p.problem))

/** 承诺交期不能早于下单日期（后端 400） */
const disabledDate = (date: Date) => date < new Date(`${props.order.orderDate}T00:00:00`)

watch(visible, (on) => {
  if (!on) { reviewSeq++; return }
  form.commitDate = props.order.commitDate ?? props.order.requiredDate
  guard.markSaved()
  void loadReview()
})
async function loadReview() {
  const seq = ++reviewSeq
  review.value = null
  reviewError.value = false
  loading.value = true
  try {
    const result = await orderReviewApi.get(props.order.id)
    if (seq === reviewSeq && visible.value) review.value = result
  } catch (e) {
    if (seq === reviewSeq) reviewError.value = true
    if (!(e instanceof ApiError)) throw e
  } finally {
    if (seq === reviewSeq) loading.value = false
  }
}

/** 多级审批的中间一级：审过以后还在待审核，提示等谁 */
function nextLevelLabel(): string | null {
  const levels = props.order.approval?.levels ?? []
  const current = levels.findIndex((l) => l.status === 'CURRENT')
  return current >= 0 ? (levels[current + 1]?.approverLabel ?? null) : null
}

async function pass() {
  if (!form.commitDate) {
    ElMessage.warning('填承诺交期')
    return
  }
  const next = nextLevelLabel()
  submitting.value = true
  try {
    await orderApi.audit(props.order.id, { version: props.order.version, commitDate: form.commitDate }, { silent: true })
    ElMessage.success(next ? `这一级审过了，等${next}审` : `${props.order.no} 已审核，资料包已锁定`)
    guard.markSaved()
    visible.value = false
    emit('changed')
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    reloadOnConflict(e, () => { guard.markSaved(); visible.value = false; emit('changed') })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`审核 ${order.no}`" width="640px" :before-close="guard.beforeClose">
    <div v-loading="loading" class="review">
      <el-alert v-if="reviewError" title="评审信息暂不可用" type="warning" :closable="false">
        当前未取得资料包、缺料和产能参考；审核仍由后台校验。<el-button link @click="loadReview">重试</el-button>
      </el-alert>
      <template v-if="review">
      <section>
        <h4>资料包</h4>
        <ul class="rows">
          <li v-for="p in review?.packs ?? []" :key="p.styleId" :class="{ bad: !!p.problem }">
            <span class="code">{{ p.styleCode }}</span>
            <span>{{ p.styleName }}</span>
            <span class="gap" />
            <span v-if="p.problem" class="danger">{{ p.problem }}</span>
            <StatusTag v-else tone="active">锁定 V{{ p.version }}</StatusTag>
          </li>
        </ul>
      </section>

      <section>
        <h4>缺口最大的料</h4>
        <el-table :data="review?.shortages ?? []" size="small" row-key="materialSkuId" empty-text="现有库存够用">
          <el-table-column label="物料 SKU" min-width="180">
            <template #default="{ row }">
              <span class="code">{{ row.skuCode }}</span> {{ row.materialName }}
              <span class="dim">{{ [row.skuColorName, row.skuSpec].filter(Boolean).join(' ') }}</span>
            </template>
          </el-table-column>
          <el-table-column label="要" width="90" class-name="num" align="right">
            <template #default="{ row }">{{ formatQty(row.need) }} {{ units.labelOf(row.unit) }}</template>
          </el-table-column>
          <el-table-column label="可用" width="80" class-name="num" align="right">
            <template #default="{ row }">{{ formatQty(row.available) }}</template>
          </el-table-column>
          <el-table-column label="缺" width="80" class-name="num" align="right">
            <template #default="{ row }"><span class="danger">{{ formatQty(row.gap) }}</span></template>
          </el-table-column>
          <el-table-column label="采购周期" width="76" class-name="num" align="right">
            <template #default="{ row }">{{ row.leadDays == null ? '—' : `${row.leadDays} 天` }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="review?.loads.length">
        <h4>默认加工厂在手</h4>
        <ul class="rows">
          <li v-for="ld in review.loads" :key="ld.factoryId">
            <strong>{{ ld.factoryName }}</strong>
            <span class="dim">{{ ld.styleCodes.join('、') }}</span>
            <span class="gap" />
            <span class="num">{{ ld.orderCount }} 张单 · {{ formatQty(ld.wipQty) }} 件在制</span>
          </li>
        </ul>
      </section>

      </template>
      <el-form label-position="top">
        <el-form-item :label="`承诺交期（要求交期 ${formatDate(order.requiredDate)}）`" required>
          <el-date-picker v-model="form.commitDate" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled-date="disabledDate" />
        </el-form-item>
      </el-form>
      <p v-if="blocked" class="danger tip">有款资料包没生效，不能审</p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="blocked || loading" @click="pass">通过</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.review {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.rows li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  font-size: 12.5px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.rows li.bad {
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

.gap {
  flex: 1;
}

.danger {
  color: var(--lm-danger-text);
}

.tip {
  margin: 0;
  font-size: 12.5px;
}
</style>
