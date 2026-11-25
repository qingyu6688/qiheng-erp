import { expect, test } from '@playwright/test'
import type { APIResponse, Page } from '@playwright/test'
import { env } from 'node:process'
import type { PageResult } from '../../src/types/api'
import type { UserSession } from '../../src/types/auth'
import type { WmsTaskSummary, WmsWarehouse } from '../../src/types/wms'

async function login(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('供应链浏览器验收须使用项目脚本的随机企业与临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('supply_tester')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
}

test('库区与库位可维护，库位标签从当前公司加载', async ({ page }) => {
  await login(page)
  await page.goto('/warehouses')
  await page.getByRole('button', { name: '新建仓库' }).click()
  const warehouse = page.getByRole('dialog')
  await warehouse.getByLabel('仓库编码').fill('E2E-M14-BIN')
  await warehouse.getByLabel('仓库名称').fill('库位验收仓')
  await warehouse.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: 'E2E-M14-BIN' })).toBeVisible()
  await page.getByRole('link', { name: '库区库位', exact: true }).last().click()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#slot-warehouse') })
    .click()
  await page.getByRole('option', { name: 'E2E-M14-BIN 库位验收仓' }).click()
  await page.getByRole('button', { name: '新增库区' }).click()
  await page.getByLabel('库区编码').fill('RC')
  await page.getByLabel('库区名称').fill('收货区')
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click()
  await page.getByRole('button', { name: '批量生成', exact: true }).click()
  await page.getByLabel('编码前缀').fill('RC')
  await page.getByRole('dialog').getByRole('button', { name: '生成', exact: true }).click()
  const slot = page.getByRole('row').filter({ hasText: 'RC-01-01-01-01' })
  await expect(slot).toBeVisible()
  await slot.getByRole('link', { name: '标签' }).click()
  await expect(page.getByRole('img', { name: '库位条码 RC-01-01-01-01' })).toBeVisible()
  await expect(page.getByRole('button', { name: '打印标签' })).toBeEnabled()
})

test('数电发票可查看开具记录、进项台账和服务商启用状态', async ({ page }) => {
  await login(page)
  await page.goto('/e-invoices')
  await expect(page.getByRole('heading', { name: '数电发票' })).toBeVisible()
  await expect(page.getByText('暂无开具申请')).toBeVisible()
  await page.getByRole('button', { name: '新建开具申请' }).click()
  const dialog = page.getByRole('dialog', { name: '新建开具申请' })
  await expect(dialog.getByRole('button', { name: '提交申请' })).toBeDisabled()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('tab', { name: '进项台账' }).click()
  await expect(page.getByText('此条件下暂无进项发票', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '服务商配置' }).click()
  await expect(page.getByRole('button', { name: '测试已保存的连接' })).toBeDisabled()
})

async function dataOf<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `接口请求失败，状态 ${response.status()}`).toBe(true)
  return ((await response.json()) as { data: T }).data
}

async function createPutawayWarehouse(
  page: Page,
  headers: Record<string, string>,
): Promise<{ id: string; code: string; receivingId: string }> {
  const code = 'M17-' + crypto.randomUUID().slice(0, 10).toUpperCase()
  const warehouse = await dataOf<{ id: string; version: number }>(
    await page.request.post('/api/v1/warehouses', {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: { code, name: '作业模式验收仓' },
    }),
  )
  const root = `/api/v1/warehouses/${warehouse.id}/locations`
  const zone = await dataOf<{ id: string }>(
    await page.request.put(root + '/zones', {
      headers,
      data: { code: 'RC', name: '收货区', type: 'receiving', enabled: true },
    }),
  )
  const slot = await dataOf<{ id: string }>(
    await page.request.put(root, {
      headers,
      data: { zoneId: zone.id, code: 'RC-01', name: '收货位', enabled: true },
    }),
  )
  const shippingZone = await dataOf<{ id: string }>(
    await page.request.put(root + '/zones', {
      headers,
      data: { code: 'SC', name: '发货区', type: 'shipping', enabled: true },
    }),
  )
  const shipping = await dataOf<{ id: string }>(
    await page.request.put(root, {
      headers,
      data: { zoneId: shippingZone.id, code: 'SC-01', name: '发货位', enabled: true },
    }),
  )
  await dataOf(
    await page.request.put(root + '/settings', {
      headers,
      data: { receivingId: slot.id, shippingId: shipping.id, version: warehouse.version },
    }),
  )
  return { id: warehouse.id, code, receivingId: slot.id }
}

