<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { attachmentBody } from '@/api/basic'
import { sampleOrderApi } from '@/api/sample'
import type { NamedId, ReviewForm, SampleActionResult, SampleOrder } from '@/types/sample'
import type { FileVO } from '@/types/system'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { businessToday, isFutureBusinessDay } from '@/utils/businessDate'
import UserSelect from '@/components/UserSelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import SampleFeedback from './SampleFeedback.vue'
import { orderScope, REVIEW_LABEL } from './common'
import { useSampleAction } from './useSampleAction'
import { useSampleContext } from './sampleScope'

const props = defineProps<{ order: SampleOrder }>()
const visible = defineModel<boolean>({ required: true }), user = useUserStore()
const emit = defineEmits<{ saved: [result: SampleActionResult] }>()
const original = props.order
const form = reactive({ result: 'PASS' as ReviewForm['result'], opinion: '', reviewDate: businessToday(),
  reviewers: user.info ? [{ id: user.info.id, name: user.info.nickname }] : [] as NamedId[], photos: [] as FileVO[] })
const selected = ref<string | null>(null), selectedName = ref<string | null>(null), inputError = ref<string | null>(null)
const allowed = computed(() => props.order.current && props.order.actions.review && user.hasPerm('dev:sample:review'))
const opinionLimit = computed(() => form.result === 'REVISE' ? 500 : 1000)
const scope = () => visible.value ? orderScope(props.order) : null
const guard = useUnsavedChanges(form, visible, { scope }), context = useSampleContext(() => { visible.value = false })
const action = useSampleAction<ReviewForm, SampleActionResult>(scope, () => allowed.value,
  (body, options) => sampleOrderApi.review(original.id, original.companyId, body, options))
function addReviewer() {
  if (!selected.value || !selectedName.value) return
  if (!form.reviewers.some((person) => person.id === selected.value) && form.reviewers.length < 100) form.reviewers.push({ id: selected.value, name: selectedName.value })
  selected.value = null; selectedName.value = null
}
async function save() {
  inputError.value = null
  if (!form.reviewDate || form.reviewDate > businessToday()) { inputError.value = '评审日期不能晚于今天'; return }
  if (!form.reviewers.length || form.reviewers.length > 100) { inputError.value = '请选择 1–100 位评审人'; return }
  if (form.result !== 'PASS' && !form.opinion.trim()) { inputError.value = '改版或淘汰必须填写意见'; return }
  if (form.opinion.length > opinionLimit.value) { inputError.value = `${form.result === 'REVISE' ? '改版' : '评审'}意见最多 ${opinionLimit.value} 字`; return }
  const current = context.capture()
  if (!await guard.waitForUploads() || !current()) return
  const result = await action.execute({ version: original.version, sampleVersionLock: original.sampleVersionLock,
    result: form.result, reviewDate: form.reviewDate, opinion: form.opinion || null,
    reviewerIds: form.reviewers.map((person) => person.id), photos: attachmentBody(form.photos) }, original.companyId)
  if (result && current()) { guard.discardDraft(); visible.value = false; emit('saved', result) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" title="样衣评审" width="620px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p class="dim">第 {{ original.sampleVersion }} 版 · {{ original.no }}</p>
    <el-alert v-if="!allowed" title="这版已只读或评审权限已变化，输入仍保留" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="!allowed || action.locked.value">
      <el-form-item label="结论"><el-segmented v-model="form.result" :options="Object.entries(REVIEW_LABEL).map(([value, label]) => ({ value, label }))" /></el-form-item>
      <el-form-item label="评审日期" required><el-date-picker v-model="form.reviewDate" type="date" value-format="YYYY-MM-DD" :disabled-date="isFutureBusinessDay" /></el-form-item>
      <el-form-item label="意见" :required="form.result !== 'PASS'"><el-input v-model="form.opinion" type="textarea" :rows="3" :maxlength="opinionLimit" show-word-limit aria-label="评审意见" /></el-form-item>
      <el-form-item label="评审人" required><div class="reviewers"><div class="selected"><el-tag v-for="person in form.reviewers" :key="person.id" :closable="!action.locked.value" @close="form.reviewers = form.reviewers.filter((item) => item.id !== person.id)">{{ person.name }}</el-tag></div><div class="choose"><UserSelect v-model="selected" v-model:label="selectedName" clearable /><el-button :disabled="!selected || form.reviewers.length >= 100" @click="addReviewer">加入评审人</el-button></div></div></el-form-item>
      <el-form-item label="试穿、问题部位照片"><ImageUpload v-model="form.photos" :max="9" :disabled="action.locked.value || !allowed" /></el-form-item>
    </el-form>
    <p v-if="form.result === 'REVISE'" class="dim">改版意见最多 500 字，将原文作为下一版的改版原因。生成下一版打样单；历史记录保留，新版本的颜色由旧选择与当前主档的交集确定。</p>
    <p v-else class="dim">评审意见最多 1000 字。</p>
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : form.result === 'REVISE' ? '改版，生成下一版' : REVIEW_LABEL[form.result] }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.reviewers { width: 100%; }
.selected { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.choose { display: flex; gap: 8px; }
.choose .el-select { flex: 1; min-width: 0; }
</style>
