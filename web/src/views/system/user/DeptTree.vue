<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { deptApi } from '@/api/system'
import type { Dept } from '@/types/system'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

/**
 * 用户页左边的部门树（RFC 018 §1.3）：最上面"全部"，点一个部门右边只列这个部门和它下级的人。
 * 鼠标移到部门上出三个小按钮：编辑（改名、换上级、排序）、加下级、删除；树顶上"+ 部门"加顶层的。
 * 树改过以后发 changed，页面拿它刷新用户列表（部门名、路径跟着变）和对话框里的部门下拉
 *
 *   <DeptTree v-model="query.deptId" @update:model-value="search" @changed="onDeptsChanged" />
 */
const selected = defineModel<string | undefined>()
const emit = defineEmits<{ changed: [tree: Dept[]] }>()

const tree = ref<Dept[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tree.value = await deptApi.tree()
  } finally {
    loading.value = false
  }
  emit('changed', tree.value)
}

defineExpose({ load })

function pick(id: string | undefined) {
  selected.value = selected.value === id ? undefined : id
}

// ---------- 新建、编辑 ----------
const dialogVisible = ref(false)
const editing = ref<Dept | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', parentId: null as string | null, sort: 0 })
const guard = useUnsavedChanges(form, dialogVisible)
const rules: FormRules = {
  name: [
    { required: true, message: '请填部门名称', trigger: 'blur' },
    { max: 32, message: '最多 32 个字', trigger: 'blur' },
  ],
}

/** 编辑时不能把它挪到自己或自己的下级下面：上级下拉里去掉这一枝 */
const parentOptions = computed(() => {
  const skip = editing.value?.id
  const strip = (nodes: Dept[]): Dept[] =>
    nodes.filter((n) => n.id !== skip).map((n) => ({ ...n, children: strip(n.children) }))
  return strip(tree.value)
})

function openCreate(parent: Dept | null) {
  editing.value = null
  Object.assign(form, { name: '', parentId: parent?.id ?? null, sort: (parent ? parent.children : tree.value).length })
  dialogVisible.value = true
}

function openEdit(dept: Dept) {
  editing.value = dept
  Object.assign(form, { name: dept.name, parentId: dept.parentId, sort: dept.sort })
  dialogVisible.value = true
}

const saving = ref(false)
async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const data = { name: form.name.trim(), parentId: form.parentId || null, sort: form.sort ?? 0 }
    if (editing.value) await deptApi.update(editing.value.id, data)
    else await deptApi.create(data)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(dept: Dept) {
  await ElMessageBox.confirm(`删除部门「${dept.path}」。下面还有部门或者还有人的删不掉，会告诉你是哪些。`, '删除部门', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await deptApi.remove(dept.id)
  ElMessage.success('已删除')
  if (selected.value === dept.id) selected.value = undefined
  await load()
}
</script>

<template>
  <nav v-loading="loading" class="dept-nav" aria-label="部门">
    <div class="nav-top">
      <button type="button" class="nav-i head" :class="{ on: selected === undefined }" @click="selected = undefined">全部</button>
      <el-button v-perm="'system:user:edit'" link icon="Plus" class="add" @click="openCreate(null)">部门</el-button>
    </div>
    <el-tree
      v-if="tree.length"
      :data="tree"
      node-key="id"
      :props="{ label: 'name', children: 'children' }"
      default-expand-all
      :expand-on-click-node="false"
      @node-click="(d: Dept) => pick(d.id)"
    >
      <template #default="{ data }">
        <span class="node" :class="{ on: selected === (data as Dept).id }">
          <span class="name">{{ (data as Dept).name }}</span>
          <span v-if="(data as Dept).userCount" class="count num">{{ (data as Dept).userCount }}</span>
          <span v-perm="'system:user:edit'" class="ops" @click.stop>
            <el-button link size="small" icon="Edit" title="编辑" aria-label="编辑" @click="openEdit(data as Dept)" />
            <el-button link size="small" icon="Plus" title="加下级" aria-label="加下级" @click="openCreate(data as Dept)" />
            <el-button link size="small" icon="Delete" title="删除" aria-label="删除" @click="remove(data as Dept)" />
          </span>
        </span>
      </template>
    </el-tree>
    <p v-else-if="!loading" class="empty">还没有部门。点上面的"+ 部门"照组织架构建，再到用户里挂上人。</p>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑部门' : '新建部门'"
      width="min(420px, 94vw)"
      append-to-body
      :before-close="guard.beforeClose"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="save">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="32" placeholder="如 设计组" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            filterable
            clearable
            default-expand-all
            :render-after-expand="false"
            placeholder="不选就是顶层部门"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" />
          <span class="dim hint">同一级里数小的在前</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存' : '新建部门' }}</el-button>
      </template>
    </el-dialog>
  </nav>
</template>

<style scoped>
.dept-nav {
  flex: none;
  width: 220px;
  padding: 10px 8px;
  overflow: auto;
  border-right: 1px solid var(--lm-line-soft);
}

.nav-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.nav-i {
  flex: 1;
  min-height: 30px;
  padding: 4px 10px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: none;
  border: none;
  border-radius: var(--lm-radius-sm);
}

.nav-i:hover {
  background: var(--lm-hover);
}

.nav-i.head {
  font-size: 12px;
  font-weight: 600;
  color: var(--lm-muted);
}

.nav-i.on {
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
}

.add {
  margin-left: 4px;
  font-size: 12px;
}

.dept-nav :deep(.el-tree) {
  background: transparent;
}

.dept-nav :deep(.el-tree-node__content) {
  height: 30px;
  border-radius: var(--lm-radius-sm);
}

.node {
  display: flex;
  flex: 1;
  align-items: center;
  min-width: 0;
  gap: 6px;
  padding-right: 4px;
  font-size: 13px;
}

.node.on {
  font-weight: 500;
  color: var(--lm-accent-2);
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.count {
  font-size: 12px;
  color: var(--lm-muted);
}

/* 小按钮平时藏着，移到这一行（或用键盘移进来）才出 */
.ops {
  display: none;
  margin-left: auto;
}

.dept-nav :deep(.el-tree-node__content:hover) .ops,
.dept-nav :deep(.el-tree-node__content:focus-within) .ops {
  display: inline-flex;
}

.ops :deep(.el-button + .el-button) {
  margin-left: 2px;
}

.empty {
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--lm-muted);
}

.hint {
  margin-left: 10px;
  font-size: 12px;
}
</style>
