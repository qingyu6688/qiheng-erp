<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { allRecords, record } from '../../api/admin'
import {
  priceList,
  priceLists,
  savePriceList,
  type PriceListHeader,
  type PriceListInput,
  type PriceListItem,
} from '../../api/crm-pricing'
import { isCancelled, messageOf } from '../../api/http'
import type { DataRow } from '../../types/admin'
import type { SalesSkuUnit } from '../../types/sales'
import { useSessionStore } from '../../stores/session'

const props = withDefaults(defineProps<{ purchase?: boolean }>(), { purchase: false })
const session = useSessionStore()
let revision = 0
let contextRevision = 0, editorRevision = 0
const pageTitle = computed(() => props.purchase ? '采购价格表' : '销售价格表')
const rows = ref<PriceListHeader[]>([])
const cursor = ref<string | null>(null)
const busy = ref(false)
const error = ref('')
const editorError = ref('')
const editing = ref(false)
const selected = ref<PriceListHeader>()
const skus = ref<DataRow[]>([])
const customers = ref<DataRow[]>([])
const units = ref<Record<string, SalesSkuUnit[]>>({})
const canManage = computed(() => session.can(props.purchase ? 'purchase_price_list:manage' : 'price_list:manage') && (!props.purchase || session.can('cost:view')))
const form = ref<PriceListInput>(blank())

function blank(): PriceListInput {
  return {
    name: '',
    priceIncludesTax: true,
    validFrom: '',
    validTo: '',
    priority: 0,
    status: 'active',
    targets: [{ targetType: props.purchase ? 'supplier' : 'all', targetValue: '' }],
    items: [{ skuId: '', unitId: '', minQty: '0', unitPrice: '' }],
  }
}

async function load(more = false): Promise<void> {
  const current = ++revision
  busy.value = true
  error.value = ''
  try {
    const result = await priceLists(more ? (cursor.value ?? undefined) : undefined, props.purchase)
    if (current !== revision) return
    rows.value = more ? [...rows.value, ...result.items] : result.items
    cursor.value = result.nextCursor
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) busy.value = false
  }
}

async function loadUnits(sku: string): Promise<SalesSkuUnit[]> {
  if (!units.value[sku]) {
    const context = contextRevision
    const value = await record('skus/' + encodeURIComponent(sku))
    if (context !== contextRevision) return []
    units.value[sku] = Array.isArray(value.units) ? (value.units as SalesSkuUnit[]) : []
  }
  return units.value[sku]!
}

async function open(row?: PriceListHeader): Promise<void> {
  const operation = ++editorRevision, context = contextRevision
  selected.value = row
  form.value = blank()
  editorError.value = ''
  busy.value = true
  editing.value = true
  try {
    const [skuChoices, partnerChoices, current] = await Promise.all([
      session.can('sku:view') ? allRecords('skus') : Promise.resolve([]),
      session.can('partner:view') ? allRecords('partners') : Promise.resolve([]),
      row ? priceList(row.id, props.purchase) : Promise.resolve(undefined),
    ])
    if (context !== contextRevision || operation !== editorRevision || !editing.value) return
    skus.value = skuChoices.filter((item) => item.status === 'active')
    customers.value = partnerChoices.filter(
      (item) =>
        item.status === 'active' && Array.isArray(item.roles) && item.roles.includes(props.purchase ? 'supplier' : 'customer'),
    )
    if (current) {
      const { name, priceIncludesTax, validFrom, validTo, priority, status, version } =
        current.header
      form.value = {
        name,
        priceIncludesTax,
        validFrom,
        validTo,
        priority,
        status,
        version,
        items: current.items,
        targets: current.targets,
      }
      if (session.can('sku:view'))
        await Promise.all([...new Set(current.items.map((item) => item.skuId))].map(loadUnits))
    }
  } catch (cause: unknown) {
    if (context === contextRevision && operation === editorRevision && !isCancelled(cause)) editorError.value = messageOf(cause)
  } finally {
    if (context === contextRevision && operation === editorRevision) busy.value = false
  }
}

