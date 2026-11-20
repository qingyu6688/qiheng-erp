<script setup lang="ts">
import { computed, inject, onMounted, onScopeDispose, ref } from 'vue'
import { ElDrawer } from 'element-plus'
import {
  explodeBill,
  manufacturingCommand,
  manufacturingDetail,
  manufacturingList,
} from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { manufacturingActions, manufacturingStates } from '../../types/manufacturing'
import type { Bom, BomPreview } from '../../types/manufacturing'
import { useWmsCommands } from '../wms/useWmsCommands'
import BomEditor from './BomEditor.vue'
import ReferenceSelect from './ReferenceSelect.vue'
import { catalogLabel, useManufacturingCatalog, useManufacturingList } from './useManufacturing'

const readOnlyPreview = inject('erp.readOnlyPreview', false)
const session = useSessionStore()
const keyword = ref('')
const status = ref('')
const detail = ref<Bom>()
const editor = ref<'new' | 'revision' | 'edit'>()
const quantity = ref('1')
const warehouse = ref('')
const date = ref('')
const preview = ref<BomPreview[]>()
const detailLoading = ref(false)
const previewLoading = ref(false)
const detailError = ref('')
let revision = 0
let previewRevision = 0
onScopeDispose(() => {
  revision++
  previewRevision++
})

const {
  items,
  cursor,
  loading,
  error: listError,
  load,
} = useManufacturingList((cursor) =>
  manufacturingList<Bom>('boms', {
    cursor,
    keyword: keyword.value.trim() || undefined,
    status: status.value || undefined,
  }),
)
const { busy, error: commandError, notice, run } = useWmsCommands()
const { skus, warehouses, loadCatalog } = useManufacturingCatalog()
const versions = ref<Bom[]>([])
const actions = computed(() => {
  const draft = detail.value
  if (!draft) return []
  const byState: Record<string, string[]> = {
    draft: ['submit'],
    pending: ['approve'],
    approved: ['disable'],
  }
  return (draft.allowedActions ?? byState[draft.status] ?? []).filter(
    (action) =>
      ['submit', 'approve', 'disable'].includes(action) && session.can('mfg_bom:' + action),
  )
})
const labelFor = (id: string): string => {
  const value = skus.value.find((row) => row.id === id)
  return value ? catalogLabel(value) : id
}

interface TreeItem {
  id: string
  label: string
  children: TreeItem[]
}
const tree = computed(() => {
  const root: TreeItem = {
    id: 'root',
    label: [detail.value?.skuCode, detail.value?.skuName].filter(Boolean).join(' · '),
    children: [],
  }
  for (const row of preview.value ?? []) {
    const parents = Array.isArray(row.path)
      ? row.path.filter((part) => part !== row.componentSkuId && part !== detail.value?.skuId)
      : []
    let current = root
    for (const parent of parents) {
      let node = current.children.find((item) => item.id === parent)
      if (!node) {
        node = { id: parent, label: labelFor(parent), children: [] }
        current.children.push(node)
      }
      current = node
    }
    const label = `${row.skuName ?? labelFor(row.componentSkuId)} · ${row.requiredQty}${row.phantom ? '（虚拟件）' : ''}`
    const existing = current.children.find((item) => item.id === row.componentSkuId)
    if (existing) existing.label = label
    else current.children.push({ id: row.componentSkuId, label, children: [] })
  }
  return root.children.length ? [root] : []
})

