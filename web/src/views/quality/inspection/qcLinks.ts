import { docRoute } from '@/utils/docRoute'

/** 来源单、生产单的详情地址（质检页的几个表共用） */
export function sourceLink(row: { kind: string; invDocId?: string | null; sourceId?: string; prdReceiptId?: string | null; prdOrderId: string | null; prdOrderKind: string | null }): string | null {
  if (row.kind === 'INCOMING') {
    const id = row.invDocId ?? row.sourceId
    return id ? docRoute({ type: 'INV_DOC', kind: 'IN', id, parentId: null }) : null
  }
  return orderLink(row, row.kind === 'FINAL' ? 'receipts' : undefined)
}

export function orderLink(row: { prdOrderId: string | null; prdOrderKind: string | null }, tab?: string): string | null {
  if (!row.prdOrderId) return null
  const base = docRoute({ type: 'PRODUCTION_ORDER', kind: row.prdOrderKind, id: row.prdOrderId, parentId: null })
  return base && tab ? `${base}?tab=${tab}` : base
}
