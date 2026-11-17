<script setup lang="ts">
import { computed, ref } from 'vue'
import { hasSeparator, MAX_CODES as MAX, splitCodes as split } from '@/utils/keywords'

/**
 * 搜索框里的"批量"：点开一个多行框，粘几个单号、款号、编码一起查（PRD §5.4，docs/api/README §3.2）。
 * 一行一个，从 Excel 复制一列直接粘进来就行；空格、逗号、顿号、分号隔开也认。查的时候拼成空格隔开的关键字交给列表接口，
 * 后端拆开精确查（common/page/Keywords，前端口径在 utils/keywords.ts），名称照样按整句包含查。关键字已经是几个编码时，按钮上显示个数。
 * 放在 el-input 的 suffix 插槽里，FilterBar 传 batch、DataPick 自己带：
 *
 *   <el-input v-model="keyword" …><template #suffix><BatchKeyword v-model="keyword" noun="单号、款号" @search="search" /></template></el-input>
 */
const keyword = defineModel<string>({ required: true })
const props = defineProps<{ noun: string }>()
const emit = defineEmits<{ search: [] }>()

/** 关键字里有分隔符才算"几个一起查"，和后端一个口径 */
const current = computed(() => (hasSeparator(keyword.value) ? split(keyword.value).length : 0))

const open = ref(false)
const text = ref('')
const draft = computed(() => split(text.value))
const tooMany = computed(() => draft.value.length > MAX)

function onShow() {
  text.value = split(keyword.value).join('\n')
}

function apply() {
  if (tooMany.value) return
  keyword.value = draft.value.join(' ')
  open.value = false
  emit('search')
}
</script>

<template>
  <el-popover v-model:visible="open" trigger="click" placement="bottom-end" :width="300" @show="onShow">
    <template #reference>
      <button type="button" class="bk-btn" :class="{ on: current }" :title="`一次查多个${props.noun}`">
        批量<b v-if="current">{{ current }}</b>
      </button>
    </template>
    <div class="bk">
      <el-input
        v-model="text"
        type="textarea"
        :rows="9"
        resize="none"
        :placeholder="`一行一个${props.noun}\n从 Excel 复制一列粘进来就行\n空格、逗号隔开也行`"
        :aria-label="`一次查多个${props.noun}`"
        @keydown.enter.meta.prevent="apply"
        @keydown.enter.ctrl.prevent="apply"
      />
      <footer>
        <span :class="tooMany ? 'over' : 'dim'">{{ tooMany ? `一次最多 ${MAX} 个，现在 ${draft.length} 个` : `${draft.length} 个` }}</span>
        <span>
          <el-button size="small" text :disabled="!text" @click="text = ''">清空</el-button>
          <el-button size="small" type="primary" :disabled="tooMany" @click="apply">查询</el-button>
        </span>
      </footer>
    </div>
  </el-popover>
</template>

<style scoped>
.bk-btn {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  height: 20px;
  padding: 0 6px;
  margin-right: -4px;
  font: inherit;
  font-size: 12px;
  color: var(--lm-muted);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-left: 1px solid var(--lm-line-soft);
  border-radius: 0;
}

.bk-btn:hover {
  color: var(--lm-ink);
}

.bk-btn.on {
  font-weight: 500;
  color: var(--lm-accent-2);
}

.bk-btn b {
  font-weight: 600;
}

.bk {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bk footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}

.over {
  color: var(--lm-danger-text);
}
</style>
