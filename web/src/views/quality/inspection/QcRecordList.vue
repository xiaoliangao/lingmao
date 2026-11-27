<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { qcApi } from '@/api/quality'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import { QC_KIND, QC_RESULT, qcResultLabel, type QcResult, type QcRow } from '@/types/quality'
import { formatDate, formatDateRange, formatMoney, formatQty } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'
import { orderLink, sourceLink } from './qcLinks'

/**
 * 质检页的"质检记录"页签：每次检验一条，新的在前。能改（扣款跟着改）、能删（扣款一起删）；
 * 安排了返修的不能删，扣款进了对账单的不能删（后端 1000 说是哪张）
 *
 *   <QcRecordList ref="recordsRef" @edit="(row) => …" />
 */
const emit = defineEmits<{ edit: [row: QcRow] }>()
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('quality:inspection:edit'))
const defectLabel = useDict('qc_defect_type').labelOf
const unitLabel = useDict('unit').labelOf
const partnerName = ref<string | null>(null)
const dates = ref<[string, string] | null>(null)

const KIND_OPTIONS = [
  { label: '全部', value: '' },
  { label: '尾期验货', value: 'FINAL' },
  { label: '来料检验', value: 'INCOMING' },
  { label: '中检', value: 'MIDDLE' },
  { label: '产前样', value: 'PRE_PRODUCTION' },
]
const RESULT_OPTIONS = (Object.keys(QC_RESULT) as QcResult[]).map((value) => ({ value, label: QC_RESULT[value].label }))

const cols = useColumns('qc-records', [
  { key: 'kind', label: '类型' },
  { key: 'source', label: '来源单' },
  { key: 'partner', label: '供应商' },
  { key: 'target', label: '货品' },
  { key: 'lot', label: '批量' },
  { key: 'sample', label: '抽检 / 不合格' },
  { key: 'result', label: '结论' },
  { key: 'problem', label: '问题' },
  { key: 'deduct', label: '扣款' },
  { key: 'inspector', label: '检验' },
  { key: 'company', label: '主体', hidden: true },
  { key: 'prdOrder', label: '生产单', hidden: true },
])

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: qcApi.page,
  query: {
    keyword: '',
    kind: '' as string,
    result: undefined as QcResult | undefined,
    partnerId: undefined as string | null | undefined,
    dateFrom: undefined as string | undefined,
    dateTo: undefined as string | undefined,
  },
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function onDates(val: [string, string] | null | undefined) {
  dates.value = val ?? null
  query.dateFrom = val?.[0]
  query.dateTo = val?.[1]
}

const emptyText = computed(() =>
  query.keyword.trim() || query.kind || query.result || query.partnerId || query.dateFrom
    ? '没有符合条件的质检记录：清空筛选看全部'
    : '还没有质检记录：在「待检」里点「验货」，产前样、中检点右上角「记产前样、中检」')

async function remove(row: QcRow) {
  await confirmRemove(`删除质检记录 ${row.no}`,
    row.deductionId ? '这条记了质量扣款，扣款也一起删。' : row.kind === 'FINAL' ? '删了以后这批回货回到待检。' : '删了就没有这次检验的记录了。')
  await qcApi.remove(row.id, row.companyId)
  ElMessage.success('删了')
  void load()
}

defineExpose({ reload: load })
</script>

