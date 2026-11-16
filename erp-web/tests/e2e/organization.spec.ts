import { expect, test } from '@playwright/test'
import { env } from 'node:process'

test('Y-01 部门岗位维护、按公司任职及停用保护', async ({ page }, testInfo) => {
  test.setTimeout(150_000)
  const password = env.ERP_DEV_ADMIN_PASSWORD
  if (!password) throw new Error('请使用项目根目录的 scripts/project.ps1 e2e 运行测试')
  const suffix = Date.now().toString().slice(-8)
  const name = '销售部' + suffix,
    post = '业务岗' + suffix
  const browserErrors: string[] = []
  page.on('pageerror', (error) => browserErrors.push(error.message))
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE ?? 'DEMO')
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  const originalCompany = await page.locator('.company-select .el-select__placeholder').innerText()
  await page.goto('/organization')
  await page.getByRole('button', { name: '新建部门', exact: true }).click()
  await page.locator('#department-code').fill('D' + suffix)
  await page.locator('#department-name').fill(name)
  await page.getByRole('button', { name: '保存部门', exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '岗位', exact: true }).click()
  await page.getByRole('button', { name: '新建岗位', exact: true }).click()
  await page.getByLabel('编码', { exact: true }).fill('P' + suffix)
  await page.getByLabel('名称', { exact: true }).fill(post)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: post })).toBeVisible()
  await page.getByRole('tab', { name: '部门', exact: true }).click()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#organization-user') })
    .click()
  await page.getByRole('option').filter({ hasText: '（admin）' }).click()
  const assignment = page.getByRole('region', { name: '用户部门与岗位', exact: true })
  await expect(assignment.locator('#organization-departments')).toBeVisible()
  await assignment
    .locator('.el-select')
    .filter({ has: page.locator('#organization-departments') })
    .click()
  const departmentNode = page
    .locator('.el-select-dropdown:visible .el-tree-node__content')
    .filter({ hasText: name })
  await departmentNode.locator('.el-checkbox').click()
  await assignment.getByRole('heading', { name: '部门与岗位', exact: true }).click()
  await assignment
    .locator('.el-select')
    .filter({ has: page.locator('#organization-posts') })
    .click()
  await page.getByRole('option', { name: post, exact: true }).click()
  await assignment.getByRole('heading', { name: '部门与岗位', exact: true }).click()
  await assignment.getByRole('button', { name: '保存部门与岗位', exact: true }).click()
  await expect(assignment.getByText('当前公司的部门与岗位已保存')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('部门岗位与任职.png'), fullPage: true })
  await page.getByRole('button', { name: '停用部门', exact: true }).click()
  await page.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByText('请先移出下级部门和成员，再停用部门')).toBeVisible()
  await page.reload()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#organization-user') })
    .click()
  await page.getByRole('option').filter({ hasText: '（admin）' }).click()
  await expect(
    assignment.locator('.el-select').filter({ has: page.locator('#organization-departments') }),
  ).toContainText(name)
  await page.goto('/companies')
  await page.getByRole('button', { name: '新建公司', exact: true }).click()
  await page.getByLabel('编码', { exact: true }).fill('C' + suffix)
  await page.getByLabel('名称', { exact: true }).fill('乙公司' + suffix)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: '乙公司' + suffix })).toBeVisible()
  await page.locator('.company-select').click()
  await page.getByRole('option', { name: '乙公司' + suffix, exact: true }).click()
  await page.goto('/organization')
  await expect(page.getByText('当前公司尚未设置部门。')).toBeVisible()
  await page.getByRole('button', { name: '新建部门', exact: true }).click()
  await page.locator('#department-code').fill('D' + suffix)
  await page.locator('#department-name').fill('乙部门' + suffix)
  await page.getByRole('button', { name: '保存部门', exact: true }).click()
  await expect(page.getByRole('heading', { name: '乙部门' + suffix, exact: true })).toBeVisible()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#organization-user') })
    .click()
  await page.getByRole('option').filter({ hasText: '（admin）' }).click()
  await expect(
    assignment.locator('.el-select').filter({ has: page.locator('#organization-departments') }),
  ).not.toContainText(name)
  await assignment
    .locator('.el-select')
    .filter({ has: page.locator('#organization-departments') })
    .click()
  await page
    .locator('.el-select-dropdown:visible .el-tree-node__content')
    .filter({ hasText: '乙部门' + suffix })
    .locator('.el-checkbox')
    .click()
  await assignment.getByRole('heading', { name: '部门与岗位', exact: true }).click()
  await assignment.getByRole('button', { name: '保存部门与岗位', exact: true }).click()
  await expect(assignment.getByText('当前公司的部门与岗位已保存')).toBeVisible()
  await page.locator('.company-select').click()
  await page.getByRole('option', { name: originalCompany, exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  await page
    .locator('.el-select')
    .filter({ has: page.locator('#organization-user') })
    .click()
  await page.getByRole('option').filter({ hasText: '（admin）' }).click()
  await expect(
    assignment.locator('.el-select').filter({ has: page.locator('#organization-departments') }),
  ).toContainText(name)
  await expect(
    assignment.locator('.el-select').filter({ has: page.locator('#organization-posts') }),
  ).toContainText(post)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: testInfo.outputPath('部门岗位移动端.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  expect(browserErrors).toEqual([])
})
