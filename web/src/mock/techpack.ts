import type { CraftStandard, PackStage, StyleCostRow, TechPackCost, TechPackDetail, TechPackOverview, TechPackSaveForm } from '@/types/techpack'
import { CRAFT_SECTION_LABEL } from '@/types/techpack'
import { fail, mock, page } from './index'

let packIdSeq = 300
const packs: TechPackDetail[] = [
  {
    id: 201,
    styleId: 1,
    styleCode: 'FS2611150101',
    styleName: '双排扣廓形短风衣',
    aliasName: '秋日风衣',
    stage: 'DEV',
    version: 1,
    status: 'EFFECTIVE',
    versionNote: '初版打样核价',
    sourcePackId: null,
    sourceVersion: null,
    utilPct: 83.5,
    packingMode: 'HANGING',
    publishBy: 1,
    publishByName: '管理员',
    publishTime: '2026-11-10 14:20:00',
    createByName: '管理员',
    createTime: '2026-11-09 10:00:00',
    updateTime: '2026-11-10 14:20:00',
    versionLock: 2,
    sizes: ['S', 'M', 'L', 'XL'],
    baseSize: 'M',
    colors: [
      { id: 1, code: 'KQ', name: '卡其', hex: '#c3b091' },
      { id: 2, code: 'BK', name: '藏青黑', hex: '#23272e' },
    ],
    pointOptions: [
      { label: '后中长', method: '由后领窝中点垂直量至下摆底边' },
      { label: '胸围', method: '腋下 2cm 处水平量一周' },
      { label: '肩袖长', method: '由颈肩点沿袖山量至袖口边' },
      { label: '摆围', method: '沿下摆底边水平量一周' },
    ],
    specPoints: [
      { id: 1, seq: 1, point: '后中长', method: '由后领窝中点垂直量至下摆底边', toleranceCm: 1, gradeCm: 2, remark: null, values: { S: 64, M: 66, L: 68, XL: 70 } },
      { id: 2, seq: 2, point: '胸围', method: '腋下 2cm 处水平量一周', toleranceCm: 1.5, gradeCm: 4, remark: null, values: { S: 104, M: 108, L: 112, XL: 116 } },
    ],
    bomItems: [
      {
        id: 11,
        seq: 1,
        part: 'BODY',
        materialId: 1,
        materialCode: 'ML0001',
        materialName: '220g 精梳斜纹棉布',
        materialType: 'FABRIC',
        materialStatus: 1,
        skuMode: 'BY_COLOR',
        usageMode: 'UNIFORM',
        usageQty: 1.65,
        sizeUsages: {},
        unit: 'METER',
        lossPct: 3,
        source: 'PURCHASE',
        supplierId: 1,
        supplierName: '示例面料厂',
        refPrice: 28.5,
        priceSourceId: 1,
        priceTaxIncluded: true,
        priceFreightIncluded: true,
        remark: '大身主面料',
        skus: [
          { colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: null, materialSkuId: 101, skuCode: 'ML0001-KQ', skuColorName: '卡其', skuSpec: '150cm', skuStatus: 1, refPrice: null, image: null },
          { colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: null, materialSkuId: 102, skuCode: 'ML0001-BK', skuColorName: '藏青黑', skuSpec: '150cm', skuStatus: 1, refPrice: null, image: null },
        ],
        unitCost: 48.44,
        image: null,
      },
    ],
    craftSections: Object.keys(CRAFT_SECTION_LABEL).map((sec) => ({
      section: sec as TechPackDetail['craftSections'][number]['section'],
      content: sec === 'SEWING' ? '明线 3cm 13 针，双排扣眼距对称一致，止口平服不起皱。' : '',
      images: [],
    })),
    processes: [
      { id: 1, seq: 1, category: 'CUTTING', name: '电剪裁片与打号', requirement: '对格对条准确', stdSeconds: 180, price: 3.5, remark: null, images: [] },
      { id: 2, seq: 2, category: 'SEWING', name: '合肩缝与装翻领', requirement: '领尖左右对称', stdSeconds: 720, price: 24.5, remark: null, images: [] },
      { id: 3, seq: 3, category: 'IRONING', name: '大烫定型与挂装', requirement: '无极光水印', stdSeconds: 240, price: 6, remark: null, images: [] },
    ],
    stdSecondsTotal: 1140,
    laborTotal: 34,
    unitCost: 89.44,
    actions: [],
  },
  {
    id: 202,
    styleId: 1,
    styleCode: 'FS2611150101',
    styleName: '双排扣廓形短风衣',
    aliasName: '秋日风衣',
    stage: 'PROD',
    version: 1,
    status: 'EFFECTIVE',
    versionNote: '转大货生产锁定版',
    sourcePackId: 201,
    sourceVersion: 1,
    utilPct: 85,
    packingMode: 'HANGING',
    publishBy: 1,
    publishByName: '管理员',
    publishTime: '2026-11-12 09:30:00',
    createByName: '管理员',
    createTime: '2026-11-11 16:00:00',
    updateTime: '2026-11-12 09:30:00',
    versionLock: 3,
    sizes: ['S', 'M', 'L', 'XL'],
    baseSize: 'M',
    colors: [
      { id: 1, code: 'KQ', name: '卡其', hex: '#c3b091' },
      { id: 2, code: 'BK', name: '藏青黑', hex: '#23272e' },
    ],
    pointOptions: [
      { label: '后中长', method: '由后领窝中点垂直量至下摆底边' },
      { label: '胸围', method: '腋下 2cm 处水平量一周' },
      { label: '肩袖长', method: '由颈肩点沿袖山量至袖口边' },
    ],
    specPoints: [
      { id: 3, seq: 1, point: '后中长', method: '由后领窝中点垂直量至下摆底边', toleranceCm: 1, gradeCm: 2, remark: null, values: { S: 64, M: 66, L: 68, XL: 70 } },
      { id: 4, seq: 2, point: '胸围', method: '腋下 2cm 处水平量一周', toleranceCm: 1.5, gradeCm: 4, remark: null, values: { S: 104, M: 108, L: 112, XL: 116 } },
    ],
    bomItems: [
      {
        id: 21,
        seq: 1,
        part: 'BODY',
        materialId: 1,
        materialCode: 'ML0001',
        materialName: '220g 精梳斜纹棉布',
        materialType: 'FABRIC',
        materialStatus: 1,
        skuMode: 'BY_COLOR',
        usageMode: 'UNIFORM',
        usageQty: 1.62,
        sizeUsages: {},
        unit: 'METER',
        lossPct: 3,
        source: 'PURCHASE',
        supplierId: 1,
        supplierName: '示例面料厂',
        refPrice: 27.8,
        priceSourceId: 1,
        priceTaxIncluded: true,
        priceFreightIncluded: true,
        remark: '大身主料',
        skus: [
          { colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: null, materialSkuId: 101, skuCode: 'ML0001-KQ', skuColorName: '卡其', skuSpec: '150cm', skuStatus: 1, refPrice: null, image: null },
          { colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: null, materialSkuId: 102, skuCode: 'ML0001-BK', skuColorName: '藏青黑', skuSpec: '150cm', skuStatus: 1, refPrice: null, image: null },
        ],
        unitCost: 46.39,
        image: null,
      },
      {
        id: 22,
        seq: 2,
        part: 'BUTTON',
        materialId: 2,
        materialCode: 'FL0008',
        materialName: '20mm 复古牛角纹树脂四眼扣',
        materialType: 'TRIM',
        materialStatus: 1,
        skuMode: 'FIXED',
        usageMode: 'UNIFORM',
        usageQty: 8,
        sizeUsages: {},
        unit: 'BEAD',
        lossPct: 2,
        source: 'PURCHASE',
        supplierId: 2,
        supplierName: '示例辅料厂',
        refPrice: 0.65,
        priceSourceId: 2,
        priceTaxIncluded: true,
        priceFreightIncluded: true,
        remark: '前门襟双排扣 + 袖袢',
        skus: [
          { colorId: null, colorCode: null, colorName: null, colorHex: null, sizeCode: null, materialSkuId: 201, skuCode: 'FL0008-BR-20MM', skuColorName: '深咖', skuSpec: '20mm', skuStatus: 1, refPrice: null, image: null },
        ],
        unitCost: 5.3,
        image: null,
      },
    ],
    craftSections: Object.keys(CRAFT_SECTION_LABEL).map((sec) => ({
      section: sec as TechPackDetail['craftSections'][number]['section'],
      content:
        sec === 'CUTTING'
          ? '面料预缩松布 24 小时后上裁床，同床分扎编号，严禁混缸。'
          : sec === 'SEWING'
            ? '明线 3cm 13 针，驳头自然顺直，扣眼锁边牢固不脱散。'
            : '',
      images: [],
    })),
    processes: [
      { id: 11, seq: 1, category: 'CUTTING', name: '松布验布与电剪裁片', requirement: '分床打扎', stdSeconds: 180, price: 3.5, remark: null, images: [] },
      { id: 12, seq: 2, category: 'SEWING', name: '全件车缝（双排扣风衣）', requirement: '止口 0.6cm 均匀', stdSeconds: 760, price: 26.5, remark: null, images: [] },
      { id: 13, seq: 3, category: 'FINISHING', name: '钉扣锁眼整烫挂装', requirement: '套防尘袋挂装', stdSeconds: 260, price: 6, remark: null, images: [] },
    ],
    stdSecondsTotal: 1200,
    laborTotal: 36,
    unitCost: 92.69,
    actions: ['revise'],
  },
]

