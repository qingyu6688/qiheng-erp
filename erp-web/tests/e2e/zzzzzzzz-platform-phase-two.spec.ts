import { expect, test, type Page } from '@playwright/test'
import { createHmac } from 'node:crypto'
import { env } from 'node:process'

async function credentials(page: Page): Promise<void> {
  if (!env.ERP_E2E_PASSWORD || !/^E2E-[a-f0-9]{12}$/.test(env.ERP_E2E_TENANT_CODE ?? '')) {
    throw new Error('平台二期验收需要随机测试企业和临时口令')
  }
  await page.goto('/login')
  await page.getByLabel('企业编码').fill(env.ERP_E2E_TENANT_CODE!)
  await page.getByLabel('用户名').fill('admin')
  await page.locator('#password').fill(env.ERP_E2E_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

function totp(encoded: string, now: number): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  const key: number[] = []
  let bits = 0
  let buffer = 0
  for (const char of encoded.replace(/=+$/, '')) {
    const value = alphabet.indexOf(char)
    if (value < 0) throw new Error('验证器密钥不是 Base32 字符串')
    buffer = (buffer << 5) | value
    bits += 5
    if (bits >= 8) {
      bits -= 8
      key.push((buffer >>> bits) & 255)
    }
  }
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(now / 30_000)))
  const digest = createHmac('sha1', Buffer.from(key)).update(counter).digest()
  const offset = digest[digest.length - 1]! & 15
  return String((digest.readUInt32BE(offset) & 0x7fff_ffff) % 1_000_000).padStart(6, '0')
}

test('平台二期可从工作台访问并预览新编号', async ({ page }) => {
  await credentials(page)
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  await page.goto('/custom-fields')
  await expect(page.getByRole('heading', { name: '自定义字段' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增字段' })).toBeVisible()
  await page.goto('/code-rules')
  await expect(page.getByRole('heading', { name: '编码规则' })).toBeVisible()
  await page.getByRole('button', { name: '新增规则' }).click()
  await expect(page.getByRole('dialog').locator('.rule-preview strong')).toHaveText(/^SO-\d{12}$/)
  await page.goto('/open-platform')
  await expect(page.getByRole('heading', { name: '开放平台' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '接口文档' })).toBeVisible()
})

test('首次绑定验证器后，密码登录要求一次性恢复码或验证码', async ({ page }) => {
  await credentials(page)
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  await page.goto('/account/security')
  await page.getByRole('button', { name: '绑定验证器' }).click()
  await expect(page.getByRole('img', { name: '验证器绑定二维码' })).toBeVisible()
  const secret = (await page.locator('.mfa-instructions code').innerText()).trim()
  await page.getByLabel('验证码', { exact: true }).fill(totp(secret, Date.now()))
  await page.getByRole('button', { name: '验证并启用' }).click()
  await expect(page.getByRole('heading', { name: '一次性恢复码' })).toBeVisible()
  const recovery = (await page.locator('.panel ul code').first().innerText()).trim()
  await page.getByRole('button', { name: '已保存', exact: true }).click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  await credentials(page)
  await expect(page.getByRole('heading', { name: '账号安全验证' })).toBeVisible()
  await page.getByLabel('验证码或恢复码').fill(recovery)
  await page.getByRole('button', { name: '验证', exact: true }).click()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
})
