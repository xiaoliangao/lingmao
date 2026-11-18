<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { provideUploadScope, useUploadScope } from '@/composables/usePendingUploads'
import { fileApi } from '@/api/system'
import type { FileVO } from '@/types/system'
import { formatFileSize } from '@/utils/format'
import { MAX_DESIGN_SOURCE_SIZE, MAX_UPLOAD_SIZE } from '@/utils/request'

/**
 * 款式的设计附件（图稿、设计说明）：一行一个文件，点名字打开。v-model 是 FileVO 数组，整组替换（RFC 005 §7）。
 * 能传的类型照后端 FileServiceImpl：图片、PDF、Word、Excel 最大 10MB，设计源文件 AI、PSD 最大 100MB。
 * 浏览器打不开的（AI、PSD、Office）点了直接下载，存下来还是原来的文件名
 *
 *   <DesignFiles v-model="form.files" />
 *   <DesignFiles :model-value="detail.files" :disabled="!canEdit" @update:model-value="saveFiles" />
 */
const props = withDefaults(defineProps<{ max?: number; disabled?: boolean; save?: (files: FileVO[]) => Promise<void> }>(), { max: 20, disabled: false })
const model = defineModel<FileVO[]>({ default: () => [] })

const uploads = props.save ? provideUploadScope() : useUploadScope()
let mounted = true
onBeforeUnmount(() => { mounted = false })
const ACCEPT = 'image/*,.pdf,.doc,.docx,.xls,.xlsx,.ai,.psd'
const isSource = (name: string) => /\.(ai|psd)$/i.test(name)
/** 图片、PDF 在新页签里看；别的下载 */
const viewable = (name: string) => /\.(jpe?g|png|gif|webp|pdf)$/i.test(name)
const uploading = ref(0)
const full = computed(() => model.value.length + uploading.value >= props.max)

function beforeUpload(file: File) {
  const limit = isSource(file.name) ? MAX_DESIGN_SOURCE_SIZE : MAX_UPLOAD_SIZE
  if (file.size > limit) {
    ElMessage.error(`${file.name} 太大，${isSource(file.name) ? 'AI、PSD' : '图片、PDF、Word、Excel'} 最大 ${limit / 1024 / 1024}MB`)
    return false
  }
  if (full.value) {
    ElMessage.warning(`最多 ${props.max} 个附件，${file.name} 没有添加`)
    return false
  }
  return true
}

let queue: Promise<void> = Promise.resolve()
function apply(change: (files: FileVO[]) => FileVO[], current: () => boolean) {
  const work = queue.then(async () => {
    if (!mounted || !current()) return
    const next = change(model.value)
    if (props.save) await props.save(next)
    else model.value = next
  })
  queue = work.catch(() => {})
  return work
}
async function upload(options: UploadRequestOptions) {
  const task = uploads.begin()
  uploading.value++
  let ok = false
  try {
    const file = await fileApi.upload(options.file)
    await apply((files) => [...files, file], task.current)
    ok = task.current() && mounted
  } finally {
    uploading.value--
    task.finish(ok)
  }
}
async function remove(index: number) {
  const file = model.value[index]
  if (!file) return
  const task = uploads.begin()
  try { await apply((files) => files.filter((f) => f.url !== file.url), task.current); task.finish(true) }
  catch { task.finish(false) }
}
</script>

<template>
  <div class="design-files">
    <ul v-if="model.length" class="list">
      <li v-for="(f, i) in model" :key="f.url">
        <el-icon class="icon"><Document /></el-icon>
        <a :href="f.url" target="_blank" rel="noopener" :download="viewable(f.name) ? undefined : f.name" class="name">{{ f.name }}</a>
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
        <span class="dim tip">图片、PDF、Word、Excel 最大 10MB，AI、PSD 最大 100MB，最多 {{ max }} 个</span>
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
