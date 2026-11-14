import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import * as auth from '../src/api/auth'
import { ApiError } from '../src/api/http'
import { useSessionStore } from '../src/stores/session'
import type { UserSession } from '../src/types/auth'

vi.mock('../src/api/auth')
const profile: UserSession = {
  id: '1',
  username: 'tester',
  displayName: '测试员',
  mustChangePassword: false,
  permissions: ['warehouse:view'],
  companies: ['8', '9'].map((id) => ({
    id,
    code: id,
    name: '测试公司' + id,
    allWarehouses: true,
    warehouseIds: [],
  })),
}
beforeEach(() => {
  setActivePinia(createPinia())
  sessionStorage.clear()
})
describe('登录与公司范围', () => {
  it('恢复当前会话时丢弃已撤销的公司选择', async () => {
    sessionStorage.setItem('erp.company', '42')
    vi.mocked(auth.currentUser).mockResolvedValue(profile)
    const session = useSessionStore()
    await Promise.all([session.restore(), session.restore()])
    expect(auth.currentUser).toHaveBeenCalledTimes(1)
    expect(session.companyId).toBe('8')
    session.selectCompany('9')
    expect(sessionStorage.getItem('erp.company')).toBe('9')
    expect(session.can('warehouse:update')).toBe(false)
    session.selectCompany('42')
    expect(session.company).toBeUndefined()
  })
  it('登录失效转入匿名态；后端不可用时保留重试机会', async () => {
    const session = useSessionStore()
    vi.mocked(auth.currentUser).mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '连接失败'))
    await expect(session.restore()).rejects.toThrow('连接失败')
    expect(session.ready).toBe(false)
    vi.mocked(auth.currentUser).mockRejectedValueOnce(
      new ApiError(401, 'UNAUTHENTICATED', '请登录'),
    )
    await session.restore()
    expect(session.ready).toBe(true)
    expect(session.user).toBeNull()
  })
  it('登录成功后载入公司，退出清除本地状态', async () => {
    const session = useSessionStore()
    vi.mocked(auth.login).mockResolvedValue(profile)
    await session.signIn({ tenantCode: 'T', username: 'tester', password: '测试输入' })
    expect(session.can('warehouse:view')).toBe(true)
    await session.signOut()
    expect(session.user).toBeNull()
    expect(sessionStorage.getItem('erp.company')).toBeNull()
  })
})
