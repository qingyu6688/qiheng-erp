<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { procurementSpecs } from '../types/procurement'
import type { ProcurementDocument, ProcurementKind } from '../types/procurement'
import { useSessionStore } from '../stores/session'
import ProcurementEditor from '../components/procurement/ProcurementEditor.vue'
import DocumentBrowserPanel from '../components/DocumentBrowserPanel.vue'
const props = defineProps<{ kind: ProcurementKind }>(),
  session = useSessionStore(),
  router = useRouter()
const spec = computed(() => procurementSpecs.find((item) => item.path === props.kind)!),
  editing = ref(false)
function create(): void {
  if (props.kind === 'purchase-orders') void router.push('/purchase-orders/new')
  else editing.value = true
}
async function saved(document: ProcurementDocument): Promise<void> {
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
  <ProcurementEditor v-if="editing" :kind="kind" @close="editing = false" @saved="saved" />
</template>
