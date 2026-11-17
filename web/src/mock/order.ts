import type {
  OrderAuditReq,
  OrderChangeCreateForm,
  OrderChangeDetail,
  OrderChangeRow,
  OrderCreateForm,
  OrderDetail,
  OrderReview,
  OrderRow,
  OrderStatus,
  OrderStyleOption,
  OrderUpdateForm,
  SchedulePrefill,
  ScheduleQuote,
  ScheduleReq,
  StyleOrderRow,
} from '@/types/order'
import { fail, mock, page } from './index'

let orderSeq = 500
const orders: OrderDetail[] = [
  {
    id: 101,
    no: 'DD202611150001',
    status: 'RUNNING',
    version: 3,
    companyId: 1,
    companyName: '示例服饰有限公司',
    remark: '秋冬首波主打款，注意卡其色缸差控制',
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-12 10:15:00',
    updateTime: '2026-11-14 16:00:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-12 14:00:00',
    actions: ['schedule', 'change', 'mrp', 'close'],
    approval: null,
    pendingNotices: 0,
    orderType: 'STOCK',
    customerId: null,
    customerName: null,
    customerOrderNo: null,
    contractNo: 'HT-202611-08',
    brand: 'ZHIJIAN',
    orderDate: '2026-11-12',
    requiredDate: '2026-12-05',
    commitDate: '2026-12-05',
    salesId: 1,
    salesName: '管理员',
    merchId: 1,
    merchName: '管理员',
    currency: 'CNY',
    deliveryReq: '独立防尘袋挂装入总仓',
    totalQty: 600,
    totalAmount: 299400,
    source: 'MANUAL',
    scheduledQty: 400,
    returnedQty: 120,
    styles: [
      {
        styleId: 1,
        styleCode: 'FS2611150101',
        styleName: '双排扣廓形短风衣',
        aliasName: '秋日风衣',
        image: null,
        sizes: ['S', 'M', 'L', 'XL'],
        techPackId: 202,
        techPackVersion: 1,
        currentPackId: 202,
        currentPackVersion: 1,
        qty: 600,
        amount: 299400,
      },
    ],
    items: [
      { id: 1001, seq: 1, styleId: 1, styleSkuId: 11, skuCode: 'FS2611150101-KQ-S', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'S', qty: 80, price: 499, amount: 39920, lineDueDate: null, techPackId: 202, scheduledQty: 60, returnedQty: 20, remark: null },
      { id: 1002, seq: 2, styleId: 1, styleSkuId: 12, skuCode: 'FS2611150101-KQ-M', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'M', qty: 140, price: 499, amount: 69860, lineDueDate: null, techPackId: 202, scheduledQty: 100, returnedQty: 40, remark: null },
      { id: 1003, seq: 3, styleId: 1, styleSkuId: 13, skuCode: 'FS2611150101-KQ-L', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'L', qty: 120, price: 499, amount: 59880, lineDueDate: null, techPackId: 202, scheduledQty: 80, returnedQty: 30, remark: null },
      { id: 1004, seq: 4, styleId: 1, styleSkuId: 14, skuCode: 'FS2611150101-KQ-XL', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'XL', qty: 60, price: 499, amount: 29940, lineDueDate: null, techPackId: 202, scheduledQty: 40, returnedQty: 10, remark: null },
      { id: 1005, seq: 5, styleId: 1, styleSkuId: 21, skuCode: 'FS2611150101-BK-M', colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: 'M', qty: 120, price: 499, amount: 59880, lineDueDate: null, techPackId: 202, scheduledQty: 80, returnedQty: 20, remark: null },
      { id: 1006, seq: 6, styleId: 1, styleSkuId: 22, skuCode: 'FS2611150101-BK-L', colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: 'L', qty: 80, price: 499, amount: 39920, lineDueDate: null, techPackId: 202, scheduledQty: 40, returnedQty: 0, remark: null },
    ],
    pendingChange: null,
  },
  {
    id: 102,
    no: 'DD202611150002',
    status: 'PENDING',
    version: 1,
    companyId: 1,
    companyName: '示例服饰有限公司',
    remark: '双十二补单待评审',
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-15 11:20:00',
    updateTime: '2026-11-15 11:20:00',
    auditBy: null,
    auditByName: null,
    auditTime: null,
    actions: ['audit', 'reject', 'withdraw', 'void'],
    approval: {
      round: 1,
      waitingFor: '第 1 级 · 有审核权限的人',
      canPass: true,
      levels: [{ levelNo: 1, approverLabel: '有审核权限的人', overAmount: null, status: 'CURRENT', passByName: null, passTime: null }],
    },
    pendingNotices: 0,
    orderType: 'REPLENISH',
    customerId: null,
    customerName: null,
    customerOrderNo: null,
    contractNo: null,
    brand: 'ZHIJIAN',
    orderDate: '2026-11-15',
    requiredDate: '2026-12-18',
    commitDate: null,
    salesId: 1,
    salesName: '管理员',
    merchId: 1,
    merchName: '管理员',
    currency: 'CNY',
    deliveryReq: null,
    totalQty: 300,
    totalAmount: 149700,
    source: 'MANUAL',
    scheduledQty: 0,
    returnedQty: 0,
    styles: [
      {
        styleId: 1,
        styleCode: 'FS2611150101',
        styleName: '双排扣廓形短风衣',
        aliasName: '秋日风衣',
        image: null,
        sizes: ['S', 'M', 'L', 'XL'],
        techPackId: null,
        techPackVersion: null,
        currentPackId: 202,
        currentPackVersion: 1,
        qty: 300,
        amount: 149700,
      },
    ],
    items: [
      { id: 1011, seq: 1, styleId: 1, styleSkuId: 12, skuCode: 'FS2611150101-KQ-M', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'M', qty: 180, price: 499, amount: 89820, lineDueDate: null, techPackId: null, scheduledQty: 0, returnedQty: 0, remark: null },
      { id: 1012, seq: 2, styleId: 1, styleSkuId: 13, skuCode: 'FS2611150101-KQ-L', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'L', qty: 120, price: 499, amount: 59880, lineDueDate: null, techPackId: null, scheduledQty: 0, returnedQty: 0, remark: null },
    ],
    pendingChange: null,
  },
]

