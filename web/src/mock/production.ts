import type {
  BoardCards,
  BoardFactoryRow,
  IssueCloseForm,
  IssueOption,
  MobileOrderRow,
  NodePlanItem,
  NodeReportForm,
  ProductionDetail,
  ProductionIssue,
  ProductionMaterials,
  ProductionReceipt,
  ProductionRow,
  ReceiptForm,
} from '@/types/production'
import type { DocNotice } from '@/types/biz'
import type { ProductionAction, ProductionStatus } from '@/types/production'
import { PRODUCTION_KIND, PRODUCTION_STATUS } from '@/types/production'
import type { UpstreamRow } from '@/types/home'
import { fail, mock, page } from './index'

/**
 * 生产的假接口（docs/api/05）。为了在页面上看得到几种样子：
 * - JG202611130001 有一条没处理的上游变更提醒（黄条、"已处理"能点）、一条没关的异常
 * - JG202611150002 是草稿：能编辑（换加工方式、改单价出改价原因）、提交、审核、驳回、撤回、反审核
 * - 登记回货超过 ⌊件数 × 1.03⌋ 回 1004 + 清单；状态动作、改草稿、开始处理的 version / 状态对不上回 1002
 * 名称、电话都是编的
 */
function checkVersion(current: number, body: unknown) {
  const v = (body as { version?: number } | null)?.version
  if (v !== undefined && v !== current) fail(1002, '单据状态变了（别人刚改过），已重新取')
}

/** 每个状态后端会给的 actions（照 05 §2.1，权限都当有） */
function actionsOf(status: ProductionStatus, kind: 'OUTSOURCE' | 'BUY'): ProductionAction[] {
  const work: ProductionAction[] = ['report', 'editPlan', 'reportIssue', 'receive', ...(kind === 'OUTSOURCE' ? (['issue', 'returnMaterial'] as const) : [])]
  switch (status) {
    case 'DRAFT':
      return ['edit', 'remove', 'submit', 'void']
    case 'PENDING':
      return ['audit', 'reject', 'withdraw', 'void']
    case 'APPROVED':
      return [...work, 'unaudit', 'close']
    case 'RUNNING':
      return [...work, 'close']
    case 'DONE':
      return ['receive', 'reportIssue', 'returnMaterial', 'close']
    case 'CLOSED':
      return ['reopen']
    default:
      return []
  }
}

