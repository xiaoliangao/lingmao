import { reactive } from 'vue'

/**
 * 错误清单对话框的状态。全局只有一个对话框（App.vue 里的 BizErrorDialog），
 * request.ts 收到带清单的错误（1001、1003、1004、1005）时调 showBizError 打开它。
 * 不放进 Pinia：request.ts 不在组件里，用模块级的状态最直接
 */
const state = reactive({
  visible: false,
  code: 0,
  message: '',
  items: [] as unknown[],
})

export function showBizError(code: number, message: string, items: unknown[]) {
  Object.assign(state, { visible: true, code, message, items })
}

export function useBizError() {
  return state
}
