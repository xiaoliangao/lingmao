<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { reactive, ref } from 'vue'
import { userApi } from '@/api/system'
import type { UserOption } from '@/types/system'

/**
 * 选人：输入用户名或昵称搜索，只列启用的账号，v-model 是用户 id。
 * 编辑已有记录时把这个人的名字传给 label：还没搜过，下拉框不知道这个 id 叫什么，不传就只能显示"用户 12"
 *
 *   <UserSelect v-model="form.ownerId" :label="form.ownerName" clearable />
 *
 * 筛选弹层的小标签要写选了谁：用 v-model:label，选了以后把名字写回来（清空写回 null）
 *
 *   <UserSelect v-model="query.merchId" v-model:label="merchName" clearable />
 */
const props = defineProps<{ label?: string | null }>()
const emit = defineEmits<{ 'update:label': [name: string | null] }>()
const model = defineModel<string | null>()

function onChange(id: string | null | undefined) {
  emit('update:label', id == null ? null : (names.get(id) ?? null))
}

const options = ref<UserOption[]>([])
const loading = ref(false)
/** 搜到过的人 id → 名字：换个词搜，上一批就不在列表里了，已经选中的还要能显示名字 */
const names = reactive(new Map<string, string>())
/** 搜索的序号：打字快的时候，先发的搜索后回来不能盖掉后发的 */
let seq = 0
const latest = useLatestRequest()

async function search(keyword: string) {
  const current = ++seq
  const read = latest.begin()
  loading.value = true
  try {
    const result = await read.run(() => userApi.search(keyword.trim()))
    if (current !== seq || !read.current()) return
    options.value = result
    for (const user of options.value) names.set(user.id, user.nickname)
  } catch {
    // 消息拦截器已经弹过了
    if (current === seq) options.value = []
  } finally {
    if (current === seq) loading.value = false
  }
}

function nameOf(id: string) {
  return names.get(id) ?? (props.label || `用户 ${id}`)
}
</script>

<template>
  <el-select
    v-model="model"
    filterable
    remote
    :remote-method="search"
    :loading="loading"
    placeholder="输入用户名或昵称搜索"
    no-data-text="没有找到，换个名字试试"
    @change="onChange"
  >
    <template #label="{ value }">{{ nameOf(value) }}</template>
    <el-option v-for="user in options" :key="user.id" :label="user.nickname" :value="user.id">
      <span>{{ user.nickname }}</span>
      <span class="username code">{{ user.username }}</span>
    </el-option>
  </el-select>
</template>

<style scoped>
.username {
  float: right;
  margin-left: 16px;
  font-size: 12px;
  color: var(--lm-muted);
}
</style>
