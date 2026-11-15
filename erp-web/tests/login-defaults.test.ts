import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import LoginPage from '../src/pages/LoginPage.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('../src/api/auth', () => ({
  availableLoginMethods: vi.fn().mockResolvedValue({ sms: false, email: false, wechat: false }),
}))
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ signIn: vi.fn() }) }))
const input = defineComponent({
  props: ['modelValue', 'id'],
  template: '<input :id="id" :value="modelValue" />',
})
describe('本地管理员登录默认值', () => {
  it('开发页面预填企业和管理员账号', () => {
    const wrapper = mount(LoginPage, { global: { stubs: {
      BrandMark: true, ElForm: { template: '<form><slot/></form>' },
      ElFormItem: { template: '<div><slot/></div>' }, ElInput: input, ElButton: true, ElAlert: true,
    } } })
    expect((wrapper.get('#tenant').element as HTMLInputElement).value).toBe('DEMO')
    expect((wrapper.get('#username').element as HTMLInputElement).value).toBe('admin')
    wrapper.unmount()
  })
})
