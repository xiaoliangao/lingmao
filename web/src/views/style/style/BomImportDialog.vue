<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fileApi } from '@/api/system'
import { techPackApi } from '@/api/techpack'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useDict } from '@/composables/useDict'
import { useUploadScope } from '@/composables/usePendingUploads'
import type { MaterialPickRow } from '@/types/basic'
import type { StylePickRow } from '@/types/style'
import type { FileVO } from '@/types/system'
import { BOM_IMPORT_STATUS, BOM_SKU_MODE, type BomImport, type BomImportRow } from '@/types/techpack'
import { MAX_UPLOAD_SIZE } from '@/utils/request'
import MaterialForm from '@/views/basic/material/MaterialForm.vue'

/** 加进物料清单的东西：行（和 bomItems 一个样子，没有 id）、替换还是接在后面、加工费、运费、导入的文件（挂到工艺单页签） */
export interface BomImportApply {
  items: NonNullable<BomImportRow['item']>[]
  mode: 'replace' | 'append'
  laborPrice: number | null
  fees: { name: string; amount: number }[]
  file: FileVO | null
}

/**
 * 从甲方的 BOM 表导入物料清单（docs/api/01 §3.12、§4.5，RFC 011 §4.3，原型 TpImport）。
 * 选文件 → 后端认（只解析不写库）→ 预览表上改部位、用量、损耗、单价，没认出来的挑物料、新建物料或者不导 → 加进物料清单。
 * 加进去以后只是改了资料包页面上的表单，和手填一样，点"保存"才存。
 *
 *   <BomImportDialog ref="importer" :pack-id="detail.id" :existing="draft.bomItems.length" :can-see-cost="canSeeCost" @apply="onImport" />
 *   importer.value?.open()
 *
 * 挑了物料、建了物料、换了 sheet 都带着原文件重新调一次；已经改过的行物料没变就留着改过的
 */
const props = defineProps<{ packId: string; existing: number; canSeeCost: boolean }>()
const emit = defineEmits<{ apply: [payload: BomImportApply] }>()

const units = useDict('unit')
const uploads = useUploadScope()

const visible = ref(false)
const loading = ref(false)
const applying = ref(false)
const file = ref<File | null>(null)
const result = ref<BomImport | null>(null)
const rows = ref<BomImportRow[]>([])
const sheet = ref<number | null>(null)
const picks = ref<Record<number, string>>({})
const skipped = ref<Set<number>>(new Set())
const fileInput = ref<HTMLInputElement>()

function open() {
  file.value = null
  result.value = null
  rows.value = []
  sheet.value = null
  picks.value = {}
  skipped.value = new Set()
  visible.value = true
}
defineExpose({ open })

function chooseFile() {
  fileInput.value?.click()
}

async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = input.files?.[0]
  input.value = ''
  if (!picked) return
  if (!/\.(xls|xlsx)$/i.test(picked.name)) {
    ElMessage.error('只收 .xls、.xlsx 的 Excel 文件')
    return
  }
  if (picked.size > MAX_UPLOAD_SIZE) {
    ElMessage.error(`${picked.name} 太大，Excel 最大 10MB`)
    return
  }
  file.value = picked
  sheet.value = null
  picks.value = {}
  skipped.value = new Set()
  rows.value = []
  await parse(false)
}

/** 调后端认一次。keep = 同一张 sheet 重新认（挑了、建了物料）：改过的行物料没变就留着改过的 */
async function parse(keep: boolean) {
  if (!file.value) return
  loading.value = true
  try {
    const next = await techPackApi.bomImport(props.packId, file.value, sheet.value, picks.value)
    const old = keep ? new Map(rows.value.map((r) => [r.line, r])) : new Map<number, BomImportRow>()
    rows.value = next.rows.map((r) => {
      const prev = old.get(r.line)
      return prev?.item && r.item && prev.item.materialId === r.item.materialId ? { ...r, item: prev.item } : r
    })
    result.value = next
    sheet.value = next.sheet
  } catch {
    // 认不出这张表、不是草稿了：消息 request.ts 弹过了，什么都不改
    if (!keep) file.value = null
  } finally {
    loading.value = false
  }
}

async function onSheetChange() {
  picks.value = {}
  skipped.value = new Set()
  await parse(false)
}

const sheetOptions = computed(() => (result.value?.sheets ?? []).map((s) => ({
  value: s.index,
  label: s.title ? `${s.name}：${s.title}` : s.name,
  disabled: !s.bom,
})))

