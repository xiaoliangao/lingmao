<script lang="ts">
import { companyApi } from '@/api/basic'
import type { CompanyOption } from '@/types/basic'

/** 模块级：同一次打开的页面共用一份；取失败清掉，下次再取 */
let cache: Promise<CompanyOption[]> | null = null
function loadOptions() {
  cache ??= companyApi.options().catch((e: unknown) => {
    cache = null
    throw e
  })
  return cache
}
</script>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * 选主体（我方公司）：启用的主体，能搜。订单、采购单、出入库、盘点的单头用。v-model 是主体 id。
 * 空着发 null = 后端用我的默认主体（docs/api/02 §3.5）：登录信息里没有默认主体，前端没法预先选上，占位写"我的默认主体"。
 * 主体就几个，一次取全，同一次打开的页面共用一份
 *
 *   <CompanySelect v-model="form.companyId" clearable placeholder="我的默认主体" />
 */
const model = defineModel<number | null>()

const options = ref<CompanyOption[]>([])

onMounted(async () => {
  try {
    options.value = await loadOptions()
  } catch {
    // 消息拦截器已经弹过了；下拉空着，主体留空用默认的
  }
})
</script>

<template>
  <el-select v-model="model" filterable :value-on-clear="null">
    <el-option v-for="c in options" :key="c.id" :label="c.shortName" :value="c.id">
      <span>{{ c.shortName }}</span>
      <span class="code-tail code">{{ c.code }}</span>
    </el-option>
  </el-select>
</template>

<style scoped>
.code-tail {
  float: right;
  margin-left: 16px;
  color: var(--lm-muted);
}
</style>
