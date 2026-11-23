<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ReceiveDialog from '@/views/fg/stock-in/ReceiveDialog.vue'
import FgReceiptLinks from '@/views/fg/FgReceiptLinks.vue'
import { fgReadError, useFgRead } from '@/views/fg/fgRead'
import { fgDocApi } from '@/api/fg'
import type { FgReceiptStocked } from '@/types/fg'
import CompanySelect from '@/components/doc/CompanySelect.vue'
const companyId = ref<string | null>('1')
const router = useRouter()
const receiptId = ref('9007199254742001')
const visible = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)
const group = ref<FgReceiptStocked>()
const latest = useFgRead(() => { group.value = undefined; error.value = null; loading.value = false })
async function loadGroup() {
  const read = latest.begin(), company = companyId.value
  group.value = undefined; error.value = null
  if (!company) return
  loading.value = true
  try {
    const result = await fgDocApi.byReceipts(['9007199254742001'], { companyId: company, silent: true, signal: read.signal })
    if (read.current()) group.value = result[0]
  } catch (cause) { if (read.current()) error.value = fgReadError(cause) }
  finally { if (read.current()) loading.value = false }
}
watch(companyId, loadGroup)
onMounted(loadGroup)
</script>
<template>
  <div class="page panels">
    <h1>实际回货组件与来源链接</h1>
    <CompanySelect v-model="companyId" aria-label="测试主体" style="width:200px" />
    <div class="actions">
      <el-button @click="receiptId = '9007199254742001'; visible = true">打开第一回货</el-button>
      <el-button data-testid="second-receipt" @click="receiptId = '9007199254742002'; visible = true">换第二回货</el-button>
      <el-button data-testid="close-receipt" @click="visible = false">关闭当前回货</el-button>
      <el-button data-testid="leave-receipt" @click="router.push('/synthetic-leave')">离开当前回货</el-button>
      <el-button @click="loadGroup">重查来源</el-button>
    </div>
    <section class="links-panel">
      <h2>回货关联入库单</h2>
      <FgReceiptLinks :company-id="companyId ?? ''" receipt-id="9007199254742001" :value="group" :loading="loading" :error="error" @retry="loadGroup" />
    </section>
    <ReceiveDialog v-model="visible" :company-id="companyId" :receipt-id="receiptId" />
  </div>
</template>
<style scoped>
h1 { font-size: 18px; } h2 { font-size: 14px; }
.actions { display: flex; margin: 18px 0; gap: 8px; }
.links-panel { border: 1px solid var(--lm-line); width: 500px; padding: 18px; border-radius: 6px; background: white; }
</style>
