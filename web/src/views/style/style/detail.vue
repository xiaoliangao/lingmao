<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { styleApi } from '@/api/style'
import { techPackApi } from '@/api/techpack'
import { STYLE_STATUS, TECH_PACK_STATUS, type StyleAction, type StyleColor, type StyleDetail, type StyleSku } from '@/types/style'
import type { FileVO } from '@/types/system'
import type { TechPackOverview } from '@/types/techpack'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import ColorSelect from '@/components/ColorSelect.vue'
import PriceHistory from '@/components/price/PriceHistory.vue'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { formatDate, formatDateTime, formatPrice } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'
import DesignFiles from './DesignFiles.vue'
import StyleOrdersTab from './StyleOrdersTab.vue'
import TechPackTab from './TechPackTab.vue'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeProductionPrice } = useFieldAccess()

/**
 * 款式详情（/styles/:id，RFC 005 §9，原型 /styles/:code）。左边页签：基本信息、资料包、颜色尺码、需求订单、价格、操作记录；右边图片、设计附件。
 * 顶上的按钮按后端给的 actions 显示（状态 + 权限，后端算好的）。
 * 价格页签只有这款的加工价（RFC 006 §9），要 price:price:list，没有就不显示。
 * 资料包页签要 techpack:pack:list，需求订单页签要 order:order:list（docs/api/01 §1.1）。
 * 一款一个页签（详情路由，见 router/index.ts）：id 在这里取一次，不跟着路由变；
 * 但地址上的 ?tab=（还有资料包的 techPackId、sub）要 watch：页签留着时，别处带着参数再点进来不会重新挂载
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()
const id = routeId(route.params.id)
const path = route.path

const classes = useDict('style_class')
const brands = useDict('brand')
const seasons = useDict('season')
const waves = useDict('wave')
const styleTags = useDict('style_tag')
const series = useDict('series')
const genders = useDict('gender')
const sources = useDict('style_source')
const standards = useDict('exec_standard')
const safety = useDict('safety_category')

/** SKU 表格的一行：条码、吊牌价在输入框里改的先放 draft，离开输入框再存 */
type SkuRow = StyleSku & { draftBarcode: string; draftPrice: number | null }

const detail = ref<StyleDetail | null>(null)
const skus = ref<SkuRow[]>([])
const notFound = ref(false)
const logList = ref<InstanceType<typeof DocLogList>>()
const loadLogs = () => styleApi.logs(id)
const canEdit = computed(() => user.hasPerm('style:style:edit'))
const canRemove = computed(() => user.hasPerm('style:style:remove'))
const canSeePack = computed(() => user.hasPerm('techpack:pack:list'))
const canSeeCost = computed(() => user.hasPerm('techpack:cost:query'))
const can = (action: StyleAction) => detail.value?.actions.includes(action) ?? false

// ---------- 页签：没带 ?tab= 是基本信息（01 §1.1） ----------
const TABS = ['info', 'techpack', 'skus', 'orders', 'prices', 'logs'] as const
type TabName = (typeof TABS)[number]
const pickTab = (value: unknown): TabName | undefined => ((TABS as readonly unknown[]).includes(value) ? (value as TabName) : undefined)
const tab = ref<TabName>(pickTab(route.query.tab) ?? 'info')
watch(
  () => route.query.tab,
  (value) => {
    const name = pickTab(value)
    if (name && route.path === path) tab.value = name
  },
)

// ---------- 资料包概况：顶上的"新颜色没配料"、基本信息的"资料包核价"、"下订单"按钮都用它 ----------
const tpOverview = ref<TechPackOverview | null>(null)
const techPack = ref<InstanceType<typeof TechPackTab>>()

async function loadOverview() {
  if (!canSeePack.value) return
  try {
    tpOverview.value = await techPackApi.overview(id)
  } catch {
    tpOverview.value = null
  }
}

const unmatchedNames = computed(() => (tpOverview.value?.unmatchedColors ?? []).map((c) => c.name).join('、'))

