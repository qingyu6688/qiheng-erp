import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import * as api from '../src/api/admin'
import ProductCatalogPage from '../src/pages/ProductCatalogPage.vue'
import ResourcePage from '../src/pages/ResourcePage.vue'
import RecordEditor from '../src/components/RecordEditor.vue'
import { useSessionStore } from '../src/stores/session'
import type { DataRow } from '../src/types/admin'

vi.mock('../src/api/admin')
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  onBeforeRouteLeave: vi.fn(),
  onBeforeRouteUpdate: vi.fn(),
}))
const categories: DataRow[] = [
  {
    id: '10',
    name: '装配件',
    code: 'ASSEMBLY',
    status: 'active',
    children: [{ id: '11', parentId: '10', name: '精密组件', code: 'PRECISION', status: 'active' }],
  },
  { id: '20', name: '包装耗材', code: 'PACKAGING', status: 'active' },
]
const product: DataRow = { id: '30', name: '标准组件', code: 'P-01', categoryId: '11', version: 2 }
const stubs = {
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElButton: {
    props: ['disabled', 'nativeType'],
    template: '<button :disabled="disabled" :type="nativeType || \'button\'"><slot /></button>',
  },
  ElTable: {
    props: ['data'],
    template: '<div data-testid="product-table">{{ JSON.stringify(data) }}</div>',
  },
  ElTableColumn: true,
  ElSelect: true,
  ElOption: true,
  ElTag: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElDialog: true,
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  RecordEditor: {
    name: 'RecordEditor',
    props: ['spec', 'row'],
    emits: ['dirty', 'saved'],
    template: '<div data-testid="product-editor">商品维护</div>',
  },
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  const session = useSessionStore()
  session.user = {
    id: '1',
    username: 'admin',
    displayName: '管理员',
    mustChangePassword: false,
    permissions: [
      'product:view',
      'product:create',
      'product:update',
      'product_category:view',
      'sku:view',
    ],
    companies: [],
  }
  session.companyId = '1'
  vi.mocked(api.records).mockImplementation(async (path, _company, params = {}) =>
    path === 'product-categories'
      ? { items: structuredClone(categories), nextCursor: null }
      : {
          items: [{ ...product, name: String(params.categoryId ?? '全部商品') }],
          nextCursor: params.cursor ? null : '30',
        },
  )
  vi.mocked(api.record).mockResolvedValue(product)
})
function page() {
  return mount(ProductCatalogPage, { global: { stubs } })
}
async function select(wrapper: ReturnType<typeof page>, id: string): Promise<void> {
  await wrapper.get(`.el-tree-node[data-key="${id}"] > .el-tree-node__content`).trigger('click')
  await flushPromises()
}
function productCalls() {
  return vi.mocked(api.records).mock.calls.filter(([path]) => path === 'products')
}