function computeActions(p: TechPackDetail): TechPackDetail['actions'] {
  if (p.status === 'DRAFT') return ['edit', 'remove', 'publish']
  if (p.status === 'EFFECTIVE') {
    const hasSameStageDraft = packs.some((x) => x.styleId === p.styleId && x.stage === p.stage && x.status === 'DRAFT')
    const hasProdDraft = packs.some((x) => x.styleId === p.styleId && x.stage === 'PROD' && x.status === 'DRAFT')
    const acts: TechPackDetail['actions'] = []
    if (!hasSameStageDraft) acts.push('revise')
    if (p.stage === 'DEV' && !hasProdDraft) acts.push('toProd')
    return acts
  }
  return []
}

let craftStd: CraftStandard = {
  files: [{ id: 1, url: '/files/std-sewing.pdf', thumbUrl: null, name: '梭织外套通用做工品质标准 V2.pdf', size: 428000 }],
  texts: {
    CUTTING: '大货面料松布静置不少于 24 小时，严格分色分缸排唛架。',
    SEWING: '止口宽窄一致，起落针回针 3 针以上，无线头外露。',
    PART: '',
    QUALITY: '成品无油污、跳针、断线、色差，左右对称度误差不超过 0.3cm。',
    BUTTON: '纽扣绕脚紧实，拉力测试不低于 70N。',
    IRONING: '整烫平服无极光，整件悬垂自然。',
    LABEL: '主唛居中车缝于后领中，洗唛车于左侧摆缝离底边 10cm 处。',
    PACKING: '单件独立防尘袋包装，外箱贴分色分码箱唛。',
    NOTE: '产前样确认签字后方可投产大货。',
  },
  packingMode: 'HANGING',
  points: [
    { value: 'BACK_LENGTH', label: '后中长', method: '由后领窝中点垂直量至下摆底边', status: 1 },
    { value: 'CHEST', label: '胸围', method: '腋下 2cm 处水平量一周', status: 1 },
    { value: 'SHOULDER_SLEEVE', label: '肩袖长', method: '由颈肩点沿袖外侧量至袖口', status: 1 },
    { value: 'HEM', label: '摆围', method: '沿下摆水平量一周', status: 1 },
  ],
  editable: true,
}