const prdOrders: ProductionDetail[] = [
  {
    id: 501,
    no: 'JG202611130001',
    kind: 'OUTSOURCE',
    status: 'RUNNING',
    version: 3,
    companyId: 1,
    companyName: '示例服饰有限公司',
    factoryId: 3,
    factoryCode: 'SUP003',
    factoryName: '示例制衣一厂',
    factoryShortName: '示例制衣一厂',
    factoryStatus: 'ACTIVE',
    factoryContact: '周厂长',
    factoryPhone: '000-0000-0003',
    processMode: 'CMT',
    orderId: 101,
    orderNo: 'DD202611150001',
    styleId: 1,
    styleCode: 'FS2611150101',
    styleName: '双排扣廓形短风衣',
    aliasName: '秋日风衣',
    brand: 'ZHIJIAN',
    image: null,
    techPackId: 202,
    techPackVersion: 1,
    orderDate: '2026-11-13',
    dueDate: '2026-11-28',
    expectDate: null,
    daysLeft: 13,
    unitPrice: 38,
    priceSourceId: 10,
    archivePrice: 38,
    priceChangeReason: null,
    taxPct: 13,
    taxIncluded: true,
    totalQty: 280,
    totalAmount: 10640,
    merchId: 1,
    merchName: '管理员',
    settleMethod: 'MONTHLY',
    terms: '大货做工按封样执行，次品率控制在 1% 以内；剩料随尾批退回总仓。',
    remark: '首单卡其色大货',
    issueStatus: 'PARTIAL',
    returnedQty: 100,
    defectQty: 2,
    currentNodeName: '车缝',
    risk: { late: false, near: false, nodeLate: false, issue: true, upstream: true },
    nearDays: 7,
    sizes: ['S', 'M', 'L', 'XL'],
    items: [
      { id: 5001, seq: 1, orderItemId: 1001, styleSkuId: 11, skuCode: 'FS2611150101-KQ-S', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'S', qty: 60, returnedQty: 20, defectQty: 0, remainQty: 40, maxReceiveQty: 41 },
      { id: 5002, seq: 2, orderItemId: 1002, styleSkuId: 12, skuCode: 'FS2611150101-KQ-M', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'M', qty: 100, returnedQty: 40, defectQty: 1, remainQty: 60, maxReceiveQty: 63 },
      { id: 5003, seq: 3, orderItemId: 1003, styleSkuId: 13, skuCode: 'FS2611150101-KQ-L', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'L', qty: 80, returnedQty: 30, defectQty: 1, remainQty: 50, maxReceiveQty: 52 },
      { id: 5004, seq: 4, orderItemId: 1004, styleSkuId: 14, skuCode: 'FS2611150101-KQ-XL', colorId: 1, colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'XL', qty: 40, returnedQty: 10, defectQty: 0, remainQty: 30, maxReceiveQty: 31 },
    ],
    nodes: [
      { id: 81, seq: 1, nodeCode: 'MATERIAL', nodeName: '到料', planStart: '2026-11-13', planFinish: '2026-11-15', actualStart: '2026-11-14', actualFinish: '2026-11-14', doneQty: 280, remark: '首缸卡其布已到厂', state: 'DONE', lateDays: null, lastReportByName: '管理员', lastReportTime: '2026-11-14 16:30:00', reportCount: 1, photos: [] },
      { id: 82, seq: 2, nodeCode: 'CUT', nodeName: '裁剪', planStart: '2026-11-16', planFinish: '2026-11-18', actualStart: '2026-11-15', actualFinish: '2026-11-15', doneQty: 280, remark: '全床裁完分扎', state: 'DONE', lateDays: null, lastReportByName: '管理员', lastReportTime: '2026-11-15 15:00:00', reportCount: 1, photos: [] },
      { id: 83, seq: 3, nodeCode: 'SEW', nodeName: '车缝', planStart: '2026-11-19', planFinish: '2026-11-24', actualStart: '2026-11-15', actualFinish: null, doneQty: 130, remark: '两组流水线同步上线', state: 'RUNNING', lateDays: null, lastReportByName: '管理员', lastReportTime: '2026-11-15 18:00:00', reportCount: 1, photos: [] },
      { id: 84, seq: 4, nodeCode: 'FINISH', nodeName: '后整', planStart: '2026-11-25', planFinish: '2026-11-26', actualStart: null, actualFinish: null, doneQty: null, remark: null, state: 'TODO', lateDays: null, lastReportByName: null, lastReportTime: null, reportCount: 0, photos: [] },
      { id: 85, seq: 5, nodeCode: 'PACK', nodeName: '包装', planStart: '2026-11-27', planFinish: '2026-11-27', actualStart: null, actualFinish: null, doneQty: null, remark: null, state: 'TODO', lateDays: null, lastReportByName: null, lastReportTime: null, reportCount: 0, photos: [] },
      { id: 86, seq: 6, nodeCode: 'WAREHOUSE', nodeName: '入仓', planStart: '2026-11-28', planFinish: '2026-11-28', actualStart: null, actualFinish: null, doneQty: null, remark: null, state: 'TODO', lateDays: null, lastReportByName: null, lastReportTime: null, reportCount: 0, photos: [] },
    ],
    openIssues: [
      { id: 71, issueType: 'WORKMANSHIP', description: '首批抽检发现 3 件右驳头止口略宽 0.2cm，已要求车间组长现场纠正', newExpectDate: null },
    ],
    issueCount: 1,
    receiptCount: 1,
    stockDocCount: 1,
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-13 14:30:00',
    updateTime: '2026-11-15 18:00:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-13 15:00:00',
    actions: ['report', 'editPlan', 'reportIssue', 'receive', 'issue', 'returnMaterial', 'close'],
    approval: null,
    pendingNotices: 1,
  },
  {
    id: 502,
    no: 'CP202611140001',
    kind: 'BUY',
    status: 'APPROVED',
    version: 2,
    companyId: 1,
    companyName: '示例服饰有限公司',
    factoryId: 4,
    factoryCode: 'SUP004',
    factoryName: '示例制衣二厂制造有限公司',
    factoryShortName: '示例制衣二厂',
    factoryStatus: 'ACTIVE',
    factoryContact: '林经理',
    factoryPhone: '000-0000-0004',
    processMode: 'FULL',
    orderId: 101,
    orderNo: 'DD202611150001',
    styleId: 1,
    styleCode: 'FS2611150101',
    styleName: '双排扣廓形短风衣',
    aliasName: '秋日风衣',
    brand: 'ZHIJIAN',
    image: null,
    techPackId: 202,
    techPackVersion: 1,
    orderDate: '2026-11-14',
    dueDate: '2026-11-21',
    expectDate: null,
    daysLeft: 6,
    unitPrice: 108,
    priceSourceId: 12,
    archivePrice: 108,
    priceChangeReason: null,
    taxPct: 13,
    taxIncluded: true,
    totalQty: 120,
    totalAmount: 12960,
    merchId: 1,
    merchName: '管理员',
    settleMethod: 'MONTHLY',
    terms: '包工包料按封样品质验收',
    remark: '藏青黑色包工包料拆单',
    issueStatus: null,
    returnedQty: 20,
    defectQty: 0,
    currentNodeName: '裁剪',
    risk: { late: false, near: true, nodeLate: false, issue: false, upstream: false },
    nearDays: 7,
    sizes: ['S', 'M', 'L', 'XL'],
    items: [
      { id: 5011, seq: 1, orderItemId: 1005, styleSkuId: 22, skuCode: 'FS2611150101-BK-M', colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: 'M', qty: 80, returnedQty: 20, defectQty: 0, remainQty: 60, maxReceiveQty: 62 },
      { id: 5012, seq: 2, orderItemId: 1006, styleSkuId: 23, skuCode: 'FS2611150101-BK-L', colorId: 2, colorCode: 'BK', colorName: '藏青黑', colorHex: '#23272e', sizeCode: 'L', qty: 40, returnedQty: 0, defectQty: 0, remainQty: 40, maxReceiveQty: 41 },
    ],
    nodes: [
      { id: 91, seq: 1, nodeCode: 'MATERIAL', nodeName: '到料', planStart: '2026-11-14', planFinish: '2026-11-15', actualStart: '2026-11-14', actualFinish: '2026-11-15', doneQty: 120, remark: null, state: 'DONE', lateDays: null, lastReportByName: '管理员', lastReportTime: '2026-11-15 10:00:00', reportCount: 1, photos: [] },
      { id: 92, seq: 2, nodeCode: 'CUT', nodeName: '裁剪', planStart: '2026-11-16', planFinish: '2026-11-17', actualStart: null, actualFinish: null, doneQty: null, remark: null, state: 'WARN', lateDays: null, lastReportByName: null, lastReportTime: null, reportCount: 0, photos: [] },
      { id: 93, seq: 3, nodeCode: 'SEW', nodeName: '车缝', planStart: '2026-11-18', planFinish: '2026-11-20', actualStart: null, actualFinish: null, doneQty: null, remark: null, state: 'TODO', lateDays: null, lastReportByName: null, lastReportTime: null, reportCount: 0, photos: [] },
    ],
    openIssues: [],
    issueCount: 0,
    receiptCount: 0,
    stockDocCount: 0,
    createBy: 1,
    createByName: '管理员',
    createTime: '2026-11-14 09:30:00',
    updateTime: '2026-11-15 10:00:00',
    auditBy: 1,
    auditByName: '管理员',
    auditTime: '2026-11-14 10:00:00',
    actions: ['report', 'editPlan', 'reportIssue', 'receive', 'close'],
    approval: null,
    pendingNotices: 0,
  },
]

