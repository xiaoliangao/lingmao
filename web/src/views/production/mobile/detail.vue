<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { productionApi } from '@/api/production'
import DictSelect from '@/components/DictSelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import { addDaysStr, todayStr } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { RESPONSIBLE_PARTY, type ResponsibleParty } from '@/types/order'
import {
  NODE_STATE,
  PRODUCTION_KIND,
  type NodeReportAction,
  type ProductionAction,
  type ProductionDetail,
  type ProductionNode,
} from '@/types/production'
import type { FileVO } from '@/types/system'
import { formatDate, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'

/**
 * 手机报工的一张单（/mobile/:id，docs/api/05 §1.4、§3.8、§4）。按 360px 宽做，按钮 44px 高，日期用原生 <input type="date">。
 * - 一页里切三个视图：概览（两列大节点按钮）↔ 报进度 ↔ 上报异常，左上"返回"一层层退；表单有没保存的改动，退之前问
 * - 节点按钮按 state 上色，下面小字"完成 11-20 / 进行中 / 计划 11-25"；没有 report 动作时点不了
 * - 报进度、上报异常的规则和电脑上一样（实际完成不预填；责任方默认我方，不列"客户"；照片必传）
 * - 单子不存在或不归我看：写一句、给回列表的按钮；页签切回来重新取
 */
const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const issueTypes = useDict('issue_type')

type View = 'overview' | 'report' | 'issue'
const view = ref<View>('overview')
const detail = ref<ProductionDetail | null>(null)
const notFound = ref(false)
const loading = ref(false)
const submitting = ref(false)
const selectedNode = ref<ProductionNode | null>(null)
const today = todayStr()

const PARTY_OPTIONS: { label: string; value: '' | ResponsibleParty }[] = [
  ...(['SELF', 'FACTORY', 'SUPPLIER'] as ResponsibleParty[]).map((value) => ({ value, label: RESPONSIBLE_PARTY[value] })),
  { label: '待定', value: '' },
]

const reportForm = reactive({
  actualStart: today,
  actualFinish: '',
  doneQty: null as number | null,
  remark: '',
  photos: [] as FileVO[],
})
const issueForm = reactive({
  issueType: '',
  responsibleParty: 'SELF' as '' | ResponsibleParty,
  description: '',
  photos: [] as FileVO[],
  impactDelivery: false,
  newExpectDate: '',
})
// 两个表单各记"打开时的样子"：切进表单那一刻（view 变）记下
const reportGuard = useUnsavedChanges(reportForm, computed(() => view.value === 'report'))
const issueGuard = useUnsavedChanges(issueForm, computed(() => view.value === 'issue'))

const can = (a: ProductionAction) => detail.value?.actions.includes(a) ?? false

async function load() {
  loading.value = true
  try {
    detail.value = await productionApi.get(id)
    notFound.value = false
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  } finally {
    loading.value = false
  }
}

onMounted(load)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  // 填到一半切走再回来：表单留着，只刷新单子
  void load()
})

function onBack() {
  if (view.value === 'report') reportGuard.beforeClose(() => (view.value = 'overview'))
  else if (view.value === 'issue') issueGuard.beforeClose(() => (view.value = 'overview'))
  else void router.push('/mobile')
}

function nodeSubText(n: ProductionNode): string {
  if (n.actualFinish) return `完成 ${formatDate(n.actualFinish).slice(5)}`
  if (n.actualStart) return '进行中'
  return `计划 ${formatDate(n.planFinish).slice(5)}`
}

function openNodeReport(node: ProductionNode) {
  if (!can('report')) return
  selectedNode.value = node
  Object.assign(reportForm, {
    actualStart: node.actualStart ?? todayStr(),
    actualFinish: '',
    doneQty: node.doneQty ?? detail.value?.totalQty ?? null,
    remark: '',
    photos: [],
  })
  view.value = 'report'
}

function openIssueForm() {
  Object.assign(issueForm, {
    issueType: '',
    responsibleParty: 'SELF',
    description: '',
    photos: [],
    impactDelivery: false,
    newExpectDate: addDaysStr(detail.value?.expectDate ?? detail.value?.dueDate, 5),
  })
  view.value = 'issue'
}

const finished = computed(() => !!selectedNode.value?.actualFinish)
const titleText = computed(() => {
  if (view.value === 'report') return selectedNode.value?.nodeName ?? '报进度'
  if (view.value === 'issue') return '上报异常'
  return detail.value?.styleCode ?? ''
})
const lastReportText = computed(() => {
  const n = selectedNode.value
  return n?.lastReportByName && n.lastReportTime ? ` · 上次 ${n.lastReportByName} ${formatDate(n.lastReportTime).slice(5)} 报的` : ''
})

