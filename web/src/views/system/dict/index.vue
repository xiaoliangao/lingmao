<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { dictApi } from '@/api/system'
import type { DictData, DictType } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import BlankState from '@/components/BlankState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useLatestRequest } from '@/composables/useLatestRequest'

// ---------- 左边：字典类型 ----------
const typeQuery = reactive({ page: 1, size: 20, code: '', name: '' })
const types = ref<DictType[]>([])
const typeTotal = ref(0)
const currentType = ref<DictType | null>(null)

const typeRequest = useLatestRequest()
async function loadTypes() {
  const read = typeRequest.begin()
  try {
    const params = { ...typeQuery, code: typeQuery.code || undefined, name: typeQuery.name || undefined }
    const result = await read.run(() => dictApi.typePage(params))
    if (!read.current()) return
    types.value = result.list
    typeTotal.value = result.total
  } catch (error) { if (read.current()) throw error }
}

function selectType(row: DictType | null) {
  currentType.value = row
  dataQuery.page = 1
  loadData()
}

const typeDialog = ref(false)
const typeEditingId = ref<string | null>(null)
const typeForm = reactive({ code: '', name: '', remark: '', status: 1, numLabel: '' })
const typeGuard = useUnsavedChanges(typeForm, typeDialog)

function openTypeForm(row?: DictType) {
  typeEditingId.value = row?.id ?? null
  Object.assign(typeForm, row
    ? { code: row.code, name: row.name, remark: row.remark ?? '', status: row.status, numLabel: row.numLabel ?? '' }
    : { code: '', name: '', remark: '', status: 1, numLabel: '' })
  typeDialog.value = true
}

async function saveType() {
  const data = { ...typeForm, remark: typeForm.remark || null, numLabel: typeForm.numLabel.trim() || null }
  if (typeEditingId.value === null) {
    await dictApi.createType(data)
  } else {
    await dictApi.updateType(typeEditingId.value, data)
  }
  ElMessage.success('已保存')
  typeDialog.value = false
  await loadTypes()
  // 右边列着的就是这个类型：数值列的名字可能改了，换成新的
  const fresh = types.value.find((t) => t.id === currentType.value?.id)
  if (fresh) currentType.value = fresh
}

