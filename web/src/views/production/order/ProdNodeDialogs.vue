<script setup lang="ts">
import { isFutureBusinessDay } from '@/utils/businessDate'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productionApi as productionApiBase } from '@/api/production'
import ImageUpload from '@/components/ImageUpload.vue'
import { reloadOnConflict, todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { NodeReportAction, ProductionNode } from '@/types/production'
import type { FileVO } from '@/types/system'
import { formatDate } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
const productionApi = bindCompanyApi(productionApiBase, () => props.companyId)

/**
 * 生产单详情的两个节点对话框（docs/api/05 §1.4、§3.3、§4）：
 *
 *   <ProdNodeDialogs v-model:report-visible="a" v-model:plan-visible="b" :order-id="id" :total-qty="d.totalQty"
 *                    :nodes="d.nodes" :initial-node-id="nodeId" @updated="refresh" />
 *
 * 报进度：
 * - 上面一排节点（报过完成的带绿勾），选中的下面写计划起止和"上次 张三 11-20 报的"
 * - 实际开始默认节点原来的实际开始，没有用今天；实际完成不预填（不会一提交就报成完成，PRD §6.7.3）
 * - "只报开始"不发实际完成；节点已经报过完成时灰掉，旁边写原因
 * - 照片追加到节点上，已有几张写出来
 * 改节点计划：一个节点一行，计划开始、计划完成能改，实际完成只读。
 * 两个对话框都有"没保存的修改"提示；换节点算重新开始填
 */
const reportVisible = defineModel<boolean>('reportVisible', { required: true })
const planVisible = defineModel<boolean>('planVisible', { required: true })

const props = defineProps<{
  orderId: string
  companyId: string
  totalQty: number
  planRevision: number
  nodes: ProductionNode[]
  initialNodeId?: string | null
}>()

const emit = defineEmits<{
  updated: []
}>()

// ---------- 报进度 ----------
const activeNodeId = ref<string | null>(null)
const submitting = ref(false)

const reportForm = reactive({
  actualStart: null as string | null,
  actualFinish: null as string | null,
  doneQty: null as number | null,
  remark: '',
  photos: [] as FileVO[],
})
const reportGuard = useUnsavedChanges(reportForm, reportVisible)

const currentNode = computed(() => props.nodes.find((n) => n.id === activeNodeId.value))
const finished = computed(() => !!currentNode.value?.actualFinish)
const lastReport = computed(() => {
  const n = currentNode.value
  if (!n?.lastReportByName || !n.lastReportTime) return ''
  return `上次 ${n.lastReportByName} ${formatDate(n.lastReportTime).slice(5)} 报的`
})

function resetReportForm(node: ProductionNode | undefined) {
  reportForm.actualStart = node?.actualStart ?? todayStr()
  reportForm.actualFinish = null
  reportForm.doneQty = node?.doneQty ?? props.totalQty
  reportForm.remark = ''
  reportForm.photos = []
  reportGuard.markSaved()
}

watch(reportVisible, (on) => {
  if (!on) return
  const firstOpen = props.nodes.find((n) => !n.actualFinish) ?? props.nodes[0]
  activeNodeId.value = props.initialNodeId ?? firstOpen?.id ?? null
  resetReportForm(currentNode.value)
})

async function selectNode(node: ProductionNode) {
  if (node.id === activeNodeId.value) return
  if (reportGuard.dirty.value) {
    try { await ElMessageBox.confirm('此节点有没保存的修改或上传中的照片，换节点后会丢弃。', '换节点', { confirmButtonText: '放弃并换节点', cancelButtonText: '继续编辑' }) } catch { return }
  }
  reportGuard.discardUploads()
  activeNodeId.value = node.id
  resetReportForm(node)
}

const disableFuture = (d: Date) => isFutureBusinessDay(d)

async function submitReport(action: NodeReportAction) {
  const node = currentNode.value
  if (!node || !(await reportGuard.waitForUploads())) return
  if (action === 'START' && !reportForm.actualStart) {
    ElMessage.warning('填实际开始')
    return
  }
  submitting.value = true
  try {
    const res = await productionApi.report(props.orderId, node.id, {
      action,
      actualStart: reportForm.actualStart || null,
      // 只报开始不发实际完成；报完成没填就是今天（后端按 null 处理）
      actualFinish: action === 'START' ? null : reportForm.actualFinish || null,
      doneQty: reportForm.doneQty,
      remark: orNull(reportForm.remark),
      photos: reportForm.photos,
    })
    const msg = `${node.nodeName}已报${action === 'FINISH' ? '完成' : '开始'}`
    ElMessage.success(res.warning ? `${msg}：${res.warning}` : msg)
    reportGuard.markSaved()
    reportVisible.value = false
    emit('updated')
  } finally {
    submitting.value = false
  }
}

// ---------- 改节点计划 ----------
interface PlanRow {
  nodeId: string
  nodeName: string
  planStart: string
  planFinish: string
  actualFinish: string | null
}
const planRows = ref<PlanRow[]>([])
const savingPlan = ref(false)
const editingPlanRevision = ref(0)
const planGuard = useUnsavedChanges(planRows, planVisible)

watch(planVisible, (on) => {
  if (!on) return
  editingPlanRevision.value = props.planRevision
  planRows.value = props.nodes.map((n) => ({
    nodeId: n.id,
    nodeName: n.nodeName,
    planStart: n.planStart,
    planFinish: n.planFinish,
    actualFinish: n.actualFinish,
  }))
  planGuard.markSaved()
})

async function savePlan() {
  const bad = planRows.value.find((r) => !r.planStart || !r.planFinish || r.planFinish < r.planStart)
  if (bad) {
    ElMessage.warning(`${bad.nodeName}：计划完成不能早于计划开始`)
    return
  }
  savingPlan.value = true
  try {
    await productionApi.updatePlan(
      props.orderId,
      { planRevision: editingPlanRevision.value, nodes: planRows.value.map(({ nodeId, planStart, planFinish }) => ({ nodeId, planStart, planFinish })) },
    )
    ElMessage.success('节点计划改好了')
    planGuard.markSaved()
    planVisible.value = false
    emit('updated')
  } catch (e) {
    reloadOnConflict(e, async () => { planGuard.markSaved(); planVisible.value = false; emit('updated') })
    if (!(e instanceof ApiError)) throw e
  } finally {
    savingPlan.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="reportVisible"
    title="报进度"
    width="560px"
    :close-on-click-modal="false"
    :before-close="reportGuard.beforeClose"
  >
    <div class="node-chips">
      <button
        v-for="n in nodes"
        :key="n.id"
        type="button"
        class="n-chip"
        :class="{ active: n.id === activeNodeId }"
        @click="selectNode(n)"
      >
        <el-icon v-if="n.actualFinish" class="done-mark"><Select /></el-icon>
        {{ n.nodeName }}
      </button>
    </div>

    <template v-if="currentNode">
      <p class="plan-line dim num">
        计划 {{ formatDate(currentNode.planStart) }} ~ {{ formatDate(currentNode.planFinish) }}
        <template v-if="lastReport"> · {{ lastReport }}</template>
        <span v-if="finished" class="warn-text"> · 这个节点已经报过完成</span>
      </p>

      <el-form label-position="top" class="form-grid">
        <el-form-item label="实际开始">
          <el-date-picker v-model="reportForm.actualStart" type="date" value-format="YYYY-MM-DD" :disabled-date="disableFuture" />
        </el-form-item>
        <el-form-item label="实际完成">
          <el-date-picker
            v-model="reportForm.actualFinish"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled-date="disableFuture"
            placeholder="报完成时不填就是今天"
          />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="reportForm.doneQty" :min="0" :precision="0" :controls="false" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reportForm.remark" maxlength="120" />
        </el-form-item>
        <el-form-item class="span-all">
          <template #label>
            照片<span v-if="currentNode.photos.length" class="dim">（这个节点已经有 {{ currentNode.photos.length }} 张，再传是追加）</span>
          </template>
          <ImageUpload v-model="reportForm.photos" :max="9" />
        </el-form-item>
      </el-form>
    </template>

    <template #footer>
      <el-button @click="reportVisible = false">取消</el-button>
      <el-button :disabled="finished" :loading="submitting" @click="submitReport('START')">只报开始</el-button>
      <el-button type="primary" :loading="submitting" @click="submitReport('FINISH')">报完成</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="planVisible"
    title="改节点计划"
    width="620px"
    :close-on-click-modal="false"
    :before-close="planGuard.beforeClose"
  >
    <el-table :data="planRows" row-key="nodeId" size="small" class="node-plan-table">
      <el-table-column label="节点" prop="nodeName" width="100" />
      <el-table-column label="计划开始">
        <template #default="{ row }">
          <el-date-picker v-model="row.planStart" size="small" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </template>
      </el-table-column>
      <el-table-column label="计划完成">
        <template #default="{ row }">
          <el-date-picker v-model="row.planFinish" size="small" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </template>
      </el-table-column>
      <el-table-column label="实际完成" width="100" class-name="num">
        <template #default="{ row }">{{ row.actualFinish ? formatDate(row.actualFinish) : '—' }}</template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="planVisible = false">取消</el-button>
      <el-button type="primary" :loading="savingPlan" @click="savePlan">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.node-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.n-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 32px;
  padding: 5px 12px;
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}

.n-chip.active {
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent);
}

.done-mark {
  color: var(--lm-done-text);
}

.plan-line {
  margin: 0 0 10px;
  font-size: 12px;
}

.warn-text {
  color: var(--lm-pending-text);
}

.form-grid :deep(.el-input-number),
.form-grid :deep(.el-date-editor) {
  width: 100%;
}

.node-plan-table :deep(.el-date-editor) {
  width: 100%;
  min-width: 0;
}

.span-all {
  grid-column: 1 / -1;
}
</style>
