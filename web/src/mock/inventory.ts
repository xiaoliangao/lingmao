import type {
  CountCreateForm,
  CountDetail,
  CountForm,
  CountRow,
  InvDocCreateForm,
  InvDocDetail,
  InvDocDraft,
  InvDocFromSourceReq,
  InvDocRow,
  StockLogRow,
  StockRow,
  StockSummaryRow,
} from '@/types/inventory'
import { INV_DOC_TYPE } from '@/types/inventory'
import { fail, mock, page } from './index'

/**
 * 库存的假接口（docs/api/04）。为了在页面上看得到出错的样子：
 * - CK202611150001（其他出库草稿）审核回 1001 库存不足，RK202611150002（采购入库草稿）审核回 1004 超收，都带清单
 * - 状态动作、改草稿的 version 对不上回 1002
 */
function checkVersion(current: number, body: unknown) {
  const v = (body as { version?: number } | null)?.version
  if (v !== undefined && v !== current) fail(1002, '单据状态变了（别人刚改过），已重新取')
}

let invSeq = 610
const invDocs: InvDocDetail[] = [
  {
    id: 601,
    no: 'RK202611140001',
    kind: 'IN',
    docType: 'PURCHASE_IN',
    status: 'APPROVED',
    version: 2,
    companyId: 1,
    companyName: '示例服饰有限公司',
    source: {
      type: 'PURCHASE_ORDER',
      kind: null,
      parentId: null,
      typeLabel: '采购单',
      id: 401,
      no: 'CG202611130001',
      status: 'RUNNING',
      statusLabel: '部分到货',
      statusTone: 'progress',
      ownerId: 1,
      ownerName: '管理员',
    },
    fromWarehouseId: null,
    fromWarehouseName: null,
    fromWarehouseType: null,
    toWarehouseId: 1,
    toWarehouseName: '总公司面辅料一仓',
    toWarehouseType: 'MATERIAL',
    partnerId: 1,
    partnerName: '示例面料厂',
    docDate: '2026-11-14',
    reason: null,
    supplierDeliveryNo: 'SH-20261114-09',
    logisticsNo: 'SF123456789',
    remark: '头批 6 卷验布合格入库',
    totalQty: 300,
    totalAmount: 8340,
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-14 14:20:00',
    updateTime: '2026-11-14 14:25:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-14 14:25:00',
    actions: ['unaudit'],
    approval: null,
    pendingNotices: 0,
    items: [
      {
        id: 6001,
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
        qty: 300,
        purchaseUnit: 'METER',
        purchaseQty: 300,
        conversionFactor: 1,
        price: 27.8,
        amount: 8340,
        dyeLot: 'G2611-A01',
        rolls: 6,
        sourceItemId: 4001,
        overReason: null,
        remark: null,
        fromStockQty: null,
      },
    ],
  },
  {
    id: 602,
    no: 'CK202611140001',
    kind: 'OUT',
    docType: 'ISSUE_OUT',
    status: 'APPROVED',
    version: 2,
    companyId: 1,
    companyName: '示例服饰有限公司',
    source: {
      type: 'PRODUCTION_ORDER',
      kind: 'OUTSOURCE',
      parentId: null,
      typeLabel: '外发加工单',
      id: 501,
      no: 'JG202611130001',
      status: 'RUNNING',
      statusLabel: '部分回货',
      statusTone: 'progress',
      ownerId: 1,
      ownerName: '管理员',
    },
    fromWarehouseId: 1,
    fromWarehouseName: '总公司面辅料一仓',
    fromWarehouseType: 'MATERIAL',
    toWarehouseId: 3,
    toWarehouseName: '示例制衣一厂工厂仓',
    toWarehouseType: 'FACTORY',
    partnerId: 3,
    partnerName: '示例制衣一厂',
    docDate: '2026-11-14',
    reason: null,
    supplierDeliveryNo: null,
    logisticsNo: null,
    remark: '首床发料',
    totalQty: 260,
    totalAmount: 7228,
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-14 16:00:00',
    updateTime: '2026-11-14 16:10:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-14 16:10:00',
    actions: ['unaudit'],
    approval: null,
    pendingNotices: 0,
    items: [
      {
        id: 6002,
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
        qty: 260,
        purchaseUnit: null,
        purchaseQty: null,
        conversionFactor: null,
        price: 27.8,
        amount: 7228,
        dyeLot: 'G2611-A01',
        rolls: 5,
        sourceItemId: null,
        overReason: null,
        remark: null,
        fromStockQty: null,
      },
    ],
  },
]

