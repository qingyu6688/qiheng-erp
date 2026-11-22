import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AiMarkdown from '../src/components/AiMarkdown.vue'
import AiSources from '../src/components/AiSources.vue'

describe('AI 只读详情入口', () => {
  it('百科正文的内部链接交给演示抽屉，不执行整页跳转', async () => {
    const preview = vi.fn()
    const wrapper = mount(AiMarkdown, {
      props: { content: '关联 [[inventory-reservations]]', wikiLinks: true },
      global: { provide: { 'erp.previewWiki': preview } },
    })
    const link = wrapper.get('a')
    const click = new MouseEvent('click', { bubbles: true, cancelable: true })
    link.element.dispatchEvent(click)
    expect(click.defaultPrevented).toBe(true)
    expect(preview).toHaveBeenCalledWith('/ai/wiki/inventory-reservations')
    wrapper.unmount()
  })

  it('引用按钮使用右侧预览入口，不打开居中对话框', async () => {
    const preview = vi.fn()
    const wrapper = mount(AiSources, {
      props: { sources: [{ segmentId: '93001', label: '查看依据 [1]' }] },
      global: {
        provide: { 'erp.aiSourcePreview': preview },
        stubs: { ElButton: { template: '<button><slot /></button>' }, ElDialog: true, ElAlert: true, RouterLink: true },
      },
    })
    await wrapper.get('button').trigger('click')
    expect(preview).toHaveBeenCalledWith('93001')
    expect(wrapper.getComponent({ name: 'ElDialog' }).attributes('modelvalue')).toBe('false')
    wrapper.unmount()
  })
})
