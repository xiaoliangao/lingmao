import type { DocRef } from '@/types/biz'

/**
 * 一张单据的详情页地址：1005 的单据清单、"下游单据"页签、首页待办、变更提醒里点单号都用它。
 * 地址表在 docs/api/README.md §2；加一种单据在这里加一项
 */
export function docRoute(ref: Pick<DocRef, 'type' | 'kind' | 'id' | 'parentId' | 'companyId'>): string | null {
  const companyQuery = ref.companyId ? `companyId=${encodeURIComponent(ref.companyId)}` : ''
  switch (ref.type) {
    case 'SUPPLIER':
      return `/suppliers/${ref.id}`
    case 'MATERIAL':
      return `/materials/${ref.id}`
    case 'STYLE':
      return `/styles/${ref.id}`
    case 'SAMPLE':
      return `/samples/${ref.id}${companyQuery ? `?${companyQuery}` : ''}`
    case 'SAMPLE_ORDER':
    case 'SAMPLE_REVIEW':
      return ref.parentId ? `/samples/${ref.parentId}?tab=versions${companyQuery ? `&${companyQuery}` : ''}` : null
    case 'TECH_PACK':
      return ref.parentId ? `${ref.kind === 'SAMPLE' ? '/samples' : '/styles'}/${ref.parentId}?tab=techpack&techPackId=${ref.id}${ref.kind === 'SAMPLE' && companyQuery ? `&${companyQuery}` : ''}` : null
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
    case 'FG_DOC':
      return `${ref.kind === 'OUT' ? '/fg-out' : '/fg-in'}/${ref.id}${ref.companyId ? `?companyId=${encodeURIComponent(ref.companyId)}` : ''}`
    case 'INV_COUNT':
      return `/stock-takes/${ref.id}`
    case 'PRODUCTION_ORDER':
      return ref.kind === 'BUY' ? `/fp-purchases/${ref.id}` : `/production/${ref.id}`
    // 挂在生产单详情里，打开对应的页签（05 §1.4）；不知道是哪种生产单，先去外发加工单的地址，详情页按 kind 自己纠正
    case 'PRODUCTION_RECEIPT':
      return ref.parentId ? `/production/${ref.parentId}?tab=receipts` : null
    case 'PRODUCTION_ISSUE':
      return ref.parentId ? `/production/${ref.parentId}?tab=issues` : null
    case 'PRODUCTION_NODE_REPORT':
      return ref.parentId ? `/production/${ref.parentId}?tab=progress` : null
    default:
      return null
  }
}
