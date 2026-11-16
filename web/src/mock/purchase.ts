import type { DocNotice } from '@/types/biz'
import type {
  FollowForm,
  MrpCalcDetail,
  MrpCalcRow,
  MrpItemForm,
  OrderKitting,
  PendingArrivalRow,
  PurchaseDefaults,
  PurchaseDetail,
  PurchaseForm,
  PurchaseRow,
  ReorderRow,
  ReservePct,
  SkuSupply,
} from '@/types/purchase'
import { fail, mock, page } from './index'

/**
 * 算料、采购的假接口（docs/api/03）。为了在页面上看得到几种出错的样子：
 * - 新算的草稿第一次"确认"回 1000"可用量已变化"，重新算料以后才能确认
 * - 状态动作、改格子的 version 对不上回 1002
 * - 结算方式没填的采购单"提交"回 1000 + 清单（新建页"提交审核"失败的样子）
 * - 供应商不是合作中的行"生成采购单"回 1000
 * 金额、名称都是编的
 */

/** 1002 的样子：版本对不上 */
function checkVersion(current: number, body: unknown) {
  const v = (body as { version?: number } | null)?.version
  if (v !== undefined && v !== current) fail(1002, '单据状态变了（别人刚改过），已重新取')
}

/** 变更提醒：按单据存，处理了记下处理人 */
function noticeStore(seed: Record<number, DocNotice[]>) {
  const store = new Map<number, DocNotice[]>(Object.entries(seed).map(([k, v]) => [Number(k), v]))
  return {
    list: (docId: number) => store.get(docId) ?? [],
    handle(docId: number, noticeId: number, remark: string | null): number {
      const n = store.get(docId)?.find((x) => x.id === noticeId) ?? fail(404, '提醒不存在')
      n.handledByName = '管理员'
      n.handledTime = '2026-11-16 10:00:00'
      n.handleRemark = remark
      return (store.get(docId) ?? []).filter((x) => !x.handledTime).length
    },
  }
}

const changeSource = {
  type: 'ORDER_CHANGE',
  kind: null,
  parentId: null,
  typeLabel: '订单变更单',
  id: 201,
  no: 'BG202611150001',
  status: 'APPROVED',
  statusLabel: '已审核',
  statusTone: 'active',
  ownerId: 1,
  ownerName: '管理员',
} as const

let reservePcts: ReservePct[] = [
  { materialType: 'FABRIC', label: '面料', pct: 2 },
  { materialType: 'LINING', label: '里料', pct: 2 },
  { materialType: 'TRIM', label: '辅料', pct: 3 },
  { materialType: 'PACKING', label: '包材', pct: 1 },
]