// 1001、1004 的例子：两张草稿，审核会失败
{
  const out = structuredClone(invDocs[1]!)
  Object.assign(out, {
    id: 603,
    no: 'CK202611150001',
    docType: 'OTHER_OUT',
    status: 'DRAFT',
    version: 1,
    source: null,
    toWarehouseId: null,
    toWarehouseName: null,
    toWarehouseType: null,
    partnerId: null,
    partnerName: null,
    reason: 'OTHER',
    remark: '打样用料',
    totalQty: 500,
    totalAmount: 0,
    auditBy: null,
    auditByName: null,
    auditTime: null,
    actions: ['edit', 'remove', 'audit'],
  })
  out.items = out.items.map((it) => ({ ...it, qty: 500, price: null, amount: null, fromStockQty: 180 }))
  const over = structuredClone(invDocs[0]!)
  Object.assign(over, {
    id: 604,
    no: 'RK202611150002',
    status: 'DRAFT',
    version: 1,
    totalQty: 260,
    auditBy: null,
    auditByName: null,
    auditTime: null,
    actions: ['edit', 'remove', 'audit'],
  })
  over.items = over.items.map((it) => ({ ...it, qty: 260, purchaseQty: 260 }))
  invDocs.push(out, over)
}

// 出入库静态子路径优先注册
mock('get', '/api/inventory-docs/export', () => new Blob(['单号,类型,数量\n'], { type: 'text/csv' }))

mock('get', '/api/inventory-docs/from-purchase/:id', ({ params, query }) => {
  const poId = Number(params.id)
  const docType = (query.docType as 'PURCHASE_IN' | 'PURCHASE_RETURN') ?? 'PURCHASE_IN'
  const draft: InvDocDraft = {
    docType,
    source: {
      type: 'PURCHASE_ORDER',
      kind: null,
      parentId: null,
      typeLabel: '采购单',
      id: poId,
      no: 'CG202611130001',
      status: 'RUNNING',
      statusLabel: '部分到货',
      statusTone: 'progress',
      ownerId: 1,
      ownerName: '管理员',
    },
    partnerId: 1,
    partnerName: '示例面料厂',
    fromWarehouseId: docType === 'PURCHASE_RETURN' ? 1 : null,
    fromWarehouseName: docType === 'PURCHASE_RETURN' ? '总公司面辅料一仓' : null,
    toWarehouseId: docType === 'PURCHASE_IN' ? 1 : null,
    toWarehouseName: docType === 'PURCHASE_IN' ? '总公司面辅料一仓' : null,
    items: [
      {
        sourceItemId: 4001,
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
        conversionFactor: 1,
        price: 27.8,
        suggestQty: docType === 'PURCHASE_IN' ? 200 : 0,
        maxQty: docType === 'PURCHASE_IN' ? 225 : 300,
        orderedQty: 500,
        receivedQty: 300,
        returnedQty: 0,
        needQty: null,
        issuedQty: null,
        dyeLot: null,
      },
    ],
  }
  return draft
})

mock('post', '/api/inventory-docs/from-purchase/:id', ({ body }) => {
  const req = body as InvDocFromSourceReq
  const id = ++invSeq
  const kind = INV_DOC_TYPE[req.docType].kind
  const no = `${kind === 'IN' ? 'RK' : 'CK'}20261115${String(id).padStart(4, '0')}`
  const status = req.post ? 'APPROVED' : 'DRAFT'
  invDocs.unshift({
    ...structuredClone(invDocs[0]!),
    id,
    no,
    kind,
    docType: req.docType,
    status,
    version: 1,
    actions: status === 'APPROVED' ? ['unaudit'] : ['edit', 'remove', 'audit'],
  })
  return { id, no, version: 1, status }
})

