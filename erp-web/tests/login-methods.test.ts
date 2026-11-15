import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPage from '../src/pages/LoginPage.vue'
import LoginSettingsPage from '../src/pages/LoginSettingsPage.vue'
import AccountLoginMethodsPage from '../src/pages/AccountLoginMethodsPage.vue'
import type { LoginSettings } from '../src/types/login-settings'

const api = vi.hoisted(() => ({
  availableLoginMethods: vi.fn(),
  loginBindings: vi.fn(),
  sendLoginCode: vi.fn(),
  sendBindingCode: vi.fn(),
  bindContact: vi.fn(),
  signInByCode: vi.fn(),
  signIn: vi.fn(),
  replace: vi.fn(),
  getLoginSettings: vi.fn(),
  saveLoginSettings: vi.fn(),
}))
vi.mock('../src/api/auth', () => api)
vi.mock('../src/api/login-settings', () => api)
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ signIn: api.signIn, signInByCode: api.signInByCode }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: api.replace }),
}))

const stubs = {
  BrandMark: true,
  ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue', 'id'],
    emits: ['update:modelValue'],
    template:
      '<input :id="id" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElButton: {
    props: ['nativeType'],
    template: '<button :type="nativeType || \'button\'"><slot /></button>',
  },
  ElSwitch: true,
  ElInputNumber: true,
  ElSelect: true,
  ElOption: true,
  ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
}
const sampleSettings: LoginSettings = {
  sms: {
    enabled: false,
    secretConfigured: true,
    accessKeyId: 'ID',
    signName: '签名',
    templateCode: 'SMS_123',
  },
  email: {
    enabled: false,
    secretConfigured: false,
    host: '',
    port: 587,
    security: 'starttls',
    username: '',
    from: '',
  },
  wechat: { enabled: false, secretConfigured: false, appId: '', callbackUrl: '' },
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
})

afterEach(() => vi.useRealTimers())

describe('备用登录方式', () => {
  it('发现企业已启用的方式，发送并提交规范化手机号与验证码', async () => {
    api.availableLoginMethods.mockResolvedValue({ sms: true, email: false, wechat: false })
    api.sendLoginCode.mockResolvedValue(undefined)
    const wrapper = mount(LoginPage, { global: { stubs } })
    await vi.advanceTimersByTimeAsync(200)
    const pick = wrapper.get('[aria-label="登录方式"]')
    expect(pick.text()).toContain('手机短信')
    expect(pick.text()).not.toContain('邮箱')
    await pick.get('button:nth-child(2)').trigger('click')
    await wrapper.get('#login-destination').setValue('13800138000')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '发送验证码')!
      .trigger('click')
    await flushPromises()
    expect(api.sendLoginCode).toHaveBeenCalledWith({
      tenantCode: 'DEMO',
      method: 'sms',
      destination: '+8613800138000',
    })
    await wrapper.get('#login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.signInByCode).toHaveBeenCalledWith({
      tenantCode: 'DEMO',
      method: 'sms',
      destination: '+8613800138000',
      code: '123456',
    })
    expect(api.replace).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })

  it('切换企业时忽略已取消的旧企业响应，账号密码可继续使用', async () => {
    let resolveOld!: (value: { sms: boolean; email: boolean; wechat: boolean }) => void
    api.availableLoginMethods.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve
      }),
    )
    api.availableLoginMethods.mockResolvedValueOnce({ sms: false, email: false, wechat: false })
    const wrapper = mount(LoginPage, { global: { stubs } })
    await vi.advanceTimersByTimeAsync(200)
    await wrapper.get('#tenant').setValue('OTHER')
    await vi.advanceTimersByTimeAsync(200)
    resolveOld({ sms: true, email: true, wechat: true })
    await flushPromises()
    expect(wrapper.find('[aria-label="登录方式"]').exists()).toBe(false)
    expect(wrapper.find('#username').exists()).toBe(true)
    wrapper.unmount()
  })

  it('后台只提交新输入的密钥，保存后清空输入', async () => {
    api.getLoginSettings.mockResolvedValue(sampleSettings)
    api.saveLoginSettings.mockResolvedValue(sampleSettings)
    const wrapper = mount(LoginSettingsPage, { global: { stubs } })
    await flushPromises()
    expect((wrapper.get('#sms-secret').element as HTMLInputElement).value).toBe('')
    await wrapper.get('#sms-secret').setValue('replacement')
    await wrapper.get('form').trigger('submit')
    expect(api.saveLoginSettings).toHaveBeenCalledWith(
      expect.objectContaining({
        sms: expect.objectContaining({ enabled: false, secret: 'replacement' }),
      }),
    )
    expect(Object.keys(api.saveLoginSettings.mock.calls[0][0])).toEqual(['sms'])
    expect(api.saveLoginSettings.mock.calls[0][0].sms).not.toHaveProperty('secretConfigured')
    expect((wrapper.get('#sms-secret').element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })

  it('个人绑定先用当前密码发送，再只提交验证码及原邮箱', async () => {
    const bindings = {
      sms: null,
      email: null,
      wechat: false,
      methods: { sms: false, email: true, wechat: false },
    }
    api.loginBindings
      .mockResolvedValueOnce(bindings)
      .mockResolvedValueOnce({ ...bindings, email: 'a***@example.com' })
    const wrapper = mount(AccountLoginMethodsPage, { global: { stubs } })
    await flushPromises()
    await wrapper.get('.binding-row:nth-child(2) button').trigger('click')
    await wrapper.get('#binding-password').setValue('current-pass')
    await wrapper.get('#binding-destination').setValue('ALICE@EXAMPLE.COM')
    await wrapper.get('form').trigger('submit')
    expect(api.sendBindingCode).toHaveBeenCalledWith('email', 'alice@example.com', 'current-pass')
    expect(wrapper.find('#binding-password').exists()).toBe(false)
    await wrapper.get('#binding-code').setValue('654321')
    await wrapper.get('form').trigger('submit')
    expect(api.bindContact).toHaveBeenCalledWith('email', 'alice@example.com', '654321')
    expect(wrapper.text()).toContain('a***@example.com')
    wrapper.unmount()
  })
})
