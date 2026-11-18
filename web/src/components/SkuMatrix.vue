<script setup lang="ts">
import { computed } from 'vue'
import { formatQty } from '@/utils/format'

/**
 * 颜色 × 尺码矩阵（docs/api/02 §4.1，架构 §10.2）：行是颜色、列是尺码，格子里是件数，右边和底下合计。
 * 录单、订单详情（只读）、提变更、排单、生产单的尺码明细、登记回货都用它，行为一样：
 *
 *   <SkuMatrix v-model="block.qtys" :sizes="opt.sizes" :colors="opt.colors" :skus="opt.skus" />
 *   <SkuMatrix :model-value="qtyOf" :sizes :colors :skus readonly :sub="(id) => `排 ${sched[id]} · 回 ${ret[id]}`" />
 *   <SkuMatrix v-model="b.qtys" :sizes :colors :skus="cells" :max="maxOf" :error="overOf" />
 *
 * - v-model：{ 格子 id: 件数 }。格子 id 由调用方定，skus 里每格给一个（订单用款式 SKU id，排单用订单行 id）；
 *   0 和没填的一样，显示成空。改一格发一个新对象出去（不改传进来的那个）
 * - colors：行。orderable = false 的整行灰掉、不能填，悬停看 reason；skus：哪个颜色 × 尺码有格子、格子 id，
 *   status = 0（停用的 SKU）那一格灰掉。没有 SKU 的格子显示"—"
 * - max：每格的上限，超了标红、不截断（后端还会拦，1004）；error：调用方自己的检查（排单几个工厂块加起来超没超、
 *   变更改得比已排还少），返回一句话就标红并悬停显示，返回 null 是没问题。两个都标在格子上，不拦输入
 * - sub：每格下面的小字（"排 80 · 回 40""原 120 · 已排 80""新加"），没有返回 null
 * - 只收正整数：输入里有小数点、字母这些就不收（输入框回到原来的数），不四舍五入
 * - 插槽 #prepend（参数 sizes）：表头下面多加一行，如"按尺码填单价"：<tr><td>单价</td><td v-for="s in sizes">…</td><td /></tr>
 */
export interface MatrixColor {
  id: string
  name: string
  code?: string | null
  hex?: string | null
  /** false：这个颜色不能下（整行灰掉）；不给 = 能下 */
  orderable?: boolean
  reason?: string | null
}

export interface MatrixCell {
  /** 格子 id，v-model 的键 */
  id: string
  colorId: string
  sizeCode: string
  /** 0 = 停用，格子灰掉；不给 = 启用 */
  status?: number
}

const props = withDefaults(
  defineProps<{
    modelValue: Record<string, number>
    sizes: string[]
    colors: MatrixColor[]
    skus: MatrixCell[]
    readonly?: boolean
    /** 每格的上限：{ 格子 id: 最多几件 } 或函数；没有上限的不给 */
    max?: Record<string, number> | ((id: string) => number | null | undefined)
    /** 调用方的检查：有问题返回一句话（格子标红、悬停显示） */
    error?: (id: string, qty: number) => string | null
    /** 每格下面的小字 */
    sub?: (id: string) => string | null
    /** 合计列、合计行的标题 */
    totalLabel?: string
  }>(),
  { readonly: false, max: undefined, error: undefined, sub: undefined, totalLabel: '合计' },
)

const emit = defineEmits<{ 'update:modelValue': [value: Record<string, number>] }>()

/** 颜色 id|尺码 → 格子 */
const cellMap = computed(() => new Map(props.skus.map((s) => [`${s.colorId}|${s.sizeCode}`, s])))
const cellOf = (colorId: string, size: string) => cellMap.value.get(`${colorId}|${size}`)