async function selectSku(item: PriceListItem): Promise<void> {
  item.unitId = ''
  if (!item.skuId) return
  const current = item.skuId
  try {
    const options = await loadUnits(current)
    if (item.skuId === current) item.unitId = options.find((unit) => unit.isBase)?.unitId ?? ''
  } catch (cause: unknown) {
    if (!isCancelled(cause)) editorError.value = messageOf(cause)
  }
}

async function save(): Promise<void> {
  if (busy.value || !canManage.value) return
  const current = editorRevision, context = contextRevision
  busy.value = true
  editorError.value = ''
  try {
    await savePriceList(form.value, selected.value?.id, props.purchase)
    if (context !== contextRevision || current !== editorRevision || !canManage.value) return
    editing.value = false
    await load()
  } catch (cause: unknown) {
    if (context === contextRevision && current === editorRevision && !isCancelled(cause)) editorError.value = messageOf(cause)
  } finally {
    if (context === contextRevision && current === editorRevision) busy.value = false
  }
}

onMounted(load)
watch(() => [session.companyId, session.user?.id, props.purchase, session.can(props.purchase ? 'purchase_price_list:view' : 'price_list:view'), session.can('cost:view')], () => {
  ++revision; ++contextRevision; ++editorRevision; busy.value = false; rows.value = []; cursor.value = null; editing.value = false; selected.value = undefined
  skus.value = []; customers.value = []; units.value = {}; form.value = blank()
  if (session.companyId && session.can(props.purchase ? 'purchase_price_list:view' : 'price_list:view') && (!props.purchase || session.can('cost:view'))) void load()
})
onScopeDispose(() => { ++revision; ++contextRevision; ++editorRevision })
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>{{ pageTitle }}</h1>
      <p>{{ purchase ? '按供应商、有效期和数量阶梯维护采购协议价，订单显示取价依据。' : '按客户、级别和数量维护销售订单默认单价。' }}</p>
    </div>
    <el-button v-if="canManage" type="primary" :disabled="busy" @click="open()"
      >新增价格表</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel">
    <el-table :data="rows" :empty-text="busy ? '正在加载…' : '暂无价格表'" row-key="id">
      <el-table-column prop="name" label="名称" min-width="170" />
      <el-table-column prop="validFrom" label="有效期起" min-width="110" />
      <el-table-column prop="validTo" label="有效期止" min-width="110" />
      <el-table-column prop="priority" label="优先级" width="90" />
      <el-table-column label="含税口径" width="100"
        ><template #default="{ row }">{{
          row.priceIncludesTax ? '含税' : '未税'
        }}</template></el-table-column
      >
      <el-table-column label="状态" width="100"
        ><template #default="{ row }">{{
          row.status === 'active' ? '启用' : '停用'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="100"
        ><template #default="{ row }"
          ><el-button link type="primary" :disabled="busy" @click="open(row)"
            >查看</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-button v-if="cursor" :disabled="busy" @click="load(true)">加载更多</el-button>
  </section>
  <el-drawer
    v-model="editing"
    :title="selected ? pageTitle + '详情' : '新增' + pageTitle"
    size="min(980px, 96vw)" direction="rtl" append-to-body :close-on-click-modal="false"
  >
    <el-alert v-if="editorError" :title="editorError" type="error" :closable="false" />
    <el-form label-position="top" :disabled="!canManage || busy" @submit.prevent="save">
      <div class="price-fields">
        <el-form-item label="名称"
          ><el-input v-model.trim="form.name" maxlength="100"
        /></el-form-item>
        <el-form-item label="有效期起"
          ><el-input v-model="form.validFrom" type="date"
        /></el-form-item>
        <el-form-item label="有效期止"
          ><el-input v-model="form.validTo" type="date"
        /></el-form-item>
        <el-form-item label="优先级"
          ><el-input-number v-model="form.priority" :min="0" :max="9999"
        /></el-form-item>
        <el-form-item label="含税报价"><el-switch v-model="form.priceIncludesTax" /></el-form-item>
        <el-form-item label="状态"
          ><el-select v-model="form.status"
            ><el-option label="启用" value="active" /><el-option
              label="停用"
              value="disabled" /></el-select
        ></el-form-item>
      </div>
      <h3>适用对象</h3>
      <div v-for="(target, index) in form.targets" :key="index" class="price-target">
        <el-select
          v-model="target.targetType"
          aria-label="适用类型"
          @change="target.targetValue = ''"
          ><el-option v-if="purchase" label="指定供应商" value="supplier" />
          <template v-else><el-option label="通用" value="all" /><el-option label="客户级别" value="customer_level" /><el-option label="指定客户" value="customer" /></template></el-select>
        <el-select
          v-if="(target.targetType === 'customer' || target.targetType === 'supplier') && customers.length"
          v-model="target.targetValue"
          filterable
          :aria-label="purchase ? '适用供应商' : '适用客户'"
          ><el-option
            v-for="customer in customers"
            :key="customer.id"
            :value="customer.id"
            :label="String(customer.name)"
        /></el-select>
        <el-input
          v-else-if="target.targetType !== 'all'"
          v-model.trim="target.targetValue"
          :aria-label="target.targetType === 'supplier' ? '供应商编号' : target.targetType === 'customer' ? '客户编号' : '客户级别编码'"
        />
        <el-button v-if="form.targets.length > 1" text @click="form.targets.splice(index, 1)"
          >移除</el-button
        >
      </div>
      <el-button size="small" @click="form.targets.push({ targetType: purchase ? 'supplier' : 'all', targetValue: '' })"
        >增加对象</el-button
      >
      <h3>价格明细</h3>
      <div v-for="(item, index) in form.items" :key="index" class="price-line">
        <el-select
          v-if="skus.length"
          v-model="item.skuId"
          filterable
          aria-label="商品"
          @change="selectSku(item)"
          ><el-option
            v-for="sku in skus"
            :key="sku.id"
            :value="sku.id"
            :label="[sku.code, sku.name].filter(Boolean).join(' · ')"
        /></el-select>
        <el-input v-else v-model.trim="item.skuId" aria-label="商品编号" placeholder="商品编号" />
        <el-select v-if="units[item.skuId]?.length" v-model="item.unitId" aria-label="单位"
          ><el-option
            v-for="unit in units[item.skuId]"
            :key="unit.unitId"
            :value="unit.unitId"
            :label="unit.unitName ?? unit.unitId"
        /></el-select>
        <el-input v-else v-model.trim="item.unitId" aria-label="单位编号" placeholder="单位编号" />
        <el-input v-model.trim="item.minQty" aria-label="起订数量" placeholder="起订数量" />
        <el-input v-model.trim="item.unitPrice" aria-label="单价" placeholder="单价" />
        <el-button v-if="form.items.length > 1" text @click="form.items.splice(index, 1)"
          >移除</el-button
        >
      </div>
      <el-button
        size="small"
        @click="form.items.push({ skuId: '', unitId: '', minQty: '0', unitPrice: '' })"
        >增加明细</el-button
      >
      <div v-if="canManage" class="price-submit">
        <el-button type="primary" native-type="submit" :loading="busy">保存价格表</el-button>
      </div>
    </el-form>
  </el-drawer>
</template>

<style scoped>
.price-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(135px, 1fr));
  gap: 8px 14px;
}
.price-target,
.price-line {
  display: grid;
  gap: 8px;
  margin-bottom: 8px;
}
.price-target {
  grid-template-columns: minmax(120px, 1fr) minmax(140px, 2fr) auto;
}
.price-line {
  grid-template-columns: minmax(130px, 2fr) minmax(80px, 1fr) minmax(70px, 1fr) minmax(
      90px,
      1fr
    ) auto;
}
.price-submit {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
@media (max-width: 640px) {
  .price-line,
  .price-target {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
