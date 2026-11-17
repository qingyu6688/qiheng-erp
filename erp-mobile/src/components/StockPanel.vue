<script setup lang="ts">
import { ref } from 'vue'
import ScanInput from './ScanInput.vue'
import WarehouseSelect from './WarehouseSelect.vue'
import { errorText } from '../api/http'
import { locationStock, resolveCode, warehouseStock } from '../api/work'
import { feedback } from '../composables/useScanner'
import type { Id, ScanMatch } from '../types/api'
import type { InventoryRow } from '../types/work'

const code = ref('')
const warehouse = ref<Id>()
const match = ref<ScanMatch>()
const items = ref<InventoryRow[]>([])
const cursor = ref<Id>()
const busy = ref(false)
const error = ref('')

async function search(input: string, more = false): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = more ? match.value! : await resolveCode(input, warehouse.value)
    if (!['sku', 'location', 'batch', 'serial'].includes(result.type)) throw new Error('请选择商品、批次、序列号或库位')
    let reply
    if (result.type === 'location') {
      if (!result.warehouseId) throw new Error('未定位到该库位所属仓库')
      reply = await locationStock(result.warehouseId, result.id, more ? cursor.value : undefined)
    } else {
      const skuId = result.type === 'sku' ? result.id : result.skuId
      if (!skuId) throw new Error('未定位到该编码关联的商品')
      reply = await warehouseStock({ skuId, warehouseId: warehouse.value }, more ? cursor.value : undefined)
    }
    match.value = result
    items.value = more ? [...items.value, ...reply.items] : reply.items
    cursor.value = reply.nextCursor ?? undefined
    feedback(true)
  } catch (cause: unknown) {
    error.value = errorText(cause)
    if (!more) {
      match.value = undefined
      items.value = []
      cursor.value = undefined
    }
    feedback(false)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <view class="panel">
    <text class="heading">库存查询</text>
    <WarehouseSelect v-model="warehouse" :disabled="busy" @update:model-value="match = undefined; items = []; cursor = undefined" />
    <ScanInput v-model="code" label="扫描商品、库位、批次或序列号" :disabled="busy" pda-focus @scan="search" />
    <wd-button block :loading="busy" @click="search(code)">查询库存</wd-button>
    <text v-if="error" class="error" role="alert">{{ error }}</text>
    <template v-if="match">
      <text class="subheading">{{ match.code }} {{ match.name }}</text>
      <text v-if="match.type === 'batch' || match.type === 'serial'" class="muted">以下为该商品的仓库汇总。</text>
      <text v-if="!items.length" class="muted">暂无库存记录</text>
      <view v-for="(item, index) in items" :key="item.id ?? index" class="line-edit">
        <text>{{ item.skuCode }} {{ item.skuName }}</text>
        <text class="muted">{{ item.warehouseName }} {{ item.locationCode }}</text>
        <text>{{ item.onHandQty }} 在库 · {{ item.blockedQty }} 冻结</text>
        <text v-if="item.availableQty" class="muted">可用 {{ item.availableQty }}</text>
      </view>
      <button v-if="cursor" class="secondary" :disabled="busy" @click="search(code, true)">加载更多</button>
    </template>
  </view>
</template>
