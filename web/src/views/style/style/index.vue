<script setup lang="ts">
import { computed, onActivated, onMounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { categoryApi } from '@/api/basic'
import { styleApi } from '@/api/style'
import type { TreeNode } from '@/types/basic'
import { STYLE_STATUS, TECH_PACK_STATUS, type StyleRow, type StyleStatus, type TechPackStatus } from '@/types/style'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import DictSelect from '@/components/DictSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import BatchBar from '@/components/BatchBar.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import type { BatchAction } from '@/components/batchBar'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { formatDate, formatPrice } from '@/utils/format'
import { download } from '@/utils/request'

/**
 * 开发 › 款式（RFC 005 §9，原型 5a-home-style.js 的 /styles）：图卡、表格两种视图，记住上次选的；
 * 点一款进详情。表格视图勾选以后批量启用、停用、导出选中；导出按现在的筛选条件导
 */
const router = useRouter()
const seasons = useDict('season')
const waves = useDict('wave')
const brands = useDict('brand')
const classes = useDict('style_class')

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  ...(['ENABLED', 'DRAFT', 'DISABLED'] as StyleStatus[]).map((value) => ({ label: STYLE_STATUS[value].label, value })),
]

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  fetch: ({ status, techPackStatus, designerId, ...rest }) =>
    styleApi.page({ ...rest, status: status || undefined, techPackStatus: techPackStatus || undefined, designerId: designerId ?? undefined }),
  query: {
    keyword: '',
    status: '' as StyleStatus | '',
    categoryId: undefined as number | undefined,
    styleClass: '',
    brand: '',
    year: undefined as number | undefined,
    season: '',
    wave: '',
    designerId: undefined as number | null | undefined,
    techPackStatus: '' as TechPackStatus | '',
  },
  exportUrl: '/api/styles/export',
  exportName: '款式.xlsx',
})

// ---------- 视图：图卡 / 表格，记在这台电脑的浏览器里 ----------
type View = 'grid' | 'list'
const VIEW_KEY = 'scm.styles.view'
function savedView(): View {
  try {
    return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'grid'
  } catch {
    return 'grid'
  }
}
const view = ref<View>(savedView())
watch(view, (value) => {
  try {
    localStorage.setItem(VIEW_KEY, value)
  } catch {
    // 无痕窗口、禁了站点数据：记不住就算了，这次照样切
  }
  tableRef.value?.clearSelection()
})
const VIEW_OPTIONS = [
  { label: '图卡', value: 'grid' },
  { label: '表格', value: 'list' },
]

// ---------- 筛选的选项 ----------
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

/** 选了设计师以后小标签上的字：名字从按他筛出来的行里取 */
const designerText = computed(() => {
  if (!query.designerId) return ''
  return list.value.find((row) => row.designerId === query.designerId)?.designerName ?? '已选'
})

async function loadCategories() {
  categories.value = await categoryApi.enabledTree()
}

onMounted(loadCategories)
// 切回这个页签（从详情页改完、删完、新建完回来）：这一页重新取。进页面第一次由 useTable 取
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  load()
})

const filtered = computed(
  () =>
    !!(query.keyword.trim() || query.categoryId !== undefined || query.styleClass || query.brand || query.year || query.season ||
      query.wave || query.designerId || query.techPackStatus),
)
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的款式，换个筛选条件试试'
  if (query.status) return `没有${STYLE_STATUS[query.status].label}的款式`
  return '还没有款式，新建一个或者从 Excel 导入'
})

// ---------- 列表 ----------
function open(row: StyleRow) {
  router.push(`/styles/${row.id}`)
}

function onRowClick(row: StyleRow, column: TableColumnCtx<StyleRow> | null) {
  if (column?.type !== 'selection') open(row)
}

const seasonText = (row: { year: number; season: string }) => `${row.year} ${seasons.labelOf(row.season)}`

