<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { categoryTreeApi, supplierApi } from '@/api/supplier'
import type { CategoryNode, SupplierDetail, SupplierForm, SupplierSaveResult } from '@/types/supplier'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import DictSelect from '@/components/DictSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useFieldAccess } from '@/composables/useFieldAccess'
import { useProductLines } from '@/composables/useProductLines'
const { canSeeSupplierContact } = useFieldAccess()

/**
 * 新建、编辑供应商（RFC 004 §12）。编码自动编、状态走准入审批和停用拉黑，都不在这里改。
 * 待准入以外的状态，★ 字段（全称、信用代码、结算、账期、发票、税率、银行三项）改了先挂起，另一个有准入权限的人批了才生效：
 * 这些字段旁边标"改了要审批"。信用代码原来是空的，补上直接生效（11-15 用户定），不标。
 * 账号：没有 bank 权限的人只读（后端也不收）；有的人打开编辑时取一次全号填进来（会记一条"查看银行账号"的日志）
 *
 *   const formRef = ref<InstanceType<typeof SupplierFormDialog>>()
 *   formRef.value?.open()            新建
 *   formRef.value?.open(detail)      编辑
 *   <SupplierFormDialog ref="formRef" @saved="onSaved" />
 */
const emit = defineEmits<{ saved: [result: SupplierSaveResult, created: boolean] }>()

const store = useUserStore()
const canSeeBank = computed(() => store.hasPerm('supplier:supplier:bank'))

const visible = ref(false)
const saving = ref(false)
const editing = ref<SupplierDetail | null>(null)
const formRef = ref<FormInstance>()

function blank(): SupplierForm {
  return {
    name: '', shortName: '', creditCode: null, level: null, categories: [], productLines: [], goodAtIds: [],
    contact: null, phone: null, wechat: null, address: null,
    settleMethod: null, creditDays: null, invoiceType: null, taxPct: null,
    bankName: null, bankAccount: null, bankAccountName: null, buyerId: null, remark: null,
  }
}

const form = reactive<SupplierForm>(blank())
const guard = useUnsavedChanges(form, visible)

const rules: FormRules = {
  name: [{ required: true, message: '请填全称', trigger: 'blur' }],
  shortName: [{ required: true, message: '请填简称', trigger: 'blur' }],
  categories: [{ type: 'array', required: true, min: 1, message: '至少选一个分类', trigger: 'change' }],
  productLines: [{ type: 'array', required: true, min: 1, message: '至少选一条品类线', trigger: 'change' }],
}

// ---------- 品类线（RFC 013 §2）：只有一条线的人不显示，新建自动带上；我看不到的线只列出来，保存时后端原样留着 ----------
const lines = useProductLines()
const multiLine = lines.multi
const lineOptions = computed(() => {
  const shown = lines.options.value.map((o) => ({ value: o.value, label: o.label }))
  for (const value of form.productLines) {
    if (!shown.some((o) => o.value === value)) shown.push({ value, label: `${lines.labelOf(value)}（已停用）` })
  }
  return shown
})
const hiddenLines = computed(() => (editing.value?.productLines ?? []).filter((l) => !lines.canSee(l)).map((l) => lines.labelOf(l)).join('、'))

const LEVELS = ['A', 'B', 'C', 'D']

/** 关键字段改了要审批：编辑、而且不是待准入 */
const gate = computed(() => editing.value !== null && editing.value.status !== 'PENDING_ADMIT')
/** 信用代码原来是空的，补上直接生效 */
const creditGate = computed(() => gate.value && !!editing.value?.creditCode)
/** 有一份改动在等审批：再改关键字段会被拒 */
const pendingLabels = computed(() => editing.value?.pendingChange?.items.map((item) => item.label).join('、') ?? '')

// ---------- 擅长品类的树（basic 域的接口；拿不到就只能去掉已有的，不能加） ----------
const tree = ref<CategoryNode[]>([])
const treeFailed = ref(false)

async function loadTree() {
  try {
    tree.value = await categoryTreeApi.tree()
    treeFailed.value = false
  } catch {
    tree.value = []
    treeFailed.value = true
  }
}

