<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { supplierApi } from '@/api/supplier'
import {
  CHANGE_STATUS, SUPPLIER_STATUS, type SupplierAction, type SupplierCert, type SupplierChange, type SupplierDetail,
  type SupplierSaveResult,
} from '@/types/supplier'
import { useDict } from '@/composables/useDict'
import { showBizError } from '@/composables/useBizError'
import { useUserStore } from '@/stores/user'
import { useTabsStore } from '@/stores/tabs'
import { ApiError } from '@/utils/request'
import { ErrorCode } from '@/utils/errorCodes'
import { formatDate, formatDateTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PriceHistory from '@/components/price/PriceHistory.vue'
import SupplierFormDialog from './SupplierFormDialog.vue'
import CertDialog from './CertDialog.vue'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost, canSeeProductionPrice, canSeeSupplierContact } = useFieldAccess()

/**
 * 供应商详情（RFC 004 §12，原型 /suppliers/:code）。左边：状态横条、待批改动、资质 / 改动记录 / 价格记录 / 操作记录四个页签；
 * 右边：资料。顶上的按钮按后端给的 actions 显示（状态 + 权限 + 不能自己批自己，后端算好的）。
 * 价格记录页签（RFC 006 §9）要 price:price:list，没有就不显示。
 * 一家供应商一个页签（详情路由，见 router/index.ts）：id 在这里取一次，不跟着路由变
 */
const route = useRoute()
const router = useRouter()
const store = useUserStore()
const tabs = useTabsStore()
const categoryDict = useDict('supplier_category')
const settleDict = useDict('settle_method')
const invoiceDict = useDict('invoice_type')
const certDict = useDict('cert_type')

const id = routeId(route.params.id)
const path = route.path
const supplier = ref<SupplierDetail | null>(null)
const loading = ref(false)
const notFound = ref(false)
const activeTab = ref('certs')
let seq = 0

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  const current = ++seq
  loading.value = true
  try {
    const detail = await read.run(() => supplierApi.get(id, { silent: true }))
    if (!read.current()) return
    if (current !== seq) return
    supplier.value = detail
    notFound.value = false
    tabs.rename(path, `供应商 ${detail.shortName}`)
  } catch (error) {
    if (!read.current()) return
    if (current !== seq) return
    if (error instanceof ApiError && error.code === 404) {
      supplier.value = null
      notFound.value = true
    } else if (error instanceof ApiError) {
      ElMessage.error(error.message)
    }
  } finally {
    if (current === seq) loading.value = false
  }
}

/** 动作做完：资料、证照、改动、操作记录都可能变了，一起刷新 */
function reloadAll() {
  void load()
  void loadCerts()
  void loadChanges()
  logsRef.value?.reload()
}

const status = computed(() => (supplier.value ? SUPPLIER_STATUS[supplier.value.status] : null))
const can = (action: SupplierAction) => supplier.value?.actions.includes(action) ?? false
const canSeeBank = computed(() => store.hasPerm('supplier:supplier:bank'))

const categoryText = computed(() => supplier.value?.categories.map((value) => categoryDict.labelOf(value)).join('、') ?? '')
const goodAtText = computed(() => supplier.value?.goodAt.map((item) => item.name).join('、') ?? '')
const settleText = computed(() => {
  const s = supplier.value
  if (!s) return ''
  const method = settleDict.labelOf(s.settleMethod)
  return [method, s.creditDays != null ? `${s.creditDays} 天` : ''].filter(Boolean).join(' · ')
})
const invoiceText = computed(() => {
  const s = supplier.value
  if (!s) return ''
  return [invoiceDict.labelOf(s.invoiceType), s.taxPct != null ? `税率 ${s.taxPct}%` : ''].filter(Boolean).join(' · ')
})
const legacyAttrs = computed(() => Object.entries(supplier.value?.legacyAttrs ?? {}).filter(([, value]) => value))

// ---------- 账号：遮过的；有 bank 权限的点"显示"取全号（后端记一条日志） ----------
const fullAccount = ref<string | null>(null)
const revealing = ref(false)

async function toggleAccount() {
  if (fullAccount.value) {
    fullAccount.value = null
    return
  }
  revealing.value = true
  try {
    fullAccount.value = (await supplierApi.bank(id)).bankAccount ?? null
  } finally {
    revealing.value = false
  }
}

