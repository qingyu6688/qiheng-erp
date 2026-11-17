<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { saveManufacturing } from '../../api/manufacturing'
import { useSessionStore } from '../../stores/session'
import type { Bom, BomDraft, BomLineDraft, SubstituteDraft } from '../../types/manufacturing'
import { today } from '../../types/sales'
import { useWmsCommands } from '../wms/useWmsCommands'
import ReferenceSelect from './ReferenceSelect.vue'
import { useManufacturingCatalog } from './useManufacturing'

const props = defineProps<{ source?: Bom; edit?: boolean }>()
const emit = defineEmits<{ close: []; saved: [Bom] }>()
const session = useSessionStore()
const { skus, warehouses, units, unitsFor, catalogError, loadCatalog } = useManufacturingCatalog()
const { busy, error, run } = useWmsCommands()
const blankLine = (): BomLineDraft => ({
  componentSkuId: '',
  unitId: '',
  qty: '1',
  scrapRate: '0',
  phantom: false,
  substitutes: [],
})
const draft = reactive<BomDraft>({
  skuId: props.source?.skuId ?? '',
  versionNo: props.edit ? (props.source?.versionNo ?? '') : '',
  unitId: props.source?.unitId ?? '',
  baseQty: props.source?.baseQty ?? '1',
  validFrom: props.edit ? (props.source?.validFrom ?? today()) : today(),
  validTo: props.edit ? (props.source?.validTo ?? '') : '',
  isDefault: props.edit ? Boolean(props.source?.isDefault) : !props.source,
  remark: props.source?.remark ?? '',
  version: props.edit ? props.source?.version : undefined,
  lines: props.source?.lines.map((line) => ({
    ...line,
    substitutes: (line.substitutes ?? []).map((item) => ({ ...item })),
  })) ?? [blankLine()],
})

onMounted(() => {
  void loadCatalog(['skus', 'warehouses'])
  const existing = [
    draft.skuId,
    ...draft.lines.flatMap((line) => [
      line.componentSkuId,
      ...line.substitutes.map((item) => item.substituteSkuId),
    ]),
  ]
  for (const id of new Set(existing)) if (id) void unitsFor(id)
})

function unitChange(item: { unitId: string }, currentSku: () => string): void {
  const sku = currentSku()
  item.unitId = ''
  void unitsFor(sku).then((list) => {
    if (sku === currentSku() && !item.unitId)
      item.unitId = list.find((unit) => unit.isBase)?.unitId ?? ''
  })
}

function addSubstitute(line: BomLineDraft): void {
  line.substitutes.push({
    substituteSkuId: '',
    unitId: '',
    ratio: '1',
    priority: line.substitutes.length + 1,
  })
}

function substitutePayload(item: SubstituteDraft) {
  return {
    substituteSkuId: item.substituteSkuId,
    unitId: item.unitId,
    ratio: item.ratio.trim(),
    priority: item.priority,
  }
}

