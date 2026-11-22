<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { productionApi as productionApiBase } from '@/api/production'
import SkuMatrix, { type MatrixCell, type MatrixColor } from '@/components/SkuMatrix.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { CutQtyForm, ProductionDetail } from '@/types/production'
import { formatQty } from '@/utils/format'
import { bindCompanyApi } from '@/utils/companyScope'

/**
 * 登记裁床数（docs/api/05 §3.6.3，RFC 013 §4，甲方 11-18 答 Q09）：
 *
 *   <ProdCutDialog v-model="visible" :doc="detail" @updated="refresh" />
 *
 * - 每格填这个 SKU 实际裁了多少件；空着 = 没登记，回货照旧按件数算。登记了的，回货上限 ⌊裁床数 × 1.05⌋、回够 97% 算完成
 * - 每格上限 ⌊件数 × 1.05⌋（超了标红）；比已回的还少（minCutQty）也标红，不拦输入，以后端为准
 * - 只发改了的格子
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  doc: ProductionDetail
}>()

const emit = defineEmits<{
  updated: []
}>()

const productionApi = bindCompanyApi(productionApiBase, () => props.doc.companyId)
const submitting = ref(false)
const form = reactive({ qtys: {} as Record<string, number> })
const guard = useUnsavedChanges(form, visible)

const itemMap = computed(() => new Map(props.doc.items.map((it) => [it.id, it])))
const matrixColors = computed<MatrixColor[]>(() => {
  const seen = new Map<string, MatrixColor>()
  for (const it of props.doc.items) {
    if (!seen.has(it.colorId)) seen.set(it.colorId, { id: it.colorId, name: it.colorName, code: it.colorCode, hex: it.colorHex })
  }
  return [...seen.values()]
})
const matrixCells = computed<MatrixCell[]>(() => props.doc.items.map((it) => ({ id: it.id, colorId: it.colorId, sizeCode: it.sizeCode })))
const maxOf = (id: string) => itemMap.value.get(id)?.maxCutQty
const sub = (id: string) => {
  const it = itemMap.value.get(id)
  return it ? `件数 ${it.qty} · 已回良品 ${it.returnedQty - it.defectQty}` : null
}
const tooFew = (id: string, qty: number) => {
  const it = itemMap.value.get(id)
  return it && qty > 0 && qty < it.minCutQty ? `已经回了良品 ${it.returnedQty - it.defectQty} 件，最少填 ${it.minCutQty}` : null
}
const total = computed(() => Object.values(form.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0))

watch(visible, (on) => {
  if (!on) return
  form.qtys = Object.fromEntries(props.doc.items.filter((it) => it.cutQty != null).map((it) => [it.id, it.cutQty!]))
  guard.markSaved()
})

async function submit() {
  const items: CutQtyForm['items'] = props.doc.items
    .map((it) => ({ prdOrderItemId: it.id, cutQty: form.qtys[it.id] > 0 ? form.qtys[it.id] : null, was: it.cutQty }))
    .filter((r) => r.cutQty !== r.was)
    .map(({ prdOrderItemId, cutQty }) => ({ prdOrderItemId, cutQty }))
  if (!items.length) {
    ElMessage.info('裁床数没有改')
    return
  }
  submitting.value = true
  try {
    await productionApi.registerCut(props.doc.id, { items })
    guard.markSaved()
    visible.value = false
    ElMessage.success(`登记了 ${items.length} 个 SKU 的裁床数`)
    emit('updated')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`登记裁床数 · ${doc.no}`" width="760px" :close-on-click-modal="false" :before-close="guard.beforeClose">
    <div class="sec-title num">
      <strong>裁床数</strong>
      <span class="dim">合计 {{ formatQty(total) }} 件 · 登记了的按裁床数算回货上限（多 5%）和回齐</span>
    </div>
    <SkuMatrix v-model="form.qtys" :sizes="doc.sizes" :colors="matrixColors" :skus="matrixCells" :max="maxOf" :error="tooFew" :sub="sub" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sec-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 12.5px;
}
</style>
