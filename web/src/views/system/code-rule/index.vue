<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { codeRuleApi } from '@/api/system'
import type { CodeRule, CodeSegment, CodeSegmentType, CodeTarget } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

/**
 * 管理后台 › 编码规则（RFC 018 §4）：款号、物料编码、供应商编码各一个页签。每个页签一条通用规则（不能删），
 * 款号可以按大类、物料编码可以按物料类型再加规则；每条规则是几段拼起来，流水有且只有一段。
 * 只管以后新编的，已有的编码不变。保存时的检查以后端为准，这里的例子只是让人看个样子
 */

/** 每种编码：页签名、按什么分（字典）、例子里流水以外的段取什么值 */
const TARGETS: { value: CodeTarget; label: string; scopeDict: string | null; scopeLabel: string }[] = [
  { value: 'STYLE', label: '款号', scopeDict: 'style_class', scopeLabel: '大类' },
  { value: 'MATERIAL', label: '物料编码', scopeDict: 'material_type', scopeLabel: '物料类型' },
  { value: 'SUPPLIER', label: '供应商编码', scopeDict: null, scopeLabel: '' },
]

/** 段的种类，和后端 CodeSegment.Type 一样；sample 是例子里放的值 */
const SEGMENTS: Record<CodeSegmentType, { label: string; targets: CodeTarget[]; hint: string; sample: string }> = {
  TEXT: { label: '固定文字', targets: ['STYLE', 'MATERIAL', 'SUPPLIER'], hint: '1-8 位大写字母、数字、横杠', sample: '' },
  STYLE_CLASS: { label: '大类编码', targets: ['STYLE'], hint: '字典「款式大类」的值', sample: 'FS' },
  BRAND: { label: '品牌编码', targets: ['STYLE'], hint: '字典「品牌」上填的编码；没填的用品牌的值（要是字母数字）', sample: 'S' },
  CATEGORY: { label: '品类编码', targets: ['STYLE'], hint: '资料 › 品类 上填的编码；没填的品类编不了', sample: '03' },
  STYLE_YEAR: { label: '款式年份', targets: ['STYLE'], hint: '款式上"年份"的后两位', sample: '27' },
  DATE: { label: '日期', targets: ['STYLE', 'MATERIAL', 'SUPPLIER'], hint: '建档那天', sample: '' },
  DESIGNER: { label: '设计师编号', targets: ['STYLE'], hint: '设计师的两位编号；用过就锁住', sample: '07' },
  MAT_PARENT_CATEGORY: { label: '一级分类编码', targets: ['MATERIAL'], hint: '物料所在一级分类的 2 位码；一级没填的编不了', sample: 'LL' },
  MAT_CATEGORY: { label: '二级分类编码', targets: ['MATERIAL'], hint: '物料所在二级分类的 2 位码', sample: 'ML' },
  SEQ: { label: '流水', targets: ['STYLE', 'MATERIAL', 'SUPPLIER'], hint: '流水以外的部分一样的，接着往下编', sample: '' },
}
const DATE_FORMATS = ['yy', 'yyMM', 'yyMMdd', 'yyyy']
const SEQ_WIDTHS = [2, 3, 4, 5, 6]

const target = ref<CodeTarget>('STYLE')
const meta = computed(() => TARGETS.find((t) => t.value === target.value)!)
const scopeDict = useDict(() => meta.value.scopeDict ?? '')

const rules = ref<CodeRule[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rules.value = await codeRuleApi.list(target.value)
  } finally {
    loading.value = false
  }
}
watch(target, load, { immediate: true })

// ---------- 显示 ----------
function formatDate(format: string, date = new Date()) {
  const yyyy = String(date.getFullYear())
  const MM = String(date.getMonth() + 1).padStart(2, '0')
  const dd = String(date.getDate()).padStart(2, '0')
  return format.replace('yyyy', yyyy).replace('yy', yyyy.slice(2)).replace('MM', MM).replace('dd', dd)
}

