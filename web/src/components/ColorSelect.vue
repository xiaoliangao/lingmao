<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { colorApi } from '@/api/basic'
import type { ColorOption } from '@/types/basic'

/**
 * 挑颜色：一排色块小标签，点一下选上、再点取消，上面能按编码、名称搜（原型的颜色 chips）。
 * v-model 是颜色 id 数组，按点的先后排（款式上的颜色顺序就是它）。只列基础资料里启用的颜色。
 * exclude 里的（款式上已经有的）不列出来
 *
 *   <ColorSelect v-model="form.colorIds" />
 *   <ColorSelect v-model="adding" :exclude="detail.colors.map((c) => c.colorId)" />
 *
 * 选中的颜色对象从 picked（defineExpose）取，拼 SKU 预览用
 */
const props = withDefaults(defineProps<{ exclude?: string[] }>(), { exclude: () => [] })
const model = defineModel<string[]>({ required: true })

const colors = ref<ColorOption[]>([])
const loaded = ref(false)
const keyword = ref('')
onMounted(async () => {
  try {
    colors.value = await colorApi.options()
  } finally {
    loaded.value = true
  }
})

const available = computed(() => {
  const excluded = new Set(props.exclude)
  return colors.value.filter((c) => !excluded.has(c.id))
})

const shown = computed(() => {
  const text = keyword.value.trim().toUpperCase()
  if (!text) return available.value
  return available.value.filter((c) => c.code.includes(text) || c.name.toUpperCase().includes(text) || model.value.includes(c.id))
})

const picked = computed(() => model.value.map((id) => colors.value.find((c) => c.id === id)).filter((c): c is ColorOption => !!c))

function toggle(id: string) {
  model.value = model.value.includes(id) ? model.value.filter((x) => x !== id) : [...model.value, id]
}

const emptyText = computed(() => {
  if (!loaded.value) return '正在取颜色……'
  if (!colors.value.length) return '还没有颜色，先在 资料 › 基础 › 颜色 里加'
  if (!available.value.length) return '启用的颜色都加过了'
  return '没有匹配的颜色'
})

defineExpose({ picked })
</script>

<template>
  <div class="color-select">
    <div class="head">
      <el-input v-model="keyword" class="search" size="small" clearable prefix-icon="Search" placeholder="编码、名称" aria-label="搜颜色" />
      <span class="dim count">{{ picked.length ? `选了 ${picked.length} 个` : '点色块选上，再点取消' }}</span>
    </div>
    <div class="chips">
      <el-check-tag v-for="c in shown" :key="c.id" :checked="model.includes(c.id)" class="chip" @change="toggle(c.id)">
        <span class="swatch" :style="{ background: c.hex ?? undefined }" />
        <span class="code">{{ c.code }}</span>
        {{ c.name }}
      </el-check-tag>
      <span v-if="!shown.length" class="dim">{{ emptyText }}</span>
    </div>
  </div>
</template>

<style scoped>
.color-select {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
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
</style>
