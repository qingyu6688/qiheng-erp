const idPattern = /^[1-9][0-9]{0,18}$/
const one = 1_000_000n

function requireFact(ok, label) {
  if (!ok) throw new Error('波次拣货容量校验失败：' + label)
}

function id(value) {
  return typeof value === 'string' && idPattern.test(value)
}

function version(value) {
  return Number.isSafeInteger(value) && value >= 0
}

function scaled(value) {
  const parts = /^(-?)([0-9]{1,24})(?:\.([0-9]{1,6}))?$/.exec(String(value ?? ''))
  requireFact(parts, '库存数量须能精确换算为六位小数')
  const amount = BigInt(parts[2]) * one + BigInt((parts[3] ?? '').padEnd(6, '0'))
  return parts[1] ? -amount : amount
}

export function parseWmsFixture(text, target) {
  let fixture
  try {
    fixture = JSON.parse(text)
  } catch {
    throw new Error('波次拣货容量夹具不是有效的 JSON')
  }
  requireFact(typeof fixture?.dataset === 'string' && /^[a-z0-9_-]{1,48}$/.test(fixture.dataset), '样本标识')
  requireFact(id(fixture?.companyId) && id(fixture?.warmWaveId), '公司和暖机波次编号必须为字符串')
  requireFact(['hotspot', 'spread'].includes(fixture.mode), '指定热点或分散库位模式')
  requireFact(Array.isArray(fixture.waveIds) && fixture.waveIds.length === target && fixture.waveIds.every(id),
    '待领取的计时波次数量必须等于额定迭代数，编号必须为字符串')
  requireFact(new Set([fixture.warmWaveId, ...fixture.waveIds]).size === target + 1, '暖机或计时波次不能重复')
  return fixture
}

export function readyWave(expectedId, wave) {
  requireFact(wave?.id === expectedId && id(wave.warehouseId) && version(wave.version) &&
    wave.status === 'created' && wave.assigneeId == null, '波次必须尚未领取')
  requireFact(Array.isArray(wave.shipments) && wave.shipments.length === 2 && Array.isArray(wave.lines) &&
    wave.lines.length === 2, '每波次须有两张各一行的发货拣货任务')
  const tasks = wave.shipments.map((task) => {
    requireFact(id(task?.id) && id(task.shipmentId) && task.waveId === expectedId && task.warehouseId === wave.warehouseId &&
      task.status === 'created' && task.assigneeId == null && version(task.version), '拣货任务不可领取或不属于该波次')
    const lines = wave.lines.filter((line) => line.taskId === task.id)
    const line = lines.length === 1 ? lines[0] : undefined
    requireFact(id(line?.id) && id(line.skuId) && id(line.batchId) && id(line.fromLocationId) && id(line.toLocationId) &&
      line.shipmentId === task.shipmentId && line.fromLocationId !== line.toLocationId &&
      typeof line.fromLocationCode === 'string' && line.fromLocationCode && typeof line.toLocationCode === 'string' && line.toLocationCode &&
      typeof line.batchNo === 'string' && line.batchNo && scaled(line.planQty) === one && scaled(line.doneQty) === 0n &&
      Array.isArray(line.serialNos) && line.serialNos.length === 1 && typeof line.serialNos[0] === 'string' &&
      line.serialNos[0].trim().length > 0 && line.serialNos[0].length <= 100 && line.scannedSerialNos?.length === 0,
    '明细必须覆盖来源和目标库位、批次、待拣数量 1 及一个未扫描序列号')
    return { id: task.id, version: task.version, shipmentId: task.shipmentId, line }
  })
  requireFact(tasks[0].id !== tasks[1].id && tasks[0].shipmentId !== tasks[1].shipmentId, '波次的任务和发货单不能重复')
  return { id: expectedId, version: wave.version, warehouseId: wave.warehouseId, tasks }
}

export function verifyWaveProgress(wave, result, actorId, stage) {
  const stages = { claim: ['assigned', 1, 0n], confirm: ['in_progress', 2, one], finish: ['done', 3, one] }
  const [status, revision, quantity] = stages[stage]
  requireFact(result?.id === wave.id && result.warehouseId === wave.warehouseId && result.status === status &&
    result.assigneeId === actorId && result.version === wave.version + 1 &&
    result.shipments?.length === wave.tasks.length && result.lines?.length === wave.tasks.length, '波次进度或领取人不一致')
  for (const task of wave.tasks) {
    const head = result.shipments.find((entry) => entry.id === task.id)
    const line = result.lines.find((entry) => entry.id === task.line.id && entry.taskId === task.id)
    requireFact(head?.status === status && (head.shortPick === false || head.shortPick === 0) &&
      head.shipmentId === task.shipmentId && head.assigneeId === actorId && head.version === task.version + revision &&
      scaled(line?.doneQty) === quantity && line?.scannedSerialNos?.length === (quantity === 0n ? 0 : 1) &&
      (quantity === 0n || line.scannedSerialNos[0] === task.line.serialNos[0]), '拣货任务版本、短拣、数量或序列号不一致')
  }
}

