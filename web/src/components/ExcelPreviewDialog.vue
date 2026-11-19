<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ExcelBook, ExcelSheet, ExcelStyle } from '@/types/techpack'

/**
 * 在页面里看一份 Excel（RFC 011 §2.3）：后端用 POI 读成表格数据，这里画成表。每个 sheet 一个页签，
 * 保留列宽行高、合并单元格、粗体斜体字号、字色底色、对齐、换行、边框，嵌在表里的图片按位置叠上去。
 * 格子的字一律当文本插值，不用 v-html；图片只认 data:image/ 开头的。
 *
 *   <ExcelPreviewDialog v-model="previewing" :title="file.name" :load="() => techPackApi.preview(packId, file.id)" :download-url="file.url" />
 *
 * 每次打开都重新取。超出上限（20 个 sheet、500 行、60 列、图片 10MB）的部分不画，底下写还有多少、给下载
 */
const props = defineProps<{ title: string; load: () => Promise<ExcelBook>; downloadUrl?: string }>()
const visible = defineModel<boolean>({ default: false })

const book = ref<ExcelBook | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const active = ref(0)
let seq = 0

watch(visible, async (open) => {
  if (!open) return
  const current = ++seq
  book.value = null
  error.value = null
  active.value = 0
  loading.value = true
  try {
    const result = await props.load()
    if (current !== seq) return
    book.value = result
    // 默认看第一张没隐藏的
    active.value = Math.max(0, result.sheets.findIndex((s) => !s.hidden))
  } catch (e) {
    if (current === seq) error.value = e instanceof Error ? e.message : '打不开这个文件'
  } finally {
    if (current === seq) loading.value = false
  }
})

const sheet = computed<ExcelSheet | null>(() => book.value?.sheets[active.value] ?? null)

/** 一张 sheet 画成什么：每行要画的格子（被合并盖住的不画）、合并的跨度 */
interface Td {
  c: number
  v: string
  style: Record<string, string>
  rowspan: number
  colspan: number
}
const grid = computed<Td[][]>(() => {
  const s = sheet.value
  if (!s) return []
  const cells = new Map(s.cells.map((cell) => [`${cell.r}:${cell.c}`, cell]))
  const spans = new Map(s.merges.map((m) => [`${m.r}:${m.c}`, m]))
  const covered = new Set<string>()
  for (const m of s.merges) {
    for (let r = m.r; r < m.r + m.rs; r++) for (let c = m.c; c < m.c + m.cs; c++) if (r !== m.r || c !== m.c) covered.add(`${r}:${c}`)
  }
  return s.rowHeights.map((_, r) => {
    const row: Td[] = []
    for (let c = 0; c < s.colWidths.length; c++) {
      const key = `${r}:${c}`
      if (covered.has(key)) continue
      const cell = cells.get(key)
      const span = spans.get(key)
      row.push({
        c,
        v: cell?.v ?? '',
        style: cssOf(cell?.s == null ? null : (s.styles[cell.s] ?? null)),
        rowspan: span?.rs ?? 1,
        colspan: span?.cs ?? 1,
      })
    }
    return row
  })
})

const BORDER = '1px solid var(--lm-ink-2)'

function cssOf(st: ExcelStyle | null): Record<string, string> {
  if (!st) return {}
  const css: Record<string, string> = {}
  if (st.b) css.fontWeight = '600'
  if (st.i) css.fontStyle = 'italic'
  // 字号是磅，换成像素（1 磅 = 4/3 像素）
  if (st.fs) css.fontSize = `${Math.round(st.fs * 4 / 3)}px`
  if (st.fc) css.color = st.fc
  if (st.bg) css.background = st.bg
  if (st.ha) css.textAlign = st.ha
  if (st.va) css.verticalAlign = st.va
  if (st.wrap) css.whiteSpace = 'pre-wrap'
  if (st.bt) css.borderTop = BORDER
  if (st.br) css.borderRight = BORDER
  if (st.bb) css.borderBottom = BORDER
  if (st.bl) css.borderLeft = BORDER
  return css
}

const images = computed(() => (sheet.value?.images ?? []).filter((img) => img.data.startsWith('data:image/')))
const tableWidth = computed(() => (sheet.value?.colWidths ?? []).reduce((sum, w) => sum + w, 0))

/** 底下的"还有多少没画" */
const more = computed(() => {
  const s = sheet.value
  const parts: string[] = []
  if (s?.moreRows) parts.push(`${s.moreRows} 行`)
  if (s?.moreCols) parts.push(`${s.moreCols} 列`)
  if (s?.skippedImages) parts.push(`${s.skippedImages} 张图`)
  if (book.value?.moreSheets) parts.push(`${book.value.moreSheets} 张 sheet`)
  return parts.join('、')
})
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="min(1180px, 94vw)" top="4vh" class="excel-preview" append-to-body>
    <div v-loading="loading" class="body">
      <p v-if="error" class="danger">{{ error }}</p>
      <template v-else-if="book">
        <el-tabs v-if="book.sheets.length > 1" v-model="active" class="sheet-tabs">
          <el-tab-pane v-for="(s, i) in book.sheets" :key="i" :name="i" :label="s.hidden ? `${s.name}（隐藏）` : s.name" />
        </el-tabs>
        <p v-if="sheet && !sheet.cells.length && !sheet.images.length" class="dim">这张 sheet 是空的</p>
        <div v-else-if="sheet" class="sheet-scroll">
          <div class="sheet-canvas" :style="{ width: `${tableWidth}px` }">
            <table class="sheet" :style="{ width: `${tableWidth}px` }">
              <colgroup>
                <col v-for="(w, c) in sheet.colWidths" :key="c" :style="{ width: `${w}px` }" />
              </colgroup>
              <tbody>
                <tr v-for="(row, r) in grid" :key="r" :style="{ height: `${sheet.rowHeights[r]}px` }" :class="{ collapsed: sheet.rowHeights[r] === 0 }">
                  <td v-for="td in row" :key="td.c" :rowspan="td.rowspan" :colspan="td.colspan" :style="td.style">{{ td.v }}</td>
                </tr>
              </tbody>
            </table>
            <img
              v-for="(img, i) in images"
              :key="i"
              :src="img.data"
              alt=""
              class="sheet-img"
              :style="{ left: `${img.x}px`, top: `${img.y}px`, width: `${img.w}px`, height: `${img.h}px` }"
            />
          </div>
        </div>
        <p v-if="more" class="dim more">后面还有 {{ more }}没显示，下载看全部</p>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <a v-if="downloadUrl" :href="downloadUrl" :download="title" class="el-button el-button--primary dl">下载</a>
    </template>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 200px;
}

.sheet-tabs {
  margin-bottom: 8px;
}

.sheet-scroll {
  max-height: 72vh;
  overflow: auto;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line-soft);
}

.sheet-canvas {
  position: relative;
}

.sheet {
  font-size: 13px;
  line-height: 1.3;
  color: var(--lm-ink);
  table-layout: fixed;
  border-collapse: collapse;
}

.sheet td {
  padding: 1px 3px;
  overflow: hidden;
  white-space: nowrap;
  vertical-align: bottom;
  border: 1px solid var(--lm-line-soft);
}

.sheet tr.collapsed {
  visibility: collapse;
}

.sheet-img {
  position: absolute;
  object-fit: fill;
  pointer-events: none;
}

.more {
  margin: 8px 0 0;
  font-size: 12.5px;
}

.danger {
  color: var(--lm-danger-text);
}

.dl {
  margin-left: 12px;
  text-decoration: none;
}
</style>
