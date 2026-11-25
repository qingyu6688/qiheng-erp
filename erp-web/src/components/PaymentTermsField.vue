<script setup lang="ts">
import { computed, useId } from 'vue'
import type { PaymentTerm } from '../types/payment-terms'

const props = defineProps<{
  modelValue: PaymentTerm | null
  label: string
  allowInherit?: boolean
  inheritLabel?: string
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: PaymentTerm | null] }>()
const id = useId()
const mode = computed(() => props.modelValue?.type ?? (props.allowInherit && props.modelValue === null ? 'inherit' : 'none'))

function changeMode(event: Event): void {
  const type = (event.target as HTMLSelectElement).value
  emit('update:modelValue', type === 'inherit' ? null : type === 'none' ? { type: null, value: null }
    : { type: type as 'days' | 'monthly', value: type === 'monthly' ? 1 : 0 })
}

function changeValue(event: Event): void {
  const raw = (event.target as HTMLInputElement).value
  emit('update:modelValue', { type: props.modelValue?.type ?? null, value: raw === '' ? null : Number(raw) })
}
</script>

<template>
  <div class="payment-term-field">
    <label :for="id">{{ label }}</label>
    <div class="payment-term-controls">
      <select :id="id" :value="mode" :disabled="disabled" class="el-input__inner" @change="changeMode">
        <option v-if="allowInherit" value="inherit">{{ inheritLabel || '使用往来单位或公司账期' }}</option>
        <option value="none">{{ allowInherit ? '不设账期' : (inheritLabel || '不设账期') }}</option>
        <option value="days">按天</option>
        <option value="monthly">月结次月固定日</option>
      </select>
      <input v-if="mode === 'days' || mode === 'monthly'" :value="modelValue?.value" type="number"
        :min="mode === 'monthly' ? 1 : 0" :max="mode === 'monthly' ? 28 : 365" step="1" required
        :aria-label="label + (mode === 'monthly' ? '次月几日' : '天数')" :disabled="disabled"
        class="el-input__inner" @input="changeValue" />
      <span v-if="mode === 'days' || mode === 'monthly'">{{ mode === 'monthly' ? '日' : '天' }}</span>
    </div>
  </div>
</template>

<style scoped>
.payment-term-field { display: grid; gap: 6px; margin-bottom: 16px; min-width: 0; }
.payment-term-field label { color: var(--erp-text-2); font-size: 13px; }
.payment-term-controls { display: flex; align-items: center; gap: 8px; min-width: 0; }
.payment-term-controls select, .payment-term-controls input { border: 1px solid var(--erp-border-1); border-radius: var(--erp-radius); padding: 6px 10px; height: 34px; background: var(--erp-bg); }
.payment-term-controls select { min-width: 0; flex: 1; }
.payment-term-controls input { width: 80px; flex-shrink: 0; }
</style>