function toOrderRow(d: OrderDetail): OrderRow {
  return {
    id: d.id,
    no: d.no,
    status: d.status,
    orderType: d.orderType,
    brand: d.brand,
    contractNo: d.contractNo,
    orderDate: d.orderDate,
    requiredDate: d.requiredDate,
    commitDate: d.commitDate,
    currency: d.currency,
    totalQty: d.totalQty,
    totalAmount: d.totalAmount,
    scheduledQty: d.scheduledQty,
    returnedQty: d.returnedQty,
    styles: d.styles.map((s) => ({ styleId: s.styleId, styleCode: s.styleCode, image: s.image })),
    salesId: d.salesId,
    salesName: d.salesName,
    merchId: d.merchId,
    merchName: d.merchName,
    companyName: d.companyName,
    source: d.source,
    createByName: d.createByName,
    createTime: d.createTime,
    version: d.version,
    actions: d.actions,
  }
}

// 静态子路径优先注册
mock('get', '/api/orders/counts', () => {
  const out: Partial<Record<OrderStatus, number>> = {}
  for (const o of orders) {
    out[o.status] = (out[o.status] ?? 0) + 1
  }
  return out
})

mock('get', '/api/orders/style-options', ({ query }) => {
  const opts: OrderStyleOption[] = [
    {
      styleId: 1,
      code: 'FS2611150101',
      name: '双排扣廓形短风衣',
      aliasName: '秋日风衣',
      image: null,
      status: 'ENABLED',
      techPackStatus: 'PROD',
      currentPackId: 202,
      currentPackVersion: 1,
      sizes: ['S', 'M', 'L', 'XL'],
      colors: [
        { id: 1, code: 'KQ', name: '卡其', hex: '#c3b091', orderable: true, reason: null },
        { id: 2, code: 'BK', name: '藏青黑', hex: '#23272e', orderable: true, reason: null },
      ],
      skus: [
        { id: 11, colorId: 1, sizeCode: 'S', code: 'FS2611150101-KQ-S', status: 1 },
        { id: 12, colorId: 1, sizeCode: 'M', code: 'FS2611150101-KQ-M', status: 1 },
        { id: 13, colorId: 1, sizeCode: 'L', code: 'FS2611150101-KQ-L', status: 1 },
        { id: 14, colorId: 1, sizeCode: 'XL', code: 'FS2611150101-KQ-XL', status: 1 },
        { id: 21, colorId: 2, sizeCode: 'S', code: 'FS2611150101-BK-S', status: 1 },
        { id: 22, colorId: 2, sizeCode: 'M', code: 'FS2611150101-BK-M', status: 1 },
        { id: 23, colorId: 2, sizeCode: 'L', code: 'FS2611150101-BK-L', status: 1 },
        { id: 24, colorId: 2, sizeCode: 'XL', code: 'FS2611150101-BK-XL', status: 1 },
      ],
      tagPrice: 499,
      problem: null,
    },
  ]
  // 按请求的款返回，顺序照请求；不认识的款造一个"没有生效的生产 BOM"的（录单页能看到标红的样子），
  // 第一个颜色不能下（看灰掉的行）
  const ids = [query.styleIds ?? []].flat().map(Number)
  return ids.map((sid) => {
    const known = opts.find((o) => o.styleId === sid)
    if (known) return known
    return {
      ...structuredClone(opts[0]!),
      styleId: sid,
      code: `FS26111501${String(sid).padStart(2, '0')}`,
      name: `示例款 ${sid}`,
      aliasName: null,
      techPackStatus: 'DEV',
      currentPackId: null,
      currentPackVersion: null,
      colors: [
        { id: 1, code: 'KQ', name: '卡其', hex: '#c3b091', orderable: false, reason: '资料包里没给这个颜色配料' },
        { id: 2, code: 'BK', name: '藏青黑', hex: '#23272e', orderable: true, reason: null },
      ],
      skus: ['S', 'M', 'L', 'XL'].flatMap((sz, i) => [
        { id: sid * 100 + i, colorId: 1, sizeCode: sz, code: `S${sid}-KQ-${sz}`, status: 1 as const },
        { id: sid * 100 + 10 + i, colorId: 2, sizeCode: sz, code: `S${sid}-BK-${sz}`, status: (sz === 'XL' ? 0 : 1) as 0 | 1 },
      ]),
      tagPrice: 399,
      problem: '没有生效的生产 BOM：研发 BOM 只用来估价，订单不能用',
    } satisfies OrderStyleOption
  })
})

