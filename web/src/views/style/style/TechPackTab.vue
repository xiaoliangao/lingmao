<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import ThumbImage from '@/components/ThumbImage.vue'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { techPackApi } from '@/api/techpack'
import BlankState from '@/components/BlankState.vue'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import DocLogList from '@/components/DocLogList.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import StatusTag from '@/components/StatusTag.vue'
import { reloadOnConflict, showSilentError } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { MATERIAL_SOURCE_LABEL, type MaterialPickRow } from '@/types/basic'
import type { FileVO } from '@/types/system'
import type { StylePickRow } from '@/types/style'
import {
  BOM_SKU_MODE,
  COST_MODE,
  CRAFT_GROUPS,
  CRAFT_SECTION_LABEL,
  PACK_STATUS,
  TECH_PACK_LOG_ACTIONS,
  packName,
  type BomItem,
  type BomSku,
  type CostItemForm,
  type CostMode,
  type CostRow,
  type CraftSection,
  type CraftSectionForm,
  type ProcessForm,
  type SpecPointForm,
  type TechPackAction,
  type TechPackBrief,
  type TechPackCost,
  type TechPackDetail,
  type TechPackOverview,
  type TechPackSaveForm,
} from '@/types/techpack'
import { formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove, orNull } from '@/views/basic/common'
import CraftStandardDrawer from '@/views/techpack/standard/CraftStandardDrawer.vue'
import BomRowDialog, { type BomDraftRow } from './BomRowDialog.vue'

/**
 * 款式详情的"资料包"页签（docs/api/01 §1.1、§4，原型 TechPack）：版本条、版本信息、四个子页签（尺寸指示、物料清单、工艺指示、核价）+ 操作记录。
 *
 *   <TechPackTab ref="techPack" :style-id="id" :style-code="s.code" :style-name="s.name" :overview="tpOverview" @changed="loadOverview" />
 *   techPack.value?.open(packId, 'cost')      别处要切到某一版的某个子页签（基本信息里点"资料包核价"）
 *
 * - 版本：地址上的 techPackId 只在第一次进来、或者地址变了时用；之后按用户自己选的，概况刷新（保存、生效后）不跳回地址那一版。
 *   新建、修订、转生产以后切到新的那一版；删了草稿回到 defaultPackId。overview 由款式详情取，这里改了东西发 changed 让它重取
 * - 四个子页签共用一份表单、一个"保存"；只有 actions 里有 edit 的草稿能改。没保存的修改：换版本先问，离开由 useUnsavedChanges 管
 * - 参考单价、单件成本、工序工价、核价和含价 PDF 都按 techpack:cost:query 控制；无权保存省略财务字段
 * - 核价按上次保存的算（01 §6），改了没保存时提示一句
 * - 工艺标准（公司通用的默认文字、测量方法、附页文件）不单开一页，从这里拉出抽屉 CraftStandardDrawer：版本条上的"工艺标准"、
 *   还没有资料包时的空状态、导出 PDF 对话框"附工艺标准"旁边的"看附页文件"。要 techpack:standard:list
 */
const props = defineProps<{
  styleId: string
  styleCode: string
  styleName: string
  styleImage?: FileVO | null
  overview: TechPackOverview | null
}>()

const emit = defineEmits<{
  changed: []
}>()

const route = useRoute()
const user = useUserStore()

const bomParts = useDict('bom_part')
const units = useDict('unit')
const procCats = useDict('process_category')

type SubTab = 'spec' | 'bom' | 'craft' | 'cost' | 'logs'
const SUB_TABS: readonly SubTab[] = ['spec', 'bom', 'craft', 'cost', 'logs']

const canSeeCost = computed(() => user.hasPerm('techpack:cost:query'))
const canSeeStd = computed(() => user.hasPerm('techpack:standard:list'))
const craftStd = ref<InstanceType<typeof CraftStandardDrawer>>()
const activePackId = ref<string | null>(null)
const subTab = ref<SubTab>('bom')
const bomView = ref<'bom' | 'color'>('bom')
const viewColorId = ref<string | undefined>(undefined)
const costColorId = ref<string | undefined>(undefined)

const loading = ref(false)
const saving = ref(false)
const detail = ref<TechPackDetail | null>(null)
const costData = ref<TechPackCost | null>(null)
const costState = ref<'idle' | 'loading' | 'ready' | 'error' | 'forbidden'>('idle')
const costDraftPackId = ref<string | null>(null)
const loadError = ref<string | null>(null)
const inspectedBomId = ref<string | null>(null)
const inspectedBom = computed(() => draft.bomItems.find((row) => row.id === inspectedBomId.value) ?? null)
const inspectedSkus = computed(() => (inspectedBom.value?.skus ?? []).filter((sku) => bomView.value !== 'color' || sku.colorId == null || sku.colorId === viewColorId.value))

/** 四个子页签共用的表单（草稿能改的部分） */
const draft = reactive({
  versionNote: '',
  utilPct: null as number | null,
  packingMode: null as string | null,
  specPoints: [] as SpecPointForm[],
  bomItems: [] as BomItem[],
  craftSections: [] as CraftSectionForm[],
  processes: [] as ProcessForm[],
  costMode: 'OUTSOURCE' as CostMode,
  buyPrice: null as number | null | undefined,
  costItems: [] as CostItemForm[],
})

const editable = computed(() => detail.value?.actions.includes('edit') ?? false)
const guard = useUnsavedChanges(() => (editable.value ? draft : null), undefined, { ready: () => !loading.value && (!canSeeCost.value || costState.value !== 'loading') })
const can = (act: TechPackAction) => detail.value?.actions.includes(act) ?? false

const ALL_SECTIONS: CraftSection[] = ['CUTTING', 'SEWING', 'PART', 'QUALITY', 'BUTTON', 'IRONING', 'LABEL', 'PACKING', 'NOTE']

/** 尺寸指示每行换部位前的部位（换部位时判断测量方法是不是原部位的默认写法） */
const prevPoint = new WeakMap<SpecPointForm, string>()

function hydrateDraft(d: TechPackDetail) {
  draft.versionNote = d.versionNote ?? ''
  draft.utilPct = d.utilPct
  draft.packingMode = d.packingMode
  draft.specPoints = d.specPoints.map(({ id: _id, seq: _seq, ...rest }) => ({ ...rest, values: { ...rest.values } }))
  draft.specPoints.forEach((p) => prevPoint.set(p, p.point))
  draft.bomItems = structuredClone(d.bomItems)
  const secMap = new Map(d.craftSections.map((s) => [s.section, s]))
  draft.craftSections = ALL_SECTIONS.map((sec) => {
    const hit = secMap.get(sec)
    return {
      section: sec,
      content: hit?.content ?? '',
      images: hit?.images.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size })) ?? [],
    }
  })
  draft.processes = d.processes.map(({ seq: _seq, ...rest }) => ({ ...rest, images: [...rest.images] }))
  draft.costMode = 'OUTSOURCE'
  draft.buyPrice = null
  draft.costItems = []
  guard.markSaved()
}

/** 主表和核价独立加载；核价失败不构造核价内容，未授权时请求层省略 cost。 */
let costSeq = 0
let loadSeq = 0
const costRequest = useLatestRequest()
async function loadCost(packId: string, colorId?: string) {
  const read = costRequest.begin()
  const current = ++costSeq
  if (!canSeeCost.value) { costState.value = 'forbidden'; costData.value = null; return }
  costState.value = 'loading'
  try {
    const result = await read.run(() => techPackApi.cost(packId, colorId))
    if (!read.current()) return
    if (current !== costSeq || activePackId.value !== packId || detail.value?.id !== packId) return
    costData.value = result
    costColorId.value = result.colorId ?? colorId
    if (costDraftPackId.value !== packId) {
      const wasDirty = guard.dirty.value
      draft.costMode = result.costMode
      draft.buyPrice = result.buyPrice
      draft.costItems = result.otherItems.map((it) => ({ name: it.name, amount: it.amount, remark: it.remark }))
      costDraftPackId.value = packId
      if (!wasDirty) guard.markSaved()
    }
    costState.value = 'ready'
  } catch {
    if (!read.current()) return
    if (current !== costSeq || activePackId.value !== packId) return
    costState.value = 'error'
    costData.value = null
  }
}

