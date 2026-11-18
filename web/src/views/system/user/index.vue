<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { roleApi, userApi } from '@/api/system'
import { companyApi } from '@/api/basic'
import type { Role, User } from '@/types/system'
import type { CompanyOption } from '@/types/basic'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

// ---------- 列表 ----------
/** 状态分段：'' = 全部 */
const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]
const query = reactive({ page: 1, size: 20, username: '', status: '' as '' | 0 | 1 })
const list = ref<User[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await userApi.page({
      ...query,
      username: query.username.trim() || undefined,
      status: query.status === '' ? undefined : query.status,
    })
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
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const EMPTY_FORM = {
  username: '', password: '', nickname: '', phone: '', status: 1,
  defaultCompanyId: null as string | null, dept: '', post: '', designerNo: '',
}
const form = reactive({ ...EMPTY_FORM })
/** 设计师编号编过款号了：不能改、清空 */
const designerNoLocked = ref(false)
const companies = ref<CompanyOption[]>([])
const guard = useUnsavedChanges(form, dialogVisible)
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-z0-9_]{3,32}$/, message: '3-32 位小写字母、数字、下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 32, message: '密码长度 8-32 位', trigger: 'blur' },
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  designerNo: [{ pattern: /^(0[1-9]|[1-9][0-9])$/, message: '两位数字 01-99', trigger: 'blur' }],
}

/** 主体公司下拉：每次打开对话框取一次（停用的不列；原来选的停用了，显示"已停用"） */
async function loadCompanies() {
  companies.value = await companyApi.options()
}

async function openCreate() {
  await loadCompanies()
  editingId.value = null
  designerNoLocked.value = false
  Object.assign(form, EMPTY_FORM)
  dialogVisible.value = true
}

async function openEdit(row: User) {
  await loadCompanies()
  editingId.value = row.id
  designerNoLocked.value = row.designerNoLocked
  Object.assign(form, {
    username: row.username, password: '', nickname: row.nickname, phone: row.phone ?? '', status: row.status,
    defaultCompanyId: row.defaultCompanyId, dept: row.dept ?? '', post: row.post ?? '', designerNo: row.designerNo ?? '',
  })
  dialogVisible.value = true
}

/** 选中的主体停用了：下拉里没有它，只剩一个 id，补一项"已停用"让人看得懂 */
function companyLabel(id: string | null) {
  const found = companies.value.find((c) => c.id === id)
  return found ? `${found.code} ${found.shortName}` : '已停用的主体'
}

