import { computed, ref, shallowRef, watch } from 'vue'
import { ApiError, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import { clientActionUuid, unknownActionResult } from '@/utils/clientAction'
import { sessionEpoch } from '@/utils/token'
import { useEntityContext } from '@/composables/useEntityContext'

function message(cause: unknown): string {
  if (cause instanceof ApiError && [403, 404].includes(cause.code)) return '权限已变化或记录不可见，请重新读取'
  if (cause instanceof ApiError) return `${cause.message}${cause.traceId ? `（编号 ${cause.traceId}）` : ''}`
  return '暂时无法完成，请重试'
}

/** 一个弹窗的一次动作：未知结果保留原参数与原 key，禁止改表单后盲目重发。 */
export function useActionReceipt<P extends object, R>(
  scope: () => string | null,
  allowed: () => boolean,
  perform: (body: P, options: RequestOptions) => Promise<R>,
) {
  const busy = ref(false), error = ref<string | null>(null), failure = shallowRef<unknown>(null)
  const pending = shallowRef<{ body: P; key: string; companyId: string | undefined; epoch: string; scope: string } | null>(null)
  let controller: AbortController | undefined
  const reset = () => { controller?.abort(); controller = undefined; pending.value = null; busy.value = false; error.value = null; failure.value = null }
  const context = useEntityContext(reset)
  watch(scope, () => { context.cancel(); reset() }, { flush: 'sync' })
  async function execute(body: P, companyId?: string): Promise<R | null> {
    const ownScope = scope()
    if (busy.value || !ownScope || !allowed()) return null
    pending.value ??= { body: snapshotRequestBody(body), key: clientActionUuid(), companyId, epoch: sessionEpoch(), scope: ownScope }
    const action = pending.value
    if (action.scope !== ownScope || action.companyId !== companyId) return null
    const current = context.capture()
    controller = new AbortController(); busy.value = true; error.value = null; failure.value = null
    try {
      const receipt = await perform(action.body, { companyId: action.companyId, authEpoch: action.epoch,
        idempotencyKey: action.key, signal: controller.signal, silent: true })
      if (!current() || scope() !== ownScope) return null
      pending.value = null
      return receipt
    } catch (cause) {
      if (!current() || scope() !== ownScope) return null
      error.value = message(cause); failure.value = cause
      const uncertain = cause instanceof ApiError ? cause.transient || unknownActionResult(cause.code) : true
      if (!uncertain) pending.value = null
      return null
    } finally { if (current()) busy.value = false }
  }
  async function retry(): Promise<R | null> { const own = pending.value; return own ? execute(own.body, own.companyId) : null }
  return { busy, error, failure, pendingBody: computed(() => pending.value?.body ?? null), retry, uncertain: computed(() => !!pending.value && !busy.value), locked: computed(() => !!pending.value), execute, reset }
}
