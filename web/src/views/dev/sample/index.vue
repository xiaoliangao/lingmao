<script setup lang="ts">
import './sample.css'
import { computed, onActivated, onDeactivated, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sampleApi } from '@/api/sample'
import { categoryApi } from '@/api/basic'
import type { TreeNode } from '@/types/basic'
import type { SampleCounts, SampleCreated, SampleQuery, SampleRow, SampleShowStatus } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useDict } from '@/composables/useDict'
import { useFieldAccess } from '@/composables/useFieldAccess'
import { useColumns, type ColumnsState } from '@/composables/useColumns'
import { PAGE_SIZES } from '@/composables/useTable'
import { validateKeywords } from '@/utils/keywords'
import { ApiError } from '@/utils/request'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatPrice } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import TagFilterItem from '@/components/tags/TagFilterItem.vue'
import FilterItem from '@/components/FilterItem.vue'
import DateRange from '@/components/DateRange.vue'
import DictSelect from '@/components/DictSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import StatusTag from '@/components/StatusTag.vue'
import SampleFormDialog from './SampleFormDialog.vue'
import SampleFeedback from './SampleFeedback.vue'
import { SAMPLE_STATUS, SAMPLE_TYPE, samplePath, sampleWho } from './common'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'
import { rememberSampleCreated } from './sampleCreatedReceipt'

const route = useRoute(), router = useRouter(), user = useUserStore()
const canRead = computed(() => user.hasPerm('dev:sample:list'))
const { canSeeCost } = useFieldAccess()
const seasons = useDict('season'), brands = useDict('brand'), nodes = useDict('sample_node'), sources = useDict('style_source')
const columns = useColumns('samples', [
  { key: 'name', label: '名称' }, { key: 'season', label: '季节 · 品类' }, { key: 'version', label: '版次' },
  { key: 'node', label: '当前节点' }, { key: 'who', label: '打样' }, { key: 'need', label: '要样' },
  { key: 'qty', label: '数量', hidden: true }, { key: 'brand', label: '品牌', hidden: true },
  { key: 'source', label: '来源', hidden: true }, { key: 'designer', label: '设计师', hidden: true },
  { key: 'patternMaker', label: '版师', hidden: true }, { key: 'fabric', label: '面料描述', hidden: true },
  { key: 'estTagPrice', label: '预估吊牌价', hidden: true }, { key: 'estCost', label: '预估成本', hidden: true },
  { key: 'created', label: '建档时间', hidden: true }, { key: 'style', label: '转大货款式', hidden: true },
])
const visibleColumns = computed<ColumnsState>(() => ({ ...columns, defs: columns.defs.filter((def) => def.key !== 'estCost' || canSeeCost.value) }))
const companyId = ref<string | null>(typeof route.query.companyId === 'string' ? route.query.companyId : user.defaultCompanyId)
const statusValue = (value: unknown): SampleShowStatus | '' => typeof value === 'string' && value in SAMPLE_STATUS ? value as SampleShowStatus : ''
const status = ref<SampleShowStatus | ''>(statusValue(route.query.status))
const query = reactive({ tagIds: [] as string[], keyword: '', categoryId: null as string | null, year: undefined as number | undefined,
  season: '', brand: '', designerId: null as string | null, factoryId: null as string | null, makerId: null as string | null,
  nodeCode: '', late: route.query.late === 'true' || route.query.late === '1', need: null as [string, string] | null,
  created: null as [string, string] | null, sort: 'CREATED_DESC' as NonNullable<SampleQuery['sort']> })
const names = reactive({ designer: null as string | null, factory: null as string | null, maker: null as string | null })
const categories = shallowRef<TreeNode[]>([]), metadataError = ref<string | null>(null), metadataLoading = ref(false)
let resumeOptionsOnActivate = false
const page = ref(1), size = ref(20), total = ref(0), rows = shallowRef<SampleRow[]>([])
const counts = shallowRef<SampleCounts | null>(null), loading = ref(false), error = ref<string | null>(null)
const statusOptions = computed(() => [{ label: `全部 ${counts.value?.ALL ?? '—'}`, value: '' },
  ...Object.entries(SAMPLE_STATUS).map(([value, item]) => ({ label: `${item.label} ${counts.value?.[value as SampleShowStatus] ?? '—'}`, value }))])