/** 一段在卡片上怎么念："日期 yyMMdd""流水 2 位""固定文字 GYS" */
function segmentText(s: CodeSegment) {
  const label = SEGMENTS[s.type]?.label ?? s.type
  if (s.type === 'TEXT') return s.text ? `「${s.text}」` : label
  if (s.type === 'DATE') return `${label} ${s.format ?? ''}`
  if (s.type === 'SEQ') return `${label} ${s.width ?? ''} 位`
  return label
}

/** 例子：按规则拼一个第 1 号；按大类分的规则，大类编码取它的第一个大类 */
function example(segments: CodeSegment[], scopeValues: string[]) {
  return segments.map((s) => {
    if (s.type === 'TEXT') return (s.text ?? '').toUpperCase()
    if (s.type === 'DATE') return formatDate(s.format ?? 'yyMMdd')
    if (s.type === 'SEQ') return '1'.padStart(s.width ?? 2, '0')
    if (s.type === 'STYLE_CLASS' && scopeValues.length) return scopeValues[0]
    return SEGMENTS[s.type]?.sample ?? ''
  }).join('')
}

function scopeText(value: string) {
  return `${value} ${scopeDict.labelOf(value)}`
}

// ---------- 新增、编辑 ----------
const dialogVisible = ref(false)
const editing = ref<CodeRule | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', scopeValues: [] as string[], segments: [] as CodeSegment[] })
const guard = useUnsavedChanges(form, dialogVisible)
const formRules: FormRules = {
  name: [
    { required: true, message: '请填规则名', trigger: 'blur' },
    { max: 32, message: '最多 32 个字', trigger: 'blur' },
  ],
}
const isGeneral = computed(() => editing.value?.general ?? false)

/** 这种编码能用的段 */
const segmentTypes = computed(() =>
  (Object.keys(SEGMENTS) as CodeSegmentType[]).filter((type) => SEGMENTS[type].targets.includes(target.value)),
)

/** 别的规则已经管着的值：下拉里灰掉 */
const takenBy = computed(() => {
  const map = new Map<string, string>()
  for (const rule of rules.value) {
    if (rule.id === editing.value?.id) continue
    for (const value of rule.scopeValues) map.set(value, rule.name)
  }
  return map
})

function openCreate() {
  editing.value = null
  const first: CodeSegment = target.value === 'MATERIAL' ? { type: 'MAT_CATEGORY' } : { type: 'TEXT', text: '' }
  Object.assign(form, { name: '', scopeValues: [], segments: [first, { type: 'SEQ', width: 4 }] })
  dialogVisible.value = true
}

function openEdit(rule: CodeRule) {
  editing.value = rule
  Object.assign(form, {
    name: rule.name,
    scopeValues: [...rule.scopeValues],
    segments: rule.segments.map((s) => ({ ...s })),
  })
  dialogVisible.value = true
}

/** 换了段的种类：带上它要的参数的默认值 */
function onTypeChange(segment: CodeSegment) {
  segment.text = segment.type === 'TEXT' ? (segment.text ?? '') : undefined
  segment.format = segment.type === 'DATE' ? (segment.format ?? 'yyMMdd') : undefined
  segment.width = segment.type === 'SEQ' ? (segment.width ?? 4) : undefined
}

function addSegment() {
  form.segments.push({ type: 'TEXT', text: '' })
}

function move(index: number, delta: number) {
  const to = index + delta
  if (to < 0 || to >= form.segments.length) return
  const [segment] = form.segments.splice(index, 1)
  form.segments.splice(to, 0, segment)
}

function removeSegment(index: number) {
  form.segments.splice(index, 1)
}

/** 页面上先提醒的几条（后端还会再查一遍，包括最长多少位） */
const problems = computed(() => {
  const out: string[] = []
  const seqs = form.segments.filter((s) => s.type === 'SEQ').length
  if (seqs === 0) out.push('要有一段流水')
  if (seqs > 1) out.push('流水只能有一段')
  if (form.segments.some((s) => s.type === 'TEXT' && !/^[A-Za-z0-9-]{1,8}$/.test(s.text ?? ''))) {
    out.push('固定文字是 1-8 位字母、数字、横杠')
  }
  if (!isGeneral.value && meta.value.scopeDict && form.scopeValues.length === 0) out.push(`选这条规则管哪几个${meta.value.scopeLabel}`)
  return out
})