mock('get', '/api/inventory-docs/from-production/:id', ({ params, query }) => {
  const prodId = Number(params.id)
  const docType = (query.docType as 'ISSUE_OUT' | 'RETURN_IN') ?? 'ISSUE_OUT'
  const draft: InvDocDraft = {
    docType,
    source: {
      type: 'PRODUCTION_ORDER',
      kind: 'OUTSOURCE',
      parentId: null,
      typeLabel: '外发加工单',
      id: prodId,
      no: 'JG202611130001',
      status: 'RUNNING',
      statusLabel: '部分回货',
      statusTone: 'progress',
      ownerId: 1,
      ownerName: '管理员',
    },
    partnerId: 3,
    partnerName: '示例制衣一厂',
    fromWarehouseId: docType === 'ISSUE_OUT' ? 1 : 3,
    fromWarehouseName: docType === 'ISSUE_OUT' ? '总公司面辅料一仓' : '示例制衣一厂工厂仓',
    toWarehouseId: docType === 'ISSUE_OUT' ? 3 : 1,
    toWarehouseName: docType === 'ISSUE_OUT' ? '示例制衣一厂工厂仓' : '总公司面辅料一仓',
    items: [
      {
        sourceItemId: null,
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
        purchaseUnit: null,
        conversionFactor: null,
        price: null,
        suggestQty: docType === 'ISSUE_OUT' ? 207.2 : 0,
        maxQty: docType === 'RETURN_IN' ? 260 : null,
        orderedQty: null,
        receivedQty: null,
        returnedQty: null,
        needQty: 467.2,
        issuedQty: 260,
        dyeLot: null,
      },
    ],
  }
  return draft
})

mock('post', '/api/inventory-docs/from-production/:id', ({ body }) => {
  const req = body as InvDocFromSourceReq
  const id = ++invSeq
  const kind = INV_DOC_TYPE[req.docType].kind
  const no = `${kind === 'IN' ? 'RK' : 'CK'}20261115${String(id).padStart(4, '0')}`
  const status = req.post ? 'APPROVED' : 'DRAFT'
  invDocs.unshift({
    ...structuredClone(invDocs[1]!),
    id,
    no,
    kind,
    docType: req.docType,
    status,
    version: 1,
    actions: status === 'APPROVED' ? ['unaudit'] : ['edit', 'remove', 'audit'],
  })
  return { id, no, version: 1, status }
})

mock('get', '/api/inventory-docs', ({ query }) => {
  const kind = (query.kind as 'IN' | 'OUT') ?? 'IN'
  const filtered = invDocs.filter((d) => d.kind === kind && (!query.docType || d.docType === query.docType))
  const rows: InvDocRow[] = filtered.map((d) => ({
    id: d.id,
    no: d.no,
    kind: d.kind,
    docType: d.docType,
    status: d.status,
    version: d.version,
    fromWarehouseId: d.fromWarehouseId,
    fromWarehouseName: d.fromWarehouseName,
    toWarehouseId: d.toWarehouseId,
    toWarehouseName: d.toWarehouseName,
    partnerId: d.partnerId,
    partnerName: d.partnerName,
    sourceNo: d.source?.no ?? null,
    firstItem: d.items[0] ?? null,
    itemCount: d.items.length,
    totalQty: d.totalQty,
    totalAmount: d.totalAmount,
    docDate: d.docDate,
    reason: d.reason,
    createByName: d.createByName,
    createTime: d.createTime,
    actions: d.actions,
  }))
  return page(rows, query)
})

mock('post', '/api/inventory-docs', ({ body }) => {
  const f = body as InvDocCreateForm
  const id = ++invSeq
  const kind = INV_DOC_TYPE[f.docType].kind
  const no = `${kind === 'IN' ? 'RK' : 'CK'}20261115${String(id).padStart(4, '0')}`
  invDocs.unshift({
    ...structuredClone(invDocs[0]!),
    id,
    no,
    kind,
    docType: f.docType,
    source: null,
    docDate: f.docDate,
    reason: f.reason,
    remark: f.remark,
    status: 'DRAFT',
    version: 1,
    actions: ['edit', 'remove', 'audit'],
  })
  return { id, no, version: 1, status: 'DRAFT' }
})

