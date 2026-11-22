<script setup lang="ts">
import { ref } from 'vue'
import TechPackTab from '@/views/style/style/TechPackTab.vue'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
import type { TechPackOverview } from '@/types/techpack'

const user = useUserStore()
user.info = { id: '9007199254740993', username: 'synthetic', nickname: '资料包回归', roles: [], perms: ['techpack:cost:query'], menus: [], defaultCompany: { id: '1', name: '合成主体' } }
setSessionSubject(user.info.id)
const pack = ref<InstanceType<typeof TechPackTab>>()
const overview: TechPackOverview = {
  styleId: '11', defaultPackId: '706', creatableStages: [], unmatchedColors: [], unitCostPackId: null, unitCostStage: null,
  packs: [
    { id: '706', stage: 'PROD', version: 1, status: 'DRAFT', versionNote: null, sourcePackId: null, sourceVersion: null, publishByName: null, publishTime: null, createByName: '回归工艺员', createTime: '2026-11-22 10:00:00', updateTime: '2026-11-22 10:00:00' },
    { id: '707', stage: 'PROD', version: 2, status: 'EFFECTIVE', versionNote: null, sourcePackId: '706', sourceVersion: 1, publishByName: '回归工艺员', publishTime: '2026-11-22 11:00:00', createByName: '回归工艺员', createTime: '2026-11-22 10:00:00', updateTime: '2026-11-22 11:00:00' },
    { id: '709', stage: 'DEV', version: 1, status: 'DRAFT', versionNote: null, sourcePackId: null, sourceVersion: null, publishByName: null, publishTime: null, createByName: '回归工艺员', createTime: '2026-11-22 10:00:00', updateTime: '2026-11-22 10:00:00' },
  ],
}
const controls = { open: (id: string, tab: 'spec' | 'bom' | 'craft' | 'cost') => pack.value!.open(id, tab) }
;(window as unknown as { __widePack: typeof controls }).__widePack = controls
</script>
<template>
  <main class="pack-fixture">
    <TechPackTab ref="pack" style-id="11" style-code="KS-WIDE" style-name="合成多尺码风衣" :overview="overview" />
  </main>
</template>
<style scoped>
.pack-fixture { min-height: 100vh; padding: 24px; background: var(--lm-bg); }
</style>
