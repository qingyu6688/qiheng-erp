import { describe, expect, it } from 'vitest'
import { demoTarget, parseDemoConfig } from '@/config/demo'

describe('parseDemoConfig', () => {
  it('未配置地址时返回 null，全站显示“筹备中”', () => {
    expect(parseDemoConfig({})).toEqual({ url: null, tenant: null, note: null })
    expect(parseDemoConfig({ VITE_DEMO_URL: '   ' }).url).toBeNull()
  })

  it('接受 https 地址并去掉空白', () => {
    const config = parseDemoConfig({ VITE_DEMO_URL: ' https://demo.example.com ', VITE_DEMO_TENANT: ' DEMO ', VITE_DEMO_NOTE: '' })
    expect(config).toEqual({ url: 'https://demo.example.com/', tenant: 'DEMO', note: null })
  })

  it('拒绝 http 地址，让构建失败', () => {
    expect(() => parseDemoConfig({ VITE_DEMO_URL: 'http://demo.example.com' })).toThrow('https')
  })

  it('地址格式不对时直接报错', () => {
    expect(() => parseDemoConfig({ VITE_DEMO_URL: 'demo.example.com' })).toThrow()
  })
})

describe('demoTarget', () => {
  it('跳转时附带来源参数，保留原有参数', () => {
    expect(demoTarget('https://demo.example.com/login?tenant=DEMO')).toBe('https://demo.example.com/login?tenant=DEMO&from=website')
  })
})
