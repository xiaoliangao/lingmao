<script setup lang="ts">
import { computed, onActivated, onMounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { categoryApi } from '@/api/basic'
import { styleCostApi } from '@/api/techpack'
import DictSelect from '@/components/DictSelect.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import type { TreeNode } from '@/types/basic'
import { PACK_STAGE, packName, type PackStage, type StyleCostRow } from '@/types/techpack'

/**
 * 开发 › 核价（/costing，docs/api/01 §1.2、§3.13，原型 /costing）：一款一行，取生产已生效版、没有取研发已生效版的单件成本构成。
 * 点一行去款式详情资料包那一版的核价子页签
 */
import { formatMoney } from '@/utils/format'

const router = useRouter()
const brands = useDict('brand')
const seasons = useDict('season')

// 款号固定在左边、总在；其余的各人在"显示列"里挑
const cols = useColumns('costing', [
  { key: 'name', label: '款式名称' },
  { key: 'pack', label: '核算版本' },
  { key: 'fabric', label: '面料' },
  { key: 'trim', label: '辅料' },
  { key: 'labor', label: '加工费' },
  { key: 'other', label: '其他费用' },
  { key: 'total', label: '单件总成本' },
  { key: 'tagPrice', label: '吊牌价' },
  { key: 'ratio', label: '倍率' },
])

const STAGE_OPTIONS = [
  { label: '全部阶段', value: '' },
  { label: '生产 BOM', value: 'PROD' },
  { label: '研发 BOM', value: 'DEV' },
]

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ stage, ...rest }) => styleCostApi.page({ ...rest, stage: stage || undefined }),
  query: {
    keyword: '',
    stage: '' as PackStage | '',
    categoryId: undefined as number | undefined,
    brand: '',
    year: undefined as number | undefined,
    season: '',
  },
})

const categories = shallowRef<TreeNode[]>([])
const categoryOptions = computed(() => {
  const map = (nodes: TreeNode[] = []): { value: number; label: string; children: unknown[] }[] =>
    nodes.map((n) => ({ value: n.id, label: n.name, children: map(n.children) }))
  return map(categories.value)
})
const categoryName = computed(() => {
  const find = (nodes: TreeNode[] = []): string | undefined => {
    for (const n of nodes) {
      if (n.id === query.categoryId) return n.name
      const hit = find(n.children)
      if (hit) return hit
    }
    return undefined
  }
  return query.categoryId === undefined ? '' : (find(categories.value) ?? '已选')
})

const thisYear = new Date().getFullYear()
const YEARS = Array.from({ length: 8 }, (_, i) => thisYear + 2 - i)

onMounted(async () => {
  categories.value = await categoryApi.enabledTree()
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function openCostTab(row: StyleCostRow) {
  router.push(`/styles/${row.styleId}?tab=techpack&techPackId=${row.techPackId}&sub=cost`)
}
</script>

<template>
  <div>
    <PageHeader />

    <FilterBar v-model:keyword="query.keyword" placeholder="款号、名称、花名；空格分隔查多个款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.stage" :options="STAGE_OPTIONS" @change="search" />
      </template>
      <FilterItem label="品类" :text="categoryName" @clear="query.categoryId = undefined">
        <el-tree-select
          v-model="query.categoryId"
          :data="categoryOptions"
          check-strictly
          filterable
          clearable
          default-expand-all
          placeholder="全部品类"
          :value-on-clear="undefined"
        />
      </FilterItem>
      <FilterItem label="品牌" :text="brands.labelOf(query.brand)" @clear="query.brand = ''">
        <DictSelect v-model="query.brand" type="brand" clearable placeholder="全部品牌" />
      </FilterItem>
      <FilterItem label="年份" :text="query.year ? String(query.year) : ''" @clear="query.year = undefined">
        <el-select v-model="query.year" filterable clearable placeholder="全部年份" :value-on-clear="undefined">
          <el-option v-for="y in YEARS" :key="y" :label="String(y)" :value="y" />
        </el-select>
      </FilterItem>
      <FilterItem label="季节" :text="seasons.labelOf(query.season)" @clear="query.season = ''">
        <DictSelect v-model="query.season" type="season" clearable placeholder="全部季节" />
      </FilterItem>
    </FilterBar>

    <el-table
      v-loading="loading"
      :data="list"
      row-key="styleId"
      row-class-name="clickable"
      scrollbar-always-on
      :empty-text="query.keyword || query.categoryId || query.brand || query.year || query.season || query.stage ? '没有符合条件的款式：换个条件，或者清空筛选' : '还没有生效的资料包：资料包生效后，这里按款列出单件成本'"
      @row-click="openCostTab"
    >
      <el-table-column label="款号" width="170" fixed="left">
        <template #default="{ row }">
          <span class="name-cell">
            <img v-if="row.image" :src="row.image.thumbUrl ?? row.image.url" alt="" class="thumb" loading="lazy" />
            <span v-else class="thumb blank" />
            <span class="code">{{ row.styleCode }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.name" label="款式名称" min-width="170">
        <template #default="{ row }">
          {{ row.styleName }}<span v-if="row.aliasName" class="dim"> ({{ row.aliasName }})</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.pack" label="核算版本" width="120">
        <template #default="{ row }">
          <span class="stage-tag" :class="row.stage.toLowerCase()">{{ PACK_STAGE[(row as StyleCostRow).stage].short }}</span>
          <span class="code"> {{ packName(row as StyleCostRow) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.fabric" label="面料" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.fabric) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.trim" label="辅料" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.trim) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.labor" label="加工费" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.labor) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.other" label="其他费用" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.other) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.total" label="单件总成本" width="120" class-name="num" align="right">
        <template #default="{ row }"><strong>{{ formatMoney(row.total) }}</strong></template>
      </el-table-column>
      <el-table-column v-if="cols.show.tagPrice" label="吊牌价" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ row.tagPrice == null ? '—' : formatMoney(row.tagPrice) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.ratio" label="倍率" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ row.ratio == null ? '—' : `${row.ratio}x` }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />
  </div>
</template>

<style scoped>
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.thumb {
  flex: none;
  width: 28px;
  height: 28px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.thumb.blank {
  background: var(--lm-greige-2);
}

.stage-tag {
  padding: 1px 5px;
  font-size: 11px;
  border-radius: 4px;
}

.stage-tag.prod {
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
}

.stage-tag.dev {
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
