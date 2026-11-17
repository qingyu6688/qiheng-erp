<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { demandExceedsAvailable } from '../../types/inventory'
import type { WmsLineConfirmation, WmsTaskLine } from '../../types/wms'

const props = defineProps<{ line: WmsTaskLine; busy: boolean }>()
const emit = defineEmits<{
  confirm: [input: Omit<WmsLineConfirmation, 'version'>]
  cancel: []
}>()

const fromLocation = ref(props.line.fromLocationCode)
const toLocation = ref(props.line.recommendedLocationCode ?? '')
const batch = ref(props.line.batchNo ?? '')
const quantity = ref('')
const serials = ref('')
const error = ref('')
const fromLocationInput = ref<InputInstance>()
const toLocationInput = ref<InputInstance>()
const batchInput = ref<InputInstance>()
const quantityInput = ref<InputInstance>()
const serialInput = ref<InputInstance>()

onMounted(() => fromLocationInput.value?.select())

function submit(): void {
  if (props.busy) return
  const fromLocationCode = fromLocation.value.trim()
  const toLocationCode = toLocation.value.trim()
  const qty = quantity.value.trim()
  error.value = ''
  if (!fromLocationCode || !toLocationCode) {
    error.value = '请扫描或输入来源库位和目标库位。'
    return
  }
  if (!demandExceedsAvailable(qty, '0')) {
    error.value = '请输入大于零的数量，最多保留六位小数。'
    return
  }
  emit('confirm', {
    fromLocationCode,
    toLocationCode,
    batchNo: batch.value.trim() || null,
    qty,
    serialNos: serials.value
      .split(/\r?\n/)
      .map((serial) => serial.trim())
      .filter(Boolean),
  })
}
</script>

<template>
  <form class="putaway-confirm" aria-label="扫码确认上架" @submit.prevent="submit">
    <h3>扫码确认 {{ line.skuCode }} · {{ line.skuName }}</h3>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <div class="putaway-fields">
      <label for="putaway-from">
        来源库位
        <el-input
          id="putaway-from"
          ref="fromLocationInput"
          v-model="fromLocation"
          maxlength="64"
          :disabled="busy"
          @keydown.enter.prevent="toLocationInput?.select()"
        />
      </label>
      <label for="putaway-to">
        目标库位
        <el-input
          id="putaway-to"
          ref="toLocationInput"
          v-model="toLocation"
          maxlength="64"
          :disabled="busy"
          @keydown.enter.prevent="line.batchNo ? batchInput?.select() : quantityInput?.focus()"
        />
      </label>
      <label for="putaway-batch">
        批次
        <el-input
          id="putaway-batch"
          ref="batchInput"
          v-model="batch"
          maxlength="160"
          :disabled="busy"
          @keydown.enter.prevent="quantityInput?.focus()"
        />
      </label>
      <label for="putaway-qty">
        实际上架数量
        <el-input
          id="putaway-qty"
          ref="quantityInput"
          v-model="quantity"
          inputmode="decimal"
          maxlength="31"
          :disabled="busy"
          @keydown.enter.prevent="serialInput?.focus()"
        />
      </label>
      <label for="putaway-serials" class="serials">
        序列号（序列号商品每行扫描一个）
        <el-input
          id="putaway-serials"
          ref="serialInput"
          v-model="serials"
          type="textarea"
          :rows="3"
          :disabled="busy"
        />
      </label>
    </div>
    <div class="sales-actions">
      <el-button :disabled="busy" @click="$emit('cancel')">取消</el-button>
      <el-button type="primary" native-type="submit" :loading="busy">确认本次上架</el-button>
    </div>
  </form>
</template>

<style scoped>
.putaway-confirm {
  display: grid;
  gap: 14px;
  padding: 16px;
}
.putaway-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
  gap: 12px;
}
.putaway-fields label {
  display: grid;
  gap: 6px;
}
.serials {
  grid-column: 1 / -1;
}
</style>