/** 下订单：款式启用、有已生效的生产 BOM、有 order:order:add（看不到资料包的按款式上的资料包状态判断） */
const canOrder = computed(() => {
  const d = detail.value
  if (!d || d.status !== 'ENABLED' || !user.hasPerm('order:order:add')) return false
  const packs = tpOverview.value?.packs
  return packs ? packs.some((p) => p.status === 'EFFECTIVE') : d.techPackStatus === 'PROD'
})

/** 基本信息里的"资料包核价"：点了去那一版的核价子页签 */
async function openCost() {
  const packId = tpOverview.value?.unitCostPackId
  if (!packId) return
  tab.value = 'techpack'
  await nextTick()
  await techPack.value?.open(packId, 'cost')
}

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  try {
    const [d, list] = await read.run(() => Promise.all([styleApi.get(id), styleApi.skus(id), loadOverview()]))
    if (!read.current()) return
    detail.value = d
    skus.value = list.map((k) => ({ ...k, draftBarcode: k.barcode ?? '', draftPrice: k.tagPrice }))
    tabs.rename(path, `款式 ${d.code}`)
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

onMounted(load)
// 从编辑页保存回来、从别的页签切回来：重新取一遍（第一次由上面取）
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (!notFound.value) refresh()
})

/** 改了东西：重新取一遍，操作记录也刷新 */
async function refresh() {
  await load()
  logList.value?.reload()
}

const s = computed(() => detail.value)
const subLine = computed(() => {
  const d = s.value
  if (!d) return ''
  return [d.categoryPath, `${d.year} ${seasons.labelOf(d.season)}`, d.wave ? waves.labelOf(d.wave) : '', brands.labelOf(d.brand)]
    .filter(Boolean)
    .join(' · ')
})

/** 基本信息页签的资料，空的显示"—" */
const infoRows = computed(() => {
  const d = s.value
  if (!d) return []
  const money = (v: number | null | undefined) => (v == null ? '' : `${formatPrice(v)} 元`)
  return [
    ['款号', d.code],
    ['款式名称', d.name],
    ['花名', d.aliasName],
    ['大类', `${d.styleClass} ${classes.labelOf(d.styleClass)}`],
    ['品牌', brands.labelOf(d.brand)],
    ['品类', d.categoryPath],
    ['年份季节', `${d.year} ${seasons.labelOf(d.season)}`],
    ['波段', waves.labelOf(d.wave)],
    ['风格', styleTags.labelOf(d.styleTag)],
    ['系列', series.labelOf(d.series)],
    ['性别', genders.labelOf(d.gender)],
    ['来源', sources.labelOf(d.source)],
    ['设计师', d.designerName],
    ['版师', d.patternMakerName],
    ['尺码组', `${d.sizeGroupName}（${d.sizes.join(' ')}）`],
    ['吊牌价', money(d.tagPrice)],
    ...(canSeeCost.value ? [['参考成本价', money(d.refCost)]] : []),
    ['上市日期', d.launchDate ? formatDate(d.launchDate) : ''],
    ['品名', d.productName],
    ['成分', d.composition],
    ['执行标准', standards.labelOf(d.execStandard)],
    ['安全类别', safety.labelOf(d.safetyCategory)],
    ['样衣编号', d.sampleNo],
    ['资料包', TECH_PACK_STATUS[d.techPackStatus].label],
    ['建档', [d.createByName, formatDateTime(d.createTime)].filter(Boolean).join(' · ')],
    ['更新', formatDateTime(d.updateTime)],
    ['备注', d.remark],
  ].map(([label, value]) => ({ label: label as string, value: (value as string | null | undefined) || '—' }))
})
const legacy = computed(() => Object.entries(s.value?.legacyAttrs ?? {}))

// ---------- 款式本身：编辑、启用停用、删除、图片、附件 ----------
async function enable() {
  await styleApi.enable(id)
  ElMessage.success(`已启用 ${s.value!.code}`)
  refresh()
}