mock('get', '/api/orders/by-style', ({ query }) => {
  const styleId = Number(query.styleId ?? 1)
  const rows: StyleOrderRow[] = orders
    .filter((o) => o.styles.some((s) => s.styleId === styleId))
    .map((o) => {
      const st = o.styles.find((s) => s.styleId === styleId)!
      return {
        orderId: o.id,
        no: o.no,
        status: o.status,
        orderType: o.orderType,
        brand: o.brand,
        qty: st.qty,
        techPackVersion: st.techPackVersion,
        requiredDate: o.requiredDate,
        commitDate: o.commitDate,
        merchName: o.merchName,
      }
    })
  return page(rows, query)
})

mock('get', '/api/orders/export', () => new Blob(['订单号,状态,件数,金额\nDD202611150001,生产中,600,299400\n'], { type: 'text/csv' }))
mock('get', '/api/orders/import-template', () => new Blob(['分单号,款号,颜色编码,尺码,数量,单价\n'], { type: 'text/csv' }))
mock('post', '/api/orders/import', () => ({ total: 2, notes: ['生成草稿订单 1 张'] }))

mock('get', '/api/orders', ({ query }) => {
  let rows = orders.map(toOrderRow)
  const st = query.status
  if (st) {
    const arr = Array.isArray(st) ? st : [st]
    rows = rows.filter((r) => arr.includes(r.status))
  }
  if (query.unscheduled === 'true') {
    rows = rows.filter((r) => r.status === 'APPROVED' && r.scheduledQty === 0)
  }
  const kw = String(query.keyword ?? '').trim().toLowerCase()
  if (kw) {
    rows = rows.filter((r) => r.no.toLowerCase().includes(kw) || r.styles.some((s) => s.styleCode.toLowerCase().includes(kw)))
  }
  return page(rows, query)
})