let mrpSeq = 310
const mrps: MrpCalcDetail[] = [
  {
    id: 301,
    no: 'SL202611130001',
    status: 'CONFIRMED',
    version: 2,
    companyId: 1,
    companyName: '示例服饰有限公司',
    remark: '首单大货面辅料核算',
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-13 09:20:00',
    updateTime: '2026-11-13 10:00:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-13 10:00:00',
    actions: ['edit', 'recalc', 'close', 'toPurchase'],
    approval: null,
    pendingNotices: 1,
    orders: [{ orderId: 101, orderNo: 'DD202611150001', styleCodes: ['FS2611150101'] }],
    styleCodes: ['FS2611150101'],
    needDate: '2026-11-18',
    prevCalcId: null,
    prevCalcNo: null,
    nextCalcId: null,
    nextCalcNo: null,
    closeReason: null,
    closeByName: null,
    closeTime: null,
    estAmount: 21406,
    convertedAmount: 13900,
    diff: null,
    items: [
      {
        id: 3001,
        seq: 1,
        block: 'PURCHASE',
        materialSkuId: 101,
        skuCode: 'ML0001-KQ',
        materialId: 1,
        materialCode: 'ML0001',
        materialName: '220g 精梳斜纹棉布',
        materialType: 'FABRIC',
        colorName: '卡其',
        colorHex: '#c3b091',
        spec: '150cm',
        stockUnit: 'METER',
        image: null,
        supplierId: 1,
        supplierName: '示例面料厂',
        supplierStatus: 'ACTIVE',
        grossQty: 667.44,
        reservePct: 2,
        needQty: 680.79,
        availStockQty: 180,
        useStockQty: 180,
        availTransitQty: 0,
        useTransitQty: 0,
        lateTransitQty: 0,
        netQty: 500.79,
        purchaseUnit: 'METER',
        conversionFactor: 1,
        suggestQty: 510,
        refPrice: 27.8,
        estAmount: 14178,
        convertedQty: 500,
        receivedQty: 300,
        shortQty: 200.79,
        orderNos: ['DD202611150001'],
        styleCodes: ['FS2611150101'],
        transitUses: [],
        lateTransits: [],
        canConvert: true,
      },
      {
        id: 3002,
        seq: 2,
        block: 'PURCHASE',
        materialSkuId: 201,
        skuCode: 'FL0008-BR-20MM',
        materialId: 2,
        materialCode: 'FL0008',
        materialName: '20mm 复古牛角纹树脂四眼扣',
        materialType: 'TRIM',
        colorName: '深咖',
        colorHex: '#4e3629',
        spec: '20mm',
        stockUnit: 'BEAD',
        image: null,
        supplierId: 2,
        supplierName: '示例辅料厂',
        supplierStatus: 'ACTIVE',
        grossQty: 4896,
        reservePct: 3,
        needQty: 5042.88,
        availStockQty: 600,
        useStockQty: 600,
        availTransitQty: 0,
        useTransitQty: 0,
        lateTransitQty: 0,
        netQty: 4442.88,
        purchaseUnit: 'BEAD',
        conversionFactor: 1,
        suggestQty: 4500,
        refPrice: 0.65,
        estAmount: 2925,
        convertedQty: 0,
        receivedQty: 0,
        shortQty: 4442.88,
        orderNos: ['DD202611150001'],
        styleCodes: ['FS2611150101'],
        transitUses: [],
        lateTransits: [
          { purchaseItemId: 4009, purchaseOrderId: 401, purchaseNo: 'CG202611130001', purchaseType: 'STOCK', eta: '2026-11-25', freeQty: 1000 },
        ],
        canConvert: true,
      },
      {
        id: 3003,
        seq: 3,
        block: 'PURCHASE',
        materialSkuId: 301,
        skuCode: 'FL0012-BK',
        materialId: 3,
        materialCode: 'FL0012',
        materialName: '5 号树脂拉链 60cm',
        materialType: 'TRIM',
        colorName: '黑',
        colorHex: '#222222',
        spec: '60cm',
        stockUnit: 'PCS',
        image: null,
        // 没选供应商：生成采购单前要拦下来、标红
        supplierId: null,
        supplierName: null,
        supplierStatus: null,
        grossQty: 612,
        reservePct: 3,
        needQty: 630.36,
        availStockQty: 0,
        useStockQty: 0,
        availTransitQty: 200,
        useTransitQty: 200,
        lateTransitQty: 0,
        netQty: 430.36,
        purchaseUnit: 'PCS',
        conversionFactor: 1,
        suggestQty: 440,
        refPrice: 1.2,
        estAmount: 528,
        convertedQty: 0,
        receivedQty: 0,
        shortQty: 430.36,
        orderNos: ['DD202611150001'],
        styleCodes: ['FS2611150101'],
        transitUses: [
          { purchaseItemId: 4010, purchaseOrderId: 401, purchaseNo: 'CG202611130001', purchaseType: 'STOCK', eta: '2026-11-16', qty: 200, arrivedQty: 0 },
        ],
        lateTransits: [],
        canConvert: true,
      },
      {
        id: 3004,
        seq: 4,
        block: 'FACTORY',
        materialSkuId: 401,
        skuCode: 'FZ0001',
        materialId: 4,
        materialCode: 'FZ0001',
        materialName: '涤纶缝纫线 40S/2',
        materialType: 'TRIM',
        colorName: null,
        colorHex: null,
        spec: null,
        stockUnit: 'CONE',
        image: null,
        supplierId: null,
        supplierName: null,
        supplierStatus: null,
        grossQty: 36,
        reservePct: 0,
        needQty: 36,
        availStockQty: 0,
        useStockQty: 0,
        availTransitQty: 0,
        useTransitQty: 0,
        lateTransitQty: 0,
        netQty: 0,
        purchaseUnit: 'CONE',
        conversionFactor: 1,
        suggestQty: 0,
        refPrice: null,
        estAmount: null,
        convertedQty: 0,
        receivedQty: 0,
        shortQty: 0,
        orderNos: ['DD202611150001'],
        styleCodes: ['FS2611150101'],
        transitUses: [],
        lateTransits: [],
        canConvert: false,
      },
    ],
  },
]

