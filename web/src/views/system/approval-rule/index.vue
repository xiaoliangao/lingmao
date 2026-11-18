<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { approvalRuleApi } from '@/api/biz'
import { roleApi } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { ApprovalRule, ApprovalRuleForm } from '@/types/biz'
import type { Role } from '@/types/system'
import { formatMoney } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'

/**
 * 设置 › 审批（管理后台 /system/approval-rule，docs/api/README §4.6、架构 §4.8）：只给超级管理员。
 * 第 1 级是有这种单据审核权限的人；单据总金额超过一条规则的门槛，就多加一级，审批人是规则里的角色或指定的人（二选一）。
 * 同一种单据有几条就加几级，门槛低的先审，表里按这个顺序排、写出是第几级。改规则只影响以后提交的单。
 */
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('biz:approval-rule:edit'))

const BIZ_LABELS: Record<ApprovalRule['bizType'], string> = {
  ORDER: '需求订单',
  PURCHASE_ORDER: '采购单',
  PRODUCTION_ORDER: '外发加工单、成品采购单',
}
const BIZ_ORDER: ApprovalRule['bizType'][] = ['ORDER', 'PURCHASE_ORDER', 'PRODUCTION_ORDER']
const MODE_OPTIONS = [
  { label: '按角色', value: 'ROLE' },
  { label: '指定的人', value: 'USER' },
]

const rows = ref<ApprovalRule[]>([])
const roles = ref<Role[]>([])
const loading = ref(false)

/** 按单据、门槛从低到高排，算出每条加的是第几级（第 1 级是有审核权限的人） */
const sorted = computed(() => {
  const list = [...rows.value].sort(
    (a, b) => BIZ_ORDER.indexOf(a.bizType) - BIZ_ORDER.indexOf(b.bizType) || a.overAmount - b.overAmount,
  )
  const seen: Partial<Record<ApprovalRule['bizType'], number>> = {}
  return list.map((rule) => {
    const level = (seen[rule.bizType] ?? 1) + 1
    seen[rule.bizType] = level
    return { rule, level }
  })
})
type SortedRow = (typeof sorted.value)[number]
const rowKey = (row: SortedRow) => String(row.rule.id)

const loadRequest = useLatestRequest()
async function load() {
  const read = loadRequest.begin()
  loading.value = true
  try {
    const fetched1 = await read.run(() => approvalRuleApi.list())
    if (!read.current()) return
    rows.value = fetched1
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

const loadRolesRequest = useLatestRequest()
async function loadRoles() {
  const read = loadRolesRequest.begin()
  try {
    const fetched1 = await read.run(() => roleApi.options())
    if (!read.current()) return
    roles.value = fetched1
  } catch {
    if (!read.current()) return
    roles.value = []
  }
}

onMounted(() => {
  void load()
  void loadRoles()
})

// ---------- 新增、编辑 ----------
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const editingUserName = ref<string | null>(null)
const form = reactive<ApprovalRuleForm & { mode: 'ROLE' | 'USER' }>({
  bizType: 'PURCHASE_ORDER',
  overAmount: null,
  roleId: null,
  userId: null,
  mode: 'ROLE',
})
const guard = useUnsavedChanges(form, dialogVisible)

function openCreate() {
  editingId.value = null
  editingUserName.value = null
  Object.assign(form, { bizType: 'PURCHASE_ORDER', overAmount: null, roleId: null, userId: null, mode: 'ROLE' })
  dialogVisible.value = true
}

function openEdit(rule: ApprovalRule) {
  editingId.value = rule.id
  editingUserName.value = rule.userName
  Object.assign(form, {
    bizType: rule.bizType,
    overAmount: rule.overAmount,
    roleId: rule.roleId,
    userId: rule.userId,
    mode: rule.userId ? 'USER' : 'ROLE',
  })
  dialogVisible.value = true
}

async function save() {
  if (form.overAmount === null || form.overAmount < 0) {
    ElMessage.warning('填一下金额超过多少')
    return
  }
  const payload: ApprovalRuleForm = {
    bizType: form.bizType,
    overAmount: form.overAmount,
    roleId: form.mode === 'ROLE' ? form.roleId : null,
    userId: form.mode === 'USER' ? form.userId : null,
  }
  if (!payload.roleId && !payload.userId) {
    ElMessage.warning(form.mode === 'ROLE' ? '选一个审批角色' : '选一个审批人')
    return
  }
  saving.value = true
  try {
    if (editingId.value) await approvalRuleApi.update(editingId.value, payload)
    else await approvalRuleApi.create(payload)
    ElMessage.success('已保存，以后提交的单按新规则审')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(rule: ApprovalRule) {
  await confirmRemove(
    `删除这条${BIZ_LABELS[rule.bizType]}的规则`,
    `以后提交的${BIZ_LABELS[rule.bizType]}金额超过 ${formatMoney(rule.overAmount)} 不再多加这一级；已经在审的单不受影响。`,
  )
  await approvalRuleApi.remove(rule.id)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div>
    <PageHeader title="审批规则">
      <template #actions>
        <el-button v-if="canEdit" type="primary" icon="Plus" @click="openCreate">新增规则</el-button>
      </template>
    </PageHeader>

    <el-table v-loading="loading" :data="sorted" :row-key="rowKey" empty-text="还没有规则：所有单据都只审一级（有审核权限的人审）">
      <el-table-column label="单据" min-width="180">
        <template #default="{ row }">{{ BIZ_LABELS[(row as SortedRow).rule.bizType] }}</template>
      </el-table-column>
      <el-table-column label="金额超过（元）" width="160" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.rule.overAmount) }}</template>
      </el-table-column>
      <el-table-column label="加到" width="90">
        <template #default="{ row }">第 {{ row.level }} 级</template>
      </el-table-column>
      <el-table-column label="审批人" min-width="200">
        <template #default="{ row }">
          <span v-if="row.rule.roleName">角色：{{ row.rule.roleName }}</span>
          <span v-else-if="row.rule.userName">{{ row.rule.userName }}</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="canEdit" label="" width="120" align="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row.rule)">编辑</el-button>
          <el-button link type="danger" @click="remove(row.rule)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑审批规则' : '新增审批规则'"
      width="520px"
      :before-close="guard.beforeClose"
    >
      <el-form label-position="top" class="form-grid" @submit.prevent>
        <el-form-item label="单据" required>
          <el-select v-model="form.bizType" filterable>
            <el-option v-for="k in BIZ_ORDER" :key="k" :label="BIZ_LABELS[k]" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额超过（元）" required>
          <el-input-number v-model="form.overAmount" :min="0" :precision="2" :controls="false" placeholder="如 50000" />
        </el-form-item>
        <el-form-item label="审批人">
          <el-segmented v-model="form.mode" :options="MODE_OPTIONS" />
        </el-form-item>
        <el-form-item v-if="form.mode === 'ROLE'" label="审批角色" required>
          <el-select v-model="form.roleId" filterable placeholder="选择角色">
            <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="审批人" required>
          <UserSelect v-model="form.userId" :label="editingUserName" placeholder="选择用户" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-grid :deep(.el-select),
.form-grid :deep(.el-input-number) {
  width: 100%;
}
</style>