async function removeType(row: DictType) {
  await ElMessageBox.confirm(`删除以后程序里按"${row.code}"就取不到选项了。下面还有选项的删不掉，要先删选项。`, '删除字典类型', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await dictApi.removeType(row.id)
  ElMessage.success('已删除')
  if (currentType.value?.id === row.id) selectType(null)
  loadTypes()
}

// ---------- 右边：选中类型下的字典数据 ----------
const dataQuery = reactive({ page: 1, size: 20 })
const dataList = ref<DictData[]>([])
const dataTotal = ref(0)

const dataRequest = useLatestRequest()
async function loadData() {
  const read = dataRequest.begin()
  const typeCode = currentType.value?.code
  if (!typeCode) {
    dataList.value = []
    dataTotal.value = 0
    return
  }
  try {
    const params = { ...dataQuery, typeCode }
    const result = await read.run(() => dictApi.dataPage(params))
    if (!read.current()) return
    dataList.value = result.list
    dataTotal.value = result.total
  } catch (error) { if (read.current()) throw error }
}

const dataDialog = ref(false)
const dataEditingId = ref<string | null>(null)
const dataForm = reactive({ label: '', value: '', sort: 0, status: 1, remark: '', numValue: undefined as number | undefined })
/** 正在编辑的是内置项：不能停用 */
const dataBuiltin = ref(false)
const dataGuard = useUnsavedChanges(dataForm, dataDialog)

function openDataForm(row?: DictData) {
  dataEditingId.value = row?.id ?? null
  dataBuiltin.value = row?.builtin ?? false
  Object.assign(dataForm, row
    ? { label: row.label, value: row.value, sort: row.sort, status: row.status, remark: row.remark ?? '', numValue: row.numValue ?? undefined }
    : { label: '', value: '', sort: 0, status: 1, remark: '', numValue: undefined })
  dataDialog.value = true
}

async function saveData() {
  const data = { ...dataForm, typeCode: currentType.value!.code, remark: dataForm.remark || null, numValue: dataForm.numValue ?? null }
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
  await ElMessageBox.confirm(`删除以后下拉框里不再出现"${row.label}"；已经选了它的单据不受影响。`, '删除选项', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await dictApi.removeData(row.id)
  ElMessage.success('已删除')
  loadData()
}

onMounted(loadTypes)
</script>

<template>
  <div>
    <PageHeader />

    <div class="page dict">
      <section class="panel">
        <div class="panel-head">
          <el-input
            v-model="typeQuery.name"
            class="find"
            clearable
            placeholder="按名称找"
            prefix-icon="Search"
            aria-label="按名称找字典类型"
            @keyup.enter="loadTypes"
            @clear="loadTypes"
          />
          <span class="acts">
            <el-button v-perm="'system:dict:add'" icon="Plus" @click="openTypeForm()">新增类型</el-button>
          </span>
        </div>
        <el-table :data="types" highlight-current-row :empty-text="typeQuery.name ? '没有叫这个名字的类型' : '暂无字典类型'" @current-change="selectType">
          <el-table-column prop="name" label="名称" min-width="90" />
          <el-table-column prop="code" label="编码" min-width="100" class-name="code" />
          <el-table-column label="状态" width="84">
            <template #default="{ row }">
              <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="right">
            <template #default="{ row }">
              <el-button v-perm="'system:dict:edit'" link @click.stop="openTypeForm(row as DictType)">编辑</el-button>
              <el-button v-perm="'system:dict:remove'" link type="danger" @click.stop="removeType(row as DictType)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="typeQuery.page" class="pagination" layout="total, prev, pager, next" :total="typeTotal" @change="loadTypes" />
      </section>

      <section class="panel">
        <template v-if="currentType">
          <div class="panel-head">
            <span>{{ currentType.name }}</span>
            <span class="code dim">{{ currentType.code }}</span>
            <span class="acts">
              <el-button v-perm="'system:dict:add'" type="primary" icon="Plus" @click="openDataForm()">新增选项</el-button>
            </span>
          </div>
          <el-table :data="dataList" empty-text="暂无选项">
            <el-table-column label="显示文字" min-width="140">
              <template #default="{ row }">
                {{ row.label }}
                <el-tag v-if="row.builtin" size="small" type="info" disable-transitions title="程序按存储值认它：不能删、不能停用">内置</el-tag>
                <el-tag v-if="row.trade" size="small" type="warning" disable-transitions title="贸易代工用的：代工没打开时下拉里没有它">代工</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="value" label="存储值" min-width="100" class-name="code" />
            <el-table-column v-if="currentType.numLabel" :label="currentType.numLabel" width="110" align="right" class-name="num">
              <template #default="{ row }">{{ row.numValue ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="70" align="right" class-name="num" />
            <el-table-column label="状态" width="84">
              <template #default="{ row }">
                <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="right">
              <template #default="{ row }">
                <el-button v-perm="'system:dict:edit'" link @click="openDataForm(row as DictData)">编辑</el-button>
                <el-button
                  v-perm="'system:dict:remove'"
                  link
                  type="danger"
                  :disabled="row.builtin"
                  :title="row.builtin ? '内置项不能删：程序按它的存储值认它' : undefined"
                  @click="removeData(row as DictData)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination v-model:current-page="dataQuery.page" class="pagination" layout="total, prev, pager, next" :total="dataTotal" @change="loadData" />
        </template>
        <BlankState v-else :title="types.length ? '在左边点一个字典类型，这里列出它的选项' : '先在左边新增一个字典类型'" />
      </section>
    </div>

    <el-dialog
      v-model="typeDialog"
      :title="typeEditingId === null ? '新增字典类型' : '编辑字典类型'"
      width="min(480px, 94vw)"
      :before-close="typeGuard.beforeClose"
    >
      <el-form class="form-grid" :model="typeForm" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="typeForm.name" placeholder="如 季节" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="typeForm.code" placeholder="如 season" />
        </el-form-item>
        <el-form-item label="数值名称">
          <el-input v-model="typeForm.numLabel" maxlength="32" placeholder="如 默认天数；不用数值不填" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注" class="full">
          <el-input v-model="typeForm.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" @click="saveType">{{ typeEditingId === null ? '新增类型' : '保存' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="dataDialog"
      :title="dataEditingId === null ? '新增选项' : '编辑选项'"
      width="min(480px, 94vw)"
      :before-close="dataGuard.beforeClose"
    >
      <el-form class="form-grid" :model="dataForm" label-position="top">
        <el-form-item label="显示文字" required>
          <el-input v-model="dataForm.label" placeholder="如 春季" />
        </el-form-item>
        <el-form-item label="存储值" required>
          <el-input v-model="dataForm.value" :disabled="dataEditingId !== null" placeholder="如 SPRING，单据里存的是它，建好不能改" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dataForm.sort" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="dataForm.status" :disabled="dataBuiltin">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="currentType?.numLabel" :label="currentType.numLabel">
          <el-input-number v-model="dataForm.numValue" :controls="false" placeholder="不填" />
        </el-form-item>
        <el-form-item label="备注" class="full">
          <el-input v-model="dataForm.remark" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dataDialog = false">取消</el-button>
        <el-button type="primary" @click="saveData">{{ dataEditingId === null ? '新增选项' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dict {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: 18px;
  align-items: start;
}

.find {
  max-width: 220px;
}

@media (width < 1000px) {
  .dict {
    grid-template-columns: 1fr;
  }
}
</style>
