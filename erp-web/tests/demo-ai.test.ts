import axios from 'axios'
import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../src/api/demo'
import { documents, inventoryRows } from '../src/demo/data'
import type { DemoAiAnswer } from '../src/demo/ai-data'
import type { AiDocument, AiEvidence, AiLibrary, AiSegment, AiWikiPage } from '../src/types/ai'

const client = axios.create({ adapter: demoAdapter, baseURL: '/ai/v1' })
async function read<T>(path: string, params: Record<string, unknown> = {}): Promise<T> {
  return (await client.get<{ data: T }>(path, { params })).data.data
}

describe('AI 只读样例', () => {
  it('百科可以搜索和读取正文，所有引用与相关条目均存在', async () => {
    const pages = await read<AiWikiPage[]>('/wiki')
    expect(pages).toHaveLength(3)
    expect(await read<AiWikiPage[]>('/wiki', { q: '预留' })).toHaveLength(1)
    expect(await read<AiWikiPage[]>('/wiki', { q: '不存在的条目' })).toEqual([])
    for (const page of pages) {
      expect(await read<AiWikiPage>('/wiki/' + page.slug)).toEqual(page)
      for (const id of page.sourceIds) {
        const evidence = await read<AiEvidence>('/citations/' + id)
        expect(evidence.wikiSlug).toBe(page.slug)
        expect(evidence.content.length).toBeGreaterThan(20)
      }
      for (const [, slug] of page.content.matchAll(/\[\[([a-z0-9-]+)\]\]/g)) expect(pages.some((item) => item.slug === slug)).toBe(true)
    }
  })

  it('知识库分组与分段对应，返回副本不能改动后续资料', async () => {
    const libraries = await read<AiLibrary[]>('/admin/libraries')
    expect(libraries).toHaveLength(2)
    for (const library of libraries) {
      const docs = await read<AiDocument[]>('/admin/documents', { libraryId: library.id })
      expect(docs.length).toBeGreaterThan(0)
      for (const doc of docs) {
        expect(doc.libraryId).toBe(library.id)
        expect(await read<AiDocument>('/admin/documents/' + doc.id)).toEqual(doc)
        const segments = await read<AiSegment[]>('/admin/documents/' + doc.id + '/segments')
        expect(segments.length).toBeGreaterThan(0)
        for (const segment of segments) {
          expect(segment.documentId).toBe(doc.id)
          expect(segment.charCount).toBe(segment.content.length)
          expect((await read<AiEvidence>('/citations/' + segment.id)).content).toBe(segment.content)
        }
        segments[0]!.content = '已修改'
        expect((await read<AiSegment[]>('/admin/documents/' + doc.id + '/segments'))[0]!.content).not.toBe('已修改')
      }
    }
    expect(await read<AiDocument[]>('/admin/documents', { libraryId: '不存在' })).toEqual([])
  })

  it('回答筛选生效，查数样例与已有订单和库存一致', async () => {
    expect(await read<DemoAiAnswer[]>('/demo/answers')).toHaveLength(4)
    expect(await read<DemoAiAnswer[]>('/demo/answers', { category: '权限边界' })).toHaveLength(1)
    expect(await read<DemoAiAnswer[]>('/demo/answers', { q: '不存在的问题' })).toEqual([])
    const order = documents.find((row) => row.id === '10112')!
    const answer = await read<DemoAiAnswer>('/demo/answers/95001')
    expect(answer.content).toContain(order.totalAmount)
    expect(answer.content).toContain(order.allocatedAmount)
    expect(answer.content).toContain('756.00')
    for (const tool of answer.tools) for (const row of tool.rows) {
      const doc = documents.find((item) => '/' + item.kind + '/' + item.id === row.detailUrl)!
      expect(doc).toBeDefined()
      expect(row.totalAmount).toBe(doc.totalAmount)
    }
    const stock = inventoryRows().find((row) => row.skuId === '403')!
    const inventory = await read<DemoAiAnswer>('/demo/answers/95002')
    expect(inventory.tools[0]!.rows[0]!.availableQty).toBe(stock.availableQty)
    for (const answer of await read<DemoAiAnswer[]>('/demo/answers')) for (const source of answer.sourceIds) expect((await read<AiEvidence>('/citations/' + source)).segmentId).toBe(source)
  })

  it('拒绝模型调用、写入、未知 AI 接口、外部地址和业务接口混用', async () => {
    for (const path of ['/conversations', '/admin/libraries', '/admin/documents', '/admin/libraries/search']) {
      for (const method of ['post', 'put', 'patch', 'delete']) await expect(client.request({ url: path, method, data: {} })).rejects.toMatchObject({ response: { status: 403 } })
    }
    for (const path of ['/wiki/unknown', '/admin/documents/999', '/citations/999', '/demo/answers/999', '/admin/settings', '/auth/me']) await expect(client.get(path)).rejects.toMatchObject({ response: { status: 404 } })
    await expect(client.get('/wiki', { baseURL: 'https://example.com' })).rejects.toMatchObject({ response: { status: 403 } })
    await expect(client.get('/wiki', { baseURL: '/api/v1' })).rejects.toMatchObject({ response: { status: 404 } })
  })
})