// 工艺标准
mock('get', '/api/craft-standards', () => craftStd)
mock('put', '/api/craft-standards/files', ({ body }) => {
  const list = (body as { url: string; thumbUrl: string | null; name: string; size: number }[]) ?? []
  craftStd.files = list.map((f, idx) => ({ id: idx + 1, ...f }))
  return craftStd.files
})
mock('put', '/api/craft-standards/texts', ({ body }) => {
  const form = body as { texts: Record<string, string | null>; packingMode: string | null }
  for (const k of Object.keys(craftStd.texts)) {
    const key = k as keyof typeof craftStd.texts
    craftStd.texts[key] = form.texts[key] ?? ''
  }
  craftStd.packingMode = form.packingMode
  return null
})
mock('put', '/api/craft-standards/points', ({ body }) => {
  const { points } = body as { points: { value: string; method: string | null }[] }
  for (const pt of points) {
    const hit = craftStd.points.find((x) => x.value === pt.value)
    if (hit) hit.method = pt.method
  }
  return null
})
mock('post', '/api/craft-standards/points', ({ body }) => {
  const { label, method } = body as { label: string; method: string | null }
  if (craftStd.points.some((p) => p.label === label)) fail(1000, `部位「${label}」已存在`)
  craftStd.points.push({ value: label, label, method, status: 1 })
  return null
})

