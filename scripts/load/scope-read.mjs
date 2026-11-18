const idPattern = /^[1-9][0-9]{0,18}$/
const datePattern = /^\d{4}-\d{2}-\d{2}$/
const orderKeys = ['visible', 'departmentDenied', 'warehouseDenied']

function required(condition, label) {
  if (!condition) throw new Error('部门范围容量夹具核验失败：' + label)
}

function validId(value) {
  return typeof value === 'string' && idPattern.test(value)
}

export function parseScopeFixture(text) {
  let fixture
  try {
    fixture = JSON.parse(text)
  } catch {
    throw new Error('部门范围容量夹具文件不是有效的 JSON')
  }
  required(fixture && typeof fixture === 'object', '夹具格式')
  required(typeof fixture.dataset === 'string' && /^[a-z0-9_-]{1,48}$/.test(fixture.dataset), '样本标识')
  required(typeof fixture.username === 'string' && /^load_[a-z0-9_]{1,26}$/.test(fixture.username), '专用只读用户')
  required(validId(fixture.companyId) && validId(fixture.warehouseId) && validId(fixture.skuId), '公司、仓库和商品 ID')
  required(
    typeof fixture.dateFrom === 'string' && typeof fixture.dateTo === 'string' &&
      datePattern.test(fixture.dateFrom) && datePattern.test(fixture.dateTo) && fixture.dateFrom <= fixture.dateTo,
    '业务日期范围',
  )
  const orders = orderKeys.map((key) => fixture.orders?.[key])
  for (const order of orders) {
    required(
      order && validId(order.id) && validId(order.shipmentLineId) &&
        typeof order.documentNo === 'string' && /^[a-zA-Z0-9_-]{1,60}$/.test(order.documentNo),
      '销售订单与签收行证据',
    )
  }
  for (const key of ['id', 'documentNo', 'shipmentLineId']) {
    required(new Set(orders.map((order) => order[key])).size === orders.length, '正反例必须分别对应不同事实')
  }
  return fixture
}

export function scopeRequests(fixture) {
  const list = '/document-browser/sales-orders'
  const detail = '/sales-orders/' + fixture.orders.visible.id
  const report = '/reports/sales-summary'
  const dates = 'dateFrom=' + fixture.dateFrom + '&dateTo=' + fixture.dateTo
  return [
    { path: list + '?' + dates + '&limit=20&withTotal=true', operation: list },
    { path: detail, operation: '/sales-orders/:id' },
    { path: report + '?' + dates + '&detail=true&skuId=' + fixture.skuId + '&limit=20', operation: report },
  ]
}

function hasRow(result, key, value) {
  return result.status === 200 && Array.isArray(result.data?.items) &&
    result.data.items.some((item) => item[key] === value)
}

function detailMatches(result, order) {
  return result.status === 200 && result.data?.id === order.id &&
    result.data?.documentNo === order.documentNo && Array.isArray(result.data?.lines) && result.data.lines.length > 0
}

export function verifyScopeProbe(sample, read) {
  const { fixture, restricted, control, sameTenantCompany, foreignTenantCompany } = sample
  const requests = scopeRequests(fixture)
  const actorList = read(restricted, requests[0].path)
  const actorReport = read(restricted, requests[2].path)
  const controlReport = read(control, requests[2].path)
  const controlOrders = orderKeys.map((key) => {
    const order = fixture.orders[key]
    const path = '/sales-orders/' + order.id
    const result = read(control, path)
    required(detailMatches(result, order), '对照账号可读匹配的销售订单详情')
    required(hasRow(controlReport, 'sourceLineId', order.shipmentLineId), '对照账号首页报表有签收行')
    const fact = controlReport.data.items.find((item) => item.sourceLineId === order.shipmentLineId)
    required(
      fact.sourceType === 'shipment' && validId(fact.sourceId) &&
        fact.departmentId === result.data.ownerDepartmentId &&
        fact.skuId === fixture.skuId && result.data.lines.some((line) => line.warehouseId === fact.warehouseId),
      '报表事实与订单部门、仓库和测试商品相符',
    )
    const shipment = read(control, '/shipments/' + fact.sourceId)
    required(
      shipment.status === 200 && Array.isArray(shipment.data?.lines) &&
        shipment.data.lines.some((line) => line.id === order.shipmentLineId && line.orderNo === order.documentNo),
      '报表签收行实际关联样本订单',
    )
    const keyword = '/document-browser/sales-orders?limit=20&keyword=' + encodeURIComponent(order.documentNo)
    required(hasRow(read(control, keyword), 'id', order.id), '对照账号列表有匹配的销售订单')
    if (key === 'visible') {
      required(detailMatches(read(restricted, path), order), '受限账号有可见详情')
      required(hasRow(actorList, 'id', order.id), '受限账号首页有可见订单')
      required(hasRow(read(restricted, keyword), 'id', order.id), '受限账号关键词列表有可见订单')
      required(hasRow(actorReport, 'sourceLineId', order.shipmentLineId), '受限账号首页报表有可见事实')
    } else {
      required(read(restricted, path).status === 404, '范围外详情返回 404')
      const hiddenList = read(restricted, keyword)
      required(
        hiddenList.status === 200 && hiddenList.data?.total === 0 &&
          Array.isArray(hiddenList.data.items) && hiddenList.data.items.length === 0 && !hasRow(actorList, 'id', order.id),
        '范围外订单不出现在列表和关键词统计',
      )
      required(!hasRow(actorReport, 'sourceLineId', order.shipmentLineId), '范围外签收行不出现在报表')
    }
    return result.data
  })
  const [visible, departmentDenied, warehouseDenied] = controlOrders
  const atWarehouse = (order) => order.lines.every((line) => line.warehouseId === fixture.warehouseId)
  required(
    validId(visible.ownerDepartmentId) && validId(departmentDenied.ownerDepartmentId) &&
      visible.ownerDepartmentId !== departmentDenied.ownerDepartmentId && atWarehouse(visible) && atWarehouse(departmentDenied),
    '部门反例与正例须在同一个已授权仓库并归属不同部门',
  )
  required(
    warehouseDenied.ownerDepartmentId === visible.ownerDepartmentId &&
      warehouseDenied.lines.every((line) => validId(line.warehouseId) && line.warehouseId !== fixture.warehouseId),
    '仓库反例与正例须归属同一部门且无任何已授权仓库明细',
  )
  for (const companyId of [sameTenantCompany, foreignTenantCompany]) {
    required(read({ ...restricted, companyId }, requests[1].path).status === 403, '切换越权公司或租户返回 403')
  }
}
