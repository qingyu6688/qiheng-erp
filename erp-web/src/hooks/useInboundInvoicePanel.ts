import { computed, onMounted, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import {
  allocateInboundInvoice,
  inboundHistory,
  inboundInvoice,
  inboundSources,
  inboundSuppliers,
  previewInboundInvoice,
  saveInboundInvoice,
  voidInboundInvoice,
} from '../api/e-invoice'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import type {
  InboundAllocation,
  InboundAllocationInput,
  InboundInvoiceDetail,
  InboundManualInput,
  InboundMatch,
  InboundPayableSource,
  InboundPreview,
  InboundSupplier,
  InboundVoidInput,
} from '../types/inbound-invoices'
import { today } from '../types/sales'
import {
  allocationInput,
  emptyManualInvoice,
  initialInvoiceAllocation,
  manualInvoiceDraft,
  validateManualInvoice,
} from '../utils/inbound-invoices'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

type Mode = 'view' | 'manual' | 'match'
type Pending =
  | { kind: 'manual'; input: InboundManualInput; id?: string; key: string }
  | { kind: 'allocation'; input: InboundAllocationInput; id: string; key: string }
  | { kind: 'void'; input: InboundVoidInput; id: string; key: string }
interface Panel {
  document: Ref<InboundInvoiceDetail | undefined>
  mode: Ref<Mode>
  draft: InboundManualInput
  lines: Ref<InboundAllocation[]>
  suppliers: Ref<InboundSupplier[]>
  supplierCursor: Ref<string | null>
  supplierLoading: Ref<boolean>
  sources: Ref<InboundPayableSource[]>
  sourceCursor: Ref<string | null>
  sourceLoading: Ref<boolean>
  selectionSupplier: Ref<string | null>
  history: Ref<InboundMatch[]>
  historyCursor: Ref<string | null>
  historyLoading: Ref<boolean>
  preview: Ref<InboundPreview | undefined>
  previewCurrent: ComputedRef<boolean>
  error: Ref<string>
  notice: Ref<string>
  loading: Ref<boolean>
  busy: Ref<boolean>
  pending: ComputedRef<boolean>
  locked: ComputedRef<boolean>
  refreshNeeded: Ref<boolean>
  canEdit: ComputedRef<boolean>
  canMatch: ComputedRef<boolean>
  canVoid: ComputedRef<boolean>
  load(): Promise<void>
  editManual(): Promise<void>
  editMatching(): Promise<void>
  cancelEdit(): Promise<void>
  loadSuppliers(keyword?: string, more?: boolean): Promise<void>
  chooseSupplier(id: string): void
  loadSources(more?: boolean): Promise<void>
  addSource(source: InboundPayableSource): void
  removeSource(id: string): void
  coverSource(id: string): void
  sourceFor(id: string): InboundPayableSource | undefined
  loadHistory(): Promise<void>
  previewMatching(): Promise<void>
  save(): Promise<void>
  clear(): Promise<void>
  voidInvoice(reason: string): Promise<void>
  retryWrite(): Promise<void>
  close(done?: () => void): Promise<void>
  mayLeave(): boolean
}

export function useInboundInvoicePanel(
  props: { id?: string },
  saved: (id: string) => void,
  closed: () => void,
): Panel {
  const session = useSessionStore(),
    owner = session.companyId,
    controller = new AbortController()
  const document = ref<InboundInvoiceDetail>(),
    mode = ref<Mode>(props.id ? 'view' : 'manual')
  const draft = reactive(emptyManualInvoice(today())),
    lines = ref<InboundAllocation[]>([])
  const suppliers = ref<InboundSupplier[]>([]),
    supplierCursor = ref<string | null>(null),
    supplierLoading = ref(false)
  const sources = ref<InboundPayableSource[]>([]),
    sourceCursor = ref<string | null>(null),
    sourceLoading = ref(false)
  const selectionSupplier = ref<string | null>(null)
  const history = ref<InboundMatch[]>([]),
    historyCursor = ref<string | null>(null),
    historyLoading = ref(false)
  const preview = ref<InboundPreview>(),
    previewSignature = ref(''),
    baseline = ref(JSON.stringify(draft))
  const error = ref(''),
    notice = ref(''),
    loading = ref(false),
    busy = ref(false),
    refreshNeeded = ref(false)
  const command = shallowRef<Pending>()
  let readRevision = 0,
    sourceRevision = 0,
    supplierRevision = 0,
    historyRevision = 0,
    supplierKeyword = ''
  const active = (): boolean => !controller.signal.aborted && owner === session.companyId
  const pending = computed(() => Boolean(command.value)),
    locked = computed(() => busy.value || pending.value)
  const signature = computed(() => JSON.stringify(mode.value === 'manual' ? draft : lines.value))
  const dirty = computed(
    () => mode.value !== 'view' && !loading.value && signature.value !== baseline.value,
  )
  const risk = computed(() => dirty.value || locked.value)
  const canMatch = computed(() =>
    Boolean(
      document.value?.status === 'normal' && session.can('einv:inbound') && !refreshNeeded.value,
    ),
  )
  const canEdit = computed(() =>
    Boolean(
      canMatch.value &&
      document.value?.source === 'manual' &&
      !document.value.allocations.length &&
      session.can('einv:inbound_register'),
    ),
  )
  const canVoid = computed(() =>
    Boolean(
      canMatch.value && document.value?.source === 'manual' && session.can('einv:inbound_void'),
    ),
  )
  const previewCurrent = computed(() =>
    Boolean(preview.value && previewSignature.value === JSON.stringify(lines.value)),
  )
  const bySource = computed(() => new Map(sources.value.map((source) => [source.id, source])))

  function mayLeave(): boolean {
    if (!active()) return true
    if (locked.value) {
      error.value = '本次提交尚未完成或结果未确认，请先确认结果。'
      return false
    }
    return !dirty.value || window.confirm('发票修改尚未保存，确定离开？')
  }
  const unregister = registerCompanyLeaveGuard(mayLeave, risk)
  onBeforeRouteLeave(mayLeave)
  function beforeUnload(event: BeforeUnloadEvent): void {
    if (risk.value) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  function resetReading(value: InboundInvoiceDetail): void {
    selectionSupplier.value = value.supplierId
    document.value = value
    history.value = [...value.history.items]
    historyCursor.value = value.history.nextCursor
    historyRevision++
    sourceRevision++
    supplierRevision++
    historyLoading.value = false
    sourceLoading.value = false
    supplierLoading.value = false
    mode.value = 'view'
    preview.value = undefined
    sources.value = []
    sourceCursor.value = null
    refreshNeeded.value = false
  }
  async function readCurrent(id: string): Promise<void> {
    const current = ++readRevision
    const value = await inboundInvoice(id, controller.signal)
    if (active() && current === readRevision) resetReading(value)
  }
  async function discard(): Promise<boolean> {
    if (locked.value) return false
    if (!dirty.value) return true
    try {
      await ElMessageBox.confirm('关闭后将放弃未保存的发票修改。', '放弃修改', { type: 'warning' })
      return true
    } catch (cause: unknown) {
      if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
      return false
    }
  }
  async function load(): Promise<void> {
    if (!active() || locked.value || !(await discard())) return
    if (!active()) return
    const id = document.value?.id ?? props.id
    if (!id) {
      await loadSuppliers()
      return
    }
    loading.value = true
    error.value = ''
    notice.value = ''
    try {
      await readCurrent(id)
    } catch (cause: unknown) {
      if (active() && !isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      if (active()) loading.value = false
    }
  }
  async function loadSuppliers(keyword = supplierKeyword, more = false): Promise<void> {
    if (
      !active() ||
      locked.value ||
      !session.can('einv:inbound_register') ||
      (more && !supplierCursor.value)
    )
      return
    supplierKeyword = keyword
    const current = ++supplierRevision
    supplierLoading.value = true
    if (!more) {
      suppliers.value = []
      supplierCursor.value = null
    }
    try {
      const page = await inboundSuppliers(
        keyword || undefined,
        more ? (supplierCursor.value ?? undefined) : undefined,
        controller.signal,
      )
      if (active() && current === supplierRevision) {
        suppliers.value = more ? [...suppliers.value, ...page.items] : page.items
        supplierCursor.value = page.nextCursor
      }
    } catch (cause: unknown) {
      if (active() && current === supplierRevision && !isCancelled(cause))
        error.value = messageOf(cause)
    } finally {
      if (active() && current === supplierRevision) supplierLoading.value = false
    }
  }
  function chooseSupplier(id: string): void {
    if (locked.value) return
    const supplier = suppliers.value.find((row) => row.id === id)
    if (supplier) {
      draft.supplierId = id
      draft.sellerName = supplier.name
      draft.sellerTaxNo = supplier.taxNo ?? ''
    }
  }
  async function editManual(): Promise<void> {
    if (!canEdit.value || locked.value || !document.value) return
    Object.assign(draft, manualInvoiceDraft(document.value))
    mode.value = 'manual'
    baseline.value = JSON.stringify(draft)
    error.value = ''
    notice.value = ''
    await loadSuppliers('')
  }
  async function editMatching(): Promise<void> {
    if (!canMatch.value || locked.value || !document.value) return
    selectionSupplier.value = document.value.supplierId
    lines.value = document.value.allocations.map((row) => ({
      payableId: row.payableId,
      invoiceUntaxedAmount: row.invoiceUntaxedAmount,
      invoiceTaxAmount: row.invoiceTaxAmount,
      payableUntaxedAmount: row.payableUntaxedAmount,
      payableTaxAmount: row.payableTaxAmount,
    }))
    mode.value = 'match'
    baseline.value = JSON.stringify(lines.value)
    preview.value = undefined
    error.value = ''
    notice.value = ''
    await loadSources()
  }
  async function cancelEdit(): Promise<void> {
    if (!(await discard())) return
    if (!active()) return
    if (!document.value) {
      closed()
      return
    }
    sourceRevision++
    supplierRevision++
    mode.value = 'view'
    preview.value = undefined
  }
  async function loadSources(more = false): Promise<void> {
    if (!document.value || mode.value !== 'match' || locked.value || (more && !sourceCursor.value))
      return
    const current = ++sourceRevision
    sourceLoading.value = true
    if (!more) {
      sources.value = []
      sourceCursor.value = null
      preview.value = undefined
    }
    try {
      const page = await inboundSources(
        document.value.id,
        more ? (sourceCursor.value ?? undefined) : undefined,
        controller.signal,
      )
      if (active() && current === sourceRevision && mode.value === 'match') {
        const combined = more ? [...sources.value, ...page.items] : page.items
        sources.value = [...new Map(combined.map((row) => [row.id, row])).values()]
        sourceCursor.value = page.nextCursor
      }
    } catch (cause: unknown) {
      if (active() && current === sourceRevision && !isCancelled(cause))
        error.value = messageOf(cause)
    } finally {
      if (active() && current === sourceRevision) sourceLoading.value = false
    }
  }
  function sourceFor(id: string): InboundPayableSource | undefined {
    return bySource.value.get(id)
  }
  function addSource(source: InboundPayableSource): void {
    if (!document.value || locked.value || lines.value.some((line) => line.payableId === source.id))
      return
    try {
      if (selectionSupplier.value && selectionSupplier.value !== source.supplierId)
        throw new Error('一张发票只能核对同一供应商的来源')
      if (lines.value.length >= 200) throw new Error('一张票最多核对200笔应付')
      lines.value.push(initialInvoiceAllocation(document.value, lines.value, source))
      selectionSupplier.value = source.supplierId
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '请先检查已录入金额'
    }
  }
  function removeSource(id: string): void {
    if (!locked.value) {
      lines.value = lines.value.filter((line) => line.payableId !== id)
      if (!lines.value.length) selectionSupplier.value = document.value?.supplierId ?? null
    }
  }
  function coverSource(id: string): void {
    if (locked.value) return
    const source = sourceFor(id),
      line = lines.value.find((value) => value.payableId === id)
    if (source && line) {
      line.payableUntaxedAmount = source.availableUntaxedAmount
      line.payableTaxAmount = source.availableTaxAmount
    }
  }
  async function loadHistory(): Promise<void> {
    if (!document.value || !historyCursor.value || historyLoading.value) return
    const current = ++historyRevision
    historyLoading.value = true
    try {
      const page = await inboundHistory(document.value.id, historyCursor.value, controller.signal)
      if (active() && current === historyRevision) {
        history.value.push(...page.items)
        historyCursor.value = page.nextCursor
      }
    } catch (cause: unknown) {
      if (active() && current === historyRevision && !isCancelled(cause))
        error.value = messageOf(cause)
    } finally {
      if (active() && current === historyRevision) historyLoading.value = false
    }
  }
  function validatedLines(): InboundAllocation[] | undefined {
    try {
      return allocationInput(lines.value)
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '请检查分配金额'
      return undefined
    }
  }
  async function previewMatching(): Promise<void> {
    if (!document.value || locked.value || refreshNeeded.value) return
    const input = validatedLines()
    if (!input) return
    const snapshot = JSON.stringify(lines.value)
    busy.value = true
    error.value = ''
    preview.value = undefined
    try {
      const value = await previewInboundInvoice(
        document.value.id,
        { version: document.value.version, lines: input },
        controller.signal,
      )
      if (active() && snapshot === JSON.stringify(lines.value)) {
        preview.value = value
        previewSignature.value = snapshot
      }
    } catch (cause: unknown) {
      if (active() && !isCancelled(cause)) {
        error.value = messageOf(cause)
        if (cause instanceof ApiError && cause.status === 409) refreshNeeded.value = true
      }
    } finally {
      if (active()) busy.value = false
    }
  }
  async function save(): Promise<void> {
    if (!active() || locked.value || refreshNeeded.value) return
    error.value = ''
    if (mode.value === 'manual') {
      if (!session.can('einv:inbound_register')) return
      let input: InboundManualInput
      try {
        input = validateManualInvoice(draft, today())
      } catch (cause: unknown) {
        error.value = cause instanceof Error ? cause.message : '请检查发票信息'
        return
      }
      command.value = { kind: 'manual', id: document.value?.id, input, key: newIdempotencyKey() }
    } else if (mode.value === 'match' && document.value) {
      if (!previewCurrent.value) {
        error.value = '分配有修改，请先预览核对结果'
        return
      }
      const input = validatedLines()
      if (!input || (!input.length && !document.value.allocations.length)) return
      if (!input.length && !(await confirmRelease())) return
      if (!active() || locked.value) return
      command.value = {
        kind: 'allocation',
        id: document.value.id,
        input: { version: document.value.version, lines: input },
        key: newIdempotencyKey(),
      }
    } else return
    await retryWrite()
  }
  async function confirmRelease(): Promise<boolean> {
    try {
      await ElMessageBox.confirm('解除后保留原分配历史，发票回到待核对。', '解除当前核对', {
        type: 'warning',
      })
      return true
    } catch (cause: unknown) {
      if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause)
      return false
    }
  }
  async function clear(): Promise<void> {
    if (
      !document.value ||
      !canMatch.value ||
      locked.value ||
      !document.value.allocations.length ||
      !(await confirmRelease())
    )
      return
    if (!active() || locked.value) return
    command.value = {
      kind: 'allocation',
      id: document.value.id,
      input: { version: document.value.version, lines: [] },
      key: newIdempotencyKey(),
    }
    await retryWrite()
  }
  async function voidInvoice(reason: string): Promise<void> {
    if (!document.value || !canVoid.value || locked.value) return
    if (!reason.trim() || reason.trim().length > 200) {
      error.value = '请填写作废原因，最多200字'
      return
    }
    command.value = {
      kind: 'void',
      id: document.value.id,
      input: { version: document.value.version, reason: reason.trim() },
      key: newIdempotencyKey(),
    }
    await retryWrite()
  }
  async function retryWrite(): Promise<void> {
    const request = command.value
    if (!request || busy.value || !active()) return
    busy.value = true
    error.value = ''
    notice.value = ''
    try {
      const result =
        request.kind === 'manual'
          ? await saveInboundInvoice(request.input, request.key, request.id, controller.signal)
          : request.kind === 'allocation'
            ? await allocateInboundInvoice(
                request.id,
                request.input,
                request.key,
                controller.signal,
              )
            : await voidInboundInvoice(request.id, request.input, request.key, controller.signal)
      if (!active()) return
      document.value = result
      command.value = undefined
      mode.value = 'view'
      preview.value = undefined
      refreshNeeded.value = true
      notice.value = '提交已确认，正在读取当前详情。'
      try {
        await readCurrent(result.id)
        if (active()) notice.value = '已保存，并更新为当前详情。'
      } catch (cause: unknown) {
        if (active()) {
          notice.value = '提交已确认，当前详情未能刷新，请刷新后继续。'
          if (!isCancelled(cause)) error.value = messageOf(cause)
        }
      }
      if (active()) saved(result.id)
    } catch (cause: unknown) {
      if (!active()) return
      if (!isCancelled(cause)) error.value = messageOf(cause)
      if (
        cause instanceof ApiError &&
        cause.status >= 400 &&
        cause.status < 500 &&
        cause.code !== 'REQUEST_IN_PROGRESS'
      ) {
        command.value = undefined
        preview.value = undefined
        if (cause.status === 409) refreshNeeded.value = true
      }
    } finally {
      if (active()) busy.value = false
    }
  }
  async function close(done: () => void = closed): Promise<void> {
    if ((await discard()) && active()) done()
  }
  watch(
    () => session.companyId,
    () => {
      if (!active()) {
        controller.abort()
        closed()
      }
    },
  )
  onMounted(() => {
    window.addEventListener('beforeunload', beforeUnload)
    void load()
  })
  onScopeDispose(() => {
    readRevision++
    sourceRevision++
    supplierRevision++
    historyRevision++
    controller.abort()
    unregister()
    window.removeEventListener('beforeunload', beforeUnload)
  })
  return {
    document,
    mode,
    draft,
    lines,
    suppliers,
    supplierCursor,
    supplierLoading,
    sources,
    sourceCursor,
    sourceLoading,
    selectionSupplier,
    history,
    historyCursor,
    historyLoading,
    preview,
    previewCurrent,
    error,
    notice,
    loading,
    busy,
    pending,
    locked,
    refreshNeeded,
    canEdit,
    canMatch,
    canVoid,
    load,
    editManual,
    editMatching,
    cancelEdit,
    loadSuppliers,
    chooseSupplier,
    loadSources,
    addSource,
    removeSource,
    coverSource,
    sourceFor,
    loadHistory,
    previewMatching,
    save,
    clear,
    voidInvoice,
    retryWrite,
    close,
    mayLeave,
  }
}
