<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { records } from '../api/admin'
import { browseDocuments } from '../api/document-browser'
import { useSessionStore } from '../stores/session'

interface MenuEntry {
  path: string
  title: string
  module: string
}
interface SearchResult {
  key: string
  label: string
  group: string
  to: RouteLocationRaw
}

const props = defineProps<{ menu: MenuEntry[] }>()
const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ closed: [] }>()
const session = useSessionStore()
const input = ref<{ focus: () => void }>()
const keyword = ref('')
const businessResults = ref<SearchResult[]>([])
const busy = ref(false)
const partial = ref(false)
const term = computed(() => keyword.value.trim())
const menuResults = computed<SearchResult[]>(() => props.menu
  .filter((item) => (item.module + item.title).toLowerCase().includes(term.value.toLowerCase()))
  .slice(0, 10)
  .map((item) => ({ key: item.path, label: item.title, group: item.module, to: '/' + item.path })))
const orderSources = ['sales-orders', 'purchase-orders', 'shipments', 'goods-receipts']
const lookupSources = ['partners', 'skus', 'products']

async function findDocuments(source: MenuEntry, query: string, signal: AbortSignal): Promise<SearchResult[]> {
  const { items } = await browseDocuments(source.path, { documentNo: query, limit: 4, withTotal: false }, signal)
  return items.filter((row) => row.documentNo).map((row) => ({
    key: source.path + '/' + row.id,
    label: row.documentNo!,
    group: source.title,
    to: '/' + source.path + '/' + row.id,
  }))
}

async function findRecords(source: MenuEntry, query: string, signal: AbortSignal): Promise<SearchResult[]> {
  const { items } = await records(source.path, false, {
    keyword: query, limit: 4, role: source.path === 'partners' ? 'customer' : undefined,
  }, undefined, signal)
  return items.slice(0, 4).map((row) => ({
    key: source.path + '/' + row.id,
    label: [row.code, row.name].filter(Boolean).join(' · ') || row.id,
    group: source.title,
    to: { path: '/' + source.path, query: { keyword: row.code || row.name || query } },
  }))
}

watch([term, open, () => session.companyId, () => props.menu], ([query, visible, company], _, onCleanup) => {
  businessResults.value = []
  partial.value = busy.value = false
  if (!visible || !company || query.length < 2) return
  const abort = new AbortController()
  const sources = props.menu.filter((item) => orderSources.includes(item.path) || lookupSources.includes(item.path))
  if (!sources.length) return
  busy.value = true
  const idle = setTimeout(async () => {
    const results = await Promise.allSettled(sources.map((source) =>
      orderSources.includes(source.path)
        ? findDocuments(source, query, abort.signal)
        : findRecords(source, query, abort.signal),
    ))
    if (abort.signal.aborted) return
    businessResults.value = results.flatMap((result) => result.status === 'fulfilled' ? result.value : [])
    partial.value = results.some((result) => result.status === 'rejected')
    busy.value = false
  }, 250)
  onCleanup(() => { clearTimeout(idle); abort.abort() })
}, { flush: 'sync' })
</script>

<template>
  <el-dialog
    v-model="open"
    title="全局搜索"
    width="min(480px, calc(100vw - 32px))"
    append-to-body
    @opened="input?.focus()"
    @closed="keyword = ''; emit('closed')"
  >
    <el-input
      ref="input"
      v-model="keyword"
      aria-label="菜单、单号、客户或商品"
      placeholder="搜索菜单、单号、客户、商品"
      :maxlength="100"
    />
    <ul class="shell-search-results">
      <li v-for="item in [...menuResults, ...businessResults]" :key="item.key">
        <router-link :to="item.to" @click="open = false">{{ item.label }}<small>{{ item.group }}</small></router-link>
      </li>
    </ul>
    <p v-if="busy" role="status">正在查询…</p>
    <p v-else-if="partial" role="status">部分业务数据暂时无法查询</p>
    <p v-else-if="!menuResults.length && !businessResults.length" role="status">没有匹配的结果</p>
  </el-dialog>
</template>