/** 新算出来的草稿：第一次确认回"可用量已变化"，重新算料后才行 */
const staleCalcIds = new Set<number>()
const mrpNotices = noticeStore({
  301: [
    {
      id: 91,
      source: changeSource,
      content: '需求订单 DD202611150001 的 FS2611150101 卡其 M 从 140 件改成 160 件',
      createTime: '2026-11-15 10:00:00',
      handledByName: null,
      handledTime: null,
      handleRemark: null,
    },
  ],
})
const findMrp = (id: string | undefined) => mrps.find((x) => x.id === Number(id)) ?? fail(404, '算料单不存在')

// 算料静态接口
mock('get', '/api/mrp-calcs/reserve-pcts', () => reservePcts)
mock('put', '/api/mrp-calcs/reserve-pcts', ({ body }) => {
  const incoming = body as { materialType: string; pct: number }[]
  for (const r of incoming) {
    const hit = reservePcts.find((x) => x.materialType === r.materialType)
    if (hit) hit.pct = r.pct
  }
  return null
})
mock('get', '/api/mrp-calcs/order-options', ({ query }) =>
  page(
    [
      {
        orderId: 101,
        orderNo: 'DD202611150001',
        orderType: 'STOCK',
        styleCodes: ['FS2611150101'],
        totalQty: 600,
        commitDate: '2026-12-05',
        calcs: [{ id: 301, no: 'SL202611130001', status: 'CONFIRMED' as const }],
      },
    ],
    query,
  ),
)

mock('get', '/api/mrp-calcs', ({ query }) => {
  const rows: MrpCalcRow[] = mrps.map((m) => {
    const buyRows = m.items.filter((i) => i.block === 'PURCHASE' && i.suggestQty > 0)
    return {
      id: m.id,
      no: m.no,
      status: m.status,
      orderNos: m.orders.map((o) => o.orderNo),
      styleCodes: m.styleCodes,
      buyCount: buyRows.length,
      convertedCount: buyRows.filter((i) => i.convertedQty >= i.suggestQty).length,
      needDate: m.needDate,
      nextCalcId: m.nextCalcId,
      nextCalcNo: m.nextCalcNo,
      createByName: m.createByName,
      createTime: m.createTime,
    }
  })
  return page(rows, query)
})

/** 复制一张草稿：选订单算料、重新算料（已确认的另算一张）都用它 */
function newDraft(from: MrpCalcDetail): MrpCalcDetail {
  const id = ++mrpSeq
  const copy = structuredClone(from)
  copy.id = id
  copy.no = `SL20261116${String(id).padStart(4, '0')}`
  copy.status = 'DRAFT'
  copy.version = 1
  copy.pendingNotices = 0
  copy.prevCalcId = null
  copy.prevCalcNo = null
  copy.nextCalcId = null
  copy.nextCalcNo = null
  copy.diff = null
  copy.auditBy = null
  copy.auditByName = null
  copy.auditTime = null
  copy.actions = ['edit', 'confirm', 'recalc', 'remove', 'toPurchase']
  for (const it of copy.items) {
    it.convertedQty = 0
    it.receivedQty = 0
  }
  mrps.unshift(copy)
  return copy
}

