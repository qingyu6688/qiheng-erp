<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { trackingOptions } from '../../api/inventory-tracking'
import { isCancelled, messageOf } from '../../api/http'
import type { StockSelection, TrackingOptions } from '../../types/inventory-tracking'
import { useSessionStore } from '../../stores/session'

const props = defineProps<{
  warehouseId: string
  skuId: string
  mode: 'in' | 'out' | 'return' | 'freeze' | 'scrap'
  modelValue?: StockSelection | null
  serialOnly?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [StockSelection | null] }>()
const session = useSessionStore()
const options = ref<TrackingOptions>()
const error = ref('')
const loading = ref(false)
const expanded = ref(false)
const serialText = ref('')
const rangeStart = ref('')
const rangeEnd = ref('')
const selection = computed(() => props.modelValue ?? {})
const show = computed(() => Boolean(options.value && (
  options.value.serialManaged || !props.serialOnly && (options.value.batchManaged || options.value.locationManaged)
)))
const batchChoice = computed(() => selection.value.batchId || (selection.value.batchNo ? 'new' : ''))
const newBatch = ref(false)
const serialLabel = computed(() => props.mode === 'in' ? '登记序列号' : '选择序列号')
const summary = computed(() => {
  const batch = options.value?.batches.find((row) => row.id === selection.value.batchId)
  const location = options.value?.locations.find((row) => row.id === selection.value.locationId)
  return [
    batch?.batchNo || selection.value.batchNo,
    location?.code,
    selection.value.serials?.length ? selection.value.serials.length + ' 个序列号' : '',
  ].filter(Boolean).join(' · ')
})

watch(() => props.modelValue?.serials, (values) => {
  const normalized = values?.join('\n') ?? ''
  if (serialText.value.split(/[\r\n]+/).map((serial) => serial.trim()).filter(Boolean).join('\n') !== normalized) serialText.value = normalized
}, { immediate: true })