// 一张草稿：编辑、提交、审核这些按钮看得到
{
  const draft = structuredClone(prdOrders[0]!)
  Object.assign(draft, {
    id: 503,
    no: 'JG202611150002',
    status: 'DRAFT',
    version: 1,
    processMode: 'CMT',
    unitPrice: 40,
    archivePrice: 38,
    priceSourceId: null,
    priceChangeReason: '加急，工厂加班费',
    dueDate: '2026-12-05',
    expectDate: null,
    daysLeft: null,
    totalQty: 120,
    totalAmount: 4800,
    issueStatus: 'NONE',
    returnedQty: 0,
    defectQty: 0,
    currentNodeName: null,
    risk: { late: false, near: false, nodeLate: false, issue: false, upstream: false },
    nodes: [],
    openIssues: [],
    issueCount: 0,
    receiptCount: 0,
    stockDocCount: 0,
    auditBy: null,
    auditByName: null,
    auditTime: null,
    pendingNotices: 0,
    actions: actionsOf('DRAFT', 'OUTSOURCE'),
  })
  draft.items = draft.items.slice(0, 2).map((it) => ({ ...it, qty: 60, returnedQty: 0, defectQty: 0, remainQty: 60, maxReceiveQty: 61 }))
  prdOrders.push(draft)
}

