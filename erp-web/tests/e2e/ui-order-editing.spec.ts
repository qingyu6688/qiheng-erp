import { expect, test } from '@playwright/test'

for (const kind of ['sales-orders', 'purchase-orders'] as const) {
  test(kind + ' 明细键盘、Excel粘贴和50行常驻操作栏', async ({ page }, testInfo) => {
    test.setTimeout(60_000)
    const purchase = kind === 'purchase-orders'
    const line = {
      id: '80',
      skuId: '41',
      unitId: '51',
      warehouseId: '61',
      qty: '1.000001',
      unitPrice: '12.00000000',
      taxRate: '0.13',
      lineDiscountAmount: '0',
    }
    const original = {
      id: '70',
      documentNo: purchase ? 'PO-70' : 'SO-70',
      status: 'draft',
      version: 3,
      customerId: '31',
      supplierId: '31',
      businessDate: '2026-11-18',
      priceTaxInclusive: true,
      orderDiscountAmount: '0',
      fees: [],
      lines: Array.from({ length: 50 }, (_, index) => ({ ...line, id: String(80 + index) })),
      allowedActions: ['update'],
    }
    let saved = false
    let submitted = false
    await page.routeWebSocket('**/ws/**', (socket) => socket.close())
    await page.route('**/api/v1/**', (route) => {
      const path = new URL(route.request().url()).pathname.slice('/api/v1'.length)
      const ok = (data: unknown): Promise<void> => route.fulfill({ json: { data } })
      if (path === '/system/version') return ok({ apiMajor: 2 })
      if (path === '/auth/me')
        return ok({
          id: '560',
          username: 'verify',
          displayName: '订单验收员',
          mustChangePassword: false,
          permissions: [
            purchase ? 'purchase_order:view' : 'sales_order:view',
            purchase ? 'purchase_order:update' : 'sales_order:update',
            purchase ? 'purchase_order:submit' : 'sales_order:submit',
            'sku:view',
            'partner:view',
          ],
          companies: [
            { id: '1', code: 'A', name: '验收公司', allWarehouses: true, warehouseIds: [] },
          ],
        })
      if (path === '/' + kind + '/70') {
        if (route.request().method() === 'PUT') saved = true
        return ok({
          ...original,
          status: submitted ? 'pending' : 'draft',
          version: submitted ? 5 : saved ? 4 : 3,
          allowedActions: submitted ? [] : saved ? ['update', 'submit'] : ['update'],
        })
      }
      if (path === '/workflow/start-options') return ok({ nodes: [] })
      if (path === '/' + kind + '/70/submit') {
        expect(saved).toBe(true)
        expect(route.request().postDataJSON()).toMatchObject({ version: 4 })
        submitted = true
        return ok({ ...original, status: 'pending', allowedActions: [], version: 5 })
      }
      if (path === '/notify/unread-count') return ok({ total: 0 })
      if (path === '/workbench/summary') return ok({ blocks: [] })
      if (path === '/partners')
        return ok({
          items: [
            {
              id: '31',
              code: 'C01',
              name: '验收伙伴',
              roles: ['customer', 'supplier'],
              status: 'active',
            },
          ],
          nextCursor: null,
        })
      if (path === '/skus')
        return ok({
          items: [{ id: '41', code: 'SKU-01', name: '验收商品', status: 'active' }],
          nextCursor: null,
        })
      if (path === '/warehouses')
        return ok({
          items: [{ id: '61', code: 'W01', name: '授权仓', status: 'active' }],
          nextCursor: null,
        })
      if (path === '/skus/41')
        return ok({
          id: '41',
          code: 'SKU-01',
          status: 'active',
          defaultTaxRate: '0.13',
          units: [{ unitId: '51', unitName: '件', rateToBase: '1', isBase: true }],
        })
      if (path === '/pricing/preview') {
        const data = route.request().postDataJSON() as { lines: Record<string, unknown>[] }
        return ok({
          ...original,
          lines: data.lines.map((item) => ({ ...item, receivableAmount: '12.00' })),
          goodsAmount: '600.00',
          taxAmount: '69.03',
          totalAmount: '600.00',
        })
      }
      return ok([])
    })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/' + kind + '/70/edit')
    await expect(page.locator('.editable-table tbody > tr')).toHaveCount(50)
    const footer = page.locator('.order-editor-footer')
    const viewport = page.viewportSize()!
    const bounds = await footer.boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(viewport.height)
    expect(bounds!.y).toBeGreaterThan(0)
    const prefix = purchase ? '采购' : ''
    const qty = page.getByRole('textbox', { name: prefix + '数量1', exact: true })
    await qty.fill('2.000001')
    await qty.press('Enter')
    await expect(page.getByRole('textbox', { name: prefix + '单价1', exact: true })).toBeFocused()
    await page.keyboard.press('ArrowDown')
    await expect(page.getByRole('textbox', { name: prefix + '单价2', exact: true })).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    await expect(page.getByRole('textbox', { name: prefix + '数量2', exact: true })).toBeFocused()
    await page.getByRole('textbox', { name: prefix + '行优惠50', exact: true }).press('Enter')
    await expect(page.locator('.editable-table tbody > tr')).toHaveCount(51)
    const lastSku = page.getByRole('combobox', { name: prefix + '商品51', exact: true })
    await expect(lastSku).toBeFocused()
    await lastSku.press('Control+Delete')
    await expect(page.locator('.editable-table tbody > tr')).toHaveCount(50)
    await page.getByRole('button', { name: '批量粘贴', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '批量粘贴商品' })
    await dialog
      .getByRole('textbox', { name: '商品编码、数量和单价' })
      .fill('SKU-01\t0.000001\t9007199254740993.12345678')
    await dialog.getByRole('button', { name: '追加明细', exact: true }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('textbox', { name: prefix + '数量51', exact: true })).toHaveValue(
      '0.000001',
    )
    await expect(page.getByRole('textbox', { name: prefix + '单价51', exact: true })).toHaveValue(
      '9007199254740993.12345678',
    )
    await expect(footer).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(kind + '-editable-50.png'), fullPage: false })
    await page.getByRole('button', { name: '保存并提交' }).click()
    const approval = page.getByRole('dialog', { name: '提交' })
    await expect(approval).toBeVisible()
    expect(saved).toBe(true)
    expect(submitted).toBe(false)
    await expect(page).toHaveURL(new RegExp('/' + kind + '/70$'))
    await approval.getByRole('button', { name: '确认提交' }).click()
    await expect(approval).toBeHidden()
    await expect.poll(() => submitted).toBe(true)
  })
}