function idsOf(nodes: CategoryNode[], into = new Set<string>()) {
  for (const node of nodes) {
    into.add(node.id)
    if (node.children) idsOf(node.children, into)
  }
  return into
}

/** 已经选了、但树里没有的（品类停用了）：挂在最后，照样显示名字，想去掉就去掉 */
const treeData = computed<CategoryNode[]>(() => {
  const known = idsOf(tree.value)
  const missing = (editing.value?.goodAt ?? [])
    .filter((item) => !known.has(item.id))
    .map((item) => ({ id: item.id, name: `${item.name}（已停用）` }))
  return [...tree.value, ...missing]
})

// ---------- 打开 ----------
async function open(detail?: SupplierDetail) {
  editing.value = detail ?? null
  const values = blank()
  if (detail) {
    Object.assign(values, {
      name: detail.name, shortName: detail.shortName, creditCode: detail.creditCode, level: detail.level,
      categories: [...detail.categories], goodAtIds: detail.goodAt.map((item) => item.id),
      productLines: detail.productLines.filter((l) => lines.canSee(l)),
      contact: detail.contact, phone: detail.phone, wechat: detail.wechat, address: detail.address,
      settleMethod: detail.settleMethod, creditDays: detail.creditDays, invoiceType: detail.invoiceType, taxPct: detail.taxPct,
      bankName: detail.bankName, bankAccount: detail.bankAccount, bankAccountName: detail.bankAccountName,
      buyerId: detail.buyerId, remark: detail.remark,
    })
    // 有权限的人编辑时填全号：表单里是遮过的，保存时后端会当作没改，但人看不到原号没法核对
    if (canSeeBank.value && detail.bankAccount) {
      values.bankAccount = (await supplierApi.bank(detail.id)).bankAccount
    }
  }
  if (!detail && lines.only.value) values.productLines = [lines.only.value]
  Object.assign(form, values)
  void loadTree()
  visible.value = true
}

