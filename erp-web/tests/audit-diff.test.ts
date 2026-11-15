import { expect, it } from 'vitest'
import { auditChanges } from '../src/utils/audit'
it('审计差异保留精确金额，区分新增删除与未变字段', () => {
  const rows = auditChanges(
    { amount: '9007199254740993.12', remark: '旧备注', version: 1 },
    { amount: '9007199254740993.12', status: 'approved', version: 2 },
  )
  expect(rows.find((row) => row.field === 'amount')).toMatchObject({
    before: '9007199254740993.12',
    changed: false,
  })
  expect(rows.find((row) => row.field === 'remark')).toMatchObject({
    before: '旧备注',
    after: '—',
    changed: true,
  })
  expect(rows.find((row) => row.field === 'status')).toMatchObject({
    label: '状态',
    before: '—',
    after: '已审核',
  })
})
it('审计嵌套值使用字段文案，时间按北京时间显示且不生成HTML', () => {
  const rows = auditChanges(null, {
    lines: [{ qty: '2', remark: '<script>' }],
    signedAt: '2026-11-15T00:01:02Z',
  })
  expect(rows[0]?.after).toBe('数量：2，备注：<script>')
  expect(rows[1]?.after).toContain('11-15 08:01:02')
})

it('审计对齐数据库字段名并按对象解释状态', () => {
  const rows = auditChanges(
    { cost_amount: '1.00', status: 'dead' },
    { costAmount: '2.00', status: 'pending' },
    'outbox_event',
  )
  expect(rows).toHaveLength(2)
  expect(rows[0]).toMatchObject({ label: '成本金额', before: '1.00', after: '2.00' })
  expect(rows[1]).toMatchObject({ before: '失败待处理', after: '待执行' })
  expect(auditChanges(null, { status: 'pending' }, 'import_job')[0]?.after).toBe('待处理')
})
