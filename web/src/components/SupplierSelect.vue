<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { supplierApi } from '@/api/supplier'
import { useUserStore } from '@/stores/user'
import { SUPPLIER_STATUS, type SupplierOption, type SupplierUsage } from '@/types/supplier'
import { withCompanyScope } from '@/utils/companyScope'
import { getToken, onSessionChanged, sessionChanged, sessionEpoch } from '@/utils/token'

/**
 * 选供应商：输入编码或名称搜索，v-model 是供应商 id（RFC 004 §7）。一次最多列 50 家，再多就多打几个字。
 * - category：只列分类里有它的（字典 supplier_category 的 value，如 FABRIC 面料商、GARMENT 成衣厂）；
 *   给数组就列有其中任一个的，如默认加工厂 :category="['GARMENT', 'PROCESSOR']"
 * - usage：SAMPLE（默认）打样、录价格用，待准入、审批中、合作中的都能选；BULK 大货单用，只有合作中的
 * - 已经选中的那家（编辑旧记录）停用了、拉黑了也照样显示名字，后面标状态；想换就换，不换也能原样保存。
 *   编辑旧记录可把名字传给 label；仅有 id 时用 options.includeId 自动补齐，不必先展开。
 * 能不能选最后由后端的 checkUsable 把关，这里只是不让人选到
 *
 *   <SupplierSelect v-model="form.defaultSupplierId" category="FABRIC" :label="form.defaultSupplierName" clearable />
 *
 * 要拿选中那家的简称（筛选小标签、单据上跟着换供应商名）：用 v-model:label，选了以后写回简称（清空写回 null）
 */
const props = withDefaults(defineProps<{ category?: string | string[]; usage?: SupplierUsage; label?: string | null; companyId?: string | null }>(), {
  category: undefined,
  usage: 'SAMPLE',
  label: null,
})
const emit = defineEmits<{ 'update:label': [name: string | null] }>()
const model = defineModel<string | null>()
const user = useUserStore()
const companyId = computed(() => props.companyId ?? user.defaultCompanyId ?? undefined)
/** 接口收逗号隔开的；也拿来判断分类变没变（模板里写的数组每次渲染都是新的） */
const categoryParam = computed(() => [props.category ?? []].flat().join(',') || undefined)

const options = ref<SupplierOption[]>([])
const loading = ref(false)
/** 见过的供应商 id → 简称：换个词搜，上一批就不在列表里了，已经选中的还要能显示名字 */
const names = reactive(new Map<string, string>())
/** 父级名字只对应它送来时的 id；只换 id 不能把旧名带到新供应商。 */
const providedName = ref<{ id: string; name: string } | null>(null)
const latest = useLatestRequest()

async function search(keyword = '') {
  if (sessionChanged() || !getToken()) return
  const selectedId = model.value
  const company = companyId.value
  const epoch = sessionEpoch()
  const read = latest.begin()
  const current = () => read.current() && model.value === selectedId && companyId.value === company
    && !sessionChanged() && sessionEpoch() === epoch
  loading.value = true
  try {
    const result = await read.run(() => withCompanyScope(company, () => supplierApi.options({
      category: categoryParam.value,
      usage: props.usage,
      keyword: keyword.trim() || undefined,
      includeId: selectedId ?? undefined,
    })))
    if (!current()) return
    options.value = result
    for (const option of result) names.set(option.id, option.shortName)
  } catch {
    // 消息拦截器已经弹过了
    if (current()) options.value = []
  } finally {
    if (read.current()) loading.value = false
  }
}

function reset(clearNames = false) {
  latest.cancel()
  options.value = []
  loading.value = false
  if (clearNames) {
    names.clear()
    providedName.value = null
  }
}

// 跨页签换身份时，旧页签不再显示先前缓存的名字，也不替新身份请求数据。
onBeforeUnmount(onSessionChanged(() => reset(true)))

watch(() => ({
  id: model.value, label: props.label, company: companyId.value,
  userId: user.info?.id, token: user.token, category: categoryParam.value, usage: props.usage,
}), (next, previous) => {
  const scopeChanged = Boolean(previous && (next.company !== previous.company
    || next.userId !== previous.userId || next.token !== previous.token))
  if (!previous || scopeChanged || next.id !== previous.id || next.category !== previous.category || next.usage !== previous.usage) reset(scopeChanged)
  if (!next.id || !next.label || scopeChanged || next.id !== previous?.id) providedName.value = null
  if (!scopeChanged && next.id && next.label && (!previous || next.label !== previous.label)) {
    providedName.value = { id: next.id, name: next.label }
  }
  if (next.id && !names.has(next.id) && providedName.value?.id !== next.id) void search()
}, { immediate: true })

/** 展开时还没搜过就先列一批，不用非得打字 */
function onVisible(visible: boolean) {
  if (visible && options.value.length === 0) void search()
}

function nameOf(id: string) {
  return names.get(id) ?? (providedName.value?.id === id ? providedName.value.name : `供应商 ${id}`)
}

function onChange(id: string | null | undefined) {
  emit('update:label', id == null ? null : (names.get(id) ?? null))
}
</script>

<template>
  <el-select
    v-model="model"
    filterable
    remote
    :remote-method="search"
    :loading="loading"
    placeholder="输入编码或名称搜索"
    no-data-text="没有找到，换个词试试"
    @visible-change="onVisible"
    @change="onChange"
  >
    <template #label="{ value }">{{ nameOf(value) }}</template>
    <el-option v-for="option in options" :key="option.id" :label="option.shortName" :value="option.id">
      <span>{{ option.shortName }}</span>
      <span v-if="option.status !== 'ACTIVE'" class="status">{{ SUPPLIER_STATUS[option.status].label }}</span>
      <span class="code-tail code">{{ option.code }}</span>
    </el-option>
  </el-select>
</template>

<style scoped>
.status {
  margin-left: 8px;
  font-size: 12px;
  color: var(--lm-pending-text);
}

.code-tail {
  float: right;
  margin-left: 16px;
  color: var(--lm-muted);
}
</style>
