import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const samples: Record<string, readonly [string, string]> = {
  workbench: ['销售订单待审核', '事项'],
  list: ['SO-C01-202611-000070', '单号'],
  detail: ['标准装配件', '商品 / 来源'],
  organization: ['auditor', '用户名'],
  'product-catalog': ['P-01', '编码'],
}

export async function verifyTableHeaders(page: Page, template: string): Promise<void> {
  const sample = samples[template]
  if (!sample) return
  const [value, heading] = sample
  const cell = page
    .locator('td')
    .filter({ hasText: new RegExp('^\\s*' + value + '\\s*$') })
    .first()
  await expect(cell).toBeVisible()
  const native = await cell.evaluate((element) => {
    const td = element as HTMLTableCellElement
    const table = td.closest('table')
    if (!table) throw new Error('数据单元格缺少所属表格')
    const columns = Array.from(table.tHead?.rows[0]?.cells ?? [])
    const rows = Array.from(table.tBodies).flatMap((body) => Array.from(body.rows))
    return {
      index: Array.from(document.querySelectorAll('table')).indexOf(table),
      columns: columns.map(
        (col) => col.textContent?.trim() || col.querySelector('input')?.getAttribute('aria-label'),
      ),
      columnIndex: td.cellIndex,
      explicitScopes: columns.every((col) => col.tagName === 'TH' && col.scope === 'col'),
      aligned: rows.length > 0 && rows.every((row) => row.cells.length === columns.length),
      rows: rows.length,
    }
  })

  const session = await page.context().newCDPSession(page)
  try {
    const { root } = await session.send('DOM.getDocument')
    const { nodeIds } = await session.send('DOM.querySelectorAll', {
      nodeId: root.nodeId,
      selector: 'table',
    })
    const { node } = await session.send('DOM.describeNode', { nodeId: nodeIds[native.index] })
    const { nodes } = await session.send('Accessibility.getFullAXTree')
    const table = nodes.find((item) => item.backendDOMNodeId === node.backendNodeId)
    const children = new Map(nodes.map((item) => [item.nodeId, item]))
    const pending = [...(table?.childIds ?? [])]
    const headings: string[] = []
    const values: string[] = []
    while (pending.length) {
      const item = children.get(pending.pop()!)
      if (!item || item.role?.value === 'table') continue
      if (item.role?.value === 'columnheader') headings.push(String(item.name?.value ?? ''))
      if (item.role?.value === 'cell') values.push(String(item.name?.value ?? ''))
      pending.push(...(item.childIds ?? []))
    }
    const evidence = {
      native,
      tableRole: table?.role?.value,
      headings,
      value,
      heading,
      valueInTree: values.includes(value),
    }
    await test.info().attach('原生表格与浏览器辅助功能树', {
      body: JSON.stringify(evidence),
      contentType: 'application/json',
    })
    expect(native.columns[native.columnIndex]).toBe(heading)
    expect(native.explicitScopes && native.aligned).toBe(true)
    expect(evidence.tableRole).toBe('table')
    expect(headings).toHaveLength(native.columns.length)
    expect(headings).toContain(heading)
    expect(evidence.valueInTree).toBe(true)
  } finally {
    await session.detach()
  }
}
