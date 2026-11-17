import type { AlertAction, AlertLevel, AlertSchedule, AlertState } from '../../api/alerts'

export const alertLevels = { info: '提示', warning: '警告', critical: '严重' } satisfies Record<
  AlertLevel,
  string
>
export const alertStates = {
  open: '未处理',
  acknowledged: '已确认',
  resolved: '已解决',
  ignored: '已忽略',
} satisfies Record<AlertState, string>
export const alertSchedules = {
  realtime: '实时触发',
  hourly: '每小时',
  daily: '每日 08:00',
} satisfies Record<AlertSchedule, string>
export const alertActions = {
  acknowledge: '确认',
  resolve: '标记已解决',
  ignore: '忽略',
} satisfies Record<AlertAction, string>

export const alertKinds: Record<string, string> = {
  credit_exceeded: '信用额度超额',
  receivable_overdue: '应收逾期',
  negative_margin: '销售负毛利',
  stock_below_safety: '低于安全库存',
  stock_slow_moving: '库存呆滞',
  batch_near_expiry: '批次临期',
  batch_expired: '批次过期',
  qc_failed: '来料检验不合格',
  price_deviation: '采购价格偏离',
  frequent_reversal: '频繁冲销',
  duplicate_payment: '疑似重复付款',
  workflow_overdue: '审批超时',
  job_failed: '任务执行失败',
  collection_due: '催收计划到期',
}

export function initialAlertStatus(raw: unknown): AlertState | '' {
  return typeof raw === 'string' && Object.hasOwn(alertStates, raw) ? (raw as AlertState) : ''
}