// ---------- 没认出来的行：挑物料、新建物料、不导 ----------
const pickingLine = ref<number | null>(null)
const picking = ref(false)
function startPick(line: number) {
  pickingLine.value = line
  picking.value = true
}
async function onPicked(picked: MaterialPickRow[] | StylePickRow[]) {
  const row = (picked as MaterialPickRow[])[0]
  if (!row || pickingLine.value == null) return
  picks.value = { ...picks.value, [pickingLine.value]: row.materialId }
  await parse(true)
}

const materialForm = ref<InstanceType<typeof MaterialForm>>()
const creatingLine = ref<number | null>(null)
function startCreate(row: BomImportRow) {
  creatingLine.value = row.line
  const unit = units.options.value.find((o) => o.label === row.source.unit.trim())?.value
  const price = row.source.price == null ? NaN : Number(row.source.price.replace(/[¥￥元,\s]/g, ''))
  materialForm.value?.openCreate(null, {
    name: row.source.name,
    specs: row.source.spec,
    ...(unit ? { stockUnit: unit } : {}),
    ...(Number.isFinite(price) ? { refPrice: price } : {}),
  })
}
async function onMaterialSaved(id: string) {
  if (creatingLine.value == null) return
  picks.value = { ...picks.value, [creatingLine.value]: id }
  creatingLine.value = null
  await parse(true)
}

function toggleSkip(line: number) {
  const next = new Set(skipped.value)
  if (next.has(line)) next.delete(line)
  else next.add(line)
  skipped.value = next
}

// ---------- 加进物料清单 ----------
const importable = computed(() => rows.value.filter((r) => !skipped.value.has(r.line) && r.item))
const blocked = computed(() => rows.value.filter((r) => !skipped.value.has(r.line) && !r.item).length)

async function apply() {
  if (!importable.value.length || blocked.value) return
  let mode: BomImportApply['mode'] = 'append'
  if (props.existing > 0) {
    try {
      await ElMessageBox.confirm(`物料清单里已经有 ${props.existing} 行。`, '加进物料清单', {
        confirmButtonText: `替换现有 ${props.existing} 行`,
        cancelButtonText: '加在后面',
        distinguishCancelAndClose: true,
      })
      mode = 'replace'
    } catch (action) {
      if (action !== 'cancel') return
    }
  }
  applying.value = true
  // 导入的表留一份在工艺单页签上；传不上去也照样加行
  let saved: FileVO | null = null
  const task = uploads.begin()
  try {
    saved = await fileApi.upload(file.value!)
  } catch {
    ElMessage.warning('BOM 表没传上去，物料清单照样加了；要留底的话在工艺单页签上自己传一份')
  } finally {
    task.finish(saved != null)
    applying.value = false
  }
  emit('apply', {
    items: importable.value.map((r) => r.item!),
    mode,
    laborPrice: props.canSeeCost ? (result.value?.laborPrice ?? null) : null,
    fees: props.canSeeCost ? (result.value?.fees ?? []) : [],
    file: saved,
  })
  visible.value = false
}

function sourceText(r: BomImportRow) {
  const s = r.source
  return [s.name, s.spec, s.color].filter((t) => t && t !== '/').join(' · ')
}
function skuText(item: NonNullable<BomImportRow['item']>) {
  if (item.skuMode === 'FIXED') return item.skus[0] ? item.skus[0].skuCode : '没配料'
  return `${BOM_SKU_MODE[item.skuMode]} · ${item.skus.length} 个 SKU`
}
</script>