// 按款看成本
mock('get', '/api/style-costs', ({ query }) => {
  const rows: StyleCostRow[] = [
    {
      styleId: 1,
      styleCode: 'FS2611150101',
      styleName: '双排扣廓形短风衣',
      aliasName: '秋日风衣',
      image: null,
      techPackId: 202,
      stage: 'PROD',
      version: 1,
      fabric: 46.39,
      trim: 5.3,
      labor: 36,
      other: 5,
      total: 92.69,
      tagPrice: 499,
      ratio: 5.38,
    },
  ]
  const kw = String(query.keyword ?? '').trim().toLowerCase()
  const filtered = rows.filter((r) => !kw || r.styleCode.toLowerCase().includes(kw) || r.styleName.toLowerCase().includes(kw))
  return page(filtered, query)
})

// 资料包概况与操作
mock('get', '/api/tech-packs', ({ query }) => {
  const styleId = Number(query.styleId ?? 1)
  const list = packs.filter((p) => p.styleId === styleId)
  const prodDraft = list.find((p) => p.stage === 'PROD' && p.status === 'DRAFT')
  const prodEff = list.find((p) => p.stage === 'PROD' && p.status === 'EFFECTIVE')
  const devDraft = list.find((p) => p.stage === 'DEV' && p.status === 'DRAFT')
  const devEff = list.find((p) => p.stage === 'DEV' && p.status === 'EFFECTIVE')
  const defaultPack = prodDraft ?? prodEff ?? devDraft ?? devEff ?? null
  const creatableStages: PackStage[] = []
  if (!devDraft) creatableStages.push('DEV')
  if (!prodDraft) creatableStages.push('PROD')
  const unitPack = prodEff ?? devEff ?? null

  const ov: TechPackOverview = {
    styleId,
    packs: list.map((p) => ({
      id: p.id,
      stage: p.stage,
      version: p.version,
      status: p.status,
      versionNote: p.versionNote,
      sourcePackId: p.sourcePackId,
      sourceVersion: p.sourceVersion,
      publishByName: p.publishByName,
      publishTime: p.publishTime,
      createByName: p.createByName,
      createTime: p.createTime,
      updateTime: p.updateTime,
    })),
    defaultPackId: defaultPack?.id ?? null,
    creatableStages,
    unmatchedColors: [],
    unitCost: unitPack?.unitCost ?? null,
    unitCostPackId: unitPack?.id ?? null,
    unitCostStage: unitPack?.stage ?? null,
  }
  return ov
})

mock('post', '/api/tech-packs', ({ body }) => {
  const { styleId, stage } = body as { styleId: number; stage: PackStage }
  const maxVer = Math.max(0, ...packs.filter((p) => p.styleId === styleId && p.stage === stage).map((p) => p.version))
  const newId = ++packIdSeq
  packs.push({
    ...structuredClone(packs[0]!),
    id: newId,
    styleId,
    stage,
    version: maxVer + 1,
    status: 'DRAFT',
    versionNote: null,
    sourcePackId: null,
    sourceVersion: null,
    publishBy: null,
    publishByName: null,
    publishTime: null,
    versionLock: 1,
    specPoints: [],
    bomItems: [],
    processes: [],
    unitCost: 0,
    actions: ['edit', 'remove', 'publish'],
  })
  return newId
})

mock('post', '/api/tech-packs/:id/bom-defaults', ({ body }) => {
  const { items } = body as { items: { materialId: number; supplierId: number | null; skuIds: number[] }[] }
  return items.map((it) => ({
    materialId: it.materialId,
    unit: 'METER',
    purchaseUnit: 'METER',
    conversionFactor: 1,
    supplierId: it.supplierId ?? 1,
    supplierName: '示例面料厂',
    lossPct: 3,
    source: 'PURCHASE',
    refPrice: 28,
    priceSourceId: 1,
    priceTaxIncluded: true,
    priceFreightIncluded: true,
    priceFrom: 'ARCHIVE',
    skuPrices: [],
  }))
})

