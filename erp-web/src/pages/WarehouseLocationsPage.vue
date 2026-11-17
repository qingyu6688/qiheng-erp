<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { isCancelled, messageOf } from '../api/http'
import { warehouses } from '../api/masterdata'
import {
  generateSlots,
  saveSlot,
  saveWarehouseLayout,
  saveZone,
  slotBalances,
  warehouseLayout,
} from '../api/supply-chain'
import type {
  Slot,
  SlotBalance,
  SlotInput,
  WarehouseLayout,
  Zone,
  ZoneInput,
  ZoneType,
} from '../api/supply-chain'
import { useSessionStore } from '../stores/session'
import type { Warehouse } from '../types/warehouse'

const session = useSessionStore()
const route = useRoute()
const write = computed(() => session.can('wms_location:manage') && session.can('warehouse:update'))
const available = ref<Warehouse[]>([])
const warehouseId = ref('')
const current = ref<WarehouseLayout>()
const zoneId = ref('')
const filterLocationId = ref('')
const balances = ref<SlotBalance[]>([])
const moreBalances = ref(false)
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const tab = ref(
  route.query.view === 'balances' && session.can('inventory:view') ? 'balances' : 'layout',
)
const zoneForm = ref<ZoneInput>()
const slotForm = ref<SlotInput>()
const batchForm = ref<{
  zoneId: string
  prefix: string
  aisles: number
  shelves: number
  layers: number
  positions: number
}>()
const defaults = ref({ receivingId: '', shippingId: '' })
const activeZones = computed(
  () => current.value?.zones.filter((zone) => zone.status === 'active') ?? [],
)
const activeSlots = computed(
  () =>
    current.value?.locations.filter(
      (slot) =>
        slot.status === 'active' && activeZones.value.some((zone) => zone.id === slot.zoneId),
    ) ?? [],
)
const visibleSlots = computed(
  () =>
    current.value?.locations.filter((slot) => !zoneId.value || slot.zoneId === zoneId.value) ?? [],
)
const dialog = computed(() =>
  zoneForm.value ? '库区' : slotForm.value ? '库位' : batchForm.value ? '批量生成库位' : '',
)
const zoneTypes: Record<ZoneType, string> = {
  storage: '存储',
  receiving: '收货',
  shipping: '发货',
  quarantine: '待检隔离',
  return: '退货',
}