const prdNotices = new Map<number, DocNotice[]>([
  [
    501,
    [
      {
        id: 1,
        source: {
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
        },
        content: '订单 DD202611150001 的 FS2611150101 卡其 M 码从 100 改成 120',
        createTime: '2026-11-15 20:00:00',
        handledByName: null,
        handledTime: null,
        handleRemark: null,
      },
    ],
  ],
])

const issuesMap: Record<number, ProductionIssue[]> = {
  501: [
    {
      id: 71,
      no: 'YC202611150001',
      issueType: 'WORKMANSHIP',
      description: '首批抽检发现 3 件右驳头止口略宽 0.2cm，已要求车间组长现场纠正',
      photos: [],
      impactDelivery: false,
      newExpectDate: null,
      responsibleParty: 'FACTORY',
      status: 'OPEN',
      createByName: '管理员',
      createTime: '2026-11-15 16:40:00',
      handlerName: null,
      measure: null,
      closeByName: null,
      closeTime: null,
      actions: ['start', 'close'],
    },
  ],
}

const receiptsMap: Record<number, ProductionReceipt[]> = {
  501: [
    {
      id: 801,
      no: 'HH202611150001',
      status: 'APPROVED',
      receiptDate: '2026-11-15',
      warehouseId: 2,
      warehouseName: '总公司成品一仓',
      logisticsNo: 'JD99887766',
      remark: '头批回货抽检合格',
      qty: 100,
      defectQty: 2,
      photos: [],
      createByName: '管理员',
      createTime: '2026-11-15 17:10:00',
      voidReason: null,
      voidByName: null,
      voidTime: null,
      actions: ['void'],
      items: [
        { id: 1, prdOrderItemId: 5001, styleSkuId: 11, skuCode: 'FS2611150101-KQ-S', colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'S', qty: 20, defectQty: 0, goodQty: 20, remark: null },
        { id: 2, prdOrderItemId: 5002, styleSkuId: 12, skuCode: 'FS2611150101-KQ-M', colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'M', qty: 40, defectQty: 1, goodQty: 39, remark: null },
        { id: 3, prdOrderItemId: 5003, styleSkuId: 13, skuCode: 'FS2611150101-KQ-L', colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'L', qty: 30, defectQty: 1, goodQty: 29, remark: null },
        { id: 4, prdOrderItemId: 5004, styleSkuId: 14, skuCode: 'FS2611150101-KQ-XL', colorCode: 'KQ', colorName: '卡其', colorHex: '#c3b091', sizeCode: 'XL', qty: 10, defectQty: 0, goodQty: 10, remark: null },
      ],
    },
  ],
}

function toPrdRow(d: ProductionDetail): ProductionRow {
  return {
    id: d.id,
    no: d.no,
    kind: d.kind,
    status: d.status,
    version: d.version,
    factoryId: d.factoryId,
    factoryShortName: d.factoryShortName,
    processMode: d.processMode,
    orderId: d.orderId,
    orderNo: d.orderNo,
    styleId: d.styleId,
    styleCode: d.styleCode,
    styleName: d.styleName,
    image: d.image,
    totalQty: d.totalQty,
    unitPrice: d.unitPrice,
    totalAmount: d.totalAmount,
    dueDate: d.dueDate,
    expectDate: d.expectDate,
    daysLeft: d.daysLeft,
    nodes: d.nodes.map((n) => ({ nodeName: n.nodeName, state: n.state })),
    currentNodeName: d.currentNodeName,
    issueStatus: d.issueStatus,
    returnedQty: d.returnedQty,
    defectQty: d.defectQty,
    openIssueCount: d.openIssues.length,
    firstOpenIssueType: d.openIssues[0]?.issueType ?? null,
    merchId: d.merchId,
    merchName: d.merchName,
    risk: d.risk,
    actions: d.actions,
  }
}

