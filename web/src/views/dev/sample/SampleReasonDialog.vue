<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { sampleApi } from '@/api/sample'
import type { SampleActionResult, SampleDetail } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import SampleFeedback from './SampleFeedback.vue'
import { useSampleAction } from './useSampleAction'
import { useSampleContext } from './sampleScope'
const props = defineProps<{ sample: SampleDetail; kind: 'stop' | 'reopen' }>()
const visible = defineModel<boolean>({ required: true }), user = useUserStore()
const emit = defineEmits<{ saved: [result: SampleActionResult] }>()
const original = props.sample, kind = props.kind, form = reactive({ reason: '' }), inputError = ref<string | null>(null)
const scope = () => visible.value ? `${props.sample.companyId}:${props.sample.id}:${props.sample.version}:${kind}` : null
const allowed = computed(() => props.sample.actions[kind] && user.hasPerm('dev:sample:review'))
const guard = useUnsavedChanges(form, visible, { scope })
useSampleContext(() => { visible.value = false })
const action = useSampleAction<{ version: number; reason: string }, SampleActionResult>(scope, () => allowed.value,
  (body, options) => sampleApi.reason(original.id, original.companyId, kind, body, options))
async function save() {
  inputError.value = null
  if (!form.reason.trim()) { inputError.value = '请填写原因'; return }
  const result = await action.execute({ version: original.version, reason: form.reason.trim() }, original.companyId)
  if (result) { guard.discardDraft(); visible.value = false; emit('saved', result) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" :title="kind === 'stop' ? '终止开发' : '恢复开发'" width="480px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p class="dim">{{ original.code }} · {{ original.name }}</p>
    <p v-if="kind === 'reopen'" class="dim">生成新版本继续开发，旧版节点和评审保留。</p>
    <el-alert v-if="!allowed" title="当前状态或权限不允许此动作" type="warning" :closable="false" />
    <el-input v-model="form.reason" :disabled="!allowed || action.locked.value" type="textarea" :rows="4" maxlength="500" placeholder="填写原因" aria-label="原因" />
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : kind === 'stop' ? '确认终止' : '恢复开发' }}</el-button></template>
  </el-dialog>
</template>