const packRequest = useLatestRequest()
let loadedPackRead: ReturnType<typeof packRequest.begin> | undefined
async function loadPack(packId: string) {
  const read = packRequest.begin()
  costRequest.cancel()
  const current = ++loadSeq
  ++costSeq
  costDraftPackId.value = null
  costData.value = null
  costState.value = 'idle'
  loadError.value = null
  inspectedBomId.value = null
  loading.value = true
  detail.value = null
  try {
    const d = await read.run(() => techPackApi.get(packId))
    if (!read.current()) return
    if (current !== loadSeq || activePackId.value !== packId) return
    detail.value = d
    loadedPackRead = read
    viewColorId.value = d.colors[0]?.id
    hydrateDraft(d)
    void loadCost(packId, costColorId.value && d.colors.some((c) => c.id === costColorId.value) ? costColorId.value : d.colors[0]?.id)
  } catch (e) {
    if (!read.current()) return
    if (current === loadSeq) loadError.value = e instanceof Error ? e.message : '资料包加载失败'
  } finally {
    if (read.current() && current === loadSeq) loading.value = false
  }
}

// ---------- 选哪一版：地址参数只在第一次、地址变了时用（01 §1.1） ----------
const stylePath = `/styles/${props.styleId}`
const queryId = (value: unknown) => (typeof value === 'string' && /^\d+$/.test(value) ? value : null)
/** 地址上要的版本，等概况到了再切，切过就清掉 */
let wantedPackId: string | null = queryId(route.query.techPackId)
const initialSub = route.query.sub
if (typeof initialSub === 'string' && (SUB_TABS as readonly string[]).includes(initialSub)) subTab.value = initialSub as SubTab

function pickPack() {
  const ov = props.overview
  if (!ov) return
  const has = (id: string | null): id is string => id != null && ov.packs.some((p) => p.id === id)
  let target: string | null
  if (has(wantedPackId)) target = wantedPackId
  else target = has(activePackId.value) ? activePackId.value : ov.defaultPackId
  wantedPackId = null
  if (target == null) {
    activePackId.value = null
    detail.value = null
    guard.markSaved()
    return
  }
  if (target !== activePackId.value || !detail.value) void switchPack(target)
}

watch(() => props.overview, pickPack, { immediate: true })

// 页签留着、别处带着 ?techPackId=&sub= 又点进来（1005 清单里点了另一版）
watch(
  () => [route.query.techPackId, route.query.sub] as const,
  ([pid, sub]) => {
    if (route.path !== stylePath) return
    if (typeof sub === 'string' && (SUB_TABS as readonly string[]).includes(sub)) subTab.value = sub as SubTab
    const want = queryId(pid)
    if (want != null && want !== activePackId.value) {
      wantedPackId = want
      pickPack()
    }
  },
)

/** 切到某一版的某个子页签（款式详情里点"资料包核价"） */
async function open(packId: string, sub?: SubTab) {
  if (sub) subTab.value = sub
  if (packId !== activePackId.value) await switchPack(packId)
}
defineExpose({ open })

const activeBrief = computed<TechPackBrief | undefined>(() => props.overview?.packs.find((p) => p.id === activePackId.value))

/** 换版本：有没保存的修改先问 */
async function switchPack(id: string) {
  if (saving.value) return
  if (id === activePackId.value && detail.value) return
  if (guard.dirty.value && detail.value) {
    try {
      await ElMessageBox.confirm(`${packName(detail.value)} 有没保存的修改，不要了吗？`, '换版本', {
        confirmButtonText: '不要了',
        cancelButtonText: '继续编辑',
        confirmButtonClass: 'el-button--danger',
      })
    } catch {
      return
    }
  }
  guard.discardUploads()
  activePackId.value = id
  await loadPack(id)
}

// ---------- 新建、修订、保存、生效、删除 ----------

/** 一个版本都还没有、又能建：出"建生产 BOM"（有了版本走修订） */
const canCreate = computed(() => !!props.overview?.creatable && !props.overview.packs.length)

/** 新建、修订出来的新草稿：切过去，再让款式详情重取概况（概况到了 pickPack 看到它已经是当前的，不再切） */
async function gotoNew(newId: string, message: string) {
  ElMessage.success(message)
  guard.markSaved()
  activePackId.value = newId
  await loadPack(newId)
  emit('changed')
}

async function createPack() {
  const newId = await techPackApi.create(props.styleId)
  await gotoNew(newId, '建了生产 BOM 草稿，填好点「生效」')
}

async function revisePack() {
  if (!detail.value) return
  const newId = await techPackApi.revise(detail.value.id)
  await gotoNew(newId, `修订出 ${packName({ version: detail.value.version + 1 })}，尺寸、物料、工艺、其他费用都带过来了，改完点「生效」`)
}

function buildSaveForm(): TechPackSaveForm {
  const d = detail.value!
  return {
    versionLock: d.versionLock,
    versionNote: orNull(draft.versionNote),
    utilPct: draft.utilPct,
    packingMode: draft.packingMode,
    specPoints: draft.specPoints.map((sp) => ({ ...sp, point: sp.point.trim(), method: orNull(sp.method), remark: orNull(sp.remark) })),
    bomItems: draft.bomItems.map((b) => ({
      id: /^[1-9]\d*$/.test(b.id) ? b.id : undefined,
      part: b.part,
      materialId: b.materialId,
      skuMode: b.skuMode,
      usageMode: b.usageMode,
      usageQty: b.usageMode === 'UNIFORM' ? b.usageQty : null,
      sizeUsages: b.usageMode === 'BY_SIZE' ? b.sizeUsages : {},
      lossPct: b.lossPct,
      source: b.source,
      supplierId: b.supplierId,
      refPrice: b.refPrice,
      priceSourceId: b.priceSourceId,
      priceTaxIncluded: b.priceTaxIncluded,
      priceFreightIncluded: b.priceFreightIncluded,
      remark: orNull(b.remark),
      skus: b.skus.map((sk) => ({ colorId: sk.colorId, sizeCode: sk.sizeCode, materialSkuId: sk.materialSkuId, refPrice: sk.refPrice })),
    })),
    craftSections: draft.craftSections.map((cs) => ({ section: cs.section, content: orNull(cs.content), images: cs.images })),
    processes: draft.processes.map((p) => ({ ...p, name: p.name.trim(), requirement: orNull(p.requirement), remark: orNull(p.remark) })),
    // 未载入核价时不构造它；无权限时请求层完全省略 cost，服务器保留既有值。
    cost: canSeeCost.value && costDraftPackId.value === d.id
      ? {
          costMode: draft.costMode,
          buyPrice: draft.buyPrice,
          items: draft.costItems.filter((i) => i.name.trim()).map((i) => ({ name: i.name.trim(), amount: i.amount, remark: orNull(i.remark) })),
        }
      : null,
  }
}

/** 保存草稿。返回新的 versionLock（生效接着用），没存上返回 null */
async function saveDraft(toast = true): Promise<number | null> {
  const d = detail.value
  if (!d || saving.value) return null
  saving.value = true
  try {
    if (!(await guard.waitForUploads())) return null
    const lock = await techPackApi.save(d.id, buildSaveForm())
    guard.markSaved()
    if (toast) ElMessage.success(`${packName(d)} 已保存`)
    // 重新取一次：单件成本、核价以后端算的为准
    await loadPack(d.id)
    emit('changed')
    return lock
  } catch (e) {
    // 别人改过、已经生效了：重新取一遍（消息 request.ts 弹过了）
    if (reloadOnConflict(e, () => loadPack(d.id))) return null
    if (!(e instanceof ApiError)) throw e
    return null
  } finally {
    saving.value = false
  }
}