<template>
  <el-dialog v-model="visible" title="从 BOM 表导入物料清单" width="min(1180px, 96vw)" top="4vh" append-to-body>
    <input ref="fileInput" type="file" accept=".xls,.xlsx" hidden @change="onFile" />
    <div v-loading="loading" class="body">
      <div class="toolbar">
        <el-button :type="file ? 'default' : 'primary'" icon="Upload" @click="chooseFile">{{ file ? '换一个文件' : '选 BOM 表' }}</el-button>
        <span v-if="file" class="dim name">{{ file.name }}</span>
        <template v-if="result && result.sheets.length > 1">
          <span class="dim">用哪张表</span>
          <el-select v-model="sheet" filterable class="sheet-sel" @change="onSheetChange">
            <el-option v-for="o in sheetOptions" :key="o.value" :label="o.disabled ? `${o.label}（不是 BOM 表）` : o.label" :value="o.value" :disabled="o.disabled" />
          </el-select>
        </template>
      </div>

      <p v-if="!file" class="dim hint">选甲方的 BOM 明细表（.xls、.xlsx）。系统按表头认列、按名称和规格对物料库，认不出的当场挑或者新建；导不了的照常手工填。</p>

      <el-table v-else-if="result" :data="rows" row-key="line" scrollbar-always-on max-height="58vh" empty-text="这张表上没有物料行"
        :row-class-name="({ row }: { row: BomImportRow }) => (skipped.has(row.line) ? 'skipped' : '')">
        <el-table-column label="行" width="52" class-name="num" fixed="left">
          <template #default="{ row }">{{ row.line }}</template>
        </el-table-column>
        <el-table-column label="表里写的" min-width="200">
          <template #default="{ row }">
            {{ sourceText(row as BomImportRow) }}
            <div class="dim small">{{ [row.source.usage, row.source.unit].filter(Boolean).join(' ') }}<template v-if="row.source.price"> · ¥{{ row.source.price }}</template></div>
          </template>
        </el-table-column>
        <el-table-column label="认成" min-width="200">
          <template #default="{ row }">
            <template v-if="row.item">
              <span class="code">{{ row.item.materialCode }}</span> {{ row.item.materialName }}
              <div class="dim small">{{ skuText(row.item) }}</div>
            </template>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="部位" width="120">
          <template #default="{ row }">
            <DictSelect v-if="row.item" v-model="row.item.part" type="bom_part" size="small" placeholder="选部位" :class="{ missing: !row.item.part }" />
          </template>
        </el-table-column>
        <el-table-column label="用量" width="104" class-name="num" align="right">
          <template #default="{ row }">
            <el-input-number v-if="row.item" v-model="row.item.usageQty" size="small" :min="0" :precision="4" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="损耗 %" width="84" class-name="num" align="right">
          <template #default="{ row }">
            <el-input-number v-if="row.item" v-model="row.item.lossPct" size="small" :min="0" :precision="2" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column v-if="canSeeCost" label="单价" width="104" class-name="num" align="right">
          <template #default="{ row }">
            <el-input-number v-if="row.item" v-model="row.item.refPrice" size="small" :min="0" :precision="4" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <StatusTag v-if="skipped.has(row.line)" tone="off">不导</StatusTag>
            <StatusTag v-else :tone="BOM_IMPORT_STATUS[(row as BomImportRow).status].tone">{{ BOM_IMPORT_STATUS[(row as BomImportRow).status].label }}</StatusTag>
          </template>
        </el-table-column>
        <el-table-column label="提示" min-width="220" :show-overflow-tooltip="false">
          <template #default="{ row }">
            <div v-for="n in row.notes" :key="n" class="note">{{ n }}</div>
          </template>
        </el-table-column>
        <el-table-column label="" width="176" align="right" fixed="right">
          <template #default="{ row }">
            <template v-if="!row.item && !skipped.has(row.line)">
              <el-button link type="primary" size="small" @click="startPick(row.line)">挑物料</el-button>
              <el-button link type="primary" size="small" @click="startCreate(row as BomImportRow)">新建物料</el-button>
            </template>
            <el-button link size="small" @click="toggleSkip(row.line)">{{ skipped.has(row.line) ? '要导' : '不导' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p v-if="result && canSeeCost && (result.laborPrice != null || result.fees.length)" class="dim extra">
        一起带进核价：<span v-if="result.laborPrice != null" class="fee">加工费 ¥{{ result.laborPrice }}</span>
        <span v-for="f in result.fees" :key="f.name" class="fee">{{ f.name }} ¥{{ f.amount }}</span>
      </p>
    </div>

    <template #footer>
      <span v-if="blocked" class="warn-txt">还有 {{ blocked }} 行没认出来：挑物料、新建物料或者点「不导」</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!importable.length || blocked > 0" :loading="applying" @click="apply">加进物料清单（{{ importable.length }} 行）</el-button>
    </template>

    <DataPick v-model="picking" kind="sku" single @pick="onPicked" />
    <MaterialForm ref="materialForm" @saved="onMaterialSaved" />
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 160px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 12.5px;
}

.name {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sheet-sel {
  width: 280px;
}

.hint,
.extra {
  margin: 8px 0 0;
  font-size: 12.5px;
}

.small {
  font-size: 11.5px;
}

.fee + .fee {
  margin-left: 12px;
}

.note {
  font-size: 12px;
  line-height: 1.4;
  color: var(--lm-pending-text);
}

.warn-txt {
  margin-right: 12px;
  font-size: 12.5px;
  color: var(--lm-pending-text);
}

.missing :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-pending-text) inset;
}

:deep(.skipped) {
  color: var(--lm-faint);
}

.body :deep(.el-input-number) {
  width: 100%;
}
</style>
