import { computed, inject, onMounted, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import {
  commandSalesPriceAdjustment,
  salesPriceAdjustment,
  salesPriceAdjustmentMetadata,
  salesPriceAdjustmentSources,
  previewSalesPriceAdjustment,
  saveSalesPriceAdjustment,
} from '../api/sales-price-adjustments'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { today } from '../types/sales'
import { priceActionLabels } from '../types/price-adjustments'
import type { PriceAdjustmentAction, PriceAdjustmentCommand } from '../types/price-adjustments'
import type {
  SalesPriceAdjustment,
  SalesPriceAdjustmentInput,
  SalesPricePreview,
  SalesPriceSource,
} from '../types/sales-price-adjustments'
import type { SalesDocument } from '../types/sales'
import type { WorkflowSelections } from '../types/workflow'
import { salesPriceDocumentDraft, salesPriceDraftInput } from '../utils/sales-price-adjustments'
import type { SalesPriceDraft } from '../utils/sales-price-adjustments'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

export interface SalesPricePanelProps {
  id?: string
  initialShipment?: SalesDocument
}
type Pending =
  | { kind: 'save'; input: SalesPriceAdjustmentInput; id?: string; key: string }
  | {
      kind: 'command'
      id: string
      action: PriceAdjustmentAction
      input: PriceAdjustmentCommand
      key: string
    }

interface SalesPricePanel {
  document: Ref<SalesPriceAdjustment | undefined>
  draft: SalesPriceDraft
  metadata: Ref<Awaited<ReturnType<typeof salesPriceAdjustmentMetadata>>>
  sources: Ref<SalesPriceSource[]>
  sourceCursor: Ref<string | null>
  sourceId: Ref<string>
  sourceLoading: Ref<boolean>
  error: Ref<string>
  loading: Ref<boolean>
  busy: Ref<boolean>
  editing: Ref<boolean>
  preview: Ref<SalesPricePreview | undefined>
  pending: Ref<Pending | undefined>
  submitting: Ref<boolean>
  selections: Ref<WorkflowSelections>
  workflowReady: Ref<boolean>
  locked: ComputedRef<boolean>
  actions: ComputedRef<PriceAdjustmentAction[]>
  load(): Promise<void>
  edit(): Promise<void>
  loadSources(more?: boolean): Promise<void>
  addSource(): void
  save(): Promise<void>
  write(): Promise<void>
  act(action: PriceAdjustmentAction): Promise<void>
  submit(): Promise<void>
  previewPosting(): Promise<void>
  close(done?: () => void): Promise<void>
}

export function useSalesPriceAdjustmentPanel(
  props: SalesPricePanelProps,
  saved: (id: string) => void,
  closed: () => void,
): SalesPricePanel {
  const session = useSessionStore()
  const readOnlyPreview = inject('erp.readOnlyPreview', false)
  const document = ref<SalesPriceAdjustment>()
  const draft = reactive<SalesPriceDraft>({
    partnerId: '',
    warehouseId: '',
    businessDate: today(),
    reason: '',
    lines: [],
  })
  const metadata = ref<Awaited<ReturnType<typeof salesPriceAdjustmentMetadata>>>({
    partners: [],
    warehouses: [],
  })
  const sources = ref<SalesPriceSource[]>([]),
    sourceCursor = ref<string | null>(null),
    sourceId = ref('')
  const error = ref(''),
    loading = ref(false),
    sourceLoading = ref(false),
    busy = ref(false),
    editing = ref(!props.id)
  const preview = ref<SalesPricePreview>(),
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
    return !dirty.value || window.confirm('销售调价修改尚未保存，确定离开？')
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
    (readOnlyPreview ? [] : (document.value?.allowedActions ?? [])).filter(
      (action): action is PriceAdjustmentAction => Object.hasOwn(priceActionLabels, action),
    ),
  )

  function replaceDraft(value: SalesPriceDraft): void {
    Object.assign(draft, value)
    baseline.value = JSON.stringify(draft)
  }

  function readError(cause: unknown): void {
    if (isCancelled(cause)) return
    error.value = messageOf(cause)
    if (cause instanceof ApiError && [403, 404].includes(cause.status)) {
      document.value = undefined
      preview.value = undefined
      editing.value = false
      sources.value = []
      metadata.value = { partners: [], warehouses: [] }
      replaceDraft({ partnerId: '', warehouseId: '', businessDate: today(), reason: '', lines: [] })
    }
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const currentId = props.id ?? document.value?.id
      if (currentId) {
        document.value = undefined
        document.value = await salesPriceAdjustment(currentId, controller.signal)
      } else {
        const shipment = props.initialShipment
        replaceDraft({
          partnerId: shipment?.customerId ?? shipment?.customer?.id ?? '',
          warehouseId: shipment?.warehouseId ?? '',
          businessDate: today(),
          reason: '',
          lines: [],
        })
        metadata.value = await salesPriceAdjustmentMetadata(controller.signal)
        await loadSources()
        sourceId.value =
          sources.value.find((item) => item.shipmentId === props.initialShipment?.id)?.id ?? ''
      }
    } catch (cause: unknown) {
      readError(cause)
    } finally {
      loading.value = false
    }
  }

  async function edit(): Promise<void> {
    if (!document.value || locked.value) return
    replaceDraft(salesPriceDocumentDraft(document.value))
    editing.value = true
    error.value = ''
    try {
      metadata.value = await salesPriceAdjustmentMetadata(controller.signal)
      await loadSources()
    } catch (cause: unknown) {
      readError(cause)
    }
  }

  function initialCursor(): string | undefined {
    const shipment = props.initialShipment
    if (
      !shipment ||
      draft.partnerId !== (shipment.customerId ?? shipment.customer?.id) ||
      draft.warehouseId !== shipment.warehouseId
    )
      return
    const ids = (shipment.lines ?? [])
      .map((line) => line.id)
      .filter((id) => /^[1-9][0-9]*$/.test(id))
      .map((id) => BigInt(id))
    if (!ids.length) return
    return (ids.reduce((max, id) => (id > max ? id : max)) + 1n).toString()
  }

  async function loadSources(more = false): Promise<void> {
    if (!editing.value || !draft.partnerId || !draft.warehouseId || (more && !sourceCursor.value))
      return
    const current = ++sourceRevision
    sourceLoading.value = true
    if (!more) {
      sources.value = []
      sourceCursor.value = null
      sourceId.value = ''
    }
    try {
      const result = await salesPriceAdjustmentSources(
        draft.partnerId,
        draft.warehouseId,
        more ? (sourceCursor.value ?? undefined) : initialCursor(),
        controller.signal,
      )
      if (current !== sourceRevision) return
      sources.value = more ? [...sources.value, ...result.items] : result.items
      sourceCursor.value = result.nextCursor
    } catch (cause: unknown) {
      if (current === sourceRevision) readError(cause)
    } finally {
      if (current === sourceRevision) sourceLoading.value = false
    }
  }

  function addSource(): void {
    const source = sources.value.find((item) => item.id === sourceId.value)
    if (!source) {
      error.value = '请选择已签收的原发货行'
      return
    }
    if (draft.lines.some((line) => line.shipmentLineId === source.id)) {
      error.value = '该发货行已经添加'
      return
    }
    if (draft.lines.length >= 100) {
      error.value = '一张调价单最多100行'
      return
    }
    draft.lines.push({
      shipmentLineId: source.id,
      mode: 'amount',
      value: '',
      label: `${source.shipmentNo} · ${source.skuName}（${source.unitName}）`,
    })
    sourceId.value = ''
    error.value = ''
  }

  async function save(): Promise<void> {
    if (locked.value || readOnlyPreview) return
    try {
      pending.value = {
        kind: 'save',
        input: salesPriceDraftInput(draft, document.value?.version),
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
    if (!request || busy.value || readOnlyPreview) return
    busy.value = true
    error.value = ''
    try {
      const result =
        request.kind === 'save'
          ? await saveSalesPriceAdjustment(
              request.input,
              request.key,
              request.id,
              controller.signal,
            )
          : await commandSalesPriceAdjustment(
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
        readError(cause)
        if (cause instanceof ApiError && cause.status < 500 && cause.code !== 'REQUEST_IN_PROGRESS')
          pending.value = undefined
      }
    } finally {
      busy.value = false
    }
  }

  async function act(action: PriceAdjustmentAction): Promise<void> {
    if (!document.value || locked.value || readOnlyPreview) return
    if (action === 'submit') {
      submitting.value = true
      return
    }
    let reason: string | undefined
    try {
      if (action === 'reject')
        reason = (
          await ElMessageBox.prompt('请填写驳回原因', '驳回销售调价', {
            inputValidator: (value: string) => Boolean(value.trim()) || '请填写原因',
          })
        ).value
      else
        await ElMessageBox.confirm(
          `确认${priceActionLabels[action]}这张销售调价单？`,
          priceActionLabels[action],
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
    if (!document.value || !workflowReady.value || locked.value || readOnlyPreview) return
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

  async function previewPosting(): Promise<void> {
    if (!document.value || locked.value) return
    busy.value = true
    error.value = ''
    try {
      preview.value = await previewSalesPriceAdjustment(document.value.id, controller.signal)
    } catch (cause: unknown) {
      readError(cause)
    } finally {
      busy.value = false
    }
  }

  async function close(done: () => void = closed): Promise<void> {
    if (locked.value) return
    if (dirty.value) {
      try {
        await ElMessageBox.confirm('关闭后将放弃未保存的修改。', '关闭调价单', { type: 'warning' })
      } catch (cause: unknown) {
        if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
        return
      }
    }
    done()
  }

  watch(
    () => [draft.partnerId, draft.warehouseId],
    () => {
      void loadSources()
    },
  )
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
    previewPosting,
    close,
  }
}