async function submitNode(action: NodeReportAction) {
  const node = selectedNode.value
  if (!node) return
  if (action === 'START' && !reportForm.actualStart) return void ElMessage.warning('填实际开始')
  submitting.value = true
  try {
    const res = await productionApi.report(id, node.id, {
      action,
      actualStart: reportForm.actualStart || null,
      actualFinish: action === 'START' ? null : reportForm.actualFinish || null,
      doneQty: reportForm.doneQty,
      remark: orNull(reportForm.remark),
      photos: reportForm.photos,
    })
    const msg = `${node.nodeName}已报${action === 'FINISH' ? '完成' : '开始'}`
    ElMessage.success(res.warning ? `${msg}：${res.warning}` : msg)
    reportGuard.markSaved()
    view.value = 'overview'
    await load()
  } finally {
    submitting.value = false
  }
}

async function submitIssue() {
  if (!issueForm.issueType) return void ElMessage.warning('选异常类型')
  if (!issueForm.description.trim()) return void ElMessage.warning('写一下怎么回事')
  if (!issueForm.photos.length) return void ElMessage.warning('至少拍一张照片')
  if (issueForm.impactDelivery && !issueForm.newExpectDate) return void ElMessage.warning('填预计新交期')
  submitting.value = true
  try {
    const res = await productionApi.reportIssue(id, {
      issueType: issueForm.issueType,
      description: issueForm.description.trim(),
      photos: issueForm.photos,
      impactDelivery: issueForm.impactDelivery,
      newExpectDate: issueForm.impactDelivery ? issueForm.newExpectDate : null,
      responsibleParty: issueForm.responsibleParty || null,
    })
    ElMessage.success(`${res.no} 报上去了`)
    issueGuard.markSaved()
    view.value = 'overview'
    await load()
  } finally {
    submitting.value = false
  }
}

function openDesktopPage() {
  if (detail.value) void router.push(`${PRODUCTION_KIND[detail.value.kind].path}/${id}`)
}
</script>

<template>
  <div v-loading="loading" class="m-detail">
    <header class="m-bar">
      <button type="button" class="back-btn" @click="onBack">← 返回</button>
      <strong class="code title-code">{{ titleText }}</strong>
      <span class="spacer" />
    </header>

    <div v-if="notFound" class="m-body m-empty">
      <p>这张单不存在，或者不归你看</p>
      <button type="button" class="full-btn" @click="router.push('/mobile')">回我的跟单</button>
    </div>

    <template v-else-if="detail">
      <div v-if="view === 'overview'" class="m-body">
        <section class="summary-card">
          <div class="s-line1">
            <strong>{{ detail.styleName }}</strong>
            <span class="dim"> · {{ detail.factoryShortName }}</span>
          </div>
          <div class="s-line2 num">
            <span>{{ formatQty(detail.totalQty) }} 件</span>
            <span>交期 {{ formatDate(detail.expectDate ?? detail.dueDate) }}</span>
            <DaysLeftTag :days-left="detail.daysLeft" :risk="detail.risk" />
          </div>
        </section>

        <p v-if="!detail.nodes.length" class="sub-hint dim">审核下单后才有节点</p>
        <div v-else class="nodes-big">
          <button
            v-for="n in detail.nodes"
            :key="n.id"
            type="button"
            class="big-node"
            :class="`st-${n.state.toLowerCase()}`"
            :disabled="!can('report')"
            @click="openNodeReport(n)"
          >
            <div class="n-top">
              <strong>{{ n.nodeName }}</strong>
              <span class="state-lbl">{{ NODE_STATE[n.state].label }}</span>
            </div>
            <div class="n-sub num">{{ nodeSubText(n) }}</div>
          </button>
        </div>

        <section v-if="detail.openIssues.length" class="open-issues">
          <h3>没关闭的异常 {{ detail.openIssues.length }}</h3>
          <div v-for="iss in detail.openIssues" :key="iss.id" class="iss-item">
            <strong>{{ issueTypes.labelOf(iss.issueType) }}：</strong>{{ iss.description }}
          </div>
        </section>

        <div class="bottom-actions">
          <button v-if="can('reportIssue')" type="button" class="full-btn danger-outline" @click="openIssueForm">上报异常</button>
          <button type="button" class="full-btn" @click="openDesktopPage">在电脑上打开</button>
        </div>
      </div>

      <div v-else-if="view === 'report' && selectedNode" class="m-body">
        <p class="sub-hint dim num">
          计划 {{ formatDate(selectedNode.planStart).slice(5) }} ~ {{ formatDate(selectedNode.planFinish).slice(5) }}{{ lastReportText }}
        </p>
        <p v-if="finished" class="sub-hint warn-text">这个节点已经报过完成</p>

        <label class="m-field">
          <span>实际开始</span>
          <input v-model="reportForm.actualStart" type="date" :max="today" class="native-date" />
        </label>

        <label class="m-field">
          <span>实际完成（报完成时不填就是今天）</span>
          <input v-model="reportForm.actualFinish" type="date" :max="today" class="native-date" />
        </label>

        <label class="m-field">
          <span>数量</span>
          <el-input-number v-model="reportForm.doneQty" :min="0" :precision="0" :controls="false" />
        </label>

        <div class="m-field">
          <span>照片<template v-if="selectedNode.photos.length">（已经有 {{ selectedNode.photos.length }} 张，再传是追加）</template></span>
          <ImageUpload v-model="reportForm.photos" :max="9" />
        </div>

        <label class="m-field">
          <span>备注</span>
          <el-input v-model="reportForm.remark" maxlength="120" />
        </label>

        <div class="dual-btns">
          <button type="button" class="act-btn" :disabled="finished || submitting" @click="submitNode('START')">只报开始</button>
          <button type="button" class="act-btn primary" :disabled="submitting" @click="submitNode('FINISH')">报完成</button>
        </div>
      </div>

      <div v-else-if="view === 'issue'" class="m-body">
        <div class="m-field">
          <span>类型</span>
          <DictSelect v-model="issueForm.issueType" type="issue_type" />
        </div>

        <label class="m-field">
          <span>责任方</span>
          <select v-model="issueForm.responsibleParty" class="native-date">
            <option v-for="p in PARTY_OPTIONS" :key="p.value" :value="p.value">{{ p.label }}</option>
          </select>
        </label>

        <label class="m-field">
          <span>描述</span>
          <el-input v-model="issueForm.description" type="textarea" :rows="3" maxlength="300" />
        </label>

        <div class="m-field">
          <span>照片 <em class="photo-hint">问题部位、到货短缺或现场，对责任时要用</em></span>
          <ImageUpload v-model="issueForm.photos" :max="9" />
        </div>

        <label class="m-check">
          <input v-model="issueForm.impactDelivery" type="checkbox" />
          <span>影响交期</span>
        </label>

        <label v-if="issueForm.impactDelivery" class="m-field">
          <span>预计新交期</span>
          <input v-model="issueForm.newExpectDate" type="date" class="native-date" />
        </label>

        <button type="button" class="full-btn primary" :disabled="submitting" @click="submitIssue">上报</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.m-detail {
  max-width: 560px;
  min-height: 100%;
  margin: 0 auto;
  background: var(--lm-greige-2);
}