mock('post', '/api/mrp-calcs', () => {
  const copy = newDraft(mrps[mrps.length - 1]!)
  staleCalcIds.add(copy.id)
  return { id: copy.id, no: copy.no, version: copy.version }
})

mock('put', '/api/mrp-calcs/:id/items/:itemId', ({ params, body }) => {
  const m = findMrp(params.id)
  const it = m.items.find((x) => x.id === Number(params.itemId)) ?? fail(404, '算料行不存在')
  const f = body as MrpItemForm
  checkVersion(m.version, body)
  if (f.useStockQty > Math.min(it.availStockQty, it.needQty)) fail(400, `本次用库存不能超过 ${Math.min(it.availStockQty, it.needQty)}`)
  if (f.supplierId !== it.supplierId) {
    it.supplierName = f.supplierId ? `供应商 ${f.supplierId}` : null
    it.supplierStatus = f.supplierId ? 'ACTIVE' : null
  }
  it.supplierId = f.supplierId
  it.useStockQty = f.useStockQty
  it.useTransitQty = f.useTransitQty
  it.netQty = Math.max(0, Number((it.needQty - it.useStockQty - it.useTransitQty).toFixed(2)))
  it.suggestQty = f.suggestQty
  it.estAmount = it.refPrice != null ? Number((it.suggestQty * it.refPrice).toFixed(2)) : null
  m.version += 1
  return m
})

mock('delete', '/api/mrp-calcs/:id/items/:itemId', ({ params }) => {
  const m = mrps.find((x) => x.id === Number(params.id)) ?? fail(404, '算料单不存在')
  m.items = m.items.filter((x) => x.id !== Number(params.itemId))
  m.version += 1
  return m
})

mock('put', '/api/mrp-calcs/:id/remark', ({ params, body }) => {
  const m = mrps.find((x) => x.id === Number(params.id)) ?? fail(404, '算料单不存在')
  m.remark = (body as { remark: string | null }).remark
  m.version += 1
  return null
})

mock('post', '/api/mrp-calcs/:id/confirm', ({ params, body }) => {
  const m = findMrp(params.id)
  checkVersion(m.version, body)
  if (staleCalcIds.has(m.id)) fail(1000, '可用量已变化，请重新计算')
  m.status = 'CONFIRMED'
  m.auditByName = '管理员'
  m.auditTime = '2026-11-16 10:00:00'
  // 重新算料出来的新单确认了：上一张自动关闭
  const prev = mrps.find((x) => x.id === m.prevCalcId)
  if (prev) {
    prev.status = 'CLOSED'
    prev.closeReason = 'RECALC'
    prev.actions = []
    prev.version += 1
  }
  m.actions = ['edit', 'recalc', 'close', 'toPurchase']
  m.version += 1
  return null
})

mock('post', '/api/mrp-calcs/:id/close', ({ params, body }) => {
  const m = findMrp(params.id)
  checkVersion(m.version, body)
  m.status = 'CLOSED'
  m.closeByName = '管理员'
  m.closeTime = '2026-11-16 10:00:00'
  m.closeReason = 'MANUAL'
  m.actions = []
  m.version += 1
  return null
})

mock('post', '/api/mrp-calcs/:id/recalc', ({ params, body }) => {
  const m = findMrp(params.id)
  checkVersion(m.version, body)
  staleCalcIds.delete(m.id)
  if (m.status === 'DRAFT') {
    m.version += 1
    return { id: m.id, no: m.no, version: m.version }
  }
  // 已确认的另算一张：卡其面料多了 20 件的量，扣子没变
  const next = newDraft(m)
  next.prevCalcId = m.id
  next.prevCalcNo = m.no
  next.diff = m.items
    .filter((it) => it.block === 'PURCHASE')
    .slice(0, 1)
    .map((it) => ({ ...it, fromNeedQty: it.needQty, toNeedQty: it.needQty + 22.7, diffQty: 22.7 }))
  m.nextCalcId = next.id
  m.nextCalcNo = next.no
  m.actions = m.actions.filter((a) => a !== 'recalc' && a !== 'toPurchase')
  m.version += 1
  return { id: next.id, no: next.no, version: next.version }
})

