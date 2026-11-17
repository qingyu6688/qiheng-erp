<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { isCancelled, messageOf } from '../api/http'
import { slotBarcode, slotLabel } from '../api/supply-chain'
import type { SlotLabel } from '../api/supply-chain'

const route = useRoute()
const warehouse = String(route.params.warehouseId)
const id = String(route.params.id)
const label = ref<SlotLabel>()
const url = ref('')
const ready = ref(false)
const busy = ref(true)
const error = ref('')
let mounted = true

onMounted(async () => {
  try {
    const [details, image] = await Promise.all([
      slotLabel(warehouse, id),
      slotBarcode(warehouse, id),
    ])
    if (!mounted) return
    label.value = details
    url.value = URL.createObjectURL(image)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
})
onUnmounted(() => {
  mounted = false
  if (url.value) URL.revokeObjectURL(url.value)
})
function printLabel(): void {
  if (ready.value) window.print()
}
</script>

<template>
  <main class="label-page" v-loading="busy">
    <header class="page-heading label-controls">
      <router-link :to="'/warehouse-locations?warehouseId=' + warehouse" class="text-link"
        >返回库区库位</router-link
      >
      <el-button type="primary" :disabled="!ready" @click="printLabel">打印标签</el-button>
    </header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <section v-if="label" class="location-label">
      <span>{{ label.warehouse }} · {{ label.name }}</span>
      <img
        :src="url"
        :alt="'库位条码 ' + label.code"
        @load="ready = true"
        @error="error = '库位条码无法显示'"
      />
      <strong>{{ label.code }}</strong>
    </section>
  </main>
</template>

<style scoped>
.label-page {
  margin: 20px auto;
  max-width: 560px;
  padding-inline: 16px;
}
.location-label {
  display: grid;
  gap: 7px;
  width: min(100%, 110mm);
  padding: 4mm;
  border: 1px solid #dce1e9;
  text-align: center;
  background: white;
  color: black;
}
.location-label img {
  width: 100%;
}
@media print {
  .label-controls {
    display: none;
  }
  .label-page {
    padding: 0;
    margin: 0;
  }
  .location-label {
    border: 0;
    break-inside: avoid;
  }
}
</style>
