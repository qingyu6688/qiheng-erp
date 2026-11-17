import { expect, test, type Locator } from '@playwright/test'

const firstCompany = '101'
const otherCompany = '102'
const clueId = '9007199254740993'
const partnerId = '9007199254740995'

async function openSelect(input: Locator): Promise<void> {
  await input.focus()
  await input.press('Enter')
}

test('线索登记跟进并合并到共享客户，切换公司后旧画像不可见', async ({ page }) => {
  let clueStatus = ''
  let version = 0
  let merged = false
  let note = ''
  const masks = {
    version: '1', unchanged: false,
    items: { crm_follow_method: [{ id: '1', value: 'call', label: '电话', enabled: true, color: 'neutral' }] },
  }
  await page.routeWebSocket('**/ws/**', (socket) => socket.close())
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.slice('/api/v1'.length)
    const ok = (data: unknown): Promise<void> => route.fulfill({ json: { code: 200, message: '操作成功', data } })
    const createdClue = () => ({ id: clueId, name: '客户演练线索', contactName: '联系人', mobile: '****7788',
      status: clueStatus, ownerUserId: '9', version, convertedPartnerId: merged ? partnerId : null })
    if (path === '/auth/me') return ok({ id: '9', username: 'crm', displayName: '员工', mustChangePassword: false,
      permissions: ['crm_clue:view', 'crm_clue:create', 'crm_clue:update', 'crm_clue:convert', 'crm_customer:view'],
      companies: [{ id: firstCompany, code: 'A', name: '甲公司', allWarehouses: true, warehouseIds: [] },
        { id: otherCompany, code: 'B', name: '乙公司', allWarehouses: true, warehouseIds: [] }] })
    if (path === '/workbench/summary') return ok({ blocks: [] })
    if (path === '/notify/unread-count') return ok({ total: 0 })
    if (path === '/notify/important-notices') return ok([])
    if (path === '/dicts') return ok(masks)
    if (path === '/areas/tree') return ok({ items: [], version: '1' })
    if (path === '/crm/employee-options') return ok([{ id: '9', displayName: '员工', departmentId: '11' }])
    if (path === '/crm/clues') {
      if (request.method() === 'POST') {
        expect(request.postDataJSON().name).toBe('客户演练线索')
        clueStatus = 'new'
        return ok(createdClue())
      }
      return ok({ items: clueStatus ? [createdClue()] : [], nextCursor: null })
    }
    if (path === `/crm/clues/${clueId}`) return ok(createdClue())
    if (path === '/crm/follow-ups') {
      if (request.method() === 'POST') {
        expect(request.postDataJSON().bizId).toBe(clueId)
        expect(request.postDataJSON().method).toBe('call')
        note = request.postDataJSON().content
        clueStatus = 'following'
        version++
        return ok({ id: '90' })
      }
      return ok({ items: note ? [{ id: '90', method: 'call', content: note, createdAt: '2026-11-17T00:00:00Z' }] : [], nextCursor: null })
    }
    if (path === `/crm/clues/${clueId}/convert`) {
      const body = request.postDataJSON()
      expect(body.version).toBe(1)
      if (!body.partnerId) return route.fulfill({ status: 409, json: { code: 409, errorCode: 'CRM_DUPLICATE_PARTNER',
        message: '存在同名往来单位', details: { candidates: [{ id: partnerId, code: 'C01', name: '共享往来客户' }] } } })
      expect(body.partnerId).toBe(partnerId)
      merged = true
      clueStatus = 'converted'
      version++
      return ok(createdClue())
    }
    if (path === '/crm/customers') return ok({ items: [], nextCursor: null })
    if (path === `/crm/customers/${partnerId}`) {
      if (request.headers()['x-company-id'] !== firstCompany) {
        return route.fulfill({ status: 404, json: { code: 404, errorCode: 'NOT_FOUND', message: 'CRM 记录不存在或无权查看' } })
      }
      return ok({ id: partnerId, partnerId, partnerCode: 'C01', partnerName: '共享往来客户', ownerUserId: '9',
        status: 'active', dealStatus: 'unsold', locked: false, canWrite: false, version: 0 })
    }
    return route.abort()
  })

  await page.goto('/crm/clues')
  await page.getByRole('button', { name: '新建线索' }).click()
  const editor = page.getByRole('dialog', { name: '新建线索' })
  await editor.getByRole('textbox', { name: '线索名称' }).fill('客户演练线索')
  await editor.getByRole('button', { name: '保存', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '客户演练线索' })
  await drawer.getByRole('tab', { name: '跟进记录' }).click()
  await openSelect(drawer.getByRole('combobox', { name: '跟进方式' }))
  await page.getByRole('option', { name: '电话' }).click()
  await drawer.getByRole('textbox', { name: '跟进内容' }).fill('约定下周回访')
  await drawer.getByRole('button', { name: '登记跟进' }).click()
  await expect(drawer.getByText('约定下周回访')).toBeVisible()
  await drawer.getByRole('button', { name: '转化客户' }).click()
  const conversion = page.getByRole('dialog', { name: '转化客户' })
  await conversion.getByRole('button', { name: '确认', exact: true }).click()
  await expect(conversion.getByText('存在同名往来单位')).toBeVisible()
  await openSelect(conversion.getByRole('combobox', { name: '合并至现有往来单位' }))
  await page.getByRole('option', { name: 'C01 · 共享往来客户' }).click()
  await conversion.getByRole('button', { name: '确认合并' }).click()
  await drawer.getByRole('tab', { name: '基本信息' }).click()
  await drawer.getByRole('link', { name: '查看客户档案' }).click()
  await expect(page.getByRole('dialog', { name: '共享往来客户' })).toBeVisible()
  await page.keyboard.press('Escape')
  await openSelect(page.getByRole('combobox', { name: '当前公司' }))
  await page.getByRole('option', { name: '乙公司' }).click()
  await page.goto(`/crm/customers?partnerId=${partnerId}`)
  await expect(page.getByText('CRM 记录不存在或无权查看')).toBeVisible()
  await expect(page.getByRole('dialog', { name: '共享往来客户' })).toHaveCount(0)
})
