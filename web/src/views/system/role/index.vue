<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type ElTree, type FormInstance, type FormRules } from 'element-plus'
import { menuApi, roleApi } from '@/api/system'
import type { MenuNode, Role } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

const ADMIN_CODE = 'admin'

// ---------- 列表 ----------
const query = reactive({ page: 1, size: 20, code: '', name: '' })
const list = ref<Role[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await roleApi.page({ ...query, code: query.code.trim() || undefined, name: query.name.trim() || undefined })
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
const guard = useUnsavedChanges(form, dialogVisible)
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
  await ElMessageBox.confirm(`删除以后"${row.name}"这个角色就没了。已经分给用户的删不掉，要先把它从用户身上去掉。`, '删除角色', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
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
  // 管理后台的菜单只给超级管理员，不能分给角色，这里不列出来
  menuTree.value = tree.filter((menu) => !menu.adminOnly)
  menuDialogVisible.value = true
  await nextTick()
  treeRef.value?.setCheckedKeys(leafIds(menuTree.value, new Set(ids)))
}

async function saveMenus() {
  const tree = treeRef.value!
  // 全选的 + 半选的（部分下级被勾了的上级），上级也要存，侧边栏才显示得出目录
  const ids = [...tree.getCheckedKeys(), ...tree.getHalfCheckedKeys()] as number[]
  await roleApi.assignMenus(menuRole.value!.id, ids)
  ElMessage.success('权限已保存，用户刷新页面后生效')
  menuDialogVisible.value = false
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'system:role:add'" type="primary" icon="Plus" @click="openCreate">新增角色</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.name" placeholder="角色名称" @search="search">
      <FilterItem label="编码" :text="query.code.trim()" @clear="query.code = ''">
        <el-input v-model="query.code" clearable placeholder="输入一部分也行" />
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="list" :empty-text="query.name || query.code ? '没有符合条件的角色' : '暂无角色'">
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="code" label="编码" min-width="140" class-name="code" />
      <el-table-column prop="sort" label="排序" width="80" align="right" class-name="num" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="240" align="right">
        <template #default="{ row }">
          <template v-if="row.code !== ADMIN_CODE">
            <el-button v-perm="'system:role:edit'" link @click="openEdit(row as Role)">编辑</el-button>
            <el-button v-perm="'system:role:edit'" link @click="openMenus(row as Role)">分配权限</el-button>
            <el-button v-perm="'system:role:remove'" link type="danger" @click="remove(row as Role)">删除</el-button>
          </template>
          <span v-else class="dim">拥有全部权限</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      :total="total"
      @change="load"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增角色' : '编辑角色'"
      width="min(520px, 94vw)"
      :before-close="guard.beforeClose"
    >
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如 仓管" />
        </el-form-item>
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="如 warehouse_keeper" />
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
        <el-form-item label="备注" class="full">
          <el-input v-model="form.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">{{ editingId === null ? '新增角色' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="menuDialogVisible" :title="`${menuRole?.name ?? ''}能用的功能`" width="min(460px, 94vw)">
      <p class="dim dialog-tip">勾选业务菜单里的页面和按钮；管理后台只给超级管理员</p>
      <el-tree
        v-if="menuTree.length"
        ref="treeRef"
        :data="menuTree"
        node-key="id"
        show-checkbox
        default-expand-all
        :props="{ label: 'name', children: 'children' }"
      />
      <p v-else class="dim">暂无业务菜单</p>
      <template #footer>
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!menuTree.length" @click="saveMenus">保存权限</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dialog-tip {
  margin-bottom: 12px;
  font-size: 12.5px;
}
</style>
