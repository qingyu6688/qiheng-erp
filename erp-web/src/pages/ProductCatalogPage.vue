<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { ElTree } from 'element-plus/es/components/tree/index.mjs'
import { records } from '../api/admin'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { DataRow } from '../types/admin'
import { flattenRows } from '../utils/admin'
import SplitPane from '../components/SplitPane.vue'
import ResourcePage from './ResourcePage.vue'

const session = useSessionStore()
const canViewProducts = computed(() => session.can('product:view'))
const canViewCategories = computed(() => session.can('product_category:view'))
const categories = ref<DataRow[]>([])
const categoryQuery = ref('')
const selectedId = ref('')
const includeChildren = ref(false)
const loading = ref(false)
const error = ref('')
const tree = ref<InstanceType<typeof ElTree>>()
const products = ref<InstanceType<typeof ResourcePage>>()
const selected = computed(() =>
  flattenRows(categories.value).find((row) => row.id === selectedId.value),
)
const selectedLabel = computed(() =>
  selectedId.value ? String(selected.value?.name ?? '所选分类') : '全部商品',
)
const layoutKey = computed(() =>
  session.user?.id
    ? `erp.split.product-categories.${session.user.id}.${session.companyId || 'shared'}`
    : undefined,
)
let revision = 0
let pending: AbortController | undefined

async function loadCategories(): Promise<void> {
  const current = ++revision
  pending?.abort()
  pending = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const data = await records('product-categories', false, {}, undefined, pending.signal)
    if (current !== revision) return
    categories.value = data.items
    await nextTick()
    if (current === revision) tree.value?.filter(categoryQuery.value.trim())
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}

function filterCategory(query: string, row: Record<string, unknown>): boolean {
  const normalized = query.toLocaleLowerCase()
  return [row.name, row.code].some((value) =>
    String(value ?? '')
      .toLocaleLowerCase()
      .includes(normalized),
  )
}

function selectCategory(row?: DataRow): void {
  const next = row?.id ?? ''
  if (next === selectedId.value) return
  if (products.value && !products.value.confirmDiscard()) {
    tree.value?.setCurrentKey(selectedId.value || undefined)
    return
  }
  selectedId.value = next
  tree.value?.setCurrentKey(next || undefined)
}

function toggleChildren(event: Event): void {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  if (products.value && !products.value.confirmDiscard()) {
    input.checked = includeChildren.value
    return
  }
  includeChildren.value = input.checked
}

watch(categoryQuery, (value) => tree.value?.filter(value.trim()))
watch(
  () => [session.user?.id, session.companyId, canViewProducts.value, canViewCategories.value],
  () => {
    ++revision
    pending?.abort()
    categories.value = []
    selectedId.value = ''
    categoryQuery.value = ''
    includeChildren.value = false
    error.value = ''
    loading.value = false
    if (canViewProducts.value && canViewCategories.value) void loadCategories()
  },
  { immediate: true },
)
onScopeDispose(() => {
  ++revision
  pending?.abort()
})
</script>

<template>
  <template v-if="canViewProducts && canViewCategories">
    <div class="page-heading">
      <h1>商品资料</h1>
      <el-button v-if="session.can('product:create')" type="primary" @click="products?.edit()">
        新建商品
      </el-button>
    </div>
    <nav class="page-tabs" aria-label="商品资料分类">
      <router-link to="/products">商品目录</router-link>
      <router-link v-if="session.can('sku:view')" to="/skus">SKU 与换算条码</router-link>
    </nav>
    <SplitPane :storage-key="layoutKey" label="调整商品分类宽度">
      <template #primary>
        <section
          class="panel category-panel"
          aria-labelledby="product-category-title"
          :aria-busy="loading"
        >
          <header class="category-heading">
            <h2 id="product-category-title">商品分类</h2>
            <router-link to="/product-categories" class="text-link">管理分类</router-link>
          </header>
          <div class="category-search">
            <label for="product-category-search">搜索分类</label>
            <el-input
              id="product-category-search"
              v-model="categoryQuery"
              maxlength="100"
              clearable
              placeholder="分类名称或编码"
            />
          </div>
          <div class="category-toolbar">
            <button
              class="category-all"
              type="button"
              :aria-pressed="!selectedId"
              @click="selectCategory()"
            >
              全部商品
            </button>
            <el-button :disabled="loading" @click="loadCategories">刷新分类</el-button>
          </div>
          <p v-if="error" class="category-error" role="alert">{{ error }}</p>
          <p v-else-if="loading && !categories.length" class="muted" role="status">正在读取分类…</p>
          <p v-else-if="!categories.length" class="muted" role="status">
            尚未设置商品分类，可到“管理分类”新增。
          </p>
          <el-tree
            v-if="categories.length"
            ref="tree"
            :data="categories"
            :props="{ label: 'name', disabled: () => false }"
            node-key="id"
            :current-node-key="selectedId"
            :filter-node-method="filterCategory"
            default-expand-all
            highlight-current
            :expand-on-click-node="false"
            @node-click="selectCategory"
          >
            <template #default="{ data }">
              <span
                >{{ data.name
                }}<span v-if="data.status === 'disabled'" class="muted">（停用）</span></span
              >
            </template>
          </el-tree>
          <label class="category-descendants">
            <input
              type="checkbox"
              :checked="includeChildren"
              :disabled="!selectedId"
              @change="toggleChildren"
            />
            含下级分类
          </label>
        </section>
      </template>
      <template #secondary>
        <section class="product-results" aria-labelledby="product-category-results">
          <h2 id="product-category-results" class="category-context">
            {{ selectedLabel }}<span v-if="selectedId && includeChildren">（含下级）</span>
          </h2>
          <ResourcePage
            ref="products"
            resource="products"
            :category-id="selectedId || undefined"
            :include-children="includeChildren"
            embedded
          />
        </section>
      </template>
    </SplitPane>
  </template>
  <ResourcePage v-else-if="canViewProducts" resource="products" />
  <el-alert
    v-else
    title="当前账号没有查看商品资料的权限，请联系管理员。"
    type="error"
    :closable="false"
  />
</template>

<style scoped>
.category-panel {
  padding: 16px;
}
.category-heading,
.category-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.category-heading h2,
.category-context {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.category-search {
  display: grid;
  gap: 6px;
  margin-block: 16px 12px;
}
.category-search label {
  font-size: 12px;
  color: var(--erp-text-2);
}
.category-toolbar {
  margin-bottom: 12px;
}
.category-all {
  padding: 6px 8px;
  border: 1px solid var(--erp-border-2);
  border-radius: var(--erp-radius-sm);
  background: var(--erp-bg);
  color: var(--erp-text-1);
  font: inherit;
  cursor: pointer;
}
.category-all[aria-pressed='true'] {
  border-color: var(--erp-primary-6);
  background: var(--erp-primary-1);
  color: var(--erp-primary-7);
}
.category-descendants {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
}
.category-descendants input {
  accent-color: var(--erp-primary-6);
}
.category-context {
  padding: 0 0 12px;
}
.category-error {
  color: var(--erp-danger-text);
}
.product-results {
  min-width: 0;
}
</style>