/** 生效：有没保存的修改先存。检查没过的一次列全（1000 + PublishProblem[]，弹清单） */
async function publishPack() {
  const d = detail.value
  if (!d) return
  let lock: number | null = d.versionLock
  if (guard.dirty.value) {
    lock = await saveDraft(false)
    if (lock == null) return
  }
  saving.value = true
  try {
    await techPackApi.publish(d.id, lock, { silent: true })
    ElMessage.success(`${packName(d)} 已生效`)
    await loadPack(d.id)
    emit('changed')
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    reloadOnConflict(e, () => loadPack(d.id))
  } finally {
    saving.value = false
  }
}

async function removeDraft() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${packName(d)} 草稿`, '这一版改的内容都不要了，删除后找不回来；已生效的版本不受影响。')
  await techPackApi.remove(d.id)
  ElMessage.success(`已删除 ${packName(d)} 草稿`)
  guard.markSaved()
  // 概况重取后选 defaultPackId
  activePackId.value = null
  detail.value = null
  emit('changed')
}

/** 上移、下移一格（代替拖动排序） */
function moveItem<T>(arr: T[], idx: number, step: -1 | 1) {
  const target = idx + step
  if (target < 0 || target >= arr.length) return
  ;[arr[idx], arr[target]] = [arr[target]!, arr[idx]!]
}

// ---------- 尺寸指示 ----------
function addSpecPoint() {
  // 公差默认 0.5（01 §4.3）
  const row: SpecPointForm = { point: '', method: '', toleranceCm: 0.5, gradeCm: null, remark: '', values: {} }
  draft.specPoints.push(row)
  prevPoint.set(row, '')
}

/** 换部位：测量方法空着、或者还是原部位的默认写法，就换成新部位的默认测量方法（原型 setPt） */
function onPointPicked(row: SpecPointForm) {
  const options = detail.value?.pointOptions ?? []
  const oldDefault = options.find((o) => o.label === prevPoint.get(row))?.method ?? null
  const newDefault = options.find((o) => o.label === row.point)?.method ?? null
  if (newDefault && (!row.method || row.method === oldDefault)) row.method = newDefault
  prevPoint.set(row, row.point)
}

/** 按档差生成：填了基码和档差的行，其他码 = 基码 ± 离基码第几档 × 档差（01 §4.3） */
async function generateByGrade() {
  const d = detail.value
  if (!d) return
  const baseIdx = d.sizes.indexOf(d.baseSize)
  if (baseIdx < 0) return
  const ready = draft.specPoints.filter((r) => r.values[d.baseSize] != null && r.gradeCm != null)
  const skipped = draft.specPoints.filter((r) => r.gradeCm == null).length
  if (!ready.length) {
    ElMessage.warning('先填基码尺寸和档差')
    return
  }
  const overwrite = ready.some((r) => d.sizes.some((sz, i) => i !== baseIdx && r.values[sz] != null))
  if (overwrite) {
    await ElMessageBox.confirm('已经填了数的格子会被覆盖。', '按档差生成', { confirmButtonText: '生成', cancelButtonText: '取消' })
  }
  for (const row of ready) {
    const base = row.values[d.baseSize]!
    d.sizes.forEach((sz, idx) => {
      if (idx !== baseIdx) row.values[sz] = Math.round((base + (idx - baseIdx) * row.gradeCm!) * 10) / 10
    })
  }
  ElMessage.success(`按档差算了 ${ready.length} 行${skipped ? `；${skipped} 行没填档差，没动` : ''}，算完每格还能改`)
}

// ---------- 物料清单 ----------
const bomDialogVisible = ref(false)
const editingBomIdx = ref<number | null>(null)
const editingBomRow = computed(() => (editingBomIdx.value != null ? (draft.bomItems[editingBomIdx.value] ?? null) : null))
const skuPicking = ref(false)
const bomSkuIds = computed(() => draft.bomItems.flatMap((b) => b.skus.map((s) => s.materialSkuId)))
/** 新加的行还没有 id：给一个负数当 row-key，保存时不发 id */
let tempId = 0

function openAddBom() {
  editingBomIdx.value = null
  bomDialogVisible.value = true
}

function openEditBom(idx: number) {
  editingBomIdx.value = idx
  bomDialogVisible.value = true
}

function colorOf(colorId: string | null) {
  return detail.value?.colors.find((c) => c.id === colorId)
}

/** 对话框改好的一行换进表格 */
function onSaveBomRow(saved: BomDraftRow) {
  const old = editingBomIdx.value != null ? draft.bomItems[editingBomIdx.value] : undefined
  const row: BomItem = {
    id: old?.materialId === saved.materialId ? old.id : String(--tempId),
    seq: 0,
    part: saved.part,
    materialId: saved.materialId,
    materialCode: saved.materialCode,
    materialName: saved.materialName,
    materialType: saved.materialType,
    materialStatus: old?.materialId === saved.materialId ? old.materialStatus : 1,
    skuMode: saved.skuMode,
    usageMode: saved.usageMode,
    usageQty: saved.usageQty,
    sizeUsages: saved.sizeUsages,
    unit: saved.unit,
    lossPct: saved.lossPct,
    source: saved.source,
    supplierId: saved.supplierId,
    supplierName: saved.supplierName,
    refPrice: saved.refPrice,
    priceSourceId: saved.priceSourceId,
    priceTaxIncluded: saved.priceTaxIncluded,
    priceFreightIncluded: saved.priceFreightIncluded,
    remark: saved.remark,
    skus: saved.skus.map((sk) => {
      const color = colorOf(sk.colorId)
      const info = saved.skuInfo[sk.materialSkuId]
      return {
        colorId: sk.colorId,
        colorCode: color?.code ?? null,
        colorName: color?.name ?? null,
        colorHex: color?.hex ?? null,
        sizeCode: sk.sizeCode,
        materialSkuId: sk.materialSkuId,
        skuCode: info?.code ?? `#${sk.materialSkuId}`,
        skuColorName: info?.colorName ?? null,
        skuSpec: info?.spec ?? null,
        skuStatus: info?.status === 0 ? 0 : 1,
        refPrice: sk.refPrice,
        image: null,
      }
    }),
    unitCost: null,
    image: old?.image ?? null,
  }
  if (editingBomIdx.value != null) draft.bomItems.splice(editingBomIdx.value, 1, row)
  else draft.bomItems.push(row)
}

/**
 * 从物料库一次勾几条加进来（01 §4.4）：一种物料一行。只勾了一个 SKU 的配料方式"固定"；勾了几个的"随款色"，
 * 物料 SKU 的颜色和款色一样就配上，配不上的空着。加完一起问一次默认值；部位要用户自己选
 */