mock('get', '/api/mrp-calcs/:id/notices', ({ params }) => mrpNotices.list(Number(params.id)))
mock('post', '/api/mrp-calcs/:id/notices/:nid/handle', ({ params, body }) => {
  const m = findMrp(params.id)
  m.pendingNotices = mrpNotices.handle(m.id, Number(params.nid), (body as { remark: string | null }).remark)
  return null
})
mock('get', '/api/mrp-calcs/:id/references', () => [
  { type: 'PURCHASE_ORDER', kind: null, parentId: null, typeLabel: '采购单', id: 401, no: 'CG202611130001', status: 'RUNNING', statusLabel: '部分到货', statusTone: 'progress', ownerId: 1, ownerName: '管理员' },
])
mock('get', '/api/mrp-calcs/:id/logs', () => [
  { id: 1, action: 'confirm', fromStatus: 'DRAFT', toStatus: 'CONFIRMED', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-13 10:00:00' },
])
mock('get', '/api/mrp-calcs/:id', ({ params }) => mrps.find((x) => x.id === Number(params.id)) ?? fail(404, '算料单不存在'))
mock('delete', '/api/mrp-calcs/:id', ({ params }) => {
  const idx = mrps.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) mrps.splice(idx, 1)
  return null
})

// 采购单
let poSeq = 410
const pos: PurchaseDetail[] = [
  {
    id: 401,
    no: 'CG202611130001',
    status: 'RUNNING',
    version: 2,
    companyId: 1,
    companyName: '示例服饰有限公司',
    remark: '头缸大货斜纹棉，请附检测报告送货',
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-13 11:00:00',
    updateTime: '2026-11-14 15:00:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-13 11:30:00',
    actions: ['receive', 'return', 'close'],
    approval: null,
    pendingNotices: 1,
    supplierId: 1,
    supplierCode: 'SUP001',
    supplierName: '示例面料有限公司',
    supplierShortName: '示例面料厂',
    supplierStatus: 'ACTIVE',
    supplierContact: '陈经理',
    supplierPhone: '13000000001',
    purchaseType: 'ORDER',
    calcId: 301,
    calcNo: 'SL202611130001',
    buyerId: 1,
    buyerName: '管理员',
    orderDate: '2026-11-13',
    deliveryDate: '2026-11-17',
    warehouseId: 1,
    warehouseName: '总公司面辅料一仓',
    warehouseType: 'MATERIAL',
    warehouseAddress: '示例市示例区仓储路 1 号',
    settleMethod: 'MONTHLY',
    taxPct: 13,
    taxIncluded: true,
    freightIncluded: true,
    totalQty: 500,
    totalAmount: 13900,
    receivedQty: 300,
    returnedQty: 0,
    planStyles: [],
    items: [
      {
        id: 4001,
        seq: 1,
        materialSkuId: 101,
        skuCode: 'ML0001-KQ',
        materialId: 1,
        materialCode: 'ML0001',
        materialName: '220g 精梳斜纹棉布',
        materialType: 'FABRIC',
        colorName: '卡其',
        colorHex: '#c3b091',
        spec: '150cm',
        stockUnit: 'METER',
        image: null,
        supplierItemNo: 'JW-220T',
        supplierColorNo: 'C#18',
        purchaseUnit: 'METER',
        conversionFactor: 1,
        qty: 500,
        price: 27.8,
        amount: 13900,
        priceSourceId: 1,
        archivePrice: 27.8,
        priceChangeReason: null,
        lineDueDate: null,
        dueDate: '2026-11-17',
        calcItemId: 3001,
        calcId: 301,
        calcNo: 'SL202611130001',
        orderNos: ['DD202611150001'],
        styleCodes: ['FS2611150101'],
        receivedQty: 300,
        returnedQty: 0,
        pendingQty: 200,
        expectDate: '2026-11-17',
        followNote: '染厂定型中，预计周二下午送达',
      },
    ],
    stockDocs: [
      {
        id: 601,
        no: 'RK202611140001',
        kind: 'IN',
        docType: 'PURCHASE_IN',
        status: 'APPROVED',
        docDate: '2026-11-14',
        warehouseName: '总公司面辅料一仓',
        purchaseQty: 300,
        dyeLots: ['G2611-A01'],
        logisticsNo: 'SF123456789',
      },
    ],
  },
]

