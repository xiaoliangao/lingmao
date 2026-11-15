<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { supplierApi } from '@/api/supplier'
import { fileApi } from '@/api/system'
import type { CertForm, SupplierCert } from '@/types/supplier'
import { MAX_UPLOAD_SIZE } from '@/utils/request'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import DictSelect from '@/components/DictSelect.vue'

/**
 * 加一份资质证照、换一份（RFC 004 §2.2）。文件是照片、扫描件或 PDF，一份证照一个文件。
 * 有效期不填 = 长期有效。准入审批中的供应商不能动资质，后端会拦
 *
 *   certRef.value?.open(supplierId)          加一份
 *   certRef.value?.open(supplierId, cert)    换
 *   <CertDialog ref="certRef" @saved="reloadCerts" />
 */
const emit = defineEmits<{ saved: [] }>()

const MAX_MB = MAX_UPLOAD_SIZE / 1024 / 1024

const visible = ref(false)
const saving = ref(false)
const uploading = ref(false)
const supplierId = ref(0)
const editing = ref<SupplierCert | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<CertForm>({ certType: '', fileUrl: '', fileName: '', validUntil: null, remark: null })
const guard = useUnsavedChanges(form, visible)

const rules: FormRules = {
  certType: [{ required: true, message: '请选证照类型', trigger: 'change' }],
  fileUrl: [{ required: true, message: '请上传照片或扫描件', trigger: 'change' }],
}

function open(id: number, cert?: SupplierCert) {
  supplierId.value = id
  editing.value = cert ?? null
  Object.assign(form, cert
    ? { certType: cert.certType, fileUrl: cert.fileUrl, fileName: cert.fileName, validUntil: cert.validUntil, remark: cert.remark }
    : { certType: 'LICENSE', fileUrl: '', fileName: '', validUntil: null, remark: null })
  visible.value = true
}

function beforeUpload(file: File) {
  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error(`文件太大，最大 ${MAX_MB}MB`)
    return false
  }
  return true
}

async function upload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const uploaded = await fileApi.upload(options.file)
    form.fileUrl = uploaded.url
    form.fileName = uploaded.name
    formRef.value?.validateField('fileUrl').catch(() => undefined)
  } finally {
    uploading.value = false
  }
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const data: CertForm = { ...form, remark: form.remark?.trim() || null }
    if (editing.value) {
      await supplierApi.updateCert(supplierId.value, editing.value.id, data)
    } else {
      await supplierApi.addCert(supplierId.value, data)
    }
    ElMessage.success(editing.value ? '证照已换' : '证照已加上')
    visible.value = false
    emit('saved')
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editing ? '换证照' : '加一份证照'"
    width="min(480px, 94vw)"
    :before-close="guard.beforeClose"
  >
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item label="类型" prop="certType">
        <DictSelect v-model="form.certType" type="cert_type" />
      </el-form-item>
      <el-form-item label="有效期至">
        <el-date-picker
          v-model="form.validUntil"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="长期有效不用填"
          clearable
          class="date"
        />
      </el-form-item>
      <el-form-item label="照片或扫描件" prop="fileUrl" class="full">
        <div class="file">
          <el-upload
            :show-file-list="false"
            :http-request="upload"
            :before-upload="beforeUpload"
            accept="image/*,.pdf"
          >
            <el-button :loading="uploading" icon="Upload">{{ form.fileUrl ? '换一个文件' : '选文件' }}</el-button>
          </el-upload>
          <a v-if="form.fileUrl" :href="form.fileUrl" target="_blank" rel="noopener" class="name">{{ form.fileName }}</a>
          <span v-else class="muted">照片、扫描件、PDF 都行，最大 {{ MAX_MB }}MB</span>
        </div>
      </el-form-item>
      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" maxlength="255" placeholder="如：年检后换新" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="uploading" @click="save">{{ editing ? '保存' : '加上' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.date {
  width: 100%;
}

.file {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.name {
  min-width: 0;
  overflow: hidden;
  color: var(--lm-accent);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
