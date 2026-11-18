<script setup lang="ts">
import { computed, reactive, ref, toRaw, watch } from 'vue'
import SkuMatrixRow from './SkuMatrixRow.vue'
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

/** 每行只跟踪自己的响应式格子，敲一个键只改一列小计。 */
const quantities = reactive<Record<string, number>>({})
const columnTotals = reactive<Record<string, number>>({})
const grandTotal = ref(0)
const cellMap = computed(() => new Map(props.skus.map((cell) => [`${cell.colorId}|${cell.sizeCode}`, cell])))
const cellsById = computed(() => new Map(props.skus.map((cell) => [cell.id, cell])))
let emitted: Record<string, number> | undefined
watch([() => props.modelValue, () => props.skus], ([value]) => {
  if (toRaw(value) === emitted && Object.keys(value).length === Object.keys(quantities).length && Object.entries(value).every(([key, qty]) => quantities[key] === qty)) { emitted = undefined; return }
  for (const key of Object.keys(quantities)) if (!(key in value)) delete quantities[key]
  for (const [key, qty] of Object.entries(value)) quantities[key] = Number.isSafeInteger(qty) && qty > 0 ? qty : 0
  for (const key of Object.keys(columnTotals)) delete columnTotals[key]
  grandTotal.value = 0
  for (const cell of props.skus) {
    const qty = quantities[cell.id] ?? 0
    columnTotals[cell.sizeCode] = (columnTotals[cell.sizeCode] ?? 0) + qty
    grandTotal.value += qty
  }
}, { immediate: true, deep: true })
function updateCell(id: string, value: number) {
  const cell = cellsById.value.get(id)
  if (!cell) return
  const delta = value - (quantities[id] ?? 0)
  quantities[id] = value
  columnTotals[cell.sizeCode] = (columnTotals[cell.sizeCode] ?? 0) + delta
  grandTotal.value += delta
  emitted = { ...quantities }
  emit('update:modelValue', emitted)
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
        <SkuMatrixRow v-for="color in colors" :key="color.id" :color="color" :sizes="sizes" :cells="cellMap" :quantities="quantities" :readonly="readonly" :max="max" :error="error" :sub="sub" @change="updateCell" />
      </tbody>
      <tfoot>
        <tr>
          <td class="color-col">{{ totalLabel }}</td>
          <td v-for="size in sizes" :key="size" class="num total-col">{{ formatQty(columnTotals[size] ?? 0) }}</td>
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

:deep(table) {
  min-width: 100%;
  font-size: 12.5px;
  border-collapse: collapse;
}

:deep(th),
:deep(td) {
  padding: 5px 8px;
  text-align: right;
  border: 1px solid var(--lm-line-soft);
}

:deep(th) {
  font-weight: 500;
  color: var(--lm-muted);
  white-space: nowrap;
  background: var(--lm-subtle);
}

:deep(.color-col) {
  min-width: 132px;
  text-align: left;
  white-space: nowrap;
}

:deep(.swatch) {
  display: inline-block;
  width: 11px;
  height: 11px;
  margin-right: 6px;
  vertical-align: -1px;
  background: var(--lm-sunken);
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

:deep(.color-col .code) {
  margin-left: 4px;
}

:deep(.reason) {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--lm-danger-text);
  white-space: normal;
}

:deep(tr.off td) {
  color: var(--lm-faint);
  background: var(--lm-subtle);
}

:deep(.cell) {
  min-width: 64px;
  vertical-align: top;
}

:deep(.cell.stopped) {
  background: var(--lm-subtle);
}

:deep(.qty-input) {
  width: 100%;
  min-width: calc(7ch + 18px);
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

:deep(.qty-input:focus) {
  border-color: var(--lm-accent);
}

:deep(.cell.bad .qty-input),
:deep(.cell.bad .qty-text) {
  color: var(--lm-danger-text);
}

:deep(.cell.bad .qty-input) {
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

:deep(.cell.bad) {
  background: var(--lm-danger-bg);
}

:deep(.qty-text) {
  display: block;
  line-height: 20px;
}

:deep(.sub) {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.2;
  color: var(--lm-faint);
  white-space: nowrap;
}

:deep(.cell.bad .sub) {
  color: var(--lm-danger-text);
}

:deep(.total-col) {
  font-weight: 600;
  background: var(--lm-subtle);
}

:deep(tfoot td) {
  font-weight: 600;
  background: var(--lm-subtle);
}

:deep(.grand) {
  color: var(--lm-ink);
}
</style>
