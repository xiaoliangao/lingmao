<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dictApi } from '@/api/system'
import type { DictData, DictType } from '@/types/system'

// ---------- 左边：字典类型 ----------
const typeQuery = reactive({ page: 1, size: 20, code: '', name: '' })
const types = ref<DictType[]>([])
const typeTotal = ref(0)
const currentType = ref<DictType | null>(null)

async function loadTypes() {
  const result = await dictApi.typePage({ ...typeQuery, code: typeQuery.code || undefined, name: typeQuery.name || undefined })
  types.value = result.list
  typeTotal.value = result.total
}

function selectType(row: DictType | null) {
  currentType.value = row
  dataQuery.page = 1
  loadData()
}

const typeDialog = ref(false)
const typeEditingId = ref<number | null>(null)
const typeForm = reactive({ code: '', name: '', remark: '', status: 1 })

function openTypeForm(row?: DictType) {
  typeEditingId.value = row?.id ?? null
  Object.assign(typeForm, row
    ? { code: row.code, name: row.name, remark: row.remark ?? '', status: row.status }
    : { code: '', name: '', remark: '', status: 1 })
  typeDialog.value = true
}

async function saveType() {
  const data = { ...typeForm, remark: typeForm.remark || null }
  if (typeEditingId.value === null) {
    await dictApi.createType(data)
  } else {
    await dictApi.updateType(typeEditingId.value, data)
  }
  ElMessage.success('已保存')
  typeDialog.value = false
  loadTypes()
}

async function removeType(row: DictType) {
  await ElMessageBox.confirm(`确定删除字典类型 ${row.name}？`, '提示', { type: 'warning' })
  await dictApi.removeType(row.id)
  ElMessage.success('已删除')
  if (currentType.value?.id === row.id) selectType(null)
  loadTypes()
}

// ---------- 右边：选中类型下的字典数据 ----------
const dataQuery = reactive({ page: 1, size: 20 })
const dataList = ref<DictData[]>([])
const dataTotal = ref(0)

async function loadData() {
  if (!currentType.value) {
    dataList.value = []
    dataTotal.value = 0
    return
  }
  const result = await dictApi.dataPage({ ...dataQuery, typeCode: currentType.value.code })
  dataList.value = result.list
  dataTotal.value = result.total
}

const dataDialog = ref(false)
const dataEditingId = ref<number | null>(null)
const dataForm = reactive({ label: '', value: '', sort: 0, status: 1, remark: '' })

function openDataForm(row?: DictData) {
  dataEditingId.value = row?.id ?? null
  Object.assign(dataForm, row
    ? { label: row.label, value: row.value, sort: row.sort, status: row.status, remark: row.remark ?? '' }
    : { label: '', value: '', sort: 0, status: 1, remark: '' })
  dataDialog.value = true
}

async function saveData() {
  const data = { ...dataForm, typeCode: currentType.value!.code, remark: dataForm.remark || null }
  if (dataEditingId.value === null) {
    await dictApi.createData(data)
  } else {
    await dictApi.updateData(dataEditingId.value, data)
  }
  ElMessage.success('已保存')
  dataDialog.value = false
  loadData()
}

async function removeData(row: DictData) {
  await ElMessageBox.confirm(`确定删除 ${row.label}？`, '提示', { type: 'warning' })
  await dictApi.removeData(row.id)
  ElMessage.success('已删除')
  loadData()
}

onMounted(loadTypes)
</script>

<template>
  <div class="page dict">
    <div class="page-card left">
      <el-form inline>
        <el-form-item>
          <el-input v-model="typeQuery.name" clearable placeholder="名称" style="width: 140px" @keyup.enter="loadTypes" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadTypes">查询</el-button>
          <el-button v-perm="'system:dict:add'" icon="Plus" @click="openTypeForm()">新增类型</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="types" border highlight-current-row @current-change="selectType">
        <el-table-column prop="code" label="编码" />
        <el-table-column prop="name" label="名称" />
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-perm="'system:dict:edit'" link type="primary" @click.stop="openTypeForm(row as DictType)">编辑</el-button>
            <el-button v-perm="'system:dict:remove'" link type="danger" @click.stop="removeType(row as DictType)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="typeQuery.page" class="pagination" layout="total, prev, pager, next" :total="typeTotal" @change="loadTypes" />
    </div>

    <div class="page-card right">
      <template v-if="currentType">
        <div class="toolbar">
          <strong>{{ currentType.name }}（{{ currentType.code }}）</strong>
          <el-button v-perm="'system:dict:add'" type="primary" icon="Plus" style="margin-left: 12px" @click="openDataForm()">新增数据</el-button>
        </div>
        <el-table :data="dataList" border>
          <el-table-column prop="label" label="显示文字" />
          <el-table-column prop="value" label="存储值" />
          <el-table-column prop="sort" label="排序" width="70" />
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-perm="'system:dict:edit'" link type="primary" @click="openDataForm(row as DictData)">编辑</el-button>
              <el-button v-perm="'system:dict:remove'" link type="danger" @click="removeData(row as DictData)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="dataQuery.page" class="pagination" layout="total, prev, pager, next" :total="dataTotal" @change="loadData" />
      </template>
      <el-empty v-else description="点左边的一个字典类型" />
    </div>

    <el-dialog v-model="typeDialog" :title="typeEditingId === null ? '新增字典类型' : '编辑字典类型'" width="440px">
      <el-form :model="typeForm" label-width="60px">
        <el-form-item label="编码" required>
          <el-input v-model="typeForm.code" placeholder="如 season" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="typeForm.name" placeholder="如 季节" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" @click="saveType">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dataDialog" :title="dataEditingId === null ? '新增字典数据' : '编辑字典数据'" width="440px">
      <el-form :model="dataForm" label-width="80px">
        <el-form-item label="显示文字" required>
          <el-input v-model="dataForm.label" placeholder="如 春季" />
        </el-form-item>
        <el-form-item label="存储值" required>
          <el-input v-model="dataForm.value" placeholder="如 1" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dataForm.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="dataForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dataForm.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dataDialog = false">取消</el-button>
        <el-button type="primary" @click="saveData">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dict {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.left {
  width: 46%;
}

.right {
  flex: 1;
}
</style>
