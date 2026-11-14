import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import * as api from '../src/api/masterdata'
import { ApiError } from '../src/api/http'
import WarehouseDialog from '../src/components/WarehouseDialog.vue'
import type { Warehouse } from '../src/types/warehouse'

vi.mock('../src/api/masterdata')
const record: Warehouse = {
  id: '5',
  code: 'W1',
  name: '主仓',
  address: null,
  version: 3,
  status: 'active',
  allowedActions: ['update', 'disable'],
}

const dialogStub = defineComponent({
  template: '<section><slot/><footer><slot name="footer"/></footer></section>',
})
const inputStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const buttonStub = defineComponent({ template: '<button><slot/></button>' })
const alertStub = defineComponent({
  props: ['title'],
  template: '<div role="alert">{{ title }}</div>',
})
const stubs = {
  'el-dialog': dialogStub,
  'el-input': inputStub,
  'el-alert': alertStub,
  'el-button': buttonStub,
  'el-form': { template: '<form><slot/></form>' },
  'el-form-item': { props: ['error'], template: '<label><slot/><span>{{ error }}</span></label>' },
}
function mountEditor(mode: 'create' | 'update') {
  return mount(WarehouseDialog, {
    props: { mode, warehouse: mode === 'update' ? record : undefined },
    global: { stubs },
  })
}
type Editor = ReturnType<typeof mountEditor>
async function click(editor: Editor, label: string): Promise<void> {
  const button = editor.findAll('button').find((b) => b.text() === label)
  if (!button) throw new Error('找不到操作按钮 ' + label)
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
})

describe('仓库编辑', () => {
  it('表单校验通过才提交，提交前去除两端空格', async () => {
    const editor = mountEditor('create')
    await click(editor, '保存')
    expect(editor.text()).toContain('请输入仓库编码。')
    expect(api.createWarehouse).not.toHaveBeenCalled()
    await editor.get('#warehouse-code').setValue(' NG ')
    await editor.get('#warehouse-name').setValue(' 南港仓 ')
    vi.mocked(api.createWarehouse).mockResolvedValue(record)
    await click(editor, '保存')
    expect(api.createWarehouse).toHaveBeenCalledWith(
      { code: 'NG', name: '南港仓', address: null },
      expect.any(String),
    )
    expect(editor.emitted('saved')).toHaveLength(1)
    editor.unmount()
  })
  it('请求超时先查询结果，确认没有记录时才用原键重试', async () => {
    vi.mocked(api.createWarehouse)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
      .mockResolvedValueOnce(record)
    vi.mocked(api.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '没有记录'))
    const editor = mountEditor('create')
    await editor.get('#warehouse-code').setValue('NG')
    await editor.get('#warehouse-name').setValue('南港仓')
    await click(editor, '保存')
    expect(editor.text()).toContain('请求结果待确认')
    const firstKey = vi.mocked(api.createWarehouse).mock.calls[0][1]
    await click(editor, '查询结果')
    expect(api.commandResult).toHaveBeenCalledWith('warehouse.create', firstKey)
    await click(editor, '保存')
    expect(vi.mocked(api.createWarehouse).mock.calls[1][1]).toBe(firstKey)
    expect(editor.emitted('saved')).toHaveLength(1)
    editor.unmount()
  })
  it('版本冲突时保留输入，刷新后按新版本继续修改', async () => {
    vi.mocked(api.updateWarehouse)
      .mockRejectedValueOnce(new ApiError(409, 'VERSION_CONFLICT', '仓库资料已被修改'))
      .mockResolvedValueOnce(record)
    vi.mocked(api.warehouseDetail).mockResolvedValue({
      ...record,
      name: '库管修改后的仓库',
      version: 4,
    })
    const editor = mountEditor('update')
    await editor.get('#warehouse-name').setValue('我的修改')
    await click(editor, '保存')
    expect((editor.get('#warehouse-name').element as HTMLInputElement).value).toBe('我的修改')
    await click(editor, '刷新查看')
    expect((editor.get('#warehouse-name').element as HTMLInputElement).value).toBe(
      '库管修改后的仓库',
    )
    await editor.get('#warehouse-name').setValue('最终名称')
    await click(editor, '保存')
    expect(api.updateWarehouse).toHaveBeenLastCalledWith(
      '5',
      { name: '最终名称', code: 'W1', address: null },
      4,
    )
    editor.unmount()
  })
})
