import { computed, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { commandCostRevaluation, costRevaluation, costRevaluationMetadata, costRevaluationResult, createCostRevaluation } from '../api/cost-revaluations'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { CostRevaluation, CostRevaluationAction, CostRevaluationCommand, CostRevaluationInput, CostRevaluationMetadata } from '../types/cost-revaluations'
import { revaluationActionLabels } from '../types/cost-revaluations'
import type { WorkflowSelections } from '../types/workflow'
import { today } from '../types/sales'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

interface Attempt {
  scope: string
  cacheKey: string
  key: string
  operation: string
  action: 'create' | CostRevaluationAction
  id?: string
  input: CostRevaluationInput | CostRevaluationCommand
}
const unresolved = new Map<string, Attempt>()

export interface CostRevaluationPanel {
  document: Ref<CostRevaluation | undefined>
  metadata: Ref<CostRevaluationMetadata>
  draft: CostRevaluationInput
  loading: Ref<boolean>; busy: Ref<boolean>; error: Ref<string>
  pending: Ref<Attempt | undefined>
  dirty: ComputedRef<boolean>; locked: ComputedRef<boolean>
  submitting: Ref<boolean>; workflowReady: Ref<boolean>; selections: Ref<WorkflowSelections>
  can: (action: string) => boolean
  load: () => Promise<void>
  searchSkus: (search: string) => Promise<void>
  retry: () => Promise<void>; verify: () => Promise<void>; save: () => Promise<void>
  act: (action: CostRevaluationAction) => Promise<void>; submit: () => Promise<void>
  close: (done?: () => void) => void
}

export function useCostRevaluationPanel(id: () => string | undefined, saved: (id: string) => void, closed: () => void, readOnly: () => boolean = () => false): CostRevaluationPanel {
  const session = useSessionStore()
  const scope = (): string => `${session.user?.id ?? ''}|${session.companyId ?? ''}`
  const cacheKey = (): string => `${scope()}|${document.value?.id ?? id() ?? 'new'}`
  const initialScope = scope()
  const document = ref<CostRevaluation>()
  const metadata = ref<CostRevaluationMetadata>({ warehouses: [], skus: [], skuLimit: 200 })
  const draft = reactive<CostRevaluationInput>({ warehouseId: '', skuId: '', startDate: today(), businessDate: today(), reason: '' })
  const baseline = JSON.stringify(draft)
  const pending = shallowRef(readOnly() ? undefined : unresolved.get(cacheKey()))
  const loading = ref(false), busy = ref(false), error = ref(''), submitting = ref(false), workflowReady = ref(false)
  const selections = ref<WorkflowSelections>({})
  const controller = new AbortController()
  let disposed = false, revision = 0, searchRevision = 0
  const dirty = computed(() => !document.value && JSON.stringify(draft) !== baseline)
  const locked = computed(() => busy.value || Boolean(pending.value))
  const atRisk = computed(() => locked.value || dirty.value)
  const can = (action: string): boolean => !readOnly() && initialScope === scope() && session.can('cost:view') && session.can('cost_revaluation:view') &&
    session.can(`cost_revaluation:${action === 'refresh' ? 'submit' : action}`)
  const visible = (): boolean => !disposed && initialScope === scope() && session.can('cost:view') && session.can('cost_revaluation:view')

  async function load(): Promise<void> {
    const current = ++revision
    loading.value = true
    error.value = ''
    try {
      if (id()) {
        const value = await costRevaluation(id()!, controller.signal)
        if (current === revision && visible()) document.value = value
      } else {
        const value = await costRevaluationMetadata(undefined, controller.signal)
        if (current === revision && visible()) metadata.value = value
      }
    } catch (cause: unknown) {
      if (!isCancelled(cause) && current === revision) error.value = messageOf(cause)
      if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) { document.value = undefined; closed() }
    } finally { if (current === revision) loading.value = false }
  }
  async function searchSkus(search: string): Promise<void> {
    const current = ++searchRevision
    try {
      const result = await costRevaluationMetadata(search, controller.signal)
      if (current === searchRevision && visible()) metadata.value = result
    } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  }
  function remember(value: Attempt | undefined): void {
    if (pending.value) unresolved.delete(pending.value.cacheKey)
    pending.value = value
    if (value) unresolved.set(value.cacheKey, value)
  }
  function accept(value: CostRevaluation): void {
    remember(undefined)
    document.value = value
    submitting.value = false
    selections.value = {}
    saved(value.id)
  }
  async function retry(): Promise<void> {
    const attempt = pending.value
    if (!attempt || busy.value || attempt.scope !== scope() || !can(attempt.action)) return
    busy.value = true
    error.value = ''
    try {
      const result = attempt.action === 'create'
        ? await createCostRevaluation(attempt.input as CostRevaluationInput, attempt.key, controller.signal)
        : await commandCostRevaluation(attempt.id!, attempt.action, attempt.input as CostRevaluationCommand, attempt.key, controller.signal)
      if (visible() && attempt.scope === scope() && can(attempt.action)) accept(result)
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
      if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500 && cause.code !== 'REQUEST_IN_PROGRESS') remember(undefined)
    } finally { busy.value = false }
  }
  async function verify(): Promise<void> {
    const attempt = pending.value
    if (!attempt || busy.value || attempt.scope !== scope() || !can(attempt.action)) return
    busy.value = true
    try {
      const result = await costRevaluationResult(attempt.operation, attempt.key)
      if (visible() && attempt.scope === scope() && can(attempt.action) && result.response) accept(result.response)
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
    } finally { busy.value = false }
  }
  function stage(action: Attempt['action'], input: Attempt['input']): void {
    remember({ scope: scope(), cacheKey: cacheKey(), action, id: document.value?.id, operation: `cost_revaluation.${action}`,
      key: newIdempotencyKey(), input: JSON.parse(JSON.stringify(input)) as Attempt['input'] })
  }
  async function save(): Promise<void> {
    if (id() || locked.value || !can('create')) return
    if (!draft.warehouseId || !draft.skuId || !draft.startDate || !draft.businessDate || !draft.reason.trim()) {
      error.value = '请选择仓库、商品、日期，并填写重估原因。'; return
    }
    stage('create', { ...draft, reason: draft.reason.trim() })
    await retry()
  }
  async function act(action: CostRevaluationAction): Promise<void> {
    if (!document.value || locked.value || !can(action) || !document.value.allowedActions.includes(action)) return
    if (action === 'submit') { submitting.value = true; return }
    try {
      await ElMessageBox.confirm(action === 'refresh' ? '重新生成预览后需重新提交审批。' : `确认${revaluationActionLabels[action]}？`, revaluationActionLabels[action], { type: 'warning' })
    } catch (cause: unknown) { if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause); return }
    stage(action, { version: document.value.version })
    await retry()
  }
  async function submit(): Promise<void> {
    if (!document.value || locked.value || !workflowReady.value || !can('submit')) return
    stage('submit', { version: document.value.version, workflowAssignees: selections.value })
    await retry()
  }
  function mayLeave(): boolean { return !locked.value && (!dirty.value || window.confirm('离开将放弃尚未保存的重估范围，确认离开？')) }
  function close(done: () => void = closed): void { if (mayLeave()) done() }
  const unregister = registerCompanyLeaveGuard(mayLeave, atRisk)
  onBeforeRouteLeave(() => mayLeave())
  watch(() => [session.companyId, session.user?.id, session.can('cost:view'), session.can('cost_revaluation:view')], () => {
    if (initialScope !== scope() || !session.can('cost:view') || !session.can('cost_revaluation:view')) {
      ++revision; ++searchRevision; controller.abort(); document.value = undefined; metadata.value = { warehouses: [], skus: [], skuLimit: 200 }; closed()
    }
  })
  onScopeDispose(() => { disposed = true; ++revision; controller.abort(); unregister() })
  void load()
  return { document, metadata, draft, loading, busy, error, pending, dirty, locked, submitting, workflowReady, selections,
    can, load, searchSkus, retry, verify, save, act, submit, close }
}