export function wmsConfirmation(task) {
  return {
    version: task.version + 1, fromLocationCode: 'L:' + task.line.fromLocationCode,
    toLocationCode: 'L:' + task.line.toLocationCode, batchNo: task.line.batchNo, qty: '1', serialNos: task.line.serialNos,
  }
}

export function wmsDimensions(waves, warm, mode) {
  const serials = new Set(), tasks = new Set(), sources = new Set(), totals = new Map()
  for (const wave of [warm, ...waves]) {
    for (const task of wave.tasks) {
      const line = task.line, serial = line.skuId + '/' + line.serialNos[0]
      requireFact(!serials.has(serial) && !tasks.has(task.id), '暖机和计时夹具不得分配同一序列号或任务')
      serials.add(serial); tasks.add(task.id)
      if (wave === warm) continue
      for (const [place, delta] of [[line.fromLocationId, -1], [line.toLocationId, 1]]) {
        const key = [wave.warehouseId, line.skuId, line.batchId, place].join('/')
        if (delta < 0) sources.add(key)
        const saved = totals.get(key) ?? { warehouseId: wave.warehouseId, skuId: line.skuId,
          batchId: line.batchId, locationId: place, delta: 0 }
        saved.delta += delta
        totals.set(key, saved)
      }
    }
  }
  requireFact(mode === 'hotspot' ? sources.size === 1 && totals.size === 2 : sources.size > 1,
    '实际库存坐标与热点或分散样本标识不符')
  return { positions: [...totals.values()], sourceDimensions: sources.size }
}

export function wmsPosition(position, rows) {
  requireFact(Array.isArray(rows) && rows.length < 200 && rows.every((row) =>
    row.locationKey === position.locationId && row.skuId === position.skuId), '库位余额为空响应、截断或包含错误坐标')
  const matches = rows.filter((row) => row.lotKey === position.batchId)
  requireFact(matches.length <= 1, '同批次同库位库存重复')
  const item = matches[0]
  return { hand: item ? String(scaled(item.onHandQty)) : '0', reserved: item ? String(scaled(item.reservedQty)) : '0',
    blocked: item ? String(scaled(item.blockedQty)) : '0' }
}

export function verifyWmsPosition(position, before, after) {
  const delta = BigInt(position.delta) * one
  requireFact(BigInt(after.hand) - BigInt(before.hand) === delta &&
    BigInt(after.reserved) - BigInt(before.reserved) === delta && after.blocked === before.blocked,
  '库位在手量、发货占用或冻结量与实际拣货不符')
}

export function verifyWmsTrace(wave, task, trace) {
  const line = task.line, serial = line.serialNos[0]
  requireFact(trace?.serial?.serialNo === serial && trace.serial.skuId === line.skuId &&
    trace.serial.locationKey === line.toLocationId && trace.serial.warehouseId === wave.warehouseId &&
    trace.serial.batchId === line.batchId && trace.serial.status === 'reserved' && Array.isArray(trace.items), '序列号当前库位和发货占用')
  const moves = trace.items.filter((row) => row.sourceType === 'wms_task_confirmation' && row.sourceId === task.id)
  const outgoing = moves.find((row) => row.movementType === 'location_move_out')
  const incoming = moves.find((row) => row.movementType === 'location_move_in')
  requireFact(moves.length === 2 && id(outgoing?.sourceLineId) && outgoing.sourceLineId === incoming?.sourceLineId &&
    outgoing.locationKey === line.fromLocationId && incoming.locationKey === line.toLocationId &&
    scaled(outgoing.onHandDelta) === -one && scaled(incoming.onHandDelta) === one &&
    moves.every((move) => move.warehouseId === wave.warehouseId && move.batchId === line.batchId &&
      move.skuId === line.skuId && move.serials?.length === 1 && move.serials[0] === serial), '同一次确认必须恰好生成一出一入两条库存事实')
}
