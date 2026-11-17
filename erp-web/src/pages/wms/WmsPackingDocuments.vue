<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ApiError } from '../../api/http'
import { createLabels } from '../../api/wms-operations'
import { useSessionStore } from '../../stores/session'
import { BOX_LABEL_LIMIT, shipmentBoxes, shipmentPackingList } from './packing-documents'
import type { ShipmentBox, ShipmentBoxDetail } from './packing-documents'
import { useWmsCommands } from './useWmsCommands'

const props = defineProps<{ shipment: ShipmentBox }>()
const session = useSessionStore()
const { busy, error, run } = useWmsCommands()
const company = session.companyId
const scope = { warehouseId: props.shipment.warehouseId, shipmentId: props.shipment.shipmentId }
const details = ref<ShipmentBoxDetail[]>([])
const manifest = ref<HTMLElement>()
let mounted = true
onBeforeUnmount(() => {
  mounted = false
})

function isCurrent(): boolean {
  return (
    mounted &&
    session.companyId === company &&
    session.can('wms_pack:view') &&
    props.shipment.warehouseId === scope.warehouseId &&
    props.shipment.shipmentId === scope.shipmentId
  )
}

async function showManifest(): Promise<void> {
  if (!isCurrent() || busy.value) return
  details.value = []
  const rows = await run('packing-list', scope, () => shipmentPackingList(scope, isCurrent))
  if (rows && isCurrent()) details.value = rows
}

async function downloadShipmentLabels(): Promise<void> {
  const canPrintLabels = (): boolean => isCurrent() && session.can('label:print')
  if (!canPrintLabels()) return
  const file = await run('shipment-box-labels', scope, async () => {
    const boxes = await shipmentBoxes(scope, canPrintLabels)
    if (!canPrintLabels()) return undefined
    if (boxes.length > BOX_LABEL_LIMIT) {
      throw new ApiError(400, 'LABEL_LIMIT', '该发货单超过 100 箱，可从装箱记录逐箱下载标签。')
    }
    return createLabels(
      scope.warehouseId,
      '70x40',
      'pdf',
      boxes.map(({ id }) => ({ id, type: 'box', copies: 1 })),
    )
  })
  if (!file || !canPrintLabels()) return
  const address = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = address
  link.download = `${props.shipment.shipmentNo}-箱标签.pdf`
  link.click()
  setTimeout(() => URL.revokeObjectURL(address), 1000)
}

function printManifest(): void {
  if (!isCurrent() || !manifest.value) return
  const print = window.open('', '_blank', 'popup')
  if (!print) {
    error.value = '请允许浏览器打开装箱清单打印窗口。'
    return
  }
  print.opener = null
  print.document.title = `装箱清单 · ${props.shipment.shipmentNo}`
  const style = print.document.createElement('style')
  style.textContent = `
    body { font: 13px sans-serif; color: #111; }
    table { width: 100%; border-collapse: collapse; }
    th, td { border: 1px solid #777; text-align: left; padding: 5px; overflow-wrap: anywhere; }
    h3 { break-after: avoid; }
    tr { break-inside: avoid; }
  `
  print.document.head.append(style)
  print.document.body.append(print.document.importNode(manifest.value, true))
  print.focus()
  print.print()
}
</script>

<template>
  <section class="packing-documents" aria-label="发货单装箱资料">
    <h3>发货单 {{ shipment.shipmentNo }}</h3>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="packing-actions">
      <el-button :disabled="busy" @click="showManifest">查看装箱清单</el-button>
      <el-button v-if="details.length" @click="printManifest">打印装箱清单</el-button>
      <el-button v-if="session.can('label:print')" :disabled="busy" @click="downloadShipmentLabels">
        下载整单箱标签
      </el-button>
    </div>
    <p v-if="busy" role="status">正在读取装箱资料。</p>
    <div v-if="details.length" ref="manifest" class="packing-manifest">
      <h2>装箱清单 · {{ shipment.shipmentNo }}</h2>
      <p>已完成 {{ details.length }} 箱</p>
      <section v-for="box in details" :key="box.id">
        <h3>箱号 {{ box.boxNo }}</h3>
        <table>
          <thead>
            <tr>
              <th scope="col">SKU</th>
              <th scope="col">商品</th>
              <th scope="col">批次</th>
              <th scope="col">数量</th>
              <th scope="col">序列号</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="line in box.lines" :key="line.id">
              <td>{{ line.skuCode }}</td>
              <td>{{ line.skuName }}</td>
              <td>{{ line.batchNo || '—' }}</td>
              <td>{{ line.qty }}</td>
              <td>{{ line.serialNos.join('、') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
  </section>
</template>

<style scoped>
.packing-documents {
  margin-top: 16px;
}
.packing-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
}
.packing-actions :deep(.el-button) {
  margin-left: 0;
}
.packing-manifest table {
  width: 100%;
  border-collapse: collapse;
}
.packing-manifest th,
.packing-manifest td {
  border: 1px solid var(--el-border-color-light);
  padding: 8px;
  text-align: left;
  overflow-wrap: anywhere;
}
</style>
