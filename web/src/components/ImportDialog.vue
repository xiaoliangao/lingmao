<script setup lang="ts">
import { ref } from 'vue'
import type { UploadFile, UploadInstance } from 'element-plus'
import { ApiError, download, MAX_UPLOAD_SIZE, upload } from '@/utils/request'
import { ErrorCode } from '@/utils/errorCodes'
import { formatFileSize } from '@/utils/format'
import { withCompanyScope } from '@/utils/companyScope'
import BizErrorTable from './BizErrorTable.vue'

/** 一条提示，格式和 1003 的错误清单一样；sheet 只在一个文件几张表时有，row 是 0 说的是整张表 */
interface ImportNote {
  sheet?: string
  row: number
  column?: string
  message: string
}
interface ImportResult {
  total: number
  notes: ImportNote[]
}

/**
 * Excel 导入：下载模板 → 选 .xlsx → 上传 → 成功显示导入了多少条，并发出 success（页面拿它刷新列表）。
 * 后端有一行不对就整批不导（RFC 001 §9.3），返回 1003 和全部错误。清单就显示在这个对话框里，
 * 不弹全局的错误清单：改好文件可以接着在这里重新选、重新导。
 * 成功时后端返回 { total, notes }：notes 是不拦导入的提示（新建了哪些颜色……），不空就列在"导入完成"下面。
 *
 *   <ImportDialog v-model="importVisible" title="导入供应商" template-url="/api/suppliers/import-template"
 *                 import-url="/api/suppliers/import" @success="search" />
 *
 * 一批共用的信息（需求订单导入的主体、订单类型、业务员……）放 #head 插槽，显示成第一步；import-url 由页面按填的值拼好，
 * 没填好时 :ready="false"，"开始导入"灰掉：
 *
 *   <ImportDialog … :import-url="orderApi.importUrl(head)" :ready="headReady">
 *     <template #head><div class="form-grid">…</div></template>
 *   </ImportDialog>
 *
 * 导进一张单据里（成品其他入库的明细）：company-id 给这张单的主体，上传带上它（后端核对页面主体和单据一致）
 */
const props = withDefaults(defineProps<{ title: string; templateUrl: string; importUrl: string; ready?: boolean; companyId?: string }>(), {
  ready: true,
  companyId: undefined,
})
const emit = defineEmits<{ success: [total: number] }>()
const visible = defineModel<boolean>({ required: true })

const MAX_MB = MAX_UPLOAD_SIZE / 1024 / 1024

const uploadRef = ref<UploadInstance>()
const file = ref<File | null>(null)
const importing = ref(false)
const downloading = ref(false)
/** 选文件、导入时出的错，一句话 */
const errorMessage = ref('')
/** 1003 带回来的错误清单 */
const errors = ref<unknown[]>([])
/** 导入了多少条；null 表示还没导成 */
const imported = ref<number | null>(null)
/** 导入成功时带回来的提示 */
const notes = ref<ImportNote[]>([])

/** 每次打开都从头来 */
function reset() {
  file.value = null
  errorMessage.value = ''
  errors.value = []
  imported.value = null
  notes.value = []
}

async function downloadTemplate() {
  downloading.value = true
  try {
    await download(props.templateUrl, undefined, `${props.title}模板.xlsx`)
  } finally {
    downloading.value = false
  }
}

/** el-upload 只用来选文件（拖进来或点开），不让它自己传：要带 token，1003 要显示在这里 */
function pick(picked: UploadFile) {
  // 它自己的文件列表用不上，清掉，不然越选越多
  uploadRef.value?.clearFiles()
  const raw = picked.raw
  if (!raw) return
  errorMessage.value = ''
  file.value = null
  // 拖进来的文件不受 accept 限制，这里再查一遍
  if (!/\.xlsx$/i.test(raw.name)) {
    errorMessage.value = `${raw.name} 不是 .xlsx 文件。.xls、.csv 请先在 Excel 里另存为 .xlsx`
    return
  }
  if (raw.size > MAX_UPLOAD_SIZE) {
    errorMessage.value = `文件有 ${formatFileSize(raw.size)}，超过 ${MAX_MB}MB。请拆成几个文件分批导入`
    return
  }
  file.value = raw
}

