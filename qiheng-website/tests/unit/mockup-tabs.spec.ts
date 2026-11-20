import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Script } from 'node:vm'
import { afterEach, describe, expect, it } from 'vitest'

const shell = readFileSync(resolve('design/mockups/assets/shell.js'), 'utf8')

function renderTabs(spec: string): NodeListOf<HTMLAnchorElement> {
  const page = document.createElement('main')
  page.id = 'page'
  document.body.replaceChildren(page)
  document.body.setAttribute('data-tabs', spec)
  new Script(shell).runInNewContext({
    document,
    window: { matchMedia: () => ({ matches: false, addEventListener: () => undefined }) },
  })
  return document.querySelectorAll<HTMLAnchorElement>('.tabsbar a')
}

afterEach(() => {
  document.body.replaceChildren()
  document.body.removeAttribute('data-tabs')
})

describe('设计稿页签', () => {
  it('标题按文本显示，并拒绝脚本地址和外部地址', () => {
    const tabs = renderTabs('<img src=x onerror=alert(1)>|javascript:alert(1)|current;外链|https://example.com|')
    expect(tabs[0]?.textContent).toBe('<img src=x onerror=alert(1)>')
    expect(document.querySelector('.tabsbar img')).toBeNull()
    expect([...tabs].map((tab) => tab.getAttribute('href'))).toEqual(['#', '#'])
  })

  it('正常页面链接、选中状态和未保存标记保持可用', () => {
    const tabs = renderTabs('工作台|01-workbench.html|current;销售订单|02-document-list.html|unsaved')
    expect(tabs[0]?.getAttribute('href')).toBe('01-workbench.html')
    expect(tabs[0]?.getAttribute('aria-current')).toBe('page')
    expect(tabs[1]?.querySelector('.unsaved')?.textContent).toBe('销售订单')
    expect(tabs[1]?.querySelector('[aria-label="关闭"]')).not.toBeNull()
  })
})
