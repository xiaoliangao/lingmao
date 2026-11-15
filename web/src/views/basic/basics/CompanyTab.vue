<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { companyApi } from '@/api/basic'
import type { Company } from '@/types/basic'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { confirmRemove, orNull, toggleStatus } from '../common'

/** 基础 › 主体公司（RFC 003 §3.1）：签合同、开票的我方公司。至少留一个启用的；编码建好不改 */
const list = ref<Company[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    list.value = await companyApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------- 新增、修改 ----------
const visible = ref(false)
const editing = ref<Company | null>(null)
const formRef = ref<FormInstance>()
const EMPTY = { code: '', name: '', shortName: '', creditCode: '', address: '', phone: '' }
const form = reactive({ ...EMPTY })
const guard = useUnsavedChanges(form, visible)
const rules: FormRules = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^\s*[A-Za-z0-9]{2,6}\s*$/, message: '2-6 位字母或数字', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入公司全称', trigger: 'blur' }],
  shortName: [{ required: true, message: '请输入简称', trigger: 'blur' }],
  creditCode: [{ pattern: /^\s*([0-9A-Za-z]{18})?\s*$/, message: '18 位字母或数字', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, EMPTY)
  visible.value = true
}

function openEdit(row: Company) {
  editing.value = row
  Object.assign(form, {
    code: row.code, name: row.name, shortName: row.shortName,
    creditCode: row.creditCode ?? '', address: row.address ?? '', phone: row.phone ?? '',
  })
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  const data = {
    code: form.code.trim(), name: form.name.trim(), shortName: form.shortName.trim(),
    creditCode: orNull(form.creditCode), address: orNull(form.address), phone: orNull(form.phone),
  }
  if (editing.value) {
    await companyApi.update(editing.value.id, data)
  } else {
    await companyApi.create(data)
  }
  ElMessage.success(editing.value ? '已保存' : `加了主体 ${data.shortName}`)
  visible.value = false
  load()
}

async function toggle(row: Company) {
  const consequence = '停用后新建单据选不到这个主体，已有的单据不受影响。至少要留一个启用的主体。'
  if (await toggleStatus(row, `主体 ${row.shortName}`, companyApi, row.id, consequence)) load()
}

async function remove(row: Company) {
  await confirmRemove(`删除主体 ${row.shortName}`, '删除以后找不回来。已经有单据、用户用了它的删不掉，请改成停用。')
  await companyApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <FilterBar>
    <template #right>
      <el-button v-perm="'basic:basics:add'" type="primary" icon="Plus" @click="openCreate">新增主体</el-button>
    </template>
  </FilterBar>

  <el-table v-loading="loading" :data="list" empty-text="还没有主体公司。签合同、开票的我方公司，至少要有一个">
    <el-table-column prop="code" label="编码" width="100" class-name="code" />
    <el-table-column prop="shortName" label="简称" min-width="120" />
    <el-table-column prop="name" label="名称" min-width="200" />
    <el-table-column label="统一社会信用代码" min-width="190" class-name="code">
      <template #default="{ row }">{{ row.creditCode ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="地址" min-width="200" show-overflow-tooltip>
      <template #default="{ row }">{{ row.address ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="电话" width="140" class-name="num">
      <template #default="{ row }">{{ row.phone ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180" align="right">
      <template #default="{ row }">
        <el-button v-perm="'basic:basics:edit'" link @click="openEdit(row as Company)">编辑</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="toggle(row as Company)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button v-perm="'basic:basics:remove'" link type="danger" @click="remove(row as Company)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="visible" :title="editing ? `编辑主体 ${editing.code}` : '新增主体公司'" width="min(560px, 94vw)" :before-close="guard.beforeClose">
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item label="编码" prop="code">
        <el-input v-model="form.code" class="code" maxlength="6" :disabled="!!editing" placeholder="2-6 位，建好不改" />
      </el-form-item>
      <el-form-item label="简称" prop="shortName">
        <el-input v-model="form.shortName" maxlength="50" placeholder="列表和单据上显示" />
      </el-form-item>
      <el-form-item label="名称" prop="name" class="full">
        <el-input v-model="form.name" maxlength="100" placeholder="公司全称" />
      </el-form-item>
      <el-form-item label="统一社会信用代码" prop="creditCode" class="full">
        <el-input v-model="form.creditCode" class="code" maxlength="18" />
      </el-form-item>
      <el-form-item label="地址" class="full">
        <el-input v-model="form.address" maxlength="255" placeholder="打印采购单、生产单时用作抬头" />
      </el-form-item>
      <el-form-item label="电话">
        <el-input v-model="form.phone" maxlength="32" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save">{{ editing ? '保存' : '新增主体' }}</el-button>
    </template>
  </el-dialog>
</template>