// ---------- 状态动作 ----------
const name = computed(() => supplier.value?.shortName ?? '')

async function confirmThen(message: string, title: string, button: string, run: () => Promise<unknown>, done: string, danger = false) {
  await ElMessageBox.confirm(message, title, {
    confirmButtonText: button,
    cancelButtonText: '取消',
    confirmButtonClass: danger ? 'el-button--danger' : undefined,
  })
  await run()
  ElMessage.success(done)
  reloadAll()
}

async function promptThen(message: string, title: string, button: string, placeholder: string,
  run: (reason: string) => Promise<unknown>, done: string, danger = false) {
  const { value } = await ElMessageBox.prompt(message, title, {
    confirmButtonText: button,
    cancelButtonText: '取消',
    confirmButtonClass: danger ? 'el-button--danger' : undefined,
    inputType: 'textarea',
    inputPlaceholder: placeholder,
    inputValidator: (text) => (text?.trim() ? (text.trim().length <= 500 ? true : '原因最多 500 个字') : '请填写原因'),
  })
  await run(value.trim())
  ElMessage.success(done)
  reloadAll()
}

/** 申请准入：缺资料时后端一次列全（1000 + 缺项），弹清单 */
async function submit() {
  try {
    await supplierApi.submit(id, { silent: true })
  } catch (error) {
    if (!(error instanceof ApiError)) throw error
    if (error.code === ErrorCode.BIZ && Array.isArray(error.data) && error.data.length > 0) {
      showBizError(error.code, error.message, error.data)
    } else {
      ElMessage.error(error.message)
    }
    return
  }
  ElMessage.success('已提交准入审批，等另一个有准入权限的人批')
  reloadAll()
}

const audit = () =>
  confirmThen('通过后能选进采购单、生产单。', `通过准入：${name.value}`, '通过准入',
    () => supplierApi.audit(id), `${name.value} 准入通过，现在能选进采购单、生产单`)

const reject = () =>
  promptThen('驳回后回到「待准入」，申请人照着原因改好再申请。', `驳回准入申请：${name.value}`, '驳回',
    '写清楚要补什么、改什么', (reason) => supplierApi.reject(id, reason), '已驳回，回到待准入')

const withdraw = () =>
  confirmThen('撤回后回到「待准入」，可以接着改资料、换证照，改好再申请。', '撤回准入申请', '撤回',
    () => supplierApi.withdraw(id), '已撤回，回到待准入')

const disable = () =>
  confirmThen('停用后新的采购单、生产单选不到它；已有单据照常入库、回货。随时可以恢复合作。', `停用 ${name.value}`, '停用',
    () => supplierApi.disable(id), `${name.value} 已停用`, true)

async function enable() {
  await supplierApi.enable(id)
  ElMessage.success(`${name.value} 恢复合作了`)
  reloadAll()
}

const blacklist = () =>
  promptThen('拉黑后新的单据、打样、录价格都选不到它；已有单据照常入库、回货。', `拉黑 ${name.value}`, '拉黑',
    '写清楚为什么拉黑，以后解除时要看', (reason) => supplierApi.blacklist(id, reason), `${name.value} 已拉黑`, true)

const unblacklist = () =>
  promptThen('准入过的回到「停用」，要用再点恢复合作；从没准入过的回到「待准入」，要走准入审批。',
    `解除黑名单：${name.value}`, '解除黑名单', '写清楚为什么解除', (reason) => supplierApi.unblacklist(id, reason),
    `${name.value} 已解除黑名单`)