const countsLoading = ref(false), countsError = ref<string | null>(null), formOpen = ref(false)
const listRead = useSampleRead(), countsRead = useSampleRead(), optionsRead = useSampleRead()
function clear() { listRead.cancel(); countsRead.cancel(); optionsRead.cancel(); rows.value = []; counts.value = null; total.value = 0; loading.value = false; countsLoading.value = false; metadataLoading.value = false; resumeOptionsOnActivate = false; formOpen.value = false; error.value = null; countsError.value = null }
useSampleContext(() => { clear(); companyId.value = null; categories.value = [] })
function params(): SampleQuery {
  if (!companyId.value) throw new Error('请先选择主体')
  try { validateKeywords(query.keyword) } catch (cause) { throw new ApiError(400, cause instanceof Error ? cause.message : '最多输入 100 个编码') }
  return { companyId: companyId.value, tagIds: query.tagIds.length ? [...query.tagIds] : undefined, keyword: query.keyword || undefined, status: status.value || undefined,
    categoryId: query.categoryId ?? undefined, year: query.year, season: query.season || undefined, brand: query.brand || undefined,
    designerId: query.designerId ?? undefined, factoryId: query.factoryId ?? undefined, makerId: query.makerId ?? undefined,
    nodeCode: query.nodeCode || undefined, late: query.late || undefined, needFrom: query.need?.[0], needTo: query.need?.[1],
    createdFrom: query.created?.[0], createdTo: query.created?.[1], sort: query.sort }
}
async function load() {
  const read = listRead.begin(); loading.value = false; error.value = null
  if (!companyId.value || !canRead.value) { rows.value = []; total.value = 0; return }
  loading.value = true
  try {
    const result = await sampleApi.page({ ...params(), page: page.value, size: size.value }, { signal: read.signal, silent: true })
    if (!read.current()) return
    rows.value = result.list; total.value = result.total
  } catch (cause) { if (read.current()) { rows.value = []; error.value = sampleError(cause) } }
  finally { if (read.current()) loading.value = false }
}
async function loadCounts() {
  const read = countsRead.begin(); countsLoading.value = false; countsError.value = null
  if (!companyId.value || !canRead.value) { counts.value = null; return }
  countsLoading.value = true
  try {
    const result = await sampleApi.counts(params(), { signal: read.signal, silent: true })
    if (read.current()) counts.value = result
  } catch (cause) { if (read.current()) { counts.value = null; countsError.value = sampleError(cause) } }
  finally { if (read.current()) countsLoading.value = false }
}
async function loadOptions() {
  const read = optionsRead.begin(); metadataError.value = null; metadataLoading.value = true; resumeOptionsOnActivate = false
  try { const result = await read.run(categoryApi.enabledTree); if (read.current()) categories.value = result }
  catch (cause) { if (read.current()) metadataError.value = sampleError(cause) }
  finally { if (read.current()) metadataLoading.value = false }
}
function search() { page.value = 1; void load(); void loadCounts() }
function paging(value: number) { page.value = value; void load() }
function sizing(value: number) { size.value = value; page.value = 1; void load() }
watch(companyId, () => { clear(); search(); if (companyId.value && canRead.value) void loadOptions() })
watch(canRead, () => { clear(); if (canRead.value) { search(); void loadOptions() } })
watch(() => route.query, (value) => {
  if (route.path !== '/samples') return
  if (typeof value.companyId === 'string') companyId.value = value.companyId
  status.value = statusValue(value.status); query.late = value.late === 'true' || value.late === '1'; search()
})
onMounted(() => { if (canRead.value) { search(); void loadOptions() } })
let activated = false
onDeactivated(() => { resumeOptionsOnActivate = metadataLoading.value; metadataLoading.value = false })
onActivated(() => {
  if (activated && canRead.value) {
    search()
    if (resumeOptionsOnActivate) void loadOptions()
  }
  activated = true
})
const openRow = (row: SampleRow) => { void router.push(samplePath(row.id, row.companyId)) }
function responsible(row: SampleRow): string {
  if (!row.currentNode?.ownerName) return sampleWho(row)
  return row.makeWay === 'EXTERNAL' ? [row.factoryName, row.currentNode.ownerName].filter(Boolean).join(' · ') : row.currentNode.ownerName
}
function created(result: SampleCreated) {
  rememberSampleCreated(result)
  ElMessage.success(`样衣已创建：${result.code}`)
  void router.push(samplePath(result.id, result.companyId))
}
</script>