mock('post', '/api/orders', ({ body }) => {
  const f = body as Omit<OrderCreateForm, 'files'>
  const id = ++orderSeq
  const no = `DD20261115${String(id).padStart(4, '0')}`
  const totalQty = f.items.reduce((s, i) => s + i.qty, 0)
  const totalAmount = f.items.reduce((s, i) => s + i.qty * (i.price ?? 0), 0)
  orders.unshift({
    ...structuredClone(orders[0]!),
    id,
    no,
    status: 'DRAFT',
    version: 1,
    orderType: f.orderType,
    brand: f.brand,
    contractNo: f.contractNo,
    orderDate: f.orderDate,
    requiredDate: f.requiredDate,
    commitDate: null,
    currency: f.currency,
    deliveryReq: f.deliveryReq,
    remark: f.remark,
    totalQty,
    totalAmount,
    scheduledQty: 0,
    returnedQty: 0,
    actions: ['edit', 'remove', 'submit', 'void'],
    approval: null,
  })
  return { id, no, version: 1 }
})

mock('get', '/api/board/orders/:id/review', () => {
  const rev: OrderReview = {
    packs: [{ styleId: 1, styleCode: 'FS2611150101', styleName: '双排扣廓形短风衣', techPackId: 202, version: 1, problem: null }],
    shortages: [
      { materialSkuId: 101, skuCode: 'ML0001-KQ', materialName: '220g 精梳斜纹棉布', skuColorName: '卡其', skuSpec: '150cm', unit: 'METER', need: 310, available: 180, gap: 130, leadDays: 7, image: null },
    ],
    loads: [{ factoryId: 3, factoryName: '示例制衣一厂', styleCodes: ['FS2611150101'], wipQty: 520, orderCount: 2 }],
  }
  return rev
})

mock('post', '/api/orders/:id/audit', ({ params, body }) => {
  const o = orders.find((x) => x.id === Number(params.id)) ?? fail(404, '订单不存在')
  const req = body as OrderAuditReq
  o.commitDate = req.commitDate
  o.status = 'APPROVED'
  o.version += 1
  o.approval = null
  o.styles.forEach((s) => {
    s.techPackId = s.currentPackId
    s.techPackVersion = s.currentPackVersion
  })
  o.actions = ['schedule', 'change', 'mrp', 'unaudit', 'close']
  return null
})

mock('post', '/api/orders/:id/:act', ({ params, body }) => {
  const o = orders.find((x) => x.id === Number(params.id)) ?? fail(404, '订单不存在')
  const act = params.act
  // 1002 的样子：版本对不上
  if ((body as { version?: number } | null)?.version !== o.version) fail(1002, '单据状态变了（别人刚改过），已重新取')
  // 提交检查没过（02 §3.6）：1000 + 一句一条
  if (act === 'submit' && o.styles.some((st) => st.currentPackId == null)) {
    fail(1000, '提交没通过', o.styles.filter((st) => st.currentPackId == null).map((st) => `${st.styleCode} 没有生效的生产 BOM：研发 BOM 只用来估价，订单不能用`))
  }
  if (act === 'submit') {
    o.status = 'PENDING'
    o.actions = ['audit', 'reject', 'withdraw', 'void']
  } else if (act === 'withdraw' || act === 'reject' || act === 'unaudit') {
    o.status = 'DRAFT'
    o.actions = ['edit', 'remove', 'submit', 'void']
  } else if (act === 'close') {
    o.status = 'CLOSED'
    o.actions = ['reopen']
  } else if (act === 'reopen') {
    o.status = o.scheduledQty > 0 ? 'RUNNING' : 'APPROVED'
    o.actions = ['schedule', 'change', 'mrp', 'close', 'unaudit']
  } else if (act === 'void') {
    o.status = 'VOIDED'
    o.actions = []
  }
  o.version += 1
  return null
})