// ---------- 勾选、批量 ----------
const tableRef = ref<TableInstance>()
const selected = ref<StyleRow[]>([])
const batchActions: BatchAction<StyleRow>[] = [
  {
    label: '启用', perm: 'style:style:edit', can: (s) => s.status !== 'ENABLED', run: (s, o) => styleApi.enable(s.id, o),
  },
  {
    label: '停用', perm: 'style:style:edit', can: (s) => s.status === 'ENABLED', run: (s, o) => styleApi.disable(s.id, o),
    confirm: '停用后不能再下新订单、录新的加工价；已有的订单照常生产、回货，随时可以启用。',
  },
]

const exportingPicked = ref(false)

async function exportPicked() {
  exportingPicked.value = true
  try {
    await download('/api/styles/export', { ids: selected.value.map((s) => s.id) }, '款式.xlsx')
  } finally {
    exportingPicked.value = false
  }
}

const importVisible = ref(false)
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'style:style:import'" icon="Upload" @click="importVisible = true">导入</el-button>
        <el-button v-perm="'style:style:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'style:style:add'" type="primary" icon="Plus" @click="router.push('/styles/new')">新建款式</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="款号、名称、花名、品名" @search="search">
      <template #left>
        <el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" />
      </template>
      <FilterItem label="品类" :text="categoryName" @clear="query.categoryId = undefined">
        <el-tree-select
          v-model="query.categoryId"
          :data="categoryOptions"
          check-strictly
          filterable
          clearable
          default-expand-all
          placeholder="全部品类（含下级）"
          :value-on-clear="undefined"
        />
      </FilterItem>
      <FilterItem label="大类" :text="classes.labelOf(query.styleClass)" @clear="query.styleClass = ''">
        <DictSelect v-model="query.styleClass" type="style_class" clearable placeholder="全部大类" />
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
      <FilterItem label="波段" :text="waves.labelOf(query.wave)" @clear="query.wave = ''">
        <DictSelect v-model="query.wave" type="wave" clearable placeholder="全部波段" />
      </FilterItem>
      <FilterItem label="设计师" :text="designerText" @clear="query.designerId = undefined">
        <UserSelect v-model="query.designerId" clearable placeholder="全部设计师" />
      </FilterItem>
      <FilterItem label="资料包" :text="query.techPackStatus ? TECH_PACK_STATUS[query.techPackStatus].label : ''" @clear="query.techPackStatus = ''">
        <el-select v-model="query.techPackStatus" filterable clearable placeholder="全部">
          <el-option v-for="(v, k) in TECH_PACK_STATUS" :key="k" :label="k === 'NONE' ? '还没有资料包' : v.label" :value="k" />
        </el-select>
      </FilterItem>
      <template #right>
        <el-segmented v-model="view" :options="VIEW_OPTIONS" aria-label="视图" />
      </template>
    </FilterBar>

    <div v-if="view === 'grid'" v-loading="loading" class="cards">
      <button v-for="s in list" :key="s.id" type="button" class="card" @click="open(s)">
        <span class="pic">
          <img v-if="s.image" :src="s.image.thumbUrl ?? s.image.url" alt="" loading="lazy" />
          <span v-else class="no-pic">还没有图</span>
          <StatusTag class="st" :tone="STYLE_STATUS[s.status].tone">{{ STYLE_STATUS[s.status].label }}</StatusTag>
        </span>
        <span class="meta">
          <span class="line">
            <span class="code">{{ s.code }}</span>
            <span class="gap" />
            <span class="dim num">{{ s.tagPrice == null ? '' : formatPrice(s.tagPrice) }}</span>
          </span>
          <span class="ell">{{ s.name }}<span v-if="s.aliasName" class="dim"> · {{ s.aliasName }}</span></span>
          <span class="line">
            <span v-for="c in s.colors" :key="c.id" class="swatch" :title="`${c.code} ${c.name}`" :style="{ background: c.hex ?? undefined }" />
            <span class="dim small">{{ seasonText(s) }}</span>
          </span>
        </span>
      </button>
      <p v-if="!loading && !list.length" class="empty">{{ emptyText }}</p>
    </div>

    <el-table
      v-else
      ref="tableRef"
      v-loading="loading"
      :data="list"
      row-key="id"
      row-class-name="clickable"
      :empty-text="emptyText"
      @selection-change="selected = $event"
      @row-click="onRowClick"
    >
      <el-table-column type="selection" width="40" reserve-selection />
      <el-table-column label="款号" min-width="150">
        <template #default="{ row }">
          <span class="name">
            <img v-if="row.image" :src="row.image.thumbUrl ?? row.image.url" alt="" class="thumb" loading="lazy" />
            <span v-else class="thumb blank" />
            <span class="code">{{ row.code }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="名称" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.aliasName ? `${row.name}（${row.aliasName}）` : row.name }}</template>
      </el-table-column>
      <el-table-column label="品类" prop="categoryName" min-width="90" />
      <el-table-column label="年份季节" min-width="120">
        <template #default="{ row }">{{ seasonText(row as StyleRow) }} · {{ row.wave ? waves.labelOf(row.wave) : '—' }}</template>
      </el-table-column>
      <el-table-column label="颜色 × 尺码" min-width="150">
        <template #default="{ row }">
          <span class="line">
            <span v-for="c in row.colors" :key="c.id" class="swatch" :title="`${c.code} ${c.name}`" :style="{ background: c.hex ?? undefined }" />
            <span class="dim">{{ row.colorCount }} × {{ row.sizeCount }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="资料包" width="90">
        <template #default="{ row }">
          <StatusTag :tone="TECH_PACK_STATUS[row.techPackStatus as TechPackStatus].tone">{{ TECH_PACK_STATUS[row.techPackStatus as TechPackStatus].label }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column label="吊牌价" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ row.tagPrice == null ? '—' : formatPrice(row.tagPrice) }}</template>
      </el-table-column>
      <el-table-column label="设计师" min-width="80">
        <template #default="{ row }">{{ row.designerName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <StatusTag :tone="STYLE_STATUS[row.status as StyleStatus].tone">{{ STYLE_STATUS[row.status as StyleStatus].label }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="124" class-name="num">
        <template #default="{ row }">{{ formatDate(row.updateTime) }}</template>
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

    <BatchBar v-if="view === 'list'" :rows="selected" :table="tableRef" :actions="batchActions" @done="load">
      <el-button v-perm="'style:style:export'" size="small" :loading="exportingPicked" @click="exportPicked">导出选中</el-button>
    </BatchBar>

    <ImportDialog
      v-model="importVisible"
      title="导入款式"
      template-url="/api/styles/import-template"
      import-url="/api/styles/import"
      @success="search"
    />
  </div>
