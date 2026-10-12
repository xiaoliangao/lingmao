<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type ElTree, type FormInstance, type FormRules } from 'element-plus'
import { menuApi, roleApi } from '@/api/system'
import type { MenuNode, Role } from '@/types/system'

const ADMIN_CODE = 'admin'

// ---------- 列表 ----------
const query = reactive({ page: 1, size: 20, code: '', name: '' })
const list = ref<Role[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await roleApi.page({ ...query, code: query.code || undefined, name: query.name || undefined })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

onMounted(load)

// ---------- 新增 / 修改 ----------
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ code: '', name: '', sort: 0, status: 1, remark: '' })
const rules: FormRules = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]*$/, message: '小写字母、数字、下划线，以字母开头', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { code: '', name: '', sort: 0, status: 1, remark: '' })
  dialogVisible.value = true
}

function openEdit(row: Role) {
  editingId.value = row.id
  Object.assign(form, { code: row.code, name: row.name, sort: row.sort, status: row.status, remark: row.remark ?? '' })
  dialogVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  const data = { ...form, remark: form.remark || null }
  if (editingId.value === null) {
    await roleApi.create(data)
  } else {
    await roleApi.update(editingId.value, data)
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  load()
}

async function remove(row: Role) {
  await ElMessageBox.confirm(`确定删除角色 ${row.name}？`, '提示', { type: 'warning' })
  await roleApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

// ---------- 分配菜单 ----------
const menuDialogVisible = ref(false)
const menuRole = ref<Role | null>(null)
const menuTree = ref<MenuNode[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()

/** 只把"叶子"（没有下级的节点）设为勾选，上级的勾选状态由树自己算；直接勾上级会把它所有下级都勾上 */
function leafIds(nodes: MenuNode[], wanted: Set<number>): number[] {
  return nodes.flatMap((node) =>
    node.children.length > 0 ? leafIds(node.children, wanted) : wanted.has(node.id) ? [node.id] : [],
  )
}

async function openMenus(row: Role) {
  menuRole.value = row
  const [tree, ids] = await Promise.all([menuApi.tree(), roleApi.menuIds(row.id)])
  menuTree.value = tree
  menuDialogVisible.value = true
  await nextTick()
  treeRef.value?.setCheckedKeys(leafIds(tree, new Set(ids)))
}

async function saveMenus() {
  const tree = treeRef.value!
  // 全选的 + 半选的（部分下级被勾了的上级），上级也要存，侧边栏才显示得出目录
  const ids = [...tree.getCheckedKeys(), ...tree.getHalfCheckedKeys()] as number[]
  await roleApi.assignMenus(menuRole.value!.id, ids)
  ElMessage.success('权限已更新')
  menuDialogVisible.value = false
}
</script>

<template>
  <div class="page">
    <div class="page-card">
      <el-form inline>
        <el-form-item label="编码">
          <el-input v-model="query.code" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="query.name" clearable @keyup.enter="search" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <el-button v-perm="'system:role:add'" type="primary" icon="Plus" @click="openCreate">新增</el-button>
      </div>

      <el-table v-loading="loading" :data="list" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="code" label="编码" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="sort" label="排序" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <template v-if="row.code !== ADMIN_CODE">
              <el-button v-perm="'system:role:edit'" link type="primary" @click="openEdit(row as Role)">编辑</el-button>
              <el-button v-perm="'system:role:edit'" link type="primary" @click="openMenus(row as Role)">分配权限</el-button>
              <el-button v-perm="'system:role:remove'" link type="danger" @click="remove(row as Role)">删除</el-button>
            </template>
            <span v-else class="hint">拥有全部权限</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :total="total"
        @change="load"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增角色' : '编辑角色'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="如 warehouse_keeper" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如 仓管" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="menuDialogVisible" :title="`${menuRole?.name} 的权限`" width="460px">
      <el-tree
        ref="treeRef"
        :data="menuTree"
        node-key="id"
        show-checkbox
        default-expand-all
        :props="{ label: 'name', children: 'children' }"
      />
      <template #footer>
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMenus">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  color: #909399;
  font-size: 13px;
}
</style>