const poNotices = noticeStore({
  401: [
    {
      id: 92,
      source: changeSource,
      content: '需求订单 DD202611150001 加了 20 件，卡其面料要多 22.7 米',
      createTime: '2026-11-15 10:00:00',
      handledByName: null,
      handledTime: null,
      handleRemark: null,
    },
  ],
})

/** 默认值：每个 SKU 都回一条，单价按供应商不同（供应商 2 贵一成），第一次报价（没有档案）的写 null */
mock('get', '/api/purchase-orders/defaults', ({ query }) => {
  const raw = query.skuIds
  const skuIds = (Array.isArray(raw) ? raw : raw ? [raw] : []).map(Number)
  const supplierId = Number(query.supplierId)
  const factor = supplierId === 2 ? 1.1 : 1
  const def: PurchaseDefaults = {
    settleMethod: supplierId === 2 ? null : 'MONTHLY',
    taxPct: supplierId === 2 ? null : 13,
    buyerId: 1,
    buyerName: '管理员',
    deliveryDate: skuIds.length ? '2026-11-23' : null,
    lines: skuIds.map((skuId) => {
      const base = skuId === 101 ? 27.8 : skuId === 201 ? 0.65 : 1.2
      const price = Number((base * factor).toFixed(4))
      return {
        materialSkuId: skuId,
        price,
        priceSourceId: skuId === 101 ? 1 : null,
        archivePrice: skuId === 101 ? price : null,
        purchaseUnit: skuId === 101 ? 'METER' : 'PCS',
        conversionFactor: 1,
        leadDays: 7,
        supplierItemNo: null,
        supplierColorNo: null,
      }
    }),
  }
  return def
})

mock('post', '/api/purchase-orders/from-mrp', ({ body }) => {
  const f = body as { calcId: number; itemIds: number[]; version: number }
  const m = findMrp(String(f.calcId))
  checkVersion(m.version, body)
  const items = m.items.filter((it) => f.itemIds.includes(it.id))
  const bad = items.filter((it) => !it.supplierId || it.supplierStatus !== 'ACTIVE')
  if (bad.length) fail(1000, `${bad.map((it) => it.skuCode).join('、')} 没选供应商或供应商不是合作中`)
  for (const it of items) {
    it.convertedQty = it.suggestQty
    it.canConvert = false
  }
  m.version += 1
  const id = ++poSeq
  const no = `CG20261115${String(id).padStart(4, '0')}`
  pos.unshift({
    ...structuredClone(pos[0]!),
    id,
    no,
    status: 'DRAFT',
    version: 1,
    receivedQty: 0,
    actions: ['edit', 'remove', 'submit', 'void'],
    stockDocs: [],
  })
  return [{ id, no, version: 1 }]
})

mock('post', '/api/purchase-orders/from-reorder', () => {
  const id = ++poSeq
  const no = `CG20261115${String(id).padStart(4, '0')}`
  pos.unshift({
    ...structuredClone(pos[0]!),
    id,
    no,
    purchaseType: 'STOCK',
    status: 'DRAFT',
    version: 1,
    receivedQty: 0,
    actions: ['edit', 'remove', 'submit', 'void'],
    stockDocs: [],
  })
  return [{ id, no, version: 1 }]
})