</template>

<style scoped>
/* 图卡：照原型的 .cards / .card，一张图一张卡 */
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  min-height: 160px;
  padding: 16px 20px;
  background: var(--lm-greige-2);
}

.card {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  font: inherit;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
  transition: border-color 0.18s var(--lm-ease), box-shadow 0.18s var(--lm-ease), transform 0.18s var(--lm-ease);
}

.card:hover {
  border-color: var(--lm-line-strong);
  box-shadow: var(--lm-shadow-pop);
  transform: translateY(-2px);
}

.card:focus-visible {
  outline: none;
  box-shadow: var(--lm-focus-ring);
}

.pic {
  position: relative;
  display: grid;
  place-items: center;
  aspect-ratio: 1;
  background: var(--lm-subtle);
}

.pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.no-pic {
  font-size: 12px;
  color: var(--lm-muted);
}

.st {
  position: absolute;
  top: 10px;
  right: 10px;
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-top: 1px solid var(--lm-line-soft);
}

.line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}

.gap {
  flex: 1;
}

.ell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.small {
  margin-left: 4px;
  font-size: 12px;
}

.empty {
  grid-column: 1 / -1;
  margin: 40px 0;
  color: var(--lm-muted);
  text-align: center;
}

.name {
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

.swatch {
  flex: none;
  width: 14px;
  height: 14px;
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}

@media (max-width: 768px) {
  .cards {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
    padding: 12px 16px;
  }
}
</style>
