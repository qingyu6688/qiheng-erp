<script setup lang="ts">
import { ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WarehouseSelect from './WarehouseSelect.vue'
import { errorText } from '../api/http'
import { labels, resolveCode } from '../api/work'
import type { LabelSize } from '../api/work'
import { feedback } from '../composables/useScanner'
import type { Id, ScanMatch } from '../types/api'
import type { LabelItem } from '../types/work'
import { openPdf } from '../utils/openPdf'

const sizes: LabelSize[] = ['50x30', '70x40', '100x100']
const size = ref<LabelSize>('50x30')
const code = ref('')
const warehouse = ref<Id>()
const preview = ref<ScanMatch>()
const copies = ref('1')
const busy = ref(false)
const error = ref('')
const success = ref('')

async function identify(value: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = success.value = ''
  try {
    const item = await resolveCode(value, warehouse.value)
    if (item.type === 'serial') throw new Error('请扫描商品、库位、批次或箱码')
    preview.value = item
    feedback(true)
  } catch (cause: unknown) {
    preview.value = undefined
    error.value = errorText(cause)
    feedback(false)
  } finally {
    busy.value = false
  }
}

async function print(): Promise<void> {
  if (!preview.value || preview.value.type === 'serial' || busy.value) return
  const count = Number(copies.value)
  if (!Number.isInteger(count) || count < 1 || count > 100) {
    error.value = '份数须为 1 至 100 的整数'
    return
  }
  busy.value = true
  error.value = success.value = ''
  try {
    const item: LabelItem = { type: preview.value.type, id: preview.value.id, copies: count }
    await openPdf(await labels([item], size.value, preview.value.warehouseId ?? warehouse.value))
    success.value = '标签 PDF 已生成'
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <view class="panel">
    <text class="heading">标签补打</text>
    <text v-if="error" class="error" role="alert">{{ error }}</text>
    <text v-if="success" class="success">{{ success }}</text>
    <WarehouseSelect v-model="warehouse" :disabled="busy" @update:model-value="preview = undefined" />
    <ScanInput v-model="code" label="扫描商品、库位、批次或箱码" :disabled="busy" pda-focus
      @update:model-value="preview = undefined" @scan="identify" />
    <button class="secondary" :disabled="busy" @click="identify(code)">识别编码</button>
    <template v-if="preview">
      <text class="subheading">{{ preview.code }} {{ preview.name }}</text>
      <text class="field-label">标签纸规格（毫米）</text>
      <picker :range="sizes" :value="sizes.indexOf(size)" :disabled="busy"
        @change="size = sizes[Number($event.detail.value)] ?? '50x30'">
        <view class="field">{{ size.replace('x', ' × ') }}</view>
      </picker>
      <input v-model="copies" class="field" type="number" aria-label="标签份数" placeholder="份数" :disabled="busy">
      <wd-button block :loading="busy" @click="print">生成标签 PDF</wd-button>
    </template>
  </view>
</template>
