<script setup lang="ts">
import { ref, watch } from 'vue'
import { productionApi } from '@/api/production'
import StatusTag from '@/components/StatusTag.vue'
import { MATERIAL_ISSUE_STATUS, PRODUCTION_STATUS, type IssueOption } from '@/types/production'
import { formatQty } from '@/utils/format'

/**
 * 选一张生产单：出库单页"领料出库（选生产单）"、退料入库表单的"源生产单"（docs/api/04 §3.3.2、§3.7）。
 *
 *   <PickProductionDialog v-model="picking" @pick="(o) => openIssue(o.id)" />              还有没领齐的
 *   <PickProductionDialog v-model="picking" returnable @pick="(o) => openReturn(o.id)" />   已领 > 0 的
 *
 * 点一行就选中、关掉；接着由调用方开 SourceInvDocDialog
 */
const visible = defineModel<boolean>({ required: true })

const props = withDefaults(
  defineProps<{
    returnable?: boolean
  }>(),
  { returnable: false },
)

const emit = defineEmits<{
  pick: [order: IssueOption]
}>()

const keyword = ref('')
const rows = ref<IssueOption[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await productionApi.issueOptions({
      page: 1,
      size: 60,
      keyword: keyword.value.trim() || undefined,
      returnable: props.returnable,
    })
    rows.value = res.list
  } finally {
    loading.value = false
  }
}

watch(visible, (on) => {
  if (on) {
    keyword.value = ''
    void load()
  }
})

function choose(row: IssueOption) {
  visible.value = false
  emit('pick', row)
}
</script>

<template>
  <el-dialog v-model="visible" :title="returnable ? '选择生产单退料入库' : '选择生产单领料出库'" width="620px">
    <div class="search-row">
      <el-input v-model="keyword" placeholder="搜索生产单号、款号或加工厂" clearable @keyup.enter="load" />
      <el-button @click="load">搜索</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      row-key="id"
      max-height="360"
      row-class-name="clickable"
      :empty-text="returnable ? '没有已领料可退的生产单' : '没有待领料的在制生产单'"
      @row-click="choose"
    >
      <el-table-column label="生产单号" prop="no" width="150" class-name="code" />
      <el-table-column label="款式" min-width="150">
        <template #default="{ row }"><span class="code">{{ row.styleCode }}</span> {{ row.styleName }}</template>
      </el-table-column>
      <el-table-column label="加工厂" prop="factoryShortName" width="110" />
      <el-table-column label="件数" width="84" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
      </el-table-column>
      <el-table-column label="领料状态" width="108">
        <template #default="{ row }">
          <StatusTag v-if="row.issueStatus" :tone="MATERIAL_ISSUE_STATUS[(row as IssueOption).issueStatus!].tone">
            {{ MATERIAL_ISSUE_STATUS[(row as IssueOption).issueStatus!].label }}
          </StatusTag>
          <StatusTag v-else :tone="PRODUCTION_STATUS[(row as IssueOption).status].tone">
            {{ PRODUCTION_STATUS[(row as IssueOption).status].label }}
          </StatusTag>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.search-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