async function save() {
  await formRef.value?.validate()
  const profile = {
    defaultCompanyId: form.defaultCompanyId,
    dept: form.dept.trim() || null,
    post: form.post.trim() || null,
    designerNo: form.designerNo.trim() || null,
  }
  if (editingId.value === null) {
    await userApi.create({ username: form.username, password: form.password, nickname: form.nickname, phone: form.phone || undefined, ...profile })
  } else {
    await userApi.update(editingId.value, { nickname: form.nickname, phone: form.phone || null, status: form.status, ...profile })
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  load()
}

// ---------- 删除、重置密码 ----------
async function remove(row: User) {
  await ElMessageBox.confirm(`删除后 ${row.username} 不能再登录，他做过的单据还在。`, '删除用户', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

async function resetPassword(row: User) {
  const { value } = await ElMessageBox.prompt(`给 ${row.username} 设置新密码（8-32 位）。他下次登录要用新密码。`, '重置密码', {
    confirmButtonText: '重置密码',
    cancelButtonText: '取消',
    inputType: 'password',
    inputPattern: /^.{8,32}$/,
    inputErrorMessage: '密码长度 8-32 位',
  })
  await userApi.resetPassword(row.id, value)
  ElMessage.success('密码已重置')
}

// ---------- 分配角色 ----------
const roleDialogVisible = ref(false)
const roleUser = ref<User | null>(null)
const roleOptions = ref<Role[]>([])
const checkedRoleIds = ref<string[]>([])
const roleGuard = useUnsavedChanges(checkedRoleIds, roleDialogVisible)

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
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'system:user:add'" type="primary" icon="Plus" @click="openCreate">新增用户</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.username" placeholder="用户名" @search="search">
      <template #left>
        <el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" />
      </template>
    </FilterBar>

    <el-table v-loading="loading" :data="list" :empty-text="query.username || query.status !== '' ? '没有符合条件的用户' : '暂无用户'">
      <el-table-column prop="username" label="用户名" min-width="140" class-name="code" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column prop="phone" label="手机号" min-width="130" class-name="num" />
      <el-table-column label="部门 · 岗位" min-width="140">
        <template #default="{ row }">{{ [row.dept, row.post].filter(Boolean).join(' · ') || '—' }}</template>
      </el-table-column>
      <el-table-column label="设计师编号" width="100" class-name="code">
        <template #default="{ row }">{{ row.designerNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" class-name="num" />
      <el-table-column label="操作" width="300" align="right">
        <template #default="{ row }">
          <el-button v-perm="'system:user:edit'" link @click="openEdit(row as User)">编辑</el-button>
          <el-button v-perm="'system:user:edit'" link @click="openRoles(row as User)">分配角色</el-button>
          <el-button v-perm="'system:user:resetPwd'" link @click="resetPassword(row as User)">重置密码</el-button>
          <el-button v-perm="'system:user:remove'" link type="danger" @click="remove(row as User)">删除</el-button>
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
      :title="editingId === null ? '新增用户' : '编辑用户'"
      width="min(520px, 94vw)"
      :before-close="guard.beforeClose"
    >
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editingId !== null" placeholder="登录用，小写字母、数字、下划线" />
        </el-form-item>
        <el-form-item v-if="editingId === null" label="初始密码" prop="password">
          <!-- new-password：不让浏览器把管理员自己的登录密码自动填进来 -->
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password placeholder="8-32 位" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="显示在侧边栏和日志里" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="form.dept" maxlength="64" placeholder="如 设计部" />
        </el-form-item>
        <el-form-item label="岗位">
          <el-input v-model="form.post" maxlength="64" placeholder="如 设计师" />
        </el-form-item>
        <el-form-item label="默认主体公司">
          <el-select v-model="form.defaultCompanyId" filterable clearable placeholder="新建单据时带出来" no-data-text="还没有主体公司，在 资料 › 基础 里加">
            <el-option v-for="c in companies" :key="c.id" :label="`${c.code} ${c.shortName}`" :value="c.id" />
            <el-option
              v-if="form.defaultCompanyId !== null && !companies.some((c) => c.id === form.defaultCompanyId)"
              :label="companyLabel(form.defaultCompanyId)"
              :value="form.defaultCompanyId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="设计师编号" prop="designerNo">
          <el-input
            v-model="form.designerNo"
            maxlength="2"
            :disabled="designerNoLocked"
            :placeholder="designerNoLocked ? '' : '两位数字，进款号；不是设计师不填'"
          />
          <span v-if="designerNoLocked" class="dim hint">已经用来编过款号，不能改</span>
        </el-form-item>
        <el-form-item v-if="editingId !== null" label="状态" class="full">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用（马上登不进来）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">{{ editingId === null ? '新增用户' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="roleDialogVisible"
      :title="`给 ${roleUser?.nickname ?? ''} 分配角色`"
      width="min(440px, 94vw)"
      :before-close="roleGuard.beforeClose"
    >
      <el-checkbox-group v-if="roleOptions.length" v-model="checkedRoleIds">
        <el-checkbox v-for="role in roleOptions" :key="role.id" :value="role.id">{{ role.name }}</el-checkbox>
      </el-checkbox-group>
      <p v-else class="dim">暂无角色，先在角色管理里新增</p>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存角色</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>
