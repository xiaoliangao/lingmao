<script setup lang="ts">
import { computed } from 'vue'
import type { SampleNode, SampleOrder } from '@/types/sample'
import { NODE_STATE } from '@/types/production'
import { useUserStore } from '@/stores/user'
import ImageUpload from '@/components/ImageUpload.vue'
import StatusTag from '@/components/StatusTag.vue'
import { SAMPLE_ORDER_STATUS, SAMPLE_TYPE, REVIEW_LABEL, sampleWho } from './common'

const props = defineProps<{ order: SampleOrder; disabled?: boolean }>()
const emit = defineEmits<{ edit: [order: SampleOrder]; files: [order: SampleOrder]; node: [order: SampleOrder, mode: 'done' | 'undo' | 'plans', nodeId?: string] }>()
const user = useUserStore()
const canNode = computed(() => !props.disabled && props.order.current && props.order.actions.node && user.hasPerm('dev:sample:make'))
const canEdit = computed(() => !props.disabled && props.order.current && props.order.actions.edit && user.hasPerm('dev:sample:edit'))
const undoable = (node: SampleNode) => !!node.doneDate && ['MAKING', 'WAIT_REVIEW'].includes(props.order.status)
</script>
<template>
  <section class="sample-version" :data-sample-version="order.sampleVersion">
    <h3><span>第 {{ order.sampleVersion }} 版 · {{ SAMPLE_TYPE[order.sampleType] }}</span><span class="code dim">{{ order.no }}</span><StatusTag :tone="SAMPLE_ORDER_STATUS[order.status].tone">{{ SAMPLE_ORDER_STATUS[order.status].label }}</StatusTag><small v-if="!order.current" class="dim">历史版 · 只读</small></h3>
    <div class="version-actions"><el-button v-if="canEdit" link @click="emit('edit', order)">编辑本版</el-button><el-button v-if="canNode && order.status === 'MAKING'" link @click="emit('node', order, 'plans')">节点计划</el-button><el-button link @click="emit('files', order)">打样附件</el-button></div>
    <dl class="props"><dt>打样</dt><dd>{{ sampleWho(order) }}<span v-if="order.makeWay" class="dim"> · {{ order.makeWay === 'INTERNAL' ? '内部版房' : '外部工厂' }}</span></dd><dt>要样</dt><dd class="num">{{ order.needDate }}</dd><dt>件数</dt><dd class="num">{{ order.qty }}</dd><dt>范围</dt><dd>{{ order.colors.map((color) => `${color.code} ${color.name}`).join('、') }} · {{ order.sizeCodes.join(' / ') }}</dd><template v-if="order.reason"><dt>改版原因</dt><dd class="pre">{{ order.reason }}</dd></template><template v-if="order.requirement"><dt>打样要求</dt><dd class="pre">{{ order.requirement }}</dd></template></dl>
    <ol v-if="order.nodes.length" class="nodes">
      <li v-for="node in order.nodes" :key="node.id" :class="node.state.toLowerCase()">
        <i class="dot" /><div class="node-name"><strong>{{ node.nodeName }}</strong><StatusTag :tone="NODE_STATE[node.state].tone">{{ NODE_STATE[node.state].label }}</StatusTag></div>
        <div class="dim small">计划 {{ node.planDate }}<template v-if="node.doneDate"> · 完成 {{ node.doneDate }}</template><template v-if="node.ownerName"> · {{ node.ownerName }}</template><template v-if="node.doneByName"> · 报完 {{ node.doneByName }}</template></div>
        <p v-if="node.remark" class="dim pre small">{{ node.remark }}</p>
        <div v-if="canNode" class="node-actions"><el-button v-if="['MAKING', 'WAIT_REVIEW'].includes(order.status)" link @click="emit('node', order, 'done', node.id)">{{ node.doneDate ? '改完成日期' : '报完成' }}</el-button><el-button v-if="undoable(node)" link @click="emit('node', order, 'undo', node.id)">撤回完成</el-button></div>
      </li>
    </ol>
    <p v-else class="dim small">待派单，派单时生成节点计划。</p>
    <div v-if="order.review" class="review"><strong>评审{{ REVIEW_LABEL[order.review.result] }}</strong><p v-if="order.review.opinion" class="pre">{{ order.review.opinion }}</p><p class="dim small">{{ order.review.reviewers.map((person) => person.name).join('、') }} · {{ order.review.reviewDate }}</p><ImageUpload v-if="order.review.photos.length" :model-value="order.review.photos" disabled :max="9" /></div>
  </section>
</template>
<style scoped>
.sample-version { padding: 18px 0; border-bottom: 1px solid var(--lm-line); min-width: 0; }
h3 { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 0 0 8px; font-size: 14px; }
h3 .code { font-size: 12px; font-weight: 400; }
.version-actions { display: flex; flex-wrap: wrap; gap: 14px; margin-bottom: 10px; }
.props { display: grid; grid-template-columns: 64px minmax(0, 1fr) 50px minmax(0, 1fr); gap: 6px 12px; margin: 0 0 12px; font-size: 12.5px; }
dt { color: var(--lm-muted); } dd { margin: 0; overflow-wrap: anywhere; }
.pre { white-space: pre-wrap; overflow-wrap: anywhere; } dd.pre { grid-column: span 3; }
.nodes { margin: 10px 0 0; padding: 0 0 0 12px; list-style: none; }
.nodes li { position: relative; min-height: 52px; padding: 0 0 14px 20px; border-left: 1px solid var(--lm-line-strong); }
.nodes li:last-child { border-color: transparent; }
.dot { position: absolute; width: 8px; height: 8px; left: -4.5px; top: 8px; border-radius: 50%; background: var(--lm-faint); border: 2px solid var(--lm-paper); }
.done .dot { background: var(--lm-done-text); } .late .dot { background: var(--lm-danger-text); } .running .dot, .warn .dot { background: var(--lm-accent); }
.node-name { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; font-size: 12.5px; }
.node-actions { margin-top: 2px; }
.small { font-size: 11.5px; }.review { margin-top: 12px; font-size: 12.5px; }
@media (max-width: 720px) { .props { grid-template-columns: 64px minmax(0, 1fr); } dd.pre { grid-column: auto; } }
</style>
