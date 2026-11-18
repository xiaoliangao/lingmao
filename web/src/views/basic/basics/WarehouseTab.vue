<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { WAREHOUSE_TYPE_LABEL, type Warehouse, type WarehouseType } from '@/types/basic'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useTable } from '@/composables/useTable'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { STATUS_OPTIONS, confirmRemove, orNull, statusParam, toggleStatus, type StatusFilter } from '../common'

/**
 * 基础 › 仓库（RFC 003 §6）。页面上只能建物料仓、成品仓；工厂仓由供应商同步（建加工厂时建、改简称时改名、删供应商时删），
 * 这里只能改地址、负责人、电话、备注，能停用，不能删
 */
const TYPE_OPTIONS = [
  { label: '全部', value: '' },
  { label: '物料仓', value: 'MATERIAL' },
  { label: '成品仓', value: 'PRODUCT' },
  { label: '工厂仓', value: 'FACTORY' },
]

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ status, type, ...rest }) => warehouseApi.page({ ...rest, type: type || undefined, status: statusParam(status) }),
  query: { keyword: '', type: '' as '' | WarehouseType, status: '' as StatusFilter },
})

const filtered = () => !!(query.keyword || query.type || query.status !== '')
const statusText = () => STATUS_OPTIONS.find((o) => o.value === query.status && o.value !== '')?.label ?? ''

// ---------- 新增、修改 ----------
const visible = ref(false)
const editing = ref<Warehouse | null>(null)
const formRef = ref<FormInstance>()
const EMPTY = {
  code: '', name: '', type: 'MATERIAL' as WarehouseType, address: '', keeperId: null as string | null, phone: '', remark: '',
}
const form = reactive({ ...EMPTY })
const guard = useUnsavedChanges(form, visible)
const rules: FormRules = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^\s*[A-Za-z0-9_-]{1,32}\s*$/, message: '1-32 位字母、数字、横线或下划线', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, EMPTY)
  visible.value = true
}

function openEdit(row: Warehouse) {
  editing.value = row
  Object.assign(form, {
    code: row.code, name: row.name, type: row.type, address: row.address ?? '', keeperId: row.keeperId,
    phone: row.phone ?? '', remark: row.remark ?? '',
  })
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  const data = {
    code: form.code.trim(), name: form.name.trim(), type: form.type,
    address: orNull(form.address), keeperId: form.keeperId, phone: orNull(form.phone), remark: orNull(form.remark),
  }
  if (editing.value) {
    await warehouseApi.update(editing.value.id, data)
  } else {
    await warehouseApi.create(data)
  }
  ElMessage.success(editing.value ? '已保存' : `加了仓库 ${data.name}`)
  visible.value = false
  load()
}

async function toggle(row: Warehouse) {
  const consequence = '停用后新建入库、出库单选不到这个仓库，仓里现有的库存和已有单据不受影响。'
  if (await toggleStatus(row, `仓库 ${row.name}`, warehouseApi, row.id, consequence)) load()
}

async function remove(row: Warehouse) {
  await confirmRemove(`删除仓库 ${row.name}`, '删除以后找不回来。已经有库存、单据用了它的删不掉，请改成停用。')
  await warehouseApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <FilterBar v-model:keyword="query.keyword" placeholder="编码、名称" @search="search">
    <template #left>
      <el-segmented v-model="query.type" :options="TYPE_OPTIONS" @change="search" />
    </template>
    <FilterItem label="状态" :text="statusText()" @clear="query.status = ''">
      <el-segmented v-model="query.status" :options="STATUS_OPTIONS" />
    </FilterItem>
    <template #right>
      <el-button v-perm="'basic:basics:add'" type="primary" icon="Plus" @click="openCreate">新增仓库</el-button>
    </template>
  </FilterBar>

  <el-table v-loading="loading" :data="list" :empty-text="filtered() ? '没有符合条件的仓库' : '还没有仓库。物料仓、成品仓在这里建，工厂仓建加工厂时自动建'">
    <el-table-column prop="code" label="编码" width="110" class-name="code" />
    <el-table-column prop="name" label="名称" min-width="140" />
    <el-table-column label="类型" width="90">
      <template #default="{ row }">{{ WAREHOUSE_TYPE_LABEL[row.type as WarehouseType] }}</template>
    </el-table-column>
    <el-table-column label="绑定加工厂" min-width="130">
      <template #default="{ row }">{{ row.factoryName ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="地址" min-width="180" show-overflow-tooltip>
      <template #default="{ row }">{{ row.address ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="负责人" width="100">
      <template #default="{ row }">{{ row.keeperName ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="电话" width="130" class-name="num">
      <template #default="{ row }">{{ row.phone ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180" align="right">
      <template #default="{ row }">
        <el-button v-perm="'basic:basics:edit'" link @click="openEdit(row as Warehouse)">编辑</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="toggle(row as Warehouse)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button
          v-perm="'basic:basics:remove'"
          link
          type="danger"
          :disabled="row.type === 'FACTORY'"
          :title="row.type === 'FACTORY' ? '工厂仓跟着加工厂，删加工厂时一起删' : ''"
          @click="remove(row as Warehouse)"
        >删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-pagination
    v-model:current-page="page"
    v-model:page-size="size"
    class="pagination"
    layout="total, sizes, prev, pager, next"
    :page-sizes="pageSizes"
    :total="total"
  />

  <el-dialog v-model="visible" :title="editing ? `编辑仓库 ${editing.code}` : '新增仓库'" width="min(560px, 94vw)" :before-close="guard.beforeClose">
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item label="编码" prop="code">
        <el-input v-model="form.code" class="code" maxlength="32" :disabled="!!editing" placeholder="如 WL02，建好不改" />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="100" :disabled="editing?.type === 'FACTORY'" />
      </el-form-item>
      <el-form-item label="类型" class="full">
        <span v-if="editing?.type === 'FACTORY'" class="dim">工厂仓，绑定 {{ editing.factoryName ?? '加工厂' }}。名称跟着加工厂的简称改</span>
        <el-radio-group v-else v-model="form.type" :disabled="!!editing">
          <el-radio value="MATERIAL">物料仓</el-radio>
          <el-radio value="PRODUCT">成品仓</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="地址" class="full">
        <el-input v-model="form.address" maxlength="255" />
      </el-form-item>
      <el-form-item label="负责人">
        <UserSelect v-model="form.keeperId" :label="editing?.keeperName" clearable />
      </el-form-item>
      <el-form-item label="电话">
        <el-input v-model="form.phone" maxlength="32" />
      </el-form-item>
      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save">{{ editing ? '保存' : '新增仓库' }}</el-button>
    </template>
  </el-dialog>
</template>
