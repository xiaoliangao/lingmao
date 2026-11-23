<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
const params = new URLSearchParams(location.search)
const small = params.get('small') === 'true'
const wide = params.get('wide') === '51' ? 51 : params.get('wide') === '63' ? 63 : 0
const maxDigits = small || wide ? 8 : 7
const sizes = reactive(Array.from({ length: wide || (small ? 2 : 20) }, (_, index) => `S${index + 1}`))
const colors = reactive(Array.from({ length: small || wide ? 2 : 50 }, (_, index) => ({ id: String(index + 1), name: `颜色${index + 1}`, orderable: true, reason: '该颜色停用' })))
const skus = reactive(colors.flatMap((color, row) => sizes.map((sizeCode, col) => ({ id: (9007199254740993n + BigInt(row * sizes.length + col)).toString(), colorId: color.id, sizeCode, status: 1 }))))
const quantities = ref<Record<string, number>>({})
const readonly = ref(false)
const limits = reactive<Record<string, number>>({})
const descriptions = reactive<Record<string, string>>({})
const problems = reactive<Record<string, string>>({})
const total = computed(() => Object.values(quantities.value).reduce((sum, value) => sum + value, 0))
const first = skus[0]!.id
const max = (id: string) => limits[id] ?? null
const sub = (id: string) => descriptions[id] ?? null
const error = (id: string) => problems[id] ?? null
let changeTopologyOnInput = false
function acceptQuantities(value: Record<string, number>) {
  quantities.value = value
  if (!changeTopologyOnInput) return
  changeTopologyOnInput = false
  sizes.reverse()
  for (let index = 0; index < skus.length; index++) skus[index]!.sizeCode = sizes[index % sizes.length]!
}
function replaceSource() {
  sizes.reverse()
  skus.splice(0, skus.length, ...colors.flatMap((color, row) => sizes.map((sizeCode, col) => ({ id: (9007199254750993n + BigInt(row * sizes.length + col)).toString(), colorId: color.id, sizeCode, status: 1 }))))
  quantities.value = { [skus[0]!.id]: 17 }
}
function clearSource() { quantities.value = {}; sizes.reverse() }
</script>
<template>
  <aside v-if="wide" class="layout-sidebar">240 px 侧栏空间</aside>
  <main :class="{ 'with-sidebar': !!wide }">
    <h1>实际 SkuMatrix · {{ colors.length }} 色 × {{ sizes.length }} 码</h1>
    <p>总件数 <output data-testid="grand-total">{{ total }}</output></p>
    <nav>
      <button @click="readonly = !readonly">切换只读</button>
      <button @click="limits[first] = 100">上限改为100</button>
      <button @click="delete limits[first]; problems[first] = '合成业务校验失败'">业务错误</button>
      <button @click="descriptions[first] = '库存 100'">增加格子说明</button>
      <button @click="skus[1]!.status = 0">停用第二SKU</button>
      <button @click="colors[1]!.orderable = false">停用第二颜色</button>
      <template v-if="small">
        <button @click="changeTopologyOnInput = true">下次输入同时换码序与映射</button>
        <button @click="replaceSource">替换完整来源并预填17</button>
        <button @click="clearSource">清空数量并换顺序</button>
      </template>
    </nav>
    <section class="matrix-scroll">
      <SkuMatrix :model-value="quantities" :sizes="sizes" :colors="colors" :skus="skus" :max-digits="maxDigits" :readonly="readonly" :max="max" :sub="sub" :error="error" @update:model-value="acceptQuantities">
        <template #prepend="{ sizes: shown }"><tr class="custom-prepend"><td>原有前置行</td><td v-for="size in shown" :key="size">{{ size }}</td><td>合计</td></tr></template>
      </SkuMatrix>
    </section>
  </main>
</template>
<style scoped>
main { padding: 24px; } h1 { font-size: 18px; } nav { display: flex; gap: 10px; margin-bottom: 14px; }
.with-sidebar { margin-left: 240px; }
.layout-sidebar { position: fixed; inset: 0 auto 0 0; width: 240px; padding: 24px; background: var(--lm-subtle); color: var(--lm-muted); }
button { padding: 5px 10px; border: 1px solid var(--lm-line); border-radius: 4px; background: var(--lm-paper); cursor: pointer; }
.matrix-scroll { max-height: 580px; overflow-y: auto; border: 1px solid var(--lm-line); }
</style>