mock('post', '/api/inventory-docs/:id/:act', ({ params, body }) => {
  const d = invDocs.find((x) => x.id === Number(params.id)) ?? fail(404, '出入库单不存在')
  checkVersion(d.version, body)
  if (params.act === 'audit') {
    const short = d.kind === 'OUT' ? d.items.filter((it) => it.fromStockQty !== null && it.qty > it.fromStockQty) : []
    const first = short[0]
    if (first) {
      fail(
        1001,
        `库存不够：${first.materialName}-${first.colorName ?? ''} 库存 ${first.fromStockQty}，差 ${first.qty - (first.fromStockQty ?? 0)} 米`,
        short.map((it) => ({
          seqs: String(it.seq),
          warehouseName: d.fromWarehouseName ?? '',
          skuCode: it.skuCode,
          materialName: `${it.materialName}-${it.colorName ?? ''}`,
          stockQty: it.fromStockQty ?? 0,
          needQty: it.qty,
          shortQty: it.qty - (it.fromStockQty ?? 0),
          unit: '米',
        })),
      )
    }
    const over = d.docType === 'PURCHASE_IN' ? d.items.filter((it) => (it.purchaseQty ?? 0) > 225) : []
    const firstOver = over[0]
    if (firstOver) {
      fail(
        1004,
        `${firstOver.materialName}-${firstOver.colorName ?? ''} 这次 ${firstOver.purchaseQty} 米，最多还能入 225 米`,
        over.map((it) => ({
          seq: it.seq,
          skuCode: it.skuCode,
          materialName: `${it.materialName}-${it.colorName ?? ''}`,
          sourceNo: d.source?.no ?? '',
          thisQty: it.purchaseQty ?? 0,
          maxQty: 225,
          unit: '米',
          reason: '采购 500 米，溢收 5%，已入库 300、已退 0',
        })),
      )
    }
    d.status = 'APPROVED'
    d.actions = ['unaudit']
  } else if (params.act === 'unaudit') {
    d.status = 'DRAFT'
    d.actions = ['edit', 'remove', 'audit']
  }
  d.version += 1
  return null
})

mock('get', '/api/inventory-docs/:id/attachments', () => [])
mock('put', '/api/inventory-docs/:id/attachments', ({ body }) => body)
mock('get', '/api/inventory-docs/:id/logs', () => [
  { id: 1, action: 'audit', fromStatus: 'DRAFT', toStatus: 'APPROVED', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-14 14:25:00' },
])
mock('get', '/api/inventory-docs/:id', ({ params }) => invDocs.find((x) => x.id === Number(params.id)) ?? fail(404, '出入库单不存在'))
mock('put', '/api/inventory-docs/:id', ({ params, body }) => {
  const d = invDocs.find((x) => x.id === Number(params.id)) ?? fail(404, '出入库单不存在')
  checkVersion(d.version, body)
  d.version += 1
  return d.version
})
mock('delete', '/api/inventory-docs/:id', ({ params }) => {
  const idx = invDocs.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) invDocs.splice(idx, 1)
  return null
})

// 盘点单
let cntSeq = 710
const counts: CountDetail[] = [
  {
    id: 701,
    no: 'PD202611150001',
    status: 'DRAFT',
    version: 1,
    companyId: 1,
    companyName: '示例服饰有限公司',
    warehouseId: 1,
    warehouseName: '总公司面辅料一仓',
    warehouseType: 'MATERIAL',
    purpose: 'DAILY',
    scope: 'ALL',
    scopeText: '全部物料',
    categories: [],
    countDate: '2026-11-15',
    counterId: 1,
    counterName: '管理员',
    bookTime: '2026-11-15 10:00:00',
    itemCount: 2,
    filledCount: 1,
    gainCount: 0,
    lossCount: 1,
    movesSinceBook: 1,
    remark: '月中旬例行面料抽盘',
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-15 10:00:00',
    updateTime: '2026-11-15 10:15:00',
    auditBy: null,
    auditByName: null,
    auditTime: null,
    actions: ['edit', 'remove', 'submit', 'refreshBook'],
    approval: null,
    pendingNotices: 0,
    items: [
      {
        id: 7001,
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
        bookQty: 220,
        countedQty: 218.5,
        diffQty: -1.5,
        adjustedQty: null,
        remark: '裁床零头损耗',
      },
      {
        id: 7002,
        seq: 2,
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
        bookQty: 600,
        countedQty: null,
        diffQty: null,
        adjustedQty: null,
        remark: null,
      },
    ],
  },
]

