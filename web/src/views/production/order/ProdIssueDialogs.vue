<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { productionApi } from '@/api/production'
import DictSelect from '@/components/DictSelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import { addDaysStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { RESPONSIBLE_PARTY, type ResponsibleParty } from '@/types/order'
import type { ProductionIssue } from '@/types/production'
import type { FileVO } from '@/types/system'

/**
 * 生产单的异常对话框（docs/api/05 §1.4、§3.5、§4）：上报、关闭。电脑详情页用；手机页的表单另写在 mobile/detail.vue。
 *
 *   <ProdIssueDialogs v-model:report-visible="a" v-model:close-visible="b" :order-id="id"
 *                     :base-due-date="d.expectDate ?? d.dueDate" :closing-issue="issue" @updated="refresh" />
 *
 * - 责任方：我方 / 工厂 / 供应商 / 待定（发 null），默认我方；"客户"是贸易代工的，TRADE 一期按关做（02 §4.7），不列
 * - 照片必传；勾了"影响交期"才出预计新交期，默认现在的交期 + 5 天
 * - 关闭：处理措施必填，责任方带上报时的
 */
const reportVisible = defineModel<boolean>('reportVisible', { required: true })
const closeVisible = defineModel<boolean>('closeVisible', { required: true })

const props = defineProps<{
  orderId: number
  /** 预计交期 ?? 交期：预计新交期的默认值从它往后推 */
  baseDueDate: string
  closingIssue: ProductionIssue | null
}>()

const emit = defineEmits<{
  updated: []
}>()

const PARTY_OPTIONS: { label: string; value: '' | ResponsibleParty }[] = [
  ...(['SELF', 'FACTORY', 'SUPPLIER'] as ResponsibleParty[]).map((value) => ({ value, label: RESPONSIBLE_PARTY[value] })),
  { label: '待定', value: '' },
]

// ---------- 上报 ----------
const submitting = ref(false)
const issueForm = reactive({
  issueType: '',
  responsibleParty: 'SELF' as '' | ResponsibleParty,
  description: '',
  photos: [] as FileVO[],
  impactDelivery: false,
  newExpectDate: '',
})
const reportGuard = useUnsavedChanges(issueForm, reportVisible)

watch(reportVisible, (on) => {
  if (!on) return
  Object.assign(issueForm, {
    issueType: '',
    responsibleParty: 'SELF',
    description: '',
    photos: [],
    impactDelivery: false,
    newExpectDate: addDaysStr(props.baseDueDate, 5),
  })
  reportGuard.markSaved()
})

async function submitIssue() {
  if (!issueForm.issueType) return void ElMessage.warning('选异常类型')
  if (!issueForm.description.trim()) return void ElMessage.warning('写一下怎么回事')
  if (!issueForm.photos.length) return void ElMessage.warning('至少传一张照片')
  if (issueForm.impactDelivery && !issueForm.newExpectDate) return void ElMessage.warning('填预计新交期')
  submitting.value = true
  try {
    const res = await productionApi.reportIssue(props.orderId, {
      issueType: issueForm.issueType,
      description: issueForm.description.trim(),
      photos: issueForm.photos,
      impactDelivery: issueForm.impactDelivery,
      newExpectDate: issueForm.impactDelivery ? issueForm.newExpectDate : null,
      responsibleParty: issueForm.responsibleParty || null,
    })
    ElMessage.success(`${res.no} 报上去了`)
    reportGuard.markSaved()
    reportVisible.value = false
    emit('updated')
  } finally {
    submitting.value = false
  }
}

// ---------- 关闭 ----------
const closing = ref(false)
const closeForm = reactive({
  measure: '',
  responsibleParty: '' as '' | ResponsibleParty,
})
const closeGuard = useUnsavedChanges(closeForm, closeVisible)

watch(closeVisible, (on) => {
  if (!on) return
  closeForm.measure = ''
  const party = props.closingIssue?.responsibleParty ?? null
  // 上报时选了"客户"（以后 TRADE 开了才会有）照原样带上
  closeForm.responsibleParty = party ?? ''
  closeGuard.markSaved()
})

const closePartyOptions = () => {
  const p = closeForm.responsibleParty
  return p && !PARTY_OPTIONS.some((o) => o.value === p) ? [...PARTY_OPTIONS, { label: RESPONSIBLE_PARTY[p], value: p }] : PARTY_OPTIONS
}

async function confirmClose() {
  const issue = props.closingIssue
  if (!issue) return
  if (!closeForm.measure.trim()) return void ElMessage.warning('写处理措施')
  closing.value = true
  try {
    await productionApi.closeIssue(props.orderId, issue.id, {
      measure: closeForm.measure.trim(),
      responsibleParty: closeForm.responsibleParty || null,
    })
    ElMessage.success(`${issue.no} 关了`)
    closeGuard.markSaved()
    closeVisible.value = false
    emit('updated')
  } finally {
    closing.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="reportVisible"
    title="上报异常"
    width="540px"
    :close-on-click-modal="false"
    :before-close="reportGuard.beforeClose"
  >
    <el-form label-position="top" class="form-grid">
      <el-form-item label="类型" required>
        <DictSelect v-model="issueForm.issueType" type="issue_type" />
      </el-form-item>
      <el-form-item label="责任方">
        <el-select v-model="issueForm.responsibleParty" filterable>
          <el-option v-for="p in PARTY_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述" required class="span-all">
        <el-input v-model="issueForm.description" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="300" />
      </el-form-item>
      <el-form-item required class="span-all">
        <template #label>照片 <span class="photo-hint">问题部位、到货短缺或现场，对责任时要用</span></template>
        <ImageUpload v-model="issueForm.photos" :max="9" />
      </el-form-item>
      <el-form-item class="span-all">
        <el-checkbox v-model="issueForm.impactDelivery">影响交期</el-checkbox>
      </el-form-item>
      <el-form-item v-if="issueForm.impactDelivery" label="预计新交期" required>
        <el-date-picker v-model="issueForm.newExpectDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reportVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitIssue">上报</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="closeVisible"
    :title="`关闭 ${closingIssue?.no ?? ''}`"
    width="480px"
    :close-on-click-modal="false"
    :before-close="closeGuard.beforeClose"
  >
    <el-form label-position="top">
      <el-form-item label="处理措施" required>
        <el-input v-model="closeForm.measure" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" maxlength="250" />
      </el-form-item>
      <el-form-item label="责任方">
        <el-select v-model="closeForm.responsibleParty" filterable class="w-full">
          <el-option v-for="p in closePartyOptions()" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="closeVisible = false">取消</el-button>
      <el-button type="primary" :loading="closing" @click="confirmClose">关闭异常</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor),
.w-full {
  width: 100%;
}

.span-all {
  grid-column: 1 / -1;
}

.photo-hint {
  margin-left: 6px;
  font-size: 12px;
  font-weight: normal;
  color: var(--lm-danger-text);
}
</style>
