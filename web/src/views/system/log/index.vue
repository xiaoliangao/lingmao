<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { logApi } from '@/api/system'
import type { OperLog } from '@/types/system'

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

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-card">
      <el-form inline>
        <el-form-item label="操作人">
          <el-input v-model="query.username" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="模块">
          <el-input v-model="query.module" clearable placeholder="如 用户管理" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.success" clearable placeholder="全部" style="width: 110px">
            <el-option label="成功" :value="true" />
            <el-option label="失败" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="list" border>
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <p><b>方法：</b>{{ row.method }}</p>
              <p><b>参数：</b><code>{{ row.params }}</code></p>
              <p v-if="row.errorMsg"><b>失败原因：</b>{{ row.errorMsg }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="username" label="操作人" width="110" />
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="action" label="动作" width="100" />
        <el-table-column label="请求" min-width="220">
          <template #default="{ row }">{{ row.requestMethod }} {{ row.requestUri }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs }} ms</template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" />
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
</template>

<style scoped>
.detail {
  padding: 0 24px;
  font-size: 13px;
}

code {
  word-break: break-all;
}
</style>
