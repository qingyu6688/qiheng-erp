<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { House, Plus, Search, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { warehouses, warehouseDetail } from '../api/masterdata'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { Warehouse, WarehouseCommand, WarehouseStatus } from '../types/warehouse'
import type { PageResult } from '../types/api'
import WarehouseDialog from '../components/WarehouseDialog.vue'

const session = useSessionStore()
const keyword = ref('')
const status = ref<WarehouseStatus | ''>('')
const filters = ref({ keyword: '', status: '' as WarehouseStatus | '' })
const pageSize = 20
const cursors = ref<string[]>([''])
const page = ref<PageResult<Warehouse>>({ items: [], nextCursor: null })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const mode = ref<'create' | 'update' | 'detail' | WarehouseCommand | null>(null)
const selected = ref<Warehouse>()
const detailBusy = ref(false)
const currentPage = computed(() => cursors.value.length)
let sequence = 0

async function load(): Promise<void> {
  const generation = ++sequence
  busy.value = true
  error.value = ''
  page.value = { items: [], nextCursor: null }
  try {
    const result = await warehouses({
      keyword: filters.value.keyword || undefined,
      status: filters.value.status || undefined,
      limit: pageSize,
      withTotal: true,
      cursor: cursors.value.at(-1) || undefined,
    })
    if (generation === sequence) page.value = result
  } catch (cause: unknown) {
    if (!isCancelled(cause) && generation === sequence) error.value = messageOf(cause)
  } finally {
    if (generation === sequence) busy.value = false
  }
}
async function search(): Promise<void> {
  filters.value = { keyword: keyword.value.trim(), status: status.value }
  cursors.value = ['']
  notice.value = ''
  await load()
}
function paginate(next: boolean): void {
  if (busy.value) return
  if (next && page.value.nextCursor) cursors.value.push(page.value.nextCursor)
  else if (!next && currentPage.value > 1) cursors.value.pop()
  else return
  void load()
}
async function openRow(
  row: Warehouse,
  next: 'detail' | 'update' | WarehouseCommand,
): Promise<void> {
  if (detailBusy.value) return
  detailBusy.value = true
  error.value = notice.value = ''
  try {
    const fresh = await warehouseDetail(row.id)
    if (next !== 'detail' && !fresh.allowedActions.includes(next)) {
      await load()
      notice.value = '仓库状态或权限已变更，列表已刷新。'
      return
    }
    selected.value = fresh
    mode.value = next
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    detailBusy.value = false
  }
}
function create(): void {
  selected.value = undefined
  mode.value = 'create'
}
async function saved(): Promise<void> {
  const wasNew = mode.value === 'create'
  mode.value = null
  notice.value = '仓库资料已更新。'
  if (wasNew) cursors.value = ['']
  await load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <h1>仓库管理</h1>
    <el-button
      v-if="session.can('warehouse:create') && session.company?.allWarehouses"
      type="primary"
      :icon="Plus"
      @click="create"
      >新建仓库</el-button
    >
  </div>
  <section class="panel table-panel" aria-label="仓库列表" :aria-busy="busy">
    <form class="filters" @submit.prevent="search">
      <div class="filter-field">
        <label for="search">仓库编码或名称</label>
        <el-input
          id="search"
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="输入编码或名称"
          clearable
          maxlength="100"
        />
      </div>
      <div class="filter-field small">
        <label for="status">状态</label>
        <el-select id="status" v-model="status">
          <el-option label="全部状态" value="" /><el-option label="启用" value="active" /><el-option
            label="停用"
            value="disabled"
          />
        </el-select>
      </div>
      <el-button native-type="submit">查询</el-button>
    </form>
    <el-alert
      v-if="notice"
      class="list-notice"
      type="success"
      :title="notice"
      show-icon
      @close="notice = ''"
    />
    <div v-if="error" class="load-error" role="alert">
      <span>{{ error }}</span
      ><el-button text type="primary" @click="load">重新加载</el-button>
    </div>
    <div class="table-scroll">
      <el-table :data="page.items" row-key="id" class="warehouse-table">
        <el-table-column prop="code" label="仓库编码" min-width="145" />
        <el-table-column prop="name" label="仓库名称" min-width="150">
          <template #default="{ row }"
            ><button
              class="text-link record-link"
              :disabled="detailBusy"
              @click="openRow(row, 'detail')"
            >
              {{ row.name }}
            </button></template
          >
        </el-table-column>
        <el-table-column label="状态" width="95"
          ><template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'active' ? 'success' : 'info'"
              effect="light"
              >{{ row.status === 'active' ? '启用' : '停用' }}</el-tag
            >
          </template></el-table-column
        >
        <el-table-column prop="address" label="地址" min-width="155"
          ><template #default="{ row }">{{ row.address || '—' }}</template></el-table-column
        >
        <el-table-column label="操作" width="145"
          ><template #default="{ row }">
            <div class="row-actions">
              <button
                v-if="row.allowedActions.includes('update')"
                class="text-link"
                :disabled="detailBusy"
                @click="openRow(row, 'update')"
              >
                编辑
              </button>
              <button
                v-if="row.allowedActions.includes('disable')"
                class="text-link"
                :disabled="detailBusy"
                @click="openRow(row, 'disable')"
              >
                停用
              </button>
              <button
                v-if="row.allowedActions.includes('enable')"
                class="text-link"
                :disabled="detailBusy"
                @click="openRow(row, 'enable')"
              >
                启用
              </button>
              <span
                v-if="
                  !row.allowedActions.some((a: string) =>
                    ['update', 'disable', 'enable'].includes(a),
                  )
                "
                >—</span
              >
            </div>
          </template></el-table-column
        >
        <template #empty>
          <div class="empty-state" role="status">
            <House class="empty-icon" />
            <strong>{{ busy ? '正在加载仓库…' : error ? '暂时无法显示仓库' : '暂无仓库' }}</strong>
            <span v-if="!busy && !error">{{
              filters.keyword || filters.status
                ? '请调整查询条件后重试。'
                : '当前公司暂无可查看的仓库。'
            }}</span>
          </div>
        </template>
      </el-table>
    </div>
    <footer class="pagination">
      <span aria-live="polite">{{
        page.total === undefined
          ? '本页 ' + page.items.length + ' 条'
          : '共 ' + page.total + (page.totalExact === false ? '+' : '') + ' 条'
      }}</span>
      <div>
        <el-button :icon="ArrowLeft" :disabled="currentPage === 1 || busy" @click="paginate(false)"
          >上一页</el-button
        >
        <el-button :disabled="!page.nextCursor || busy" @click="paginate(true)"
          >下一页<ArrowRight class="icon small-icon"
        /></el-button>
      </div>
    </footer>
  </section>
  <WarehouseDialog
    v-if="mode"
    :mode="mode"
    :warehouse="selected"
    @close="mode = null"
    @saved="saved"
  />
</template>
