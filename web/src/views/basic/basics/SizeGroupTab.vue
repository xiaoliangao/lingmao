<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { sizeGroupApi } from '@/api/basic'
import type { SizeGroup } from '@/types/basic'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { confirmRemove, orNull, toggleStatus } from '../common'

/**
 * 基础 › 尺码组（RFC 003 §5.3）。建好以后尺码只能往后追加，不能删、改、调顺序，基码不能换（款式按位置存着尺码）；
 * 名称能改。写错了又没被款式用过的，整组删掉重建
 */
const list = ref<SizeGroup[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await sizeGroupApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 和后端一样拆尺码：空格、逗号、顿号隔开，转大写，去掉重复的 */
function splitSizes(text: string) {
  return [...new Set(text.split(/[\s,，、]+/).map((s) => s.trim().toUpperCase()).filter(Boolean))]
}

// ---------- 新建 ----------
const createVisible = ref(false)
const createRef = ref<FormInstance>()
const EMPTY = { name: '', sizes: '', baseSize: '' }
const createForm = reactive({ ...EMPTY })
const createGuard = useUnsavedChanges(createForm, createVisible)
const createRules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  sizes: [{ required: true, message: '请输入尺码', trigger: 'blur' }],
}
const parsed = computed(() => splitSizes(createForm.sizes))
/** 基码不填取中间那个，偶数个取靠前的（和后端一样） */
const baseShown = computed(() => orNull(createForm.baseSize)?.toUpperCase() ?? parsed.value[Math.floor((parsed.value.length - 1) / 2)] ?? '')

function openCreate() {
  Object.assign(createForm, EMPTY)
  createVisible.value = true
}

async function create() {
  await createRef.value?.validate()
  await sizeGroupApi.create({ name: createForm.name.trim(), sizes: createForm.sizes, baseSize: orNull(createForm.baseSize) })
  ElMessage.success(`加了尺码组 ${createForm.name.trim()}`)
  createVisible.value = false
  load()
}

// ---------- 追加尺码 ----------
const appendVisible = ref(false)
const appendGroup = ref<SizeGroup | null>(null)
const appendForm = reactive({ sizes: '' })
const appendGuard = useUnsavedChanges(appendForm, appendVisible)
const appended = computed(() => splitSizes(appendForm.sizes))

function openAppend(row: SizeGroup) {
  appendGroup.value = row
  appendForm.sizes = ''
  appendVisible.value = true
}

async function append() {
  if (!appended.value.length) {
    ElMessage.warning('请输入要追加的尺码')
    return
  }
  await sizeGroupApi.appendSizes(appendGroup.value!.id, appendForm.sizes)
  ElMessage.success(`${appendGroup.value!.name} 加了 ${appended.value.join(' ')}`)
  appendVisible.value = false
  load()
}

// ---------- 改名、启用停用、删除 ----------
async function rename(row: SizeGroup) {
  const { value } = await ElMessageBox.prompt('只改名称，尺码不变。', `改尺码组 ${row.name} 的名称`, {
    confirmButtonText: '保存',
    cancelButtonText: '取消',
    inputValue: row.name,
    inputPattern: /\S/,
    inputErrorMessage: '名称不能为空',
  })
  await sizeGroupApi.rename(row.id, value.trim())
  ElMessage.success('已保存')
  load()
}

async function toggle(row: SizeGroup) {
  if (await toggleStatus(row, `尺码组 ${row.name}`, sizeGroupApi, row.id, '停用后新建款式选不到这个尺码组，已经用了它的款式不受影响。')) load()
}

async function remove(row: SizeGroup) {
  await confirmRemove(`删除尺码组 ${row.name}`, '删除以后找不回来。已经有款式用了它的删不掉，请改成停用。')
  await sizeGroupApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <FilterBar>
    <template #right>
      <el-button v-perm="'basic:basics:add'" type="primary" icon="Plus" @click="openCreate">新增尺码组</el-button>
    </template>
  </FilterBar>

  <el-table v-loading="loading" :data="list" empty-text="还没有尺码组。新建款式要选一个">
    <el-table-column prop="name" label="名称" min-width="140" />
    <el-table-column label="尺码" min-width="320">
      <template #default="{ row }">
        <span class="sizes">
          <el-tag
            v-for="z in (row as SizeGroup).sizes"
            :key="z"
            :type="z === row.baseSize ? 'primary' : 'info'"
            :effect="z === row.baseSize ? 'light' : 'plain'"
            size="small"
            disable-transitions
            :title="z === row.baseSize ? '基码' : undefined"
          >{{ z }}</el-tag>
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="baseSize" label="基码" width="90" class-name="code" />
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="250" align="right">
      <template #default="{ row }">
        <el-button v-perm="'basic:basics:edit'" link @click="openAppend(row as SizeGroup)">追加尺码</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="rename(row as SizeGroup)">改名</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="toggle(row as SizeGroup)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button v-perm="'basic:basics:remove'" link type="danger" @click="remove(row as SizeGroup)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="createVisible" title="新增尺码组" width="min(520px, 94vw)" :before-close="createGuard.beforeClose">
    <el-form ref="createRef" class="form-grid" :model="createForm" :rules="createRules" label-position="top">
      <el-form-item label="名称" prop="name">
        <el-input v-model="createForm.name" maxlength="50" placeholder="如 童装身高码" />
      </el-form-item>
      <el-form-item label="基码">
        <el-input v-model="createForm.baseSize" class="code" maxlength="16" :placeholder="baseShown ? `不填就是 ${baseShown}` : '不填取中间那个'" />
      </el-form-item>
      <el-form-item label="尺码" prop="sizes" class="full">
        <el-input v-model="createForm.sizes" class="code" placeholder="按顺序，空格隔开：110 120 130 140" />
      </el-form-item>
    </el-form>
    <p class="dim note">建好以后只能往后追加尺码，不能删、改、调顺序，基码不能换。</p>
    <template #footer>
      <span class="preview dim code">{{ parsed.length ? `${parsed.length} 个：${parsed.join(' ')}，基码 ${baseShown}` : '' }}</span>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" @click="create">新增尺码组</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="appendVisible" :title="`给 ${appendGroup?.name ?? ''} 追加尺码`" width="min(460px, 94vw)" :before-close="appendGuard.beforeClose">
    <p class="dim note">现在是 <span class="code">{{ appendGroup?.sizes.join(' ') }}</span>，新加的排在最后，加了就不能删。</p>
    <el-input v-model="appendForm.sizes" class="code" placeholder="空格隔开：XXL 3XL" @keyup.enter="append" />
    <template #footer>
      <el-button @click="appendVisible = false">取消</el-button>
      <el-button type="primary" @click="append">追加</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sizes {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 2px 0;
}

.note {
  margin: 0 0 10px;
  font-size: 12.5px;
}

.preview {
  float: left;
  max-width: 60%;
  overflow: hidden;
  font-size: 12px;
  line-height: 32px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