mock('get', '/api/purchase-orders/pending-items/counts', () => ({ all: 1, late: 0, near: 1, cut: 0 }))
mock('get', '/api/purchase-orders/pending-items', ({ query }) => {
  const rows: PendingArrivalRow[] = [
    {
      purchaseItemId: 4001,
      purchaseOrderId: 401,
      purchaseNo: 'CG202611130001',
      purchaseType: 'ORDER',
      supplierId: 1,
      supplierName: '示例面料厂',
      buyerId: 1,
      buyerName: '管理员',
      warehouseId: 1,
      warehouseName: '总公司面辅料一仓',
      materialSkuId: 101,
      skuCode: 'ML0001-KQ',
      materialId: 1,
      materialCode: 'ML0001',
      materialName: '220g 精梳斜纹棉布',
      materialType: 'FABRIC',
      colorName: '卡其',
      colorHex: '#c3b091',
      spec: '150cm',
      stockUnit: 'METER',
      image: null,
      purchaseUnit: 'METER',
      qty: 500,
      receivedQty: 300,
      returnedQty: 0,
      pendingQty: 200,
      dueDate: '2026-11-17',
      expectDate: '2026-11-17',
      followNote: '染厂定型中，预计周二下午送达',
      arriveDate: '2026-11-17',
      daysLeft: 2,
      risk: 'NEAR',
      needDate: '2026-11-18',
      cutLateDays: 0,
      orderNos: ['DD202611150001'],
      styleCodes: ['FS2611150101'],
      calcNo: 'SL202611130001',
      actions: ['receive', 'follow'],
    },
  ]
  return page(rows, query)
})

mock('get', '/api/purchase-orders/export', () => new Blob(['采购单号,供应商,金额\n'], { type: 'text/csv' }))

mock('get', '/api/purchase-orders', ({ query }) => {
  const rows: PurchaseRow[] = pos.map((p) => ({
    id: p.id,
    no: p.no,
    supplierId: p.supplierId,
    supplierName: p.supplierShortName,
    supplierStatus: p.supplierStatus,
    purchaseType: p.purchaseType,
    firstItem: p.items[0] ?? null,
    itemCount: p.items.length,
    totalQty: p.totalQty,
    totalAmount: p.totalAmount,
    orderDate: p.orderDate,
    deliveryDate: p.deliveryDate,
    warehouseId: p.warehouseId,
    warehouseName: p.warehouseName,
    receivedQty: p.receivedQty - p.returnedQty,
    buyerId: p.buyerId,
    buyerName: p.buyerName,
    companyName: p.companyName,
    status: p.status,
    version: p.version,
    actions: p.actions,
    createTime: p.createTime,
  }))
  return page(rows, query)
})

mock('post', '/api/purchase-orders', ({ body }) => {
  const f = body as PurchaseForm
  const id = ++poSeq
  const no = `CG20261115${String(id).padStart(4, '0')}`
  pos.unshift({
    ...structuredClone(pos[0]!),
    id,
    no,
    purchaseType: f.purchaseType,
    orderDate: f.orderDate,
    deliveryDate: f.deliveryDate ?? '2026-11-25',
    settleMethod: f.settleMethod,
    taxPct: f.taxPct,
    remark: f.remark,
    pendingNotices: 0,
    returnedQty: 0,
    status: 'DRAFT',
    version: 1,
    receivedQty: 0,
    actions: ['edit', 'remove', 'submit', 'void'],
    stockDocs: [],
  })
  return { id, no, version: 1 }
})

mock('put', '/api/purchase-orders/:id/items/:itemId/follow', ({ params, body }) => {
  const po = pos.find((x) => x.id === Number(params.id))
  const it = po?.items.find((x) => x.id === Number(params.itemId))
  const f = body as FollowForm
  if (it) {
    it.expectDate = f.expectDate
    it.followNote = f.followNote
  }
  return null
})

mock('post', '/api/purchase-orders/:id/notices/:nid/handle', ({ params, body }) => {
  const p = pos.find((x) => x.id === Number(params.id)) ?? fail(404, '采购单不存在')
  p.pendingNotices = poNotices.handle(p.id, Number(params.nid), (body as { remark: string | null }).remark)
  return null
})