async function select(id: string): Promise<void> {
  const current = ++revision
  previewRevision++
  preview.value = undefined
  previewLoading.value = false
  detailLoading.value = true
  detailError.value = ''
  commandError.value = ''
  try {
    const bill = await manufacturingDetail<Bom>('boms', id)
    if (current !== revision) return
    detail.value = bill
    quantity.value = bill.baseQty
    if (!versions.value.some((row) => row.id === id)) {
      const data = await manufacturingList<Bom>('boms', { skuId: bill.skuId, limit: 100 })
      if (current === revision) versions.value = data.items
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    if (current === revision) detailLoading.value = false
  }
}

async function showPreview(): Promise<void> {
  if (!detail.value || !quantity.value.trim() || previewLoading.value || detailLoading.value) return
  const current = ++previewRevision
  previewLoading.value = true
  detailError.value = ''
  preview.value = undefined
  try {
    const result = await explodeBill(
      detail.value.id,
      quantity.value.trim(),
      date.value || undefined,
      warehouse.value || undefined,
    )
    if (current === previewRevision) preview.value = result.lines
  } catch (cause: unknown) {
    if (current === previewRevision && !isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    if (current === previewRevision) previewLoading.value = false
  }
}

async function change(action: string): Promise<void> {
  const current = detail.value
  if (!current || detailLoading.value) return
  const input = { version: current.version }
  const result = await run(
    action + ':' + current.id,
    input,
    (key) => manufacturingCommand<Bom>('boms', current.id, action, input, key),
    '物料清单已更新。',
  )
  if (result) await saved(result)
}

async function saved(result: Bom): Promise<void> {
  editor.value = undefined
  versions.value = []
  await Promise.all([load(), select(result.id)])
}

onMounted(() => {
  void load()
  void loadCatalog(['skus', 'warehouses'])
})
</script>

<template>
  <el-alert
    v-if="listError || detailError || commandError"
    :title="listError || detailError || commandError"
    type="error"
    :closable="false"
  />
  <p v-if="notice" role="status">{{ notice }}</p>
  <div :class="{ 'bom-columns': !readOnlyPreview }">
    <section class="panel mfg-panel" aria-label="BOM 父项列表">
      <div class="mfg-toolbar">
        <el-input
          v-model="keyword"
          aria-label="搜索物料清单"
          placeholder="父项编码、名称或版本"
          clearable
          @keydown.enter.prevent="load()"
        />
        <el-select
          v-model="status"
          placeholder="全部状态"
          aria-label="BOM 状态"
          clearable
          @change="load()"
        >
          <el-option
            v-for="key in ['draft', 'pending', 'approved', 'disabled']"
            :key="key"
            :label="manufacturingStates[key]"
            :value="key"
          />
        </el-select>
        <el-button @click="load()">查询</el-button>
        <el-button v-if="session.can('mfg_bom:create')" type="primary" @click="editor = 'new'"
          >新建 BOM</el-button
        >
      </div>
      <el-table v-loading="loading" :data="items" empty-text="暂无物料清单">
        <el-table-column label="父项" min-width="130">
          <template #default="{ row }"
            ><el-button link type="primary" @click="select(row.id)">{{
              row.skuCode ?? row.skuName ?? row.skuId
            }}</el-button></template
          >
        </el-table-column>
        <el-table-column prop="versionNo" label="版本" min-width="70" />
        <el-table-column label="状态" min-width="75"
          ><template #default="{ row }">{{
            manufacturingStates[row.status] ?? '—'
          }}</template></el-table-column
        >
      </el-table>
      <el-button v-if="cursor" class="mfg-list-pager" :loading="loading" @click="load(true)"
        >加载更多</el-button
      >
    </section>
    <component :is="readOnlyPreview ? ElDrawer : 'section'" :model-value="Boolean(detail) || detailLoading" title="BOM · 只读详情" size="min(1000px, 94vw)" @update:model-value="(open: boolean) => { if (!open) detail = undefined }" v-loading="detailLoading" class="panel mfg-panel" aria-label="BOM 详情">
      <el-empty v-if="!detail" description="选择父项查看物料及版本" />
      <template v-else>
        <h2>
          {{ detail.skuCode ? `${detail.skuCode} · ` : ''
          }}{{ detail.skuName ?? labelFor(detail.skuId) }}
        </h2>
        <div class="mfg-toolbar">
          <el-select
            :model-value="detail.id"
            aria-label="切换 BOM 版本"
            :disabled="busy"
            @update:model-value="select"
          >
            <el-option
              v-for="version in versions.length ? versions : [detail]"
              :key="version.id"
              :value="version.id"
              :label="version.versionNo + (version.isDefault ? '（默认）' : '')"
            />
          </el-select>
          <el-button
            v-if="detail.status === 'draft' && session.can('mfg_bom:update')"
            :disabled="busy || detailLoading"
            @click="editor = 'edit'"
            >编辑当前版本</el-button
          >
          <el-button
            v-if="session.can('mfg_bom:create')"
            :disabled="busy || detailLoading"
            @click="editor = 'revision'"
            >新建版本</el-button
          >
          <el-button
            v-for="action in actions"
            :key="action"
            :disabled="detailLoading"
            :loading="busy"
            @click="change(action)"
            >{{ manufacturingActions[action] }}</el-button
          >
        </div>
        <p>
          基准数量 {{ detail.baseQty }} · {{ manufacturingStates[detail.status] }} ·
          {{ detail.validFrom }} 至 {{ detail.validTo || '长期有效' }}
        </p>
        <el-table :data="detail.lines" empty-text="暂无物料明细">
          <el-table-column label="子件" min-width="155"
            ><template #default="{ row }"
              >{{ row.componentSkuName ?? row.componentSkuCode ?? labelFor(row.componentSkuId)
              }}{{ row.phantom ? '（虚拟件）' : '' }}</template
            ></el-table-column
          >
          <el-table-column prop="qty" label="基准用量" min-width="88" />
          <el-table-column prop="scrapRate" label="损耗率 %" min-width="90" />
          <el-table-column label="替代料" min-width="160"
            ><template #default="{ row }">{{
              row.substitutes
                ?.map(
                  (item: import('../../types/manufacturing').SubstituteDraft) =>
                    `${item.skuName ?? item.skuCode ?? labelFor(item.substituteSkuId)} × ${item.ratio}`,
                )
                .join('、') || '—'
            }}</template></el-table-column
          >
        </el-table>
        <h3>用料展开</h3>
        <el-form class="mfg-toolbar" @submit.prevent="showPreview">
          <el-input
            v-model="quantity"
            inputmode="decimal"
            aria-label="展开数量"
            placeholder="计划数量"
          />
          <el-input v-model="date" type="date" aria-label="展开日期" />
          <ReferenceSelect
            v-if="warehouses.length"
            v-model="warehouse"
            :rows="warehouses"
            label="预览库存仓库"
            optional
          />
          <el-button native-type="submit" type="primary" :loading="previewLoading"
            >按数量展开</el-button
          >
        </el-form>
        <template v-if="preview">
          <el-tree :data="tree" default-expand-all aria-label="多层 BOM" />
          <el-table :data="preview" empty-text="该数量未产生领料明细">
            <el-table-column label="物料" min-width="130"
              ><template #default="{ row }">{{
                row.skuName ?? labelFor(row.componentSkuId)
              }}</template></el-table-column
            >
            <el-table-column prop="requiredQty" label="展开用量" min-width="105" />
            <el-table-column label="用途" min-width="105"
              ><template #default="{ row }">{{
                row.consumable ? '工单领用' : row.phantom ? '虚拟展开' : '下级参考'
              }}</template></el-table-column
            >
            <el-table-column label="当前可用" min-width="105"
              ><template #default="{ row }">{{
                row.availableQty ?? '—'
              }}</template></el-table-column
            >
            <el-table-column label="领料仓" min-width="110"
              ><template #default="{ row }">{{
                row.warehouseName ?? row.issueWarehouseId ?? '工单默认'
              }}</template></el-table-column
            >
          </el-table>
        </template>
      </template>
    </component>
  </div>
  <BomEditor
    v-if="editor"
    :edit="editor === 'edit'"
    :source="editor === 'new' ? undefined : detail"
    @close="editor = undefined"
    @saved="saved"
  />
</template>

<style scoped>
.bom-columns {
  display: grid;
  grid-template-columns: minmax(250px, 1fr) minmax(390px, 2fr);
  gap: 14px;
}
.bom-columns .mfg-panel + .mfg-panel {
  margin-top: 0;
}
@media (max-width: 1000px) {
  .bom-columns {
    grid-template-columns: 1fr;
  }
}
</style>
