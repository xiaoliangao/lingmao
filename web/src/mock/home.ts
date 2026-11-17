import type { DocRef } from '@/types/biz'
import type { DeliveryRow, HomeKpi, HomeTodos, TodoGroup, UpstreamList, UpstreamRow } from '@/types/home'
import { mock } from './index'
import { productionUpstreamRows } from './production'

/**
 * 首页、上游已变更的假接口（docs/api/06）。
 * - "上游已变更"不另存一份：从生产 mock 的提醒里现取（算料单、采购单的已经是真后端，不在这里），在 /upstream 或单据详情里处理掉，这里跟着少
 * - 待办、上游已变更：canSeeAll = true（看得到"全部 / 我的"和负责人筛选）；选"我的"或别的负责人时只剩"待我审核"一组，看得出筛了
 * 名称都是编的
 */
const upstreamRows = (): UpstreamRow[] => productionUpstreamRows()

const ref = (r: Omit<DocRef, 'kind' | 'parentId' | 'ownerId' | 'ownerName'> & Partial<DocRef>): DocRef => ({
  kind: null,
  parentId: null,
  ownerId: 1,
  ownerName: '管理员',
  ...r,
})

mock('get', '/api/board/home/kpi', () => {
  const kpi: HomeKpi = {
    dev: { styleCount: 12, packDraftCount: 1 },
    order: { qty: 900, count: 2, pendingCount: 1, unscheduledCount: 0, changePendingCount: 1 },
    production: { qty: 280, count: 2, lateCount: 1, nodeLateCount: 0, issueCount: 1 },
    material: { transitLineCount: 1, arrivalLateCount: 0, purchasePendingCount: 0, mrpDraftCount: 0 },
  }
  return kpi
})

mock('get', '/api/board/home/todos', ({ query }) => {
  const filtered = query.mine === 'true' || (!!query.ownerId && query.ownerId !== '1')
  const upstream = upstreamRows()
  const groups: TodoGroup[] = [
    {
      key: 'AUDIT',
      total: 2,
      items: [
        {
          ref: ref({ type: 'ORDER', typeLabel: '需求订单', id: 102, no: 'DD202611150002', status: 'PENDING', statusLabel: '待审核', statusTone: 'pending' }),
          title: '需求订单 DD202611150002',
          sub: '— · 300 件 · 交期 12-18 · 第 1 级',
          image: null,
        },
        {
          ref: ref({ type: 'ORDER_CHANGE', typeLabel: '订单变更单', id: 201, no: 'BG202611150001', status: 'PENDING', statusLabel: '待审核', statusTone: 'pending' }),
          title: '订单变更 BG202611150001',
          sub: '原单 DD202611150001 · 卡其 M 140 → 160',
          image: null,
        },
      ],
    },
    {
      key: 'PRODUCTION_LATE',
      total: 1,
      items: [
        {
          ref: ref({ type: 'PRODUCTION_ORDER', kind: 'OUTSOURCE', typeLabel: '外发加工单', id: 501, no: 'JG202611130001', status: 'RUNNING', statusLabel: '生产中', statusTone: 'progress' }),
          title: 'JG202611130001 双排扣廓形短风衣',
          sub: '示例制衣一厂 · 节点「车缝」延期',
          image: null,
        },
      ],
    },
    {
      key: 'ISSUE',
      total: 1,
      items: [
        {
          ref: ref({ type: 'PRODUCTION_ISSUE', kind: 'OUTSOURCE', parentId: 501, typeLabel: '生产异常', id: 71, no: 'YC202611150001', status: 'OPEN', statusLabel: '待处理', statusTone: 'pending' }),
          title: '做工：首批抽检 3 件右驳头止口略宽',
          sub: 'JG202611130001 · 管理员 上报于 11-15',
          image: null,
        },
      ],
    },
    {
      key: 'UPSTREAM',
      total: upstream.length,
      items: upstream.map((u) => ({ ref: u.doc, title: `${u.doc.typeLabel} ${u.doc.no}`, sub: u.notices[0]?.content ?? '', image: null })),
    },
  ]
  const todos: HomeTodos = {
    canSeeAll: true,
    groups: groups.filter((g) => g.total > 0 && (!filtered || g.key === 'AUDIT')),
    counts: [
      { key: 'ORDER_PENDING' as const, count: 1 },
      { key: 'CHANGE_PENDING' as const, count: 1 },
      { key: 'PRODUCTION_LATE' as const, count: filtered ? 0 : 1 },
      { key: 'PRODUCTION_ISSUE' as const, count: filtered ? 0 : 1 },
      { key: 'UPSTREAM' as const, count: filtered ? 0 : upstream.length },
      { key: 'REORDER' as const, count: 1 },
    ].filter((c) => c.count > 0),
  }
  return todos
})

mock('get', '/api/board/home/deliveries', () => {
  const list: DeliveryRow[] = [
    {
      ref: ref({ type: 'PRODUCTION_ORDER', kind: 'OUTSOURCE', typeLabel: '外发加工单', id: 501, no: 'JG202611130001', status: 'RUNNING', statusLabel: '生产中', statusTone: 'progress' }),
      sub: '双排扣廓形短风衣 · 示例制衣一厂',
      image: null,
      dueDate: '2026-11-14',
      daysLeft: -2,
      doneQty: 60,
      totalQty: 180,
    },
    {
      ref: ref({ type: 'PRODUCTION_ORDER', kind: 'BUY', typeLabel: '成品采购单', id: 502, no: 'CP202611140001', status: 'APPROVED', statusLabel: '已下单', statusTone: 'active' }),
      sub: '双排扣廓形短风衣 · 示例制衣二厂',
      image: null,
      dueDate: '2026-11-21',
      daysLeft: 5,
      doneQty: 20,
      totalQty: 120,
    },
  ]
  return list
})

mock('get', '/api/board/upstream', ({ query }) => {
  const list: UpstreamList = {
    canSeeAll: true,
    rows: upstreamRows().filter(
      (r) => (!query.type || r.doc.type === query.type) && (!query.ownerId || String(r.doc.ownerId) === query.ownerId),
    ),
  }
  return list
})