<template>
  <div>
    <FilterBar v-model:keyword="query.keyword" placeholder="质检单号、来源单号、生产单号、供应商、货品" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.kind" :options="KIND_OPTIONS" @change="search" />
      </template>
      <FilterItem label="结论" :text="query.result ? QC_RESULT[query.result].label : ''" @clear="query.result = undefined">
        <el-select v-model="query.result" filterable clearable placeholder="全部结论" style="width: 100%">
          <el-option v-for="o in RESULT_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
      </FilterItem>
      <FilterItem label="供应商" :text="query.partnerId ? (partnerName ?? '已选') : ''" @clear="query.partnerId = undefined">
        <SupplierSelect v-model="query.partnerId" v-model:label="partnerName" clearable placeholder="全部供应商、加工厂" />
      </FilterItem>
      <FilterItem label="检验日期" :text="formatDateRange(dates)" @clear="onDates(null)">
        <DateRange :model-value="dates" @update:model-value="onDates" />
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="list" row-key="id" scrollbar-always-on :empty-text="emptyText">
      <el-table-column label="单号" width="150" fixed="left">
        <template #default="{ row }"><span class="code">{{ row.no }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.kind" label="类型" width="110">
        <template #default="{ row }">{{ QC_KIND[(row as QcRow).kind] }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.source" label="来源单" width="200">
        <template #default="{ row }">
          <router-link v-if="sourceLink(row as QcRow)" class="code link" :to="sourceLink(row as QcRow)!">{{ row.sourceNo }}</router-link>
          <span v-else class="code">{{ row.sourceNo }}</span>
          <span v-if="(row as QcRow).attemptNo > 1" class="dim"> 第 {{ row.attemptNo }} 次</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.prdOrder" label="生产单" width="160">
        <template #default="{ row }">
          <router-link v-if="orderLink(row as QcRow)" class="code link" :to="orderLink(row as QcRow)!">{{ row.prdOrderNo }}</router-link>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.partner" label="供应商" min-width="120" prop="partnerName" />
      <el-table-column v-if="cols.show.target" label="货品" min-width="160" prop="targetDesc" />
      <el-table-column v-if="cols.show.lot" label="批量" width="120" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.lotQty != null">{{ formatQty(row.lotQty) }} {{ unitLabel(row.unit) }}</template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.sample" label="抽检 / 不合格" width="120" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.sampleQty != null">{{ formatQty(row.sampleQty) }} / {{ formatQty(row.defectQty) }}</template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.result" label="结论" width="96">
        <template #default="{ row }">
          <StatusTag :tone="QC_RESULT[(row as QcRow).result].tone">{{ qcResultLabel(row.kind, row.result) }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.problem" label="问题" min-width="260" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <div class="problem">
            <el-image v-for="(p, i) in (row as QcRow).photos.slice(0, 2)" :key="p.id" class="pic" :src="p.thumbUrl ?? p.url" fit="cover"
              :preview-src-list="(row as QcRow).photos.map((x) => x.url)" :initial-index="i" preview-teleported />
            <div class="problem-text">
              <div class="ell">
                <span v-if="(row as QcRow).defectTypes.length" class="types">{{ (row as QcRow).defectTypes.map(defectLabel).join('、') }}</span>
                {{ row.note || (row.defectTypes.length ? '' : '—') }}
              </div>
              <div v-if="row.reworkNote" class="dim small ell">
                返修：{{ row.reworkNote }}{{ row.reworkDoneTime ? `（${formatDate(row.reworkDoneTime)} 返修完成）` : '（返修中）' }}
              </div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.deduct" label="扣款" width="110" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.deductAmount != null">{{ formatMoney(row.deductAmount) }}</template>
          <span v-else-if="row.deductionId" class="dim">已扣</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.inspector" label="检验" width="150">
        <template #default="{ row }">{{ row.inspectorName ?? '—' }} <span class="dim">{{ formatDate(row.inspectDate) }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.company" label="主体" width="120" prop="companyName" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <template v-if="canEdit">
            <el-button link @click="emit('edit', row as QcRow)">改</el-button>
            <el-button v-if="!row.reworkTime && !row.deductStatementNo" link type="danger" @click="remove(row as QcRow)">删除</el-button>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination" layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes" :total="total" />
  </div>
</template>

<style scoped>
.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.small {
  font-size: 12px;
}

.problem {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.pic {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: var(--lm-radius-sm);
}

.problem-text {
  min-width: 0;
  flex: 1;
}

.ell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.types {
  margin-right: 4px;
  color: var(--lm-danger-text);
}
</style>
