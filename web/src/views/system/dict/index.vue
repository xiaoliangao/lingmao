<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dictApi } from '@/api/system'
import type { DictData, DictType } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'

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
  await ElMessageBox.confirm(`删除字典类型"${row.name}"。下面还有选项的删不掉，要先删选项。`, '删除字典类型', { confirmButtonText: '删除', type: 'warning' })
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
  await ElMessageBox.confirm(`删除选项"${row.label}"以后，下拉框里不再出现它。`, '删除选项', { confirmButtonText: '删除', type: 'warning' })
  await dictApi.removeData(row.id)
  ElMessage.success('已删除')
  loadData()
}

onMounted(loadTypes)
</script>

<template>
  <div class="page">
    <PageHeader description="下拉框里的选项，比如季节、计量单位。左边选一个类型，右边改它的选项。" />

    <div class="dict">
      <section class="panel side">
        <div class="side-head">
          <el-input v-model="typeQuery.name" clearable placeholder="按名称找" prefix-icon="Search" @keyup.enter="loadTypes" @clear="loadTypes" />
          <el-button v-perm="'system:dict:add'" icon="Plus" @click="openTypeForm()">新增类型</el-button>
        </div>
        <el-table :data="types" highlight-current-row empty-text="还没有字典类型" @current-change="selectType">
          <el-table-column prop="name" label="名称" min-width="90" />
          <el-table-column prop="code" label="编码" min-width="100" class-name="code" />
          <el-table-column label="状态" width="84">
            <template #default="{ row }">
              <StatusTag :tone="row.status === 1 ? 'done' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-perm="'system:dict:edit'" link type="primary" @click.stop="openTypeForm(row as DictType)">编辑</el-button>
              <el-button v-perm="'system:dict:remove'" link type="danger" @click.stop="removeType(row as DictType)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="typeQuery.page" class="pagination side-pager" layout="total, prev, pager, next" :total="typeTotal" @change="loadTypes" />
      </section>

      <section class="panel main">
        <template v-if="currentType">
          <div class="main-head">
            <h2>{{ currentType.name }} <span class="code muted">{{ currentType.code }}</span></h2>
            <el-button v-perm="'system:dict:add'" type="primary" icon="Plus" @click="openDataForm()">新增选项</el-button>
          </div>
          <el-table :data="dataList" empty-text="这个类型还没有选项">
            <el-table-column prop="label" label="显示文字" min-width="120" />
            <el-table-column prop="value" label="存储值" min-width="100" class-name="code" />
            <el-table-column prop="sort" label="排序" width="70" align="right" class-name="num" />
            <el-table-column label="状态" width="84">
              <template #default="{ row }">
                <StatusTag :tone="row.status === 1 ? 'done' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110">
              <template #default="{ row }">
                <el-button v-perm="'system:dict:edit'" link type="primary" @click="openDataForm(row as DictData)">编辑</el-button>
                <el-button v-perm="'system:dict:remove'" link type="danger" @click="removeData(row as DictData)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination v-model:current-page="dataQuery.page" class="pagination main-pager" layout="total, prev, pager, next" :total="dataTotal" @change="loadData" />
        </template>
        <p v-else class="pick muted">{{ types.length ? '在左边点一个字典类型，这里显示它的选项。' : '先在左边新增一个字典类型。' }}</p>
      </section>
    </div>

    <el-dialog v-model="typeDialog" :title="typeEditingId === null ? '新增字典类型' : '编辑字典类型'" width="440px">
      <el-form :model="typeForm" label-width="60px">
        <el-form-item label="名称" required>
          <el-input v-model="typeForm.name" placeholder="如 季节" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="typeForm.code" placeholder="如 season，程序里按它取选项" />
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

    <el-dialog v-model="dataDialog" :title="dataEditingId === null ? '新增选项' : '编辑选项'" width="440px">
      <el-form :model="dataForm" label-width="80px">
        <el-form-item label="显示文字" required>
          <el-input v-model="dataForm.label" placeholder="如 春季" />
        </el-form-item>
        <el-form-item label="存储值" required>
          <el-input v-model="dataForm.value" placeholder="如 1，存进单据里的是它" />
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
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: 16px;
  align-items: start;
}

.side-head,
.main-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--lm-line-soft);
}

.side-head .el-input {
  max-width: 200px;
}

h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

h2 .code {
  margin-left: 6px;
  font-size: 13px;
}

.side-pager,
.main-pager {
  padding: 0 20px 16px;
}

.pick {
  margin: 0;
  padding: 48px 20px;
  text-align: center;
}

@media (width < 1000px) {
  .dict {
    grid-template-columns: 1fr;
  }
}
</style>
