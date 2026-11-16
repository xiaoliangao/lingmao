<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { fileApi } from '@/api/system'
import { craftStandardApi } from '@/api/techpack'
import DictSelect from '@/components/DictSelect.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { FileVO } from '@/types/system'
import { CRAFT_GROUPS, CRAFT_SECTION_LABEL, type CraftSection, type CraftStandard, type SizePointStd } from '@/types/techpack'
import { formatFileSize } from '@/utils/format'
import { MAX_UPLOAD_SIZE } from '@/utils/request'
import { confirmRemove, orNull } from '@/views/basic/common'

/**
 * 开发 › 工艺标准（/craft-standard，docs/api/01 §1.3、§3.14、§4.8，原型 /settings 的"工艺标准"）。从上到下三块：
 * - 工艺标准文件：导出工艺单勾"附工艺标准"时按这个顺序附在最后，名字就是附页标题。改了马上存（整组替换）
 * - 工艺指示默认文字、默认包装方式：只影响以后新建的资料包，一个"保存"
 * - 尺寸部位的默认测量方法：一个"保存"；加部位以后只把新部位并进来，没存的测量方法留着
 * 默认文字、测量方法各记各的"没保存"（useUnsavedChanges 两份），存一块不影响另一块。没有 editable 的整页只读
 */
const loading = ref(false)
const editable = ref(false)

// ---------- 文件 ----------
const files = ref<FileVO[]>([])
const uploading = ref(0)

// ---------- 默认文字 ----------
const EMPTY_TEXTS: Record<CraftSection, string> = { CUTTING: '', SEWING: '', PART: '', QUALITY: '', BUTTON: '', IRONING: '', LABEL: '', PACKING: '', NOTE: '' }
const textForm = reactive({ packingMode: null as string | null, texts: { ...EMPTY_TEXTS } })
const textGuard = useUnsavedChanges(() => (editable.value ? textForm : null))
const savingTexts = ref(false)

// ---------- 部位 ----------
const points = ref<SizePointStd[]>([])
const pointGuard = useUnsavedChanges(() => (editable.value ? points.value.map((p) => [p.value, p.method ?? '']) : null))
const savingPoints = ref(false)
const newPoint = reactive({ label: '', method: '' })
const addingPoint = ref(false)

function applyFiles(std: CraftStandard) {
  files.value = std.files.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size }))
}

async function load() {
  loading.value = true
  try {
    const std = await craftStandardApi.get()
    editable.value = std.editable
    applyFiles(std)
    textForm.packingMode = std.packingMode
    textForm.texts = { ...EMPTY_TEXTS, ...std.texts }
    points.value = std.points.map((p) => ({ ...p, method: p.method ?? '' }))
    textGuard.markSaved()
    pointGuard.markSaved()
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function saveTexts() {
  savingTexts.value = true
  try {
    const texts = Object.fromEntries(Object.entries(textForm.texts).map(([k, v]) => [k, orNull(v)])) as Record<CraftSection, string | null>
    await craftStandardApi.saveTexts({ texts, packingMode: textForm.packingMode })
    textGuard.markSaved()
    ElMessage.success('默认文字存好了，以后新建的资料包带这些')
  } finally {
    savingTexts.value = false
  }
}

async function savePoints() {
  savingPoints.value = true
  try {
    await craftStandardApi.saveMethods(points.value.map((p) => ({ value: p.value, method: orNull(p.method) })))
    pointGuard.markSaved()
    ElMessage.success('测量方法存好了')
  } finally {
    savingPoints.value = false
  }
}

/** 加部位：只把新部位并进表里，别的行没存的测量方法不动 */
async function addPoint() {
  const label = newPoint.label.trim()
  if (!label) {
    ElMessage.warning('写部位名称')
    return
  }
  addingPoint.value = true
  try {
    await craftStandardApi.addPoint(label, orNull(newPoint.method))
    const std = await craftStandardApi.get()
    const have = new Set(points.value.map((p) => p.value))
    const added = std.points.filter((p) => !have.has(p.value)).map((p) => ({ ...p, method: p.method ?? '' }))
    // 新部位是存好了的：并进来以后，"没保存"只算原来那些行改了的
    const dirtyBefore = pointGuard.dirty.value
    points.value.push(...added)
    if (!dirtyBefore) pointGuard.markSaved()
    newPoint.label = ''
    newPoint.method = ''
    ElMessage.success(`加了部位「${label}」`)
  } finally {
    addingPoint.value = false
  }
}

// ---------- 文件：改了马上存，排队存，几个文件同时传完也不互相覆盖 ----------
let queue: Promise<void> = Promise.resolve()
function saveFiles(change: (current: FileVO[]) => FileVO[]) {
  queue = queue
    .then(async () => {
      const saved = await craftStandardApi.replaceFiles(change(files.value))
      files.value = saved.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size }))
    })
    .catch(() => {})
  return queue
}

function beforeUpload(file: File) {
  if (file.size > MAX_UPLOAD_SIZE) {
    ElMessage.error(`${file.name} 超过 10MB`)
    return false
  }
  return true
}

const uploadedBatch: FileVO[] = []
async function handleUpload(options: UploadRequestOptions) {
  uploading.value++
  try {
    uploadedBatch.push(await fileApi.upload(options.file))
  } finally {
    uploading.value--
    if (uploading.value === 0 && uploadedBatch.length) {
      const batch = uploadedBatch.splice(0)
      await saveFiles((current) => [...current, ...batch])
    }
  }
}

