<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { fileApi } from '@/api/system'
import { techPackApi } from '@/api/techpack'
import ExcelPreviewDialog from '@/components/ExcelPreviewDialog.vue'
import { useUploadScope } from '@/composables/usePendingUploads'
import { PACK_FILE_GROUPS, PACK_FILE_MAX, type PackFileCategory, type PackFileForm } from '@/types/techpack'
import { formatFileSize } from '@/utils/format'
import { MAX_DESIGN_SOURCE_SIZE, MAX_UPLOAD_SIZE } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'

/**
 * 资料包的"工艺单"页签（docs/api/01 §4.3，RFC 011 §2，原型 TpFiles）：三组文件——工艺单、纸样和其他、导入的 BOM 表。
 * v-model 是资料包表单里的 files（整版保存时一起发），这里只改数组：传上去的文件先放进来，点资料包的"保存"才挂到版本上。
 *
 *   <TechPackFiles v-model="draft.files" :pack-id="detail.id" :disabled="!editable" />
 *
 * - 每组收的类型照 PACK_FILE_GROUPS（后端再查一遍）；prj 100MB，别的 10MB；一组最多 20 个
 * - Excel 点"预览"在页面里看（要已经存上、有 id 的）；图片在新页签里看；PDF、prj 下载
 * - "导入的 BOM 表"一组不能传，从 BOM 表导入时自动加；没有就不出这一块
 * - 上传登记在资料包页签的未保存跟踪里（useUploadScope）：传到一半保存会等它传完
 */
const props = defineProps<{ packId: string; disabled?: boolean }>()
const model = defineModel<PackFileForm[]>({ default: () => [] })

const uploads = useUploadScope()
let mounted = true
onBeforeUnmount(() => { mounted = false })

const uploading = ref<Record<string, number>>({})
const ext = (name: string) => name.slice(name.lastIndexOf('.') + 1).toLowerCase()
const isExcel = (name: string) => ['xls', 'xlsx'].includes(ext(name))
const isImage = (name: string) => ['jpg', 'jpeg', 'png', 'webp'].includes(ext(name))

const groups = computed(() =>
  PACK_FILE_GROUPS.map((g) => ({ ...g, files: model.value.filter((f) => f.category === g.category) }))
    .filter((g) => g.category !== 'BOM_SOURCE' || g.files.length),
)

function beforeUpload(category: PackFileCategory, file: File) {
  const group = PACK_FILE_GROUPS.find((g) => g.category === category)!
  if (!group.types.includes(ext(file.name))) {
    ElMessage.error(`${group.label}不收 .${ext(file.name)} 文件：${file.name}。能传 ${group.types.join('、')}`)
    return false
  }
  const limit = ext(file.name) === 'prj' ? MAX_DESIGN_SOURCE_SIZE : MAX_UPLOAD_SIZE
  if (file.size > limit) {
    ElMessage.error(`${file.name} 太大，${ext(file.name) === 'prj' ? '纸样文件' : '图片、PDF、Excel'} 最大 ${limit / 1024 / 1024}MB`)
    return false
  }
  const count = model.value.filter((f) => f.category === category).length + (uploading.value[category] ?? 0)
  if (count >= PACK_FILE_MAX) {
    ElMessage.warning(`${group.label}最多 ${PACK_FILE_MAX} 个，${file.name} 没有添加`)
    return false
  }
  return true
}

async function upload(category: PackFileCategory, options: UploadRequestOptions) {
  const task = uploads.begin()
  uploading.value[category] = (uploading.value[category] ?? 0) + 1
  let ok = false
  try {
    const file = await fileApi.upload(options.file)
    if (mounted && task.current()) {
      // 插在这一组的最后，别的组不动
      const next = [...model.value]
      const lastIdx = next.map((f) => f.category).lastIndexOf(category)
      next.splice(lastIdx < 0 ? next.length : lastIdx + 1, 0, { category, ...file })
      model.value = next
      ok = true
    }
  } finally {
    uploading.value[category]!--
    task.finish(ok)
  }
}

/** 组里上移、下移一格 */
function move(file: PackFileForm, step: -1 | 1) {
  const same = model.value.filter((f) => f.category === file.category)
  const target = same[same.indexOf(file) + step]
  if (!target) return
  const next = [...model.value]
  const a = next.indexOf(file)
  const b = next.indexOf(target)
  ;[next[a], next[b]] = [next[b]!, next[a]!]
  model.value = next
}

