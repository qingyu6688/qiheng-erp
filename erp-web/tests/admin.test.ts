import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '../src/api/http'
import { allRecords, records, writeRecord } from '../src/api/admin'
import { editorPayload, fieldValues, flattenRows } from '../src/utils/admin'
import type { FormField } from '../src/types/admin'
import { defaultWorkspace } from '../src/pages/resources'

vi.mock('../src/api/http', () => ({ request: vi.fn() }))
beforeEach(() => vi.mocked(request).mockReset())

describe('基础资料表单', () => {
  it('初始密码保留用户输入的前后空格', () => {
    expect(editorPayload([{key: 'password', label: '初始密码', kind: 'password'}], {password: '  Pass word  '}))
      .toEqual({password: '  Pass word  '})
  })
  it('有权限编辑银行账号时明确提交空字符串以清空', () => {
    expect(editorPayload([{key: 'accountNo', label: '银行账号', emptyAsNull: false}], {accountNo: ''}, {id:'3', version:2}))
      .toEqual({accountNo: '', version:2})
    expect(editorPayload([], {}, {id:'3', version:2})).not.toHaveProperty('accountNo')
  })
  it('仅获商品权限的用户登录后进入商品页面', () => {
    expect(defaultWorkspace(permission => permission === 'product:view', false)).toBe('/products')
  })
  it('编辑提交保留字符串价格和原商品关联，移除初始密码', () => {
    const fields: FormField[] = [
      { key: 'price', label: '价格' },
      { key: 'password', label: '密码', createOnly: true },
      { key: 'productId', label: '商品', readonlyOnEdit: true },
      { key: 'roles', label: '角色', kind: 'multiple' },
    ]
    const row = {
      id: '3',
      version: 5,
      price: '999999999999999.01',
      productId: '2',
      roles: ['customer'],
    }
    const values = fieldValues(fields, row)
    values.productId = '其他商品'
    expect(editorPayload(fields, values, row)).toEqual({
      price: '999999999999999.01',
      productId: '2',
      roles: ['customer'],
      version: 5,
    })
  })
  it('分类下拉保留树层级和字符串编号', () => {
    const rows = [
      {
        id: '9007199254740993',
        name: '服装',
        children: [{ id: '9007199254740994', name: '上装' }],
      },
    ]
    expect(flattenRows(rows).map((r) => [r.id, r.treeLabel])).toEqual([
      ['9007199254740993', '服装'],
      ['9007199254740994', '　上装'],
    ])
  })
})

describe('基础资料请求', () => {
  it('为新建传递原幂等键并使用指定公司上下文', async () => {
    vi.mocked(request).mockResolvedValue({ id: '1' })
    await writeRecord('cash-accounts', { code: 'BANK' }, 'POST', true, 'same-key')
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        headers: { 'Idempotency-Key': 'same-key' },
      }),
      true,
    )
  })
  it('选择其他公司仓库时显式传递该公司编号', async () => {
    vi.mocked(request).mockResolvedValue({ items: [], nextCursor: null })
    await records('warehouses', false, {}, '42')
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({ headers: { 'X-Company-Id': '42' } }),
      false,
    )
  })
  it('表单关联项读取全部分页，保持后续页可选择', async () => {
    vi.mocked(request)
      .mockResolvedValueOnce({ items: [{ id: '1' }], nextCursor: 'second' })
      .mockResolvedValueOnce({ items: [{ id: '2' }], nextCursor: null })
    expect(await allRecords('products')).toEqual([{ id: '1' }, { id: '2' }])
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({ params: { limit: 200, cursor: 'second' } }),
      false,
    )
  })
  it('遇到循环分页拒绝继续加载', async () => {
    vi.mocked(request).mockResolvedValue({ items: [], nextCursor: 'again' })
    await expect(allRecords('products')).rejects.toThrow('资料分页异常')
  })
})
