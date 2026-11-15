import { expect, it, vi } from 'vitest'
import { confirmCompanyLeave, registerCompanyLeaveGuard } from '../src/utils/unsaved'
it('未保存保护拒绝切换且卸载后移除守卫', () => {
  const guard = vi.fn(() => false),
    remove = registerCompanyLeaveGuard(guard)
  expect(confirmCompanyLeave()).toBe(false)
  expect(guard).toHaveBeenCalledOnce()
  remove()
  expect(confirmCompanyLeave()).toBe(true)
})
it('已保存的编辑允许切换公司', () => {
  const remove = registerCompanyLeaveGuard(() => true)
  expect(confirmCompanyLeave()).toBe(true)
  remove()
})