const qtyOf = (id: string) => {
  const value = props.modelValue[id]
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

function maxOf(id: string): number | null {
  const max = props.max
  if (!max) return null
  const value = typeof max === 'function' ? max(id) : max[id]
  return value ?? null
}

/** 这一格的问题：超上限、调用方的检查；没问题是 null */
function problemOf(id: string): string | null {
  const qty = qtyOf(id)
  const max = maxOf(id)
  if (max != null && qty > max) return `最多 ${formatQty(max)} 件`
  return props.error?.(id, qty) ?? null
}

const subOf = (id: string) => props.sub?.(id) ?? null

const editable = (color: MatrixColor, cell: MatrixCell) => !props.readonly && color.orderable !== false && cell.status !== 0

const rowTotal = (color: MatrixColor) =>
  props.sizes.reduce((sum, size) => {
    const cell = cellOf(color.id, size)
    return sum + (cell ? qtyOf(cell.id) : 0)
  }, 0)

const colTotal = (size: string) =>
  props.colors.reduce((sum, color) => {
    const cell = cellOf(color.id, size)
    return sum + (cell ? qtyOf(cell.id) : 0)
  }, 0)

const grandTotal = computed(() => props.colors.reduce((sum, color) => sum + rowTotal(color), 0))

const POSITIVE_INT = /^\d{0,7}$/

/** 只收数字：不是就把输入框改回原来的数（不收这次输入），是就发出新的件数 */
function onInput(id: string, event: Event) {
  const input = event.target as HTMLInputElement
  const text = input.value.trim()
  if (!POSITIVE_INT.test(text)) {
    input.value = qtyOf(id) ? String(qtyOf(id)) : ''
    return
  }
  emit('update:modelValue', { ...props.modelValue, [id]: text === '' ? 0 : Number(text) })
}

function cellTitle(color: MatrixColor, cell: MatrixCell): string | undefined {
  if (color.orderable === false) return color.reason ?? '这个颜色不能下'
  if (cell.status === 0) return '这个 SKU 已停用'
  return problemOf(cell.id) ?? undefined
}
</script>

<template>
  <div class="sku-matrix">
    <table>
      <thead>
        <tr>
          <th class="color-col">颜色 \ 尺码</th>
          <th v-for="size in sizes" :key="size" class="num">{{ size }}</th>
          <th class="num total-col">{{ totalLabel }}</th>
        </tr>
        <slot name="prepend" :sizes="sizes" />
      </thead>
      <tbody>
        <tr v-for="color in colors" :key="color.id" :class="{ off: color.orderable === false }" :title="color.orderable === false ? (color.reason ?? undefined) : undefined">
          <td class="color-col">
            <span class="swatch" :style="{ background: color.hex ?? undefined }" />
            <span>{{ color.name }}</span>
            <span v-if="color.code" class="code dim">{{ color.code }}</span>
            <span v-if="color.orderable === false && color.reason" class="reason">{{ color.reason }}</span>
          </td>
          <td
            v-for="size in sizes"
            :key="size"
            class="num cell"
            :class="{ bad: cellOf(color.id, size) && problemOf(cellOf(color.id, size)!.id), stopped: cellOf(color.id, size)?.status === 0 }"
            :title="cellOf(color.id, size) ? cellTitle(color, cellOf(color.id, size)!) : undefined"
          >
            <template v-if="cellOf(color.id, size)">
              <input
                v-if="editable(color, cellOf(color.id, size)!)"
                class="qty-input num"
                inputmode="numeric"
                :aria-label="`${color.name} ${size} 件数`"
                :value="qtyOf(cellOf(color.id, size)!.id) || ''"
                placeholder="0"
                @input="onInput(cellOf(color.id, size)!.id, $event)"
              />
              <span v-else class="qty-text">{{ qtyOf(cellOf(color.id, size)!.id) ? formatQty(qtyOf(cellOf(color.id, size)!.id)) : '' }}</span>
              <span v-if="subOf(cellOf(color.id, size)!.id)" class="sub">{{ subOf(cellOf(color.id, size)!.id) }}</span>
            </template>
            <span v-else class="dim">—</span>
          </td>
          <td class="num total-col">{{ formatQty(rowTotal(color)) }}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <td class="color-col">{{ totalLabel }}</td>
          <td v-for="size in sizes" :key="size" class="num total-col">{{ formatQty(colTotal(size)) }}</td>
          <td class="num total-col grand">{{ formatQty(grandTotal) }}</td>
        </tr>
      </tfoot>
    </table>
  </div>
</template>

<style scoped>
.sku-matrix {
  overflow-x: auto;
}

table {
  min-width: 100%;
  font-size: 12.5px;
  border-collapse: collapse;
}

th,
td {
  padding: 5px 8px;
  text-align: right;
  border: 1px solid var(--lm-line-soft);
}

th {
  font-weight: 500;
  color: var(--lm-muted);
  white-space: nowrap;
  background: var(--lm-subtle);
}

.color-col {
  min-width: 132px;
  text-align: left;
  white-space: nowrap;
}

.swatch {
  display: inline-block;
  width: 11px;
  height: 11px;
  margin-right: 6px;
  vertical-align: -1px;
  background: var(--lm-sunken);
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

.color-col .code {
  margin-left: 4px;
}

.reason {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--lm-danger-text);
  white-space: normal;
}

tr.off td {
  color: var(--lm-faint);
  background: var(--lm-subtle);
}

.cell {
  min-width: 64px;
  vertical-align: top;
}

.cell.stopped {
  background: var(--lm-subtle);
}

.qty-input {
  width: 100%;
  min-width: 48px;
  height: 26px;
  padding: 0 6px;
  font-size: 12.5px;
  color: var(--lm-ink);
  text-align: right;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-sm);
  outline: none;
}

.qty-input:focus {
  border-color: var(--lm-accent);
}

.cell.bad .qty-input,
.cell.bad .qty-text {
  color: var(--lm-danger-text);
}

.cell.bad .qty-input {
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

.cell.bad {
  background: var(--lm-danger-bg);
}

.qty-text {
  display: block;
  line-height: 20px;
}

.sub {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.2;
  color: var(--lm-faint);
  white-space: nowrap;
}

.cell.bad .sub {
  color: var(--lm-danger-text);
}

.total-col {
  font-weight: 600;
  background: var(--lm-subtle);
}

tfoot td {
  font-weight: 600;
  background: var(--lm-subtle);
}

.grand {
  color: var(--lm-ink);
}
</style>
