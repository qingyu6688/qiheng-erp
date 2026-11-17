<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { warehouseChoices } from '../api/work'
import { errorText } from '../api/http'
import type { Id } from '../types/api'
import type { Warehouse } from '../types/work'

const props = defineProps<{ modelValue?: Id; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: Id | undefined] }>()
const warehouseRows = ref<Warehouse[]>([])
const error = ref('')
const names = computed(() => ['不限仓库', ...warehouseRows.value.map((item) => item.code + ' · ' + item.name)])
const active = computed(() => (
  warehouseRows.value.findIndex((item) => item.id === props.modelValue) + 1
))
onMounted(async () => {
  try {
    warehouseRows.value = (await warehouseChoices()).items
    if (warehouseRows.value.length === 1) emit('update:modelValue', warehouseRows.value[0]?.id)
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
})
</script>

<template>
  <view>
    <text class="field-label">仓库范围</text>
    <picker :range="names" :value="active" :disabled="disabled"
      @change="emit('update:modelValue', warehouseRows[Number($event.detail.value) - 1]?.id)">
      <view class="field">{{ names[active] }}</view>
    </picker>
    <text v-if="error" class="muted">无法加载仓库列表。{{ error }}</text>
  </view>
</template>
