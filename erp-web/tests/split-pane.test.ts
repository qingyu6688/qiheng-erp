import { afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SplitPane from '../src/components/SplitPane.vue'

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

it('键盘调整遵循范围，宽度按调用方的用户公司配置恢复', async () => {
  const wrapper = mount(SplitPane, {
    props: { storageKey: 'split.user1.company1' },
    slots: { primary: '部门树', secondary: '部门成员' },
  })
  const separator = wrapper.get('[role="separator"]')
  expect(separator.attributes('aria-valuenow')).toBe('280')
  await separator.trigger('keydown', { key: 'ArrowRight' })
  expect(separator.attributes('aria-valuenow')).toBe('296')
  await separator.trigger('keydown', { key: 'ArrowRight', shiftKey: true })
  expect(separator.attributes('aria-valuenow')).toBe('360')
  await separator.trigger('keydown', { key: 'Home' })
  expect(separator.attributes('aria-valuenow')).toBe('220')
  await separator.trigger('keydown', { key: 'End' })
  expect(separator.attributes('aria-valuenow')).toBe('480')
  expect(localStorage.getItem('split.user1.company1')).toBe('480')
  await wrapper.setProps({ storageKey: 'split.user1.company2' })
  expect(separator.attributes('aria-valuenow')).toBe('280')
  await wrapper.setProps({ storageKey: 'split.user1.company1' })
  expect(separator.attributes('aria-valuenow')).toBe('480')
  localStorage.setItem('split.user2.company1', '损坏的数据')
  await wrapper.setProps({ storageKey: 'split.user2.company1' })
  expect(separator.attributes('aria-valuenow')).toBe('280')
  wrapper.unmount()
})

it('指针拖动在容器内限宽，结束后忽略旧指针并保存最终宽度', async () => {
  const wrapper = mount(SplitPane, { props: { storageKey: 'split.drag' } })
  vi.spyOn(wrapper.element, 'getBoundingClientRect').mockReturnValue(new DOMRect(20, 0, 900, 500))
  const separator = wrapper.get('[role="separator"]')
  const captured = new Set<number>()
  Object.assign(separator.element, {
    setPointerCapture: (id: number) => captured.add(id),
    hasPointerCapture: (id: number) => captured.has(id),
    releasePointerCapture: (id: number) => captured.delete(id),
  })
  await separator.trigger('pointerdown', { pointerId: 3, button: 0, clientX: 320 })
  expect(separator.attributes('aria-valuenow')).toBe('300')
  await separator.trigger('pointermove', { pointerId: 3, clientX: 900 })
  expect(separator.attributes('aria-valuenow')).toBe('480')
  await separator.trigger('pointerup', { pointerId: 3 })
  expect(localStorage.getItem('split.drag')).toBe('480')
  expect(captured.size).toBe(0)
  await separator.trigger('pointermove', { pointerId: 3, clientX: 250 })
  expect(separator.attributes('aria-valuenow')).toBe('480')
  await separator.trigger('pointerdown', { pointerId: 4, button: 2, clientX: 250 })
  expect(separator.attributes('aria-valuenow')).toBe('480')
  wrapper.unmount()
})

it('浏览器禁止保存时仍可完成当次键盘操作', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('存储受限')
  })
  const wrapper = mount(SplitPane, { props: { storageKey: 'split.restricted' } })
  const separator = wrapper.get('[role="separator"]')
  await separator.trigger('keydown', { key: 'ArrowLeft' })
  expect(separator.attributes('aria-valuenow')).toBe('264')
  wrapper.unmount()
})