const preview = computed(() => example(form.segments, form.scopeValues))

const saving = ref(false)
async function save() {
  await formRef.value?.validate()
  if (problems.value.length) {
    ElMessage.warning(problems.value[0])
    return
  }
  saving.value = true
  try {
    const data = {
      target: target.value,
      name: form.name.trim(),
      scopeValues: isGeneral.value ? [] : form.scopeValues,
      segments: form.segments.map((s) => ({
        type: s.type,
        text: s.type === 'TEXT' ? (s.text ?? '').trim().toUpperCase() : undefined,
        format: s.type === 'DATE' ? s.format : undefined,
        width: s.type === 'SEQ' ? s.width : undefined,
      })),
    }
    if (editing.value) await codeRuleApi.update(editing.value.id, data)
    else await codeRuleApi.create(data)
    ElMessage.success('已保存，以后新编的按它编')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(rule: CodeRule) {
  await ElMessageBox.confirm(
    `删除规则「${rule.name}」以后，它管的${meta.value.scopeLabel}按通用规则编。已有的编码不变。`,
    '删除规则',
    { confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' },
  )
  await codeRuleApi.remove(rule.id)
  ElMessage.success('已删除')
  load()
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-if="meta.scopeDict" type="primary" icon="Plus" @click="openCreate">新增规则</el-button>
      </template>
    </PageHeader>

    <div class="page">
      <el-tabs v-model="target">
        <el-tab-pane v-for="t in TARGETS" :key="t.value" :label="t.label" :name="t.value" />
      </el-tabs>

      <p class="lead dim">
        <template v-if="meta.scopeDict">
          没单独定规则的{{ meta.scopeLabel }}按通用规则编。规则只管以后新编的，已有的编码不变。
        </template>
        <template v-else>供应商编码只有一条通用规则。规则只管以后新编的，已有的编码不变。</template>
      </p>

      <div v-loading="loading" class="cards">
        <section v-for="rule in rules" :key="rule.id" class="card">
          <header class="card-head">
            <h3>{{ rule.name }}</h3>
            <span v-if="rule.general" class="dim small">没单独定规则的都按它</span>
            <span class="spacer" />
            <el-button link icon="Edit" @click="openEdit(rule)">编辑</el-button>
            <el-button v-if="!rule.general" link type="danger" icon="Delete" @click="remove(rule)">删除</el-button>
          </header>
          <div v-if="rule.scopeValues.length" class="tags">
            <el-tag v-for="v in rule.scopeValues" :key="v" size="small" type="info" disable-transitions>{{ scopeText(v) }}</el-tag>
          </div>
          <div class="segments">
            <template v-for="(s, i) in rule.segments" :key="i">
              <span v-if="i > 0" class="plus" aria-hidden="true">+</span>
              <span class="chip" :class="{ seq: s.type === 'SEQ' }">{{ segmentText(s) }}</span>
            </template>
          </div>
          <p class="example">例：<span class="code">{{ example(rule.segments, rule.scopeValues) }}</span></p>
        </section>
        <p v-if="!loading && rules.length === 0" class="dim">还没有规则：通用规则被删了，请联系我们恢复。</p>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? `编辑规则：${editing.name}` : `新增${meta.label}规则`"
      width="min(640px, 94vw)"
      :before-close="guard.beforeClose"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="规则名" prop="name">
            <el-input v-model="form.name" maxlength="32" :placeholder="meta.scopeDict ? `如 鞋子` : ''" />
          </el-form-item>
          <el-form-item v-if="meta.scopeDict && !isGeneral" :label="`管哪几个${meta.scopeLabel}`">
            <el-select v-model="form.scopeValues" multiple filterable :placeholder="`选${meta.scopeLabel}`">
              <el-option
                v-for="o in scopeDict.enabled.value"
                :key="o.value"
                :label="`${o.value} ${o.label}`"
                :value="o.value"
                :disabled="takenBy.has(o.value)"
              >
                <span>{{ o.value }} {{ o.label }}</span>
                <span v-if="takenBy.has(o.value)" class="taken">在「{{ takenBy.get(o.value) }}」里</span>
              </el-option>
            </el-select>
          </el-form-item>
        </div>

        <p class="step-title">按顺序拼起来</p>
        <ol class="seg-list">
          <li v-for="(s, i) in form.segments" :key="i" class="seg-row">
            <span class="seg-no num">{{ i + 1 }}</span>
            <el-select v-model="s.type" class="seg-type" @change="onTypeChange(s)">
              <el-option v-for="t in segmentTypes" :key="t" :label="SEGMENTS[t].label" :value="t" />
            </el-select>
            <el-input v-if="s.type === 'TEXT'" v-model="s.text" class="seg-param" maxlength="8" placeholder="如 GYS" />
            <el-select v-else-if="s.type === 'DATE'" v-model="s.format" class="seg-param">
              <el-option v-for="f in DATE_FORMATS" :key="f" :label="`${f}（${formatDate(f)}）`" :value="f" />
            </el-select>
            <el-select v-else-if="s.type === 'SEQ'" v-model="s.width" class="seg-param">
              <el-option v-for="w in SEQ_WIDTHS" :key="w" :label="`${w} 位（最多 ${'9'.repeat(w)}）`" :value="w" />
            </el-select>
            <span v-else class="seg-param dim small">{{ SEGMENTS[s.type].hint }}</span>
            <span class="seg-ops">
              <el-button link icon="ArrowUp" :disabled="i === 0" title="上移" aria-label="上移" @click="move(i, -1)" />
              <el-button link icon="ArrowDown" :disabled="i === form.segments.length - 1" title="下移" aria-label="下移" @click="move(i, 1)" />
              <el-button link icon="Delete" :disabled="form.segments.length <= 1" title="删除这一段" aria-label="删除这一段" @click="removeSegment(i)" />
            </span>
          </li>
        </ol>
        <el-button link icon="Plus" :disabled="form.segments.length >= 12" @click="addSegment">加一段</el-button>

        <div class="preview">
          <span class="dim">例子</span>
          <span class="code big">{{ preview || '—' }}</span>
        </div>
        <ul v-if="problems.length" class="problems">
          <li v-for="p in problems" :key="p">{{ p }}</li>
        </ul>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lead {
  margin: 0 0 14px;
  font-size: 13px;
}

.cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 860px;
}

.card {
  padding: 14px 16px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-head h3 {
  font-size: 14px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.small {
  font-size: 12px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.segments {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}

.chip {
  padding: 2px 8px;
  font-size: 12.5px;
  background: var(--lm-sunken);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.chip.seq {
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.plus {
  font-size: 12px;
  color: var(--lm-faint);
}

.example {
  margin-top: 10px;
  font-size: 13px;
  color: var(--lm-muted);
}

.example .code {
  color: var(--lm-ink);
}

.step-title {
  margin: 4px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.seg-list {
  padding: 0;
  margin: 0 0 6px;
  list-style: none;
}

.seg-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.seg-no {
  width: 18px;
  font-size: 12px;
  color: var(--lm-muted);
  text-align: right;
}

.seg-type {
  flex: none;
  width: 140px;
}

.seg-param {
  flex: 1;
  min-width: 0;
}

.seg-ops {
  display: inline-flex;
  flex: none;
}

.seg-ops :deep(.el-button + .el-button) {
  margin-left: 2px;
}

.taken {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--lm-muted);
}

.preview {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 10px 12px;
  margin-top: 12px;
  background: var(--lm-sunken);
  border-radius: var(--lm-radius-sm);
}

.big {
  font-size: 16px;
  letter-spacing: 0.02em;
}

.problems {
  padding-left: 18px;
  margin: 8px 0 0;
  font-size: 12.5px;
  color: var(--lm-pending-text);
}
</style>
