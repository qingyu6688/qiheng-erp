<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { command, idPart, moves, resolveCode, stockWork, warehouseChoices } from '../api/work'
import { usePage } from '../composables/usePage'
import { feedback } from '../composables/useScanner'
import { useTaskFlow } from '../composables/useTaskFlow'
import { useSession } from '../stores/session'
import type { Id, ScanMatch } from '../types/api'
import type { StockWork, Warehouse } from '../types/work'
import { quantity, serialLines } from '../utils/quantity'
import { listRow, statusTitle } from '../utils/work'

const props = defineProps<{ type: 'move' | 'replenish' }>()
const session = useSession()
const current = ref<StockWork>()
const editing = ref(false)
const warehouses = ref<Warehouse[]>([])
const warehouse = ref<Warehouse>()
const from = ref('')
const to = ref('')
const item = ref('')
const serial = ref('')
const qty = ref('')
const remark = ref('')
const error = ref('')
const { items, cursor, busy, failure, load } = usePage((after) => moves(props.type, after))
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const canPost = computed(() => session.profile?.permissions.includes('wms_move:manage'))
const canCreate = computed(() => props.type === 'move' && canPost.value)

onMounted(() => load())

async function open(id: Id): Promise<void> {
  error.value = ''
  try {
    current.value = await stockWork('/wms/moves', id)
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function create(): Promise<void> {
  error.value = ''
  try {
    warehouses.value = (await warehouseChoices()).items.filter((row) => row.locationManaged)
    if (!warehouses.value.length) throw new Error('当前公司没有可操作的库位仓')
    warehouse.value = warehouses.value.length === 1 ? warehouses.value[0] : undefined
    editing.value = true
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

function changeWarehouse(event: { detail: { value: string | number } }): void {
  warehouse.value = warehouses.value[Number(event.detail.value)]
  from.value = ''
  to.value = ''
  item.value = ''
}

function location(match: ScanMatch, field: string): Id {
  if (match.type !== 'location') throw new Error(`${field}不是库位编码`)
  return match.id
}

async function saveMove(): Promise<void> {
  const warehouseId = warehouse.value?.id
  if (!warehouseId || sending.value) return
  error.value = ''
  try {
    const [source, target, product] = await Promise.all(
      [from.value, to.value, item.value].map((code) => resolveCode(code, warehouseId)),
    )
    if (!['sku', 'batch'].includes(product!.type)) throw new Error('请扫描商品或批次')
    const fromLocationId = location(source!, '来源')
    const toLocationId = location(target!, '目标')
    if (fromLocationId === toLocationId) throw new Error('来源和目标库位不能相同')
    const body = {
      warehouseId, remark: remark.value.trim() || undefined,
      lines: [{
        skuId: product!.type === 'sku' ? product!.id : product!.skuId,
        batchId: product!.type === 'batch' ? product!.id : undefined,
        fromLocationId, toLocationId, qty: quantity(qty.value), serialNos: serialLines(serial.value),
      }],
    }
    if (!body.lines[0]!.skuId) throw new Error('批次没有关联商品')
    const created = await run(body, (key) => command<StockWork>('/wms/moves', body, key), '移库单已建立')
    if (created) {
      editing.value = false
      from.value = to.value = item.value = serial.value = qty.value = remark.value = ''
      await open(created.id)
    }
    feedback(Boolean(created))
  } catch (cause: unknown) {
    error.value = errorText(cause)
    feedback(false)
  }
}

async function post(): Promise<void> {
  const record = current.value
  if (!record) return
  const path = '/wms/moves/' + idPart(record.id) + '/post'
  const body = { version: record.version }
  const result = await run([path, body], async (key) => {
    await command(path, body, key)
    current.value = await stockWork('/wms/moves', record.id)
    return true
  }, '库位余额已更新')
  feedback(Boolean(result))
}
</script>

<template>
  <view class="panel">
    <view class="section-title">
      <text class="heading">{{ type === 'move' ? '移库' : '补货' }}</text>
      <button v-if="current || editing" class="compact secondary" :disabled="sending"
        @click="current = undefined; editing = false; load()">返回</button>
      <button v-else-if="canCreate" class="compact secondary" @click="create">新建移库</button>
    </view>
    <text v-if="error || failure || commandError" class="error" role="alert">{{ error || failure || commandError }}</text>
    <text v-if="success" class="success">{{ success }}</text>
    <template v-if="editing">
      <picker :range="warehouses" range-key="name" :disabled="sending" @change="changeWarehouse">
        <view class="field">{{ warehouse?.name ?? '请选择仓库' }}</view>
      </picker>
      <ScanInput v-model="from" label="来源库位" :disabled="sending" pda-focus />
      <ScanInput v-model="to" label="目标库位" :disabled="sending" />
      <ScanInput v-model="item" label="商品或批次" :disabled="sending" />
      <input v-model="qty" class="field" type="digit" placeholder="数量" aria-label="数量" :disabled="sending">
      <textarea v-model="serial" class="field field-text" placeholder="序列号，每行一个" aria-label="序列号" :disabled="sending" />
      <input v-model="remark" class="field" placeholder="备注（选填）" aria-label="备注" :disabled="sending">
      <wd-button block :loading="sending" :disabled="!warehouse" @click="saveMove">保存移库单</wd-button>
    </template>
    <template v-else-if="current">
      <text class="subheading">{{ current.no ?? current.id }} · {{ statusTitle(current.status) }}</text>
      <view v-for="line in current.lines" :key="line.id" class="line-edit">
        <text>{{ line.skuCode }} {{ line.batchNo }} · {{ line.qty }}</text>
        <text class="muted">{{ line.fromLocationCode }} → {{ line.toLocationCode }}</text>
      </view>
      <wd-button v-if="current.status === 'draft' && canPost" block :loading="sending" @click="post">确认过账</wd-button>
    </template>
    <WorkList v-else :rows="items.map(listRow)" :more="!!cursor" :busy="busy" @select="open" @more="load(true)" />
  </view>
</template>
