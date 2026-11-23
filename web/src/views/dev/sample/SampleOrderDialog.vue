<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { sampleOrderApi } from '@/api/sample'
import type { SampleActionResult, SampleDetail, SampleOrder, SampleOrderForm } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import SampleFeedback from './SampleFeedback.vue'
import { SAMPLE_TYPE, orderScope } from './common'
import { useSampleAction } from './useSampleAction'
import { useSampleContext } from './sampleScope'

const props = defineProps<{ sample: SampleDetail; order: SampleOrder }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [result: SampleActionResult] }>()
const user = useUserStore(), original = props.order
const form = reactive<SampleOrderForm>({ version: original.version, sampleVersionLock: original.sampleVersionLock,
  sampleType: original.sampleType, qty: original.qty, colorIds: original.colors.map((color) => color.id),
  sizeCodes: [...original.sizeCodes], needDate: original.needDate, reason: original.reason, requirement: original.requirement,
  makeWay: original.makeWay, factoryId: original.factoryId, makerId: original.makerId })
const names = reactive({ factory: original.factoryName, maker: original.makerName })
const making = original.status === 'MAKING'
const allowed = computed(() => props.order.current && props.order.actions.edit && user.hasPerm('dev:sample:edit'))
const scope = () => visible.value ? orderScope(props.order) : null
const guard = useUnsavedChanges({ form, names }, visible, { scope })
useSampleContext(() => { visible.value = false })
const action = useSampleAction<SampleOrderForm, SampleActionResult>(scope, () => allowed.value,
  (body, options) => sampleOrderApi.update(original.id, original.companyId, body, options))
const inputError = ref<string | null>(null)
function wayChanged() { if (form.makeWay === 'INTERNAL') form.factoryId = null; else if (form.makeWay === 'EXTERNAL') form.makerId = null; else { form.factoryId = null; form.makerId = null } }
async function save() {
  inputError.value = null
  if (!Number.isInteger(form.qty) || form.qty < 1 || form.qty > 99999999 || !form.needDate) { inputError.value = '请填写 1–99999999 件的整数数量和要样日期'; return }
  if (!form.colorIds.length || !form.sizeCodes.length) { inputError.value = '至少选一个颜色和尺码'; return }
  if (original.sampleVersion > 1 && !form.reason?.trim()) { inputError.value = '第 2 版起必须填写改版原因'; return }
  if (making && (!form.makeWay || (form.makeWay === 'INTERNAL' ? !form.makerId : !form.factoryId))) { inputError.value = '请选择内部版师或外部打样工厂'; return }
  const result = await action.execute({ ...form, colorIds: [...form.colorIds], sizeCodes: [...form.sizeCodes],
    factoryId: form.makeWay === 'EXTERNAL' ? form.factoryId : null, makerId: form.makeWay === 'INTERNAL' ? form.makerId : null }, original.companyId)
  if (result) { guard.discardDraft(); visible.value = false; emit('saved', result) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" :title="`编辑第 ${original.sampleVersion} 版 · ${original.no}`" width="680px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <el-alert v-if="!allowed" title="这版已只读或权限已变化，输入仍保留" type="warning" :closable="false" />
    <el-form label-position="top" class="order-form" :disabled="!allowed || action.locked.value">
      <el-form-item label="样衣类型"><el-select v-model="form.sampleType" :disabled="making"><el-option v-for="(label, key) in SAMPLE_TYPE" :key="key" :value="key" :label="label" /></el-select></el-form-item>
      <el-form-item label="整版打样总件数"><el-input-number v-model="form.qty" :min="1" :max="99999999" :precision="0" :controls="false" :disabled="making" aria-label="打样总件数" /></el-form-item>
      <el-form-item label="颜色" class="full"><el-checkbox-group v-model="form.colorIds" :disabled="making"><el-checkbox v-for="color in making ? original.colors : sample.colors" :key="color.id" :value="color.id">{{ color.code }} {{ color.name }}</el-checkbox></el-checkbox-group></el-form-item>
      <el-form-item label="尺码" class="full"><el-checkbox-group v-model="form.sizeCodes" :disabled="making"><el-checkbox v-for="size in sample.sizes" :key="size" :value="size">{{ size }}</el-checkbox></el-checkbox-group></el-form-item>
      <el-form-item label="要样日期" required><el-date-picker v-model="form.needDate" value-format="YYYY-MM-DD" type="date" /></el-form-item>
      <el-form-item label="打样方式"><el-select v-model="form.makeWay" :clearable="!making" @change="wayChanged"><el-option label="内部版房" value="INTERNAL" /><el-option label="外部工厂" value="EXTERNAL" /></el-select></el-form-item>
      <el-form-item v-if="form.makeWay === 'INTERNAL'" label="版师" class="full"><UserSelect v-model="form.makerId" v-model:label="names.maker" /></el-form-item>
      <el-form-item v-if="form.makeWay === 'EXTERNAL'" label="打样工厂" class="full"><SupplierSelect v-model="form.factoryId" v-model:label="names.factory" :category="['GARMENT', 'PROCESSOR']" usage="SAMPLE" /></el-form-item>
      <el-form-item label="改版原因" class="full" :required="original.sampleVersion > 1"><el-input v-model="form.reason" type="textarea" maxlength="500" :disabled="making" /></el-form-item>
      <el-form-item label="打样要求" class="full"><el-input v-model="form.requirement" type="textarea" maxlength="1000" /></el-form-item>
    </el-form>
    <p v-if="making" class="dim">打样中只可改派单方、要样日期和要求；已有节点计划不会自动重排。</p>
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : '保存' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.order-form { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0 18px; }
.full { grid-column: 1 / -1; }
.order-form :deep(.el-select), .order-form :deep(.el-date-editor), .order-form :deep(.el-input-number) { width: 100%; }
</style>
