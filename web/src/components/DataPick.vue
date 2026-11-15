<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue'
import { materialApi, materialCategoryApi } from '@/api/basic'
import type { MaterialPickRow, TreeNode } from '@/types/basic'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { formatPrice } from '@/utils/format'

/**
 * 从物料库里挑数据，一次勾多条（用户 11-14 定，原型 3e-pick.js 的 DataPick）：搜索 + 一级分类 + 分页。
 * 翻页、换分类、换关键字时已经勾的留着；have 里的（单上已经有的）灰掉不能再勾。
 * 点"加入"发出 pick（勾的行，按勾的先后），对话框自己关。每次打开都从头来。
 *
 *   <DataPick v-model="picking" kind="sku" :have="lines.map((l) => l.skuId)" @pick="addLines" />
 *
 * kind：sku = 物料 SKU（GET /api/materials/pick，启用物料的启用 SKU），have 是 SKU id。
 * 加一种（比如款式）：SOURCES 加一项，模板里按 kind 加它的列，pick 的类型改成联合
 *
 * single：只挑一条（录价格选物料用）。点一行就换成这一行，表头没有"全选"，按钮叫"选定"，pick 发出的数组只有一条
 *
 *   <DataPick v-model="picking" kind="sku" single @pick="(rows) => choose(rows[0])" />
 */
const props = withDefaults(defineProps<{ kind?: 'sku'; have?: number[]; single?: boolean }>(), { kind: 'sku', have: () => [], single: false })
const emit = defineEmits<{ pick: [rows: MaterialPickRow[]] }>()
const visible = defineModel<boolean>({ required: true })

const SOURCES = {
  sku: {
    title: '从物料库选',
    noun: '物料',
    placeholder: '物料名称、编码、SKU 码、颜色、供应商货号',
    groups: materialCategoryApi.enabledTree,
    fetch: materialApi.pick,
    key: (row: MaterialPickRow) => row.skuId,
  },
}
const source = computed(() => SOURCES[props.kind])

const units = useDict('unit')
const groups = shallowRef<TreeNode[]>([])
const { query, page, size, list, total, loading, search } = useTable({
  fetch: (params) => source.value.fetch(params),
  query: { keyword: '', categoryId: undefined as number | undefined },
  immediate: false,
})
/** 勾了的：id → 行，Map 按勾的先后排 */
const chosen = ref(new Map<number, MaterialPickRow>())
const had = computed(() => new Set(props.have))

async function onOpen() {
  query.keyword = ''
  query.categoryId = undefined
  chosen.value = new Map()
  search()
  groups.value = await source.value.groups()
}

function pickGroup(id: number | undefined) {
  query.categoryId = id
  search()
}

const keyOf = (row: MaterialPickRow) => source.value.key(row)
const rowKey = (row: MaterialPickRow) => String(keyOf(row))
const isHad = (row: MaterialPickRow) => had.value.has(keyOf(row))
/** 这一页能勾的 */
const free = computed(() => list.value.filter((row) => !isHad(row)))
const allOn = computed(() => free.value.length > 0 && free.value.every((row) => chosen.value.has(keyOf(row))))
const someOn = computed(() => free.value.some((row) => chosen.value.has(keyOf(row))))

function toggle(row: MaterialPickRow) {
  if (isHad(row)) return
  const key = keyOf(row)
  if (props.single && !chosen.value.has(key)) chosen.value.clear()
  if (!chosen.value.delete(key)) chosen.value.set(key, row)
}

function toggleAll() {
  const on = allOn.value
  for (const row of free.value) {
    if (on) chosen.value.delete(keyOf(row))
    else chosen.value.set(keyOf(row), row)
  }
}

function confirm() {
  emit('pick', [...chosen.value.values()])
  visible.value = false
}

const emptyText = computed(() =>
  query.keyword.trim() ? `没有匹配「${query.keyword.trim()}」的${source.value.noun}` : `这一类还没有启用的${source.value.noun}`,
)
const rowClass = ({ row }: { row: MaterialPickRow }) => (isHad(row) ? 'is-had' : chosen.value.has(keyOf(row)) ? 'is-chosen' : '')
</script>