mock('get', '/api/inventory-counts/open-warehouses', () =>
  counts
    .filter((c) => c.status !== 'APPROVED')
    .map((c) => ({ warehouseId: c.warehouseId, countId: c.id, countNo: c.no, status: c.status })),
)
mock('get', '/api/inventory-counts/export', () => new Blob(['盘点单号,仓库,状态\n'], { type: 'text/csv' }))

mock('get', '/api/inventory-counts', ({ query }) => {
  const rows: CountRow[] = counts.map((c) => ({
    id: c.id,
    no: c.no,
    warehouseId: c.warehouseId,
    warehouseName: c.warehouseName,
    purpose: c.purpose,
    scope: c.scope,
    scopeText: c.scopeText,
    countDate: c.countDate,
    counterId: c.counterId,
    counterName: c.counterName,
    itemCount: c.items.length,
    filledCount: c.items.filter((i) => i.countedQty != null).length,
    gainCount: c.items.filter((i) => i.countedQty != null && i.countedQty > i.bookQty).length,
    lossCount: c.items.filter((i) => i.countedQty != null && i.countedQty < i.bookQty).length,
    status: c.status,
    version: c.version,
    actions: c.actions,
    createTime: c.createTime,
  }))
  return page(rows, query)
})

mock('post', '/api/inventory-counts', ({ body }) => {
  const f = body as CountCreateForm
  const id = ++cntSeq
  const no = `PD20261115${String(id).padStart(4, '0')}`
  const copy = structuredClone(counts[0]!)
  copy.id = id
  copy.no = no
  copy.warehouseId = f.warehouseId
  copy.purpose = f.purpose
  copy.scope = f.scope
  copy.countDate = f.countDate
  copy.remark = f.remark
  copy.status = 'DRAFT'
  copy.version = 1
  copy.actions = ['edit', 'remove', 'submit', 'refreshBook']
  counts.unshift(copy)
  return { id, no, version: 1, itemCount: copy.items.length }
})

mock('get', '/api/inventory-counts/:id/sheet', () => new Blob(['SKU码,账面数,实盘数,备注\n'], { type: 'text/csv' }))
mock('post', '/api/inventory-counts/:id/import', () => ({ total: 2 }))
mock('post', '/api/inventory-counts/:id/items', ({ params }) => counts.find((x) => x.id === Number(params.id)) ?? fail(404, '盘点单不存在'))
mock('post', '/api/inventory-counts/:id/refresh-book', ({ params }) => counts.find((x) => x.id === Number(params.id)) ?? fail(404, '盘点单不存在'))

mock('post', '/api/inventory-counts/:id/:act', ({ params, body }) => {
  const c = counts.find((x) => x.id === Number(params.id)) ?? fail(404, '盘点单不存在')
  const act = params.act
  checkVersion(c.version, body)
  if (act === 'submit') {
    c.status = 'PENDING'
    c.actions = ['audit', 'reject', 'withdraw']
  } else if (act === 'audit') {
    c.status = 'APPROVED'
    c.actions = ['unaudit']
    for (const it of c.items) it.adjustedQty = it.diffQty ?? 0
  } else if (act === 'withdraw' || act === 'reject' || act === 'unaudit') {
    c.status = 'DRAFT'
    c.actions = ['edit', 'remove', 'submit', 'refreshBook']
  }
  c.version += 1
  return null
})

