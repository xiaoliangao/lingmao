<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { roleApi, userApi } from '@/api/system'
import type { Role, User } from '@/types/system'

// ---------- 列表 ----------
const query = reactive({ page: 1, size: 20, username: '', status: undefined as number | undefined })
const list = ref<User[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await userApi.page({ ...query, username: query.username || undefined })
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
const form = reactive({ username: '', password: '', nickname: '', phone: '', status: 1 })
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-z0-9_]{3,32}$/, message: '3-32 位小写字母、数字、下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32', trigger: 'blur' },
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', password: '', nickname: '', phone: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row: User) {
  editingId.value = row.id
  Object.assign(form, { username: row.username, password: '', nickname: row.nickname, phone: row.phone ?? '', status: row.status })
  dialogVisible.value = true
}

async function save() {
  await formRef.value?.validate()
  if (editingId.value === null) {
    await userApi.create({ username: form.username, password: form.password, nickname: form.nickname, phone: form.phone || undefined })
  } else {
    await userApi.update(editingId.value, { nickname: form.nickname, phone: form.phone || null, status: form.status })
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  load()
}

// ---------- 删除、重置密码 ----------
async function remove(row: User) {
  await ElMessageBox.confirm(`确定删除用户 ${row.username}？`, '提示', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

async function resetPassword(row: User) {
  const { value } = await ElMessageBox.prompt(`给 ${row.username} 设置新密码（6-32 位）`, '重置密码', {
    inputType: 'password',
    inputPattern: /^.{6,32}$/,
    inputErrorMessage: '密码长度 6-32',
  })
  await userApi.resetPassword(row.id, value)
  ElMessage.success('密码已重置')
}

// ---------- 分配角色 ----------
const roleDialogVisible = ref(false)
const roleUser = ref<User | null>(null)
const roleOptions = ref<Role[]>([])
const checkedRoleIds = ref<number[]>([])

async function openRoles(row: User) {
  roleUser.value = row
  const [options, ids] = await Promise.all([roleApi.options(), userApi.roleIds(row.id)])
  roleOptions.value = options
  checkedRoleIds.value = ids
  roleDialogVisible.value = true
}

async function saveRoles() {
  await userApi.assignRoles(roleUser.value!.id, checkedRoleIds.value)
  ElMessage.success('角色已更新')
  roleDialogVisible.value = false
}
</script>

<template>
  <div class="page">
    <div class="page-card">
      <el-form inline>
        <el-form-item label="用户名">
          <el-input v-model="query.username" clearable placeholder="模糊搜索" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <el-button v-perm="'system:user:add'" type="primary" icon="Plus" @click="openCreate">新增</el-button>
      </div>

      <el-table v-loading="loading" :data="list" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column prop="phone" label="手机号" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button v-perm="'system:user:edit'" link type="primary" @click="openEdit(row as User)">编辑</el-button>
            <el-button v-perm="'system:user:edit'" link type="primary" @click="openRoles(row as User)">分配角色</el-button>
            <el-button v-perm="'system:user:resetPwd'" link type="primary" @click="resetPassword(row as User)">重置密码</el-button>
            <el-button v-perm="'system:user:remove'" link type="danger" @click="remove(row as User)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增用户' : '编辑用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editingId !== null" />
        </el-form-item>
        <el-form-item v-if="editingId === null" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item v-if="editingId !== null" label="状态">
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

    <el-dialog v-model="roleDialogVisible" :title="`给 ${roleUser?.username} 分配角色`" width="420px">
      <el-checkbox-group v-model="checkedRoleIds">
        <el-checkbox v-for="role in roleOptions" :key="role.id" :value="role.id">{{ role.name }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