function renameFile(index: number, name: string) {
  const trimmed = name.trim()
  if (!trimmed) return
  void saveFiles((current) => current.map((f, i) => (i === index ? { ...f, name: trimmed } : f)))
}

function moveFile(index: number, step: -1 | 1) {
  const target = index + step
  if (target < 0 || target >= files.value.length) return
  void saveFiles((current) => {
    const next = [...current]
    ;[next[index], next[target]] = [next[target]!, next[index]!]
    return next
  })
}

async function removeFile(index: number) {
  const file = files.value[index]
  if (!file) return
  await confirmRemove(`删除 ${file.name}`, '以后导出的工艺单不再附这个文件；已经发出去的不受影响。')
  await saveFiles((current) => current.filter((f) => f.url !== file.url))
}

const fileType = (name: string) => (/\.pdf$/i.test(name) ? 'PDF' : '图片')
</script>

<template>
  <div>
    <PageHeader />

    <div v-loading="loading" class="page stack">
      <section class="panel">
        <div class="panel-head">
          工艺标准文件
          <div class="acts">
            <el-upload v-if="editable" :show-file-list="false" :http-request="handleUpload" :before-upload="beforeUpload" accept=".pdf,.jpg,.jpeg,.png" multiple>
              <el-button size="small" icon="Upload" :loading="uploading > 0">上传</el-button>
            </el-upload>
          </div>
        </div>
        <el-table :data="files" empty-text="还没有工艺标准文件：导出工艺单时就不附">
          <el-table-column label="#" width="48" class-name="num">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column label="名称（附页标题）" min-width="240">
            <template #default="{ row, $index }">
              <el-input v-if="editable" :model-value="row.name" size="small" @change="(v: string) => renameFile($index, v)" />
              <a v-else :href="row.url" target="_blank" rel="noopener">{{ row.name }}</a>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="70">
            <template #default="{ row }">{{ fileType(row.name) }}</template>
          </el-table-column>
          <el-table-column label="大小" width="90" class-name="num" align="right">
            <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
          </el-table-column>
          <el-table-column label="" :width="editable ? 170 : 70" align="right">
            <template #default="{ row, $index }">
              <a :href="row.url" target="_blank" rel="noopener" class="view-link">查看</a>
              <template v-if="editable">
                <el-button link size="small" :disabled="$index === 0" aria-label="上移" @click="moveFile($index, -1)"><el-icon><Top /></el-icon></el-button>
                <el-button link size="small" :disabled="$index === files.length - 1" aria-label="下移" @click="moveFile($index, 1)"><el-icon><Bottom /></el-icon></el-button>
                <el-button link type="danger" size="small" @click="removeFile($index)">删除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-head">
          工艺指示默认文字
          <div class="acts">
            <el-button v-if="editable" size="small" type="primary" :loading="savingTexts" :disabled="!textGuard.dirty.value" @click="saveTexts">保存</el-button>
          </div>
        </div>
        <div class="panel-body">
          <div v-for="grp in CRAFT_GROUPS" :key="grp.title" class="grp">
            <h4>{{ grp.title }}</h4>
            <div class="sec-grid">
              <div v-for="sec in grp.sections" :key="sec" class="box">
                <div class="box-head">
                  <span class="lbl">{{ CRAFT_SECTION_LABEL[sec] }}</span>
                  <label v-if="sec === 'PACKING'" class="pack-row">
                    <span class="dim">默认包装方式</span>
                    <DictSelect v-model="textForm.packingMode" type="packing_mode" size="small" :disabled="!editable" clearable class="w-120" />
                  </label>
                </div>
                <el-input v-model="textForm.texts[sec]" type="textarea" :autosize="{ minRows: 3 }" :disabled="!editable" />
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          尺寸部位的测量方法
          <div class="acts">
            <el-button v-if="editable" size="small" type="primary" :loading="savingPoints" :disabled="!pointGuard.dirty.value" @click="savePoints">保存</el-button>
          </div>
        </div>
        <el-table :data="points" row-key="value" :row-class-name="({ row }) => (row.status === 1 ? '' : 'off-row')" empty-text="还没有部位">
          <el-table-column label="部位" prop="label" width="160" />
          <el-table-column label="" width="80">
            <template #default="{ row }">
              <StatusTag v-if="row.status !== 1" tone="off">停用</StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="测量方法">
            <template #default="{ row }">
              <el-input v-if="editable" v-model="row.method" size="small" maxlength="160" />
              <span v-else>{{ row.method || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="editable" class="add-row">
          <el-input v-model="newPoint.label" size="small" maxlength="40" placeholder="新部位，如：袖窿深" class="w-180" />
          <el-input v-model="newPoint.method" size="small" maxlength="160" placeholder="测量方法（选填）" />
          <el-button size="small" :loading="addingPoint" @click="addPoint">加部位</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grp {
  margin-bottom: 16px;
}

.grp h4 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
}

.sec-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.box-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.lbl {
  font-size: 13px;
  font-weight: 500;
}

.pack-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}

.w-120 {
  width: 120px;
}

.w-180 {
  flex: none;
  width: 180px;
}

.view-link {
  margin-right: 8px;
  font-size: 12.5px;
}

.add-row {
  display: flex;
  gap: 8px;
  padding: 10px var(--lm-table-edge, 16px);
  border-top: 1px solid var(--lm-line-soft);
}

:deep(.off-row) {
  color: var(--lm-faint);
}

@media (max-width: 900px) {
  .sec-grid {
    grid-template-columns: 1fr;
  }
}
</style>
