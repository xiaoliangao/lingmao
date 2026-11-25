<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TableInstance } from 'element-plus'
import { roleApi, userApi, userUpdateForm } from '@/api/system'
import { companyApi } from '@/api/basic'
import type { Dept, Role, User } from '@/types/system'
import type { CompanyOption } from '@/types/basic'
import type { BatchAction } from '@/components/batchBar'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import DictSelect from '@/components/DictSelect.vue'
import BatchBar from '@/components/BatchBar.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import DeptTree from './DeptTree.vue'
import { PRODUCT_LINE_DICT } from '@/composables/useProductLines'
import { useDict } from '@/composables/useDict'
import { useColumns } from '@/composables/useColumns'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

// ---------- 列表 ----------
/** 状态分段：'' = 全部 */
const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '启用', value: 1 },
  { label: '停用', value: 0 },
]
const query = reactive({
  page: 1, size: 20, keyword: '', status: '' as '' | 0 | 1, deptId: undefined as string | undefined, roleId: '' as string,
})
const list = ref<User[]>([])
const total = ref(0)
const loading = ref(false)
const tableRef = ref<TableInstance>()
const selected = ref<User[]>([])

const cols = useColumns('users', [
  { key: 'phone', label: '手机号' },
  { key: 'depts', label: '部门' },
  { key: 'post', label: '岗位' },
  { key: 'roles', label: '角色' },
  { key: 'designerNo', label: '设计师编号' },
  { key: 'productLines', label: '品类线' },
  { key: 'createTime', label: '创建时间', hidden: true },
])

