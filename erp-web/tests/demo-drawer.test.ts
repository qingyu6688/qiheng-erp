import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { createDemoRouter } from '../src/demo/router'
import { closeDocumentPreview, previewRoute, returnDocumentPreview } from '../src/demo/document-preview'
import { useSessionStore } from '../src/stores/session'
import { demoSession } from '../src/demo/data'

beforeEach(() => {
  setActivePinia(createPinia())
  useSessionStore().acceptLogin(structuredClone(demoSession))
  closeDocumentPreview()
})

describe('演示单据右侧抽屉', () => {
  it.each(['/mfg/work-orders/74001', '/gl/vouchers/80001'])('新增详情 %s 保留列表筛选并支持直接访问', async (path) => {
    const router = createDemoRouter(createMemoryHistory())
    const list = path.slice(0, path.lastIndexOf('/'))
    await router.push(list + '?status=posted')
    const original = router.currentRoute.value.fullPath
    await router.push(path)
    expect(previewRoute.value?.path).toBe(path)
    expect(router.currentRoute.value.fullPath).toBe(original)
    closeDocumentPreview()
    const directRouter = createDemoRouter(createMemoryHistory())
    await directRouter.push(path)
    expect(directRouter.currentRoute.value.path).toBe(list)
    expect(previewRoute.value?.path).toBe(path)
  })
  it('查看和关联单据不改变列表路由及筛选，关闭后仍停留在原列表', async () => {
    const router = createDemoRouter(createMemoryHistory())
    await router.push('/sales-orders?keyword=星桥&status=approved')
    const original = router.currentRoute.value.fullPath
    await router.push('/sales-orders/10112')
    expect(previewRoute.value?.params.id).toBe('10112')
    expect(router.currentRoute.value.fullPath).toBe(original)
    await router.push('/receipts/40112')
    expect(previewRoute.value?.params.id).toBe('40112')
    expect(router.currentRoute.value.fullPath).toBe(original)
    returnDocumentPreview()
    expect(previewRoute.value?.params.id).toBe('10112')
    closeDocumentPreview()
    expect(previewRoute.value).toBeUndefined()
    expect(router.currentRoute.value.fullPath).toBe(original)
  })

  it('直接访问单据链接时打开列表上的详情抽屉', async () => {
    const router = createDemoRouter(createMemoryHistory())
    await router.push('/purchase-orders/20110')
    expect(router.currentRoute.value.path).toBe('/purchase-orders')
    expect(previewRoute.value?.params.id).toBe('20110')
  })
})