watch(() => [session.companyId, props.warehouseId, props.skuId], async (_, prior, cleanup) => {
  options.value = undefined
  error.value = ''
  expanded.value = false
  loading.value = false
  newBatch.value = false
  if (prior && prior[0] !== session.companyId) emit('update:modelValue', null)
  if (!props.warehouseId || !props.skuId) return
  const controller = new AbortController()
  cleanup(() => controller.abort())
  loading.value = true
  try {
    options.value = await trackingOptions(props.warehouseId, props.skuId, controller.signal)
    newBatch.value = Boolean(selection.value.batchNo)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })

function replace(fields: Partial<StockSelection>): void {
  const next: StockSelection = { ...selection.value, ...fields }
  const values = Object.entries(next).filter(([, value]) => Array.isArray(value) ? value.length > 0 : Boolean(value))
  emit('update:modelValue', values.length > 0 ? Object.fromEntries(values) as StockSelection : null)
}

function chooseBatch(value: string): void {
  newBatch.value = value === 'new'
  replace({ batchId: value && value !== 'new' ? value : undefined, batchNo: undefined, productionDate: undefined, expiryDate: undefined })
}

function enterSerials(value: string): void {
  serialText.value = value
  replace({ serials: value.split(/[\r\n]+/).map((serial) => serial.trim()).filter(Boolean) })
}

function generateSerials(): void {
  const start = /^(.*?)([0-9]+)$/.exec(rangeStart.value)
  const end = /^(.*?)([0-9]+)$/.exec(rangeEnd.value)
  if (!start?.[2] || !end?.[2] || start[1] !== end[1] || start[2].length !== end[2].length) {
    error.value = '请填写前缀和数字位数相同的首尾序列号。'
    return
  }
  const first = BigInt(start[2]), last = BigInt(end[2])
  if (last < first || last - first >= 500n) {
    error.value = '序列区间必须按递增顺序填写，每次最多生成 500 个。'
    return
  }
  const values = Array.from({ length: Number(last - first) + 1 }, (_, index) =>
    (start[1] ?? '') + (first + BigInt(index)).toString().padStart(start[2]!.length, '0'))
  const current = new Set([...(selection.value.serials ?? []), ...values])
  if (current.size > 500) {
    error.value = '每行最多登记 500 个序列号。'
    return
  }
  error.value = ''
  enterSerials([...current].join('\n'))
}
</script>

<template>
  <small v-if="loading" class="stock-selection-status" role="status">加载库存选项</small>
  <small v-else-if="error && !show" class="stock-selection-status" role="alert">{{ error }}</small>
  <div v-if="show" class="stock-selection-fields">
    <el-button size="small" text type="primary" @click="expanded = !expanded" :aria-expanded="expanded">
      {{ summary || '设置批次、库位与序列号' }}
    </el-button>
    <div v-if="expanded" class="stock-selection-inputs">
      <el-form-item v-if="options?.batchManaged && mode !== 'return' && !serialOnly" label="批次">
        <el-select
          :model-value="newBatch ? 'new' : batchChoice"
          :placeholder="mode === 'out' && options.issueStrategy !== 'manual' ? '系统推荐批次' : '请选择批次'"
          aria-label="批次"
          filterable clearable
          @update:model-value="chooseBatch"
        >
          <el-option v-if="mode === 'in'" label="新建批次" value="new" />
          <el-option
            v-for="item in options.batches" :key="item.id" :value="item.id"
            :label="item.batchNo + (item.expiryDate ? ' · ' + item.expiryDate : '')"
            :disabled="mode === 'out' && (item.expiryState === 'expired' || item.status !== 'normal')"
          />
        </el-select>
      </el-form-item>
      <template v-if="options?.batchManaged && mode === 'in' && newBatch && !serialOnly">
        <el-form-item label="批次号">
          <el-input :model-value="selection.batchNo" maxlength="64" @update:model-value="replace({ batchNo: $event })" />
        </el-form-item>
        <el-form-item label="生产日期">
          <el-date-picker :model-value="selection.productionDate" value-format="YYYY-MM-DD" type="date" @update:model-value="replace({ productionDate: $event || undefined })" />
        </el-form-item>
        <el-form-item label="到期日">
          <el-date-picker :model-value="selection.expiryDate" value-format="YYYY-MM-DD" type="date" @update:model-value="replace({ expiryDate: $event || undefined })" />
        </el-form-item>
      </template>
      <el-form-item v-if="options?.locationManaged && !serialOnly" label="库位">
        <el-select :model-value="selection.locationId" filterable clearable :placeholder="mode === 'freeze' ? '请选择库位' : '按业务规则选择'" @update:model-value="replace({ locationId: $event || undefined })">
          <el-option v-for="item in options.locations" :key="item.id" :value="item.id" :label="item.code + ' · ' + item.name" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="options?.serialManaged && mode !== 'freeze'" :label="serialLabel">
        <el-input :model-value="serialText" type="textarea" :rows="3" placeholder="可扫码或粘贴，一行一个序列号" @update:model-value="enterSerials" />
        <div v-if="mode === 'in'" class="stock-selection-range">
          <el-input v-model="rangeStart" aria-label="首个序列号" placeholder="首个序列号" maxlength="100" />
          <el-input v-model="rangeEnd" aria-label="末个序列号" placeholder="末个序列号" maxlength="100" />
          <el-button @click="generateSerials">按区间生成</el-button>
        </div>
      </el-form-item>
      <small v-if="error" class="stock-selection-status" role="alert">{{ error }}</small>
    </div>
  </div>
</template>

<style scoped>
.stock-selection-fields { grid-column: 1 / -1; min-width: 0; }
.stock-selection-inputs { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px 12px; padding: 10px 12px 0; background: var(--el-fill-color-light); border-radius: var(--erp-radius); }
.stock-selection-range { display: flex; gap: 8px; margin-top: 8px; }
.stock-selection-status { grid-column: 1 / -1; color: var(--el-text-color-secondary); }
</style>
