<script setup lang="ts">
import { onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { materialApi, materialCategoryApi } from '@/api/basic'
import type { MaterialRow, TreeNode } from '@/types/basic'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import DictSelect from '@/components/DictSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import BatchBar from '@/components/BatchBar.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import type { BatchAction } from '@/components/batchBar'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { formatPrice, formatQty } from '@/utils/format'
import { download } from '@/utils/request'
import { STATUS_OPTIONS, statusParam, type StatusFilter } from '../common'
import MaterialForm from './MaterialForm.vue'

/**
 * 资料 › 物料（RFC 003 §7、§12）：左边物料分类，右边列表；点一行进详情。
 * 勾选以后批量启用、停用、删除、导出选中；导出按现在的筛选条件导
 */
const router = useRouter()
const types = useDict('material_type')
const units = useDict('unit')

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  fetch: ({ status, ...rest }) => materialApi.page({ ...rest, status: statusParam(status) }),
  query: { keyword: '', categoryId: undefined as number | undefined, materialType: '', status: 1 as StatusFilter },
  exportUrl: '/api/materials/export',
  exportName: '物料.xlsx',
})

// ---------- 左边分类 ----------
const categories = ref<TreeNode[]>([])

async function loadCategories() {
  categories.value = await materialCategoryApi.tree()
}

onMounted(loadCategories)
// 切回这个页签（从详情页改完、删完回来，从基础页改了分类回来）：分类和这一页都重新取。进页面第一次由上面取
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  loadCategories()
  load()
})

function pickCategory(id: number | undefined) {
  query.categoryId = id
  tableRef.value?.clearSelection()
  search()
}

function manageCategories() {
  router.push({ path: '/basics', query: { tab: 'matcats' } })
}

const emptyText = () => {
  if (query.keyword || query.materialType) return '没有符合条件的物料'
  if (query.categoryId !== undefined) return '这个分类下没有物料'
  return query.status === 0 ? '没有停用的物料' : '还没有物料，新建一个或者从领猫导入'
}

// ---------- 列表 ----------
function open(row: MaterialRow) {
  router.push(`/materials/${row.id}`)
}

function onRowClick(row: MaterialRow, column: TableColumnCtx<MaterialRow> | null) {
  if (column?.type !== 'selection') open(row)
}

/** 单位一列：采购单位不一样时带上换算 */
function unitText(row: MaterialRow) {
  const stock = units.labelOf(row.stockUnit)
  if (row.purchaseUnit === row.stockUnit) return stock
  return `${stock}（1 ${units.labelOf(row.purchaseUnit)} = ${formatQty(row.conversionFactor)} ${stock}）`
}

function sizeText(row: MaterialRow) {
  const parts = [row.widthCm != null ? `${formatQty(row.widthCm)}cm` : '', row.weightGsm != null ? `${formatQty(row.weightGsm)}g` : '']
  return parts.filter(Boolean).join(' · ') || '—'
}

// ---------- 勾选、批量 ----------
const tableRef = ref<TableInstance>()
const selected = ref<MaterialRow[]>([])
const batchActions: BatchAction<MaterialRow>[] = [
  { label: '启用', perm: 'basic:material:edit', can: (m) => m.status === 0, run: (m, o) => materialApi.enable(m.id, o) },
  {
    label: '停用', perm: 'basic:material:edit', can: (m) => m.status === 1, run: (m, o) => materialApi.disable(m.id, o),
    confirm: '停用后它的 SKU 不能再选进新单据，已有库存照常出库，随时可以启用。',
  },
  {
    label: '删除', perm: 'basic:material:remove', danger: true, run: (m, o) => materialApi.remove(m.id, o),
    confirm: '删除以后连同 SKU、图片一起找不回来。已经被价格档案、单据用过的删不掉，会列出来，请改成停用。',
  },
]

const exportingPicked = ref(false)

async function exportPicked() {
  exportingPicked.value = true
  try {
    await download('/api/materials/export', { ids: selected.value.map((m) => m.id) }, '物料.xlsx')
  } finally {
    exportingPicked.value = false
  }
}

// ---------- 新建、导入 ----------
const formDialog = ref<InstanceType<typeof MaterialForm>>()
const importVisible = ref(false)

