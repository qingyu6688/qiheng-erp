<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { errorText } from '../api/http'
import { feedback } from '../composables/useScanner'
import type { StockConfirmation, StockLine } from '../types/work'
import { quantity, remaining, scaledQuantity, serialLines, stepQuantity } from '../utils/quantity'
import { scanMismatch, type FixedScanField, type StockTaskType } from '../utils/work'
import ScanFeedback from './ScanFeedback.vue'
import ScanInput from './ScanInput.vue'

type ScanStage = FixedScanField | 'serial'

const props = defineProps<{ line: StockLine; taskType: StockTaskType; busy: boolean; failure: string }>()
const title = computed(() => props.taskType === 'putaway' ? '上架' : '拣货')
const emit = defineEmits<{ confirm: [input: StockConfirmation]; edit: [] }>()
const labels: Record<ScanStage, string> = {
  from: '扫描来源库位', to: '扫描目标库位', batch: '扫描批次码', serial: '扫描序列号',
}
const stage = ref<ScanStage>('from')
const showScanner = ref(true)
const scanInput = ref<InstanceType<typeof ScanInput>>()
const draft = reactive<Record<ScanStage, string>>({ from: '', to: '', batch: '', serial: '' })
const serials = ref<string[]>([])
const left = computed(() => remaining(props.line.planQty, props.line.doneQty))
const qty = ref(left.value)
const lower = computed(() => stepQuantity(qty.value, left.value, -1))
const higher = computed(() => stepQuantity(qty.value, left.value, 1))
const localError = ref('')
const target = computed(() => props.line.toLocationCode ?? props.line.recommendedLocationCode)
const expected = computed(() => ({
  from: props.line.fromLocationCode,
  to: target.value,
  batch: props.line.batchNo,
  serial: null,
})[stage.value])
const expectedLabel = computed(() => ({
  from: '来源库位', to: props.taskType === 'putaway' ? '推荐库位' : '目标库位', batch: '批次码', serial: '',
})[stage.value])

function changeStage(next?: ScanStage): void {
  localError.value = ''
  emit('edit')
  showScanner.value = next !== undefined
  if (next) stage.value = next
}

function advance(value: string): void {
  if (props.busy || !value.trim()) return
  draft[stage.value] = value.trim()
  if (stage.value === 'serial') {
    if (serials.value.includes(draft.serial)) {
      localError.value = '序列号已录入'
      feedback(false)
    } else {
      serials.value.push(draft.serial)
      localError.value = ''
      feedback(true)
    }
    draft.serial = ''
    scanInput.value?.focusScanner()
    return
  }
  const mismatch = scanMismatch(props.taskType, stage.value, value, props.line)
  if (mismatch) {
    localError.value = mismatch
    draft[stage.value] = ''
    feedback(false)
    scanInput.value?.focusScanner()
    return
  }
  feedback(true)
  if (stage.value === 'from') changeStage('to')
  else if (!draft.batch && props.line.batchNo && stage.value === 'to') changeStage('batch')
  else changeStage()
}

function prepare(): void {
  if (props.busy) return
  try {
    const amount = quantity(qty.value)
    if ((scaledQuantity(amount) ?? 0n) > (scaledQuantity(left.value) ?? 0n)) {
      throw new Error('本次数量超过待确认数量')
    }
    emit('confirm', {
      fromLocationCode: draft.from.trim(), toLocationCode: draft.to.trim(), batchNo: draft.batch.trim() || null,
      qty: amount, serialNos: serialLines([...serials.value, draft.serial].join('\n')),
    })
    localError.value = ''
  } catch (cause: unknown) {
    localError.value = errorText(cause)
    feedback(false)
  }
}
</script>

<template>
  <view class="task-line-editor">
    <ScanInput v-if="showScanner" :key="stage" ref="scanInput" v-model="draft[stage]"
      :label="labels[stage]" :disabled="busy" pda-focus @scan="advance" />
    <ScanFeedback v-if="localError || failure" :ok="false" title="作业未确认" :detail="localError || failure" />
    <view v-if="showScanner && expected" class="task-line-card">
      <text class="muted">{{ expectedLabel }}</text>
      <text class="task-line-location">{{ expected }}</text>
      <text v-if="stage === 'to' && title === '上架'" class="muted">可扫描其他库位，提交时确认。</text>
    </view>
    <view class="task-line-card">
      <text class="subheading">{{ line.skuCode }} {{ line.skuName }}</text>
      <text v-if="line.batchNo" class="muted">批次 {{ line.batchNo }}</text>
      <text class="muted">待确认 {{ left }} / {{ line.planQty }}</text>
    </view>
    <template v-if="showScanner && stage === 'serial'">
      <text class="field-label">已录入 {{ serials.length }} 个序列号</text>
      <view v-if="serials.length" class="chips">
        <button v-for="(code, index) in serials" :key="code" class="secondary"
          :aria-label="'移除序列号 ' + code" :disabled="busy" @click="serials.splice(index, 1)">{{ code }} ×</button>
      </view>
    </template>
    <template v-if="!showScanner">
      <text class="field-label">确认数量</text>
      <view class="task-quantity">
        <button class="secondary" role="button" aria-label="减少数量" :disabled="busy || lower === qty" @click="qty = lower">−</button>
        <input v-model="qty" class="field" type="digit" aria-label="确认数量" :disabled="busy">
        <button class="secondary" role="button" aria-label="增加数量" :disabled="busy || higher === qty" @click="qty = higher">+</button>
      </view>
      <view class="chips">
        <button class="secondary" :disabled="busy" @click="changeStage('from')">重扫来源 {{ draft.from }}</button>
        <button v-if="line.batchNo" class="secondary" :disabled="busy" @click="changeStage('batch')">
          重扫批次 {{ draft.batch }}
        </button>
        <button class="secondary" :disabled="busy" @click="changeStage('serial')">
          {{ serials.length ? '已录入 ' + serials.length + ' 个序列号' : '扫描序列号' }}
        </button>
      </view>
    </template>
    <view class="task-footer">
      <wd-button v-if="showScanner && stage === 'serial'" role="button" block :round="false"
        :disabled="busy" @click="changeStage()">录入完成</wd-button>
      <wd-button v-else-if="showScanner" role="button" block :round="false"
        :disabled="busy || !draft[stage].trim()" @click="advance(draft[stage])">下一步</wd-button>
      <template v-else>
        <button class="secondary" :disabled="busy" @click="changeStage('to')">换库位</button>
        <view class="task-footer-primary">
          <wd-button role="button" block :round="false" :loading="busy" :disabled="busy" @click="prepare">
            {{ busy ? '正在提交' : '确认' + title }}
          </wd-button>
        </view>
      </template>
    </view>
  </view>
</template>

<style scoped>
.task-line-editor { display: flex; flex-direction: column; gap: 12px; }
.task-line-card { padding: 12px; border-radius: var(--mobile-radius); background: #fff; border: 1px solid var(--mobile-divider); }
.task-line-location { display: block; color: var(--mobile-primary-hover); font-size: 28px; font-weight: 700; line-height: 36px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.task-quantity { display: grid; grid-template-columns: 56px minmax(0, 1fr) 56px; gap: 8px; }
.task-quantity .field, .task-quantity button { height: var(--mobile-scan); font-size: 22px; text-align: center; }
</style>