const loadRequest = useLatestRequest()
async function load() {
  const read = loadRequest.begin()
  loading.value = true
  try {
    const result = await read.run(() => userApi.page({
      page: query.page,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status === '' ? undefined : query.status,
      deptId: query.deptId,
      roleId: query.roleId || undefined,
    }))
    if (!read.current()) return
    list.value = result.list
    total.value = result.total
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

const filtered = computed(() => !!(query.keyword || query.status !== '' || query.deptId || query.roleId))

const lineDict = useDict(PRODUCT_LINE_DICT)
const roleOptions = ref<Role[]>([])
const roleName = computed(() => roleOptions.value.find((r) => r.id === query.roleId)?.name ?? '')

// ---------- 部门树 ----------
const deptTreeRef = ref<InstanceType<typeof DeptTree>>()
/** 部门树的数据，对话框里的部门下拉也用它 */
const depts = ref<Dept[]>([])

/** 部门改过名、挪过：列表里的部门路径跟着变 */
function onDeptsChanged(tree: Dept[]) {
  const first = depts.value.length === 0
  depts.value = tree
  if (!first) load()
}

onMounted(async () => {
  load()
  deptTreeRef.value?.load()
  roleOptions.value = await roleApi.options()
})

// ---------- 新增 / 修改 ----------
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const EMPTY_FORM = {
  username: '', password: '', nickname: '', phone: '', status: 1,
  defaultCompanyId: null as string | null, deptIds: [] as string[], post: '', designerNo: '', productLines: [] as string[],
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
  productLines: [{ type: 'array', required: true, min: 1, message: '至少选一条品类线', trigger: 'change' }],
}

/** 主体公司下拉：每次打开对话框取一次（停用的不列；原来选的停用了，显示"已停用"） */
async function loadCompanies() {
  companies.value = await companyApi.options()
}

async function openCreate() {
  await loadCompanies()
  editingId.value = null
  designerNoLocked.value = false
  // 在左边选了部门再点新增：默认挂在这个部门
  Object.assign(form, { ...EMPTY_FORM, deptIds: query.deptId ? [query.deptId] : [], productLines: [] })
  dialogVisible.value = true
}

async function openEdit(row: User) {
  await loadCompanies()
  editingId.value = row.id
  designerNoLocked.value = row.designerNoLocked
  Object.assign(form, {
    username: row.username, password: '', nickname: row.nickname, phone: row.phone ?? '', status: row.status,
    defaultCompanyId: row.defaultCompanyId, deptIds: row.depts.map((d) => d.id), post: row.post ?? '',
    designerNo: row.designerNo ?? '', productLines: [...row.productLines],
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
    deptIds: form.deptIds,
    post: form.post.trim() || null,
    designerNo: form.designerNo.trim() || null,
    productLines: form.productLines,
  }
  if (editingId.value === null) {
    await userApi.create({ username: form.username, password: form.password, nickname: form.nickname, phone: form.phone || undefined, ...profile })
  } else {
    await userApi.update(editingId.value, { nickname: form.nickname, phone: form.phone || null, status: form.status, ...profile })
  }
  ElMessage.success('已保存')
  dialogVisible.value = false
  load()
  deptTreeRef.value?.load()
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
  deptTreeRef.value?.load()
}

async function resetPassword(row: User) {
  const { value } = await ElMessageBox.prompt(
    `给 ${row.username} 设置一个临时密码（8-32 位）。他用它登录后要先改成自己的密码，才能用系统。`,
    '重置密码',
    {
      confirmButtonText: '重置密码',
      cancelButtonText: '取消',
      inputType: 'password',
      inputPattern: /^.{8,32}$/,
      inputErrorMessage: '密码长度 8-32 位',
    },
  )
  await userApi.resetPassword(row.id, value)
  ElMessage.success('密码已重置')
  load()
}

// ---------- 分配角色 ----------
const roleDialogVisible = ref(false)
const roleUser = ref<User | null>(null)
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
  load()
}

// ---------- 导入（RFC 018 §3） ----------
const importVisible = ref(false)
/** 所有导进来的人共用的初始密码；关掉对话框就清掉，不留在页面里 */
const importPassword = ref('')
const importReady = computed(() => importPassword.value.length >= 8 && importPassword.value.length <= 32)
watch(importVisible, (open) => {
  if (!open) importPassword.value = ''
})

function onImported() {
  load()
  deptTreeRef.value?.load()
}

// ---------- 批量（RFC 018 §3.3） ----------
const batchRef = ref<{ run: (action: BatchAction<User>) => Promise<void> }>()
const batchActions: BatchAction<User>[] = [
  {
    label: '启用', perm: 'system:user:edit', can: (u) => u.status === 0,
    run: (u, o) => userApi.update(u.id, { ...userUpdateForm(u), status: 1 }, o),
  },
  {
    label: '停用', perm: 'system:user:edit', can: (u) => u.status === 1,
    confirm: '停用的人马上登不进来，做过的单据还在，随时可以启用。',
    run: (u, o) => userApi.update(u.id, { ...userUpdateForm(u), status: 0 }, o),
  },
]

/** 要先选个值的三种：选角色 / 选品类线的小对话框 */
type PickMode = 'addRole' | 'removeRole' | 'lines'
const PICK_TITLES: Record<PickMode, string> = { addRole: '加角色', removeRole: '去角色', lines: '设品类线' }
const pickVisible = ref(false)
const pickMode = ref<PickMode>('addRole')
const pickRoleId = ref('')
const pickLines = ref<string[]>([])

function openPick(mode: PickMode) {
  pickMode.value = mode
  pickRoleId.value = ''
  pickLines.value = []
  pickVisible.value = true
}

const pickReady = computed(() => (pickMode.value === 'lines' ? pickLines.value.length > 0 : !!pickRoleId.value))

function runPick() {
  const mode = pickMode.value
  pickVisible.value = false
  if (mode === 'lines') {
    const lines = [...pickLines.value]
    const text = lines.map((l) => lineDict.labelOf(l)).join('、')
    batchRef.value?.run({
      label: '设品类线',
      confirm: `选中的人只能看「${text}」的款式、单据、供应商，原来的品类线换掉。`,
      can: (u) => u.productLines.join() !== lines.join(),
      run: (u, o) => userApi.update(u.id, { ...userUpdateForm(u), productLines: lines }, o),
    })
    return
  }
  const role = roleOptions.value.find((r) => r.id === pickRoleId.value)
  if (!role) return
  const has = (u: User) => u.roles.some((r) => r.id === role.id)
  // 角色接口是整体覆盖：先取这个人现在的角色（列表里的可能已经过时），加上或去掉这一个再存
  batchRef.value?.run(mode === 'addRole'
    ? {
        label: '加角色',
        confirm: `给选中的人加上「${role.name}」，原来的角色不动。`,
        can: (u) => !has(u),
        run: async (u, o) => {
          const ids = await userApi.roleIds(u.id, o)
          if (!ids.includes(role.id)) await userApi.assignRoles(u.id, [...ids, role.id], o)
        },
      }
    : {
        label: '去角色',
        confirm: `去掉选中的人的「${role.name}」，别的角色不动。`,
        can: has,
        run: async (u, o) => {
          const ids = await userApi.roleIds(u.id, o)
          await userApi.assignRoles(u.id, ids.filter((id) => id !== role.id), o)
        },
      })
}

function emptyText() {
  if (filtered.value) return '没有符合条件的用户'
  return '还没有用户：点右上角新增，或者按领猫的用户列表导入'
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'system:user:import'" icon="Upload" @click="importVisible = true">导入</el-button>
        <el-button v-perm="'system:user:add'" type="primary" icon="Plus" @click="openCreate">新增用户</el-button>
      </template>
    </PageHeader>

    <div class="with-nav">
      <DeptTree ref="deptTreeRef" v-model="query.deptId" @update:model-value="search" @changed="onDeptsChanged" />

      <div class="main">
        <FilterBar v-model:keyword="query.keyword" placeholder="用户名、昵称、手机号" :columns="cols" @search="search">
          <template #left>
            <el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" />
          </template>
          <FilterItem label="角色" :text="roleName" @clear="query.roleId = ''; search()">
            <el-select v-model="query.roleId" filterable clearable placeholder="全部角色" @change="search">
              <el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
          </FilterItem>
        </FilterBar>

        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="list"
          row-key="id"
          scrollbar-always-on
          :empty-text="emptyText()"
          @selection-change="selected = $event"
        >
          <el-table-column type="selection" width="40" fixed="left" reserve-selection />
          <el-table-column prop="username" label="用户名" min-width="130" fixed="left" class-name="code" />
          <el-table-column prop="nickname" label="昵称" min-width="100" />
          <el-table-column v-if="cols.show.phone" prop="phone" label="手机号" width="120" class-name="num" />
          <el-table-column v-if="cols.show.depts" label="部门" min-width="140">
            <template #default="{ row }">
              <span :title="(row as User).depts.map((d) => d.path).join('\n')">
                {{ (row as User).depts.map((d) => d.name).join('、') || '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column v-if="cols.show.post" label="岗位" min-width="100">
            <template #default="{ row }">{{ row.post || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="cols.show.roles" label="角色" min-width="130">
            <template #default="{ row }">
              <span v-if="(row as User).roles.length">{{ (row as User).roles.map((r) => r.name).join('、') }}</span>
              <span v-else class="warn">没有角色</span>
            </template>
          </el-table-column>
          <el-table-column v-if="cols.show.designerNo" label="设计师编号" width="100" class-name="code">
            <template #default="{ row }">{{ row.designerNo ?? '—' }}</template>
          </el-table-column>
          <el-table-column v-if="cols.show.productLines" label="品类线" min-width="130">
            <template #default="{ row }">{{ (row as User).productLines.map((l) => lineDict.labelOf(l)).join('、') }}</template>
          </el-table-column>
          <el-table-column v-if="cols.show.createTime" prop="createTime" label="创建时间" width="170" class-name="num" />
          <el-table-column label="状态" width="164">
            <template #default="{ row }">
              <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
              <StatusTag v-if="row.pwdMustChange" tone="pending" class="must-change" title="还在用管理员给的密码，登录后要先改密码">
                待改密码
              </StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="268" align="right" fixed="right">
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

        <BatchBar
          ref="batchRef"
          :rows="selected"
          :table="tableRef"
          :actions="batchActions"
          unit="个人"
          :row-name="(u: User) => `${u.nickname}（${u.username}）`"
          @done="load"
        >
          <template #default="{ running }">
            <el-button v-perm="'system:user:edit'" size="small" :disabled="running" @click="openPick('addRole')">加角色</el-button>
            <el-button v-perm="'system:user:edit'" size="small" :disabled="running" @click="openPick('removeRole')">去角色</el-button>
            <el-button v-perm="'system:user:edit'" size="small" :disabled="running" @click="openPick('lines')">设品类线</el-button>
          </template>
        </BatchBar>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增用户' : '编辑用户'"
      width="min(560px, 94vw)"
      :before-close="guard.beforeClose"
    >
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editingId !== null" placeholder="登录用，小写字母、数字、下划线" />
        </el-form-item>
        <el-form-item v-if="editingId === null" label="初始密码" prop="password">
          <!-- new-password：不让浏览器把管理员自己的登录密码自动填进来 -->
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password placeholder="8-32 位，第一次登录要改" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="显示在侧边栏和日志里" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="部门" class="full">
          <el-tree-select
            v-model="form.deptIds"
            :data="depts"
            :props="{ label: 'name', children: 'children' }"
            node-key="id"
            multiple
            check-strictly
            filterable
            clearable
            default-expand-all
            :render-after-expand="false"
            placeholder="可以选几个；选上级不带下级"
            no-data-text="还没有部门，先在左边的部门树里建"
          />
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
        <el-form-item label="品类线" prop="productLines" class="full">
          <DictSelect v-model="form.productLines" :type="PRODUCT_LINE_DICT" multiple placeholder="能看哪几条线的款式、单据、供应商" />
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

    <el-dialog v-model="pickVisible" :title="`${PICK_TITLES[pickMode]}：选中的 ${selected.length} 个人`" width="min(420px, 94vw)">
      <el-select v-if="pickMode !== 'lines'" v-model="pickRoleId" filterable placeholder="选一个角色" class="pick">
        <el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <DictSelect v-else v-model="pickLines" :type="PRODUCT_LINE_DICT" multiple placeholder="选几条品类线" class="pick" />
      <template #footer>
        <el-button @click="pickVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!pickReady" @click="runPick">下一步</el-button>
      </template>
    </el-dialog>

    <ImportDialog
      v-model="importVisible"
      title="导入账号"
      :template-url="userApi.importTemplateUrl"
      :import-url="userApi.importUrl"
      :ready="importReady"
      :fields="{ password: importPassword }"
      @success="onImported"
    >
      <template #head>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="初始密码">
            <el-input
              v-model="importPassword"
              type="password"
              autocomplete="new-password"
              show-password
              maxlength="32"
              placeholder="8-32 位，导进来的人都用它"
            />
          </el-form-item>
        </el-form>
        <p class="dim hint">
          每个人第一次登录必须先改成自己的密码。别用好猜的，导完尽快通知大家登录。
          先在左边建好部门；领猫导出的用户列表可以直接导，登录名是中文的用手机号登录。
        </p>
      </template>
    </ImportDialog>
  </div>
</template>

<style scoped>
.with-nav {
  display: flex;
  align-items: stretch;
  min-height: calc(100vh - var(--lm-header-height) - 40px);
}

.main {
  flex: 1;
  min-width: 0;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.warn {
  color: var(--lm-pending-text);
}

.must-change {
  margin-left: 4px;
}

.pick {
  width: 100%;
}
</style>
