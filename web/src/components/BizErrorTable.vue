<script setup lang="ts">
import { computed } from 'vue'

/**
 * 错误清单表格。1003 的每一项是 {sheet?, row, column, message}，显示成"工作表 / 行 / 列 / 问题"：
 * sheet 只在一个文件几张表的导入里有，没有就不出这一列；row 是 0 说的是整张表、整个文件，显示"—"。
 * 导入成功时的提示清单格式一样，传 message-label="说明"。
 * 1001、1004 是"对象数组"：每个字段一列，表头查 LABELS，不认识的字段用字段名当表头（一眼看得出漏了哪个）。
 * 各种清单有哪些字段见 docs/api：库存不足、超收超退 04 §3.4，超排 02 §3.12。1005 是 DocRef（README §4.5），不走这里，
 * BizErrorDialog 用 DocRefTable 显示
 */
const props = withDefaults(defineProps<{ items: unknown[]; messageLabel?: string }>(), { messageLabel: '问题' })

type Item = Record<string, unknown>

const LABELS = computed<Record<string, string>>(() => ({
  sheet: '工作表',
  row: '行',
  column: '列',
  message: props.messageLabel,
  // 1001 库存不足、1004 超收超退（04 §3.4）
  seqs: '行',
  seq: '行',
  warehouseName: '仓库',
  skuCode: 'SKU',
  materialName: '物料',
  stockQty: '库存',
  needQty: '要扣',
  shortQty: '差',
  unit: '单位',
  sourceNo: '源单',
  thisQty: '本次',
  maxQty: '最多',
  reason: '说明',
  // 成品（07 §3.5）：款式 SKU 的颜色、尺码
  styleName: '款名',
  colorName: '颜色',
  sizeCode: '尺码',
  // 1004 超排（02 §3.12）、超回货（05 §3.6.2，只算良品）
  sku: 'SKU',
  qty: '加工',
  goodQty: '已回良品',
  thisGoodQty: '这次的良品',
  orderQty: '订单数',
  scheduledQty: '已排产',
}))

/** 数字列（右对齐、定宽）和几列短字段的宽度：差多少、单位要一眼看到（走查 T-33e438：原来"差""单位"被挤出去） */
const NUM_KEYS = new Set(['stockQty', 'needQty', 'shortQty', 'thisQty', 'maxQty', 'orderQty', 'scheduledQty', 'qty', 'goodQty', 'thisGoodQty'])
const FIXED_WIDTH: Record<string, number> = { row: 64, seq: 64, seqs: 72, unit: 64, sizeCode: 72, colorName: 90, skuCode: 140, sku: 140 }
const width = (key: string) => FIXED_WIDTH[key] ?? (NUM_KEYS.has(key) ? 96 : undefined)
const minWidth = (key: string) => (key === 'message' ? 240 : key === 'materialName' || key === 'warehouseName' ? 130 : 100)

/** 不是对象的（只有一句话）当成 message */
const rows = computed<Item[]>(() =>
  props.items.map((item) => (item !== null && typeof item === 'object' ? (item as Item) : { message: item })),
)

/** 所有行出现过的字段，按第一次出现的顺序 */
const columns = computed(() => [...new Set(rows.value.flatMap((row) => Object.keys(row)))])

function text(key: string, value: unknown) {
  if (value == null) return ''
  if (key === 'row' && value === 0) return '—'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
</script>

<template>
  <el-table :data="rows" max-height="360" scrollbar-always-on empty-text="这个错误没有带明细">
    <el-table-column
      v-for="key in columns"
      :key="key"
      :label="LABELS[key] ?? key"
      :width="width(key)"
      :min-width="minWidth(key)"
      :class-name="key === 'row' || key === 'seq' || key === 'seqs' || NUM_KEYS.has(key) ? 'num' : undefined"
      :align="NUM_KEYS.has(key) ? 'right' : undefined"
    >
      <template #default="{ row }"><strong v-if="key === 'shortQty'" class="short">{{ text(key, (row as Item)[key]) }}</strong><template v-else>{{ text(key, (row as Item)[key]) }}</template></template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.short {
  color: var(--lm-danger-text);
}
</style>
