<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, type InputInstance, type UploadRequestOptions } from 'element-plus'
import { fileApi } from '@/api/system'
import { craftStandardApi } from '@/api/techpack'
import DictSelect from '@/components/DictSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useLatestRequest } from '@/composables/useLatestRequest'
import type { FileVO } from '@/types/system'
import { CRAFT_GROUPS, CRAFT_SECTION_LABEL, type CraftSection, type CraftStandard, type SizePointStd } from '@/types/techpack'
import { formatFileSize } from '@/utils/format'
import { MAX_UPLOAD_SIZE } from '@/utils/request'
import { confirmRemove, orNull } from '@/views/basic/common'

/**
 * 工艺标准（docs/api/01 §3.14、§4.8，原型 /settings 的"工艺标准"）：公司通用的一份，从右边拉出来的抽屉。
 * 入口在资料包页签：版本条上的"工艺标准"、还没有资料包时的空状态、导出 PDF 对话框里的"看附页文件"。三个页签：
 * - 默认文字：工艺指示九段的默认文字、默认包装方式，只影响以后新建的资料包
 * - 测量方法：尺寸部位的默认测量方法；加部位以后只把新部位并进来，没存的测量方法留着
 * - 附页文件：导出工艺单勾"附工艺标准"时按这个顺序附在最后，名称就是附页标题。改了马上存（整组替换）
 *
 *   <CraftStandardDrawer ref="craftStd" />
 *   craftStd.value?.open()          打开，停在"默认文字"
 *   craftStd.value?.open('files')   打开，停在"附页文件"
 *
 * 每次打开都重取。默认文字、测量方法共用底栏一个"保存"，哪块改了存哪块；两块各记各的"没保存"（useUnsavedChanges 两份），
 * 关抽屉时哪块有修改都先问。没有 editable 的整个只读，没有底栏
 */
type StdTab = 'texts' | 'points' | 'files'

const visible = ref(false)
const tab = ref<StdTab>('texts')
const loading = ref(false)
const editable = ref(false)

// ---------- 文件 ----------
const files = ref<FileVO[]>([])
const uploading = ref(0)

// ---------- 默认文字 ----------
const EMPTY_TEXTS: Record<CraftSection, string> = { CUTTING: '', SEWING: '', PART: '', QUALITY: '', BUTTON: '', IRONING: '', LABEL: '', PACKING: '', NOTE: '' }
const textForm = reactive({ packingMode: null as string | null, texts: { ...EMPTY_TEXTS } })
const textGuard = useUnsavedChanges(() => (editable.value ? textForm : null), visible, { ready: () => !loading.value })

// ---------- 部位 ----------
const points = ref<SizePointStd[]>([])
const pointGuard = useUnsavedChanges(() => (editable.value ? points.value.map((p) => [p.value, p.method ?? '']) : null), visible, { ready: () => !loading.value, restore(value) {
  if (!Array.isArray(value)) return
  for (const saved of value) {
    if (!Array.isArray(saved) || typeof saved[1] !== 'string') continue
    const point = points.value.find((row) => row.value === saved[0])
    if (point) point.method = saved[1]
  }
} })
const newPoint = reactive({ label: '', method: '' })
const addingPoint = ref(false)

const saving = ref(false)
const dirty = computed(() => textGuard.dirty.value || pointGuard.dirty.value)

/** 底栏左边一句：哪几块没保存；都存了、又在附页文件这页时，说一声文件不用点保存 */
const footNote = computed(() => {
  const parts = [textGuard.dirty.value ? '默认文字' : '', pointGuard.dirty.value ? '测量方法' : ''].filter(Boolean)
  if (parts.length) return `${parts.join('、')}改了还没保存`
  return tab.value === 'files' ? '附页文件改了马上就存，不用点保存' : ''
})

function applyFiles(std: CraftStandard) {
  files.value = std.files.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size }))
}

const loadRequest = useLatestRequest()
watch(visible, (open) => { if (!open) loadRequest.cancel() })
async function load() {
  const read = loadRequest.begin()
  loading.value = true
  try {
    const std = await read.run(() => craftStandardApi.get())
    if (!read.current()) return
    editable.value = std.editable
    applyFiles(std)
    textForm.packingMode = std.packingMode
    textForm.texts = { ...EMPTY_TEXTS, ...std.texts }
    points.value = std.points.map((p) => ({ ...p, method: p.method ?? '' }))
    textGuard.markSaved()
    pointGuard.markSaved()
  } finally {
    if (read.current()) loading.value = false
  }
}

function open(to: StdTab = 'texts') {
  tab.value = to
  renaming.value = null
  newPoint.label = ''
  newPoint.method = ''
  visible.value = true
  void load()
}

defineExpose({ open })

