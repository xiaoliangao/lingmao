<script setup lang="ts">
import ThumbImage from '@/components/ThumbImage.vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { factoryOrderApi, productionApi } from '@/api/production'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import NodeDots from '@/components/doc/NodeDots.vue'
import { useUserStore } from '@/stores/user'
import type { MobileOrderRow } from '@/types/production'
import { formatDate, formatQty } from '@/utils/format'
import { useLatestRequest } from '@/composables/useLatestRequest'

/**
 * 手机报工"我的跟单"（/mobile，docs/api/05 §1.4、§3.8）：按 360px 宽做，卡片 44px 以上好点。
 * 生产跟单是我、已下单或生产中的单，后端按风险排；剩余天数的颜色按后端的 risk。
 * 工厂账号（RFC 019 §7）看的是本厂已下单、生产中的单（factoryOrderApi），标题是厂名，不标异常
 */
const router = useRouter()
const user = useUserStore()
const factory = computed(() => user.info?.factory ?? null)

const rows = ref<MobileOrderRow[]>([])
/** 口径和看板一样：生产中算"在制"，已下单还没开工算"待开工"（走查 T-7d453c：原来全算在制） */
const countText = computed(() => {
  const running = rows.value.filter((r) => r.status === 'RUNNING').length
  const waiting = rows.value.length - running
  return [running && `在制 ${running} 单`, waiting && `待开工 ${waiting} 单`].filter(Boolean).join(' · ') || (factory.value ? '没有在做的单' : '没有跟的单')
})
const loading = ref(false)

const listRequest = useLatestRequest()
async function load() {
  const read = listRequest.begin()
  loading.value = true
  try {
    const result = await read.run(() => (factory.value ? factoryOrderApi.list() : productionApi.myFollow()))
    if (read.current()) rows.value = result
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

// 单独的外框里没有 keep-alive：从详情页回来是重新打开这一页，onMounted 会再取一次
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="m-shell">
    <header class="m-top">
      <div class="title-box">
        <h1>{{ factory ? factory.shortName : '我的跟单' }}</h1>
        <span class="dim">{{ user.info?.nickname ?? '' }} · {{ countText }}</span>
      </div>
      <button type="button" class="refresh-btn" @click="load">刷新</button>
    </header>

    <div v-if="!loading && !rows.length" class="m-empty">没有在做的单</div>

    <div v-else class="m-list">
      <button
        v-for="r in rows"
        :key="r.id"
        type="button"
        class="m-card"
        @click="router.push(`/mobile/${r.id}`)"
      >
        <ThumbImage v-if="r.image" :src="r.image.thumbUrl" alt="" class="pic" loading="lazy" />
        <span v-else class="pic blank" />

        <div class="info">
          <div class="line1">
            <span class="code style-code">{{ r.styleCode }}</span>
            <span v-if="r.risk.issue && !factory" class="issue-bang" title="有没关闭的异常">!</span>
            <span class="gap" />
            <span class="num due">{{ formatDate(r.expectDate ?? r.dueDate) }}</span>
            <DaysLeftTag :days-left="r.daysLeft" :risk="r.risk" />
          </div>
          <div class="line2 dim">
            <span>{{ r.factoryShortName }} · {{ formatQty(r.totalQty) }} 件</span>
            <span class="code">{{ r.no }}</span>
          </div>
          <div class="line3">
            <NodeDots :nodes="r.nodes" :current-name="r.currentNodeName" />
          </div>
        </div>
      </button>
    </div>
  </div>
</template>

<style scoped>
.m-shell {
  max-width: 560px;
  min-height: 100%;
  padding: 14px 14px 32px;
  margin: 0 auto;
  background: var(--lm-greige-2);
}

.m-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.title-box h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.title-box span {
  font-size: 12px;
}

.refresh-btn {
  min-height: 44px;
  padding: 0 14px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}

.m-empty {
  padding: 64px 16px;
  font-size: 14px;
  color: var(--lm-muted);
  text-align: center;
}

.m-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.m-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px;
  font: inherit;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.m-card:active {
  background: var(--lm-hover);
}

.pic {
  flex: none;
  width: 52px;
  height: 52px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.pic.blank {
  background: var(--lm-subtle);
}

.info {
  flex: 1;
  min-width: 0;
}

.line1 {
  display: flex;
  align-items: center;
  gap: 6px;
}

.style-code {
  font-size: 14px;
  font-weight: 600;
}

.issue-bang {
  display: inline-grid;
  place-items: center;
  width: 18px;
  height: 18px;
  font-size: 12px;
  font-weight: 700;
  color: var(--lm-on-accent);
  background: var(--lm-danger-text);
  border-radius: 99px;
}

.gap {
  flex: 1;
}

.due {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--lm-ink-2);
  /* 360px 宽的手机上别把日期断成两行 */
  white-space: nowrap;
}

.line2 {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin: 4px 0 6px;
  font-size: 12px;
}

.line3 {
  overflow: hidden;
}
</style>
