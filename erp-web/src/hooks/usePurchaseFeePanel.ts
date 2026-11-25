import { computed, onMounted, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import {
  commandPurchaseFee,
  purchaseFee,
  purchaseFeeMetadata,
  purchaseFeeSources,
  previewPurchaseFee,
  savePurchaseFee,
} from '../api/purchase-fees'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { today } from '../types/sales'
import { feeActionLabels } from '../types/purchase-fees'
import type {
  PurchaseFee,
  PurchaseFeeAction,
  PurchaseFeeCommand,
  PurchaseFeeInput,
  PurchaseFeePreview,
  PurchaseFeeSource,
} from '../types/purchase-fees'
import type { ProcurementDocument } from '../types/procurement'
import type { WorkflowSelections } from '../types/workflow'
import { feeDocumentDraft, feeDraftInput } from '../utils/purchase-fees'
import type { FeeDraft } from '../utils/purchase-fees'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

interface PanelProps {
  id?: string
  initialReceipt?: ProcurementDocument
}
type Pending =
  | { kind: 'save'; input: PurchaseFeeInput; id?: string; key: string }
  | {
      kind: 'command'
      id: string
      action: PurchaseFeeAction
      input: PurchaseFeeCommand
      key: string
    }

interface FeePanel {
  document: Ref<PurchaseFee | undefined>
  draft: FeeDraft
  metadata: Ref<Awaited<ReturnType<typeof purchaseFeeMetadata>>>
  sources: Ref<PurchaseFeeSource[]>
  sourceCursor: Ref<string | null>
  sourceId: Ref<string>
  sourceLoading: Ref<boolean>
  error: Ref<string>
  loading: Ref<boolean>
  busy: Ref<boolean>
  editing: Ref<boolean>
  preview: Ref<PurchaseFeePreview | undefined>
  pending: Ref<Pending | undefined>
  submitting: Ref<boolean>
  selections: Ref<WorkflowSelections>
  workflowReady: Ref<boolean>
  locked: ComputedRef<boolean>
  actions: ComputedRef<PurchaseFeeAction[]>
  load(): Promise<void>
  edit(): Promise<void>
  loadSources(more?: boolean): Promise<void>
  addSource(): void
  save(): Promise<void>
  write(): Promise<void>
  act(action: PurchaseFeeAction): Promise<void>
  submit(): Promise<void>
  previewCost(): Promise<void>
  close(done?: () => void): Promise<void>
}

export function usePurchaseFeePanel(
  props: PanelProps,
  saved: (id: string) => void,
  closed: () => void,
): FeePanel {
  const session = useSessionStore()
  const document = ref<PurchaseFee>()
  const draft = reactive<FeeDraft>({
    partnerId: '',
    allocationMethod: 'amount',
    fees: [{ feeType: 'freight', amount: '', taxRate: '0', remark: '' }],
    businessDate: today(),
    reason: '',
    sources: [],
  })
  const metadata = ref<Awaited<ReturnType<typeof purchaseFeeMetadata>>>({
    partners: [],
    costEnabled: true,
    allocationMethod: 'amount',
  })
  const sources = ref<PurchaseFeeSource[]>([]),
    sourceCursor = ref<string | null>(null),
    sourceId = ref('')
  const error = ref(''),
    loading = ref(false),
    sourceLoading = ref(false),
    busy = ref(false),
    editing = ref(!props.id)
  const preview = ref<PurchaseFeePreview>(),
    pending = shallowRef<Pending>(),
    submitting = ref(false)
  const selections = ref<WorkflowSelections>({}),
    workflowReady = ref(false)
  const baseline = ref(''),
    controller = new AbortController()
  let sourceRevision = 0
  const locked = computed(() => busy.value || Boolean(pending.value))
  const dirty = computed(
    () => !loading.value && editing.value && baseline.value !== JSON.stringify(draft),
  )
  const atRisk = computed(() => dirty.value || locked.value)
  function mayLeave(): boolean {
    if (locked.value) {
      error.value = '本次请求尚未完成或结果未确认，请先在当前单据确认结果。'
      return false
    }
    return !dirty.value || window.confirm('采购费用修改尚未保存，确定离开？')
  }
  function beforeUnload(event: BeforeUnloadEvent): void {
    if (atRisk.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  const removeCompanyGuard = registerCompanyLeaveGuard(mayLeave, atRisk)
  onBeforeRouteLeave(mayLeave)
  const actions = computed(() =>
    (document.value?.allowedActions ?? []).filter((action): action is PurchaseFeeAction =>
      Object.hasOwn(feeActionLabels, action),
    ),
  )

  function replaceDraft(value: FeeDraft): void {
    Object.assign(draft, value)
    baseline.value = JSON.stringify(draft)
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const currentId = props.id ?? document.value?.id
      if (currentId) document.value = await purchaseFee(currentId, controller.signal)
      else {
        const receipt = props.initialReceipt
        metadata.value = await purchaseFeeMetadata(controller.signal)
        replaceDraft({
          partnerId: '',
          businessDate: today(),
          reason: '',
          allocationMethod: metadata.value.allocationMethod,
          fees: [{ feeType: 'freight', amount: '', taxRate: '0', remark: '' }],
          sources: (receipt?.lines ?? [])
            .filter(
              (line) =>
                line.baseQty !== line.returnedBaseQty &&
                (!line.pendingReturnBaseQty || /^0(?:\.0+)?$/.test(line.pendingReturnBaseQty)),
            )
            .map((line) => ({
              receiptLineId: line.id,
              label: `${receipt?.documentNo ?? ''} · ${line.skuName ?? line.sku?.name ?? '原收货行'}`,
            })),
        })
        await loadSources()
      }
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      loading.value = false
    }
  }

  async function edit(): Promise<void> {
    if (!document.value || locked.value) return
    replaceDraft(feeDocumentDraft(document.value))
    editing.value = true
    error.value = ''
    try {
      metadata.value = await purchaseFeeMetadata(controller.signal)
      await loadSources()
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
    }
  }

  async function loadSources(more = false): Promise<void> {
    if (!editing.value || (more && !sourceCursor.value)) return
    const current = ++sourceRevision
    sourceLoading.value = true
    if (!more) {
      sources.value = []
      sourceCursor.value = null
      sourceId.value = ''
    }
    try {
      const result = await purchaseFeeSources(
        more ? (sourceCursor.value ?? undefined) : undefined,
        controller.signal,
      )
      if (current !== sourceRevision) return
      sources.value = more ? [...sources.value, ...result.items] : result.items
      sourceCursor.value = result.nextCursor
    } catch (cause: unknown) {
      if (current === sourceRevision && !isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      if (current === sourceRevision) sourceLoading.value = false
    }
  }

  function addSource(): void {
    const source = sources.value.find((item) => item.id === sourceId.value)
    if (!source) {
      error.value = '请选择已过账的原收货行'
      return
    }
    if (draft.sources.some((line) => line.receiptLineId === source.id)) {
      error.value = '该收货行已经添加'
      return
    }
    if (draft.sources.length >= 100) {
      error.value = '一张费用单最多100行'
      return
    }
    draft.sources.push({
      receiptLineId: source.id,
      label: `${source.receiptNo} · ${source.skuName}（${source.unitName}）`,
    })
    sourceId.value = ''
    error.value = ''
  }

  async function save(): Promise<void> {
    if (locked.value) return
    try {
      pending.value = {
        kind: 'save',
        input: feeDraftInput(draft, document.value?.version),
        id: document.value?.id,
        key: newIdempotencyKey(),
      }
      await write()
    } catch (cause: unknown) {
      error.value = messageOf(cause)
    }
  }

  async function write(): Promise<void> {
    const request = pending.value
    if (!request || busy.value) return
    busy.value = true
    error.value = ''
    try {
      const result =
        request.kind === 'save'
          ? await savePurchaseFee(request.input, request.key, request.id, controller.signal)
          : await commandPurchaseFee(
              request.id,
              request.action,
              request.input,
              request.key,
              controller.signal,
            )
      document.value = result
      pending.value = undefined
      preview.value = undefined
      editing.value = false
      submitting.value = false
      selections.value = {}
      saved(result.id)
    } catch (cause: unknown) {
      if (!isCancelled(cause)) {
        error.value = messageOf(cause)
        if (
          cause instanceof ApiError &&
          cause.status >= 400 &&
          cause.status < 500 &&
          cause.code !== 'REQUEST_IN_PROGRESS'
        )
          pending.value = undefined
      }
    } finally {
      busy.value = false
    }
  }

  async function act(action: PurchaseFeeAction): Promise<void> {
    if (!document.value || locked.value) return
    if (action === 'submit') {
      submitting.value = true
      return
    }
    let reason: string | undefined
    try {
      if (action === 'reject')
        reason = (
          await ElMessageBox.prompt('请填写驳回原因', '驳回采购费用', {
            inputValidator: (value: string) => Boolean(value.trim()) || '请填写原因',
          })
        ).value
      else
        await ElMessageBox.confirm(
          `确认${feeActionLabels[action]}这张采购费用单？`,
          feeActionLabels[action],
          { type: 'warning' },
        )
    } catch (cause: unknown) {
      if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
      return
    }
    pending.value = {
      kind: 'command',
      id: document.value.id,
      action,
      input: { version: document.value.version, reason },
      key: newIdempotencyKey(),
    }
    await write()
  }

  async function submit(): Promise<void> {
    if (!document.value || !workflowReady.value || locked.value) return
    pending.value = {
      kind: 'command',
      id: document.value.id,
      action: 'submit',
      input: {
        version: document.value.version,
        workflowAssignees: Object.fromEntries(
          Object.entries(selections.value).map(([node, users]) => [node, [...users]]),
        ),
      },
      key: newIdempotencyKey(),
    }
    await write()
  }

  async function previewCost(): Promise<void> {
    if (!document.value || locked.value) return
    busy.value = true
    error.value = ''
    try {
      preview.value = await previewPurchaseFee(document.value.id, controller.signal)
    } catch (cause: unknown) {
      if (!isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      busy.value = false
    }
  }

  async function close(done: () => void = closed): Promise<void> {
    if (locked.value) return
    if (dirty.value) {
      try {
        await ElMessageBox.confirm('关闭后将放弃未保存的修改。', '关闭费用单', { type: 'warning' })
      } catch (cause: unknown) {
        if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
        return
      }
    }
    done()
  }

  watch(
    () => session.companyId,
    () => {
      closed()
    },
  )
  onMounted(() => {
    window.addEventListener('beforeunload', beforeUnload)
    void load()
  })
  onScopeDispose(() => {
    sourceRevision++
    controller.abort()
    removeCompanyGuard()
    window.removeEventListener('beforeunload', beforeUnload)
  })
  return {
    document,
    draft,
    metadata,
    sources,
    sourceCursor,
    sourceId,
    sourceLoading,
    error,
    loading,
    busy,
    editing,
    preview,
    pending,
    submitting,
    selections,
    workflowReady,
    locked,
    actions,
    load,
    edit,
    loadSources,
    addSource,
    save,
    write,
    act,
    submit,
    previewCost,
    close,
  }
}
