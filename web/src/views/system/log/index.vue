<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { logApi } from '@/api/system'
import type { OperLog } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'

/** 结果分段：'' = 全部 */
const RESULT_OPTIONS = [
  { label: '全部', value: '' },
  { label: '成功', value: true },
  { label: '失败', value: false },
]
const query = reactive({ page: 1, size: 20, username: '', module: '', success: '' as '' | boolean })
const list = ref<OperLog[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await logApi.page({
      ...query,
      username: query.username.trim() || undefined,
      module: query.module.trim() || undefined,
      success: query.success === '' ? undefined : query.success,
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader />

    <FilterBar v-model:keyword="query.username" placeholder="操作人的用户名" @search="search">
      <template #left>
        <el-segmented v-model="query.success" :options="RESULT_OPTIONS" @change="search" />
      </template>
      <FilterItem label="模块" :text="query.module.trim()" @clear="query.module = ''">
        <el-input v-model="query.module" clearable placeholder="如 用户管理" />
      </FilterItem>
    </FilterBar>

    <el-table
      v-loading="loading"
      :data="list"
      :empty-text="query.username || query.module || query.success !== '' ? '没有符合条件的记录' : '暂无操作记录'"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <dl class="detail">
            <dt>方法</dt>
            <dd class="code">{{ row.method }}</dd>
            <dt>参数</dt>
            <dd class="code params">{{ row.params }}</dd>
            <template v-if="row.errorMsg">
              <dt>失败原因</dt>
              <dd>{{ row.errorMsg }}</dd>
            </template>
          </dl>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="时间" width="170" class-name="num" />
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">
          <span v-if="row.username" class="code">{{ row.username }}</span>
          <span v-else class="dim">未登录</span>
        </template>
      </el-table-column>
      <el-table-column prop="module" label="模块" width="110" />
      <el-table-column prop="action" label="动作" width="100" />
      <el-table-column label="请求" min-width="220" class-name="code">
        <template #default="{ row }">{{ row.requestMethod }} {{ row.requestUri }}</template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <StatusTag :tone="row.success ? 'done' : 'danger'">{{ row.success ? '成功' : '失败' }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90" align="right" class-name="num">
        <template #default="{ row }">{{ row.costMs }} ms</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="150" class-name="num" />
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      :total="total"
      @change="load"
    />
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 6px 12px;
  margin: 0;
  padding: 4px 48px 4px 72px;
  font-size: 12.5px;
}

dt {
  color: var(--lm-faint);
}

dd {
  margin: 0;
}

.params {
  word-break: break-all;
}
</style>