mock('get', '/api/orders/:id/logs', () => [
  { id: 1, action: 'audit', fromStatus: 'PENDING', toStatus: 'APPROVED', remark: '锁定生产 BOM V1', operatorId: 1, operatorName: '管理员', createTime: '2026-11-12 14:00:00' },
])
mock('get', '/api/orders/:id/attachments', () => [])
mock('put', '/api/orders/:id/attachments', ({ body }) => body)
mock('get', '/api/orders/:id/references', () => [
  { type: 'MRP_CALC', kind: null, parentId: null, typeLabel: '算料单', id: 301, no: 'SL202611130001', status: 'CONFIRMED', statusLabel: '已确认', statusTone: 'active', ownerId: 1, ownerName: '管理员' },
  { type: 'PRODUCTION_ORDER', kind: 'OUTSOURCE', parentId: null, typeLabel: '外发加工单', id: 501, no: 'JG202611130001', status: 'RUNNING', statusLabel: '部分回货', statusTone: 'progress', ownerId: 1, ownerName: '管理员' },
])

mock('get', '/api/orders/:id', ({ params }) => orders.find((x) => x.id === Number(params.id)) ?? fail(404, '订单不存在'))
mock('put', '/api/orders/:id', ({ params, body }) => {
  const o = orders.find((x) => x.id === Number(params.id)) ?? fail(404, '订单不存在')
  const f = body as OrderUpdateForm
  o.orderType = f.orderType
  o.brand = f.brand
  o.requiredDate = f.requiredDate
  o.remark = f.remark
  o.version += 1
  return o.version
})
mock('delete', '/api/orders/:id', ({ params }) => {
  const idx = orders.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) orders.splice(idx, 1)
  return null
})

// 排单接口
mock('get', '/api/production-orders/schedule-prefill', ({ query }) => {
  const orderId = Number(query.orderId ?? 101)
  const o = orders.find((x) => x.id === orderId) ?? orders[0]!
  const pf: SchedulePrefill = {
    orderId: o.id,
    orderNo: o.no,
    commitDate: o.commitDate,
    requiredDate: o.requiredDate,
    defaultDueDate: '2026-11-28',
    merchId: o.merchId,
    merchName: o.merchName,
    styles: [
      {
        styleId: 1,
        styleCode: 'FS2611150101',
        styleName: '双排扣廓形短风衣',
        image: null,
        techPackId: 202,
        techPackVersion: 1,
        laborTotal: 36,
        defaultFactoryId: 3,
        defaultFactoryName: '示例制衣一厂',
        sizes: ['S', 'M', 'L', 'XL'],
        colors: [
          { id: 1, code: 'KQ', name: '卡其', hex: '#c3b091' },
          { id: 2, code: 'BK', name: '藏青黑', hex: '#23272e' },
        ],
        skus: o.items.map((it) => ({
          orderItemId: it.id,
          styleSkuId: it.styleSkuId,
          colorId: it.colorId,
          sizeCode: it.sizeCode,
          orderQty: it.qty,
          scheduledQty: it.scheduledQty,
          remainQty: Math.max(0, it.qty - it.scheduledQty),
          maxQty: Math.max(0, Math.floor(it.qty * 1.03) - it.scheduledQty),
        })),
      },
    ],
  }
  return pf
})

