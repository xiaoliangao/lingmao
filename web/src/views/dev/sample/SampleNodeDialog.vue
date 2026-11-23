<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { sampleOrderApi } from '@/api/sample'
import type { NodeDoneForm, NodePlanForm, SampleActionResult, SampleOrder } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { businessToday, isFutureBusinessDay } from '@/utils/businessDate'
import UserSelect from '@/components/UserSelect.vue'
import SampleFeedback from './SampleFeedback.vue'
import { orderScope } from './common'
import { useSampleAction } from './useSampleAction'
import { useSampleContext } from './sampleScope'

const props = defineProps<{ order: SampleOrder; initialMode?: 'done' | 'undo' | 'plans'; initialNodeId?: string }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [result: SampleActionResult] }>()
const user = useUserStore(), original = props.order
const mode = ref(props.initialMode ?? 'done')
const initialNode = original.nodes.find((node) => node.id === props.initialNodeId) ?? original.nodes.find((node) => !node.doneDate) ?? original.nodes[0]
const form = reactive({ nodeId: initialNode?.id ?? '', doneDate: initialNode?.doneDate ?? businessToday(),
  nodes: original.nodes.map((node) => ({ id: node.id, nodeCode: node.nodeCode, nodeName: node.nodeName, planDate: node.planDate, ownerId: node.ownerId, ownerName: node.ownerName, remark: node.remark })) })
const scope = () => visible.value ? `${orderScope(props.order)}:${mode.value}` : null
const allowed = computed(() => props.order.current && props.order.actions.node && user.hasPerm('dev:sample:make'))
const guard = useUnsavedChanges(form, visible, { scope })
useSampleContext(() => { visible.value = false })
type Operation = { kind: 'done'; nodeId: string; body: NodeDoneForm } | { kind: 'undo'; nodeId: string; body: Pick<NodePlanForm, 'version' | 'sampleVersionLock'> } | { kind: 'plans'; body: NodePlanForm }
const action = useSampleAction<Operation, SampleActionResult>(scope, () => allowed.value, (operation, options) => {
  if (operation.kind === 'plans') return sampleOrderApi.nodePlans(original.id, original.companyId, operation.body, options)
  return operation.kind === 'done' ? sampleOrderApi.nodeDone(original.id, operation.nodeId, original.companyId, operation.body, options)
    : sampleOrderApi.nodeUndo(original.id, operation.nodeId, original.companyId, operation.body, options)
})
const inputError = ref<string | null>(null)
const node = computed(() => original.nodes.find((item) => item.id === form.nodeId))
const selectable = computed(() => ['MAKING', 'WAIT_REVIEW'].includes(original.status)
  ? original.nodes.filter((item) => mode.value !== 'undo' || !!item.doneDate) : [])
watch(() => form.nodeId, () => { form.doneDate = node.value?.doneDate ?? businessToday() }, { flush: 'sync' })
async function save() {
  inputError.value = null
  const locks = { version: original.version, sampleVersionLock: original.sampleVersionLock }
  let operation: Operation
  if (mode.value === 'plans') {
    if (original.status !== 'MAKING' || form.nodes.length !== original.nodes.length || form.nodes.some((item) => !item.planDate)) { inputError.value = '请填写全部节点的计划日期'; return }
    operation = { kind: 'plans', body: { ...locks, nodes: form.nodes.map(({ id, planDate, ownerId, remark }) => ({ id, planDate, ownerId, remark })) } }
  } else {
    if (!node.value || !selectable.value.some((item) => item.id === form.nodeId)) { inputError.value = '请选择当前可操作的节点'; return }
    if (mode.value === 'done') {
      if (!form.doneDate || form.doneDate > businessToday()) { inputError.value = '完成日期不能晚于今天'; return }
      operation = { kind: 'done', nodeId: form.nodeId, body: { ...locks, doneDate: form.doneDate } }
    } else operation = { kind: 'undo', nodeId: form.nodeId, body: locks }
  }
  const result = await action.execute(operation, original.companyId)
  if (result) { guard.discardDraft(); visible.value = false; emit('saved', result) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" :title="mode === 'plans' ? '节点计划' : mode === 'undo' ? '撤回节点完成' : node?.doneDate ? '改完成日期' : '报节点'" :width="mode === 'plans' ? '900px' : '520px'" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p class="dim">第 {{ original.sampleVersion }} 版 · {{ original.no }}</p>
    <el-alert v-if="!allowed" title="当前状态或权限不允许修改节点" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="!allowed || action.locked.value">
      <template v-if="mode !== 'plans'">
        <el-form-item label="节点" required><el-select v-model="form.nodeId" aria-label="打样节点"><el-option v-for="item in selectable" :key="item.id" :value="item.id" :label="item.nodeName + (item.doneDate ? ' · 已完成' : '')" /></el-select></el-form-item>
        <el-form-item v-if="mode === 'done'" label="完成日期" required><el-date-picker v-model="form.doneDate" value-format="YYYY-MM-DD" type="date" :disabled-date="isFutureBusinessDay" aria-label="节点完成日期" /></el-form-item>
        <p v-if="mode === 'done' && node?.doneDate" class="dim">这一步已报完成；再次保存将更新完成日期，并保留操作记录。</p>
        <p v-if="mode === 'done' && node?.nodeCode === 'SEND'" class="dim">送样后进入待评审；有未报的前置节点时，服务器会在成功结果中明确提示。</p>
        <p v-if="mode === 'undo'" class="dim">撤回完成保留操作记录；撤回送样会回到打样中。</p>
      </template>
      <el-table v-else :data="form.nodes" max-height="420px" row-key="id">
        <el-table-column label="节点" prop="nodeName" width="110" fixed="left" />
        <el-table-column label="计划日期" width="168"><template #default="{ row }"><el-date-picker v-model="row.planDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" /></template></el-table-column>
        <el-table-column label="负责人" min-width="220"><template #default="{ row }"><UserSelect v-model="row.ownerId" v-model:label="row.ownerName" clearable /></template></el-table-column>
        <el-table-column label="备注" min-width="200"><template #default="{ row }"><el-input v-model="row.remark" maxlength="255" /></template></el-table-column>
      </el-table>
    </el-form>
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : mode === 'plans' ? '保存计划' : mode === 'undo' ? '确认撤回' : node?.doneDate ? '保存日期' : '报完成' }}</el-button></template>
  </el-dialog>
</template>
