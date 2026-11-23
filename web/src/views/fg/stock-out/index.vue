<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { onFgIdentityChange } from '../fgRead'
import type { FgDocType } from '@/types/fg'
import FgDocFormDialog from '../doc/FgDocFormDialog.vue'
import FgDocList from '../doc/FgDocList.vue'

/**
 * 成品出库单（/fg-out，docs/api/07 §1.3）：出库单列表（和入库单共用 FgDocList，认 ?status=DRAFT）。
 * 页头"其他出库"（拿样品、报废……）、"发货出库"（主按钮，照需求订单发）
 */
const route = useRoute()
const user = useUserStore()
const router = useRouter()
const listRef = ref<InstanceType<typeof FgDocList>>()

const companyId = ref<string | null>(typeof route.query.companyId === 'string' ? route.query.companyId : user.defaultCompanyId)
const formVisible = ref(false)
watch(companyId, () => { formVisible.value = false })
watch(() => route.query.companyId, (value) => {
  if (route.path !== '/fg-out') return
  if (typeof value === 'string') companyId.value = value
})
onFgIdentityChange(() => { companyId.value = null; formVisible.value = false })
const formType = ref<Extract<FgDocType, 'SHIP_OUT' | 'OTHER_OUT'>>('SHIP_OUT')

function openNew(type: Extract<FgDocType, 'SHIP_OUT' | 'OTHER_OUT'>) {
  formType.value = type
  formVisible.value = true
}

function openDoc(id: string, savedCompanyId: string) {
  void router.push(`/fg-out/${id}?companyId=${savedCompanyId}`)
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <CompanySelect v-model="companyId" style="width: 180px" placeholder="选择主体" aria-label="成品主体" />
        <el-button v-perm="'fg:doc:export'" icon="Download" :loading="listRef?.exporting" @click="listRef?.exportAll()">导出</el-button>
        <el-button v-perm="'fg:doc:add'" @click="openNew('OTHER_OUT')">其他出库</el-button>
        <el-button v-perm="'fg:doc:add'" type="primary" icon="Plus" @click="openNew('SHIP_OUT')">发货出库</el-button>
      </template>
    </PageHeader>

    <FgDocList ref="listRef" kind="OUT" :company-id="companyId" />

    <FgDocFormDialog v-model="formVisible" :initial-company-id="companyId" :doc-type="formType" @saved="openDoc" />
  </div>
</template>
