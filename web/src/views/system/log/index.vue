<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { logApi } from '@/api/system'
import type { OperLog } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'

const query = reactive({ page: 1, size: 20, username: '', module: '', success: undefined as boolean | undefined })
const list = ref<OperLog[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await logApi.page({ ...query, username: query.username || undefined, module: query.module || undefined })
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

function reset() {
  Object.assign(query, { username: '', module: '', success: undefined })
  search()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader description="谁在什么时候做了什么。失败的操作也会记下来，密码一律打码。" />

    <div class="panel">
      <FilterBar @search="search" @reset="reset">
        <el-form-item label="操作人">
          <el-input v-model="query.username" clearable />
        </el-form-item>
        <el-form-item label="模块">
          <el-input v-model="query.module" clearable placeholder="如 用户管理" />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.success" clearable placeholder="全部">
            <el-option label="成功" :value="true" />
            <el-option label="失败" :value="false" />
          </el-select>
        </el-form-item>
      </FilterBar>

      <div class="panel-body">
        <el-table v-loading="loading" :data="list" empty-text="没有符合条件的记录">
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
              <span v-else class="muted">未登录</span>
            </template>
          </el-table-column>
          <el-table-column prop="module" label="模块" width="110" />
          <el-table-column prop="action" label="动作" width="100" />
          <el-table-column label="请求" min-width="220" class-name="code">
            <template #default="{ row }">{{ row.requestMethod }} {{ row.requestUri }}</template>
          </el-table-column>
          <el-table-column label="结果" width="80">
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
          :total="total"
          @change="load"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 6px 12px;
  margin: 0;
  padding: 4px 48px;
  font-size: 13px;
}

dt {
  color: var(--lm-muted);
}

dd {
  margin: 0;
}

.params {
  word-break: break-all;
}
</style>