describe('商品分类主从资料', () => {
  it('分类与含下级交给服务端，变更条件重置游标，全部商品不带分类参数', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      expect(wrapper.get('[role=separator]').attributes('aria-valuenow')).toBe('280')
      await select(wrapper, '10')
      expect(productCalls().at(-1)?.[2]).toMatchObject({
        categoryId: '10',
        includeChildren: false,
        cursor: undefined,
      })
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '下一页')!
        .trigger('click')
      await flushPromises()
      expect(productCalls().at(-1)?.[2]).toMatchObject({ cursor: '30' })
      await wrapper.get('.category-descendants input').setValue(true)
      await flushPromises()
      expect(productCalls().at(-1)?.[2]).toMatchObject({
        categoryId: '10',
        includeChildren: true,
        cursor: undefined,
      })
      await wrapper.get('.category-all').trigger('click')
      await flushPromises()
      expect(productCalls().at(-1)?.[2]).not.toHaveProperty('categoryId')
      expect(wrapper.get('.category-descendants input').attributes('disabled')).toBeDefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('搜索保留祖先，分栏支持键盘，新建商品沿用所选分类及原编辑器', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      await wrapper.get('#product-category-search').setValue('精密')
      await flushPromises()
      expect(wrapper.get('.el-tree-node[data-key="10"]').isVisible()).toBe(true)
      expect(wrapper.get('.el-tree-node[data-key="11"]').isVisible()).toBe(true)
      expect(wrapper.get('.el-tree-node[data-key="20"]').isVisible()).toBe(false)
      await wrapper.get('[role=separator]').trigger('keydown', { key: 'ArrowRight' })
      expect(wrapper.get('[role=separator]').attributes('aria-valuenow')).toBe('296')
      await select(wrapper, '11')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '新建商品')!
        .trigger('click')
      await flushPromises()
      const editor = wrapper.findComponent(RecordEditor)
      expect(editor.props('row')).toBeUndefined()
      expect(
        editor.props('spec').fields.find((field: { key: string }) => field.key === 'categoryId')
          ?.initial,
      ).toBe('11')
      expect(wrapper.find('a[href="/product-categories"]').exists()).toBe(true)
      expect(wrapper.find('a[href="/skus"]').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('未保存商品取消分类和含下级切换，原表单保持不变', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      await select(wrapper, '10')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '新建商品')!
        .trigger('click')
      await flushPromises()
      wrapper.findComponent(RecordEditor).vm.$emit('dirty', true)
      vi.spyOn(window, 'confirm').mockReturnValue(false)
      const count = productCalls().length
      await select(wrapper, '11')
      await wrapper.get('.category-descendants input').setValue(true)
      await flushPromises()
      expect(productCalls()).toHaveLength(count)
      expect(wrapper.get('#product-category-results').text()).toBe('装配件')
      expect((wrapper.get('.category-descendants input').element as HTMLInputElement).checked).toBe(
        false,
      )
      expect(wrapper.findComponent(RecordEditor).exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('分类权限撤销回退原商品页，不再读取分类；商品权限撤销清空主从内容', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      await select(wrapper, '11')
      const session = useSessionStore()
      session.user!.permissions = ['product:view']
      await flushPromises()
      expect(wrapper.find('.category-panel').exists()).toBe(false)
      expect(wrapper.findComponent(ResourcePage).exists()).toBe(true)
      expect(productCalls().at(-1)?.[2]).not.toHaveProperty('categoryId')
      const categoriesRead = vi
        .mocked(api.records)
        .mock.calls.filter(([path]) => path === 'product-categories').length
      session.companyId = '2'
      await flushPromises()
      expect(
        vi.mocked(api.records).mock.calls.filter(([path]) => path === 'product-categories'),
      ).toHaveLength(categoriesRead)
      session.user!.permissions = []
      await flushPromises()
      expect(wrapper.findComponent(ResourcePage).exists()).toBe(false)
      expect(wrapper.get('[role=alert]').text()).toContain('没有查看商品资料的权限')
    } finally {
      wrapper.unmount()
    }
  })

  it('切换公司重置分类与含下级，按用户和公司保存分栏宽度', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      await select(wrapper, '10')
      await wrapper.get('.category-descendants input').setValue(true)
      await wrapper.get('[role=separator]').trigger('keydown', { key: 'ArrowRight' })
      expect(localStorage.getItem('erp.split.product-categories.1.1')).toBe('296')
      useSessionStore().companyId = '2'
      await flushPromises()
      expect(wrapper.get('[role=separator]').attributes('aria-valuenow')).toBe('280')
      expect(wrapper.get('#product-category-results').text()).toBe('全部商品')
      expect((wrapper.get('.category-descendants input').element as HTMLInputElement).checked).toBe(
        false,
      )
      expect(productCalls().at(-1)?.[2]).not.toHaveProperty('categoryId')
    } finally {
      wrapper.unmount()
    }
  })

  it('切换分类后忽略旧商品详情响应，保留当前分类结果', async () => {
    const wrapper = page()
    try {
      await flushPromises()
      await select(wrapper, '10')
      let resolveDetail: (value: DataRow) => void = () => undefined
      vi.mocked(api.record).mockReturnValueOnce(
        new Promise((resolve) => {
          resolveDetail = resolve
        }),
      )
      const resource = wrapper.findComponent(ResourcePage).vm as unknown as {
        edit: (row: DataRow) => Promise<void>
      }
      const pendingEdit = resource.edit(product)
      await select(wrapper, '11')
      resolveDetail(product)
      await pendingEdit
      await flushPromises()
      expect(wrapper.findComponent(RecordEditor).exists()).toBe(false)
      expect(wrapper.get('#product-category-results').text()).toBe('精密组件')
    } finally {
      wrapper.unmount()
    }
  })
})