// 静态子路径优先注册
mock('get', '/api/production-orders/counts', ({ query }) => {
  const list = prdOrders.filter((d) => !query.kind || d.kind === query.kind)
  const counts: Partial<Record<ProductionStatus | 'PARTIAL', number>> = {}
  for (const d of list) counts[d.status] = (counts[d.status] ?? 0) + 1
  counts.PARTIAL = list.filter((d) => d.status === 'RUNNING' && d.returnedQty > 0).length
  return counts
})

mock('get', '/api/production-orders/issue-options', ({ query }) => {
  const opts: IssueOption[] = prdOrders
    .filter((d) => d.kind === 'OUTSOURCE')
    .map((d) => ({
      id: d.id,
      no: d.no,
      kind: d.kind,
      status: d.status,
      factoryId: d.factoryId,
      factoryShortName: d.factoryShortName,
      styleCode: d.styleCode,
      styleName: d.styleName,
      totalQty: d.totalQty,
      issueStatus: d.issueStatus,
    }))
  return page(opts, query)
})

mock('get', '/api/production-orders/my-follow', () => {
  const list: MobileOrderRow[] = prdOrders.filter((d) => d.status === 'APPROVED' || d.status === 'RUNNING').map((d) => ({
    id: d.id,
    no: d.no,
    kind: d.kind,
    status: d.status,
    styleCode: d.styleCode,
    styleName: d.styleName,
    image: d.image,
    factoryShortName: d.factoryShortName,
    totalQty: d.totalQty,
    dueDate: d.dueDate,
    expectDate: d.expectDate,
    daysLeft: d.daysLeft,
    nodes: d.nodes.map((n) => ({ nodeName: n.nodeName, state: n.state })),
    currentNodeName: d.currentNodeName,
    risk: d.risk,
  }))
  return list
})

mock('get', '/api/production-orders/export', () => new Blob(['单号,工厂,件数\n'], { type: 'text/csv' }))

mock('get', '/api/production-orders', ({ query }) => {
  const statuses = ([] as string[]).concat(query.status ?? [])
  let rows = prdOrders.filter((d) => (!query.kind || d.kind === query.kind) && (!statuses.length || statuses.includes(d.status))).map(toPrdRow)
  if (query.returned === 'true') rows = rows.filter((r) => r.returnedQty > 0)
  else if (query.returned === 'false') rows = rows.filter((r) => r.returnedQty === 0)
  return page(rows, query)
})

mock('put', '/api/production-orders/:id/nodes', ({ params, body }) => {
  const d = prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在')
  const { nodes } = body as { nodes: NodePlanItem[] }
  for (const item of nodes) {
    const hit = d.nodes.find((n) => n.id === item.nodeId)
    if (hit) {
      hit.planStart = item.planStart
      hit.planFinish = item.planFinish
    }
  }
  return null
})

mock('get', '/api/production-orders/:id/nodes/:nodeId/reports', () => [
  { id: 1, action: 'START', actualStart: '2026-11-15', actualFinish: null, doneQty: 130, remark: '两组流水线同步上线', photos: [], createByName: '管理员', createTime: '2026-11-15 18:00:00' },
])

mock('post', '/api/production-orders/:id/nodes/:nodeId/reports', ({ params, body }) => {
  const d = prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在')
  const node = d.nodes.find((n) => n.id === Number(params.nodeId)) ?? fail(404, '节点不存在')
  const f = body as Omit<NodeReportForm, 'photos'>
  if (f.action === 'START') {
    node.actualStart = f.actualStart
    node.state = 'RUNNING'
  } else {
    node.actualStart = f.actualStart ?? node.actualStart ?? '2026-11-15'
    node.actualFinish = f.actualFinish ?? '2026-11-15'
    node.state = 'DONE'
  }
  node.doneQty = f.doneQty
  node.remark = f.remark
  node.reportCount += 1
  if (d.status === 'APPROVED') d.status = 'RUNNING'
  d.currentNodeName = d.nodes.find((n) => n.state !== 'DONE')?.nodeName ?? null
  return { warning: null, status: d.status }
})