mock('get', '/api/production-orders/schedule-quote', ({ query }) => {
  const factoryId = Number(query.factoryId ?? 3)
  const q: ScheduleQuote = {
    factoryId,
    factoryName: '示例制衣一厂',
    usable: true,
    reason: null,
    settleMethod: 'MONTHLY',
    taxPct: 13,
    defaultMode: 'CMT',
    prices: [
      { processMode: 'CMT', price: 38, priceSourceId: 10, effectiveDate: '2026-11-01' },
      { processMode: 'HALF', price: 45, priceSourceId: 11, effectiveDate: '2026-11-01' },
      { processMode: 'FULL', price: 108, priceSourceId: 12, effectiveDate: '2026-11-01' },
    ],
  }
  return q
})

mock('post', '/api/production-orders/schedule', ({ body }) => {
  const req = body as ScheduleReq
  // 超排的样子（1004）：同一订单行几块加起来超过 ⌊订单数 × 1.03⌋ − 已排
  const o = orders.find((x) => x.id === req.orderId) ?? orders[0]!
  const sums = new Map<number, number>()
  req.blocks.forEach((b) => b.items.forEach((it) => sums.set(it.orderItemId, (sums.get(it.orderItemId) ?? 0) + it.qty)))
  const over = o.items.flatMap((it) => {
    const thisQty = sums.get(it.id) ?? 0
    const maxQty = Math.floor(it.qty * 1.03)
    return it.scheduledQty + thisQty > maxQty
      ? [{ sku: it.skuCode, orderQty: it.qty, scheduledQty: it.scheduledQty, thisQty, maxQty, message: `最多还能排 ${Math.max(0, maxQty - it.scheduledQty)} 件` }]
      : []
  })
  if (over.length) fail(1004, '超排了', over)
  return req.blocks.map((b, idx) => {
    const isBuy = b.processMode === 'FULL'
    return {
      type: 'PRODUCTION_ORDER' as const,
      kind: isBuy ? 'BUY' : 'OUTSOURCE',
      parentId: null,
      typeLabel: isBuy ? '成品采购单' : '外发加工单',
      id: 501 + idx,
      no: `${isBuy ? 'CP' : 'JG'}20261115000${idx + 2}`,
      status: 'DRAFT',
      statusLabel: '草稿',
      statusTone: 'off' as const,
      ownerId: b.merchId,
      ownerName: '管理员',
    }
  })
})

// 订单变更单
let chgSeq = 210
const changes: OrderChangeDetail[] = [
  {
    id: 201,
    no: 'BG202611150001',
    status: 'PENDING',
    version: 1,
    companyId: 1,
    companyName: '示例服饰有限公司',
    remark: null,
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-15 09:40:00',
    updateTime: '2026-11-15 09:40:00',
    auditBy: null,
    auditByName: null,
    auditTime: null,
    actions: ['audit', 'reject', 'withdraw', 'void'],
    approval: null,
    pendingNotices: 0,
    orderId: 101,
    orderNo: 'DD202611150001',
    orderStatus: 'RUNNING',
    changeTypes: ['QTY', 'DUE'],
    cancel: false,
    reason: '门店卡其 M 码预售火爆追加 20 件，交期顺延 3 天',
    responsibleParty: 'SELF',
    feeNote: '无额外面辅料报废',
    rejectReason: null,
    summary: 'FS2611150101-KQ-M：140 → 160；要求交期：2026-12-05 → 2026-12-08',
    items: [
      { id: 1, seq: 1, changeType: 'QTY', orderItemId: 1002, styleId: 1, styleCode: 'FS2611150101', styleSkuId: 12, skuCode: 'FS2611150101-KQ-M', fromQty: 140, toQty: 160, fromDate: null, toDate: null, fromPrice: null, toPrice: null, fromTechPackId: null, fromVersion: null, toTechPackId: null, toVersion: null, text: 'FS2611150101-KQ-M：140 → 160（已排 100）' },
      { id: 2, seq: 2, changeType: 'DUE', orderItemId: null, styleId: null, styleCode: null, styleSkuId: null, skuCode: null, fromQty: null, toQty: null, fromDate: '2026-12-05', toDate: '2026-12-08', fromPrice: null, toPrice: null, fromTechPackId: null, fromVersion: null, toTechPackId: null, toVersion: null, text: '要求交期：2026-12-05 → 2026-12-08' },
    ],
  },
]

