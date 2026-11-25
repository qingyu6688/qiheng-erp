import { computed, inject, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { commandResult, salesCommand } from '../api/sales'
import { saveSourcelessCreditRefund, saveSourcelessReturn } from '../api/sourceless-returns'
import { workflowEnd, workflowTaskAction } from '../api/workflow'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import type {
  SourcelessCreditRefundInput,
  SourcelessReturnInput,
} from '../types/sourceless-returns'
import type { WorkflowInstance, WorkflowTask } from '../types/workflow'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

export type SourcelessKind = 'sales-returns' | 'refund-requests'
interface Attempt {
  cacheKey: string
  key: string
  operation: string
  businessId?: string
  action: string
  requiresCost: boolean
  uncertain: boolean
  run: () => Promise<string>
}

// 仅在当前应用内保留未确认请求；强制卸载抽屉后重开也不能换键重复创建。
const unresolved = new Map<string, Attempt>()

export interface SourcelessMutation {
  error: Ref<string>
  busy: Ref<boolean>
  locked: ComputedRef<boolean>
  uncertain: ComputedRef<boolean>
  can: (action: string) => boolean
  saveReturn: (input: SourcelessReturnInput) => Promise<void>
  saveRefund: (input: SourcelessCreditRefundInput) => Promise<void>
  command: (action: string, data: Record<string, unknown>) => Promise<void>
  review: (task: WorkflowTask, action: 'approve' | 'reject', comment: string) => Promise<void>
  end: (
    instance: WorkflowInstance,
    action: 'cancel' | 'terminate',
    comment: string,
  ) => Promise<void>
  retry: () => Promise<void>
  verify: () => Promise<void>
  mayLeave: () => boolean
}

function snapshot<T>(value: T): T {
  // 输入均为接口定义中的JSON标量及集合，冻结后保持原十进制字符串和原版本。
  return JSON.parse(JSON.stringify(value)) as T
}

export function useSourcelessMutation(
  kind: SourcelessKind,
  documentId: () => string | undefined,
  dirty: Readonly<Ref<boolean>>,
  confirmed: (id: string) => void,
  closed: () => void,
): SourcelessMutation {
  const session = useSessionStore()
  const readOnly = inject('erp.readOnlyPreview', false)
  const base = kind === 'sales-returns' ? 'sales_return' : 'refund_request'
  const scope = (): string => `${session.user?.id ?? ''}|${session.companyId ?? ''}|${kind}`
  const cacheKey = (): string => `${scope()}|${documentId() ?? 'new'}`
  const pending = shallowRef<Attempt | undefined>(readOnly ? undefined : unresolved.get(cacheKey()))
  const busy = ref(false),
    error = ref('')
  const locked = computed(() => busy.value || Boolean(pending.value))
  const uncertain = computed(() => Boolean(pending.value?.uncertain))
  const atRisk = computed(() => locked.value || dirty.value)
  let disposed = false

  function can(action: string): boolean {
    if (readOnly || !session.companyId || !session.user || !session.can(`${base}:view`))
      return false
    if (kind === 'refund-requests' && !session.can('sales_return:view')) return false
    if (action === 'workflow_cancel') return true
    if (action === 'workflow_terminate') return session.can('workflow_instance:terminate')
    const review = action === 'approve' || action === 'reject'
    if (!session.can(`${base}:${action === 'reject' ? 'approve' : action}`)) return false
    return (
      kind !== 'sales-returns' ||
      (review ? session.can('cost:view') : session.can('sales_return:sourceless'))
    )
  }

  function current(attempt: Attempt): boolean {
    return !disposed && attempt.cacheKey === cacheKey() && pending.value?.key === attempt.key
  }
  function remember(attempt: Attempt | undefined): void {
    if (pending.value) unresolved.delete(pending.value.cacheKey)
    pending.value = attempt
    if (attempt) unresolved.set(attempt.cacheKey, attempt)
  }
  function finish(attempt: Attempt, id: string): void {
    if (!current(attempt)) return
    if (!/^[1-9]\d{0,18}$/.test(id))
      throw new Error('原请求已返回，但单据编号不完整，请继续确认结果。')
    remember(undefined)
    error.value = ''
    if (
      !session.can(`${base}:view`) ||
      (kind === 'refund-requests' && !session.can('sales_return:view'))
    ) {
      closed()
      return
    }
    confirmed(id)
  }
  function failed(attempt: Attempt, cause: unknown): void {
    if (!current(attempt)) return
    if (!isCancelled(cause)) error.value = messageOf(cause)
    const definitive =
      cause instanceof ApiError &&
      cause.status >= 400 &&
      cause.status < 500 &&
      ![408, 425, 429].includes(cause.status) &&
      cause.code !== 'REQUEST_IN_PROGRESS'
    if (definitive && !attempt.uncertain) remember(undefined)
    else remember({ ...attempt, uncertain: true })
  }

  async function retry(): Promise<void> {
    const attempt = pending.value
    if (!attempt || busy.value || !current(attempt)) return
    if (!can(attempt.action) || (attempt.requiresCost && !session.can('cost:view'))) {
      error.value = '当前权限不允许重试，请先确认原请求结果。'
      return
    }
    busy.value = true
    error.value = ''
    try {
      finish(attempt, await attempt.run())
    } catch (cause: unknown) {
      failed(attempt, cause)
    } finally {
      if (!disposed && attempt.cacheKey === cacheKey()) busy.value = false
    }
  }

  async function verify(): Promise<void> {
    const attempt = pending.value
    if (!attempt || busy.value || !current(attempt) || readOnly) return
    busy.value = true
    error.value = ''
    try {
      const result = await commandResult(attempt.operation, attempt.key)
      if (!current(attempt)) return
      if (result.status === 'succeeded') finish(attempt, attempt.businessId ?? result.response.id)
      else error.value = '原请求仍在处理中，请稍后继续确认。'
    } catch (cause: unknown) {
      if (current(attempt) && !isCancelled(cause)) {
        error.value =
          cause instanceof ApiError && cause.status === 404
            ? '尚未查到原请求结果，请继续查询或使用同一请求重试。'
            : messageOf(cause)
      }
    } finally {
      if (!disposed && attempt.cacheKey === cacheKey()) busy.value = false
    }
  }

  async function stage(
    action: string,
    operation: string,
    id: string | undefined,
    requiresCost: boolean,
    run: (key: string) => Promise<string>,
  ): Promise<void> {
    if (locked.value || !can(action)) return
    if (requiresCost && !session.can('cost:view')) {
      error.value = '当前没有填写或复核成本的权限。'
      return
    }
    const key = newIdempotencyKey()
    remember({
      cacheKey: cacheKey(),
      key,
      operation,
      businessId: id,
      action,
      requiresCost,
      uncertain: false,
      run: () => run(key),
    })
    await retry()
  }
  async function saveReturn(input: SourcelessReturnInput): Promise<void> {
    if (kind !== 'sales-returns') return
    const data = snapshot(input),
      id = documentId()
    await stage(
      id ? 'update' : 'create',
      `${base}.${id ? 'update' : 'create'}`,
      id,
      data.lines.some((line) => line.manualUnitCost != null),
      async (key) => (await saveSourcelessReturn(data, key, id)).id,
    )
  }
  async function saveRefund(input: SourcelessCreditRefundInput): Promise<void> {
    if (kind !== 'refund-requests') return
    const data = snapshot(input),
      id = documentId()
    await stage(
      id ? 'update' : 'create',
      `${base}.${id ? 'update' : 'create'}`,
      id,
      false,
      async (key) => (await saveSourcelessCreditRefund(data, key, id)).id,
    )
  }
  async function command(action: string, input: Record<string, unknown>): Promise<void> {
    const id = documentId()
    if (!id) return
    const data = snapshot(input)
    await stage(action, `${base}.${action}`, id, false, async (key) => {
      await salesCommand(kind, id, action, data, key)
      return id
    })
  }
  async function review(
    task: WorkflowTask,
    action: 'approve' | 'reject',
    comment: string,
  ): Promise<void> {
    const id = documentId()
    if (!id || !task.canHandle || task.status !== 'pending') return
    const taskId = task.id,
      data = { version: task.version, comment }
    await stage(action, `workflow_task.${action}`, id, kind === 'sales-returns', async (key) => {
      await workflowTaskAction(taskId, action, data, key)
      return id
    })
  }
  async function end(
    instance: WorkflowInstance,
    action: 'cancel' | 'terminate',
    comment: string,
  ): Promise<void> {
    const id = documentId()
    if (!id || !(action === 'cancel' ? instance.canCancel : instance.canTerminate)) return
    const instanceId = instance.id,
      version = instance.version
    await stage(`workflow_${action}`, `workflow_instance.${action}`, id, false, async (key) => {
      await workflowEnd(instanceId, action, version, comment, key)
      return id
    })
  }

  function mayLeave(): boolean {
    if (locked.value) {
      error.value = '原请求尚未完成或结果未确认，请先确认结果。'
      return false
    }
    return !dirty.value || window.confirm('修改尚未保存，确定离开？')
  }
  function beforeUnload(event: BeforeUnloadEvent): void {
    if (atRisk.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  const unregister = registerCompanyLeaveGuard(mayLeave, atRisk)
  onBeforeRouteLeave(mayLeave)
  window.addEventListener('beforeunload', beforeUnload)
  watch(
    () => [session.companyId, session.user?.id, documentId()],
    (next, previous) => {
      if (pending.value)
        unresolved.set(pending.value.cacheKey, { ...pending.value, uncertain: true })
      pending.value = readOnly ? undefined : unresolved.get(cacheKey())
      busy.value = false
      error.value = ''
      if (next[0] !== previous[0] || next[1] !== previous[1]) closed()
    },
    { flush: 'sync' },
  )
  onScopeDispose(() => {
    if (pending.value) unresolved.set(pending.value.cacheKey, { ...pending.value, uncertain: true })
    disposed = true
    unregister()
    window.removeEventListener('beforeunload', beforeUnload)
  })
  return {
    error,
    busy,
    locked,
    uncertain,
    can,
    saveReturn,
    saveRefund,
    command,
    review,
    end,
    retry,
    verify,
    mayLeave,
  }
}