function onSaved(id: number) {
  load()
  open({ id } as MaterialRow)
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'basic:material:import'" icon="Upload" @click="importVisible = true">导入</el-button>
        <el-button v-perm="'basic:material:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'basic:material:add'" type="primary" icon="Plus" @click="formDialog?.openCreate(query.categoryId)">新建物料</el-button>
      </template>
    </PageHeader>

    <div class="with-nav">
      <nav class="cat-nav" aria-label="物料分类">
        <button type="button" class="nav-i" :class="{ on: query.categoryId === undefined }" @click="pickCategory(undefined)">全部</button>
        <div v-for="group in categories" :key="group.id" class="grp">
          <button type="button" class="nav-i head" :class="{ on: query.categoryId === group.id, off: group.status !== 1 }" @click="pickCategory(group.id)">
            {{ group.name }}{{ group.status !== 1 ? '（停用）' : '' }}
          </button>
          <button
            v-for="c in group.children"
            :key="c.id"
            type="button"
            class="nav-i"
            :class="{ on: query.categoryId === c.id, off: c.status !== 1 }"
            @click="pickCategory(c.id)"
          >
            <span class="code dim">{{ c.code }}</span>
            <span>{{ c.name }}{{ c.status !== 1 ? '（停用）' : '' }}</span>
          </button>
        </div>
        <el-button v-perm="'basic:basics:list'" class="manage" link icon="Setting" @click="manageCategories">管理分类</el-button>
      </nav>

      <div class="main">
        <FilterBar v-model:keyword="query.keyword" placeholder="编码、名称、货号、成分" batch="编码" @search="search">
          <template #left>
            <el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" />
          </template>
          <FilterItem label="物料类型" :text="types.labelOf(query.materialType)" @clear="query.materialType = ''">
            <DictSelect v-model="query.materialType" type="material_type" clearable placeholder="全部类型" />
          </FilterItem>
        </FilterBar>

        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="list"
          row-key="id"
          row-class-name="clickable"
          :empty-text="emptyText()"
          @selection-change="selected = $event"
          @row-click="onRowClick"
        >
          <el-table-column type="selection" width="40" reserve-selection />
          <el-table-column prop="code" label="编码" width="110" class-name="code" />
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <span class="name">
                <img v-if="row.image" :src="row.image.thumbUrl ?? row.image.url" alt="" class="thumb" loading="lazy" />
                <span v-else class="thumb blank" />
                <span>{{ row.name }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="分类" min-width="120">
            <template #default="{ row }">{{ row.categoryName }}</template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ types.labelOf(row.materialType) }}</template>
          </el-table-column>
          <el-table-column label="单位" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ unitText(row as MaterialRow) }}</template>
          </el-table-column>
          <el-table-column label="门幅 · 克重" min-width="120" class-name="num">
            <template #default="{ row }">{{ sizeText(row as MaterialRow) }}</template>
          </el-table-column>
          <el-table-column label="默认供应商" min-width="110">
            <template #default="{ row }">{{ row.defaultSupplierName ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="供应商货号" min-width="110" class-name="code">
            <template #default="{ row }">{{ row.supplierItemNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="参考单价" min-width="110" class-name="num" align="right">
            <template #default="{ row }">
              {{ row.refPrice != null ? `${formatPrice(row.refPrice)} / ${units.labelOf(row.purchaseUnit)}` : '—' }}
            </template>
          </el-table-column>
          <el-table-column prop="skuCount" label="SKU" width="70" class-name="num" align="right" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
            </template>
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
    </div>

    <BatchBar :rows="selected" :table="tableRef" :actions="batchActions" @done="load">
      <el-button v-perm="'basic:material:export'" size="small" :loading="exportingPicked" @click="exportPicked">导出选中</el-button>
    </BatchBar>

    <MaterialForm ref="formDialog" @saved="onSaved" />
    <ImportDialog
      v-model="importVisible"
      title="导入物料"
      template-url="/api/materials/import-template"
      import-url="/api/materials/import"
      @success="search"
    />
  </div>
</template>

<style scoped>
.with-nav {
  display: flex;
  align-items: stretch;
  min-height: calc(100vh - var(--lm-header-height) - 40px);
}

.cat-nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
  width: 196px;
  padding: 10px 8px;
  border-right: 1px solid var(--lm-line-soft);
}

.grp {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-top: 8px;
}

.nav-i {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 30px;
  padding: 4px 10px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: none;
  border: none;
  border-radius: var(--lm-radius-sm);
}

.nav-i:hover {
  background: var(--lm-hover);
}

.nav-i.on {
  font-weight: 500;
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
}

.nav-i.head {
  font-size: 12px;
  font-weight: 600;
  color: var(--lm-muted);
}

.nav-i.head.on {
  color: var(--lm-accent-2);
}

.nav-i.off {
  color: var(--lm-muted);
}

.manage {
  align-self: flex-start;
  margin: 12px 0 0 10px;
  font-size: 12px;
}

.main {
  flex: 1;
  min-width: 0;
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

:deep(.el-table__row.clickable) {
  cursor: pointer;
}

@media (max-width: 768px) {
  .with-nav {
    flex-direction: column;
    min-height: 0;
  }

  .cat-nav {
    flex-flow: row wrap;
    width: auto;
    border-right: none;
    border-bottom: 1px solid var(--lm-line-soft);
  }

  .grp {
    flex-flow: row wrap;
    margin-top: 0;
  }

  .nav-i {
    width: auto;
  }
}
</style>
