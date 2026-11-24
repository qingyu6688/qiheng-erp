<script setup lang="ts">
import { computed, defineAsyncComponent, inject, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { salesSpecs } from '../types/sales'
import type { SalesDocument, SalesKind } from '../types/sales'
import { useSessionStore } from '../stores/session'
import DocumentEditor from '../components/sales/DocumentEditor.vue'
import DocumentBrowserPanel from '../components/DocumentBrowserPanel.vue'
import SalesReturnSourceTag from '../components/sales/SalesReturnSourceTag.vue'
import StatusTag from '../components/StatusTag.vue'
import type { SourcelessKind } from '../hooks/useSourcelessMutation'
const SourcelessDocumentDrawer = defineAsyncComponent(
  () => import('../components/sales/SourcelessDocumentDrawer.vue'),
)
const props = defineProps<{ kind: SalesKind }>(),
  session = useSessionStore(),
  router = useRouter()
const spec = computed(() => salesSpecs.find((item) => item.path === props.kind)!),
  editing = ref(false)
const readOnly = inject('erp.readOnlyPreview', false),
  sourceOpen = ref(false),
  sourceId = ref<string>()
const browser = ref<InstanceType<typeof DocumentBrowserPanel>>()
const sourceKind = computed<SourcelessKind>(() =>
  props.kind === 'sales-returns' ? 'sales-returns' : 'refund-requests',
)
const canCreateSource = computed(
  () =>
    !readOnly &&
    session.can(spec.value.permission + ':create') &&
    (props.kind === 'sales-returns'
      ? session.can('sales_return:sourceless')
      : props.kind === 'refund-requests' && session.can('sales_return:view')),
)
function newMode(row: SalesDocument): boolean {
  return props.kind === 'sales-returns'
    ? row.returnMode === 'sourceless'
    : props.kind === 'refund-requests' && row.refundMode === 'sourceless_credit'
}
function openSource(id?: string): void {
  sourceId.value = id
  sourceOpen.value = true
}
function sourceSaved(): void {
  void browser.value?.refresh()
}
watch(
  () => [props.kind, session.companyId, session.user?.id],
  () => {
    sourceOpen.value = false
    editing.value = false
  },
)
function create(): void {
  if (props.kind === 'sales-orders') void router.push('/sales-orders/new')
  else editing.value = true
}
async function saved(document: SalesDocument): Promise<void> {
  editing.value = false
  await router.push('/' + props.kind + '/' + document.id)
}
</script>
<template>
  <div class="page-heading">
    <h1>{{ spec.title }}</h1>
    <el-button
      v-if="!readOnly && session.can(spec.permission + ':create')"
      type="primary"
      @click="create"
      >新建{{ spec.singular }}</el-button
    >
    <el-button v-if="canCreateSource" @click="openSource()">{{
      kind === 'sales-returns' ? '无原单退货' : '退货信用额退款'
    }}</el-button>
  </div>
  <DocumentBrowserPanel ref="browser" :key="kind" :kind="kind"
    ><template #open="{ row, label }"
      ><button
        v-if="newMode(row) && !readOnly"
        type="button"
        class="text-link"
        @click="openSource(row.id)"
      >
        {{ label }}</button
      ><router-link v-else class="text-link" :to="'/' + kind + '/' + row.id">{{
        label
      }}</router-link
      ><template v-if="label !== '查看'"
        ><SalesReturnSourceTag v-if="kind === 'sales-returns'" :mode="row.returnMode" /><StatusTag
          v-else-if="row.refundMode === 'sourceless_credit'"
          label="退货信用额退款"
          tone="info" /></template></template
  ></DocumentBrowserPanel>
  <SourcelessDocumentDrawer
    v-if="sourceOpen"
    :kind="sourceKind"
    :id="sourceId"
    @close="sourceOpen = false"
    @saved="sourceSaved"
  />
  <DocumentEditor
    v-if="editing && kind !== 'sales-orders'"
    :kind="kind"
    @close="editing = false"
    @saved="saved"
  />
</template>