async function onPickSkus(rows: MaterialPickRow[] | StylePickRow[]) {
  const d = detail.value
  const read = loadedPackRead
  if (!d || !read?.current()) return
  const groups = new Map<string, MaterialPickRow[]>()
  for (const r of rows as MaterialPickRow[]) groups.set(r.materialId, [...(groups.get(r.materialId) ?? []), r])
  const added: BomItem[] = []
  for (const picked of groups.values()) {
    const first = picked[0]!
    const fixed = picked.length === 1
    const toSku = (p: MaterialPickRow, colorId: string | null): BomSku => {
      const color = colorOf(colorId)
      return {
        colorId,
        colorCode: color?.code ?? null,
        colorName: color?.name ?? null,
        colorHex: color?.hex ?? null,
        sizeCode: null,
        materialSkuId: p.skuId,
        skuCode: p.skuCode,
        skuColorName: p.colorName,
        skuSpec: p.spec,
        skuStatus: 1,
        refPrice: null,
        image: p.image,
      }
    }
    const skus = fixed
      ? [toSku(first, null)]
      : d.colors.flatMap((c) => {
          const hit = picked.find((p) => p.colorId === c.id)
          return hit ? [toSku(hit, c.id)] : []
        })
    added.push({
      id: String(--tempId),
      seq: 0,
      part: '',
      materialId: first.materialId,
      materialCode: first.materialCode,
      materialName: first.materialName,
      materialType: first.materialType,
      materialStatus: 1,
      skuMode: fixed ? 'FIXED' : 'BY_COLOR',
      usageMode: 'UNIFORM',
      usageQty: null,
      sizeUsages: {},
      unit: first.stockUnit,
      lossPct: 0,
      source: 'PURCHASE',
      supplierId: null,
      supplierName: null,
      refPrice: first.price,
      priceSourceId: null,
      priceTaxIncluded: true,
      priceFreightIncluded: true,
      remark: null,
      skus,
      unitCost: null,
      image: first.image,
    })
  }
  draft.bomItems.push(...added)
  try {
    const defaults = await read.run(() => techPackApi.bomDefaults(
      d.id,
      added.map((b) => ({ materialId: b.materialId, supplierId: null, skuIds: b.skus.map((s) => s.materialSkuId) })),
    ))
    if (!read.current()) return
    // 加行：整套带默认值（01 §3.9）
    defaults.forEach((def, i) => {
      const row = draft.bomItems.find((b) => b.id === added[i]?.id)
      if (!row) return
      const prices = new Map((def.skuPrices ?? []).map((p) => [p.materialSkuId, p.refPrice]))
      Object.assign(row, {
        unit: def.unit,
        supplierId: def.supplierId,
        supplierName: def.supplierName,
        lossPct: def.lossPct,
        source: def.source,
        refPrice: def.refPrice,
        priceSourceId: def.priceSourceId,
        priceTaxIncluded: def.priceTaxIncluded,
        priceFreightIncluded: def.priceFreightIncluded,
      })
      row.skus.forEach((s) => (s.refPrice = prices.get(s.materialSkuId) ?? null))
    })
  } catch {
    if (!read.current()) return
    // 取不到默认值也留着这几行，单价、供应商可以自己填
  }
  ElMessage.success(`加了 ${added.length} 行，选好部位、填上用量`)
}

/** 用量一格：统一的一个数，分码的"2.1–2.5" */
function usageText(b: BomItem): string {
  if (b.usageMode === 'UNIFORM') return b.usageQty == null ? '—' : formatQty(b.usageQty)
  const values = Object.values(b.sizeUsages).filter((v) => v != null)
  if (!values.length) return '—'
  const min = Math.min(...values)
  const max = Math.max(...values)
  return min === max ? formatQty(min) : `${formatQty(min)}–${formatQty(max)}`
}

/** 配料摘要里要标红的：随款色少配了的颜色、停用的 SKU（生效时会被拦，先标出来） */
function bomProblems(b: BomItem): string[] {
  const d = detail.value
  if (!d) return []
  const out: string[] = []
  if (b.materialStatus === 0) out.push('物料已停用')
  if (b.skuMode === 'BY_COLOR_SIZE') {
    const missing = d.colors.flatMap((color) => d.sizes.filter((size) => !b.skus.some((sku) => sku.colorId === color.id && sku.sizeCode === size)).map((size) => `${color.name}/${size}`))
    if (missing.length) out.push(`${missing.join('、')} 没配料`)
  } else if (b.skuMode === 'BY_COLOR') {
    const missing = d.colors.filter((c) => !b.skus.some((s) => s.colorId === c.id))
    if (missing.length) out.push(`${missing.map((c) => c.name).join('、')} 没配料`)
  } else if (b.skuMode === 'BY_SIZE') {
    const missing = d.sizes.filter((sz) => !b.skus.some((s) => s.sizeCode === sz))
    if (missing.length) out.push(`${missing.join('、')} 没配料`)
  }
  if (b.usageMode === 'BY_SIZE') {
    const missing = d.sizes.filter((size) => b.sizeUsages[size] == null)
    if (missing.length) out.push(`${missing.join('、')} 未填用量`)
  }
  if (b.skus.some((s) => s.skuStatus === 0)) out.push('有停用的 SKU')
  return out
}

/** 单件成本：草稿上按输入即时算（保存后以后端为准）。基码用量 ×（1 + 损耗）× 单价；随款色取第一个款色的 SKU 价，随尺码取基码的 */
function unitCostOf(b: BomItem): number | null {
  if (!editable.value) return b.unitCost ?? null
  const d = detail.value
  if (!d || b.source !== 'PURCHASE') return null
  const usage = b.usageMode === 'UNIFORM' ? b.usageQty : b.sizeUsages[d.baseSize]
  if (usage == null) return null
  const firstColor = d.colors[0]?.id ?? null
  const sku = b.skus.find(
    (s) => (s.colorId == null || s.colorId === firstColor) && (s.sizeCode == null || s.sizeCode === d.baseSize),
  )
  const price = sku?.refPrice ?? b.refPrice
  return price == null ? null : usage * (1 + b.lossPct / 100) * price
}

/** "按颜色"视图（01 §4.4）：这个颜色实际要用的每个物料 SKU 和各码用量，一个 SKU 一行（随尺码的按码拆开） */
interface ColorViewRow {
  key: string
  item: BomItem
  sku: BomSku | null
  usages: Record<string, number | null>
}
const colorViewRows = computed<ColorViewRow[]>(() => {
  const d = detail.value
  const colorId = viewColorId.value
  if (!d || colorId == null) return []
  const rows: ColorViewRow[] = []
  draft.bomItems.forEach((item, idx) => {
    const bySku = new Map<string, ColorViewRow>()
    for (const size of d.sizes) {
      const sku = item.skus.find((s) => (s.colorId == null || s.colorId === colorId) && (s.sizeCode == null || s.sizeCode === size))
      if (!sku) continue
      const row = bySku.get(sku.materialSkuId) ?? { key: `${idx}-${sku.materialSkuId}`, item, sku, usages: {} }
      row.usages[size] = item.usageMode === 'UNIFORM' ? item.usageQty : (item.sizeUsages[size] ?? null)
      bySku.set(sku.materialSkuId, row)
    }
    if (bySku.size) rows.push(...bySku.values())
    else rows.push({ key: `${idx}-none`, item, sku: null, usages: {} })
  })
  return rows
})

// ---------- 工艺指示、工序工价 ----------
function sectionFormOf(sec: CraftSection): CraftSectionForm {
  let hit = draft.craftSections.find((s) => s.section === sec)
  if (!hit) {
    hit = { section: sec, content: '', images: [] }
    draft.craftSections.push(hit)
  }
  return hit
}

/** 只读时空的段不出（01 §4.5） */
const shownSections = (sections: CraftSection[]) =>
  editable.value ? sections : sections.filter((s) => sectionFormOf(s).content?.trim() || sectionFormOf(s).images.length)

function addProcess() {
  draft.processes.push({ category: null, name: '', requirement: '', stdSeconds: null, price: null, remark: '', images: [] })
}

const totalProcessLabor = computed(() => draft.processes.reduce((sum, p) => sum + (Number(p.price) || 0), 0))
const totalProcessSeconds = computed(() => draft.processes.reduce((sum, p) => sum + (Number(p.stdSeconds) || 0), 0))

// ---------- 核价（按上次保存的算，01 §6） ----------
async function onCostColorChange(colorId: string | undefined) {
  if (detail.value) await loadCost(detail.value.id, colorId)
}

function addOtherCost() {
  draft.costItems.push({ name: '', amount: 0, remark: '' })
}

const costBlocks = computed(() =>
  costData.value
    ? [
        { title: '面料', rows: costData.value.fabricRows, sum: costData.value.summary.fabric },
        { title: '辅料', rows: costData.value.trimRows, sum: costData.value.summary.trim },
      ]
    : [],
)

/** 不计入的行写为什么（厂购含在加工价里、客供客户出） */
const notCountedText = (row: CostRow) => (row.source === 'FACTORY' ? '含在加工价里' : '客户出')

// ---------- 导出 PDF ----------
const pdfVisible = ref(false)
const pdfForm = reactive({ colorId: undefined as string | undefined, price: false, appendix: true })
const exportingPdf = ref(false)

function openPdf() {
  // 微信里下载不了文件（01 §4.7）
  if (/MicroMessenger/i.test(navigator.userAgent)) {
    ElMessage.warning('微信里下载不了文件：点右上角，在浏览器里打开再导出')
    return
  }
  Object.assign(pdfForm, { colorId: undefined, price: false, appendix: true })
  pdfVisible.value = true
}

