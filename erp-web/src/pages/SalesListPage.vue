<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { salesSpecs } from '../types/sales'
import type { SalesDocument, SalesKind } from '../types/sales'
import { useSessionStore } from '../stores/session'
import DocumentEditor from '../components/sales/DocumentEditor.vue'
import DocumentBrowserPanel from '../components/DocumentBrowserPanel.vue'
const props = defineProps<{ kind: SalesKind }>(),
  session = useSessionStore(),
  router = useRouter()
const spec = computed(() => salesSpecs.find((item) => item.path === props.kind)!),
  editing = ref(false)
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
    <el-button v-if="session.can(spec.permission + ':create')" type="primary" @click="create"
      >新建{{ spec.singular }}</el-button
    >
  </div>
  <DocumentBrowserPanel :key="kind" :kind="kind" />
  <DocumentEditor
    v-if="editing && kind !== 'sales-orders'"
    :kind="kind"
    @close="editing = false"
    @saved="saved"
  />
</template>
