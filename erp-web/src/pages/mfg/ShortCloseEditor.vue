<script setup lang="ts">
import { reactive } from 'vue'
import { manufacturingCommand } from '../../api/manufacturing'
import { useSessionStore } from '../../stores/session'
import { shortCloseCostLabels } from '../../types/manufacturing'
import type {
  ShortCloseCostDisposition,
  ShortCloseDraft,
  ShortCloseMaterial,
  WorkOrder,
} from '../../types/manufacturing'
import { today } from '../../types/sales'
import { useWmsCommands } from '../wms/useWmsCommands'
import { remainingMaterial } from './useManufacturing'

const props = defineProps<{ order: WorkOrder }>()
const emit = defineEmits<{ close: []; saved: [WorkOrder] }>()
const session = useSessionStore()
const { busy, error, run } = useWmsCommands()
const requestVersion = props.order.version
const activeCompany = session.companyId
const previous = props.order.shortClose?.status === 'pending' ? props.order.shortClose : undefined
const previousLines = new Map(previous?.materials.map((line) => [line.materialId, line]))
const draft = reactive({
  businessDate: previous?.businessDate ?? today(),
  reason: previous?.reason ?? '',
  costDisposition: previous?.costDisposition ?? ('' as ShortCloseCostDisposition | ''),
})
const rows = reactive(
  (props.order.materials ?? [])
    .map((material) => {
      const existing = previousLines.get(material.id)
      return {
        materialId: material.id,
        label: material.skuName ?? material.skuCode ?? material.componentSkuId,
        unit: material.unitName,
        netQty: remainingMaterial(material.issuedQty, material.returnedQty, '0'),
        consumedQty: existing?.consumedQty ?? '',
        scrappedQty: existing?.scrappedQty ?? '0',
        scrapReason: existing?.scrapReason ?? '',
      }
    })
    .filter((line) => line.netQty !== '0'),
)
const QUANTITY = /^[0-9]{1,18}(?:\.[0-9]{1,6})?$/
const POSITIVE_DIGIT = /[1-9]/

function materialInput(): ShortCloseMaterial[] | undefined {
  const materials: ShortCloseMaterial[] = []
  for (const row of rows) {
    const consumedQty = row.consumedQty.trim()
    const scrappedQty = row.scrappedQty.trim()
    const scrapReason = row.scrapReason.trim()
    if (!QUANTITY.test(consumedQty) || !QUANTITY.test(scrappedQty)) {
      error.value = `${row.label}的耗用和报废数量须为非负数，最多六位小数。`
      return
    }
    if (remainingMaterial(consumedQty, '0', scrappedQty) !== row.netQty) {
      error.value = `${row.label}的耗用和报废数量合计须等于净领料。`
      return
    }
    if (POSITIVE_DIGIT.test(scrappedQty) && !scrapReason) {
      error.value = `请填写${row.label}的报废原因。`
      return
    }
    materials.push({
      materialId: row.materialId,
      consumedQty,
      scrappedQty,
      scrapReason: scrapReason || undefined,
    })
  }
  return materials
}

async function submit(): Promise<void> {
  if (
    !session.can('mfg_work_order:short_close') ||
    session.companyId !== activeCompany ||
    props.order.version !== requestVersion ||
    !props.order.allowedActions?.includes('short_close')
  ) {
    error.value = '工单或权限已变化，请关闭窗口后刷新工单。'
    return
  }
  const reason = draft.reason.trim()
  if (!draft.businessDate || !reason || !draft.costDisposition) {
    error.value = '请填写短交日期、原因和剩余在制成本处理方式。'
    return
  }
  const materials = materialInput()
  if (!materials) return
  const data: ShortCloseDraft = {
    version: requestVersion,
    businessDate: draft.businessDate,
    reason,
    costDisposition: draft.costDisposition,
    materials,
  }
  const result = await run('short-close:' + props.order.id, data, (key) =>
    manufacturingCommand<WorkOrder>('work-orders', props.order.id, 'short-close', data, key),
  )
  if (result && session.companyId === activeCompany) emit('saved', result)
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="申请短交关闭"
    width="min(920px, 95vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @close="emit('close')"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p>
      已完工 {{ order.completedQty }}，计划
      {{ order.planQty }}。待退材料请先过账退料，再按净领料登记耗用和报废。
    </p>
    <el-form label-position="top" :disabled="busy" @submit.prevent="submit">
      <div class="mfg-form">
        <el-form-item label="短交日期">
          <el-input v-model="draft.businessDate" type="date" aria-label="短交日期" />
        </el-form-item>
        <el-form-item label="剩余在制成本">
          <el-select v-model="draft.costDisposition" aria-label="剩余在制成本">
            <el-option
              v-for="(label, value) in shortCloseCostLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="短交原因">
          <el-input v-model="draft.reason" type="textarea" :maxlength="500" aria-label="短交原因" />
        </el-form-item>
      </div>
      <el-table :data="rows" row-key="materialId" empty-text="暂无净领料">
        <el-table-column prop="label" label="物料" min-width="128" />
        <el-table-column label="净领料" min-width="106" align="right">
          <template #default="{ row }">{{ row.netQty }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="已耗用" min-width="115">
          <template #default="{ row }">
            <el-input
              v-model="row.consumedQty"
              inputmode="decimal"
              :aria-label="row.label + '已耗用'"
            />
          </template>
        </el-table-column>
        <el-table-column label="已报废" min-width="115">
          <template #default="{ row }">
            <el-input
              v-model="row.scrappedQty"
              inputmode="decimal"
              :aria-label="row.label + '已报废'"
            />
          </template>
        </el-table-column>
        <el-table-column label="报废原因" min-width="162">
          <template #default="{ row }">
            <el-input
              v-model="row.scrapReason"
              :maxlength="500"
              :aria-label="row.label + '报废原因'"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">提交短交申请</el-button>
    </template>
  </el-dialog>
</template>
