<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { provideUploadScope, useUploadScope } from '@/composables/usePendingUploads'
import { fileApi } from '@/api/system'
import type { Attachment } from '@/types/biz'
import type { FileVO } from '@/types/system'
import { formatFileSize } from '@/utils/format'
import { MAX_UPLOAD_SIZE } from '@/utils/request'

/**
 * 单据的附件块（docs/api/README.md §4.3）：列出附件、上传、删除。两种用法：
 *
 *   已经存在的单据——接口整组替换（PUT …/attachments），改完马上存：
 *     const loadFiles = () => purchaseApi.attachments(id)
 *     const saveFiles = (files: FileVO[]) => purchaseApi.replaceAttachments(id, files)
 *     <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="!canEdit" />
 *
 *   新建表单里——还没有单据 id，只收在表单里，跟着新建请求一起发：
 *     <DocAttachments v-model="form.files" />
 *
 * load、save 在 script 里定义好再传（模板里写箭头函数引用 import 的东西，vue-tsc 会报错，见 DocLogList）。
 * 一次选好几个文件：各自上传，等这一批都传完再一起存一次；存的时候又来了新的，排在后面接着存，不会互相覆盖
 */
const props = withDefaults(
  defineProps<{
    load?: () => Promise<Attachment[]>
    save?: (files: FileVO[]) => Promise<Attachment[]>
    modelValue?: FileVO[]
    disabled?: boolean
    max?: number
  }>(),
  { disabled: false, max: 20 },
)

const emit = defineEmits<{
  'update:modelValue': [files: FileVO[]]
  changed: []
}>()

const uploads = props.save ? provideUploadScope() : useUploadScope()
let mounted = true
onBeforeUnmount(() => { mounted = false })
const remoteFiles = ref<Attachment[]>([])
const uploading = ref(0)
const loading = ref(false)

const isControlled = computed(() => props.modelValue !== undefined)
const list = computed<FileVO[]>(() => (isControlled.value ? (props.modelValue ?? []) : remoteFiles.value))
const full = computed(() => list.value.length + uploading.value >= props.max)
const viewable = (name: string) => /\.(jpe?g|png|gif|webp|pdf)$/i.test(name)

const reloadRequest = useLatestRequest()
async function reload() {
  const read = reloadRequest.begin()
  const load = props.load
  if (!load) return
  loading.value = true
  try {
    const fetched1 = await read.run(load)
    if (!read.current()) return
    remoteFiles.value = fetched1
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

onMounted(() => {
  if (props.load) void reload()
})

function beforeUpload(file: File) {
  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error(`${file.name} 超过 10MB 上限`)
    return false
  }
  if (full.value) {
    ElMessage.warning(`最多上传 ${props.max} 个附件`)
    return false
  }
  return true
}

async function applyList(next: FileVO[]) {
  if (isControlled.value) {
    emit('update:modelValue', next)
    return
  }
  if (props.save) {
    remoteFiles.value = await props.save(next)
    emit('changed')
  }
}

/**
 * 存附件排成一队：每次都拿上一次存完的列表再改，几个文件同时传完、一边删一边传，都不会拿旧列表把别的覆盖掉。
 * 一次出错不影响后面排着的
 */
let queue: Promise<void> = Promise.resolve()
function enqueue(change: (current: FileVO[]) => FileVO[]) {
  const work = queue.then(() => applyList(change(list.value)))
  queue = work.catch(() => {})
  return work
}

async function handleUpload(options: UploadRequestOptions) {
  const task = uploads.begin()
  uploading.value++
  let success = false
  try {
    const file = await fileApi.upload(options.file)
    if (!mounted || !task.current()) return
    await enqueue((current) => task.current() ? [...current, file] : current)
    success = task.current()
  } finally {
    uploading.value--
    task.finish(success)
  }
}

function removeAt(index: number) {
  const target = list.value[index]
  if (!target) return
  void enqueue((current) => current.filter((f) => f !== target && f.url !== target.url))
}

defineExpose({ reload })
</script>

<template>
  <div v-loading="loading" class="doc-files">
    <ul v-if="list.length" class="list">
      <li v-for="(f, i) in list" :key="`${f.url}-${i}`">
        <el-icon class="icon"><Document /></el-icon>
        <a :href="f.url" target="_blank" rel="noopener" :download="viewable(f.name) ? undefined : f.name" class="name">{{ f.name }}</a>
        <span class="dim size num">{{ formatFileSize(f.size) }}</span>
        <el-button v-if="!disabled" link type="danger" size="small" @click="removeAt(i)">删除</el-button>
      </li>
    </ul>
    <p v-else class="dim empty">暂无附件</p>

    <el-upload
      v-if="!disabled"
      :show-file-list="false"
      :http-request="handleUpload"
      :before-upload="beforeUpload"
      accept="image/*,.pdf,.doc,.docx,.xls,.xlsx,.csv"
      :disabled="full"
      multiple
    >
      <el-button size="small" icon="Upload" :loading="uploading > 0" :disabled="full">上传附件</el-button>
    </el-upload>
  </div>
</template>

<style scoped>
.doc-files {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.list li {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12.5px;
}

.icon {
  flex: none;
  color: var(--lm-muted);
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--lm-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name:hover {
  color: var(--lm-accent);
}

.size {
  flex: none;
  font-size: 11.5px;
}

.empty {
  margin: 0;
  font-size: 12.5px;
}
</style>
