<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { menuApi, type MenuForm } from '@/api/system'
import type { MenuNode } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'

const TYPE_LABEL: Record<number, string> = { 1: '目录', 2: '页面', 3: '按钮' }

const tree = ref<MenuNode[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tree.value = await menuApi.tree()
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 选上级用的树：最前面加一个"顶层"，按钮不能当上级 */
const parentOptions = computed(() => {
  const strip = (nodes: MenuNode[]): MenuNode[] =>
    nodes.filter((n) => n.type !== 3).map((n) => ({ ...n, children: strip(n.children) }))
  return [{ id: 0, name: '顶层', children: strip(tree.value) }]
})

// ---------- 新增 / 修改 ----------
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const emptyForm = (): MenuForm => ({
  parentId: 0, name: '', type: 2, path: '', component: '', perms: '', icon: '', sort: 0, visible: true, status: 1,
  adminOnly: false,
})
const form = reactive<MenuForm>(emptyForm())

function openCreate(parent?: MenuNode) {
  editingId.value = null
  Object.assign(form, emptyForm(), {
    parentId: parent?.id ?? 0,
    type: parent ? (parent.type === 1 ? 2 : 3) : 1,
  })
  dialogVisible.value = true
}

function openEdit(row: MenuNode) {
  editingId.value = row.id
  Object.assign(form, {
    parentId: row.parentId, name: row.name, type: row.type, path: row.path ?? '', component: row.component ?? '',
    perms: row.perms ?? '', icon: row.icon ?? '', sort: row.sort, visible: row.visible, status: row.status,
    adminOnly: row.adminOnly,
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name) {
    ElMessage.warning('请输入名称')
    return
  }
  // 空字符串当作没填
  const data: MenuForm = {
    ...form,
    path: form.path || null,
    component: form.component || null,
    perms: form.perms || null,
    icon: form.icon || null,
  }
  if (editingId.value === null) {
    await menuApi.create(data)
  } else {
    await menuApi.update(editingId.value, data)
  }
  ElMessage.success('已保存，刷新页面后侧边栏生效')
  dialogVisible.value = false
  load()
}

async function remove(row: MenuNode) {
  await ElMessageBox.confirm(`删除"${row.name}"。下面还有菜单的删不掉，要先删下级。`, '删除菜单', { confirmButtonText: '删除', type: 'warning' })
  await menuApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <div class="page">
    <PageHeader description="侧边栏里的目录、页面，和页面上的按钮。管理后台的菜单只有超级管理员能用。">
      <template #actions>
        <el-button v-perm="'system:menu:add'" type="primary" icon="Plus" @click="openCreate()">新增目录</el-button>
      </template>
    </PageHeader>

    <div class="panel">
      <div class="panel-body">
        <el-table v-loading="loading" :data="tree" row-key="id" default-expand-all>
          <el-table-column label="名称" min-width="190">
            <template #default="{ row }">
              <span class="name">
                <el-icon v-if="row.icon"><component :is="row.icon" /></el-icon>
                {{ row.name }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="区域" width="96">
            <template #default="{ row }">
              <template v-if="row.parentId === 0">{{ row.adminOnly ? '管理后台' : '工作台' }}</template>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="64">
            <template #default="{ row }">{{ TYPE_LABEL[row.type] }}</template>
          </el-table-column>
          <el-table-column prop="perms" label="权限标识" min-width="170" class-name="code" show-overflow-tooltip />
          <el-table-column prop="path" label="路由" min-width="100" class-name="code" show-overflow-tooltip />
          <el-table-column prop="component" label="组件" min-width="170" class-name="code" show-overflow-tooltip />
          <el-table-column prop="sort" label="排序" width="64" align="right" class-name="num" />
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <StatusTag :tone="row.status === 1 ? 'done' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.type !== 3" v-perm="'system:menu:add'" link type="primary" @click="openCreate(row as MenuNode)">新增下级</el-button>
              <el-button v-perm="'system:menu:edit'" link type="primary" @click="openEdit(row as MenuNode)">编辑</el-button>
              <el-button v-perm="'system:menu:remove'" link type="danger" @click="remove(row as MenuNode)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增菜单' : '编辑菜单'" width="540px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="上级">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            check-strictly
            default-expand-all
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="form.parentId === 0" label="区域">
          <el-radio-group v-model="form.adminOnly">
            <el-radio :value="false">工作台</el-radio>
            <el-radio :value="true">管理后台（只有超级管理员）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio :value="1">目录</el-radio>
            <el-radio :value="2">页面</el-radio>
            <el-radio :value="3">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item v-if="form.type !== 3" label="路由地址">
          <el-input v-model="form.path" :placeholder="form.type === 1 ? '如 /purchase' : '如 order'" />
        </el-form-item>
        <el-form-item v-if="form.type === 2" label="组件路径">
          <el-input v-model="form.component" placeholder="如 purchase/order/index，对应 views 下的文件" />
        </el-form-item>
        <el-form-item v-if="form.type !== 1" label="权限标识">
          <el-input v-model="form.perms" placeholder="如 purchase:order:add" />
        </el-form-item>
        <el-form-item v-if="form.type !== 3" label="图标">
          <el-input v-model="form.icon" placeholder="Element Plus 图标名，如 ShoppingCart">
            <template #append>
              <el-icon v-if="form.icon"><component :is="form.icon" /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item v-if="form.type !== 3" label="侧边栏显示">
          <el-switch v-model="form.visible" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