/** 空串 → null，字符串去掉首尾空格：后端"空 = 还没有" */
function payload(): SupplierForm {
  const data = { ...form, categories: [...form.categories], productLines: [...form.productLines], goodAtIds: [...form.goodAtIds] }
  for (const key of Object.keys(data) as (keyof SupplierForm)[]) {
    const value = data[key]
    if (typeof value === 'string') {
      const trimmed = value.trim()
      ;(data as Record<string, unknown>)[key] = trimmed === '' && key !== 'name' && key !== 'shortName' ? null : trimmed
    }
  }
  if (data.creditCode) data.creditCode = data.creditCode.replace(/\s+/g, '').toUpperCase()
  if (data.bankAccount) data.bankAccount = data.bankAccount.replace(/\s+/g, '')
  // 没有 bank 权限：后端不收账号，也不把遮过的值发回去
  // 无权字段由请求层按供应商域完全省略，保留服务器既有值。
  return data
}

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const current = editing.value
    const result = current ? await supplierApi.update(current.id, payload()) : await supplierApi.create(payload())
    visible.value = false
    if (!current) {
      ElMessage.success(`${form.shortName.trim()} 已建好，待准入；资料补齐后点「申请准入」`)
    } else if (result.pending.length > 0) {
      ElMessage.warning(`已保存；${result.pending.map((item) => item.label).join('、')}要等审批，批准前还按原来的用`)
    } else {
      ElMessage.success('已保存')
    }
    emit('saved', result, !current)
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editing ? `编辑供应商 ${editing.code}` : '新建供应商'"
    width="min(680px, 94vw)"
    :before-close="guard.beforeClose"
  >
    <el-alert
      v-if="editing?.pendingChange"
      type="warning"
      :closable="false"
      class="pending"
      :title="`${pendingLabels}的改动还在等审批：关键字段先别改，撤回或者批完再改；其他资料照常改`"
    />
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item prop="name" class="full">
        <template #label>全称<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input v-model="form.name" maxlength="100" placeholder="营业执照上的名称" />
      </el-form-item>
      <el-form-item label="简称" prop="shortName">
        <el-input v-model="form.shortName" maxlength="50" placeholder="单据、下拉里显示它" />
      </el-form-item>
      <el-form-item>
        <template #label>统一社会信用代码<i v-if="creditGate" class="kmark">改了要审批</i></template>
        <el-input v-model="form.creditCode" class="code" maxlength="20" placeholder="18 位，营业执照上有" />
      </el-form-item>
      <el-form-item label="分类" prop="categories">
        <DictSelect v-model="form.categories" type="supplier_category" multiple placeholder="可以选几个" />
      </el-form-item>
      <el-form-item v-if="multiLine" label="品类线" prop="productLines">
        <el-select v-model="form.productLines" multiple filterable placeholder="做哪几条线">
          <el-option v-for="o in lineOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <span v-if="hiddenLines" class="dim hint">还做{{ hiddenLines }}：你看不到这条线，保存时原样留着</span>
      </el-form-item>
      <el-form-item label="等级">
        <el-select v-model="form.level" clearable filterable placeholder="A–D">
          <el-option v-for="level in LEVELS" :key="level" :label="`${level} 级`" :value="level" />
        </el-select>
      </el-form-item>
      <el-form-item label="擅长品类" class="full">
        <el-tree-select
          v-model="form.goodAtIds"
          :data="treeData"
          :props="{ label: 'name', children: 'children' }"
          node-key="id"
          multiple
          filterable
          check-strictly
          show-checkbox
          :render-after-expand="false"
          :placeholder="treeFailed ? '品类没加载出来，只能去掉已有的' : '选这家做得好的品类，挑供应商时参考'"
          no-data-text="还没有品类，先在基础资料里建"
        />
      </el-form-item>
      <el-form-item v-if="canSeeSupplierContact" label="联系人">
        <el-input v-model="form.contact" maxlength="50" />
      </el-form-item>
      <el-form-item v-if="canSeeSupplierContact" label="电话">
        <el-input v-model="form.phone" maxlength="32" />
      </el-form-item>
      <el-form-item v-if="canSeeSupplierContact" label="微信">
        <el-input v-model="form.wechat" maxlength="64" />
      </el-form-item>
      <el-form-item label="负责采购员">
        <UserSelect v-model="form.buyerId" :label="editing?.buyerName" clearable />
      </el-form-item>
      <el-form-item v-if="canSeeSupplierContact" label="地址" class="full">
        <el-input v-model="form.address" maxlength="255" />
      </el-form-item>
      <el-form-item>
        <template #label>结算方式<i v-if="gate" class="kmark">改了要审批</i></template>
        <DictSelect v-model="form.settleMethod" type="settle_method" clearable />
      </el-form-item>
      <el-form-item>
        <template #label>账期（天）<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input-number v-model="form.creditDays" :min="0" :max="3650" :precision="0" :controls="false" align="left" class="num-input" />
      </el-form-item>
      <el-form-item>
        <template #label>发票类型<i v-if="gate" class="kmark">改了要审批</i></template>
        <DictSelect v-model="form.invoiceType" type="invoice_type" clearable />
      </el-form-item>
      <el-form-item>
        <template #label>税率（%）<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input-number v-model="form.taxPct" :min="0" :max="100" :precision="2" :controls="false" align="left" class="num-input" placeholder="如 13" />
      </el-form-item>
      <el-form-item v-if="canSeeBank">
        <template #label>开户行<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input v-model="form.bankName" maxlength="100" />
      </el-form-item>
      <el-form-item v-if="canSeeBank">
        <template #label>收款户名<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input v-model="form.bankAccountName" maxlength="100" placeholder="一般和全称一样" />
      </el-form-item>
      <el-form-item v-if="canSeeBank" class="full">
        <template #label>账号<i v-if="gate" class="kmark">改了要审批</i></template>
        <el-input
          v-model="form.bankAccount"
          class="code"
          maxlength="40"
          :disabled="!canSeeBank"
          :placeholder="canSeeBank ? '' : '要有看银行全号的权限才能填、改'"
        />
      </el-form-item>
      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存' : '新建供应商' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.pending {
  margin-bottom: 14px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.kmark {
  margin-left: 6px;
  padding: 0 6px;
  font-size: 11px;
  font-style: normal;
  font-weight: 500;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: 5px;
}

.num-input {
  width: 100%;
}
</style>
