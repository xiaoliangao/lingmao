import type { DocRef } from '@/types/biz'

/**
 * 一张单据的详情页地址：1005 的单据清单、"下游单据"页签、首页待办、变更提醒里点单号都用它。
 * 地址表在 docs/api/README.md §2；加一种单据在这里加一项
 */
export function docRoute(ref: Pick<DocRef, 'type' | 'kind' | 'id' | 'parentId'>): string | null {
  switch (ref.type) {
    case 'SUPPLIER':
      return `/suppliers/${ref.id}`
    case 'MATERIAL':
      return `/materials/${ref.id}`
    case 'STYLE':
      return `/styles/${ref.id}`
    case 'TECH_PACK':
      return ref.parentId ? `/styles/${ref.parentId}?tab=techpack&techPackId=${ref.id}` : null
    case 'ORDER':
      return `/orders/${ref.id}`
    case 'ORDER_CHANGE':
      return `/order-changes/${ref.id}`
    case 'MRP_CALC':
      return `/mrp/${ref.id}`
    case 'PURCHASE_ORDER':
      return `/purchases/${ref.id}`
    case 'INV_DOC':
      return ref.kind === 'OUT' ? `/stock-out/${ref.id}` : `/stock-in/${ref.id}`
    case 'INV_COUNT':
      return `/stock-takes/${ref.id}`
    case 'PRODUCTION_ORDER':
      return ref.kind === 'BUY' ? `/fp-purchases/${ref.id}` : `/production/${ref.id}`
    case 'PRODUCTION_RECEIPT':
    case 'PRODUCTION_ISSUE':
    case 'PRODUCTION_NODE_REPORT':
      // 挂在生产单详情里；不知道是哪种生产单，先去外发加工单的地址，详情页按 kind 自己纠正
      return ref.parentId ? `/production/${ref.parentId}` : null
    default:
      return null
  }
}
