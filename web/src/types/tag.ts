/** 公共标签 API 08；对象标签版本与业务单头版本独立。 */
export type TagObjectType =
  | 'SAMPLE' | 'STYLE' | 'MATERIAL' | 'SUPPLIER' | 'TECH_PACK' | 'ORDER' | 'MRP_CALC'
  | 'PURCHASE_ORDER' | 'INV_DOC' | 'INV_COUNT' | 'PRODUCTION_ORDER' | 'PRODUCTION_RECEIPT' | 'FG_DOC'

export interface PublicTag {
  id: string
  name: string
  archived: boolean
  version: number
}

export interface ObjectTagSnapshot {
  version: number
  tags: PublicTag[]
}

export interface TagReplace {
  version: number
  names: string[]
  retainIds: string[]
  tagVersions: Record<string, number>
}

export interface TagQuery {
  keyword?: string
  size?: number
  includeIds?: string[]
}

export const TAG_PERMISSIONS: Record<TagObjectType, { read: string; write: string }> = {
  SAMPLE: { read: 'dev:sample:list', write: 'dev:sample:edit' },
  STYLE: { read: 'style:style:list', write: 'style:style:edit' },
  MATERIAL: { read: 'basic:material:list', write: 'basic:material:edit' },
  SUPPLIER: { read: 'supplier:supplier:list', write: 'supplier:supplier:edit' },
  TECH_PACK: { read: 'techpack:pack:list', write: 'techpack:pack:edit' },
  ORDER: { read: 'order:order:list', write: 'order:order:edit' },
  MRP_CALC: { read: 'purchase:mrp:list', write: 'purchase:mrp:edit' },
  PURCHASE_ORDER: { read: 'purchase:order:list', write: 'purchase:order:edit' },
  INV_DOC: { read: 'inventory:doc:list', write: 'inventory:doc:edit' },
  INV_COUNT: { read: 'inventory:count:list', write: 'inventory:count:edit' },
  PRODUCTION_ORDER: { read: 'production:order:list', write: 'production:order:edit' },
  PRODUCTION_RECEIPT: { read: 'production:order:list', write: 'production:receipt:add' },
  FG_DOC: { read: 'fg:doc:list', write: 'fg:doc:edit' },
}

export const TAG_LIMIT = 30
export const TAG_OPTION_LIMIT = 100