async function doExportPdf() {
  const d = detail.value
  if (!d) return
  exportingPdf.value = true
  try {
    await techPackApi.pdf(
      d.id,
      { colorId: pdfForm.colorId, price: canSeeCost.value && pdfForm.price, appendix: pdfForm.appendix },
      `${props.styleCode}-${props.styleName}-工艺单-${packName(d)}.pdf`,
    )
    pdfVisible.value = false
  } finally {
    exportingPdf.value = false
  }
}

// ---------- 操作记录 ----------
const loadLogs = () => techPackApi.logs(activePackId.value!)
const LOG_STATUSES = Object.fromEntries(Object.entries(PACK_STATUS).map(([k, v]) => [k, v.label]))

/** 版本条上的字："V2 生效中""V3 草稿""V1 已作废" */
const PILL_STATUS: Record<TechPackBrief['status'], string> = { DRAFT: '草稿', EFFECTIVE: '生效中', VOIDED: '已作废' }
</script>

<template>
  <div v-loading="loading" class="tp-wrap">
    <BlankState v-if="overview && !overview.packs.length" title="还没有资料包：生产 BOM 生效后才能下订单">
      <el-button v-if="canCreate" type="primary" @click="createPack">建生产 BOM</el-button>
      <el-button v-if="canSeeStd" icon="Notebook" @click="craftStd?.open()">工艺标准</el-button>
    </BlankState>

    <template v-else-if="overview">
      <!-- 版本条 -->
      <div class="ver-bar">
        <div class="ver-pills" role="tablist">
          <button
            v-for="p in overview.packs"
            :key="p.id"
            :data-testid="`techpack-version-${p.id}`"
            :disabled="saving"
            type="button"
            role="tab"
            class="ver-pill"
            :class="{ active: p.id === activePackId, voided: p.status === 'VOIDED' }"
            :aria-selected="p.id === activePackId"
            @click="switchPack(p.id)"
          >
            {{ packName(p) }} <span class="dim">{{ PILL_STATUS[p.status] }}</span>
          </button>
        </div>
        <div class="ver-acts">
          <template v-if="detail">
            <el-button v-if="can('remove')" size="small" @click="removeDraft">删除草稿</el-button>
            <el-button size="small" icon="Printer" @click="openPdf">导出 PDF</el-button>
            <el-button v-if="canSeeStd" size="small" icon="Notebook" @click="craftStd?.open()">工艺标准</el-button>
            <el-button v-if="can('revise')" size="small" @click="revisePack">修订</el-button>
            <el-button v-if="editable" size="small" :loading="saving" :disabled="!guard.dirty.value" @click="saveDraft()">保存</el-button>
            <el-button v-if="can('publish')" size="small" type="primary" :loading="saving" @click="publishPack">生效</el-button>
          </template>
        </div>
      </div>

      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button v-if="activePackId" @click="loadPack(activePackId)">重试</el-button></el-alert>
      <template v-if="detail">
        <el-alert v-if="costState === 'error'" data-testid="cost-load-error" title="核价加载失败；其他页签仍可保存，已保存成本会保留" type="warning" :closable="false">
          <el-button size="small" @click="loadCost(detail.id, costColorId)">重试核价</el-button>
        </el-alert>
        <!-- 版本信息 -->
        <div class="pack-meta">
          <StatusTag :tone="PACK_STATUS[detail.status].tone">{{ PACK_STATUS[detail.status].label }}</StatusTag>
          <span v-if="detail.publishTime" class="dim">{{ detail.publishByName }} {{ formatDateTime(detail.publishTime) }} 生效</span>
          <span v-if="canSeeCost" class="num">参考加工价 ¥{{ formatMoney(detail.laborTotal) }}</span>
          <span v-if="canSeeCost && detail.unitCost != null" class="num">单件成本 <strong>¥{{ formatMoney(detail.unitCost) }}</strong></span>
          <label class="inline-field">
            <span class="dim">排版利用率 %</span>
            <el-input-number v-if="editable" v-model="draft.utilPct" size="small" :min="0" :max="100" :precision="1" :controls="false" />
            <span v-else class="num">{{ draft.utilPct == null ? '—' : formatQty(draft.utilPct) }}</span>
          </label>
          <el-input
            v-if="editable"
            v-model="draft.versionNote"
            :disabled="saving"
            size="small"
            maxlength="200"
            class="note-input"
            :placeholder="detail.version > 1 ? '本版说明（V2 起必填）：这版改了什么' : '本版说明（选填）'"
          />
          <span v-else-if="activeBrief?.versionNote" class="dim">说明：{{ activeBrief.versionNote }}</span>
        </div>

        <el-tabs v-model="subTab" :inert="saving || loading">
          <!-- 尺寸指示 -->
          <el-tab-pane :label="`尺寸指示（${draft.specPoints.length}）`" name="spec">
            <div v-if="editable" class="sub-toolbar">
              <el-button size="small" icon="Plus" @click="addSpecPoint">加一行</el-button>
              <el-button size="small" @click="generateByGrade">按档差生成</el-button>
              <span class="dim">单位 cm</span>
            </div>
            <el-table :data="draft.specPoints" empty-text="还没有尺寸指示（不是必填）">
              <el-table-column label="#" width="44" class-name="num">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column label="部位" min-width="130">
                <template #default="{ row }">
                  <el-select
                    v-if="editable"
                    v-model="row.point"
                    size="small"
                    filterable
                    allow-create
                    default-first-option
                    placeholder="选或填部位"
                    @change="onPointPicked(row as SpecPointForm)"
                  >
                    <el-option v-for="o in detail.pointOptions" :key="o.label" :label="o.label" :value="o.label" />
                  </el-select>
                  <span v-else>{{ row.point }}</span>
                </template>
              </el-table-column>
              <el-table-column label="测量方法" min-width="160">
                <template #default="{ row }">
                  <el-input v-if="editable" v-model="row.method" size="small" />
                  <span v-else>{{ row.method || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="公差 ±" width="78" class-name="num" align="right">
                <template #default="{ row }">
                  <el-input-number v-if="editable" v-model="row.toleranceCm" size="small" :min="0" :precision="1" :controls="false" />
                  <span v-else>{{ row.toleranceCm == null ? '—' : formatQty(row.toleranceCm) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="档差" width="78" class-name="num" align="right">
                <template #default="{ row }">
                  <el-input-number v-if="editable" v-model="row.gradeCm" size="small" :precision="1" :controls="false" />
                  <span v-else>{{ row.gradeCm == null ? '—' : formatQty(row.gradeCm) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                v-for="sz in detail.sizes"
                :key="sz"
                :label="sz === detail.baseSize ? `${sz}（基码）` : sz"
                :class-name="sz === detail.baseSize ? 'num base-col' : 'num'"
                width="84"
                align="right"
              >
                <template #default="{ row }">
                  <el-input-number v-if="editable" v-model="row.values[sz]" size="small" :precision="1" :controls="false" />
                  <span v-else>{{ row.values[sz] == null ? '—' : formatQty(row.values[sz]) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="120">
                <template #default="{ row }">
                  <el-input v-if="editable" v-model="row.remark" size="small" />
                  <span v-else>{{ row.remark || '' }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="editable" label="" width="96" align="right">
                <template #default="{ $index }">
                  <el-button link size="small" :disabled="$index === 0" aria-label="上移" @click="moveItem(draft.specPoints, $index, -1)"><el-icon><Top /></el-icon></el-button>
                  <el-button link size="small" :disabled="$index === draft.specPoints.length - 1" aria-label="下移" @click="moveItem(draft.specPoints, $index, 1)"><el-icon><Bottom /></el-icon></el-button>
                  <el-button link type="danger" size="small" @click="draft.specPoints.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 物料清单 -->
          <el-tab-pane :label="`物料清单（${draft.bomItems.length}）`" name="bom">
            <div class="sub-toolbar">
              <template v-if="editable">
                <el-button type="primary" size="small" icon="Plus" @click="skuPicking = true">从物料库选</el-button>
                <el-button size="small" @click="openAddBom">加一行</el-button>
              </template>
              <el-segmented v-model="bomView" size="small" :options="[{ label: '物料清单', value: 'bom' }, { label: '按颜色', value: 'color' }]" />
              <el-select v-if="bomView === 'color'" v-model="viewColorId" size="small" filterable class="color-sel">
                <el-option v-for="c in detail.colors" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
              </el-select>
            </div>

            <aside data-testid="bom-inspector" class="bom-inspector" aria-label="主图与物料查看">
              <div class="bom-style-image">
                <el-image v-if="styleImage" :src="styleImage.thumbUrl || styleImage.url" :preview-src-list="[styleImage.url]" preview-teleported fit="contain" />
                <span v-else class="dim">暂无主图</span>
                <small>{{ detail.styleCode }} {{ detail.styleName }} · 当前款式主图</small>
              </div>
              <div v-if="inspectedBom" data-testid="bom-row-card" class="bom-row-card" tabindex="0">
                <strong>{{ inspectedBom.materialCode }} {{ inspectedBom.materialName }}</strong>
                <p class="dim">{{ bomParts.labelOf(inspectedBom.part) }} · {{ inspectedBom.supplierName || '未指定供应商' }}</p>
                <div v-for="sk in inspectedSkus" :key="`${sk.materialSkuId}-${sk.colorId}-${sk.sizeCode}`" class="bom-card-sku">
                  <el-image v-if="sk.image || inspectedBom.image" :src="(sk.image || inspectedBom.image)!.url" :preview-src-list="[(sk.image || inspectedBom.image)!.url]" preview-teleported fit="contain" />
                  <span v-else class="dim">暂无物料图</span>
                  <span>{{ sk.skuCode }} · {{ [sk.skuColorName, sk.skuSpec].filter(Boolean).join(' / ') || '默认规格' }}<small v-if="sk.skuStatus === 0" class="danger">已停用</small></span>
                </div>
                <p v-if="!inspectedSkus.length" class="danger">当前颜色还没有配料</p>
                <p v-for="problem in bomProblems(inspectedBom)" :key="problem" class="danger">{{ problem }}</p>
              </div>
              <span v-else class="dim">悬停、聚焦或点击物料编码查看料卡</span>
            </aside>

            <el-table v-if="bomView === 'bom'" :data="draft.bomItems" row-key="id" empty-text="还没有物料：点「从物料库选」加">
              <el-table-column type="expand" width="32">
                <template #default="{ row }">
                  <div class="expand">
                    <table class="mini">
                      <thead>
                        <tr><th>配到</th><th>物料 SKU</th><th v-if="canSeeCost" class="num">单独单价</th></tr>
                      </thead>
                      <tbody>
                        <tr v-for="sk in (row as BomItem).skus" :key="`${sk.colorId}-${sk.sizeCode}`">
                          <td>{{ [sk.colorName, sk.sizeCode].filter(Boolean).join(' · ') || '全部' }}</td>
                          <td :class="{ danger: sk.skuStatus === 0 }">
                            <span class="code">{{ sk.skuCode }}</span> {{ [sk.skuColorName, sk.skuSpec].filter(Boolean).join(' ') }}
                            <template v-if="sk.skuStatus === 0">（已停用，生效时会被拦）</template>
                          </td>
                          <td v-if="canSeeCost" class="num">{{ sk.refPrice == null ? '用行上的' : formatPrice(sk.refPrice) }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <table v-if="row.usageMode === 'BY_SIZE'" class="mini">
                      <thead>
                        <tr><th v-for="sz in detail.sizes" :key="sz" class="num">{{ sz }}</th></tr>
                      </thead>
                      <tbody>
                        <tr><td v-for="sz in detail.sizes" :key="sz" class="num">{{ row.sizeUsages[sz] == null ? '—' : formatQty(row.sizeUsages[sz]) }}</td></tr>
                      </tbody>
                    </table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="#" width="40" class-name="num">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column label="" width="48">
                <template #default="{ row }">
                  <ThumbImage v-if="row.image" :src="row.image.thumbUrl" alt="" class="thumb" />
                </template>
              </el-table-column>
              <el-table-column label="部位" width="110">
                <template #default="{ row }">
                  <DictSelect v-if="editable" v-model="row.part" type="bom_part" size="small" placeholder="选部位" :class="{ missing: !row.part }" />
                  <span v-else>{{ bomParts.labelOf(row.part) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="物料" min-width="170">
                <template #default="{ row }">
                  <button type="button" data-testid="bom-row" class="link-btn code" :class="{ danger: row.materialStatus === 0 }" @mouseenter="inspectedBomId = row.id" @focus="inspectedBomId = row.id" @click="inspectedBomId = row.id">{{ row.materialCode }}</button>
                  {{ row.materialName }}
                </template>
              </el-table-column>
              <el-table-column label="配料" min-width="170">
                <template #default="{ row }">
                  <span class="dim">{{ BOM_SKU_MODE[(row as BomItem).skuMode] }}</span>
                  <template v-if="row.skuMode === 'FIXED' && row.skus[0]"> <span class="code">{{ row.skus[0].skuCode }}</span></template>
                  <template v-else> · {{ row.skus.length }} 个 SKU</template>
                  <div v-for="p in bomProblems(row as BomItem)" :key="p" class="danger small">{{ p }}</div>
                </template>
              </el-table-column>
              <el-table-column label="用量" width="96" class-name="num" align="right">
                <template #default="{ row }">{{ usageText(row as BomItem) }}</template>
              </el-table-column>
              <el-table-column label="单位" width="56">
                <template #default="{ row }">{{ units.labelOf(row.unit) }}</template>
              </el-table-column>
              <el-table-column label="损耗 %" width="66" class-name="num" align="right">
                <template #default="{ row }">{{ formatQty(row.lossPct) }}</template>
              </el-table-column>
              <el-table-column label="来源" width="80">
                <template #default="{ row }">{{ MATERIAL_SOURCE_LABEL[(row as BomItem).source] }}</template>
              </el-table-column>
              <el-table-column label="供应商" min-width="100">
                <template #default="{ row }">{{ row.supplierName ?? '—' }}</template>
              </el-table-column>
              <el-table-column v-if="canSeeCost" label="参考单价" width="86" class-name="num" align="right">
                <template #default="{ row }">{{ row.refPrice == null ? '—' : formatPrice(row.refPrice) }}</template>
              </el-table-column>
              <el-table-column v-if="canSeeCost" label="单件成本" width="86" class-name="num" align="right">
                <template #default="{ row }">{{ unitCostOf(row as BomItem) == null ? '—' : formatMoney(unitCostOf(row as BomItem)) }}</template>
              </el-table-column>
              <el-table-column label="备注" min-width="100">
                <template #default="{ row }">{{ row.remark ?? '' }}</template>
              </el-table-column>
              <el-table-column v-if="editable" label="" width="150" align="right">
                <template #default="{ $index }">
                  <el-button link size="small" :disabled="$index === 0" aria-label="上移" @click="moveItem(draft.bomItems, $index, -1)"><el-icon><Top /></el-icon></el-button>
                  <el-button link size="small" :disabled="$index === draft.bomItems.length - 1" aria-label="下移" @click="moveItem(draft.bomItems, $index, 1)"><el-icon><Bottom /></el-icon></el-button>
                  <el-button link size="small" @click="openEditBom($index)">改</el-button>
                  <el-button link type="danger" size="small" @click="draft.bomItems.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>

            <el-table v-else :data="colorViewRows" row-key="key" empty-text="还没有物料">
              <el-table-column label="部位" width="100">
                <template #default="{ row }">{{ bomParts.labelOf(row.item.part) }}</template>
              </el-table-column>
              <el-table-column label="物料" min-width="170">
                <template #default="{ row }"><button type="button" data-testid="bom-row" class="link-btn" @mouseenter="inspectedBomId = row.item.id" @focus="inspectedBomId = row.item.id" @click="inspectedBomId = row.item.id">{{ row.item.materialName }}</button></template>
              </el-table-column>
              <el-table-column label="物料 SKU" min-width="170">
                <template #default="{ row }">
                  <template v-if="row.sku">
                    <span class="code" :class="{ danger: row.sku.skuStatus === 0 }">{{ row.sku.skuCode }}</span>
                    {{ [row.sku.skuColorName, row.sku.skuSpec].filter(Boolean).join(' ') }}
                  </template>
                  <span v-else class="danger">这个颜色没配料</span>
                </template>
              </el-table-column>
              <el-table-column v-for="sz in detail.sizes" :key="sz" :label="sz" width="70" class-name="num" align="right">
                <template #default="{ row }">{{ row.usages[sz] == null ? '' : formatQty(row.usages[sz]) }}</template>
              </el-table-column>
              <el-table-column label="单位" width="56">
                <template #default="{ row }">{{ units.labelOf(row.item.unit) }}</template>
              </el-table-column>
              <el-table-column label="来源" width="80">
                <template #default="{ row }">{{ MATERIAL_SOURCE_LABEL[(row as ColorViewRow).item.source] }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 工艺指示 -->
          <el-tab-pane label="工艺指示" name="craft">
            <template v-for="grp in CRAFT_GROUPS" :key="grp.title">
              <div v-if="shownSections(grp.sections).length" class="craft-group">
                <h4 class="grp-h">{{ grp.title }}</h4>
                <div class="sec-grid">
                  <div v-for="sec in shownSections(grp.sections)" :key="sec" class="craft-box">
                    <div class="craft-head">
                      <strong>{{ CRAFT_SECTION_LABEL[sec] }}</strong>
                      <div v-if="sec === 'PACKING'" class="pack-mode">
                        <span class="dim">包装方式</span>
                        <DictSelect v-model="draft.packingMode" type="packing_mode" size="small" :disabled="!editable" clearable />
                      </div>
                    </div>
                    <el-input v-if="editable" v-model="sectionFormOf(sec).content" type="textarea" :autosize="{ minRows: 3 }" />
                    <p v-else class="craft-text">{{ sectionFormOf(sec).content }}</p>
                    <ImageUpload :key="`${detail.id}-${sec}`" v-if="editable || sectionFormOf(sec).images.length" v-model="sectionFormOf(sec).images" :max="6" :disabled="!editable" />
                  </div>
                </div>
              </div>
            </template>

            <div class="proc-block">
              <div class="sub-toolbar">
                <strong>工序工价</strong>
                <el-tag size="small" type="info" disable-transitions>内部</el-tag>
                <span class="gap" />
                <el-button v-if="editable" size="small" icon="Plus" @click="addProcess">加工序</el-button>
              </div>
              <el-table :data="draft.processes" empty-text="还没有工序">
                <el-table-column label="#" width="44" class-name="num">
                  <template #default="{ $index }">{{ $index + 1 }}</template>
                </el-table-column>
                <el-table-column label="工序类别" width="120">
                  <template #default="{ row }">
                    <DictSelect v-if="editable" v-model="row.category" type="process_category" size="small" clearable />
                    <span v-else>{{ procCats.labelOf(row.category) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="工序名称" min-width="130">
                  <template #default="{ row }">
                    <el-input v-if="editable" v-model="row.name" size="small" />
                    <span v-else>{{ row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="工艺要求" min-width="160">
                  <template #default="{ row }">
                    <el-input v-if="editable" v-model="row.requirement" size="small" />
                    <span v-else>{{ row.requirement || '' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="图示" width="150">
                  <template #default="{ row }">
                    <ImageUpload v-if="editable || row.images.length" v-model="row.images" :max="3" :disabled="!editable" />
                  </template>
                </el-table-column>
                <el-table-column label="标准工时（秒）" width="104" class-name="num" align="right">
                  <template #default="{ row }">
                    <el-input-number v-if="editable" v-model="row.stdSeconds" size="small" :min="0" :precision="0" :controls="false" />
                    <span v-else>{{ row.stdSeconds ?? '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="canSeeCost" label="工价（元）" width="96" class-name="num" align="right">
                  <template #default="{ row }">
                    <el-input-number v-if="editable" v-model="row.price" size="small" :min="0" :precision="4" :controls="false" />
                    <span v-else>{{ row.price == null ? '—' : formatPrice(row.price) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="备注" min-width="100">
                  <template #default="{ row }">
                    <el-input v-if="editable" v-model="row.remark" size="small" />
                    <span v-else>{{ row.remark || '' }}</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="editable" label="" width="110" align="right">
                  <template #default="{ $index }">
                    <el-button link size="small" :disabled="$index === 0" aria-label="上移" @click="moveItem(draft.processes, $index, -1)"><el-icon><Top /></el-icon></el-button>
                    <el-button link size="small" :disabled="$index === draft.processes.length - 1" aria-label="下移" @click="moveItem(draft.processes, $index, 1)"><el-icon><Bottom /></el-icon></el-button>
                    <el-button link type="danger" size="small" @click="draft.processes.splice($index, 1)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <p class="proc-total num">总工时 {{ totalProcessSeconds }} 秒<template v-if="canSeeCost"> · 参考加工价 ¥{{ formatMoney(totalProcessLabor) }}</template></p>
            </div>
          </el-tab-pane>

          <!-- 核价（按上次保存的算） -->
          <el-tab-pane v-if="canSeeCost" label="核价" name="cost">
            <el-alert v-if="guard.dirty.value" type="warning" :closable="false" show-icon class="mb-12" title="有没保存的修改，保存后核价才更新" />
            <p v-if="costState === 'loading'" role="status" class="dim">核价加载中，其他页签可继续编辑…</p>
            <div v-if="costData" class="cost-pane">
              <div class="sub-toolbar">
                <el-segmented v-model="draft.costMode" size="small" :disabled="!editable" :options="Object.entries(COST_MODE).map(([value, label]) => ({ label, value }))" />
                <span class="dim">颜色</span>
                <el-select v-model="costColorId" size="small" filterable class="color-sel" @change="onCostColorChange">
                  <el-option v-for="c in detail.colors" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
                </el-select>
                <template v-if="draft.costMode === 'BUY'">
                  <span class="dim">成品单价</span>
                  <el-input-number
                    v-model="draft.buyPrice"
                    data-testid="cost-buy-price"
                    size="small"
                    :min="0"
                    :max="9999999999.9999"
                    :precision="4"
                    :controls="false"
                    :disabled="!editable"
                    :placeholder="costData.buyPriceDefault == null ? '' : `默认 ${formatMoney(costData.buyPriceDefault)}，取自价格档案`"
                    class="buy-input"
                  />
                </template>
              </div>

              <div v-for="blk in costBlocks" :key="blk.title" class="cost-block">
                <div class="blk-head">
                  <strong>{{ blk.title }}</strong>
                  <span class="num">¥{{ formatMoney(blk.sum) }}</span>
                </div>
                <el-table :data="blk.rows" row-key="bomItemId" :empty-text="`没有${blk.title}`">
                  <el-table-column label="部位" width="90">
                    <template #default="{ row }">{{ bomParts.labelOf(row.part) }}</template>
                  </el-table-column>
                  <el-table-column label="物料 SKU" min-width="190">
                    <template #default="{ row }">
                      {{ row.materialName }}<span v-if="row.skuCode" class="code dim"> {{ row.skuCode }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="用量" width="96" class-name="num" align="right">
                    <template #default="{ row }">{{ formatQty(row.usageQty) }} {{ units.labelOf(row.unit) }}</template>
                  </el-table-column>
                  <el-table-column label="损耗 %" width="66" class-name="num" align="right">
                    <template #default="{ row }">{{ formatQty(row.lossPct) }}</template>
                  </el-table-column>
                  <el-table-column v-if="canSeeCost" label="单价" width="86" class-name="num" align="right">
                    <template #default="{ row }">{{ row.price == null ? '—' : formatPrice(row.price) }}</template>
                  </el-table-column>
                  <el-table-column v-if="canSeeCost" label="单件成本" width="110" class-name="num" align="right">
                    <template #default="{ row }">
                      <template v-if="row.counted">{{ formatMoney(row.cost) }}</template>
                      <span v-else class="dim">{{ notCountedText(row as CostRow) }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="cost-block">
                <div class="blk-head">
                  <strong>加工费</strong>
                  <span class="num">¥{{ formatMoney(costData.summary.labor) }}</span>
                </div>
                <p class="blk-line">
                  <template v-if="costData.costMode === 'OUTSOURCE'">按外发：{{ costData.processCount }} 道工序的工价合计</template>
                  <template v-else>按成品采购：成品单价{{ costData.buyPrice == null ? '（没填，用价格档案的默认价）' : '' }}</template>
                </p>
                <p v-if="costData.costMode === 'OUTSOURCE' && costData.buyPriceDefault != null" class="blk-line dim">
                  成品采购参考价 ¥{{ formatMoney(costData.buyPriceDefault) }}{{ costData.defaultFactoryName ? `（${costData.defaultFactoryName}）` : '' }}，不计入
                </p>
              </div>

              <div class="cost-block">
                <div class="blk-head">
                  <strong>其他费用</strong>
                  <span class="num">¥{{ formatMoney(costData.summary.other) }}</span>
                  <span class="gap" />
                  <el-button v-if="editable" size="small" icon="Plus" @click="addOtherCost">加一项</el-button>
                </div>
                <el-table :data="draft.costItems" empty-text="没有其他费用" class="other-cost-table">
                  <el-table-column label="名称" min-width="160">
                    <template #default="{ row }">
                      <el-input v-if="editable" v-model="row.name" size="small" placeholder="如：前胸刺绣" />
                      <span v-else>{{ row.name }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="canSeeCost" label="金额（元/件）" width="120" class-name="num" align="right">
                    <template #default="{ row }">
                      <el-input-number v-if="editable" v-model="row.amount" size="small" :min="0" :precision="2" :controls="false" />
                      <span v-else>{{ formatMoney(row.amount) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="备注" min-width="160">
                    <template #default="{ row }">
                      <el-input v-if="editable" v-model="row.remark" size="small" />
                      <span v-else>{{ row.remark || '' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="editable" label="" width="60" align="right">
                    <template #default="{ $index }">
                      <el-button link type="danger" size="small" @click="draft.costItems.splice($index, 1)">删</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div class="cost-total num">
                <span>单件成本 <strong>¥{{ formatMoney(costData.summary.total) }}</strong></span>
                <span v-if="costData.mixedBasis" class="warn-txt">单价口径不一（有的含税有的不含），照录入的数加总</span>
                <span v-if="costData.tagPrice != null">吊牌价 ¥{{ formatMoney(costData.tagPrice) }}</span>
                <span v-if="costData.ratio != null">倍率 {{ costData.ratio }}</span>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList :key="detail.id" :load="loadLogs" :actions="TECH_PACK_LOG_ACTIONS" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </template>

    <BomRowDialog
      v-if="detail"
      v-model="bomDialogVisible"
      :pack-id="detail.id"
      :sizes="detail.sizes"
      :base-size="detail.baseSize"
      :colors="detail.colors"
      :editing-row="editingBomRow"
      @save="onSaveBomRow"
    />
    <DataPick v-model="skuPicking" kind="sku" :have="bomSkuIds" @pick="onPickSkus" />

    <el-dialog v-model="pdfVisible" title="导出工艺单" width="440px">
      <el-form v-if="detail" label-position="top">
        <el-form-item label="颜色">
          <el-select v-model="pdfForm.colorId" filterable clearable placeholder="全部颜色">
            <el-option v-for="c in detail.colors" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <div class="pdf-checks">
            <el-checkbox v-if="canSeeCost" v-model="pdfForm.price">含价格（单价、成本、工价）</el-checkbox>
            <div class="appendix-row">
              <el-checkbox v-model="pdfForm.appendix">附工艺标准</el-checkbox>
              <el-button v-if="canSeeStd" link type="primary" size="small" @click="craftStd?.open('files')">看附页文件</el-button>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pdfVisible = false">取消</el-button>
        <el-button type="primary" :loading="exportingPdf" @click="doExportPdf">导出</el-button>
      </template>
    </el-dialog>

    <CraftStandardDrawer v-if="canSeeStd" ref="craftStd" />
  </div>
</template>

<style scoped>
.bom-inspector { display: flex; flex-wrap: wrap; align-items: center; gap: 20px; padding: 12px; margin-bottom: 12px; border: 1px solid var(--lm-line-soft); border-radius: var(--lm-radius); }
.bom-style-image { display: flex; flex-direction: column; gap: 6px; min-width: 140px; max-width: 220px; }
.bom-style-image .el-image { width: 120px; height: 120px; }
.bom-row-card { flex: 1; min-width: 200px; }
.bom-card-sku { display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px 6px 0; }
.bom-card-sku .el-image { width: 60px; height: 60px; }
.bom-card-sku small { display: block; }

.tp-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.ver-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.ver-pills {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 2px;
  background: var(--lm-sunken);
  border-radius: var(--lm-radius);
}

.ver-pill {
  padding: 4px 10px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-ink-2);
  cursor: pointer;
  background: none;
  border: none;
  border-radius: var(--lm-radius-sm);
}

.ver-pill.active {
  color: var(--lm-ink);
  background: var(--lm-paper);
  box-shadow: var(--lm-shadow-sm);
}

.ver-pill.voided {
  color: var(--lm-faint);
}

.ver-acts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pack-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  padding: 8px 12px;
  font-size: 12.5px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.inline-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.inline-field :deep(.el-input-number) {
  width: 72px;
}

.note-input {
  flex: 1;
  min-width: 240px;
}

.link-btn {
  padding: 0;
  font: inherit;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.link-btn:hover {
  text-decoration: underline;
}

.sub-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 12.5px;
}

.gap {
  flex: 1;
}

.color-sel {
  width: 150px;
}

.buy-input {
  width: 200px;
}

.thumb {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.missing :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-pending-text) inset;
}

.danger {
  color: var(--lm-danger-text);
}

.warn-txt {
  color: var(--lm-pending-text);
}

.small {
  font-size: 11.5px;
}

:deep(.base-col) {
  background: var(--lm-accent-bg);
}

.expand {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 4px 16px 8px 56px;
}

.mini {
  font-size: 12px;
  border-collapse: collapse;
}

.mini th,
.mini td {
  padding: 4px 10px;
  text-align: left;
  border-bottom: 1px solid var(--lm-line-soft);
}

.mini th {
  font-weight: 500;
  color: var(--lm-muted);
}

.mini .num {
  text-align: right;
}

.craft-group {
  margin-bottom: 18px;
}

.grp-h {
  margin: 0 0 10px;
  font-size: 13.5px;
  font-weight: 600;
}

.sec-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.craft-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.craft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}

.craft-text {
  margin: 0;
  font-size: 13px;
  white-space: pre-wrap;
}

.pack-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pack-mode :deep(.el-select) {
  width: 110px;
}

.proc-block {
  padding-top: 14px;
  margin-top: 18px;
  border-top: 1px solid var(--lm-line-soft);
}

.proc-total {
  margin: 8px 0 0;
  font-size: 12.5px;
  text-align: right;
}

.mb-12 {
  margin-bottom: 12px;
}

.cost-block {
  margin-bottom: 16px;
}

.other-cost-table :deep(.el-input-number) {
  width: 100%;
  min-width: 0;
}

.blk-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 13px;
}

.blk-line {
  margin: 0 0 4px;
  font-size: 12.5px;
}

.cost-total {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 18px;
  padding: 10px 12px;
  font-size: 13px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.cost-total strong {
  font-size: 16px;
}

.pdf-checks {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.appendix-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

@media (max-width: 900px) {
  .sec-grid {
    grid-template-columns: 1fr;
  }
}
</style>