mock('post', '/api/purchase-orders/:id/:act', ({ params, body }) => {
  const p = pos.find((x) => x.id === Number(params.id)) ?? fail(404, '采购单不存在')
  const act = params.act
  checkVersion(p.version, body)
  if (act === 'submit') {
    const problems = [!p.settleMethod && '结算方式没填', p.taxPct === null && '税率没填'].filter((x): x is string => !!x)
    if (problems.length) fail(1000, '提交检查没过', problems)
    p.status = 'PENDING'
    p.actions = ['audit', 'reject', 'withdraw', 'void']
  } else if (act === 'audit') {
    p.status = 'APPROVED'
    p.actions = ['receive', 'unaudit', 'close']
  } else if (act === 'withdraw' || act === 'reject' || act === 'unaudit') {
    p.status = 'DRAFT'
    p.actions = ['edit', 'remove', 'submit', 'void']
  } else if (act === 'close') {
    p.status = 'CLOSED'
    p.actions = ['reopen']
  } else if (act === 'reopen') {
    p.status = p.receivedQty > 0 ? 'RUNNING' : 'APPROVED'
    p.actions = ['receive', 'return', 'close']
  } else if (act === 'void') {
    p.status = 'VOIDED'
    p.actions = []
  }
  p.version += 1
  return null
})

mock('get', '/api/purchase-orders/:id/notices', ({ params }) => poNotices.list(Number(params.id)))
mock('get', '/api/purchase-orders/:id/attachments', () => [])
mock('put', '/api/purchase-orders/:id/attachments', ({ body }) => body)
mock('get', '/api/purchase-orders/:id/logs', () => [
  { id: 1, action: 'audit', fromStatus: 'PENDING', toStatus: 'APPROVED', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-13 11:30:00' },
])
mock('get', '/api/purchase-orders/:id', ({ params }) => pos.find((x) => x.id === Number(params.id)) ?? fail(404, '采购单不存在'))
mock('put', '/api/purchase-orders/:id', ({ params, body }) => {
  const p = pos.find((x) => x.id === Number(params.id)) ?? fail(404, '采购单不存在')
  checkVersion(p.version, body)
  const f = body as PurchaseForm
  p.settleMethod = f.settleMethod
  p.taxPct = f.taxPct
  p.remark = f.remark
  p.version += 1
  return p.version
})
mock('delete', '/api/purchase-orders/:id', ({ params }) => {
  const idx = pos.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) pos.splice(idx, 1)
  return null
})

// 齐料、补货提醒与物料在途
mock('get', '/api/order-kitting', ({ query }) => {
  const raw = query.orderIds
  const ids = (Array.isArray(raw) ? raw : raw ? [raw] : []).map(Number)
  const list: OrderKitting[] = ids.map((oid) => ({
    orderId: oid,
    status: 'PARTIAL',
    readyCount: 1,
    totalCount: 2,
    label: '部分到料 1/2',
    calcIds: [301],
  }))
  return list
})

mock('get', '/api/reorder-items', () => {
  const list: ReorderRow[] = [
    {
      materialSkuId: 201,
      skuCode: 'FL0008-BR-20MM',
      materialId: 2,
      materialCode: 'FL0008',
      materialName: '20mm 复古牛角纹树脂四眼扣',
      materialType: 'TRIM',
      colorName: '深咖',
      colorHex: '#4e3629',
      spec: '20mm',
      stockUnit: 'BEAD',
      image: null,
      defaultSupplierId: 2,
      defaultSupplierName: '示例辅料厂',
      defaultSupplierStatus: 'ACTIVE',
      stockQty: 600,
      transitQty: 0,
      heldQty: 600,
      safetyStock: 2000,
      gapQty: 2000,
      purchaseUnit: 'BEAD',
      conversionFactor: 1,
      suggestQty: 2000,
      price: 0.65,
      openDrafts: [],
    },
  ]
  return list
})

mock('get', '/api/material-supply', () => {
  const list: SkuSupply[] = [
    { materialSkuId: 101, transits: [{ warehouseId: 1, qty: 200 }], heldQty: 180, safetyStock: 100, belowSafety: false },
    { materialSkuId: 201, transits: [], heldQty: 600, safetyStock: 2000, belowSafety: true },
  ]
  return list
})
