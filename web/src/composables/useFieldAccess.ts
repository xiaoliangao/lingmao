import { computed } from 'vue'
import { useUserStore } from '@/stores/user'
import { FIELD_PERMISSIONS } from '@/utils/fieldAccess'

/** 能不能看也决定能不能改；最终由服务端把关。 */
export function useFieldAccess() {
  const user = useUserStore()
  return {
    canSeeCost: computed(() => user.hasPerm(FIELD_PERMISSIONS.cost)),
    canSeeOrderPrice: computed(() => user.hasPerm(FIELD_PERMISSIONS.orderPrice)),
    canSeeProductionPrice: computed(() => user.hasPerm(FIELD_PERMISSIONS.productionPrice)),
    canSeeSupplierContact: computed(() => user.hasPerm(FIELD_PERMISSIONS.supplierContact)),
    canSeeSupplierBank: computed(() => user.hasPerm(FIELD_PERMISSIONS.supplierBank)),
  }
}
