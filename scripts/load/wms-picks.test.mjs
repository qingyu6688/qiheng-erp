import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  parseWmsFixture, readyWave, verifyWaveProgress, verifyWmsPosition, verifyWmsTrace, wmsConfirmation,
  wmsDimensions, wmsPosition,
} from './wms-picks.mjs'

function waveFixture(id, serialPrefix, storage = '500') {
  return {
    id, warehouseId: '100', version: 0, status: 'created', assigneeId: null,
    shipments: [1, 2].map((n) => ({ id: id + n, shipmentId: id + n + '0', waveId: id, warehouseId: '100',
      version: 1, status: 'created', assigneeId: null, shortPick: 0 })),
    lines: [1, 2].map((n) => ({ id: id + n + '5', taskId: id + n, shipmentId: id + n + '0', skuId: '700',
      batchId: '800', batchNo: '批次A', fromLocationId: storage, toLocationId: '501', fromLocationCode: 'ST-A',
      toLocationCode: 'SH-A', planQty: '1.000000', doneQty: '0.000000', serialNos: [serialPrefix + n], scannedSerialNos: [] })),
  }
}

test('夹具容量恰好覆盖所有迭代且波次不重复', () => {
  const valid = { dataset: 'test-hotspot', companyId: '1', mode: 'hotspot', warmWaveId: '12', waveIds: ['23', '34'] }
  assert.deepEqual(parseWmsFixture(JSON.stringify(valid), 2), valid)
  assert.throws(() => parseWmsFixture(JSON.stringify(valid), 3), /额定迭代数/)
  assert.throws(() => parseWmsFixture(JSON.stringify({ ...valid, waveIds: ['23', '12'] }), 2), /重复/)
  assert.throws(() => parseWmsFixture(JSON.stringify({ ...valid, companyId: 1 }), 2), /字符串/)
})

test('只接受未领取的真实批次序列与两张发货任务', () => {
  const input = waveFixture('12', 'SN')
  const wave = readyWave('12', input)
  assert.deepEqual(wmsConfirmation(wave.tasks[0]), { version: 2, fromLocationCode: 'L:ST-A', toLocationCode: 'L:SH-A',
    batchNo: '批次A', qty: '1', serialNos: ['SN1'] })
  input.lines[1].batchId = null
  assert.throws(() => readyWave('12', input), /批次/)
  input.status = 'done'
  assert.throws(() => readyWave('12', input), /尚未领取/)
})

test('领取、确认和完成必须由原领取人各推进一次', () => {
  const raw = waveFixture('12', 'SN'), expected = readyWave('12', raw)
  const conform = (state, progress) => ({
    ...raw, status: state, version: 1, assigneeId: '42',
    shipments: raw.shipments.map((item) => ({ ...item, status: state, version: item.version + progress, assigneeId: '42' })),
    lines: raw.lines.map((item) => ({ ...item, doneQty: progress > 1 ? '1.0' : '0.0', scannedSerialNos: progress > 1 ? item.serialNos : [] })),
  })
  verifyWaveProgress(expected, conform('assigned', 1), '42', 'claim')
  verifyWaveProgress(expected, conform('in_progress', 2), '42', 'confirm')
  verifyWaveProgress(expected, conform('done', 3), '42', 'finish')
  assert.throws(() => verifyWaveProgress(expected, conform('done', 4), '42', 'finish'), /版本/)
  assert.throws(() => verifyWaveProgress(expected, conform('done', 3), '43', 'finish'), /领取人/)
})

test('热点和分散维度来自真实的任务库存坐标，暖机单独计数', () => {
  const warm = readyWave('12', waveFixture('12', 'WARM'))
  const same = readyWave('23', waveFixture('23', 'HOT'))
  const scattered = readyWave('34', waveFixture('34', 'SPREAD', '502'))
  const hotspot = wmsDimensions([same], warm, 'hotspot')
  assert.equal(hotspot.sourceDimensions, 1)
  assert.deepEqual(hotspot.positions.map(({ delta }) => delta), [-2, 2])
  assert.equal(wmsDimensions([same, scattered], warm, 'spread').sourceDimensions, 2)
  assert.throws(() => wmsDimensions([same, scattered], warm, 'hotspot'), /坐标/)
  assert.throws(() => wmsDimensions([same], same, 'hotspot'), /同一序列号/)
})

test('库存对账按批次精确比较在手、发货占用和冻结数量', () => {
  const position = { skuId: '700', locationId: '500', batchId: '800', delta: -1 }
  const rows = (onHandQty, reservedQty, blockedQty = '0.25') => [{ skuId: '700', locationKey: '500', lotKey: '800', onHandQty, reservedQty, blockedQty }]
  const before = wmsPosition(position, rows('9007199254740993.000007', '2'))
  const after = wmsPosition(position, rows('9007199254740992.000007', '1'))
  verifyWmsPosition(position, before, after)
  assert.throws(() => verifyWmsPosition(position, before, { ...after, reserved: '2000000' }), /发货占用/)
  assert.throws(() => verifyWmsPosition(position, before, { ...after, blocked: '0' }), /冻结量/)
  assert.throws(() => wmsPosition(position, Array.from({ length: 200 }, () => rows('1','1')[0])), /截断/)
  assert.throws(() => wmsPosition(position, [...rows('1','1'), ...rows('1','1')]), /库存重复/)
})

test('序列号追溯拒绝重复移动和假平衡', () => {
  const wave = readyWave('12', waveFixture('12', 'SN')), task = wave.tasks[0]
  const identity = { serialNo: 'SN1', skuId: '700', batchId: '800', warehouseId: '100', locationKey: '501', status: 'reserved' }
  const shared = { sourceType: 'wms_task_confirmation', sourceId: task.id, sourceLineId: '134', skuId: '700', batchId: '800', warehouseId: '100', serials: ['SN1'] }
  const out = { ...shared, movementType: 'location_move_out', locationKey: '500', onHandDelta: '-1.000000' }
  const incoming = { ...shared, movementType: 'location_move_in', locationKey: '501', onHandDelta: '1.000000' }
  verifyWmsTrace(wave, task, { serial: identity, items: [out, incoming] })
  assert.throws(() => verifyWmsTrace(wave, task, { serial: identity, items: [out, incoming, out] }), /恰好/)
  assert.throws(() => verifyWmsTrace(wave, task, { serial: identity, items: [out, { ...incoming, sourceLineId: '999' }] }), /恰好/)
  assert.throws(() => verifyWmsTrace(wave, task, { serial: { ...identity, locationKey: '500' }, items: [out, incoming] }), /库位/)
})
