import { computed, nextTick, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { commandSalesQuote, quoteCommandResult, quoteMetadata, quotePreview, quoteUnits, salesQuote, saveSalesQuote } from '../api/sales-quotes'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { QuoteChoice, QuoteMetadata, QuotePricing, QuoteUnit, SalesQuote, SalesQuoteAction, SalesQuoteCommand, SalesQuoteInput } from '../types/sales-quotes'
import { quoteActionLabels } from '../types/sales-quotes'
import type { WorkflowSelections } from '../types/workflow'
import { today } from '../types/sales'
import { registerCompanyLeaveGuard } from '../utils/unsaved'

interface Attempt {
  scope: string; cacheKey: string; key: string; operation: string; id?: string
  action: 'create' | 'update' | SalesQuoteAction
  input: SalesQuoteInput | SalesQuoteCommand
}
const unresolved = new Map<string, Attempt>()
function defaultDeadline(): string { const date = new Date(`${today()}T00:00:00Z`); date.setUTCDate(date.getUTCDate() + 30); return date.toISOString().slice(0, 10) }
function blank(): SalesQuoteInput {
  return { validTo: defaultDeadline(), order: { customerId: '', businessDate: today(), priceTaxInclusive: true, orderDiscountAmount: '0',
    lines: [{ skuId: '', unitId: '', qty: '1' }], fees: [], remark: '' } }
}
function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }

export interface SalesQuotePanel {
  document: Ref<SalesQuote | undefined>; draft: Ref<SalesQuoteInput>; pricing: Ref<QuotePricing | undefined>
  metadata: Ref<QuoteMetadata>; units: Ref<Record<string, QuoteUnit[]>>
  editing: Ref<boolean>; loading: Ref<boolean>; busy: Ref<boolean>; calculating: Ref<boolean>; error: Ref<string>
  pending: Ref<Attempt | undefined>; locked: ComputedRef<boolean>
  submitting: Ref<boolean>; selections: Ref<WorkflowSelections>; workflowReady: Ref<boolean>
  can: (action: string) => boolean; load: () => Promise<void>
  loadMetadata: (search?: string) => Promise<void>; selectSku: (index: number) => Promise<void>
  edit: () => Promise<void>; save: () => Promise<void>; calculate: () => Promise<void>
  retry: () => Promise<void>; verify: () => Promise<void>; act: (action: SalesQuoteAction) => Promise<void>; submit: () => Promise<void>
  cancelEdit: () => void; close: (done?: () => void) => void
}