async function save(): Promise<void> {
  if (
    !draft.skuId ||
    !draft.versionNo.trim() ||
    !draft.unitId ||
    !draft.baseQty.trim() ||
    !draft.validFrom ||
    !draft.lines.length
  ) {
    error.value = '请填写父项、版本、计量单位、基准数量、生效日和物料明细。'
    return
  }
  if (
    draft.lines.some(
      (line) =>
        !line.componentSkuId ||
        !line.unitId ||
        !line.qty.trim() ||
        line.substitutes.some(
          (item) => !item.substituteSkuId || !item.unitId || !item.ratio.trim(),
        ),
    )
  ) {
    error.value = '请完整填写子件和替代料。'
    return
  }
  const payload: BomDraft = {
    ...draft,
    versionNo: draft.versionNo.trim(),
    baseQty: draft.baseQty.trim(),
    validTo: draft.validTo || undefined,
    lines: draft.lines.map((line) => ({
      componentSkuId: line.componentSkuId,
      unitId: line.unitId,
      phantom: line.phantom,
      remark: line.remark,
      issueWarehouseId: line.issueWarehouseId || undefined,
      qty: line.qty.trim(),
      scrapRate: line.scrapRate.trim() || '0',
      substitutes: line.substitutes.map(substitutePayload),
    })),
  }
  const saved = await run('bom-save', payload, (key) =>
    saveManufacturing<Bom>('boms', payload, key, props.edit ? props.source?.id : undefined),
  )
  if (saved) emit('saved', saved)
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="edit ? '编辑物料清单' : source ? '新建 BOM 版本' : '新建物料清单'"
    width="min(920px, 95vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="emit('close')"
  >
    <el-alert
      v-if="error || catalogError"
      :title="error || catalogError"
      type="error"
      :closable="false"
    />
    <el-form label-position="top" :disabled="busy" @submit.prevent="save">
      <div class="mfg-form">
        <el-form-item label="父项商品">
          <ReferenceSelect
            v-model="draft.skuId"
            :rows="skus"
            label="父项商品"
            :disabled="Boolean(source && !edit)"
            @update:model-value="unitChange(draft, () => draft.skuId)"
          />
        </el-form-item>
        <el-form-item label="版本"
          ><el-input v-model="draft.versionNo" aria-label="BOM 版本" maxlength="30"
        /></el-form-item>
        <el-form-item label="基准数量"
          ><el-input v-model="draft.baseQty" aria-label="基准数量" inputmode="decimal"
        /></el-form-item>
        <el-form-item label="父项计量单位">
          <el-select
            v-if="session.can('sku:view')"
            v-model="draft.unitId"
            aria-label="父项计量单位"
            placeholder="请选择单位"
          >
            <el-option
              v-for="unit in units[draft.skuId] ?? []"
              :key="unit.unitId"
              :label="unit.unitName"
              :value="unit.unitId"
            />
          </el-select>
          <el-input
            v-else
            v-model="draft.unitId"
            aria-label="父项计量单位"
            placeholder="输入单位编号"
          />
        </el-form-item>
        <el-form-item label="生效日"
          ><el-input v-model="draft.validFrom" aria-label="生效日" type="date"
        /></el-form-item>
        <el-form-item label="失效日"
          ><el-input v-model="draft.validTo" aria-label="失效日" type="date"
        /></el-form-item>
      </div>
      <el-checkbox v-model="draft.isDefault">默认版本</el-checkbox>
      <el-form-item label="备注"><el-input v-model="draft.remark" maxlength="200" /></el-form-item>
      <h3>子件与替代料</h3>
      <div v-for="(line, index) in draft.lines" :key="index" class="mfg-lines">
        <div class="mfg-form">
          <el-form-item label="子件商品">
            <ReferenceSelect
              v-model="line.componentSkuId"
              :rows="skus"
              label="子件商品"
              @update:model-value="unitChange(line, () => line.componentSkuId)"
            />
          </el-form-item>
          <el-form-item label="子件单位">
            <el-select
              v-if="session.can('sku:view')"
              v-model="line.unitId"
              aria-label="子件单位"
              placeholder="请选择单位"
            >
              <el-option
                v-for="unit in units[line.componentSkuId] ?? []"
                :key="unit.unitId"
                :label="unit.unitName"
                :value="unit.unitId"
              />
            </el-select>
            <el-input
              v-else
              v-model="line.unitId"
              aria-label="子件单位"
              placeholder="输入单位编号"
            />
          </el-form-item>
          <el-form-item label="用量"
            ><el-input v-model="line.qty" aria-label="子件用量" inputmode="decimal"
          /></el-form-item>
          <el-form-item label="损耗率（%）"
            ><el-input v-model="line.scrapRate" aria-label="损耗率" inputmode="decimal"
          /></el-form-item>
          <el-form-item label="领料仓"
            ><ReferenceSelect
              v-model="line.issueWarehouseId"
              :rows="warehouses"
              label="子件领料仓"
              optional
          /></el-form-item>
          <el-form-item label="子件备注"
            ><el-input v-model="line.remark" maxlength="200"
          /></el-form-item>
        </div>
        <div class="mfg-toolbar">
          <el-checkbox v-model="line.phantom">虚拟件</el-checkbox>
          <el-button @click="addSubstitute(line)">添加替代料</el-button>
          <el-button
            link
            type="danger"
            :disabled="draft.lines.length === 1"
            @click="draft.lines.splice(index, 1)"
            >移除子件</el-button
          >
        </div>
        <div v-for="(substitute, subIndex) in line.substitutes" :key="subIndex" class="mfg-form">
          <el-form-item label="替代商品"
            ><ReferenceSelect
              v-model="substitute.substituteSkuId"
              :rows="skus"
              label="替代商品"
              @update:model-value="unitChange(substitute, () => substitute.substituteSkuId)"
          /></el-form-item>
          <el-form-item label="替代单位">
            <el-select
              v-if="session.can('sku:view')"
              v-model="substitute.unitId"
              aria-label="替代单位"
              placeholder="请选择单位"
            >
              <el-option
                v-for="unit in units[substitute.substituteSkuId] ?? []"
                :key="unit.unitId"
                :label="unit.unitName"
                :value="unit.unitId"
              />
            </el-select>
            <el-input
              v-else
              v-model="substitute.unitId"
              aria-label="替代单位"
              placeholder="输入单位编号"
            />
          </el-form-item>
          <el-form-item label="替代比例"
            ><el-input v-model="substitute.ratio" aria-label="替代比例" inputmode="decimal"
          /></el-form-item>
          <el-form-item label="优先级"
            ><el-input-number v-model="substitute.priority" :min="1" :precision="0"
          /></el-form-item>
          <el-button link type="danger" @click="line.substitutes.splice(subIndex, 1)"
            >移除替代料</el-button
          >
        </div>
      </div>
      <div class="mfg-toolbar">
        <el-button @click="draft.lines.push(blankLine())">添加子件</el-button>
        <el-button native-type="submit" type="primary" :loading="busy">保存物料清单</el-button>
        <el-button @click="emit('close')">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