mock('get', '/api/inventory-counts/:id/attachments', () => [])
mock('put', '/api/inventory-counts/:id/attachments', ({ body }) => body)
mock('get', '/api/inventory-counts/:id/logs', () => [
  { id: 1, action: 'create', fromStatus: null, toStatus: 'DRAFT', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-15 10:00:00' },
])
mock('get', '/api/inventory-counts/:id', ({ params }) => counts.find((x) => x.id === Number(params.id)) ?? fail(404, '盘点单不存在'))
mock('put', '/api/inventory-counts/:id', ({ params, body }) => {
  const c = counts.find((x) => x.id === Number(params.id)) ?? fail(404, '盘点单不存在')
  const f = body as CountForm
  c.countDate = f.countDate
  c.remark = f.remark
  for (const row of f.items) {
    const hit = c.items.find((i) => i.materialSkuId === row.materialSkuId)
    if (hit) {
      hit.countedQty = row.countedQty
      hit.diffQty = row.countedQty == null ? null : Number((row.countedQty - hit.bookQty).toFixed(3))
      hit.remark = row.remark
    }
  }
  c.version += 1
  return c.version
})
mock('delete', '/api/inventory-counts/:id', ({ params }) => {
  const idx = counts.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) counts.splice(idx, 1)
  return null
})

// 库存台账与日志
const stockRows: StockRow[] = [
  {
    id: 1,
    warehouseId: 1,
    warehouseCode: 'WH-M01',
    warehouseName: '总公司面辅料一仓',
    warehouseType: 'MATERIAL',
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
    qty: 220,
    safetyStock: 100,
    updateTime: '2026-11-14 16:10:00',
  },
  {
    id: 2,
    warehouseId: 1,
    warehouseCode: 'WH-M01',
    warehouseName: '总公司面辅料一仓',
    warehouseType: 'MATERIAL',
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
    qty: 600,
    safetyStock: 2000,
    updateTime: '2026-11-12 09:00:00',
  },
  {
    id: 3,
    warehouseId: 3,
    warehouseCode: 'WH-F03',
    warehouseName: '示例制衣一厂工厂仓',
    warehouseType: 'FACTORY',
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
    qty: 260,
    safetyStock: null,
    updateTime: '2026-11-14 16:10:00',
  },
]

mock('get', '/api/stocks/summary/export', () => new Blob(['SKU,物料仓,工厂仓,合计\n'], { type: 'text/csv' }))
mock('get', '/api/stocks/summary', ({ query }) => {
  const summary: StockSummaryRow[] = [
    {
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
      materialQty: 220,
      factoryQty: 260,
      totalQty: 480,
      safetyStock: 100,
    },
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
      materialQty: 600,
      factoryQty: 0,
      totalQty: 600,
      safetyStock: 2000,
    },
  ]
  return page(summary, query)
})

mock('get', '/api/stocks/qty', () => [
  { materialSkuId: 101, qty: 220 },
  { materialSkuId: 201, qty: 600 },
])
mock('get', '/api/stocks/export', () => new Blob(['仓库,SKU,现存量\n'], { type: 'text/csv' }))
mock('get', '/api/stocks', ({ query }) => {
  const whType = query.warehouseType
  const filtered = stockRows.filter((r) => !whType || r.warehouseType === whType)
  return page(filtered, query)
})

mock('get', '/api/stock-logs/export', () => new Blob(['日期,类型,单号,变动\n'], { type: 'text/csv' }))
mock('get', '/api/stock-logs', ({ query }) => {
  const logs: StockLogRow[] = [
    {
      id: 902,
      bizDate: '2026-11-14',
      createTime: '2026-11-14 16:10:00',
      changeType: 'ISSUE_OUT',
      docType: 'INV_DOC',
      docKind: 'OUT',
      docId: 602,
      docNo: 'CK202611140001',
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
      changeQty: -260,
      balanceQty: 220,
      dyeLot: 'G2611-A01',
      operatorName: '管理员',
    },
    {
      id: 901,
      bizDate: '2026-11-14',
      createTime: '2026-11-14 14:25:00',
      changeType: 'PURCHASE_IN',
      docType: 'INV_DOC',
      docKind: 'IN',
      docId: 601,
      docNo: 'RK202611140001',
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
      changeQty: 300,
      balanceQty: 480,
      dyeLot: 'G2611-A01',
      operatorName: '管理员',
    },
  ]
  return page(logs, query)
})
