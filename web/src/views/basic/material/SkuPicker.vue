<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { colorApi } from '@/api/basic'
import type { ColorOption } from '@/types/basic'
import { parseSpecs, specCodeOf, type SpecCodeOverrides } from './sku'

/**
 * 选颜色 × 填规格，两两组合成 SKU（RFC 003 §7.2），新建物料和"批量加 SKU"共用。
 * 规格码和后端一样由规格算：去掉空格和末尾的 cm、mm、厘米、毫米、码，转大写；能手改（RFC 003 §7.1）。
 * 不是字母、数字、小数点的标红，后端也不收。都不选不填 = 一个不带颜色、规格的 SKU（码就是物料编码）
 *
 *   <SkuPicker v-model:color-ids="form.colorIds" v-model:specs="form.specs" v-model:spec-codes="form.specCodes"
 *              material-code="ML····" :existing="codes" />
 *   specsOf(form.specs, form.specCodes)（./sku.ts）→ 发给后端的 specs
 */
const props = defineProps<{
  /** 物料编码，拼预览用。新建时还没有，传分类码 + "····" */
  materialCode: string
  /** 已有的 SKU 码：预览里标"已有，跳过" */
  existing?: string[]
}>()
const colorIds = defineModel<string[]>('colorIds', { required: true })
const specs = defineModel<string>('specs', { required: true })
/** 手改过的规格码：规格 → 规格码 */
const specCodes = defineModel<SpecCodeOverrides>('specCodes', { required: true })

const colors = ref<ColorOption[]>([])
const keyword = ref('')
onMounted(async () => {
  colors.value = await colorApi.options()
})

const shown = computed(() => {
  const text = keyword.value.trim().toUpperCase()
  if (!text) return colors.value
  return colors.value.filter((c) => c.code.includes(text) || c.name.toUpperCase().includes(text) || colorIds.value.includes(c.id))
})

function toggle(id: string) {
  colorIds.value = colorIds.value.includes(id) ? colorIds.value.filter((x) => x !== id) : [...colorIds.value, id]
}

const picked = computed(() => colorIds.value.map((id) => colors.value.find((c) => c.id === id)).filter((c): c is ColorOption => !!c))
const parsedSpecs = computed(() => parseSpecs(specs.value, specCodes.value))

function editCode(spec: string, value: string) {
  const code = value.trim().toUpperCase()
  const next = { ...specCodes.value }
  // 改回和自动算的一样、或者清空：当没改过
  if (!code || code === specCodeOf(spec)) delete next[spec]
  else next[spec] = code
  specCodes.value = next
}

/** 预览：要建的 SKU 码；已有的标出来，规格码不合格的标出来 */
const preview = computed(() => {
  const colorCodes: (string | null)[] = picked.value.length ? picked.value.map((c) => c.code) : [null]
  const specCodes = parsedSpecs.value.length ? parsedSpecs.value : [{ spec: null, specCode: null, valid: true }]
  const existing = new Set(props.existing ?? [])
  return colorCodes.flatMap((color) =>
    specCodes.map((s) => {
      const code = [props.materialCode, color, s.specCode].filter(Boolean).join('-')
      return { code, exists: existing.has(code), valid: s.valid }
    }),
  )
})

defineExpose({ preview })
</script>

<template>
  <div class="sku-picker">
    <div class="row">
      <span class="label">颜色</span>
      <el-input v-model="keyword" class="search" size="small" clearable prefix-icon="Search" placeholder="编码、名称" />
      <span class="dim count">{{ picked.length ? `选了 ${picked.length} 个` : '不选 = 不分颜色' }}</span>
    </div>
    <div class="chips">
      <el-check-tag
        v-for="c in shown"
        :key="c.id"
        :checked="colorIds.includes(c.id)"
        class="chip"
        @change="toggle(c.id)"
      >
        <span class="swatch" :style="{ background: c.hex ?? undefined }" />
        <span class="code">{{ c.code }}</span>
        {{ c.name }}
      </el-check-tag>
      <span v-if="!shown.length" class="dim">{{ colors.length ? '没有匹配的颜色' : '还没有颜色，在 资料 › 基础 › 颜色 里加' }}</span>
    </div>
    <div class="row">
      <span class="label">规格</span>
      <el-input v-model="specs" class="code" placeholder="可以填多个，空格隔开：15cm 18cm 60cm；不填 = 不分规格" />
    </div>
    <div v-if="parsedSpecs.length" class="row codes">
      <span class="label">规格码</span>
      <span v-for="s in parsedSpecs" :key="s.spec" class="spec-code">
        <span class="dim">{{ s.spec }}</span>
        <el-input
          :model-value="s.specCode ?? ''"
          class="code"
          size="small"
          maxlength="16"
          :class="{ invalid: !s.valid }"
          title="进 SKU 码，自动算的不合适可以改"
          @update:model-value="editCode(s.spec, $event)"
        />
      </span>
    </div>
    <div class="preview">
      <span class="dim">要建 {{ preview.filter((p) => !p.exists).length }} 个 SKU：</span>
      <span v-for="p in preview.slice(0, 60)" :key="p.code" class="code" :class="{ exists: p.exists, invalid: !p.valid }" :title="p.exists ? '已有，跳过' : p.valid ? '' : '规格码只能是字母、数字、小数点，最长 16 位'">{{ p.code }}</span>
      <span v-if="preview.length > 60" class="dim">…… 一共 {{ preview.length }} 个</span>
    </div>
  </div>
</template>

<style scoped>
.sku-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.label {
  flex: none;
  width: 42px;
  color: var(--lm-muted);
  white-space: nowrap;
}

.search {
  width: 180px;
}

.count {
  font-size: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 168px;
  padding-left: 52px;
  overflow-y: auto;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 400;
}

.swatch {
  width: 12px;
  height: 12px;
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

.codes {
  flex-wrap: wrap;
  row-gap: 6px;
}

.spec-code {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.spec-code .el-input {
  width: 72px;
}

.spec-code .invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-danger-text) inset;
}

.preview {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  max-height: 96px;
  padding: 8px 10px;
  overflow-y: auto;
  font-size: 12px;
  background: var(--lm-greige-2);
  border-radius: var(--lm-radius-sm);
}

.preview .exists {
  color: var(--lm-disabled);
  text-decoration: line-through;
}

.preview .invalid {
  color: var(--lm-danger-text);
}
</style>