test('M17 上架任务菜单读取当前仓库，库位仓作业模式默认关闭且可开启', async ({ page }) => {
  await login(page)
  const user = await dataOf<UserSession>(await page.request.get('/api/v1/auth/me'))
  const token = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
  const headers = { 'X-Company-Id': user.companies[0]!.id, 'X-XSRF-TOKEN': token?.value ?? '' }
  const warehouse = await createPutawayWarehouse(page, headers)
  const list = () =>
    page.request.get('/api/v1/wms/warehouses', { headers }).then(dataOf<{ items: WmsWarehouse[] }>)
  expect((await list()).items.find((row) => row.id === warehouse.id)).toMatchObject({
    taskMode: 'off',
    locationManaged: true,
  })

  await page.getByRole('button', { name: '库存', exact: true }).click()
  const link = page.getByRole('link', { name: '上架任务', exact: true })
  await link.click()
  await expect(page.getByRole('heading', { name: '上架任务', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '仓库任务模式' }).click()
  const settings = page.getByRole('region', { name: '仓库任务模式' })
  await settings.getByRole('combobox', { name: '配置仓库' }).press('Enter')
  await page.getByRole('option', { name: `${warehouse.code} · 作业模式验收仓` }).click()
  await expect(settings.getByRole('button', { name: '保存模式' })).toBeDisabled()
  await settings.getByRole('combobox', { name: '任务模式' }).press('Enter')
  await page.getByRole('option', { name: '生成上架任务' }).click()
  await settings.getByRole('button', { name: '保存模式' }).click()
  await expect(page.getByText('仓库任务模式已保存。')).toBeVisible()
  expect((await list()).items.find((row) => row.id === warehouse.id)?.taskMode).toBe('optional')

  const tasks = await dataOf<PageResult<WmsTaskSummary>>(
    await page.request.get('/api/v1/wms/tasks', { headers, params: { warehouseId: warehouse.id } }),
  )
  expect(tasks.items).toEqual([])
  await expect(page.getByText('暂无匹配的上架任务')).toBeVisible()

  await page.getByRole('button', { name: '仓库任务模式' }).click()
  await settings.getByRole('combobox', { name: '任务模式' }).press('Enter')
  await page.getByRole('option', { name: '拣货复核后发货' }).click()
  await settings.getByRole('button', { name: '保存模式' }).click()
  await expect
    .poll(async () => (await list()).items.find((row) => row.id === warehouse.id)?.taskMode)
    .toBe('required')

  await page.locator('.nav-group a[href="/warehouse-work"]').click()
  await expect(page.getByRole('heading', { name: '仓储作业', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '标签打印' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '拣货', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '波次' })).toBeVisible()

  const scanned = await dataOf<{ type: string; id: string }>(
    await page.request.get('/api/v1/wms/scan/resolve', {
      headers,
      params: { warehouseId: warehouse.id, code: 'L:RC-01' },
    }),
  )
  expect(scanned).toMatchObject({ type: 'location', id: warehouse.receivingId })
  const label = await page.request.post('/api/v1/labels/print-jobs', {
    headers,
    data: {
      warehouseId: warehouse.id,
      size: '50x30',
      format: 'pdf',
      items: [{ type: 'location', id: warehouse.receivingId }],
    },
  })
  expect(label.ok()).toBe(true)
  expect(label.headers()['content-type']).toContain('application/pdf')
  expect((await label.body()).subarray(0, 5).toString()).toBe('%PDF-')
})
