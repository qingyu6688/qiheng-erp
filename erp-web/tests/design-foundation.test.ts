import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import DisplayDensitySelect from '../src/components/DisplayDensitySelect.vue'
import StatusTag from '../src/components/StatusTag.vue'
import { provideDisplayDensity } from '../src/hooks/useDisplayDensity'
import { businessDateRange } from '../src/utils/business-date-range'

const userId = ref<string>()
const preference = 'erp.display-density.'
const DensityHost = defineComponent({
  setup() {
    provideDisplayDensity(() => userId.value)
    return () => h(DisplayDensitySelect)
  },
})

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  userId.value = undefined
})

describe('显示密度偏好', () => {
  it('按当前登录用户保存与恢复，并在退出后使用默认值', async () => {
    userId.value = '1001'
    const wrapper = mount(DensityHost)
    const select = wrapper.get('select')
    expect(select.element.value).toBe('default')
    await select.setValue('compact')
    expect(localStorage.getItem(preference + '1001')).toBe('compact')

    userId.value = '1002'
    await wrapper.vm.$nextTick()
    expect(select.element.value).toBe('default')
    await select.setValue('comfortable')
    expect(localStorage.getItem(preference + '1002')).toBe('comfortable')

    userId.value = '1001'
    await wrapper.vm.$nextTick()
    expect(select.element.value).toBe('compact')
    userId.value = undefined
    await wrapper.vm.$nextTick()
    expect(select.element.value).toBe('default')
    wrapper.unmount()
  })

  it('浏览器禁止读写偏好时，选择框仍显示默认值', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('存储访问受限')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('存储访问受限')
    })
    userId.value = '1001'
    const wrapper = mount(DensityHost)
    const select = wrapper.get('select')
    expect(select.element.value).toBe('default')
    await select.setValue('compact')
    expect(select.element.value).toBe('default')
    wrapper.unmount()
  })
})

describe('状态标签', () => {
  it('始终以可见文字呈现状态，同时应用语义色', async () => {
    const wrapper = mount(StatusTag, { props: { label: '草稿' } })
    expect(wrapper.attributes('data-tone')).toBe('neutral')
    expect(wrapper.text()).toBe('草稿')
    await wrapper.setProps({ label: '已作废', tone: 'danger' })
    expect(wrapper.attributes('data-tone')).toBe('danger')
    expect(wrapper.text()).toBe('已作废')
    wrapper.unmount()
  })
})

describe('业务日期快捷选项', () => {
  it('使用周一开始的日历周，跨月和跨年不受本机时区影响', () => {
    expect(businessDateRange('today', '2026-11-17')).toEqual(['2026-11-17', '2026-11-17'])
    expect(businessDateRange('week', '2026-01-01')).toEqual(['2025-12-29', '2026-01-04'])
    expect(businessDateRange('week', '2026-11-15')).toEqual(['2026-11-09', '2026-11-15'])
    expect(businessDateRange('month', '2024-02-03')).toEqual(['2024-02-01', '2024-02-29'])
    expect(businessDateRange('previousMonth', '2026-01-01')).toEqual(['2025-12-01', '2025-12-31'])
    expect(businessDateRange('year', '2026-12-01')).toEqual(['2026-01-01', '2026-12-31'])
  })
})