mock('get', '/api/tech-packs/:id/cost', ({ params, query }) => {
  const p = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  const colorId = query.colorId ? Number(query.colorId) : (p.colors[0]?.id ?? null)
  const fabricRows = p.bomItems
    .filter((b) => b.materialType === 'FABRIC' || b.materialType === 'LINING')
    .map((b) => ({
      bomItemId: b.id,
      seq: b.seq,
      part: b.part,
      materialId: b.materialId,
      materialName: b.materialName,
      materialSkuId: b.skus[0]?.materialSkuId ?? null,
      skuCode: b.skus[0]?.skuCode ?? null,
      skuColorName: b.skus[0]?.skuColorName ?? null,
      skuSpec: b.skus[0]?.skuSpec ?? null,
      source: b.source,
      usageQty: b.usageQty ?? 1,
      unit: b.unit,
      lossPct: b.lossPct,
      price: b.refPrice,
      priceTaxIncluded: b.priceTaxIncluded,
      priceFreightIncluded: b.priceFreightIncluded,
      counted: b.source === 'PURCHASE',
      cost: b.unitCost ?? 0,
      devCost: p.sourcePackId ? 48.44 : null,
    }))
  const trimRows = p.bomItems
    .filter((b) => b.materialType !== 'FABRIC' && b.materialType !== 'LINING')
    .map((b) => ({
      bomItemId: b.id,
      seq: b.seq,
      part: b.part,
      materialId: b.materialId,
      materialName: b.materialName,
      materialSkuId: b.skus[0]?.materialSkuId ?? null,
      skuCode: b.skus[0]?.skuCode ?? null,
      skuColorName: b.skus[0]?.skuColorName ?? null,
      skuSpec: b.skus[0]?.skuSpec ?? null,
      source: b.source,
      usageQty: b.usageQty ?? 1,
      unit: b.unit,
      lossPct: b.lossPct,
      price: b.refPrice,
      priceTaxIncluded: b.priceTaxIncluded,
      priceFreightIncluded: b.priceFreightIncluded,
      counted: b.source === 'PURCHASE',
      cost: b.unitCost ?? 0,
      devCost: null,
    }))
  const fabric = Number(fabricRows.reduce((s, r) => s + r.cost, 0).toFixed(2))
  const trim = Number(trimRows.reduce((s, r) => s + r.cost, 0).toFixed(2))
  const other = 5
  const total = Number((fabric + trim + p.laborTotal + other).toFixed(2))
  const cost: TechPackCost = {
    techPackId: p.id,
    baseSize: p.baseSize,
    colorId,
    costMode: 'OUTSOURCE',
    buyPrice: null,
    buyPriceDefault: 108,
    defaultFactoryId: 3,
    defaultFactoryName: '示例制衣一厂',
    fabricRows,
    trimRows,
    laborTotal: p.laborTotal,
    processCount: p.processes.length,
    otherItems: [{ id: 1, seq: 1, name: '整烫挂装包材费', amount: 5, remark: null, devAmount: 5 }],
    summary: { fabric, trim, labor: p.laborTotal, other, total },
    tagPrice: 499,
    ratio: total > 0 ? Number((499 / total).toFixed(2)) : null,
    mixedBasis: false,
    dev: p.sourcePackId ? { packId: p.sourcePackId, version: p.sourceVersion ?? 1, summary: { fabric: 48.44, trim: 5, labor: 34, other: 5, total: 92.44 } } : null,
  }
  return cost
})

