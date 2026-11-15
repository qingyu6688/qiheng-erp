<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { stockDocument } from '../api/stock-operations'
import { isCancelled, messageOf } from '../api/http'
import {
  reversalTypes,
  stockActionLabels,
  stockSpecs,
  stockStatusLabels,
} from '../types/stock-operations'
import type { StockDocument, StockKind } from '../types/stock-operations'
import { useSessionStore } from '../stores/session'
import StockDocumentEditor from '../components/stock-operations/StockDocumentEditor.vue'
import StockCommandDialog from '../components/stock-operations/StockCommandDialog.vue'
import DocumentFilesPanel from '../components/DocumentFilesPanel.vue'
import { documentType } from '../types/documents'
import DocumentBrowserPanel from '../components/DocumentBrowserPanel.vue'
import { documentStateTag } from '../types/document-ui'
import { formatDateTime, formatMoney, formatQuantity } from '../utils/format'

const props = defineProps<{ kind: StockKind }>(),
  route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const spec = computed(() => stockSpecs.find((item) => item.path === props.kind)!),
  detailId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const value = ref<StockDocument>(),
  loading = ref(false),
  error = ref('')
const editing = ref(false),
  action = ref('')
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  loading.value = true
  error.value = ''
  try {
    if (detailId.value) {
      const result = await stockDocument(props.kind, detailId.value)
      if (current === revision) value.value = result
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
function choose(name: string): void {
  if (name === 'update') editing.value = true
  else action.value = name
}
async function saved(document: StockDocument): Promise<void> {
  editing.value = false
  if (detailId.value === document.id) await load()
  else await router.push('/' + props.kind + '/' + document.id)
}
async function closeEditor(): Promise<void> {
  editing.value = false
  await load()
}
async function commandSaved(): Promise<void> {
  action.value = ''
  await load()
}
function sourceLabel(type?: string): string {
  return reversalTypes.find((item) => item.value === type)?.label ?? '原单据'
}
watch(
  () => [props.kind, detailId.value, session.companyId],
  () => {
    editing.value = false
    action.value = ''
    value.value = undefined
    if (detailId.value) void load()
  },
  { immediate: true },
)
function closeAction(): void {
  action.value = ''
  void load()
}
</script>
<template>
  <div class="page-heading">
    <div>
      <router-link v-if="detailId" class="text-link" :to="'/' + kind"
        >返回{{ spec.title }}</router-link
      >
      <h1>{{ detailId ? (value?.documentNo ?? spec.singular) : spec.title }}</h1>
    </div>
    <div class="sales-actions">
      <el-button
        v-if="!detailId && session.can(spec.permission + ':create')"
        type="primary"
        @click="editing = true"
        >新建{{ spec.singular }}</el-button
      ><el-button
        v-for="name in value?.allowedActions ?? []"
        :key="name"
        :type="['approve', 'post', 'ship'].includes(name) ? 'primary' : undefined"
        @click="choose(name)"
        >{{ stockActionLabels[name] ?? name }}</el-button
      >
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="loading">正在加载…</p>
  <DocumentBrowserPanel v-if="!detailId" :kind="kind" />
  <template v-else-if="value"
    ><section class="panel sales-detail-header">
      <dl>
        <dt>状态</dt>
        <dd>
          <el-tag :type="documentStateTag(kind, value.status)">{{
            stockStatusLabels[value.status] ?? value.status
          }}</el-tag>
        </dd>
        <dt>业务日期</dt>
        <dd>{{ value.businessDate }}</dd>
        <dt>仓库 / 路线</dt>
        <dd>
          {{
            value.warehouseName ??
            (value.fromWarehouseName
              ? value.fromWarehouseName + ' → ' + value.toWarehouseName
              : '—')
          }}
        </dd>
        <dt>版本</dt>
        <dd>{{ value.version }}</dd>
      </dl>
      <p v-if="value.sourceNo">{{ sourceLabel(value.sourceType) }}：{{ value.sourceNo }}</p>
      <p v-if="value.snapshotAt">
        盘点快照
        {{
          formatDateTime(value.snapshotAt)
        }}。实盘数对应此时点，过账按快照差额调整，不覆盖期间收发货。
      </p>
      <p v-if="value.reason">原因：{{ value.reason }}</p>
      <p v-if="value.remark">备注：{{ value.remark }}</p>
    </section>
    <section v-if="value.lines?.length" class="panel table-panel">
      <el-table :data="value.lines" row-key="id"
        ><el-table-column prop="skuName" label="商品" min-width="150" /><template
          v-if="kind === 'stock-transfers'"
          ><el-table-column label="调拨数量" min-width="150"
            ><template #default="{ row }">{{
              formatQuantity(row.qty, row.unitName)
            }}</template></el-table-column
          ><el-table-column label="基础数量" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.baseQty)
            }}</template></el-table-column
          ><el-table-column label="已调入" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.receivedBaseQty)
            }}</template></el-table-column
          ><el-table-column label="在途数量" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.remainingQty)
            }}</template></el-table-column
          ><el-table-column label="差异数量" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.diffBaseQty)
            }}</template></el-table-column
          ></template
        ><template v-else
          ><el-table-column prop="baseUnitName" label="基础单位" /><el-table-column
            label="快照账面数"
            min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.bookQty)
            }}</template></el-table-column
          ><el-table-column label="时点实盘数" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.countedQty)
            }}</template></el-table-column
          ><el-table-column label="盘点差额" min-width="130"
            ><template #default="{ row }">{{
              formatQuantity(row.diffQty)
            }}</template></el-table-column
          ></template
        ><el-table-column
          v-if="session.can('cost:view')"
          label="成本金额"
          min-width="130"
          align="right"
          ><template #default="{ row }">{{
            formatMoney(row.costAmount)
          }}</template></el-table-column
        ></el-table
      >
    </section>
  </template>
  <DocumentFilesPanel
    v-if="detailId && value"
    :owner-type="documentType(spec.permission)"
    :owner-id="value.id"
  />
  <StockDocumentEditor
    v-if="editing"
    :key="kind + (value?.id ?? '')"
    :kind="kind"
    :original="detailId ? value : undefined"
    @close="closeEditor"
    @saved="saved"
  />
  <StockCommandDialog
    v-if="action && value"
    :key="action + value.id"
    :kind="kind"
    :document="value"
    :action="action"
    @close="closeAction"
    @saved="commandSaved"
  />
</template>
