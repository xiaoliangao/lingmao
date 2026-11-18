<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
import type { ProductionDetail } from '@/types/production'
import OrderForm from '@/views/order/order/form.vue'
import OrderChangeDialog from '@/views/order/order/OrderChangeDialog.vue'
import ProdEditDialog from '@/views/production/order/ProdEditDialog.vue'
import SupplierFormDialog from '@/views/supplier/supplier/SupplierFormDialog.vue'
import MaterialForm from '@/views/basic/material/MaterialForm.vue'
import PurchaseForm from '@/views/purchase/order/form.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import PriceHistory from '@/components/price/PriceHistory.vue'
import { invDocApi } from '@/api/inventory'
import { newClientAction } from '@/utils/clientAction'
import { http } from '@/utils/request'
import { withCompanyScope } from '@/utils/companyScope'
import type { InvDocFromSourceReq } from '@/types/inventory'
import TechPackTab from '@/views/style/style/TechPackTab.vue'
import type { TechPackOverview } from '@/types/techpack'
import { scheduleApi } from '@/api/order'
import type { OrderChangeDetail, OrderDetail } from '@/types/order'

const mode = new URLSearchParams(location.search).get('mode')
const user = useUserStore()
user.info = { id: '9007199254740993', username: 'synthetic', nickname: '权限回归', roles: [], perms: mode === 'purchase-retry' ? ['purchase:order:submit'] : [], menus: [], defaultCompany: { id: '1', name: '测试主体' } }
setSessionSubject(user.info.id)
const router = useRouter()
const supplier = ref<InstanceType<typeof SupplierFormDialog>>()
const material = ref<InstanceType<typeof MaterialForm>>()
const productionOpen = ref(false)
const sourceOpen = ref(false)
const sourceKind = ref<'PURCHASE' | 'PRODUCTION'>('PURCHASE')
const pricesOpen = ref(false)
const changeOpen = ref(false)
const changeOrder = reactive({ id: '704', no: 'DD-SYNTHETIC', companyId: '1', requiredDate: '2026-12-18', styles: [{ styleId: '11', techPackId: '51', techPackVersion: 1, currentPackId: '51', currentPackVersion: 1 }], items: [{ id: '801', styleId: '11', styleSkuId: '101', colorId: '1', sizeCode: 'M', qty: 2, scheduledQty: 0, price: 17.25, lineDueDate: null, remark: null }] }) as unknown as OrderDetail
const changeDoc = reactive({ id: '707', no: 'BG-SYNTHETIC', version: 3, companyId: '1', orderId: '704', orderNo: 'DD-SYNTHETIC', status: 'DRAFT', cancel: false, reason: '合成变更原因', responsibleParty: 'SELF', feeNote: '合成原费用说明', items: [] }) as unknown as OrderChangeDetail
const packOverview = { styleId: '11', defaultPackId: '706', creatable: false, unmatchedColors: [], unitCostPackId: null, packs: [{ id: '706', stage: 'PROD', version: 1, status: 'DRAFT', versionNote: null, publishByName: null, publishTime: null, createByName: '回归工艺员', createTime: '2026-11-18 10:00:00', updateTime: '2026-11-18 10:00:00' }] } satisfies TechPackOverview
const production = reactive({
  id: '701', no: 'SC-SYNTHETIC', version: 3, kind: 'OUTSOURCE', companyId: '1', orderId: '11', styleId: '12',
  factoryId: '13', factoryShortName: '合成工厂', processMode: 'CUT', unitPrice: 17.25, archivePrice: 17.25,
  priceSourceId: '14', dueDate: '2026-12-01', merchId: '15', merchName: '回归跟单', settleMethod: 'MONTHLY',
  taxPct: 13, taxIncluded: true, terms: '', remark: '', priceChangeReason: '',
}) as unknown as ProductionDetail
const controls = {
  role: async (perms: string[]) => { user.info!.perms = perms; await nextTick() },
  seedOrder: () => router.push('/?styleId=11'),
  change: async (priced: boolean) => {
    if (priced) { changeDoc.feeNote = '合成原费用说明'; changeOrder.items[0]!.price = 17.25 }
    else { delete changeDoc.feeNote; delete changeOrder.items[0]!.price }
    changeDoc.items = priced ? [{ id: '9007199254741001', seq: 1, changeType: 'PRICE', orderItemId: null, styleId: '11', styleCode: 'KS-SYNTHETIC', styleSkuId: null, skuCode: null, fromQty: null, toQty: null, fromDate: null, toDate: null, fromPrice: 17.25, toPrice: 19.5, fromTechPackId: null, fromVersion: null, toTechPackId: null, toVersion: null, text: '合成改价' }] : []
    changeOpen.value = true
    await nextTick()
  },
  schedule: () => scheduleApi.schedule('11', [{ styleId: '11', factoryId: '13', processMode: 'CUT', unitPrice: 17.25, priceSourceId: '9007199254740993', priceChangeReason: null, dueDate: '2026-12-01', merchId: '15', settleMethod: 'MONTHLY', terms: null, remark: null, items: [{ orderItemId: '801', qty: 2 }] }], { companyId: '1' }),
  production: async (priced: boolean) => {
    if (priced) { production.unitPrice = 17.25; production.archivePrice = 17.25 }
    else { delete production.unitPrice; delete production.archivePrice }
    productionOpen.value = true
    await nextTick()
  },
  supplier: () => supplier.value!.open(),
  material: () => material.value!.openCreate(),
  source: async (kind: 'PURCHASE' | 'PRODUCTION') => { sourceKind.value = kind; sourceOpen.value = true; await nextTick() },
  prices: async (on: boolean) => { pricesOpen.value = on; await nextTick() },
  sourceWithoutScope: async (kind: 'PURCHASE' | 'PRODUCTION') => {
    const body: InvDocFromSourceReq = newClientAction({ docType: kind === 'PURCHASE' ? 'PURCHASE_IN' : 'ISSUE_OUT', docDate: '2026-11-18', fromWarehouseId: kind === 'PURCHASE' ? null : '31', toWarehouseId: kind === 'PURCHASE' ? '31' : '32', supplierDeliveryNo: null, logisticsNo: null, reason: null, remark: null, itemIds: null, post: false, items: [{ sourceItemId: '801', materialSkuId: '901', qty: 1, dyeLot: null, rolls: null, overReason: null, remark: null }] })
    const pending = kind === 'PURCHASE' ? invDocApi.fromPurchase('702', body) : invDocApi.fromProduction('703', body)
    body.items![0]!.qty = 999
    await pending
  },
  invalidCompany: async (url: string, expected?: string) => {
    try { await withCompanyScope('1', () => http.put(url, { companyId: '2', expectedCompanyId: expected, version: 3 }, { silent: true })); return 'sent' }
    catch (error) { return error instanceof Error ? error.message : String(error) }
  },
}
;(window as unknown as { __security: typeof controls }).__security = controls
</script>
<template>
  <main class="security-fixture">
    <TechPackTab v-if="mode === 'pack-edit'" style-id="11" style-code="KS-SYNTHETIC" style-name="合成测试款式" :overview="packOverview" />
    <PurchaseForm v-else-if="mode === 'purchase-edit' || mode === 'purchase-retry'" />
    <OrderForm v-else />
    <PriceHistory v-if="pricesOpen" :subject="{ kind: 'supplier', id: '13', code: 'GY-SYNTHETIC', name: '合成供应商' }" />
    <ProdEditDialog v-model="productionOpen" :doc="production" />
    <OrderChangeDialog v-model="changeOpen" :order="changeOrder" :editing="changeDoc" />
    <SupplierFormDialog ref="supplier" />
    <MaterialForm ref="material" />
    <SourceInvDocDialog v-model="sourceOpen" :source-kind="sourceKind" :source-id="sourceKind === 'PURCHASE' ? '702' : '703'" :doc-type="sourceKind === 'PURCHASE' ? 'PURCHASE_IN' : 'ISSUE_OUT'" />
  </main>
</template>
<style scoped>
.security-fixture { padding: 20px; background: var(--lm-bg); min-height: 100vh; }
</style>