mock('get', '/api/production-orders/:id/materials', ({ params }) => {
  const id = Number(params.id)
  if (id === 502) return { rows: [], docs: [] } satisfies ProductionMaterials
  const res: ProductionMaterials = {
    rows: [
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
        needQty: 467.2,
        issuedQty: 260,
        remainQty: 207.2,
        stockQty: 220,
      },
    ],
    docs: [
      {
        id: 602,
        no: 'CK202611140001',
        kind: 'OUT',
        docType: 'ISSUE_OUT',
        status: 'APPROVED',
        docDate: '2026-11-14',
        fromWarehouseName: '总公司面辅料一仓',
        toWarehouseName: '示例制衣一厂工厂仓',
        itemCount: 1,
      },
    ],
  }
  return res
})

mock('get', '/api/production-orders/:id/issues', ({ params }) => issuesMap[Number(params.id)] ?? [])
mock('post', '/api/production-orders/:id/issues', ({ params, body }) => {
  const id = Number(params.id)
  const d = prdOrders.find((x) => x.id === id) ?? fail(404, '生产单不存在')
  const f = body as { issueType: string; description: string; impactDelivery: boolean; newExpectDate: string | null; responsibleParty: ProductionIssue['responsibleParty'] }
  const issId = Date.now()
  const no = `YC20261115${String(issId % 10000).padStart(4, '0')}`
  const item: ProductionIssue = {
    id: issId,
    no,
    issueType: f.issueType,
    description: f.description,
    photos: [],
    impactDelivery: f.impactDelivery,
    newExpectDate: f.newExpectDate,
    responsibleParty: f.responsibleParty,
    status: 'OPEN',
    createByName: '管理员',
    createTime: '2026-11-15 21:00:00',
    handlerName: null,
    measure: null,
    closeByName: null,
    closeTime: null,
    actions: ['start', 'close'],
  }
  issuesMap[id] = [item, ...(issuesMap[id] ?? [])]
  d.openIssues.unshift({ id: issId, issueType: f.issueType, description: f.description, newExpectDate: f.newExpectDate })
  d.issueCount += 1
  d.risk.issue = true
  if (f.impactDelivery && f.newExpectDate) d.expectDate = f.newExpectDate
  return { id: issId, no }
})

mock('post', '/api/production-orders/:id/issues/:issueId/start', ({ params }) => {
  const list = issuesMap[Number(params.id)] ?? []
  const hit = list.find((x) => x.id === Number(params.issueId)) ?? fail(404, '异常不存在')
  if (hit.status !== 'OPEN') fail(1002, '这条异常已经有人在处理了，已重新取')
  hit.status = 'HANDLING'
  hit.handlerName = '管理员'
  hit.actions = ['close']
  return null
})

mock('post', '/api/production-orders/:id/issues/:issueId/close', ({ params, body }) => {
  const id = Number(params.id)
  const list = issuesMap[id] ?? []
  const hit = list.find((x) => x.id === Number(params.issueId)) ?? fail(404, '异常不存在')
  const f = body as IssueCloseForm
  hit.status = 'CLOSED'
  hit.measure = f.measure
  hit.responsibleParty = f.responsibleParty
  hit.closeByName = '管理员'
  hit.closeTime = '2026-11-15 21:30:00'
  hit.actions = []
  const d = prdOrders.find((x) => x.id === id)
  if (d) {
    d.openIssues = d.openIssues.filter((o) => o.id !== hit.id)
    d.risk.issue = d.openIssues.length > 0
  }
  return null
})