<template>
  <el-dialog v-model="visible" :title="source.title" width="880px" append-to-body @open="onOpen">
    <el-input v-model="query.keyword" :placeholder="source.placeholder" clearable class="dpk-search" @keyup.enter="search" @clear="search">
      <template #prefix><el-icon><Search /></el-icon></template>
    </el-input>
    <div class="dpk-chips" role="group" aria-label="分类">
      <button type="button" :class="{ on: query.categoryId === undefined }" @click="pickGroup(undefined)">全部</button>
      <button v-for="g in groups" :key="g.id" type="button" :class="{ on: query.categoryId === g.id }" @click="pickGroup(g.id)">
        {{ g.name }}
      </button>
    </div>

    <el-table v-loading="loading" :data="list" :row-key="rowKey" max-height="480" class="dpk-table" :row-class-name="rowClass" :empty-text="emptyText" @row-click="toggle">
      <el-table-column width="44">
        <template v-if="!single" #header>
          <el-checkbox :model-value="allOn" :indeterminate="someOn && !allOn" :disabled="!free.length" aria-label="勾选这一页全部" @change="toggleAll" />
        </template>
        <template #default="{ row }">
          <span @click.stop>
            <el-checkbox :model-value="isHad(row as MaterialPickRow) || chosen.has(keyOf(row as MaterialPickRow))" :disabled="isHad(row as MaterialPickRow)" @change="toggle(row as MaterialPickRow)" />
          </span>
        </template>
      </el-table-column>
      <template v-if="kind === 'sku'">
        <el-table-column label="物料" min-width="260">
          <template #default="{ row }">
            <span class="dpk-item">
              <img v-if="row.image" class="dpk-thumb" :src="row.image.thumbUrl || row.image.url" alt="" />
              <span v-else class="dpk-thumb" />
              <span class="dpk-text">
                <span>{{ row.materialName }}</span>
                <small class="code">{{ row.skuCode }}<template v-if="isHad(row as MaterialPickRow)"> · 已在单上</template></small>
              </span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="分类" prop="categoryName" min-width="100" />
        <el-table-column label="颜色、规格" min-width="140">
          <template #default="{ row }">
            <span class="dpk-color">
              <span v-if="row.colorId" class="swatch" :style="{ background: row.colorHex ?? undefined }" />
              {{ [row.colorName, row.spec].filter(Boolean).join(' · ') || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="单位" width="70">
          <template #default="{ row }">{{ units.labelOf(row.stockUnit) }}</template>
        </el-table-column>
        <el-table-column label="单价" class-name="num" width="100">
          <template #default="{ row }">{{ row.price == null ? '—' : formatPrice(row.price) }}</template>
        </el-table-column>
      </template>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" size="small" class="pagination" />

    <template #footer>
      <div class="dpk-foot">
        <span class="dpk-count">已选 <b>{{ chosen.size }}</b> 条</span>
        <el-button v-if="chosen.size" link type="primary" @click="chosen = new Map()">清空</el-button>
        <span class="dpk-gap" />
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :disabled="!chosen.size" @click="confirm">{{ single ? '选定' : `加入${chosen.size ? ` ${chosen.size} 条` : ''}` }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 样子照原型的 .dpk-top / .dpk-chips：分类是一排小胶囊，选中的铜褐底 */
.dpk-search {
  margin-bottom: 10px;
}

.dpk-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 10px;
}

.dpk-chips button {
  padding: 3px 10px;
  border: 1px solid var(--lm-line);
  border-radius: 999px;
  background: var(--lm-paper);
  color: var(--lm-ink-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.dpk-chips button:hover {
  border-color: var(--lm-line-strong);
  color: var(--lm-ink);
}

.dpk-chips button.on {
  border-color: var(--lm-accent-line);
  background: var(--lm-accent-bg);
  color: var(--lm-accent-2);
  font-weight: 500;
}

.dpk-table :deep(.el-table__row) {
  cursor: pointer;
}

.dpk-table :deep(.el-table__row.is-chosen > td) {
  background: var(--lm-accent-bg);
}

.dpk-table :deep(.el-table__row.is-had) {
  color: var(--lm-muted);
  cursor: default;
}

.dpk-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dpk-thumb {
  flex: none;
  width: 30px;
  height: 30px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
  background: var(--lm-subtle);
}

.dpk-text {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.dpk-text small {
  color: var(--lm-muted);
}

.dpk-color {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.swatch {
  width: 14px;
  height: 14px;
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

.dpk-foot {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
}

.dpk-count {
  color: var(--lm-muted);
}

.dpk-count b {
  color: var(--lm-ink);
}

.dpk-gap {
  flex: 1;
}
</style>
