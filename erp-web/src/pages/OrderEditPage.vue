<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { document as salesDocument } from '../api/sales'
import { procurementDocument } from '../api/procurement'
import { isCancelled, messageOf } from '../api/http'
import type { SalesDocument } from '../types/sales'
import type { ProcurementDocument } from '../types/procurement'
import OrderEditor from '../components/sales/OrderEditor.vue'
import ProcurementEditor from '../components/procurement/ProcurementEditor.vue'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import { registerCompanyLeaveGuard } from '../utils/unsaved'
const props = defineProps<{ kind: 'sales-orders' | 'purchase-orders' }>(),
  route = useRoute(),
  router = useRouter()
const original = ref<SalesDocument | ProcurementDocument>(),
  loading = ref(false),
  error = ref(''),
  dirty = ref(false),
  permitted = ref(true)
const title = computed(
  () =>
    (route.params.id ? '编辑' : '新建') + (props.kind === 'sales-orders' ? '销售订单' : '采购订单'),
)
let active = true
const removeCompanyGuard = registerCompanyLeaveGuard(
  () => !dirty.value || window.confirm('修改尚未保存，确定切换公司？'),
  dirty,
)
async function load(): Promise<void> {
  if (!route.params.id) return
  loading.value = true
  error.value = ''
  try {
    const value =
      props.kind === 'sales-orders'
        ? await salesDocument(props.kind, String(route.params.id))
        : await procurementDocument(props.kind, String(route.params.id))
    if (!active) return
    original.value = value
    permitted.value = value.allowedActions.includes('update')
    if (!permitted.value) error.value = '该单据当前不能编辑，请返回详情查看最新状态。'
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) loading.value = false
  }
}
async function saved(document: SalesDocument | ProcurementDocument, submitAfterSave = false): Promise<void> {
  dirty.value = false
  const path = '/' + props.kind + '/' + document.id
  await router.replace(submitAfterSave ? { path, query: { next: 'submit' } } : path)
}
function close(): void {
  void router.push(original.value ? '/' + props.kind + '/' + original.value.id : '/' + props.kind)
}
function beforeUnload(event: BeforeUnloadEvent): void {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(() => !dirty.value || window.confirm('修改尚未保存，确定离开此页面？'))
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void load()
})
onBeforeUnmount(() => {
  active = false
  removeCompanyGuard()
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>
<template>
  <PageHeader
    :title="title"
    :back-label="kind === 'sales-orders' ? '返回销售订单' : '返回采购订单'"
    @back="close"
  >
    <template #status>
      <StatusTag v-if="dirty" label="有未保存的修改" tone="warning" />
      <StatusTag v-else-if="!loading && !error && permitted" label="草稿" />
    </template>
  </PageHeader>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="loading" role="status">正在加载订单…</p>
  <template v-if="!loading && !error && permitted"
    ><OrderEditor
      v-if="kind === 'sales-orders'"
      :key="original?.id ?? 'new'"
      :original="original"
      inline
      @dirty="dirty = $event"
      @close="close"
      @saved="saved" /><ProcurementEditor
      v-else
      :key="original?.id ?? 'new'"
      kind="purchase-orders"
      :original="original"
      inline
      @dirty="dirty = $event"
      @close="close"
      @saved="saved"
  /></template>
</template>