/** el-drawer 的 before-close：默认文字、测量方法哪块有修改都先问（先看默认文字，没改再交给测量方法那份，它没改就直接关） */
function beforeClose(done: () => void) {
  ;(textGuard.dirty.value ? textGuard : pointGuard).beforeClose(done)
}

/** 底栏的"保存"：哪块改了存哪块。先存的成功、后存的失败时，先存的那块照样算存好了 */
async function save() {
  saving.value = true
  try {
    const saved: string[] = []
    if (textGuard.dirty.value) {
      const texts = Object.fromEntries(Object.entries(textForm.texts).map(([k, v]) => [k, orNull(v)])) as Record<CraftSection, string | null>
      await craftStandardApi.saveTexts({ texts, packingMode: textForm.packingMode })
      textGuard.markSaved()
      saved.push('默认文字')
    }
    if (pointGuard.dirty.value) {
      await craftStandardApi.saveMethods(points.value.map((p) => ({ value: p.value, method: orNull(p.method) })))
      pointGuard.markSaved()
      saved.push('测量方法')
    }
    ElMessage.success(`${saved.join('、')}存好了`)
  } finally {
    saving.value = false
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

const pointRowClass = ({ row }: { row: SizePointStd }) => (row.status === 1 ? '' : 'off-row')

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

// 改名称：点名称后面的笔，名称变成输入框；回车、点别处存，Esc 不改（Esc 不往上传，免得把抽屉也关了）。按 url 认是哪个文件
const renaming = ref<string | null>(null)
const renameText = ref('')
const renameInput = ref<InputInstance>()

function startRename(url: string, name: string) {
  renaming.value = url
  renameText.value = name
  void nextTick(() => renameInput.value?.focus())
}

function commitRename() {
  const url = renaming.value
  if (!url) return
  renaming.value = null
  const name = renameText.value.trim()
  const file = files.value.find((f) => f.url === url)
  if (!name || !file || name === file.name) return
  void saveFiles((current) => current.map((f) => (f.url === url ? { ...f, name } : f)))
}

/** 回车存；输入法选字时的回车不算 */
function onRenameEnter(event: Event | KeyboardEvent) {
  if ((event as KeyboardEvent).isComposing) return
  commitRename()
}

function cancelRename() {
  renaming.value = null
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

/** 按地址认类型：名称是附页标题，改名时常把 .pdf 去掉。地址带签名参数（架构 §8），扩展名后面可能跟着 ? */
const fileType = (url: string) => (/\.pdf(?:[?#]|$)/i.test(url) ? 'PDF' : '图片')
</script>

<template>
  <el-drawer v-model="visible" size="720px" class="craft-std-drawer" append-to-body :before-close="beforeClose">
    <template #header="{ titleId, titleClass }">
      <div class="std-head">
        <h2 :id="titleId" :class="titleClass">工艺标准</h2>
        <p class="dim">公司通用，新建资料包时带进去；改了不影响已有的资料包</p>
      </div>
    </template>

    <div v-loading="loading" class="std-body">
      <el-tabs v-model="tab" class="std-tabs">
        <!-- 默认文字 -->
        <el-tab-pane label="默认文字" name="texts">
          <section v-for="grp in CRAFT_GROUPS" :key="grp.title" class="grp">
            <h4>{{ grp.title }}</h4>
            <div v-for="sec in grp.sections" :key="sec" class="sec">
              <div class="sec-head">
                <span class="lbl">{{ CRAFT_SECTION_LABEL[sec] }}</span>
                <label v-if="sec === 'PACKING'" class="pack-mode">
                  <span class="dim">默认包装方式</span>
                  <DictSelect v-model="textForm.packingMode" type="packing_mode" size="small" :disabled="!editable" clearable />
                </label>
              </div>
              <el-input v-if="editable" v-model="textForm.texts[sec]" type="textarea" :autosize="{ minRows: 2, maxRows: 10 }" />
              <p v-else class="sec-text">{{ textForm.texts[sec] || '—' }}</p>
            </div>
          </section>
        </el-tab-pane>

        <!-- 测量方法 -->
        <el-tab-pane label="测量方法" name="points">
          <p class="tab-hint dim">尺寸指示里选了部位，带出这里的测量方法</p>
          <el-table :data="points" row-key="value" :row-class-name="pointRowClass" :empty-text="editable ? '还没有部位：在下面加' : '还没有部位'">
            <el-table-column label="部位" width="150">
              <template #default="{ row }">
                {{ row.label }}
                <StatusTag v-if="row.status !== 1" tone="off">停用</StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="默认测量方法">
              <template #default="{ row }">
                <el-input v-if="editable" v-model="row.method" size="small" maxlength="160" />
                <span v-else>{{ row.method || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="editable" class="add-row">
            <el-input v-model="newPoint.label" size="small" maxlength="40" placeholder="新部位，如：袖窿深" class="w-160" />
            <el-input v-model="newPoint.method" size="small" maxlength="160" placeholder="测量方法（选填）" class="grow" />
            <el-button size="small" icon="Plus" :loading="addingPoint" @click="addPoint">加部位</el-button>
          </div>
        </el-tab-pane>

        <!-- 附页文件 -->
        <el-tab-pane label="附页文件" name="files">
          <div class="tab-bar">
            <span class="dim">导出工艺单勾了「附工艺标准」，按这个顺序附在最后</span>
            <el-upload v-if="editable" :show-file-list="false" :http-request="handleUpload" :before-upload="beforeUpload" accept=".pdf,.jpg,.jpeg,.png" multiple>
              <el-button size="small" icon="Upload" :loading="uploading > 0">上传</el-button>
            </el-upload>
          </div>
          <el-table :data="files" row-key="url" empty-text="还没传文件，导出工艺单时就不带附页">
            <el-table-column label="#" width="44" class-name="num">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="名称（附页标题）" min-width="200">
              <template #default="{ row }">
                <el-input
                  v-if="renaming === row.url"
                  ref="renameInput"
                  v-model="renameText"
                  size="small"
                  maxlength="255"
                  @keydown.enter="onRenameEnter"
                  @keydown.esc.stop="cancelRename"
                  @blur="commitRename"
                />
                <div v-else class="file-name">
                  <span class="file-name-text">{{ row.name }}</span>
                  <el-button v-if="editable" link size="small" class="rename-btn" title="改名称" aria-label="改名称" @click="startRename(row.url, row.name)">
                    <el-icon><EditPen /></el-icon>
                  </el-button>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="60">
              <template #default="{ row }">{{ fileType(row.url) }}</template>
            </el-table-column>
            <el-table-column label="大小" width="80" class-name="num" align="right">
              <template #default="{ row }">{{ formatFileSize(row.size) }}</template>
            </el-table-column>
            <el-table-column label="" :width="editable ? 160 : 60" align="right">
              <template #default="{ row, $index }">
                <a :href="row.url" target="_blank" rel="noopener" class="view-link">查看</a>
                <template v-if="editable">
                  <el-button link size="small" :disabled="$index === 0" title="上移" aria-label="上移" @click="moveFile($index, -1)"><el-icon><Top /></el-icon></el-button>
                  <el-button link size="small" :disabled="$index === files.length - 1" title="下移" aria-label="下移" @click="moveFile($index, 1)"><el-icon><Bottom /></el-icon></el-button>
                  <el-button link type="danger" size="small" @click="removeFile($index)">删除</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template v-if="editable" #footer>
      <span class="foot-note dim">{{ footNote }}</span>
      <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.std-head {
  min-width: 0;
  padding: 10px 0;
}

.std-head h2 {
  margin: 0;
}

.std-head p {
  margin: 2px 0 0;
  font-size: 12px;
}

.std-body {
  min-height: 240px;
}

/* 页签那一行吊在顶上：往下翻默认文字时照样能换页签（抽屉正文顶上不留白，见下面不带 scoped 的那段） */
.std-tabs > :deep(.el-tabs__header) {
  position: sticky;
  top: 0;
  z-index: 2;
  padding-top: 6px;
  margin-bottom: 14px;
  background: var(--lm-paper);
}

.grp + .grp {
  margin-top: 18px;
}

.grp h4 {
  margin: 0 0 8px;
  font-size: 13.5px;
  font-weight: 600;
}

.sec {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sec + .sec {
  margin-top: 12px;
}

.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 24px;
}

.lbl {
  font-size: 13px;
  font-weight: 500;
}

.pack-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}

.pack-mode :deep(.el-select) {
  width: 120px;
}

.sec-text {
  margin: 0;
  font-size: 13px;
  white-space: pre-wrap;
}

.tab-hint {
  margin: 0 0 10px;
  font-size: 12.5px;
}

.tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 12.5px;
}

.add-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 0 0;
}

.w-160 {
  flex: none;
  width: 160px;
}

.grow {
  flex: 1;
  min-width: 160px;
}

.file-name {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.file-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rename-btn {
  flex: none;
  color: var(--lm-muted);
}

.view-link {
  margin-right: 8px;
  font-size: 12.5px;
}

.foot-note {
  margin-right: auto;
  font-size: 12.5px;
}

:deep(.off-row) {
  color: var(--lm-faint);
}
</style>

<style>
/* 抽屉挂在 body 下，scoped 的样式够不着：正文顶上不留白，页签那一行才能贴着标题行吊住 */
.craft-std-drawer .el-drawer__body {
  padding-top: 0;
}
</style>
