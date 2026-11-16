import { expect, test } from '@playwright/test'
import { env } from 'node:process'
import { randomUUID } from 'node:crypto'
import type { UserOrganization } from '../../src/types/org'

interface Row {
  id: string
  version: number
  documentNo?: string
}

test('Y-02 角色自定义部门范围限制单据列表、详情、写命令及打印', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const password = env.ERP_DEV_ADMIN_PASSWORD
  if (!password) throw new Error('请使用 scripts/project.ps1 e2e 运行浏览器测试')
  const tenantCode = env.ERP_E2E_TENANT_CODE ?? 'DEMO',
    suffix = Date.now().toString().slice(-8)
  const name = '部门范围角色' + suffix,
    username = 'scope' + suffix
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(tenantCode)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  const profile: { id: string; companies: { id: string }[] } = (
    await (await page.request.get('/api/v1/auth/me')).json()
  ).data
  const company = profile.companies[0]!.id
  async function api<T>(method: string, path: string, data?: unknown, expected = 200): Promise<T> {
    const csrf =
      (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
    const response = await page.request.fetch('/api/v1' + path, {
      method,
      data,
      headers: { 'X-Company-Id': company, 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': randomUUID() },
    })
    expect(response.status(), method + ' ' + path).toBe(expected)
    const body: unknown = await response.json()
    return (body as { data: T }).data
  }
  const a = await api<Row>(
    'POST',
    '/org/departments',
    { code: 'DA' + suffix, name: '范围甲部门' + suffix, sortNo: 0 },
    201,
  )
  const b = await api<Row>(
    'POST',
    '/org/departments',
    { code: 'DB' + suffix, name: '范围乙部门' + suffix, sortNo: 1 },
    201,
  )
  const role = await api<Row>('POST', '/roles', { code: 'DS' + suffix, name }, 201)
  await api('PUT', '/roles/' + role.id + '/permissions', {
    permissionCodes: [
      'receipt:view',
      'receipt:create',
      'receipt:update',
      'receipt:submit',
      'receipt:print',
      'cash_account:view',
      'partner:view',
    ],
    version: 0,
  })
  const initialPassword = 'Start-' + randomUUID() + 'A1!',
    userPassword = 'Next-' + randomUUID() + 'A1!'
  const user = await api<Row>(
    'POST',
    '/users',
    { username, displayName: '范围测试员' + suffix, password: initialPassword },
    201,
  )
  await api('PUT', '/users/' + user.id + '/roles', { roleIds: [role.id], version: 0 })
  await api('PUT', '/users/' + user.id + '/data-scopes', {
    scopes: [{ companyId: company, scopeType: 'company' }],
    version: 1,
  })
  await api('PUT', '/users/' + user.id + '/organization', {
    departments: [{ id: a.id, primary: true }],
    postIds: [],
    version: 0,
  })
  const adminOrg = await api<UserOrganization>('GET', '/users/' + profile.id + '/organization')
  await api('PUT', '/users/' + profile.id + '/organization', {
    departments: [{ id: b.id, primary: true }],
    postIds: [],
    version: adminOrg.version,
  })
  const partner = await api<Row>(
    'POST',
    '/partners',
    { code: 'PC' + suffix, name: '范围客户' + suffix, roles: ['customer'] },
    201,
  )
  const account = await api<Row>(
    'POST',
    '/cash-accounts',
    { code: 'CA' + suffix, name: '范围账户' + suffix, accountType: 'cash' },
    201,
  )
  const businessDate = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
  const receipt = {
    receiptType: 'customer',
    partnerId: partner.id,
    cashAccountId: account.id,
    amount: '10.00',
    businessDate,
  }
  const outside = await api<Row>('POST', '/receipts', { ...receipt, remark: '乙部门单据' }, 201)
  await api('PUT', '/users/' + profile.id + '/organization', {
    departments: adminOrg.departments,
    postIds: adminOrg.postIds,
    version: adminOrg.version + 1,
  })

  await page.goto('/roles')
  await page.locator('#resource-search').fill(name)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page
    .getByRole('row')
    .filter({ hasText: name })
    .getByRole('button', { name: '权限设置', exact: true })
    .click()
  const editor = page.getByRole('region', { name: '角色部门数据范围', exact: true })
  await editor
    .locator('.el-select')
    .filter({ has: page.locator('#role-department-scope') })
    .click()
  await page.getByRole('option', { name: '自定义部门', exact: true }).click()
  await editor
    .locator('.el-select')
    .filter({ has: page.locator('#role-scope-departments') })
    .click()
  await page
    .locator('.el-select-dropdown:visible .el-tree-node__content')
    .filter({ hasText: '范围甲部门' + suffix })
    .locator('.el-checkbox')
    .click()
  await editor.getByRole('heading', { name: '部门数据范围', exact: true }).click()
  await editor.getByRole('button', { name: '保存部门范围', exact: true }).click()
  await expect(editor.getByText('当前公司的角色部门范围已保存')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('角色部门范围配置.png'), fullPage: true })

  await api('POST', '/auth/logout')
  await api('POST', '/auth/login', { tenantCode, username, password: initialPassword })
  await api('PUT', '/auth/password', { oldPassword: initialPassword, newPassword: userPassword })
  const own = await api<Row>('POST', '/receipts', { ...receipt, remark: '甲部门单据' }, 201)
  const ownDetails = await api<Row & { ownerDepartmentId: string }>('GET', '/receipts/' + own.id)
  expect(ownDetails.ownerDepartmentId).toBe(a.id)
  await api('GET', '/receipts/' + outside.id, undefined, 404)
  await api('POST', '/receipts/' + outside.id + '/submit', { version: 0 }, 404)
  await api('GET', '/print-data/receipt/' + own.id)
  await api('GET', '/print-data/receipt/' + outside.id, undefined, 404)
  await page.goto('/receipts')
  await page.getByRole('tab', { name: /^全部 / }).click()
  await expect(page.getByRole('row').filter({ hasText: own.documentNo! })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: outside.documentNo! })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('部门范围单据列表.png'), fullPage: true })
})
