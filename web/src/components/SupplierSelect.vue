<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, reactive, ref, watch } from 'vue'
import { supplierApi } from '@/api/supplier'
import { SUPPLIER_STATUS, type SupplierOption, type SupplierUsage } from '@/types/supplier'

/**
 * 选供应商：输入编码或名称搜索，v-model 是供应商 id（RFC 004 §7）。一次最多列 50 家，再多就多打几个字。
 * - category：只列分类里有它的（字典 supplier_category 的 value，如 FABRIC 面料商、GARMENT 成衣厂）；
 *   给数组就列有其中任一个的，如默认加工厂 :category="['GARMENT', 'PROCESSOR']"
 * - usage：SAMPLE（默认）打样、录价格用，待准入、审批中、合作中的都能选；BULK 大货单用，只有合作中的
 * - productLine：只列做这条品类线的（采购单、生产单，RFC 013 §2）；不给就列和我的线有交集的
 * - 已经选中的那家（编辑旧记录）停用了、拉黑了也照样显示名字，后面标状态；想换就换，不换也能原样保存。
 *   编辑旧记录时把名字传给 label：还没搜过，下拉框不知道这个 id 叫什么
 * 能不能选最后由后端的 checkUsable 把关，这里只是不让人选到
 *
 *   <SupplierSelect v-model="form.defaultSupplierId" category="FABRIC" :label="form.defaultSupplierName" clearable />
 *
 * 要拿选中那家的简称（筛选小标签、单据上跟着换供应商名）：用 v-model:label，选了以后写回简称（清空写回 null）
 */
const props = withDefaults(defineProps<{
  category?: string | string[]; usage?: SupplierUsage; label?: string | null; productLine?: string | null
}>(), {
  category: undefined,
  usage: 'SAMPLE',
  label: null,
  productLine: null,
})
const emit = defineEmits<{ 'update:label': [name: string | null] }>()
const model = defineModel<string | null>()
/** 接口收逗号隔开的；也拿来判断分类变没变（模板里写的数组每次渲染都是新的） */
const categoryParam = computed(() => [props.category ?? []].flat().join(',') || undefined)

const options = ref<SupplierOption[]>([])
const loading = ref(false)
/** 见过的供应商 id → 简称：换个词搜，上一批就不在列表里了，已经选中的还要能显示名字 */
const names = reactive(new Map<string, string>())
/** 搜索的序号：打字快的时候，先发的搜索后回来不能盖掉后发的 */
let seq = 0
const latest = useLatestRequest()

async function search(keyword = '') {
  const current = ++seq
  const read = latest.begin()
  loading.value = true
  try {
    const result = await read.run(() => supplierApi.options({
      category: categoryParam.value,
      usage: props.usage,
      keyword: keyword.trim() || undefined,
      includeId: model.value ?? undefined,
      productLine: props.productLine ?? undefined,
    }))
    if (current !== seq || !read.current()) return
    options.value = result
    for (const option of result) names.set(option.id, option.shortName)
  } catch {
    // 消息拦截器已经弹过了
    if (current === seq) options.value = []
  } finally {
    if (current === seq) loading.value = false
  }
}

/** 分类、用途、品类线变了，已经列出来的不对了，下次展开重新取 */
watch(() => `${categoryParam.value}|${props.usage}|${props.productLine}`, () => {
  options.value = []
})

/** 展开时还没搜过就先列一批，不用非得打字 */
function onVisible(visible: boolean) {
  if (visible && options.value.length === 0) void search()
}

/**
 * label 是哪个 id 的名字：父组件只改了 id、没改名字（退料入库按仓库自动带出加工厂）时，
 * 原来那家的名字不能安到新 id 上，也不能显示成"供应商 3"
 */
const labelFor = ref<string | null>(null)
/**
 * id 和名字放在一个 watch 里看：父组件同时换了两样（打开另一张单据）时，名字就是新 id 的；
 * 分成两个 watch 的话谁先跑说不准，会把新 id 当成没名字、多查一次。
 * 只换了 id、名字没动：名字还是原来那个 id 的。给了一个没见过名字的 id，按 includeId 取一批，名字就有了
 */
watch([model, () => props.label], ([id, label], previous) => {
  if (!previous || label !== previous[1]) labelFor.value = label ? (id ?? null) : null
  if (id && !names.has(id) && id !== labelFor.value) void search()
}, { immediate: true })

function nameOf(id: string) {
  return names.get(id) ?? (id === labelFor.value && props.label ? props.label : `供应商 ${id}`)
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