mock('get', '/api/tech-packs/:id/logs', () => [
  { id: 1, action: 'publish', fromStatus: 'DRAFT', toStatus: 'EFFECTIVE', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-12 09:30:00' },
  { id: 2, action: 'create', fromStatus: null, toStatus: 'DRAFT', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-11 16:00:00' },
])

mock('get', '/api/tech-packs/:id/pdf', () => new Blob(['%PDF-1.4 Lingmao TechPack Craft Sheet'], { type: 'application/pdf' }))

mock('post', '/api/tech-packs/:id/revise', ({ params }) => {
  const src = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  const maxVer = Math.max(0, ...packs.filter((p) => p.styleId === src.styleId && p.stage === src.stage).map((p) => p.version))
  const copy = structuredClone(src)
  copy.id = ++packIdSeq
  copy.version = maxVer + 1
  copy.status = 'DRAFT'
  copy.versionNote = ''
  copy.publishTime = null
  copy.versionLock = 1
  copy.actions = ['edit', 'remove', 'publish']
  packs.push(copy)
  return copy.id
})

mock('post', '/api/tech-packs/:id/to-prod', ({ params }) => {
  const src = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  const maxVer = Math.max(0, ...packs.filter((p) => p.styleId === src.styleId && p.stage === 'PROD').map((p) => p.version))
  const copy = structuredClone(src)
  copy.id = ++packIdSeq
  copy.stage = 'PROD'
  copy.version = maxVer + 1
  copy.status = 'DRAFT'
  copy.sourcePackId = src.id
  copy.sourceVersion = src.version
  copy.publishTime = null
  copy.versionLock = 1
  copy.actions = ['edit', 'remove', 'publish']
  packs.push(copy)
  return copy.id
})

mock('post', '/api/tech-packs/:id/publish', ({ params }) => {
  const p = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  if (p.version > 1 && !p.versionNote?.trim()) {
    fail(1000, '资料包校验未通过', [{ row: 0, message: 'V2 及以上修订版本生效前必须填写版本说明' }])
  }
  for (const other of packs) {
    if (other.styleId === p.styleId && other.stage === p.stage && other.status === 'EFFECTIVE') {
      other.status = 'VOIDED'
    }
  }
  p.status = 'EFFECTIVE'
  p.publishByName = '管理员'
  p.publishTime = '2026-11-15 21:00:00'
  return null
})

mock('get', '/api/tech-packs/:id', ({ params }) => {
  const p = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  p.actions = computeActions(p)
  return p
})

mock('put', '/api/tech-packs/:id', ({ params, body }) => {
  const p = packs.find((x) => x.id === Number(params.id)) ?? fail(404, '资料包不存在')
  const form = body as TechPackSaveForm
  p.versionNote = form.versionNote
  p.utilPct = form.utilPct
  p.packingMode = form.packingMode
  p.specPoints = form.specPoints.map((sp, i) => ({ id: i + 1, seq: i + 1, ...sp }))
  // 物料清单、工艺指示也存下来（保存后重新 get 要看得到改的）。名称、SKU 码从各版本已有的行里找，找不到的写个占位
  const knownItems = new Map(packs.flatMap((x) => x.bomItems).map((b) => [b.materialId, b]))
  const knownSkus = new Map(packs.flatMap((x) => x.bomItems.flatMap((b) => b.skus)).map((s) => [s.materialSkuId, s]))
  p.bomItems = form.bomItems.map((b, i) => {
    const known = knownItems.get(b.materialId)
    const base = b.usageMode === 'UNIFORM' ? b.usageQty : b.sizeUsages[p.baseSize]
    return {
      id: 1000 + i,
      seq: i + 1,
      ...b,
      materialCode: known?.materialCode ?? `ML${String(b.materialId).padStart(4, '0')}`,
      materialName: known?.materialName ?? `物料 ${b.materialId}`,
      materialType: known?.materialType ?? 'TRIM',
      materialStatus: 1,
      unit: known?.unit ?? 'PCS',
      supplierName: b.supplierId == null ? null : (known?.supplierName ?? `供应商 ${b.supplierId}`),
      skus: b.skus.map((s) => {
        const sku = knownSkus.get(s.materialSkuId)
        const color = p.colors.find((c) => c.id === s.colorId)
        return {
          ...s,
          colorCode: color?.code ?? null,
          colorName: color?.name ?? null,
          colorHex: color?.hex ?? null,
          skuCode: sku?.skuCode ?? `SKU-${s.materialSkuId}`,
          skuColorName: sku?.skuColorName ?? null,
          skuSpec: sku?.skuSpec ?? null,
          skuStatus: 1 as const,
          image: null,
        }
      }),
      unitCost: b.source === 'PURCHASE' && base != null && b.refPrice != null ? Math.round(base * (1 + b.lossPct / 100) * b.refPrice * 100) / 100 : null,
      image: null,
    }
  })
  p.craftSections = form.craftSections.map((cs) => ({
    section: cs.section,
    content: cs.content ?? '',
    images: cs.images.map((f, i) => ({ id: i + 1, url: f.url, thumbUrl: f.thumbUrl, name: f.name, size: f.size })),
  }))
  p.processes = form.processes.map((pr, i) => ({ id: i + 1, seq: i + 1, ...pr }))
  p.laborTotal = p.processes.reduce((s, r) => s + (r.price ?? 0), 0)
  p.stdSecondsTotal = p.processes.reduce((s, r) => s + (r.stdSeconds ?? 0), 0)
  p.versionLock += 1
  return p.versionLock
})

mock('delete', '/api/tech-packs/:id', ({ params }) => {
  const idx = packs.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) packs.splice(idx, 1)
  return null
})
