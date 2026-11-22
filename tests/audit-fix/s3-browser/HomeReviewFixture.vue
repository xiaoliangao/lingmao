<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import HomePage from '@/views/home/index.vue'
import OrderAuditDialog from '@/views/order/order/OrderAuditDialog.vue'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
import type { OrderDetail } from '@/types/order'

const user = useUserStore()
user.info = {
  id: '9007199254740993', username: 'synthetic', nickname: '前端回归', roles: [], menus: [],
  perms: ['style:style:list', 'order:order:list', 'production:order:list', 'purchase:order:list', 'order:order:add'],
  defaultCompany: { id: '1', name: '合成主体' },
}
setSessionSubject(user.info.id)
const visible = ref(false)
const order = reactive<OrderDetail>({
  id: '9007199254741001', no: 'DD-9007199254741001', version: 3, status: 'PENDING', actions: ['audit'],
  companyId: '1', companyName: '合成主体', remark: null, createBy: user.info.id, createByName: '前端回归',
  createTime: '2026-11-22 10:00:00', updateTime: '2026-11-22 10:00:00', auditBy: null, auditByName: null, auditTime: null,
  approval: null, pendingNotices: 0, orderType: 'OWN', customerId: null, customerName: null, customerOrderNo: null,
  contractNo: null, brand: null, orderDate: '2026-11-22', requiredDate: '2026-12-18', commitDate: null,
  salesId: user.info.id, salesName: '前端回归', merchId: user.info.id, merchName: '前端回归', currency: 'CNY',
  deliveryReq: null, totalQty: 100, source: 'MANUAL', scheduledQty: 0, returnedQty: 0, styles: [], items: [], pendingChange: null,
})
const controls = {
  async open(id: string) {
    order.id = id
    order.no = `DD-${id}`
    visible.value = true
    await nextTick()
  },
}
;(window as unknown as { __homeReview: typeof controls }).__homeReview = controls
</script>
<template>
  <main class="home-review-fixture">
    <HomePage />
    <OrderAuditDialog v-model="visible" :order="order" />
  </main>
</template>
<style scoped>
.home-review-fixture { min-height: 100vh; padding: 24px; background: var(--lm-bg); }
</style>
