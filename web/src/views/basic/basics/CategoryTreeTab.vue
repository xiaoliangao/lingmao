<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { categoryApi, materialCategoryApi } from '@/api/basic'
import type { TreeNode } from '@/types/basic'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { confirmRemove, orNull, toggleStatus } from '../common'

/**
 * 基础 › 品类、物料分类（RFC 003 §5.1、§5.4），两个页签共用：
 * - 品类：三级，只有名称；款式只能挂在叶子上
 * - 物料分类：两级，一级只有名称，二级要 2 位字母编码（物料编码的前缀）；物料只能挂在二级上
 * 上级、编码建好不改（不挪节点）；同一上级下不重名；有下级的删不掉，先删下级
 */
const props = defineProps<{ kind: 'category' | 'material' }>()

const isMaterial = computed(() => props.kind === 'material')
const api = computed(() => (isMaterial.value ? materialCategoryApi : categoryApi))
const maxLevel = computed(() => (isMaterial.value ? 2 : 3))
const noun = computed(() => (isMaterial.value ? '物料分类' : '品类'))
const usedBy = computed(() => (isMaterial.value ? '物料' : '款式'))

const tree = ref<TreeNode[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tree.value = await api.value.tree()
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** id → "女装 / 外套"，编辑时显示上级用 */
const paths = computed(() => {
  const map = new Map<number, string>()
  const walk = (nodes: TreeNode[], prefix: string) => {
    for (const node of nodes) {
      const path = prefix ? `${prefix} / ${node.name}` : node.name
      map.set(node.id, path)
      walk(node.children ?? [], path)
    }
  }
  walk(tree.value, '')
  return map
})

/** 能当上级的节点（不到最末一级的），给 el-tree-select */
interface ParentOption { value: number; label: string; children?: ParentOption[] }
const parentOptions = computed(() => {
  const toOptions = (nodes: TreeNode[]): ParentOption[] =>
    nodes
      .filter((node) => node.level < maxLevel.value)
      .map((node) => ({
        value: node.id,
        label: node.status === 1 ? node.name : `${node.name}（已停用）`,
        children: toOptions(node.children ?? []),
      }))
  return toOptions(tree.value)
})

const levelOf = (parentId: number | null) => {
  if (parentId === null) return 1
  const find = (nodes: TreeNode[]): TreeNode | undefined => {
    for (const node of nodes) {
      if (node.id === parentId) return node
      const hit = find(node.children ?? [])
      if (hit) return hit
    }
    return undefined
  }
  return (find(tree.value)?.level ?? 0) + 1
}

// ---------- 新增、修改 ----------
const visible = ref(false)
const editing = ref<TreeNode | null>(null)
const formRef = ref<FormInstance>()
const EMPTY = { parentId: null as number | null, name: '', code: '', sort: 0 }
const form = reactive({ ...EMPTY })
const guard = useUnsavedChanges(form, visible)
/** 正在建、改的是第几级：物料分类的二级要编码 */
const formLevel = computed(() => (editing.value ? editing.value.level : levelOf(form.parentId)))
const needCode = computed(() => isMaterial.value && formLevel.value === 2)
const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  code: needCode.value
    ? [
        { required: true, message: '请输入编码', trigger: 'blur' },
        { pattern: /^\s*[A-Za-z]{2}\s*$/, message: '2 位字母', trigger: 'blur' },
      ]
    : [],
}))

function openCreate(parent: TreeNode | null) {
  editing.value = null
  Object.assign(form, EMPTY, { parentId: parent?.id ?? null })
  visible.value = true
}

function openEdit(row: TreeNode) {
  editing.value = row
  Object.assign(form, { parentId: row.parentId ?? null, name: row.name, code: row.code ?? '', sort: row.sort })
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  const data = {
    parentId: form.parentId,
    name: form.name.trim(),
    sort: form.sort ?? 0,
    code: needCode.value ? orNull(form.code)?.toUpperCase() ?? null : null,
  }
  if (editing.value) {
    await api.value.update(editing.value.id, data)
  } else {
    await api.value.create(data)
  }
  ElMessage.success(editing.value ? '已保存' : `加了${noun.value} ${data.name}`)
  visible.value = false
  load()
}

async function toggle(row: TreeNode) {
  const consequence = `停用后新建${usedBy.value}选不到它，已经用了它的${usedBy.value}不受影响。`
  if (await toggleStatus(row, `${noun.value} ${row.name}`, api.value, row.id, consequence)) load()
}

async function remove(row: TreeNode) {
  const extra = row.code ? `，编码 ${row.code} 可以再用` : ''
  await confirmRemove(
    `删除${noun.value} ${row.name}`,
    `删除以后找不回来${extra}。有下级的要先删下级；已经有${usedBy.value}用了它的删不掉，请改成停用。`,
  )
  await api.value.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <FilterBar>
    <template #right>
      <el-button v-perm="'basic:basics:add'" type="primary" icon="Plus" @click="openCreate(null)">
        {{ isMaterial ? '新增一级分类' : '新增一级品类' }}
      </el-button>
    </template>
  </FilterBar>

  <el-table
    v-loading="loading"
    :data="tree"
    row-key="id"
    default-expand-all
    :empty-text="isMaterial ? '还没有物料分类。先建一级（面料、辅料……），再在下面建带编码的二级' : '还没有品类。先建一级（女装、男装……），再往下分'"
  >
    <el-table-column label="名称" min-width="220">
      <template #default="{ row }">
        <span :class="{ dim: row.status !== 1 }">{{ row.name }}</span>
      </template>
    </el-table-column>
    <el-table-column v-if="isMaterial" label="编码" width="100" class-name="code">
      <template #default="{ row }">{{ row.code ?? '' }}</template>
    </el-table-column>
    <el-table-column label="层级" width="80" class-name="num">
      <template #default="{ row }">{{ row.level }} 级</template>
    </el-table-column>
    <el-table-column prop="sort" label="排序" width="80" class-name="num" />
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="240" align="right">
      <template #default="{ row }">
        <el-button v-if="row.level < maxLevel" v-perm="'basic:basics:add'" link @click="openCreate(row as TreeNode)">加下级</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="openEdit(row as TreeNode)">编辑</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="toggle(row as TreeNode)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button v-perm="'basic:basics:remove'" link type="danger" @click="remove(row as TreeNode)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog
    v-model="visible"
    :title="editing ? `编辑${noun} ${editing.name}` : `新增${noun}`"
    width="min(480px, 94vw)"
    :before-close="guard.beforeClose"
  >
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item label="上级" class="full">
        <span v-if="editing" class="dim">{{ editing.parentId == null ? '无（一级）' : paths.get(editing.parentId) }}，建好不改</span>
        <el-tree-select
          v-else
          v-model="form.parentId"
          :data="parentOptions"
          check-strictly
          filterable
          clearable
          default-expand-all
          :render-after-expand="false"
          placeholder="不选就是一级"
          no-data-text="还没有能当上级的"
        />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="50" />
      </el-form-item>
      <el-form-item v-if="needCode" label="编码" prop="code">
        <el-input v-model="form.code" class="code" maxlength="2" :disabled="!!editing" placeholder="2 位字母，物料编码的前缀" />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save">{{ editing ? '保存' : `新增${noun}` }}</el-button>
    </template>
  </el-dialog>
</template>
