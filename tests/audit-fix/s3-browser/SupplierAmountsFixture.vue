<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import SupplierSelect from '@/components/SupplierSelect.vue'
import InvDocFormDialog from '@/views/inventory/doc/InvDocFormDialog.vue'
import { useUserStore } from '@/stores/user'
import { getToken, setSessionSubject, setToken } from '@/utils/token'
import type { InvDocDetail } from '@/types/inventory'

const user = useUserStore()
user.info = {
  id: '9007199254740993', username: 'synthetic', nickname: '名称与金额回归', roles: [], menus: [],
  perms: ['inventory:doc:add', 'inventory:stock:list', 'purchase:order:list', 'techpack:cost:query'],
  defaultCompany: { id: '1', name: '合成主体甲' },
}
setSessionSubject(user.info.id)
const route = useRoute()
const provided = new URLSearchParams(location.search).get('view') === 'provided'
const appLayout = new URLSearchParams(location.search).get('layout') === 'app'
const supplierId = ref<string | null>('3')
const supplierLabel = ref<string | null>(provided ? '父级工厂甲' : null)
const companyId = ref('1')
const mounted = ref(true)
const dialogVisible = ref(false)
const editingDoc = ref<InvDocDetail | null>(null)
function changeIdentity() {
  setToken('synthetic-next-identity')
  user.token = getToken()
  if (user.info) user.info = { ...user.info, id: '9007199254740994', username: 'synthetic-next' }
  setSessionSubject('9007199254740994')
}
function openNew() { editingDoc.value = null; dialogVisible.value = true }
function openEdit() {
  editingDoc.value = {
    id: '9007199254743001', no: 'RK-SYN-001', version: 1, status: 'DRAFT', companyId: '1', companyName: '合成主体甲',
    remark: null, createBy: user.info!.id, createByName: '合成', createTime: '2026-11-23 10:00:00', updateTime: '2026-11-23 10:00:00',
    auditBy: null, auditByName: null, auditTime: null, actions: ['edit'], approval: null, pendingNotices: 0,
    kind: 'IN', docType: 'RETURN_IN', source: null, fromWarehouseId: '11', fromWarehouseName: '甲工厂仓', fromWarehouseType: 'FACTORY',
    toWarehouseId: '21', toWarehouseName: '合成面料仓', toWarehouseType: 'MATERIAL', partnerId: '3', partnerName: '工厂甲',
    docDate: '2026-11-23', reason: null, supplierDeliveryNo: null, logisticsNo: null, totalQty: '0.018', totalAmount: '0.14', posted: false,
    items: [{ id: '501', seq: 1, materialSkuId: '601', skuCode: 'ML-SYN-B', materialId: '701', materialCode: 'ML-SYN',
      materialName: '合成面料', materialType: 'FABRIC', colorName: '蓝色', colorHex: null, spec: null, stockUnit: 'M', image: null,
      posted: false, qty: 0.018, purchaseUnit: null, purchaseQty: null, conversionFactor: null, price: 7.5, amount: 0.14,
      dyeLot: null, rolls: null, sourceItemId: null, overReason: null, remark: null, fromStockQty: 10 }],
  }
  dialogVisible.value = true
}
</script>

<template>
  <router-view v-if="appLayout" />
  <main v-else class="supplier-amounts-fixture">
    <header><h1>供应商名称与金额预览 · 合成 HTTP / 实际组件</h1><output data-testid="route">{{ route.path }}</output></header>
    <router-view v-if="route.path.startsWith('/purchases/')" />
    <template v-else>
      <section class="selector-panel">
        <label>当前供应商</label>
        <div data-testid="supplier-select"><SupplierSelect v-if="mounted" v-model="supplierId" v-model:label="supplierLabel" :company-id="companyId" :category="['GARMENT', 'PROCESSOR']" clearable /></div>
        <output data-testid="selected-id">{{ supplierId }}</output>
        <nav>
          <el-button @click="supplierId = '3'">预选甲</el-button><el-button @click="supplierId = '4'">只换ID乙</el-button>
          <el-button @click="supplierId = '5'">慢ID</el-button><el-button @click="supplierId = '6'">快ID</el-button>
          <el-button @click="supplierId = '999'">未知ID</el-button><el-button @click="supplierId = '998'">失败ID</el-button>
          <el-button @click="companyId = companyId === '1' ? '2' : '1'">切换主体</el-button>
          <el-button @click="changeIdentity">切换身份</el-button>
          <el-button @click="mounted = !mounted">切换挂载</el-button>
        </nav>
      </section>
      <section><el-button @click="openNew">新建退料入库</el-button><el-button @click="openEdit">编辑退料及金额</el-button></section>
      <InvDocFormDialog v-model="dialogVisible" doc-type="RETURN_IN" :editing-doc="editingDoc" />
    </template>
  </main>
</template>

<style scoped>
.supplier-amounts-fixture { padding: 24px; min-height: 100vh; background: var(--lm-bg); }
header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
h1 { font-size: 16px; margin: 0; }
.selector-panel { margin-bottom: 30px; }
.selector-panel > label { display: block; margin-bottom: 8px; }
[data-testid="supplier-select"] { width: 360px; }
nav { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
nav .el-button + .el-button { margin-left: 0; }
</style>