async function remove() {
  await ElMessageBox.confirm('没有单据、物料、价格用过它才能删，删了找不回来；用过的删不掉，请改成停用。', `删除 ${name.value}`, {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await supplierApi.remove(id)
  ElMessage.success(`${name.value} 已删除`)
  // 这一页没东西了：回列表，关掉它的页签
  await router.push('/suppliers')
  tabs.close(path, '/suppliers')
}

/** 右上角"更多"里的：停用、拉黑、删除这类不常点、点了影响大的 */
const moreItems = computed(() =>
  [
    can('disable') && { command: 'disable', label: '停用' },
    can('blacklist') && { command: 'blacklist', label: '拉黑', danger: true },
    can('remove') && { command: 'remove', label: '删除', danger: true },
  ].filter((item): item is { command: string; label: string; danger?: boolean } => !!item),
)

function onMore(command: string) {
  if (command === 'disable') void disable()
  else if (command === 'blacklist') void blacklist()
  else if (command === 'remove') void remove()
}

// ---------- 编辑 ----------
const formRef = ref<InstanceType<typeof SupplierFormDialog>>()

function edit() {
  if (supplier.value) void formRef.value?.open(supplier.value)
}

function onSaved(_result: SupplierSaveResult) {
  reloadAll()
}

// ---------- 待批的改动 ----------
const pending = computed(() => supplier.value?.pendingChange ?? null)

function changeText(change: SupplierChange) {
  return change.items.map((item) => `${item.label} ${item.before} → ${item.after}`).join('；')
}

const approveChange = (change: SupplierChange) =>
  confirmThen(`批准后马上生效：${changeText(change)}`, '批准改动', '批准改动',
    () => supplierApi.approveChange(id, change.id), '改动已批准，生效了')

const rejectChange = (change: SupplierChange) =>
  promptThen('驳回后这批改动不生效，资料保持原样。', '驳回改动', '驳回', '写清楚为什么不批',
    (reason) => supplierApi.rejectChange(id, change.id, reason), '已驳回，资料保持原样')

const withdrawChange = (change: SupplierChange) =>
  confirmThen('撤回后这批改动不生效，要改再重新提。', '撤回改动', '撤回',
    () => supplierApi.withdrawChange(id, change.id), '已撤回')

// ---------- 资质 ----------
const certs = ref<SupplierCert[]>([])
const certsLoading = ref(false)
const certRef = ref<InstanceType<typeof CertDialog>>()
const certRequest = useLatestRequest()

async function loadCerts() {
  const read = certRequest.begin()
  const current = id
  certsLoading.value = true
  try {
    const result = await read.run(() => supplierApi.certs(current))
    if (read.current() && current === id) certs.value = result
  } catch {
    // 404 由详情那边显示；其他错误拦截器已经弹过了
  } finally {
    if (read.current()) certsLoading.value = false
  }
}

/** 有效期：还剩几天；过期的、30 天内到期的标出来 */
function certLeft(cert: SupplierCert): { text: string; tone: '' | 'warn' | 'danger' } {
  if (!cert.validUntil) return { text: '长期有效', tone: '' }
  const days = dayjs(cert.validUntil).diff(dayjs().startOf('day'), 'day')
  if (days < 0) return { text: `过期 ${-days} 天`, tone: 'danger' }
  if (days === 0) return { text: '今天到期', tone: 'warn' }
  return { text: `还有 ${days} 天`, tone: days <= 30 ? 'warn' : '' }
}

async function removeCert(cert: SupplierCert) {
  await ElMessageBox.confirm(`${certDict.labelOf(cert.certType)} ${cert.fileName} 删了找不回来；证照换新的可以点「换」。`, '删除证照', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    confirmButtonClass: 'el-button--danger',
  })
  await supplierApi.removeCert(id, cert.id)
  ElMessage.success('证照已删除')
  void loadCerts()
  logsRef.value?.reload()
}

function onCertSaved() {
  void loadCerts()
  logsRef.value?.reload()
}

// ---------- 改动记录、操作记录 ----------
const changes = ref<SupplierChange[]>([])
const changesLoading = ref(false)
const changesRequest = useLatestRequest()

async function loadChanges() {
  const read = changesRequest.begin()
  const current = id
  changesLoading.value = true
  try {
    const result = await read.run(() => supplierApi.changes(current))
    if (read.current() && current === id) changes.value = result
  } catch {
    // 拦截器已经弹过了
  } finally {
    if (read.current()) changesLoading.value = false
  }
}

const logsRef = ref<InstanceType<typeof DocLogList>>()
/** 换了一家供应商，DocLogList 看到 load 变了会重新加载 */
const loadLogs = computed(() => {
  const current = id
  return () => supplierApi.logs(current)
})

const LOG_ACTIONS: Record<string, string> = {
  submit: '申请准入',
  withdraw: '撤回准入申请',
  audit: '准入通过',
  reject: '驳回准入',
  disable: '停用',
  enable: '恢复合作',
  blacklist: '拉黑',
  unblacklist: '解除黑名单',
  change: '提改动',
  'change-approve': '批准改动',
  'change-reject': '驳回改动',
  'change-withdraw': '撤回改动',
  'cert-add': '加证照',
  'cert-edit': '换证照',
  'cert-remove': '删证照',
}
const LOG_STATUSES = Object.fromEntries(Object.entries(SUPPLIER_STATUS).map(([key, value]) => [key, value.label]))

// 放在最后：要用到上面的 certs、changes、logsRef
reloadAll()
</script>

<template>
  <div>
    <PageHeader :title="supplier?.shortName" :back="{ label: '供应商', to: '/suppliers' }">
      <template #title>
        <StatusTag v-if="status" :tone="status.tone">{{ status.label }}</StatusTag>
      </template>
      <template v-if="supplier" #actions>
        <el-button v-if="can('submit')" type="primary" @click="submit">申请准入</el-button>
        <el-button v-if="can('audit')" type="primary" @click="audit">通过准入</el-button>
        <el-button v-if="can('reject')" @click="reject">驳回</el-button>
        <el-button v-if="can('withdraw')" @click="withdraw">撤回申请</el-button>
        <el-button v-if="can('enable')" type="primary" @click="enable">恢复合作</el-button>
        <el-button v-if="can('unblacklist')" @click="unblacklist">解除黑名单</el-button>
        <el-button v-if="can('edit')" icon="Edit" @click="edit">编辑</el-button>
        <el-dropdown v-if="moreItems.length" trigger="click" popper-class="supplier-more" @command="onMore">
          <el-button icon="MoreFilled" aria-label="更多操作" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="item in moreItems" :key="item.command" :command="item.command" :class="{ danger: item.danger }">
                {{ item.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这家供应商不存在，可能已经被删除了">
      <el-button @click="router.push('/suppliers')">回供应商列表</el-button>
    </BlankState>

    <div v-else-if="supplier" v-loading="loading" class="detail">
      <div class="body">
        <div class="sub">
          <span>{{ supplier.name }}</span>
          <span class="code">{{ supplier.creditCode || '没填信用代码' }}</span>
          <span v-if="categoryText">{{ categoryText }}</span>
          <span v-if="supplier.level">{{ supplier.level }} 级</span>
        </div>

        <div v-if="supplier.status === 'BLACKLISTED'" class="banner danger">
          <el-icon><WarningFilled /></el-icon>
          <span class="grow">拉黑原因：{{ supplier.blackReason }}</span>
          <span class="dim small">{{ supplier.blackByName }} · {{ formatDateTime(supplier.blackTime) }}</span>
        </div>
        <div v-if="supplier.status === 'ADMITTING'" class="banner">
          <el-icon><Bell /></el-icon>
          <span>
            {{ supplier.admitApplyByName }} {{ formatDateTime(supplier.admitApplyTime) }} 申请准入，等另一个有准入权限的人审批（不能自己批自己）；
            审批期间资料、资质都不能改，要改先撤回。
          </span>
        </div>

        <div v-if="pending" class="banner pend">
          <div class="pend-head">
            <el-icon><Bell /></el-icon>
            <b>关键信息改动等审批</b>
            <span class="dim">{{ pending.applyByName }} · {{ formatDateTime(pending.applyTime) }} 提的；批准前还按原来的用</span>
            <span class="grow" />
            <el-button v-if="pending.actions.includes('withdraw')" size="small" @click="withdrawChange(pending)">撤回</el-button>
            <el-button v-if="pending.actions.includes('reject')" size="small" @click="rejectChange(pending)">驳回</el-button>
            <el-button v-if="pending.actions.includes('approve')" size="small" type="primary" @click="approveChange(pending)">批准改动</el-button>
          </div>
          <table class="pend-items">
            <tbody>
              <tr v-for="item in pending.items" :key="item.field">
                <td>{{ item.label }}</td>
                <td class="dim"><s>{{ item.before }}</s></td>
                <td>→ {{ item.after }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane :label="certs.length ? `资质 ${certs.length}` : '资质'" name="certs">
            <el-table v-loading="certsLoading" :data="certs" :empty-text="'还没传证照；申请准入要有营业执照'">
              <el-table-column label="类型" min-width="100">
                <template #default="{ row }">{{ certDict.labelOf((row as SupplierCert).certType) }}</template>
              </el-table-column>
              <el-table-column label="有效期至" width="110" class-name="num">
                <template #default="{ row }">{{ (row as SupplierCert).validUntil ? formatDate((row as SupplierCert).validUntil) : '—' }}</template>
              </el-table-column>
              <el-table-column label="" width="96">
                <template #default="{ row }">
                  <span :class="['left', certLeft(row as SupplierCert).tone]">{{ certLeft(row as SupplierCert).text }}</span>
                </template>
              </el-table-column>
              <el-table-column label="文件" min-width="150">
                <template #default="{ row }">
                  <a :href="(row as SupplierCert).fileUrl" target="_blank" rel="noopener" class="file">{{ (row as SupplierCert).fileName }}</a>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="100">
                <template #default="{ row }">{{ (row as SupplierCert).remark || '—' }}</template>
              </el-table-column>
              <el-table-column label="上传" width="140">
                <template #default="{ row }">
                  <span class="dim small">{{ (row as SupplierCert).createByName }} · {{ formatDate((row as SupplierCert).createTime) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="can('edit')" label="" width="100" align="right">
                <template #default="{ row }">
                  <el-button link @click="certRef?.open(id, row as SupplierCert)">换</el-button>
                  <el-button link type="danger" @click="removeCert(row as SupplierCert)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-button v-if="can('edit')" class="add-cert" icon="Plus" @click="certRef?.open(id)">加一份证照</el-button>
          </el-tab-pane>

          <el-tab-pane :label="changes.length ? `改动记录 ${changes.length}` : '改动记录'" name="changes">
            <el-table v-loading="changesLoading" :data="changes" empty-text="还没改过关键信息（全称、信用代码、结算、发票、银行这些）">
              <el-table-column label="提的时间" width="150" class-name="num">
                <template #default="{ row }">{{ formatDateTime((row as SupplierChange).applyTime) }}</template>
              </el-table-column>
              <el-table-column prop="applyByName" label="提的人" width="90" />
              <el-table-column label="改了什么" min-width="260">
                <template #default="{ row }">
                  <div v-for="item in (row as SupplierChange).items" :key="item.field">
                    {{ item.label }} <s class="dim">{{ item.before }}</s> → {{ item.after }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="结果" width="90">
                <template #default="{ row }">
                  <StatusTag :tone="CHANGE_STATUS[(row as SupplierChange).status].tone">{{ CHANGE_STATUS[(row as SupplierChange).status].label }}</StatusTag>
                </template>
              </el-table-column>
              <el-table-column label="处理" min-width="180">
                <template #default="{ row }">
                  <template v-if="(row as SupplierChange).auditByName">
                    <span class="dim small">{{ (row as SupplierChange).auditByName }} · {{ formatDateTime((row as SupplierChange).auditTime) }}</span>
                    <div v-if="(row as SupplierChange).rejectReason">原因：{{ (row as SupplierChange).rejectReason }}</div>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane v-if="(canSeeCost || canSeeProductionPrice) && store.hasPerm('price:price:list')" label="价格记录" name="prices" lazy>
            <PriceHistory :subject="{ kind: 'supplier', id, code: supplier.code, name: supplier.shortName }" />
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logsRef" :load="loadLogs" :actions="LOG_ACTIONS" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="aside">
        <dl class="props">
          <dt>编码</dt><dd class="code">{{ supplier.code }}</dd>
          <dt>全称</dt><dd>{{ supplier.name }}</dd>
          <dt>信用代码</dt><dd class="code">{{ supplier.creditCode || '—' }}</dd>
          <dt>擅长品类</dt><dd>{{ goodAtText || '—' }}</dd>
          <template v-if="canSeeSupplierContact"><dt>联系人</dt><dd>{{ supplier.contact || '—' }}</dd>
          <dt>电话</dt><dd>{{ supplier.phone || '—' }}</dd>
          <dt>微信</dt><dd>{{ supplier.wechat || '—' }}</dd>
          <dt>地址</dt><dd>{{ supplier.address || '—' }}</dd></template>
          <dt>结算</dt><dd>{{ settleText || '—' }}</dd>
          <dt>发票</dt><dd>{{ invoiceText || '—' }}</dd>
          <template v-if="canSeeBank"><dt>开户行</dt><dd>{{ supplier.bankName || '—' }}</dd>
          <dt>账号</dt>
          <dd>
            <span class="code">{{ fullAccount ?? (supplier.bankAccount || '—') }}</span>
            <el-button v-if="canSeeBank && supplier.bankAccount" link type="primary" size="small" class="reveal" :loading="revealing" @click="toggleAccount">
              {{ fullAccount ? '隐藏' : '显示' }}
            </el-button>
          </dd>
          <dt>收款户名</dt><dd>{{ supplier.bankAccountName || '—' }}</dd></template>
          <dt>负责采购员</dt><dd>{{ supplier.buyerName || '—' }}</dd>
          <dt>准入</dt><dd>{{ supplier.admitTime ? `${supplier.admitByName ?? ''} · ${formatDate(supplier.admitTime)}` : '—' }}</dd>
          <dt>建档</dt><dd>{{ supplier.createByName ?? '系统' }} · {{ formatDate(supplier.createTime) }}</dd>
          <dt>备注</dt><dd class="pre">{{ supplier.remark || '—' }}</dd>
        </dl>
        <template v-if="legacyAttrs.length">
          <h4>领猫附加属性</h4>
          <dl class="props">
            <template v-for="[key, value] in legacyAttrs" :key="key">
              <dt>{{ key }}</dt><dd>{{ value }}</dd>
            </template>
          </dl>
        </template>
      </aside>
    </div>

    <div v-else v-loading="loading" class="placeholder" />

    <SupplierFormDialog ref="formRef" @saved="onSaved" />
    <CertDialog ref="certRef" @saved="onCertSaved" />
  </div>
</template>

<style scoped>
/* 左边内容、右边资料（原型的 .detail）；窄屏上下排 */
.detail {
  display: flex;
  min-height: calc(100vh - var(--lm-header-height) - 40px);
}

.body {
  flex: 1;
  min-width: 0;
  padding: 20px 24px 48px;
}

.aside {
  flex: none;
  width: 320px;
  padding: 20px 18px 40px;
  background: var(--lm-greige-2);
  border-left: 1px solid var(--lm-line);
}

.placeholder {
  min-height: 240px;
}

.sub {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  padding-bottom: 14px;
  margin-bottom: 16px;
  font-size: 12.5px;
  color: var(--lm-ink-2);
  border-bottom: 1px solid var(--lm-line-soft);
}

.banner {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 11px 15px;
  margin-bottom: 16px;
  font-size: 12.5px;
  color: var(--lm-ink);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: 10px;
}

.banner > .el-icon,
.pend-head > .el-icon {
  flex: none;
  color: var(--lm-pending-text);
}

.banner.danger {
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

.banner.danger > .el-icon {
  color: var(--lm-danger-text);
}

.banner.pend {
  display: block;
}

.pend-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.pend-items {
  margin: 8px 0 0 26px;
  font-size: 13px;
  border-collapse: collapse;
}

.pend-items td {
  padding: 2px 14px 2px 0;
}

.grow {
  flex: 1;
}

.small {
  font-size: 12px;
}

.left.warn {
  color: var(--lm-pending-text);
}

.left.danger {
  color: var(--lm-danger-text);
}

.file {
  color: var(--lm-accent);
}

.add-cert {
  margin-top: 12px;
}

.props {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 8px 10px;
  padding: 14px;
  margin: 0;
  font-size: 12.5px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.props dt {
  color: var(--lm-muted);
}

.props dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--lm-ink);
}

.pre {
  white-space: pre-line;
}

.reveal {
  margin-left: 6px;
  vertical-align: baseline;
}

.aside h4 {
  margin: 20px 0 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--lm-ink-2);
}

@media (max-width: 1000px) {
  .detail {
    flex-direction: column;
  }

  .aside {
    width: auto;
    border-top: 1px solid var(--lm-line);
    border-left: none;
  }
}
</style>

<style>
/* "更多"菜单挂在 body 下，scoped 的样式够不着 */
.supplier-more .el-dropdown-menu__item.danger {
  color: var(--lm-danger-text);
}
</style>