async function remove(file: PackFileForm) {
  await confirmRemove(`删除 ${file.name}`, `从这一版资料包上拿掉 ${file.name}。点"保存"后生效；已经发出去的不受影响。`)
  model.value = model.value.filter((f) => f !== file)
}

const previewing = ref(false)
const previewFile = ref<PackFileForm | null>(null)
function preview(file: PackFileForm) {
  previewFile.value = file
  previewing.value = true
}
const loadPreview = () => techPackApi.preview(props.packId, previewFile.value!.id!)
</script>

<template>
  <div class="pack-files">
    <section v-for="g in groups" :key="g.category" class="group">
      <div class="group-head">
        <strong>{{ g.label }}</strong>
        <span class="dim">{{ g.files.length }} 个</span>
        <span class="gap" />
        <el-upload
          v-if="!disabled && g.category !== 'BOM_SOURCE'"
          :show-file-list="false"
          :http-request="(o: UploadRequestOptions) => upload(g.category, o)"
          :before-upload="(f: File) => beforeUpload(g.category, f)"
          :accept="g.types.map((t) => '.' + t).join(',')"
          multiple
        >
          <el-button size="small" icon="Upload" :loading="(uploading[g.category] ?? 0) > 0">上传</el-button>
        </el-upload>
      </div>
      <el-upload
        v-if="!disabled && g.category === 'CRAFT_SHEET' && !g.files.length"
        drag
        :show-file-list="false"
        :http-request="(o: UploadRequestOptions) => upload(g.category, o)"
        :before-upload="(f: File) => beforeUpload(g.category, f)"
        :accept="g.types.map((t) => '.' + t).join(',')"
        multiple
        class="drop"
      >
        <span class="dim">还没有工艺单：把甲方的工艺单 Excel 拖到这里，或者点这里选</span>
      </el-upload>
      <p v-else-if="!g.files.length" class="dim empty">{{ disabled ? `这一版没有${g.label}` : `还没有${g.label}` }}</p>
      <ul v-else class="list">
        <li v-for="(f, i) in g.files" :key="f.url">
          <el-icon class="icon"><Document /></el-icon>
          <span class="name" :title="f.name">{{ f.name }}</span>
          <span class="dim size">{{ formatFileSize(f.size) }}</span>
          <span class="acts">
            <template v-if="isExcel(f.name)">
              <el-button v-if="f.id" link type="primary" size="small" @click="preview(f)">预览</el-button>
              <span v-else class="dim small">保存后能预览</span>
            </template>
            <a v-else-if="isImage(f.name)" :href="f.url" target="_blank" rel="noopener" class="link">看图</a>
            <a :href="f.url" :download="f.name" class="link">下载</a>
            <template v-if="!disabled">
              <el-button link size="small" :disabled="i === 0" aria-label="上移" @click="move(f, -1)"><el-icon><Top /></el-icon></el-button>
              <el-button link size="small" :disabled="i === g.files.length - 1" aria-label="下移" @click="move(f, 1)"><el-icon><Bottom /></el-icon></el-button>
              <el-button link type="danger" size="small" @click="remove(f)">删除</el-button>
            </template>
          </span>
        </li>
      </ul>
      <p v-if="!disabled && g.category !== 'BOM_SOURCE'" class="dim tip">能传 {{ g.types.join('、') }}{{ g.types.includes('prj') ? '（prj 最大 100MB）' : '' }}，单个最大 10MB，最多 {{ PACK_FILE_MAX }} 个</p>
    </section>

    <ExcelPreviewDialog v-if="previewFile" v-model="previewing" :title="previewFile.name" :load="loadPreview" :download-url="previewFile.url" />
  </div>
</template>

<style scoped>
.pack-files {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.group {
  padding: 12px;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}

.gap {
  flex: 1;
}

.drop :deep(.el-upload-dragger) {
  padding: 18px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.list li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 4px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--lm-line-soft);
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

.acts {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.link {
  font-size: 12.5px;
  color: var(--lm-accent);
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}

.empty,
.tip {
  margin: 4px 0 0;
  font-size: 12.5px;
}

.small {
  font-size: 12px;
}
</style>