<template>
  <div class="sample-index">
    <PageHeader title="样衣"><template #actions>
      <CompanySelect v-model="companyId" placeholder="选择主体" aria-label="样衣主体" style="width: 180px" />
      <el-button v-perm="'dev:sample:add'" type="primary" icon="Plus" :disabled="!companyId" @click="formOpen = true">新建样衣</el-button>
    </template></PageHeader>
    <p v-if="!canRead" class="page dim">当前没有样衣读取权限。</p>
    <template v-else>
      <div v-if="countsLoading || countsError" class="counts-feedback"><SampleFeedback :loading="countsLoading" :error="countsError" label="状态数量" @retry="loadCounts" /></div>
      <FilterBar v-model:keyword="query.keyword" :columns="visibleColumns" placeholder="样衣号、打样单号、名称" @search="search">
        <TagFilterItem v-model="query.tagIds" />
        <template #left><el-segmented v-model="status" :options="statusOptions" aria-label="样衣状态" @change="search" /></template>
        <FilterItem label="品类" :text="query.categoryId ? '已选择' : ''" @clear="query.categoryId = null">
          <el-tree-select v-model="query.categoryId" :data="categories" :loading="metadataLoading" node-key="id" :props="{ label: 'name', children: 'children' }" check-strictly clearable filterable />
          <SampleFeedback :loading="metadataLoading" :error="metadataError" label="品类" @retry="loadOptions" />
        </FilterItem>
        <FilterItem label="年份" :text="query.year ? String(query.year) : ''" @clear="query.year = undefined"><el-input-number v-model="query.year" :min="2000" :max="2099" :precision="0" :controls="false" /></FilterItem>
        <FilterItem label="季节" :text="seasons.labelOf(query.season)" @clear="query.season = ''"><DictSelect v-model="query.season" type="season" clearable /></FilterItem>
        <FilterItem label="品牌" :text="brands.labelOf(query.brand)" @clear="query.brand = ''"><DictSelect v-model="query.brand" type="brand" clearable /></FilterItem>
        <FilterItem label="设计师" :text="names.designer" @clear="query.designerId = null; names.designer = null"><UserSelect v-model="query.designerId" v-model:label="names.designer" clearable /></FilterItem>
        <FilterItem label="版师" :text="names.maker" @clear="query.makerId = null; names.maker = null"><UserSelect v-model="query.makerId" v-model:label="names.maker" clearable /></FilterItem>
        <FilterItem label="打样工厂" :text="names.factory" @clear="query.factoryId = null; names.factory = null"><SupplierSelect v-model="query.factoryId" v-model:label="names.factory" :category="['GARMENT', 'PROCESSOR']" usage="SAMPLE" clearable /></FilterItem>
        <FilterItem label="当前节点" :text="nodes.labelOf(query.nodeCode)" @clear="query.nodeCode = ''"><DictSelect v-model="query.nodeCode" type="sample_node" clearable /></FilterItem>
        <FilterItem label="延期" :text="query.late ? '只看延期' : ''" @clear="query.late = false"><el-checkbox v-model="query.late">只看当前节点延期</el-checkbox></FilterItem>
        <FilterItem label="要样日期" :text="formatDateRange(query.need)" @clear="query.need = null"><DateRange v-model="query.need" /></FilterItem>
        <FilterItem label="建档日期" :text="formatDateRange(query.created)" @clear="query.created = null"><DateRange v-model="query.created" /></FilterItem>
        <template #right><el-select v-model="query.sort" aria-label="样衣排序" style="width: 142px" @change="search">
          <el-option label="最新建档" value="CREATED_DESC" /><el-option label="最早建档" value="CREATED_ASC" />
          <el-option label="要样日期由近到远" value="NEED_ASC" /><el-option label="要样日期由远到近" value="NEED_DESC" />
          <el-option label="样衣号升序" value="CODE_ASC" /><el-option label="样衣号降序" value="CODE_DESC" />
        </el-select></template>
      </FilterBar>
      <p v-if="!companyId" class="page dim">先选择主体，再查询样衣。</p>
      <el-table v-else v-loading="loading" :data="rows" row-key="id" class="sample-table" @row-click="openRow">
        <el-table-column label="样衣" width="210" fixed="left"><template #default="{ row }"><span class="sample-code"><ThumbImage v-if="row.image" :src="row.image.thumbUrl || row.image.url" alt="" class="thumb" /><span class="code link">{{ row.code }}</span></span></template></el-table-column>
        <el-table-column v-if="columns.show.name" prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column v-if="columns.show.season" label="季节 · 品类" min-width="165"><template #default="{ row }">{{ row.year }} {{ seasons.labelOf(row.season) }}<div class="dim">{{ row.categoryName }}</div></template></el-table-column>
        <el-table-column v-if="columns.show.version" label="版次" min-width="145"><template #default="{ row }">第 {{ row.currentSampleVersion }} 版 · {{ SAMPLE_TYPE[(row as SampleRow).sampleType] }}</template></el-table-column>
        <el-table-column v-if="columns.show.node" label="当前节点" min-width="138"><template #default="{ row }"><span :class="{ danger: row.late }">{{ row.currentNode?.name ?? '—' }}</span><el-tag v-if="row.late" type="danger" size="small">延期</el-tag></template></el-table-column>
        <el-table-column v-if="columns.show.who" label="打样" min-width="160"><template #default="{ row }">{{ responsible(row as SampleRow) }}<div v-if="row.makeWay" class="dim">{{ row.makeWay === 'INTERNAL' ? '内部版房' : '外部工厂' }}</div></template></el-table-column>
        <el-table-column v-if="columns.show.qty" label="数量" prop="qty" width="100" align="right" />
        <el-table-column v-if="columns.show.need" label="要样" width="112"><template #default="{ row }">{{ formatDate(row.needDate) }}</template></el-table-column>
        <el-table-column v-if="columns.show.brand" label="品牌" min-width="110"><template #default="{ row }">{{ brands.labelOf(row.brand) || '—' }}</template></el-table-column>
        <el-table-column v-if="columns.show.source" label="来源" min-width="100"><template #default="{ row }">{{ sources.labelOf(row.source) || '—' }}</template></el-table-column>
        <el-table-column v-if="columns.show.designer" label="设计师" prop="designerName" min-width="110" show-overflow-tooltip />
        <el-table-column v-if="columns.show.patternMaker" label="版师" prop="patternMakerName" min-width="110" show-overflow-tooltip />
        <el-table-column v-if="columns.show.fabric" label="面料描述" prop="fabricDesc" min-width="200" show-overflow-tooltip />
        <el-table-column v-if="columns.show.estTagPrice" label="预估吊牌价" width="130" align="right"><template #default="{ row }">{{ row.estTagPrice == null ? '—' : formatMoney(row.estTagPrice) }}</template></el-table-column>
        <el-table-column v-if="columns.show.estCost && canSeeCost" label="预估成本" width="130" align="right"><template #default="{ row }">{{ row.estCost == null ? '—' : formatPrice(row.estCost) }}</template></el-table-column>
        <el-table-column v-if="columns.show.created" label="建档时间" min-width="154"><template #default="{ row }">{{ formatDateTime(row.createTime) }}</template></el-table-column>
        <el-table-column v-if="columns.show.style" label="转大货款式" min-width="170"><template #default="{ row }"><router-link v-if="row.convertedStyleId && user.hasPerm('style:style:list')" :to="`/styles/${row.convertedStyleId}`" class="code link" @click.stop>{{ row.convertedStyleCode }}</router-link><span v-else>{{ row.convertedStyleCode || '—' }}</span></template></el-table-column>
        <el-table-column label="状态" width="128" fixed="right"><template #default="{ row }"><StatusTag :tone="SAMPLE_STATUS[(row as SampleRow).showStatus].tone">{{ SAMPLE_STATUS[(row as SampleRow).showStatus].label }}</StatusTag></template></el-table-column>
        <template #empty><SampleFeedback v-if="error" :error="error" label="样衣" @retry="load" /><span v-else-if="!loading" class="dim">没有符合条件的样衣</span></template>
      </el-table>
      <el-pagination v-if="companyId && !error" :current-page="page" :page-size="size" :page-sizes="PAGE_SIZES" :total="total" layout="total, sizes, prev, pager, next" class="pagination" @update:current-page="paging" @update:page-size="sizing" />
    </template>
    <SampleFormDialog v-if="formOpen && companyId" v-model="formOpen" :initial-company-id="companyId" @created="created" />
  </div>
</template>
<style scoped>
.sample-index :deep(.filter-bar) { position: sticky; top: var(--lm-header-height); z-index: 7; }
.sample-index :deep(.flt-pop) { max-height: min(72vh, calc(100vh - 190px)); overflow-y: auto; }
.counts-feedback { padding: 0 24px; }
.sample-code { display: flex; align-items: center; gap: 8px; }
.thumb { width: 28px; height: 28px; flex: none; }
.sample-table :deep(.el-table__row) { cursor: pointer; }
.sample-table .el-tag { margin-left: 6px; }
.danger { color: var(--lm-danger-text); }
</style>
