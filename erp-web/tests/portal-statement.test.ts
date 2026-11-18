import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { reactive } from 'vue'
import { ApiError } from '../src/api/http'
import PortalWorkspace from '../src/pages/portal/PortalWorkspace.vue'
import type { PortalStatement, PortalView } from '../src/types/portal'

const read = vi.hoisted(() => vi.fn())
const route = reactive<{ name: PortalView }>({ name: 'statements' })

vi.mock('vue-router', () => ({ useRoute: () => route }))
vi.mock('../src/api/portal', () => ({ portalRead: read, portalFile: vi.fn(), portalWrite: vi.fn() }))

function statement(sourceNo: string, closingBalance: string): PortalStatement {
  return { digest: sourceNo, items: [{ sourceNo }], summary: { closingBalance } }
}

let page: ReturnType<typeof mount<typeof PortalWorkspace>>

beforeEach(() => {
  route.name = 'statements'
  read.mockReset()
  read.mockResolvedValueOnce(statement('原区间单据', '123.45'))
  page = mount(PortalWorkspace, { global: { stubs: { RouterLink: true } } })
})

afterEach(() => page.unmount())

describe('客户门户对账区间', () => {
  test.each([0, 1])('修改第 %i 个日期字段即清除旧结果，查询失败时仍隐藏', async (field) => {
    await flushPromises()
    expect(page.text()).toContain('原区间单据')
    expect(page.text()).toContain('123.45')

    await page.findAll('input[type="date"]')[field]!.setValue('2025-01-01')
    expect(page.text()).toContain('请选择日期并查询对账单。')
    expect(page.text()).not.toContain('原区间单据')
    expect(page.text()).not.toContain('123.45')
    expect(page.findAll('form button[type="button"][disabled]')).toHaveLength(2)

    read.mockRejectedValueOnce(new ApiError(503, 'UNAVAILABLE', '对账服务暂不可用'))
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(page.get('[role="alert"]').text()).toBe('对账服务暂不可用')
    expect(page.text()).not.toContain('原区间单据')
    expect(page.text()).not.toContain('123.45')

    read.mockResolvedValueOnce(statement('新查询单据', '678.90'))
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(page.text()).toContain('新查询单据')
    expect(page.text()).toContain('678.90')
    expect(page.text()).not.toContain('123.45')
    expect(page.findAll('form button[type="button"][disabled]')).toHaveLength(0)
  })

  test('切换到订单后，在途对账查询结果不会覆盖订单列表', async () => {
    await flushPromises()
    let resolveStatement: (result: PortalStatement) => void = () => {}
    read.mockReturnValueOnce(new Promise<PortalStatement>((resolve) => {
      resolveStatement = resolve
    }))
    await page.get('form').trigger('submit')
    expect(page.text()).toContain('正在加载…')
    expect(page.text()).not.toContain('原区间单据')
    expect(page.text()).not.toContain('123.45')

    read.mockResolvedValueOnce({ items: [{ documentNo: '查询订单' }], nextCursor: null })
    route.name = 'orders'
    await flushPromises()
    resolveStatement(statement('已离开页面的对账单据', '678.90'))
    await flushPromises()
    expect(page.text()).toContain('查询订单')
    expect(page.text()).not.toContain('已离开页面的对账单据')
  })
})