onMounted(async () => {
  try {
    const page = await warehouses({ limit: 200, withTotal: false })
    available.value = page.items
    warehouseId.value =
      typeof route.query.warehouseId === 'string'
        ? route.query.warehouseId
        : (page.items[0]?.id ?? '')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
})
let loadingId = 0
async function load(): Promise<void> {
  const generation = ++loadingId
  if (!warehouseId.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await warehouseLayout(warehouseId.value)
    if (generation !== loadingId) return
    current.value = result
    defaults.value = {
      receivingId: result.warehouse.defaultReceivingLocationId ?? '',
      shippingId: result.warehouse.defaultShippingLocationId ?? '',
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause) && generation === loadingId) error.value = messageOf(cause)
  } finally {
    if (generation === loadingId) busy.value = false
  }
}
let balanceLoad = 0
async function loadBalances(append = false): Promise<void> {
  const generation = ++balanceLoad
  if (!warehouseId.value || tab.value !== 'balances') return
  const limit = 50
  try {
    const rows = await slotBalances(warehouseId.value, {
      locationId: filterLocationId.value || undefined,
      cursor: append ? balances.value.at(-1)?.id : undefined,
      limit,
    })
    if (generation !== balanceLoad) return
    balances.value = append ? [...balances.value, ...rows] : rows
    moreBalances.value = rows.length === limit
  } catch (cause: unknown) {
    if (!isCancelled(cause) && generation === balanceLoad) error.value = messageOf(cause)
  }
}
watch(warehouseId, () => {
  current.value = undefined
  zoneId.value = filterLocationId.value = notice.value = ''
  balances.value = []
  close()
  void load()
  void loadBalances()
})
watch([tab, filterLocationId], () => {
  balances.value = []
  moreBalances.value = false
  void loadBalances()
})
function close(): void {
  zoneForm.value = slotForm.value = batchForm.value = undefined
}
function editZone(row?: Zone): void {
  error.value = ''
  zoneForm.value = row
    ? {
        id: row.id,
        version: row.version,
        code: row.code,
        name: row.name,
        type: row.type,
        enabled: row.status === 'active',
      }
    : { code: '', name: '', type: 'storage', enabled: true }
}
function editSlot(row?: Slot): void {
  error.value = ''
  if (row) {
    const { status, ...fields } = row
    slotForm.value = { ...fields, enabled: status === 'active' }
  } else {
    slotForm.value = {
      zoneId: zoneId.value || activeZones.value[0]?.id || '',
      code: '',
      name: '',
      aisle: null,
      shelf: null,
      layer: null,
      position: null,
      capacityQty: null,
      mixedSkuAllowed: true,
      mixedBatchAllowed: true,
      pickSequence: 0,
      enabled: true,
    }
  }
}
function batch(): void {
  error.value = ''
  batchForm.value = {
    zoneId: zoneId.value || activeZones.value[0]?.id || '',
    prefix: '',
    aisles: 1,
    shelves: 1,
    layers: 1,
    positions: 1,
  }
}
async function perform(configure = false): Promise<void> {
  if (!current.value || saving.value) return
  const id = warehouseId.value
  error.value = notice.value = ''
  saving.value = true
  try {
    if (configure) {
      await saveWarehouseLayout(id, { ...defaults.value, version: current.value.warehouse.version })
      notice.value = '仓库库位设置已保存'
    } else if (zoneForm.value) {
      await saveZone(id, zoneForm.value)
      notice.value = '库区已保存'
    } else if (slotForm.value) {
      await saveSlot(id, { ...slotForm.value, capacityQty: slotForm.value.capacityQty || null })
      notice.value = '库位已保存'
    } else if (batchForm.value) {
      const { count } = await generateSlots(id, batchForm.value)
      notice.value = '已生成 ' + count + ' 个库位'
    }
    close()
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <header class="page-heading">
    <h1>库区库位</h1>
    <router-link to="/warehouses" class="text-link">仓库管理</router-link>
  </header>
  <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
  <section class="panel table-panel" v-loading="busy">
    <div class="filters">
      <label for="slot-warehouse">仓库</label>
      <el-select
        id="slot-warehouse"
        v-model="warehouseId"
        filterable
        :disabled="saving"
        placeholder="选择仓库"
      >
        <el-option
          v-for="warehouse in available"
          :key="warehouse.id"
          :label="warehouse.code + ' ' + warehouse.name"
          :value="warehouse.id"
        />
      </el-select>
      <el-tag v-if="current">{{
        current.warehouse.locationManaged ? '已启用库位' : '按仓库记账'
      }}</el-tag>
    </div>
    <el-tabs v-if="current" v-model="tab">
      <el-tab-pane name="layout" label="库区与库位">
        <div class="filters">
          <el-select
            v-model="zoneId"
            aria-label="筛选库区"
            placeholder="全部库区"
            filterable
            clearable
          >
            <el-option
              v-for="zone in current.zones"
              :key="zone.id"
              :label="zone.code + ' ' + zone.name"
              :value="zone.id"
            />
          </el-select>
          <el-button v-if="write" @click="editZone()">新增库区</el-button>
          <el-button
            v-if="write && zoneId"
            @click="editZone(current.zones.find((zone) => zone.id === zoneId))"
            >编辑库区</el-button
          >
          <el-button v-if="write" :disabled="!activeZones.length" @click="editSlot()"
            >新增库位</el-button
          >
          <el-button v-if="write" :disabled="!activeZones.length" @click="batch"
            >批量生成</el-button
          >
        </div>
        <el-table :data="visibleSlots" row-key="id" empty-text="暂无库位">
          <el-table-column prop="code" label="库位编码" min-width="170" />
          <el-table-column prop="name" label="库位名称" min-width="140" />
          <el-table-column label="所属库区" min-width="100">
            <template #default="{ row }">{{
              current.zones.find((zone) => zone.id === row.zoneId)?.name
            }}</template>
          </el-table-column>
          <el-table-column prop="pickSequence" label="拣货顺序" width="100" />
          <el-table-column label="状态" width="90"
            ><template #default="{ row }">{{
              row.status === 'active' ? '启用' : '停用'
            }}</template></el-table-column
          >
          <el-table-column label="操作" min-width="140">
            <template #default="{ row }">
              <el-button v-if="write" type="primary" link @click="editSlot(row)">编辑</el-button>
              <router-link
                class="text-link"
                :to="'/warehouse-locations/' + warehouseId + '/labels/' + row.id"
                >标签</router-link
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane name="balances" label="库位余额" v-if="session.can('inventory:view')">
        <div class="filters">
          <el-select
            v-model="filterLocationId"
            filterable
            clearable
            aria-label="筛选库位"
            placeholder="全部库位"
          >
            <el-option
              v-for="slot in current.locations"
              :key="slot.id"
              :value="slot.id"
              :label="slot.code"
            />
          </el-select>
          <el-button @click="loadBalances()">刷新</el-button>
        </div>
        <el-table :data="balances" row-key="id" empty-text="暂无库位余额">
          <el-table-column prop="locationCode" label="库位" min-width="130" />
          <el-table-column prop="skuCode" label="商品编码" min-width="120" />
          <el-table-column prop="skuName" label="商品名称" min-width="140" />
          <el-table-column prop="batchNo" label="批次" min-width="110" />
          <el-table-column prop="expiryDate" label="到期日" min-width="104" />
          <el-table-column prop="onHandQty" label="在库" width="100" />
          <el-table-column prop="reservedQty" label="拣货占用" width="104" />
          <el-table-column prop="blockedQty" label="冻结" width="100" />
          <el-table-column prop="availableQty" label="可用" width="100" />
        </el-table>
        <el-button v-if="moreBalances" @click="loadBalances(true)">加载更多</el-button>
      </el-tab-pane>
      <el-tab-pane v-if="write" name="settings" label="仓库设置">
        <el-form label-position="top" :disabled="saving" @submit.prevent="perform(true)">
          <el-form-item label="默认收货库位">
            <el-select v-model="defaults.receivingId" filterable>
              <el-option
                v-for="slot in activeSlots"
                :key="slot.id"
                :value="slot.id"
                :label="slot.code + ' ' + slot.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="默认发货库位">
            <el-select v-model="defaults.shippingId" filterable>
              <el-option
                v-for="slot in activeSlots"
                :key="slot.id"
                :value="slot.id"
                :label="slot.code + ' ' + slot.name"
              />
            </el-select>
          </el-form-item>
          <el-alert
            v-if="!current.warehouse.locationManaged"
            title="首次启用会将现有库存登记到默认收货库位，请先处理未完结预留。"
            type="info"
            :closable="false"
          />
          <el-button
            type="primary"
            native-type="submit"
            :disabled="!defaults.receivingId || !defaults.shippingId"
            :loading="saving"
          >
            {{ current.warehouse.locationManaged ? '保存库位设置' : '启用库位管理' }}
          </el-button>
        </el-form>
      </el-tab-pane>
    </el-tabs>
    <el-empty v-else-if="!busy" description="请选择仓库" />
  </section>
  <el-dialog :model-value="!!dialog" :title="dialog" @update:model-value="close">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="saving" @submit.prevent="perform()">
      <template v-if="zoneForm">
        <el-form-item label="库区编码"
          ><el-input v-model="zoneForm.code" maxlength="32" required
        /></el-form-item>
        <el-form-item label="库区名称"
          ><el-input v-model="zoneForm.name" maxlength="100" required
        /></el-form-item>
        <el-form-item label="用途">
          <el-select v-model="zoneForm.type">
            <el-option
              v-for="(label, value) in zoneTypes"
              :key="value"
              :value="value"
              :label="label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="zoneForm.enabled" /></el-form-item>
      </template>
      <template v-if="slotForm">
        <el-form-item label="所属库区">
          <el-select v-model="slotForm.zoneId" filterable>
            <el-option
              v-for="zone in activeZones"
              :key="zone.id"
              :value="zone.id"
              :label="zone.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="库位编码"
          ><el-input v-model="slotForm.code" maxlength="48" required
        /></el-form-item>
        <el-form-item label="库位名称"
          ><el-input v-model="slotForm.name" maxlength="100" required
        /></el-form-item>
        <div class="slot-fields">
          <el-form-item label="巷道"
            ><el-input v-model="slotForm.aisle" maxlength="16"
          /></el-form-item>
          <el-form-item label="货架"
            ><el-input v-model="slotForm.shelf" maxlength="16"
          /></el-form-item>
          <el-form-item label="层"
            ><el-input v-model="slotForm.layer" maxlength="16"
          /></el-form-item>
          <el-form-item label="位"
            ><el-input v-model="slotForm.position" maxlength="16"
          /></el-form-item>
        </div>
        <el-form-item label="容量（基础数量）"
          ><el-input v-model="slotForm.capacityQty" inputmode="decimal" placeholder="留空不限"
        /></el-form-item>
        <el-form-item label="拣货顺序"
          ><el-input-number v-model="slotForm.pickSequence" :min="0" :precision="0"
        /></el-form-item>
        <el-form-item label="允许混放商品"
          ><el-switch v-model="slotForm.mixedSkuAllowed"
        /></el-form-item>
        <el-form-item label="允许混放批次"
          ><el-switch v-model="slotForm.mixedBatchAllowed"
        /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="slotForm.enabled" /></el-form-item>
      </template>
      <template v-if="batchForm">
        <el-form-item label="所属库区">
          <el-select v-model="batchForm.zoneId" filterable>
            <el-option
              v-for="zone in activeZones"
              :key="zone.id"
              :value="zone.id"
              :label="zone.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="编码前缀"
          ><el-input v-model="batchForm.prefix" maxlength="24" required
        /></el-form-item>
        <div class="slot-fields">
          <el-form-item label="巷道数"
            ><el-input-number v-model="batchForm.aisles" :min="1" :max="99" :precision="0"
          /></el-form-item>
          <el-form-item label="货架数"
            ><el-input-number v-model="batchForm.shelves" :min="1" :max="99" :precision="0"
          /></el-form-item>
          <el-form-item label="层数"
            ><el-input-number v-model="batchForm.layers" :min="1" :max="99" :precision="0"
          /></el-form-item>
          <el-form-item label="位数"
            ><el-input-number v-model="batchForm.positions" :min="1" :max="99" :precision="0"
          /></el-form-item>
        </div>
      </template>
      <el-button native-type="submit" type="primary" :loading="saving">{{
        batchForm ? '生成' : '保存'
      }}</el-button>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.filters {
  flex-wrap: wrap;
}
.filters .el-select {
  max-width: 280px;
}
.slot-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.slot-fields .el-form-item {
  flex: 1 1 120px;
}
.el-alert + .el-button {
  margin-top: 12px;
}
</style>