async function disable() {
  try {
    await ElMessageBox.confirm('停用后不能再下新订单、录新的加工价；已有的订单照常生产、回货，随时可以启用。', `停用款式 ${s.value!.code}`, {
      confirmButtonText: '停用',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  await styleApi.disable(id)
  ElMessage.success(`已停用 ${s.value!.code}`)
  refresh()
}

async function remove() {
  const code = s.value!.code
  await confirmRemove(`删除款式 ${code}`, '删除以后连同颜色、SKU、图片、附件、操作记录一起找不回来。已经被价格档案、订单用过的删不掉，请改成停用。')
  await styleApi.remove(id)
  ElMessage.success(`已删除 ${code}`)
  await router.push('/styles')
  tabs.close(path, '/styles')
}

async function saveImages(files: FileVO[]) {
  detail.value!.images = await styleApi.replaceImages(id, files)
  logList.value?.reload()
}

async function saveFiles(files: FileVO[]) {
  detail.value!.files = await styleApi.replaceFiles(id, files)
  logList.value?.reload()
}

// ---------- 颜色 ----------
const adding = ref<string[]>([])
const colorPicker = ref<InstanceType<typeof ColorSelect>>()
const addingPreview = computed(() => {
  const d = s.value
  const picked = colorPicker.value?.picked ?? []
  if (!d) return []
  return picked.flatMap((c) => d.sizes.map((size) => `${d.code}-${c.code}-${size}`))
})
const working = ref(false)

async function addColors() {
  working.value = true
  try {
    await styleApi.addColors(id, adding.value)
    const created = await styleApi.generateSkus(id)
    ElMessage.success(`加了 ${adding.value.length} 个颜色，生成 ${created} 个 SKU`)
    adding.value = []
  } finally {
    working.value = false
    refresh()
  }
}

async function generate() {
  working.value = true
  try {
    const created = await styleApi.generateSkus(id)
    if (created) ElMessage.success(`补了 ${created} 个 SKU`)
    else ElMessage.info('启用的颜色 × 尺码都有 SKU 了，没有要补的')
  } finally {
    working.value = false
    refresh()
  }
}

/** 颜色在款式上的顺序：上移、下移一格，整组发给后端 */
async function move(index: number, step: -1 | 1) {
  const ids = s.value!.colors.map((c) => c.colorId)
  const target = index + step
  if (target < 0 || target >= ids.length) return
  ;[ids[index], ids[target]] = [ids[target]!, ids[index]!]
  await styleApi.reorderColors(id, ids)
  refresh()
}

async function toggleColor(c: StyleColor) {
  if (c.status === 1) {
    try {
      await ElMessageBox.confirm(`停用后订单里选不到 ${c.name} 的 SKU，已有的不受影响；生成 SKU 时跳过它。`, `停用颜色 ${c.code} ${c.name}`, {
        confirmButtonText: '停用',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      })
    } catch {
      return
    }
    await styleApi.disableColor(id, c.colorId)
  } else {
    await styleApi.enableColor(id, c.colorId)
  }
  refresh()
}

async function removeColor(c: StyleColor) {
  await confirmRemove(`去掉颜色 ${c.code} ${c.name}`, '这个颜色还没生成 SKU，去掉以后可以再加回来。')
  await styleApi.removeColor(id, c.colorId)
  ElMessage.success(`去掉了 ${c.name}`)
  refresh()
}

// ---------- SKU：条码、吊牌价直接在表格里改，离开输入框就存 ----------
async function saveSku(sku: SkuRow) {
  const barcode = sku.draftBarcode.trim() || null
  const tagPrice = sku.draftPrice ?? null
  if (barcode === sku.barcode && tagPrice === sku.tagPrice) return
  try {
    await styleApi.updateSku(id, sku.id, { barcode, tagPrice })
    ElMessage.success(`已保存 ${sku.code}`)
  } finally {
    // 没存上（条码重复……）也重新取：输入框回到存着的值
    refresh()
  }
}

async function toggleSku(sku: StyleSku) {
  if (sku.status === 1) {
    try {
      await ElMessageBox.confirm('停用后订单里选不到这个 SKU，已有的订单照常生产。', `停用 SKU ${sku.code}`, {
        confirmButtonText: '停用',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      })
    } catch {
      return
    }
    await styleApi.disableSku(id, sku.id)
  } else {
    await styleApi.enableSku(id, sku.id)
  }
  refresh()
}

async function removeSku(sku: StyleSku) {
  await confirmRemove(`删除 SKU ${sku.code}`, '删除以后找不回来，点「补齐 SKU」会重新生成。已经下过订单、录过价格的删不掉，请改成停用。')
  await styleApi.removeSku(id, sku.id)
  ElMessage.success(`已删除 ${sku.code}`)
  refresh()
}

/** 操作记录里款式自己的动作（新建、修改、启用、停用、导入、换图片由 DocLogList 给） */
const LOG_ACTIONS = {
  addColors: '加颜色',
  sortColors: '调颜色顺序',
  enableColor: '启用颜色',
  disableColor: '停用颜色',
  removeColor: '去掉颜色',
  generate: '生成 SKU',
  updateSku: '改 SKU',
  deleteSku: '删 SKU',
  enableSku: '启用 SKU',
  disableSku: '停用 SKU',
  files: '换附件',
}
const LOG_STATUSES = Object.fromEntries(Object.entries(STYLE_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="s ? `${s.code} ${s.name}` : '款式'" :back="{ label: '款式', to: '/styles' }">
      <template v-if="s" #title>
        <StatusTag :tone="STYLE_STATUS[s.status].tone">{{ STYLE_STATUS[s.status].label }}</StatusTag>
      </template>
      <template v-if="s" #actions>
        <el-button v-if="can('remove')" type="danger" plain @click="remove">删除</el-button>
        <el-button v-if="can('disable')" @click="disable">停用</el-button>
        <el-button v-if="can('edit')" @click="router.push(`/styles/${id}/edit`)">编辑</el-button>
        <el-button v-if="can('enable')" type="primary" @click="enable">启用</el-button>
        <el-button v-if="canOrder" type="primary" @click="router.push(`/orders/new?styleId=${id}`)">下订单</el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这个款式不存在，可能已经删了">
      <el-button @click="router.push('/styles')">回款式列表</el-button>
    </BlankState>

    <div v-else-if="s" class="page detail">
      <div class="body">
        <div v-if="unmatchedNames" class="unmatched">
          <span>新颜色 {{ unmatchedNames }} 还没配料，订单不能下这个颜色</span>
          <el-button size="small" @click="tab = 'techpack'">去资料包</el-button>
        </div>
        <p class="sub dim">
          {{ subLine }}
          <span v-for="c in s.colors.filter((x) => x.status === 1)" :key="c.colorId" class="swatch" :title="`${c.code} ${c.name}`" :style="{ background: c.hex ?? undefined }" />
        </p>
        <el-tabs v-model="tab">
          <el-tab-pane label="基本信息" name="info">
            <dl class="props info">
              <template v-for="p in infoRows" :key="p.label">
                <dt>{{ p.label }}</dt>
                <dd>{{ p.value }}</dd>
              </template>
              <template v-if="canSeePack && canSeeCost">
                <dt>资料包核价</dt>
                <dd>
                  <button v-if="tpOverview?.unitCost != null" type="button" class="link-btn num" @click="openCost">
                    {{ formatPrice(tpOverview.unitCost) }} 元
                  </button>
                  <template v-else>资料包还没生效</template>
                </dd>
              </template>
              <dt>主力面料</dt>
              <dd>
                <router-link v-if="s.mainMaterialId" :to="`/materials/${s.mainMaterialId}`">
                  <span class="code">{{ s.mainMaterialCode }}</span> {{ s.mainMaterialName }}
                </router-link>
                <template v-else>—</template>
              </dd>
              <dt>默认加工厂</dt>
              <dd>
                <router-link v-if="s.defaultFactoryId" :to="`/suppliers/${s.defaultFactoryId}`">{{ s.defaultFactoryName }}</router-link>
                <template v-else>—</template>
              </dd>
            </dl>
            <template v-if="legacy.length">
              <h4 class="sec">领猫附加属性<span class="dim">导入时带过来的，只看</span></h4>
              <dl class="props info">
                <template v-for="[k, v] in legacy" :key="k">
                  <dt>{{ k }}</dt>
                  <dd>{{ v || '—' }}</dd>
                </template>
              </dl>
            </template>
          </el-tab-pane>

          <el-tab-pane v-if="canSeePack" label="资料包" name="techpack" lazy>
            <TechPackTab ref="techPack" :style-id="id" :style-code="s.code" :style-name="s.name" :style-image="s.images[0] ?? null" :overview="tpOverview" @changed="loadOverview" />
          </el-tab-pane>

          <el-tab-pane :label="`颜色尺码（${skus.length}）`" name="skus">
            <h4 class="sec">颜色<span class="dim">{{ s.colors.length }} 个 · 尺码组 {{ s.sizeGroupName }}（{{ s.sizes.join(' ') }}）</span></h4>
            <ul v-if="s.colors.length" class="colors">
              <li v-for="(c, i) in s.colors" :key="c.colorId" :class="{ off: c.status !== 1 }">
                <span class="swatch" :style="{ background: c.hex ?? undefined }" />
                <span class="code">{{ c.code }}</span>
                <span>{{ c.name }}</span>
                <StatusTag v-if="c.status !== 1" tone="off">停用</StatusTag>
                <span v-if="c.colorStatus !== 1" class="warn" title="基础资料里这个颜色停用了，生成 SKU 时跳过">基础里已停用</span>
                <span class="dim small">{{ c.skuCount }} 个 SKU</span>
                <span class="gap" />
                <template v-if="canEdit">
                  <el-button link size="small" :disabled="i === 0" aria-label="上移" @click="move(i, -1)"><el-icon><Top /></el-icon></el-button>
                  <el-button link size="small" :disabled="i === s.colors.length - 1" aria-label="下移" @click="move(i, 1)"><el-icon><Bottom /></el-icon></el-button>
                  <el-button link size="small" @click="toggleColor(c)">{{ c.status === 1 ? '停用' : '启用' }}</el-button>
                  <el-button v-if="c.skuCount === 0" link size="small" type="danger" @click="removeColor(c)">去掉</el-button>
                </template>
              </li>
            </ul>
            <p v-else class="dim">还没有颜色：在下面挑颜色，加上就按 颜色 × 尺码 生成 SKU</p>

            <div v-if="canEdit" class="add">
              <ColorSelect ref="colorPicker" v-model="adding" :exclude="s.colors.map((c) => c.colorId)" />
              <div v-if="adding.length" class="preview">
                <span class="dim">会新增 {{ addingPreview.length }} 个 SKU：</span>
                <span v-for="code in addingPreview.slice(0, 60)" :key="code" class="code">{{ code }}</span>
                <span v-if="addingPreview.length > 60" class="dim">…… 一共 {{ addingPreview.length }} 个</span>
              </div>
              <div class="add-acts">
                <el-button type="primary" :disabled="!adding.length" :loading="working" @click="addColors">
                  {{ adding.length ? `加 ${adding.length} 个颜色，生成 ${addingPreview.length} 个 SKU` : '加颜色' }}
                </el-button>
                <el-button v-if="adding.length" @click="adding = []">不加了</el-button>
                <span class="gap" />
                <el-button :loading="working" title="启用的颜色 × 尺码里还没有 SKU 的补上（删过的、颜色重新启用的）" @click="generate">补齐 SKU</el-button>
              </div>
            </div>

            <h4 class="sec">SKU<span class="dim">条码、吊牌价改完离开输入框就存；吊牌价空着 = 用款式的</span></h4>
            <el-table :data="skus" row-key="id" :row-class-name="({ row }) => (row.status === 1 ? '' : 'off')" empty-text="还没有 SKU：先加颜色">
              <el-table-column label="SKU" min-width="170" class-name="code" prop="code" />
              <el-table-column label="颜色" min-width="100">
                <template #default="{ row }">
                  <span class="color">
                    <span class="swatch" :style="{ background: row.colorHex ?? undefined }" />
                    {{ row.colorName }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="尺码" prop="sizeCode" width="70" />
              <el-table-column label="条码" min-width="170">
                <template #default="{ row }">
                  <el-input
                    v-if="canEdit"
                    v-model="row.draftBarcode"
                    size="small"
                    class="code"
                    maxlength="32"
                    placeholder="手填或导入"
                    @change="saveSku(row as SkuRow)"
                  />
                  <span v-else class="code">{{ row.barcode ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="吊牌价" width="120" class-name="num" align="right">
                <template #default="{ row }">
                  <el-input-number
                    v-if="canEdit"
                    v-model="row.draftPrice"
                    size="small"
                    :min="0"
                    :precision="2"
                    :controls="false"
                    :value-on-clear="null"
                    :placeholder="s.tagPrice == null ? '' : formatPrice(s.tagPrice)"
                    @change="saveSku(row as SkuRow)"
                  />
                  <span v-else>{{ row.tagPrice == null ? '—' : formatPrice(row.tagPrice) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
                </template>
              </el-table-column>
              <el-table-column v-if="canEdit || canRemove" label="操作" width="130" align="right">
                <template #default="{ row }">
                  <el-button v-if="canEdit" link @click="toggleSku(row as StyleSku)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
                  <el-button v-if="canRemove" link type="danger" @click="removeSku(row as StyleSku)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane v-if="user.hasPerm('order:order:list')" label="需求订单" name="orders" lazy>
            <StyleOrdersTab :style-id="id" />
          </el-tab-pane>

          <el-tab-pane v-if="canSeeProductionPrice && user.hasPerm('price:price:list')" label="价格" name="prices" lazy>
            <PriceHistory :subject="{ kind: 'style', id, code: s.code, name: s.name }" />
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logList" :load="loadLogs" :actions="LOG_ACTIONS" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="aside">
        <section class="panel">
          <div class="panel-head">图片<span class="acts dim">第一张是主图</span></div>
          <div class="panel-body">
            <ImageUpload :model-value="s.images" :max="20" :disabled="!canEdit" :save="saveImages" />
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">设计附件<span class="acts dim">图稿、设计说明</span></div>
          <div class="panel-body">
            <DesignFiles :model-value="s.files" :disabled="!canEdit" :save="saveFiles" />
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
/* 已生效的生产 BOM 里有颜色没配料（款式后来加的颜色）：顶上黄条 */
.unmatched {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
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

/* 详情页：左边主体（页签），右边一栏图片、附件。窄屏上下排（和物料详情一样） */
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1280px) {
  .detail {
    grid-template-columns: minmax(0, 1fr);
  }
}

.sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0 0 6px;
  font-size: 13px;
}

.sub .swatch:first-of-type {
  margin-left: 6px;
}

.aside {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.props {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 13px;
}

.props.info {
  grid-template-columns: 84px minmax(0, 1fr) 84px minmax(0, 1fr);
  max-width: 880px;
}

.props dt {
  color: var(--lm-muted);
}

.props dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.sec {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 18px 0 10px;
  font-size: 13px;
  font-weight: 600;
}

.sec:first-child {
  margin-top: 4px;
}

.sec .dim {
  font-size: 12px;
  font-weight: 400;
}

.colors {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 720px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.colors li {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 2px 8px;
  font-size: 13px;
  border-radius: var(--lm-radius-sm);
}

.colors li:hover {
  background: var(--lm-hover);
}

.colors li.off {
  color: var(--lm-muted);
}

.gap {
  flex: 1;
}

.small {
  font-size: 12px;
}

.warn {
  font-size: 12px;
  color: var(--lm-pending-text);
}

.add {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 720px;
  padding: 12px;
  margin-top: 10px;
  background: var(--lm-greige-2);
  border-radius: var(--lm-radius);
}

.add-acts {
  display: flex;
  align-items: center;
  gap: 8px;
}

.preview {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  max-height: 96px;
  overflow-y: auto;
  font-size: 12px;
}

.color {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.swatch {
  flex: none;
  width: 14px;
  height: 14px;
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

:deep(.el-table__row.off) {
  color: var(--lm-muted);
}

:deep(.el-input-number--small) {
  width: 96px;
}

@media (max-width: 768px) {
  .props.info {
    grid-template-columns: 84px minmax(0, 1fr);
  }
}
</style>