mock('get', '/api/production-orders/:id/receipts', ({ params }) => receiptsMap[Number(params.id)] ?? [])
mock('post', '/api/production-orders/:id/receipts', ({ params, body }) => {
  const id = Number(params.id)
  const d = prdOrders.find((x) => x.id === id) ?? fail(404, '生产单不存在')
  const f = body as Omit<ReceiptForm, 'photos'>
  const over = f.items
    .map((it) => ({ it, src: d.items.find((x) => x.id === it.prdOrderItemId) }))
    .filter(({ it, src }) => src && src.returnedQty + it.qty > Math.floor(src.qty * 1.03))
  if (over.length) {
    fail(
      1004,
      '有几个码超过了能回的上限（件数 × 1.03）',
      over.map(({ it, src }) => {
        const max = Math.floor((src?.qty ?? 0) * 1.03)
        return {
          sku: src?.skuCode ?? '',
          qty: src?.qty ?? 0,
          returnedQty: src?.returnedQty ?? 0,
          thisQty: it.qty,
          maxQty: max,
          message: `加工 ${src?.qty ?? 0} 件，已回 ${src?.returnedQty ?? 0}，最多还能回 ${Math.max(0, max - (src?.returnedQty ?? 0))} 件`,
        }
      }),
    )
  }
  const addQty = f.items.reduce((s, i) => s + i.qty, 0)
  const addDef = f.items.reduce((s, i) => s + i.defectQty, 0)
  d.returnedQty += addQty
  d.defectQty += addDef
  d.receiptCount += 1
  if (d.returnedQty >= Math.ceil(d.totalQty * 0.97)) d.status = 'DONE'
  else d.status = 'RUNNING'
  d.actions = actionsOf(d.status, d.kind)
  const rcpId = Date.now()
  const no = `HH20261115${String(rcpId % 10000).padStart(4, '0')}`
  receiptsMap[id] = [
    {
      id: rcpId,
      no,
      status: 'APPROVED',
      receiptDate: f.receiptDate,
      warehouseId: f.warehouseId,
      warehouseName: '总公司成品一仓',
      logisticsNo: f.logisticsNo,
      remark: f.remark,
      qty: addQty,
      defectQty: addDef,
      photos: [],
      createByName: '管理员',
      createTime: '2026-11-15 21:30:00',
      voidReason: null,
      voidByName: null,
      voidTime: null,
      actions: ['void'],
      items: f.items.map((it, idx) => {
        const src = d.items.find((x) => x.id === it.prdOrderItemId)!
        src.returnedQty += it.qty
        src.defectQty += it.defectQty
        src.remainQty = Math.max(0, src.qty - src.returnedQty)
        return {
          id: idx + 1,
          prdOrderItemId: it.prdOrderItemId,
          styleSkuId: src.styleSkuId,
          skuCode: src.skuCode,
          colorCode: src.colorCode,
          colorName: src.colorName,
          colorHex: src.colorHex,
          sizeCode: src.sizeCode,
          qty: it.qty,
          defectQty: it.defectQty,
          goodQty: it.qty - it.defectQty,
          remark: it.remark,
        }
      }),
    },
    ...(receiptsMap[id] ?? []),
  ]
  return { id: rcpId, no, status: d.status }
})

mock('post', '/api/production-orders/:id/receipts/:rcpId/void', ({ params, body }) => {
  const id = Number(params.id)
  const list = receiptsMap[id] ?? []
  const r = list.find((x) => x.id === Number(params.rcpId)) ?? fail(404, '回货单不存在')
  r.status = 'VOIDED'
  r.voidReason = (body as { reason: string }).reason
  r.voidByName = '管理员'
  r.voidTime = '2026-11-15 21:40:00'
  r.actions = []
  return null
})

mock('get', '/api/production-orders/:id/notices', ({ params }) => prdNotices.get(Number(params.id)) ?? [])
mock('post', '/api/production-orders/:id/notices/:nid/handle', ({ params, body }) => {
  const d = prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在')
  const list = prdNotices.get(d.id) ?? []
  const n = list.find((x) => x.id === Number(params.nid)) ?? fail(404, '提醒不存在')
  n.handledByName = '管理员'
  n.handledTime = '2026-11-16 10:00:00'
  n.handleRemark = (body as { remark: string | null }).remark
  d.pendingNotices = list.filter((x) => !x.handledTime).length
  d.risk.upstream = d.pendingNotices > 0
  return null
})