.m-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 12px;
  background: var(--lm-paper);
  border-bottom: 1px solid var(--lm-line);
}

.back-btn {
  min-height: 44px;
  padding: 0 8px;
  font: inherit;
  font-size: 14px;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.title-code {
  font-size: 15px;
}

.spacer {
  width: 52px;
}

.m-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 14px 36px;
}

.m-empty {
  align-items: center;
  padding-top: 48px;
  text-align: center;
}

.summary-card {
  padding: 12px 14px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.s-line1 {
  font-size: 14px;
}

.s-line2 {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--lm-ink-2);
}

.nodes-big {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.big-node {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 74px;
  padding: 12px;
  font: inherit;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.big-node:disabled {
  cursor: default;
}

.big-node.st-done {
  color: var(--lm-done-text);
  background: var(--lm-done-bg);
  border-color: var(--lm-done-line);
}

.big-node.st-running {
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.big-node.st-warn {
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border-color: var(--lm-pending-line);
}

.big-node.st-late {
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

.n-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}

.state-lbl {
  font-size: 11.5px;
}

.n-sub {
  margin-top: 6px;
  font-size: 12px;
}

.open-issues {
  padding: 12px;
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
  border-radius: var(--lm-radius-panel);
}

.open-issues h3 {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--lm-danger-text);
}

.iss-item {
  font-size: 12.5px;
  color: var(--lm-ink);
}

.iss-item + .iss-item {
  margin-top: 4px;
}

.bottom-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 6px;
}

.full-btn,
.act-btn {
  min-height: 46px;
  padding: 0 16px;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  color: var(--lm-ink);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}

.full-btn.danger-outline {
  color: var(--lm-danger-text);
  border-color: var(--lm-danger-line);
}

.full-btn.primary,
.act-btn.primary {
  color: var(--lm-on-accent);
  background: var(--lm-accent);
  border-color: var(--lm-accent);
}

.act-btn:disabled,
.full-btn:disabled {
  color: var(--lm-disabled);
  cursor: not-allowed;
  background: var(--lm-subtle);
  border-color: var(--lm-line);
}

.sub-hint {
  margin: 0;
  font-size: 12.5px;
}

.warn-text {
  color: var(--lm-pending-text);
}

.photo-hint {
  font-size: 12px;
  font-style: normal;
  color: var(--lm-danger-text);
}

.m-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.m-field :deep(.el-input-number),
.m-field :deep(.el-select) {
  width: 100%;
}

.native-date {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  font: inherit;
  font-size: 14px;
  color: var(--lm-ink);
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}

.m-check {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  font-size: 13.5px;
}

.dual-btns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 8px;
}
</style>
