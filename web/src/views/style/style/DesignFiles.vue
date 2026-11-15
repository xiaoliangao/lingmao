<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { fileApi } from '@/api/system'
import type { FileVO } from '@/types/system'
import { formatFileSize } from '@/utils/format'
import { MAX_UPLOAD_SIZE } from '@/utils/request'

/**
 * 款式的设计附件（图稿、设计说明）：一行一个文件，点名字打开。v-model 是 FileVO 数组，整组替换（RFC 005 §7）。
 * 能传的类型照后端 FileServiceImpl：图片、PDF、Word、Excel；AI、PSD 传不了，先导出成 PDF
 *
 *   <DesignFiles v-model="form.files" />
 *   <DesignFiles :model-value="detail.files" :disabled="!canEdit" @update:model-value="saveFiles" />
 */
const props = withDefaults(defineProps<{ max?: number; disabled?: boolean }>(), { max: 20, disabled: false })
const model = defineModel<FileVO[]>({ default: () => [] })

const MAX_MB = MAX_UPLOAD_SIZE / 1024 / 1024
const ACCEPT = 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
const uploading = ref(0)
const full = computed(() => model.value.length + uploading.value >= props.max)

function beforeUpload(file: File) {
  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error(`${file.name} 太大，最大 ${MAX_MB}MB`)
    return false
  }
  if (full.value) {
    ElMessage.warning(`最多 ${props.max} 个附件，${file.name} 没有添加`)
    return false
  }
  return true
}

async function upload(options: UploadRequestOptions) {
  uploading.value++
  try {
    const uploaded = await fileApi.upload(options.file)
    model.value = [...model.value, uploaded]
  } finally {
    uploading.value--
  }
}

function remove(index: number) {
  model.value = model.value.filter((_, i) => i !== index)
}
</script>

<template>
  <div class="design-files">
    <ul v-if="model.length" class="list">
      <li v-for="(f, i) in model" :key="f.url">
        <el-icon class="icon"><Document /></el-icon>
        <a :href="f.url" target="_blank" rel="noopener" class="name">{{ f.name }}</a>
        <span class="dim size">{{ formatFileSize(f.size) }}</span>
        <el-button v-if="!disabled" link type="danger" size="small" :aria-label="`去掉 ${f.name}`" @click="remove(i)">去掉</el-button>
      </li>
    </ul>
    <p v-else-if="disabled" class="dim empty">还没有附件</p>
    <el-upload
      v-if="!disabled"
      :show-file-list="false"
      :http-request="upload"
      :before-upload="beforeUpload"
      :accept="ACCEPT"
      :disabled="full"
      multiple
    >
      <el-button size="small" icon="Upload" :loading="uploading > 0" :disabled="full">传附件</el-button>
      <template #tip>
        <span class="dim tip">图片、PDF、Word、Excel，每个最大 {{ MAX_MB }}MB，最多 {{ max }} 个</span>
      </template>
    </el-upload>
  </div>
</template>

<style scoped>
.design-files {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.list li {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
}

.icon {
  flex: none;
  color: var(--lm-muted);
}

.name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.size {
  flex: none;
  font-size: 12px;
}

.empty {
  margin: 0;
  font-size: 13px;
}

.tip {
  margin-left: 8px;
  font-size: 12px;
}
</style>
