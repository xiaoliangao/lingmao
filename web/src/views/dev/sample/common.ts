import type { StatusTone } from '@/types/biz'
import type { SampleOrder, SampleOrderStatus, SampleShowStatus, SampleType } from '@/types/sample'

export const SAMPLE_STATUS: Record<SampleShowStatus, { label: string; tone: StatusTone }> = {
  WAIT_ASSIGN: { label: '待派单', tone: 'pending' }, MAKING: { label: '打样中', tone: 'progress' },
  WAIT_REVIEW: { label: '待评审', tone: 'pending' }, PASSED: { label: '已通过', tone: 'pending' },
  CONVERTED: { label: '已转大货', tone: 'done' }, ELIMINATED: { label: '已淘汰', tone: 'closed' },
}
export const SAMPLE_ORDER_STATUS: Record<SampleOrderStatus, { label: string; tone: StatusTone }> = {
  WAIT_ASSIGN: SAMPLE_STATUS.WAIT_ASSIGN, MAKING: SAMPLE_STATUS.MAKING, WAIT_REVIEW: SAMPLE_STATUS.WAIT_REVIEW,
  PASSED: { label: '已通过', tone: 'done' }, ELIMINATED: SAMPLE_STATUS.ELIMINATED,
  REVISE: { label: '需改版', tone: 'closed' }, CANCELLED: { label: '已取消', tone: 'void' },
}
export const SAMPLE_TYPE: Record<SampleType, string> = { FIRST: '初样', REVISE: '改版样', FULL_SET: '齐色齐码样', PRE_PRODUCTION: '产前样' }
export const REVIEW_LABEL = { PASS: '通过', REVISE: '改版', ELIMINATE: '淘汰' }
export const SAMPLE_LOG_ACTIONS = { create: '建样衣', update: '改样衣', remove: '删除', 'order-update': '改打样单', dispatch: '派单',
  'node-plan': '改节点计划', 'node-done': '报完成', 'node-undo': '撤回完成', review: '评审', stop: '终止', reopen: '恢复开发', convert: '转大货', images: '换图片', 'order-files': '打样附件' }
export const SAMPLE_LOG_STATUS = { DEVELOPING: '开发中', ...Object.fromEntries(Object.entries(SAMPLE_ORDER_STATUS).map(([key, value]) => [key, value.label])), CONVERTED: '已转大货' }
export const samplePath = (id: string, companyId: string) => `/samples/${id}?companyId=${encodeURIComponent(companyId)}`
export const sampleListPath = (companyId?: string | null) => `/samples${companyId ? `?companyId=${encodeURIComponent(companyId)}` : ''}`
export const orderScope = (order: SampleOrder) => `${order.companyId}:${order.sampleId}:${order.id}:${order.sampleVersionLock}:${order.version}`
export const sampleWho = (order: Pick<SampleOrder, 'makeWay' | 'makerName' | 'factoryName'>) =>
  order.makeWay === 'INTERNAL' ? (order.makerName ?? '版师未指定') : order.makeWay === 'EXTERNAL' ? (order.factoryName ?? '工厂未指定') : '未派单'
