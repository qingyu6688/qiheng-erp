<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useSessionStore } from '../../stores/session'
import { sourcelessSourceTarget } from '../../utils/sourceless-sources'
import type { SalesSourceReference } from '../../utils/sourceless-sources'

const Preview = defineAsyncComponent(() => import('./SourcelessSourcePreview.vue'))
const props = defineProps<{ source: SalesSourceReference; label?: string; original?: boolean }>()
const session = useSessionStore(),
  open = ref(false)
const target = computed(() =>
  sourcelessSourceTarget(props.source, (permission) => session.can(permission), props.original),
)
const label = computed(
  () =>
    props.label ??
    (props.original
      ? '查看原退货 · ' + (target.value?.number ?? '来源单据')
      : (target.value?.number ??
        (typeof props.source.sourceNo === 'string' ? props.source.sourceNo : '—'))),
)
watch(
  () => [session.companyId, session.user?.id, target.value?.kind, target.value?.id],
  () => {
    open.value = false
  },
  { flush: 'sync' },
)
</script>
<template>
  <button v-if="target" type="button" class="text-link source-link" @click="open = true">
    {{ label }}
  </button>
  <span v-else-if="!original">{{ label }}</span>
  <Preview
    v-if="open && target"
    :key="target.kind + target.id"
    :kind="target.kind"
    :id="target.id"
    @close="open = false"
  />
</template>
<style scoped>
.source-link {
  max-width: 100%;
  text-align: start;
  overflow-wrap: anywhere;
}
</style>
