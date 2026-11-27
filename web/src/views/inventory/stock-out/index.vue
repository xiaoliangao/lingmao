<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import { INV_DOC_TYPE, type InvDocType } from '@/types/inventory'
import type { IssueOption } from '@/types/production'
import DocList from '../doc/DocList.vue'
import InvDocFormDialog from '../doc/InvDocFormDialog.vue'
import PickProductionDialog from '../doc/PickProductionDialog.vue'

/**
 * 物料出库单（/stock-out，docs/api/04 §1.5）：出库单列表（和入库单共用 DocList，认 ?status=DRAFT）。
 * 页头"新建"下拉：调拨、其他出库（能手建的），另加"领料出库（选生产单）""耗料核销（选生产单）"——先选生产单，再走生成接口
 */
const router = useRouter()
const listRef = ref<InstanceType<typeof DocList>>()

const NEW_TYPES = (Object.keys(INV_DOC_TYPE) as InvDocType[]).filter((t) => INV_DOC_TYPE[t].kind === 'OUT' && !INV_DOC_TYPE[t].sourceRequired)
const formVisible = ref(false)
const formType = ref<InvDocType>('OTHER_OUT')
const pickVisible = ref(false)
const issueVisible = ref(false)
const issueProdId = ref<string | null>(null)
/** 选生产单是为了领料还是核销耗料 */
const pickFor = ref<'ISSUE_OUT' | 'CONSUME_OUT'>('ISSUE_OUT')

function onNew(command: InvDocType | 'ISSUE' | 'CONSUME') {
  if (command === 'ISSUE' || command === 'CONSUME') {
    pickFor.value = command === 'ISSUE' ? 'ISSUE_OUT' : 'CONSUME_OUT'
    pickVisible.value = true
    return
  }
  formType.value = command
  formVisible.value = true
}

function onPicked(order: IssueOption) {
  issueProdId.value = order.id
  issueVisible.value = true
}

function openDoc(id: string) {
  void router.push(`/stock-out/${id}`)
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'inventory:doc:export'" icon="Download" :loading="listRef?.exporting" @click="listRef?.exportAll()">导出</el-button>
        <el-dropdown v-perm="'inventory:doc:add'" trigger="click" @command="onNew">
          <el-button type="primary" icon="Plus">新建</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in NEW_TYPES" :key="t" :command="t">{{ INV_DOC_TYPE[t].label }}</el-dropdown-item>
              <el-dropdown-item command="ISSUE" divided>领料出库（选生产单）</el-dropdown-item>
              <el-dropdown-item command="CONSUME">耗料核销（选生产单）</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </PageHeader>

    <DocList ref="listRef" kind="OUT" />

    <InvDocFormDialog v-model="formVisible" :doc-type="formType" @saved="openDoc" />
    <PickProductionDialog v-model="pickVisible" :settle="pickFor === 'CONSUME_OUT'" @pick="onPicked" />
    <SourceInvDocDialog v-model="issueVisible" source-kind="PRODUCTION" :source-id="issueProdId" :doc-type="pickFor" @created="listRef?.reload()" />
  </div>
</template>
