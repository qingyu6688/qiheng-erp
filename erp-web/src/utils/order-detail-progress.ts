export type OrderDetailKind = 'sales-orders' | 'purchase-orders'

interface LineProgress {
  baseQty?: string
  closedQty?: string
  shippedQty?: string
  signedQty?: string
  receivedQty?: string
}

export interface OrderProgressInput {
  status: string
  settlementStatus?: string
  lines?: readonly { progress?: LineProgress }[]
}

type Fulfillment = 'shipped' | 'signed' | 'received'
type StepKey = 'submit' | 'approve' | Fulfillment | 'settle'
type StepState = 'done' | 'current' | 'pending' | 'skipped'

interface QuantityProgress {
  actual: string
  required: string
  percent: string | null
  complete: boolean
  skipped: boolean
}

interface OrderStep {
  key: StepKey
  label: string
  state: StepState
}

const SCALE = 1_000_000n
const PERCENT_TENTHS = 1000n
const approvedStatuses = new Set(['approved', 'change_pending', 'closed'])

function scaled(value?: string): bigint | undefined {
  if (typeof value !== 'string') return undefined
  const parts = /^(\d{1,24})(?:\.(\d{1,6}))?$/.exec(value)
  return parts ? BigInt(parts[1]!) * SCALE + BigInt((parts[2] ?? '').padEnd(6, '0')) : undefined
}

function quantityText(value: bigint): string {
  const fraction = String(value % SCALE)
    .padStart(6, '0')
    .replace(/0+$/, '')
  return String(value / SCALE) + (fraction ? '.' + fraction : '')
}

function percentText(actual: bigint, required: bigint): string | null {
  if (required === 0n) return null
  if (actual >= required) return '100%'
  const tenths = (actual * PERCENT_TENTHS) / required
  if (tenths === 0n && actual > 0n) return '<0.1%'
  const remainder = tenths % 10n
  return String(tenths / 10n) + (remainder ? '.' + remainder : '') + '%'
}

/** 与订单履约状态同口径，已关闭数量从应收发基础数量中扣除。 */
function projectQuantity(
  lines: OrderProgressInput['lines'],
  field: keyof LineProgress,
): QuantityProgress | null {
  if (!lines?.length) return null
  let actual = 0n
  let required = 0n
  for (const { progress } of lines) {
    const base = scaled(progress?.baseQty),
      closed = scaled(progress?.closedQty),
      done = scaled(progress?.[field])
    if (base === undefined || closed === undefined || done === undefined) return null
    required += base > closed ? base - closed : 0n
    actual += done
  }
  return {
    actual: quantityText(actual),
    required: quantityText(required),
    percent: percentText(actual, required),
    complete: actual > 0n && actual >= required,
    skipped: required === 0n && actual === 0n,
  }
}

const measurements = {
  'sales-orders': [
    { key: 'shipped', label: '发货', field: 'shippedQty' },
    { key: 'signed', label: '签收', field: 'signedQty' },
  ],
  'purchase-orders': [{ key: 'received', label: '收货', field: 'receivedQty' }],
} as const

export function orderDetailProgress(kind: OrderDetailKind, order: OrderProgressInput) {
  const approved = approvedStatuses.has(order.status)
  const quantities = measurements[kind].map(({ key, label, field }) => ({
    key,
    label,
    progress: projectQuantity(order.lines, field),
  }))
  const fulfilled =
    approved && quantities.every(({ progress }) => progress?.complete || progress?.skipped)
  const allSkipped = quantities.every(({ progress }) => progress?.skipped)
  const milestones: { key: StepKey; label: string; done: boolean; skipped?: boolean }[] = [
    { key: 'submit', label: '提交', done: approved || order.status === 'pending' },
    {
      key: 'approve',
      label: order.status === 'change_pending' ? '审核变更' : '审核',
      done: approved && order.status !== 'change_pending',
    },
    ...quantities.map(({ key, label, progress }) => ({
      key,
      label,
      done: approved && !!progress?.complete,
      skipped: approved && !!progress?.skipped,
    })),
    { key: 'settle', label: '结清', done: fulfilled && order.settlementStatus === 'full' },
  ]
  const next = milestones.find((step) => !step.done && !step.skipped)?.key
  const current =
    order.status === 'cancelled' ||
    (allSkipped && next === 'settle' && order.settlementStatus === 'none')
      ? undefined
      : next
  const steps: OrderStep[] = milestones.map(({ key, label, done, skipped }) => ({
    key,
    label,
    state: done ? 'done' : skipped ? 'skipped' : key === current ? 'current' : 'pending',
  }))
  return { quantities, steps }
}