export function useSalesQuotePanel(id: () => string | undefined, saved: (id: string) => void, closed: () => void, readOnly: () => boolean = () => false): SalesQuotePanel {
  const session = useSessionStore()
  const document = ref<SalesQuote>(), draft = ref<SalesQuoteInput>(blank()), pricing = ref<QuotePricing>()
  const scope = (): string => `${session.user?.id ?? ''}|${session.companyId ?? ''}`
  const initialScope = scope()
  const cacheKey = (): string => `${scope()}|${document.value?.id ?? id() ?? 'new'}`
  const pending = shallowRef(readOnly() ? undefined : unresolved.get(cacheKey()))
  const editing = ref(!id()), loading = ref(false), busy = ref(false), calculating = ref(false), error = ref(''), submitting = ref(false)
  const metadata = ref<QuoteMetadata>({ customers: [], warehouses: [], skus: [], opportunities: [] })
  const units = ref<Record<string, QuoteUnit[]>>({}), selections = ref<WorkflowSelections>({}), workflowReady = ref(false)
  const baseline = ref(JSON.stringify(draft.value))
  const controller = new AbortController()
  let disposed = false, replacingDraft = false, revision = 0, priceRevision = 0, metadataRevision = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const dirty = computed(() => editing.value && JSON.stringify(draft.value) !== baseline.value)
  const locked = computed(() => busy.value || Boolean(pending.value))
  const atRisk = computed(() => locked.value || dirty.value)
  const can = (action: string): boolean => !readOnly() && initialScope === scope() && session.can('sales_quote:view') && session.can(`sales_quote:${action}`) &&
    (action !== 'convert' || session.can('sales_order:create'))
  const visible = (): boolean => !disposed && initialScope === scope() && session.can('sales_quote:view')

  async function loadMetadata(search?: string): Promise<void> {
    const current = ++metadataRevision
    try {
      const result = await quoteMetadata(search, draft.value.order.customerId || undefined, controller.signal)
      if (visible() && current === metadataRevision) {
        const retain = (choices: QuoteChoice[], known: QuoteChoice[], selected: string[]): QuoteChoice[] => [...new Map(
          [...known.filter((item) => selected.includes(item.id)), ...choices].map((item) => [item.id, item]),
        ).values()]
        const knownSkus = [...metadata.value.skus, ...(document.value?.lines.map((line) => ({ id: line.skuId, name: line.skuName })) ?? [])]
        const knownCustomers = [...metadata.value.customers, ...(document.value ? [{ id: document.value.customerId, name: document.value.customerName }] : [])]
        metadata.value = { ...result, skus: retain(result.skus, knownSkus, draft.value.order.lines.map((line) => line.skuId)),
          customers: retain(result.customers, knownCustomers, [draft.value.order.customerId]) }
      }
    } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  }
  async function selectSku(index: number): Promise<void> {
    const line = draft.value.order.lines[index]
    if (!line) return
    const sku = line.skuId
    line.unitId = ''; line.unitPrice = undefined
    if (!sku) return
    try {
      const choices = units.value[sku] ?? await quoteUnits(sku, controller.signal)
      if (!visible() || line.skuId !== sku) return
      units.value[sku] = choices
      line.unitId = choices.find((unit) => Boolean(unit.isBase))?.unitId ?? choices[0]?.unitId ?? ''
      line.warehouseId ??= metadata.value.warehouses[0]?.id
    } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  }
  async function edit(): Promise<void> {
    if (locked.value || !document.value || !can('update')) return
    replacingDraft = true
    draft.value = { order: clone(document.value.input), validTo: document.value.validTo, opportunityId: document.value.opportunityId ?? undefined, version: document.value.version }
    baseline.value = JSON.stringify(draft.value); editing.value = true; pricing.value = document.value.pricing
    await nextTick(); replacingDraft = false
    await loadMetadata()
    try {
      await Promise.all([...new Set(draft.value.order.lines.map((line) => line.skuId))].map(async (sku) => {
        const result = await quoteUnits(sku, controller.signal)
        if (visible()) units.value[sku] = result
      }))
    } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
  }
  async function load(): Promise<void> {
    const current = ++revision
    loading.value = true; error.value = ''
    try {
      const selected = document.value?.id ?? id()
      if (selected) {
        const value = await salesQuote(selected, controller.signal)
        if (visible() && current === revision) { document.value = value; pricing.value = value.pricing; editing.value = false }
      } else await loadMetadata()
    } catch (cause: unknown) {
      if (!isCancelled(cause) && current === revision) error.value = messageOf(cause)
      if (cause instanceof ApiError && (cause.status === 403 || cause.status === 404)) { document.value = undefined; closed() }
    }
    finally { if (current === revision) loading.value = false }
  }
  function payload(): SalesQuoteInput {
    const value = clone(draft.value)
    value.order.deliveryDate ||= undefined
    value.order.lines = value.order.lines.map((line) => ({ ...line, unitPrice: line.unitPrice || undefined,
      warehouseId: line.warehouseId || undefined, taxRate: line.taxRate || undefined, lineDiscountAmount: line.lineDiscountAmount || undefined }))
    value.opportunityId ||= undefined
    return value
  }
  async function calculate(): Promise<void> {
    const current = ++priceRevision, input = payload()
    if (!editing.value || !input.order.customerId || !input.validTo || !input.order.lines.length || input.order.lines.some((line) => !line.skuId || !line.unitId || !line.qty)) {
      pricing.value = undefined; calculating.value = false; return
    }
    calculating.value = true
    try {
      const result = await quotePreview(input, controller.signal)
      if (visible() && current === priceRevision && editing.value) { pricing.value = result.pricing; error.value = '' }
    } catch (cause: unknown) { if (!isCancelled(cause) && current === priceRevision) { error.value = messageOf(cause); pricing.value = undefined } }
    finally { if (current === priceRevision) calculating.value = false }
  }
  function remember(value: Attempt | undefined): void {
    if (pending.value) unresolved.delete(pending.value.cacheKey)
    pending.value = value
    if (value) unresolved.set(value.cacheKey, value)
  }
  function accept(value: SalesQuote): void {
    ++priceRevision; clearTimeout(timer); calculating.value = false
    remember(undefined); document.value = value; pricing.value = value.pricing; editing.value = false; submitting.value = false; selections.value = {}; saved(value.id)
  }
  async function retry(): Promise<void> {
    const attempt = pending.value
    if (!attempt || busy.value || attempt.scope !== scope() || !can(attempt.action)) return
    busy.value = true; error.value = ''
    try {
      const result = attempt.action === 'create' || attempt.action === 'update'
        ? await saveSalesQuote(attempt.input as SalesQuoteInput, attempt.key, attempt.id, controller.signal)
        : await commandSalesQuote(attempt.id!, attempt.action, attempt.input as SalesQuoteCommand, attempt.key, controller.signal)
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
      const result = await quoteCommandResult(attempt.operation, attempt.key)
      if (visible() && attempt.scope === scope() && can(attempt.action) && result.response) accept(result.response)
    } catch (cause: unknown) { if (!isCancelled(cause)) error.value = messageOf(cause) }
    finally { busy.value = false }
  }
  function stage(action: Attempt['action'], input: Attempt['input']): void {
    ++priceRevision; clearTimeout(timer); calculating.value = false
    remember({ scope: scope(), cacheKey: cacheKey(), operation: `sales_quote.${action}`, action, input: clone(input), id: document.value?.id ?? id(), key: newIdempotencyKey() })
  }
  async function save(): Promise<void> {
    const action = document.value || id() ? 'update' : 'create'
    if (locked.value || !can(action)) return
    const input = payload()
    if (!input.order.customerId || !input.validTo || !input.order.lines.length || input.order.lines.some((line) => !line.skuId || !line.unitId || !line.qty)) { error.value = '请填写客户、有效期及完整商品明细。'; return }
    stage(action, input); await retry()
  }
  async function act(action: SalesQuoteAction): Promise<void> {
    if (!document.value || locked.value || !can(action) || !document.value.allowedActions.includes(action)) return
    if (action === 'submit') { submitting.value = true; return }
    try { await ElMessageBox.confirm(action === 'convert' ? '将按报价价格生成销售订单草稿，并关闭本报价。' : '确认作废本报价？', quoteActionLabels[action], { type: 'warning' }) }
    catch (cause: unknown) { if (cause !== 'cancel' && cause !== 'close') error.value = messageOf(cause); return }
    stage(action, { version: document.value.version, businessDate: action === 'convert' ? today() : undefined }); await retry()
  }
  async function submit(): Promise<void> {
    if (!document.value || locked.value || !can('submit') || document.value.belowMinPrice &&
      (document.value.belowMinPricePolicy === 'block' || document.value.belowMinPricePolicy === 'approval' && !workflowReady.value)) return
    stage('submit', { version: document.value.version, workflowAssignees: selections.value }); await retry()
  }
  function cancelEdit(): void {
    if (!locked.value && document.value && (!dirty.value || window.confirm('放弃尚未保存的报价修改？'))) {
      ++priceRevision; clearTimeout(timer); calculating.value = false; pricing.value = document.value.pricing; editing.value = false
    }
  }
  function mayLeave(): boolean { return !locked.value && (!dirty.value || window.confirm('离开将放弃尚未保存的报价，确认离开？')) }
  function close(done: () => void = closed): void { if (mayLeave()) done() }
  const unregister = registerCompanyLeaveGuard(mayLeave, atRisk)
  onBeforeRouteLeave(() => mayLeave())
  watch(() => JSON.stringify(draft.value), () => { clearTimeout(timer); ++priceRevision; if (editing.value && !locked.value) timer = setTimeout(() => { void calculate() }, 350) })
  watch(() => draft.value.order.customerId, (value, before) => { if (value !== before && editing.value && !replacingDraft) { draft.value.opportunityId = undefined; void loadMetadata() } })
  watch(() => [session.companyId, session.user?.id, session.can('sales_quote:view')], () => {
    if (scope() !== initialScope || !session.can('sales_quote:view')) {
      ++revision; ++metadataRevision; ++priceRevision; clearTimeout(timer); controller.abort()
      document.value = undefined; pricing.value = undefined; metadata.value = { customers: [], warehouses: [], skus: [], opportunities: [] }; units.value = {}
      editing.value = false; calculating.value = false; closed()
    }
  })
  onScopeDispose(() => { disposed = true; ++revision; ++priceRevision; clearTimeout(timer); controller.abort(); unregister() })
  void load()
  return { document, draft, pricing, metadata, units, editing, loading, busy, calculating, error, pending, locked, submitting, selections, workflowReady,
    can, load, loadMetadata, selectSku, edit, save, calculate, retry, verify, act, submit, cancelEdit, close }
}
