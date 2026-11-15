<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { colorApi } from '@/api/basic'
import type { Color } from '@/types/basic'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import DictSelect from '@/components/DictSelect.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { STATUS_OPTIONS, confirmRemove, orNull, statusParam, toggleStatus, type StatusFilter } from '../common'

/** 基础 › 颜色（RFC 003 §5.2）：编码 2-3 位，进 SKU 码，建好不改；名称、色系、色块能改 */
const families = useDict('color_family')
const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ status, ...rest }) => colorApi.page({ ...rest, status: statusParam(status) }),
  query: { keyword: '', status: '' as StatusFilter, family: '' },
})

const filtered = () => !!(query.keyword || query.status !== '' || query.family)

// ---------- 新增、修改 ----------
const visible = ref(false)
const editing = ref<Color | null>(null)
const formRef = ref<FormInstance>()
const EMPTY = { code: '', name: '', family: null as string | null, hex: '#888888' as string | null }
const form = reactive({ ...EMPTY })
const guard = useUnsavedChanges(form, visible)
const rules: FormRules = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^\s*[A-Za-z0-9]{2,3}\s*$/, message: '2-3 位字母或数字', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, EMPTY)
  visible.value = true
}

function openEdit(row: Color) {
  editing.value = row
  Object.assign(form, { code: row.code, name: row.name, family: row.family, hex: row.hex })
  visible.value = true
}

async function save() {
  await formRef.value?.validate()
  const data = { code: form.code.trim(), name: form.name.trim(), family: form.family || null, hex: orNull(form.hex)?.toUpperCase() ?? null }
  if (editing.value) {
    await colorApi.update(editing.value.id, data)
  } else {
    await colorApi.create(data)
  }
  ElMessage.success(editing.value ? '已保存' : `加了颜色 ${data.name}`)
  visible.value = false
  load()
}

async function toggle(row: Color) {
  if (await toggleStatus(row, `颜色 ${row.name}`, colorApi, row.id, '停用后新建物料、款式选不到这个颜色，已经用了它的不受影响。')) load()
}

async function remove(row: Color) {
  await confirmRemove(`删除颜色 ${row.name}`, `删除以后编码 ${row.code} 可以再用。已经有物料、款式用了它的删不掉，请改成停用。`)
  await colorApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <FilterBar v-model:keyword="query.keyword" placeholder="编码、名称" @search="search">
    <template #left>
      <el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" />
    </template>
    <FilterItem label="色系" :text="query.family ? families.labelOf(query.family) : ''" @clear="query.family = ''">
      <DictSelect v-model="query.family" type="color_family" clearable placeholder="全部色系" />
    </FilterItem>
    <template #right>
      <el-button v-perm="'basic:basics:add'" type="primary" icon="Plus" @click="openCreate">新增颜色</el-button>
    </template>
  </FilterBar>

  <el-table v-loading="loading" :data="list" :empty-text="filtered() ? '没有符合条件的颜色' : '还没有颜色，新增一个，或者导入物料时自动建'">
    <el-table-column label="" width="72">
      <template #default="{ row }">
        <span class="swatch" :style="{ background: row.hex ?? undefined }" :class="{ blank: !row.hex }" />
      </template>
    </el-table-column>
    <el-table-column prop="code" label="编码" width="100" class-name="code" />
    <el-table-column prop="name" label="名称" min-width="140" />
    <el-table-column label="色系" min-width="120">
      <template #default="{ row }">{{ row.family ? families.labelOf(row.family) : '—' }}</template>
    </el-table-column>
    <el-table-column label="色块" width="110" class-name="code">
      <template #default="{ row }">{{ row.hex ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180" align="right">
      <template #default="{ row }">
        <el-button v-perm="'basic:basics:edit'" link @click="openEdit(row as Color)">编辑</el-button>
        <el-button v-perm="'basic:basics:edit'" link @click="toggle(row as Color)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button v-perm="'basic:basics:remove'" link type="danger" @click="remove(row as Color)">删除</el-button>
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

  <el-dialog v-model="visible" :title="editing ? `编辑颜色 ${editing.code}` : '新增颜色'" width="min(480px, 94vw)" :before-close="guard.beforeClose">
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item label="编码" prop="code">
        <el-input v-model="form.code" class="code" maxlength="3" :disabled="!!editing" placeholder="2-3 位，进 SKU 码，建好不改" />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="32" placeholder="如 卡其" />
      </el-form-item>
      <el-form-item label="色系">
        <DictSelect v-model="form.family" type="color_family" clearable placeholder="按色系筛颜色用" />
      </el-form-item>
      <el-form-item label="色块">
        <div class="hex">
          <el-color-picker v-model="form.hex" />
          <span class="code dim">{{ form.hex ?? '不设' }}</span>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="save">{{ editing ? '保存' : '新增颜色' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.swatch {
  display: inline-block;
  width: 22px;
  height: 22px;
  vertical-align: middle;
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-sm);
}

.swatch.blank {
  background: repeating-linear-gradient(45deg, var(--lm-paper) 0 4px, var(--lm-line-soft) 4px 8px);
}

.hex {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