mock('post', '/api/production-orders/:id/:act', ({ params, body }) => {
  const d = prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在')
  checkVersion(d.version, body)
  const act = params.act
  if (!d.actions.includes(act as ProductionAction)) fail(1002, '单据状态变了（别人刚改过），已重新取')
  const reopenTo: ProductionStatus = d.returnedQty > 0 ? 'RUNNING' : 'APPROVED'
  const next: Partial<Record<string, ProductionStatus>> = {
    submit: 'PENDING',
    withdraw: 'DRAFT',
    reject: 'DRAFT',
    audit: 'APPROVED',
    unaudit: 'DRAFT',
    close: 'CLOSED',
    reopen: reopenTo,
    void: 'VOIDED',
  }
  const to = next[act] ?? d.status
  d.status = to
  d.actions = actionsOf(to, d.kind)
  if (act === 'audit') {
    d.auditBy = 1
    d.auditByName = '管理员'
    d.auditTime = '2026-11-16 10:00:00'
  }
  d.version += 1
  return null
})
mock('get', '/api/production-orders/:id/attachments', () => [])
mock('put', '/api/production-orders/:id/attachments', ({ body }) => body)
mock('get', '/api/production-orders/:id/logs', () => [
  { id: 1, action: 'report', fromStatus: 'RUNNING', toStatus: 'RUNNING', remark: '车缝完成 130 件', operatorId: 1, operatorName: '管理员', createTime: '2026-11-15 18:00:00' },
])
mock('get', '/api/production-orders/:id', ({ params }) => prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在'))
mock('put', '/api/production-orders/:id', ({ params, body }) => {
  const d = prdOrders.find((x) => x.id === Number(params.id)) ?? fail(404, '生产单不存在')
  checkVersion(d.version, body)
  if (d.status !== 'DRAFT') fail(1002, '单据状态变了（别人刚改过），已重新取')
  const f = body as {
    processMode: string
    unitPrice: number
    priceSourceId: number | null
    priceChangeReason: string | null
    dueDate: string
    merchId: number
    settleMethod: string
    taxPct: number | null
    taxIncluded: boolean
    terms: string | null
    remark: string | null
  }
  Object.assign(d, {
    processMode: f.processMode,
    unitPrice: f.unitPrice,
    priceSourceId: f.priceSourceId,
    priceChangeReason: f.priceChangeReason,
    dueDate: f.dueDate,
    merchId: f.merchId,
    settleMethod: f.settleMethod,
    taxPct: f.taxPct ?? d.taxPct,
    taxIncluded: f.taxIncluded,
    terms: f.terms,
    remark: f.remark,
    totalAmount: Number((f.unitPrice * d.totalQty).toFixed(2)),
  })
  d.version += 1
  return d.version
})
mock('delete', '/api/production-orders/:id', ({ params }) => {
  const idx = prdOrders.findIndex((x) => x.id === Number(params.id))
  if (idx >= 0) prdOrders.splice(idx, 1)
  return null
})

// 生产看板
mock('get', '/api/production-board/cards', () => {
  const c: BoardCards = {
    runningCount: 1,
    runningQty: 180,
    waitingCount: 1,
    nearCount: 1,
    lateCount: 0,
    nodeLateCount: 0,
    issueCount: 1,
    monthReturnedQty: 120,
    nearDays: 7,
  }
  return c
})

mock('get', '/api/production-board/orders', ({ query }) => {
  let rows = prdOrders.map(toPrdRow)
  if (query.card === 'ISSUE') rows = rows.filter((r) => r.risk.issue)
  else if (query.card === 'NEAR') rows = rows.filter((r) => r.risk.near)
  else if (query.card === 'LATE') rows = rows.filter((r) => r.risk.late)
  return page(rows, query)
})

mock('get', '/api/production-board/factories', () => {
  const list: BoardFactoryRow[] = [
    { factoryId: 3, factoryShortName: '示例制衣一厂', categoryNames: ['成衣厂', '加工厂'], wipCount: 1, wipQty: 180, lateCount: 0 },
    { factoryId: 4, factoryShortName: '示例制衣二厂', categoryNames: ['成衣厂'], wipCount: 1, wipQty: 100, lateCount: 0 },
  ]
  return list
})

/** 首页"上游已变更"（mock/home.ts）汇总用：生产单里还没处理的提醒，一张单一行；处理掉以后这里跟着少 */
export function productionUpstreamRows(): UpstreamRow[] {
  const rows: UpstreamRow[] = []
  for (const d of prdOrders) {
    const notices = (prdNotices.get(d.id) ?? []).filter((n) => !n.handledTime)
    if (!notices.length) continue
    const st = PRODUCTION_STATUS[d.status]
    rows.push({
      doc: { type: 'PRODUCTION_ORDER', kind: d.kind, parentId: null, typeLabel: PRODUCTION_KIND[d.kind].label, id: d.id, no: d.no, status: d.status, statusLabel: st.label, statusTone: st.tone, ownerId: d.merchId, ownerName: d.merchName },
      notices,
    })
  }
  return rows
}
