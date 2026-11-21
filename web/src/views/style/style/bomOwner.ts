import { computed } from 'vue'
import { useDict } from '@/composables/useDict'
import { useUserStore } from '@/stores/user'

export type BomOwner = 'DEV' | 'PROD'

export const BOM_OWNERS: readonly BomOwner[] = ['DEV', 'PROD']
/** 甲方 11-21：主料和工艺开发填，辅料生产填（面料、里料、工艺、鞋底归开发；辅料、标签吊牌、配件、包材归生产，字典里定） */
export const BOM_OWNER_TITLE: Record<BomOwner, string> = { DEV: '主料和工艺（开发填）', PROD: '辅料（生产填）' }
export const BOM_OWNER_SHORT: Record<BomOwner, string> = { DEV: '开发', PROD: '生产' }

/**
 * 物料清单开发、生产分工（RFC 012 §3）：物料类型在字典里写着归谁填（material_type 的文字是 PROD 的归生产，其余归开发，
 * 设置 › 字典里改）；有 techpack:bom:dev 的填开发的行，有 techpack:bom:prod 的填生产的行。页面上只是不让改，
 * 真正挡人的是后端保存时的 PackBomAccess
 */
export function useBomOwner() {
  const user = useUserStore()
  const types = useDict('material_type')
  const prodTypes = computed(() => types.options.value.filter((o) => o.textValue === 'PROD').map((o) => o.value))

  function ownerOf(materialType: string | null | undefined): BomOwner {
    return materialType && prodTypes.value.includes(materialType) ? 'PROD' : 'DEV'
  }
  function canFill(owner: BomOwner): boolean {
    return user.hasPerm(owner === 'PROD' ? 'techpack:bom:prod' : 'techpack:bom:dev')
  }
  const canFillAny = computed(() => canFill('DEV') || canFill('PROD'))

  /** 选物料弹窗（DataPick 的 params）：只能填一块的人只列这一块的物料类型 */
  const pickParams = computed(() => {
    const params: Record<string, string> = {}
    const dev = canFill('DEV')
    const prod = canFill('PROD')
    const list = prodTypes.value.join(',')
    // 只能填生产、又没有归生产的类型：什么都不列
    if (prod && !dev) params.materialTypes = list || '-'
    else if (dev && !prod && list) params.excludeMaterialTypes = list
    return params
  })

  return { ownerOf, canFill, canFillAny, pickParams }
}