mock('get', '/api/order-changes/counts', () => ({
  PENDING: changes.filter((c) => c.status === 'PENDING').length,
  DRAFT: changes.filter((c) => c.status === 'DRAFT').length,
  REJECTED: changes.filter((c) => c.status === 'DRAFT' && c.rejectReason).length,
  APPROVED: changes.filter((c) => c.status === 'APPROVED').length,
}))

mock('get', '/api/order-changes', ({ query }) => {
  const rows: OrderChangeRow[] = changes.map((c) => ({
    id: c.id,
    no: c.no,
    status: c.status,
    rejectReason: c.rejectReason,
    orderId: c.orderId,
    orderNo: c.orderNo,
    changeTypes: c.changeTypes,
    summary: c.summary,
    reason: c.reason,
    responsibleParty: c.responsibleParty,
    feeNote: c.feeNote,
    createByName: c.createByName,
    createTime: c.createTime,
    auditByName: c.auditByName,
    auditTime: c.auditTime,
    actions: c.actions,
  }))
  return page(rows, query)
})

mock('post', '/api/order-changes', ({ body }) => {
  const f = body as OrderChangeCreateForm
  const id = ++chgSeq
  const no = `BG20261115${String(id).padStart(4, '0')}`
  const ord = orders.find((o) => o.id === f.orderId)
  changes.unshift({
    ...structuredClone(changes[0]!),
    id,
    no,
    orderId: f.orderId,
    orderNo: ord?.no ?? 'DD202611150001',
    status: 'DRAFT',
    version: 1,
    cancel: f.cancel,
    changeTypes: f.cancel ? ['CANCEL'] : ['QTY'],
    reason: f.reason,
    responsibleParty: f.responsibleParty,
    feeNote: f.feeNote,
    rejectReason: null,
    summary: f.cancel ? '取消整张需求订单' : '更新订单数量与交期参数',
    actions: ['edit', 'remove', 'submit', 'void'],
  })
  return { id, no, version: 1 }
})

mock('post', '/api/order-changes/:id/:act', ({ params, body }) => {
  const c = changes.find((x) => x.id === Number(params.id)) ?? fail(404, '变更单不存在')
  const act = params.act
  const reason = (body as { reason?: string | null } | null)?.reason ?? null
  if (act === 'submit') {
    c.status = 'PENDING'
    c.rejectReason = null
    c.actions = ['audit', 'reject', 'withdraw', 'void']
  } else if (act === 'reject') {
    c.status = 'DRAFT'
    c.rejectReason = reason ?? '请核实明细'
    c.actions = ['edit', 'remove', 'submit', 'void']
  } else if (act === 'audit') {
    c.status = 'APPROVED'
    c.auditByName = '管理员'
    c.auditTime = '2026-11-15 21:30:00'
    c.actions = []
  } else if (act === 'withdraw') {
    c.status = 'DRAFT'
    c.actions = ['edit', 'remove', 'submit', 'void']
  } else if (act === 'void') {
    c.status = 'VOIDED'
    c.actions = []
  }
  c.version += 1
  return null
})

mock('get', '/api/order-changes/:id/logs', () => [
  { id: 1, action: 'submit', fromStatus: 'DRAFT', toStatus: 'PENDING', remark: null, operatorId: 1, operatorName: '管理员', createTime: '2026-11-15 09:40:00' },
])
mock('get', '/api/order-changes/:id/attachments', () => [])
mock('put', '/api/order-changes/:id/attachments', ({ body }) => body)
mock('get', '/api/order-changes/:id', ({ params }) => changes.find((x) => x.id === Number(params.id)) ?? fail(404, '变更单不存在'))
mock('delete', '/api/order-changes/:id', ({ params }) => {
  const idx = changes.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) changes.splice(idx, 1)
  return null
})