async function submit() {
  if (!file.value) return
  importing.value = true
  errorMessage.value = ''
  errors.value = []
  try {
    const picked = file.value
    const send = () => upload<ImportResult>(props.importUrl, picked, { silent: true })
    const result = await (props.companyId ? withCompanyScope(props.companyId, send) : send())
    notes.value = result.notes ?? []
    imported.value = result.total
    emit('success', result.total)
  } catch (error) {
    if (!(error instanceof ApiError)) throw error
    errorMessage.value = error.message
    if (error.code === ErrorCode.IMPORT_INVALID && Array.isArray(error.data)) {
      errors.value = error.data
    }
    // 改好的文件要重新选：浏览器手里拿着的还是改之前的那份
    file.value = null
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="min(600px, 94vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!importing"
    :show-close="!importing"
    @open="reset"
  >
    <template v-if="imported !== null">
      <div class="done">
        <el-icon class="done-icon"><CircleCheckFilled /></el-icon>
        <p>导入完成，共 <span class="num">{{ imported }}</span> 条。</p>
      </div>
      <template v-if="notes.length">
        <p class="notes-title">有 <span class="num">{{ notes.length }}</span> 条提示，不影响导入，请看一下：</p>
        <BizErrorTable :items="notes" message-label="说明" />
      </template>
    </template>

    <template v-else>
      <ol class="steps">
        <li v-if="$slots.head">
          <p class="step-title">先填这一批共用的信息</p>
          <slot name="head" />
        </li>
        <li>
          <p class="step-title">下载模板，按模板填好</p>
          <p class="step-hint">带 * 的列必填；表头别改名，列的顺序可以调。模板里"填写说明"那一页有每一列的格式和例子。</p>
          <el-button icon="Download" :loading="downloading" @click="downloadTemplate">下载模板</el-button>
        </li>
        <li>
          <p class="step-title">选择填好的文件</p>
          <el-upload
            ref="uploadRef"
            class="drop"
            drag
            accept=".xlsx"
            :auto-upload="false"
            :show-file-list="false"
            :disabled="importing"
            :on-change="pick"
          >
            <el-icon class="drop-icon"><UploadFilled /></el-icon>
            <div v-if="file" class="el-upload__text">
              <span class="file-name">{{ file.name }}</span>
              <span class="num muted">{{ formatFileSize(file.size) }}</span>
              <br />点击或拖进来可以换一个
            </div>
            <div v-else-if="errors.length" class="el-upload__text">改好以后把文件重新拖进来，或<em>点击选择</em></div>
            <div v-else class="el-upload__text">把 .xlsx 文件拖到这里，或<em>点击选择</em></div>
            <template #tip>
              <p class="tip">只收 .xlsx，{{ MAX_MB }}MB、5000 行以内。有一行不对，整批都不导入。</p>
            </template>
          </el-upload>
        </li>
      </ol>

      <el-alert v-if="errorMessage" class="alert" type="error" :title="errorMessage" :closable="false" show-icon />
      <BizErrorTable v-if="errors.length" class="errors" :items="errors" />
    </template>

    <template #footer>
      <el-button v-if="imported !== null" type="primary" @click="visible = false">完成</el-button>
      <template v-else>
        <el-button :disabled="importing" @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!file || !ready" :loading="importing" @click="submit">开始导入</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.steps {
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: step;
}

/* 步骤编号：浅铜褐小圆点里一个数字 */
.steps li {
  position: relative;
  padding-left: 34px;
  counter-increment: step;
}

.steps li + li {
  margin-top: 20px;
}

.steps li::before {
  position: absolute;
  top: 0;
  left: 0;
  width: 22px;
  height: 22px;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 20px;
  color: var(--lm-accent-2);
  text-align: center;
  content: counter(step);
  background: var(--lm-accent-bg);
  border: 1px solid var(--lm-accent-line);
  border-radius: 50%;
}

.step-title {
  margin: 0 0 4px;
  font-weight: 600;
  line-height: 22px;
}

.step-hint {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--lm-muted);
}

.drop :deep(.el-upload-dragger) {
  padding: 20px 16px;
}

.drop-icon {
  font-size: 36px;
  color: var(--lm-faint);
}

.file-name {
  margin-right: 8px;
  font-weight: 600;
  color: var(--lm-ink);
  word-break: break-all;
}

.tip {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--lm-muted);
}

.alert {
  margin-top: 16px;
}

.errors {
  margin-top: 12px;
}

.done {
  padding: 24px 0 8px;
  text-align: center;
}

.done-icon {
  font-size: 48px;
  color: var(--lm-done-text);
}

.done p {
  margin: 12px 0 0;
  font-size: 15px;
}

.notes-title {
  margin: 20px 0 8px;
  font-size: 13px;
  color: var(--lm-muted);
}
</style>
