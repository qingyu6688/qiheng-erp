// @vitest-environment node

import { describe, expect, it } from 'vitest'
import { projectTestStorage, webTestStorage } from '../scripts/test-storage'

const windows = { platform: 'win32' as const, repoRoot: 'C:/workspace/ERP', envText: '' }

describe('Web 测试输出目录', () => {
  it('移动端与浏览器临时目录沿用同一测试根并区分项目', () => {
    const storage = projectTestStorage('erp-mobile', {
      ...windows,
      environment: { ERP_TEST_ROOT: 'D:/test-data' },
    })
    expect(storage.output('vitest/cache', '').replaceAll('\\', '/')).toBe(
      'D:/test-data/erp-mobile/vitest/cache',
    )
    expect(storage.temporaryDirectory?.replaceAll('\\', '/')).toBe('D:/test-data/erp-mobile/tmp')
  })
  it('环境变量优先于私有 .env，所有证据放在同一根目录', () => {
    const storage = webTestStorage({
      ...windows,
      environment: { ERP_TEST_ROOT: 'D:/test-data', ERP_A11Y_OUTPUT_DIR: 'E:/old-evidence' },
      envText: 'ERP_TEST_ROOT=E:/from-file',
    })
    expect(storage.root).toBe('D:\\test-data')
    expect(storage.output('a11y/evidence', '', 'ERP_A11Y_OUTPUT_DIR')).toBe(
      'D:\\test-data\\erp-web\\a11y\\evidence',
    )
    expect(storage.reporterEnvironment.PLAYWRIGHT_JSON_OUTPUT_NAME).toBe(
      'D:\\test-data\\erp-web\\playwright\\results.json',
    )
  })

  it('支持私有 .env 的引号、注释和 Windows 路径，不导出其他私有配置', () => {
    const storage = webTestStorage({
      ...windows,
      environment: {},
      envText: 'ERP_TEST_ROOT="D:/team tests" # 测试目录\nPRIVATE_SETTING=hidden',
    })
    expect(storage.root).toBe('D:\\team tests')
    expect(storage.reporterEnvironment).not.toHaveProperty('PRIVATE_SETTING')
  })

  it.each(['C:/test-data', 'c:\\test-data', '\\\\?\\C:\\test-data', '.local/test-data'])(
    'Windows 拒绝解析到 C 盘的目录：%s',
    (root) => {
      expect(() => webTestStorage({ ...windows, environment: { ERP_TEST_ROOT: root } })).toThrow(
        '不能指向 C 盘',
      )
    },
  )

  it('Windows 不允许显式把旧报告路径指回 C 盘，也不接受盘符相对路径', () => {
    expect(() =>
      webTestStorage({
        ...windows,
        environment: { ERP_TEST_ROOT: 'D:/test-data', PLAYWRIGHT_HTML_OUTPUT_DIR: 'C:/reports' },
      }),
    ).toThrow('不能指向 C 盘')
    expect(() =>
      webTestStorage({ ...windows, environment: { ERP_TEST_ROOT: 'D:reports' } }),
    ).toThrow('盘符相对路径')
    expect(() =>
      webTestStorage({ ...windows, environment: {} }).output(
        'results',
        'C:/workspace/ERP/erp-web/test-results',
      ),
    ).toThrow('不能指向 C 盘')
  })

  it('Linux 与 CI 无设置时保留现有目录及旧的独立无障碍输出参数', () => {
    const storage = webTestStorage({
      platform: 'linux',
      repoRoot: '/workspace/ERP',
      environment: { ERP_A11Y_OUTPUT_DIR: '/tmp/a11y' },
      envText: '',
    })
    expect(storage.root).toBeUndefined()
    expect(storage.output('playwright/results', '/workspace/ERP/erp-web/test-results')).toBe(
      '/workspace/ERP/erp-web/test-results',
    )
    expect(storage.output('a11y/evidence', '', 'ERP_A11Y_OUTPUT_DIR')).toBe('/tmp/a11y')
    expect(storage.reporterEnvironment).toEqual({})
  })
})
