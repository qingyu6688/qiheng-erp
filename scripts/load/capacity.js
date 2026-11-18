import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { parseScopeFixture, scopeRequests, verifyScopeProbe } from './scope-read.mjs';
import { parseWorkflowSalesFixture, parseWorkflowSalesSnapshot, pendingWorkflowSalesTask, verifyApprovedWorkflowSales, verifyWorkflowSalesIsolation, verifyWorkflowSalesModel, workflowSalesCount } from './workflow-sales.mjs';
import { parseWmsFixture, readyWave, verifyWaveProgress, verifyWmsPosition, verifyWmsTrace, wmsConfirmation, wmsDimensions, wmsPosition } from './wms-picks.mjs';

const base = __ENV.ERP_LOAD_URL || 'http://127.0.0.1:19080';
if (!/^http:\/\/(127\.0\.0\.1|localhost):19080$/.test(base) || !/^[a-f0-9]{12}$/.test(__ENV.ERP_LOAD_RUN_ID || '') || !__ENV.ERP_LOAD_PASSWORD || !__ENV.ERP_LOAD_KEY) throw new Error('容量脚本仅允许本机 19080、随机运行编号和临时凭据');
const scenario = __ENV.SCENARIO || 'query', rate = Number(__ENV.RATE || (['write', 'workflow-sales'].includes(scenario) ? 2 : 20)), duration = __ENV.DURATION || '30s';
if (scenario === 'scope-read' && !__ENV.ERP_LOAD_SCOPE_FILE) throw new Error('部门范围容量场景需要提供 ScopeFixture');
const scopeFixture = scenario === 'scope-read' ? parseScopeFixture(open(__ENV.ERP_LOAD_SCOPE_FILE)) : null;
if (scenario === 'workflow-sales' && !__ENV.ERP_LOAD_WORKFLOW_FILE) throw new Error('销售审批容量场景需要提供 WorkflowFixture');
const workflowSalesFixture = scenario === 'workflow-sales' ? parseWorkflowSalesFixture(open(__ENV.ERP_LOAD_WORKFLOW_FILE)) : null;
const workflowSalesLines = Number(__ENV.LINES || 1);
if (workflowSalesFixture && ![1, 20, 100].includes(workflowSalesLines)) throw new Error('销售审批容量场景仅支持 1、20 或 100 行订单');
const workflowSalesTarget = workflowSalesFixture ? workflowSalesCount(rate, duration) : 0;
const wmsDuration = /^([1-9][0-9]*)(s|m|h)$/.exec(duration);
const wmsTarget = scenario === 'wms-picks' && wmsDuration ? rate * Number(wmsDuration[1]) * { s: 1, m: 60, h: 3600 }[wmsDuration[2]] : 0;
if (scenario === 'wms-picks' && (!__ENV.ERP_LOAD_WMS_FILE || !Number.isInteger(rate) || rate < 1 || rate > 200 ||
  !Number.isSafeInteger(wmsTarget) || wmsTarget < 1)) throw new Error('波次拣货容量场景需要 WmsFixture、每秒 1 至 200 波次和整数时长');
const wmsFixture = scenario === 'wms-picks' ? parseWmsFixture(open(__ENV.ERP_LOAD_WMS_FILE), wmsTarget) : null;
const hotPreconnect = scenario === 'hotspot' && __ENV.ERP_LOAD_HOT_PRECONNECT === 'true';
const systemError = new Rate('system_error'), businessRejected = new Counter('business_rejected'), committed = new Counter('committed_operations'), hotAccepted = new Counter('hot_accepted'), refundAccepted = new Counter('refund_accepted'), invariantFailures = new Counter('invariant_failures');
const mixedInboundCompleted = new Counter('mixed_inbound_completed'), mixedRefundCompleted = new Counter('mixed_refund_completed'), exportsAccepted = new Counter('exports_accepted');
const preconnectError = new Rate('preconnect_error'), preconnectTime = new Trend('preconnect_duration', true), preconnected = new Counter('preconnected_clients'), preconnectLate = new Counter('preconnect_late'), hotRequests = new Counter('hot_requests'), hotReused = new Counter('hot_reused_connections'), hotDispatchDelay = new Trend('hot_dispatch_delay', true);
const hotCold = new Counter('hot_new_connections');
const readTime = new Trend('read_duration', true), writeTime = new Trend('write_duration', true), workflowTime = new Trend('workflow_duration', true);
const scopedReads = new Counter('scoped_reads');
const workflowSalesWrites = new Trend('workflow_sales_write_duration', true);
const workflowSalesCompleted = new Counter('workflow_sales_completed');
const wmsWriteTime = new Trend('wms_pick_write_duration', true);
const wmsWavesCompleted = new Counter('wms_waves_completed'), wmsPicksConfirmed = new Counter('wms_picks_confirmed');
const wmsBoundarySlots = new Counter('wms_boundary_slots');
const arrival = { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs: Math.min(100, Math.max(10, rate)), maxVUs: Number(__ENV.MAX_VUS || 250), gracefulStop: '60s' };
// 按查询延迟上限预先准备客户端，避免计时开始后临时初始化 VU 造成调度丢弃。
const queryLatencyBudgetSeconds = 0.5;
const configuredQueryVus = Number(__ENV.ERP_LOAD_QUERY_VUS || 0);
if (!Number.isInteger(configuredQueryVus) || configuredQueryVus < 0 || configuredQueryVus > 2000) {
  throw new Error('查询预分配 VU 必须在 0 至 2000 之间');
}
const queryVus = Math.max(arrival.preAllocatedVUs, Math.ceil(rate * queryLatencyBudgetSeconds), configuredQueryVus);
const definitions = {
  query: { ...arrival, preAllocatedVUs: queryVus, maxVUs: Math.max(queryVus, arrival.maxVUs), exec: 'query' },
  'scope-read': { ...arrival, preAllocatedVUs: queryVus, maxVUs: Math.max(queryVus, arrival.maxVUs), exec: 'scopeRead' },
  write: { ...arrival, exec: 'write' },
  'workflow-sales': { ...arrival, exec: 'workflowSales' },
  'wms-picks': { ...arrival, exec: 'wmsPicks' },
  hotspot: { executor: 'per-vu-iterations', vus: 1000, iterations: 1, maxDuration: '4m', exec: 'hotspot' },
  duplicate: { executor: 'per-vu-iterations', vus: 100, iterations: 1, maxDuration: '3m', exec: 'duplicate' },
  refund: { executor: 'per-vu-iterations', vus: 100, iterations: 1, maxDuration: '3m', exec: 'refund' },
  recovery: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m', exec: 'recover' },
  mixed: { ...arrival, exec: 'mixed' },
  burst: { executor: 'ramping-arrival-rate', startRate: rate, timeUnit: '1s', preAllocatedVUs: Math.min(100, Math.max(10, rate)), maxVUs: Number(__ENV.MAX_VUS || 250), stages: [{ duration: '30s', target: rate }, { duration: '15s', target: rate * 3 }, { duration: '30s', target: rate }], gracefulStop: '60s', exec: 'mixed' },
};
if (!definitions[scenario]) throw new Error('容量场景无效');
const thresholds = { system_error: ['rate<0.01'], invariant_failures: ['count==0'], checks: ['rate==1'] };
if (['query', 'scope-read', 'write', 'workflow-sales', 'wms-picks', 'mixed', 'burst'].includes(scenario)) thresholds.dropped_iterations = ['count==0'];
if (['query', 'scope-read'].includes(scenario) && duration === '30s') thresholds.iterations = ['count>=' + rate * 30];
if (['query', 'mixed', 'burst'].includes(scenario)) {
  thresholds.read_duration = ['p(95)<500'];
  for (const operation of ['/sales-orders', '/purchase-orders', '/inventory-balances', '/skus', '/document-browser/sales-orders', '/document-browser/purchase-orders']) thresholds['read_duration{operation:' + operation + '}'] = ['p(95)<500'];
}
if (scopeFixture) {
  for (const { operation } of scopeRequests(scopeFixture)) {
    thresholds['read_duration{operation:' + operation + '}'] = ['p(95)<500'];
    thresholds['scoped_reads{operation:' + operation + '}'] = ['count>0'];
  }
}
if (workflowSalesFixture) {
  thresholds.workflow_sales_completed = ['count==' + workflowSalesTarget];
  thresholds['workflow_sales_write_duration{operation:/sales-orders/:id/submit}'] = ['p(95)<1000'];
  thresholds['workflow_sales_write_duration{operation:/workflow/tasks/:id/approve}'] = ['p(95)<1000'];
}
if (wmsFixture) {
  thresholds.wms_waves_completed = ['count==' + wmsTarget];
  thresholds.wms_picks_confirmed = ['count==' + 2 * wmsTarget];
  thresholds.wms_boundary_slots = ['count<=1'];
  for (const operation of ['/wms/waves/:id/claim', '/wms/picks/:id/lines/:id/confirm', '/wms/picks/:id/finish'])
    thresholds['wms_pick_write_duration{operation:' + operation + '}'] = ['p(95)<1000'];
}
if (['write', 'mixed', 'burst'].includes(scenario)) {
  thresholds.write_duration = ['p(95)<1000'];
  for (const operation of ['/sales-orders', '/sales-orders/:id/submit', '/sales-orders/:id/approve', '/shipments', '/shipments/:id/submit', '/shipments/:id/post', '/receipts', '/receipts/:id/submit', '/receipts/:id/confirm']) thresholds['write_duration{operation:' + operation + '}'] = ['p(95)<1000'];
}
if (['mixed', 'burst'].includes(scenario)) {
  thresholds.mixed_inbound_completed = ['count>0']; thresholds.mixed_refund_completed = ['count>0']; thresholds.exports_accepted = ['count>0'];
  for (const operation of ['/capacity/mixed-inbound', '/refund-requests', '/refund-requests/:id/submit', '/refund-requests/:id/approve', '/refund-requests/:id/register-paid']) thresholds['write_duration{operation:' + operation + '}'] = ['p(95)<1000'];
}
if (scenario === 'hotspot') { thresholds.hot_accepted = ['count==100']; thresholds.hot_requests = ['count==1000']; }
if (scenario === 'hotspot' && !hotPreconnect) { thresholds.hot_new_connections = ['count==1000']; thresholds.system_error = ['rate==0']; }
if (hotPreconnect) { thresholds.preconnect_error = ['rate==0']; thresholds.preconnect_late = ['count==0']; thresholds.preconnected_clients = ['count==1000']; thresholds.hot_reused_connections = ['count==1000']; }
if (scenario === 'refund') thresholds.refund_accepted = ['count==50'];
export const options = { scenarios: { [scenario]: definitions[scenario] }, thresholds, noConnectionReuse: scenario === 'hotspot' && !hotPreconnect, summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'], setupTimeout: wmsFixture ? '5m' : '60s', teardownTimeout: wmsFixture ? '5m' : '2m', discardResponseBodies: false };

function body(response) { try { return response.json(); } catch { return null; } }
function login(code, username = 'operator') {
  const jar = http.cookieJar(); jar.clear(base);
  const response = http.post(base + '/api/v1/auth/login', JSON.stringify({ tenantCode: code, username, password: __ENV.ERP_LOAD_PASSWORD }), { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'setup' } });
  if (response.status !== 200) throw new Error('容量临时账号登录失败：' + code);
  const me = http.get(base + '/api/v1/auth/me', { tags: { phase: 'setup' } });
  if (me.status !== 200) throw new Error('容量临时账号资料加载失败');
  const { id, companies, permissions } = body(me).data;
  return { cookies: jar.cookiesForURL(base), companies, permissions, id };
}
function headers(context) { return { 'Content-Type': 'application/json', 'X-Company-Id': context.companyId, 'X-Load-Key': __ENV.ERP_LOAD_KEY, 'X-XSRF-TOKEN': context.cookies['XSRF-TOKEN'][0], Cookie: Object.entries(context.cookies).map(([name, values]) => name + '=' + values[0]).join('; ') }; }
function request(context, method, path, data, key, timing, phase) {
  http.cookieJar().clear(base);
  const operation = path.split('?')[0].replace(/\/[0-9]+/g, '/:id');
  const start = Date.now(), response = http.request(method, base + '/api/v1' + path, data === undefined ? null : JSON.stringify(data), { headers: { ...headers(context), ...(key ? { 'Idempotency-Key': key } : {}) }, timeout: '60s', tags: { operation, scenario, ...(phase ? { phase } : {}) } });
  const parsed = body(response), rejected = response.status === 422 && ['INVENTORY_INSUFFICIENT', 'REFUND_AMOUNT_EXCEEDED', 'SETTLEMENT_NOT_ALLOWED'].includes(parsed && parsed.errorCode);
  systemError.add(response.status >= 500 || response.status === 0 || (!rejected && response.status >= 400));
  if (rejected) businessRejected.add(1);
  if (timing) timing.add(Date.now() - start, { operation });
  return { response, data: parsed && parsed.data, rejected, retryable: parsed && parsed.details && parsed.details.retryable === true, errorCode: parsed && parsed.errorCode, message: parsed && parsed.message };
}
export function setup() {
  invariantFailures.add(0); hotAccepted.add(0); refundAccepted.add(0); committed.add(0); businessRejected.add(0); mixedInboundCompleted.add(0); mixedRefundCompleted.add(0); exportsAccepted.add(0);
  preconnected.add(0); preconnectLate.add(0); hotRequests.add(0); hotReused.add(0);
  hotCold.add(0);
  const first = login('LOAD-' + __ENV.ERP_LOAD_RUN_ID + '-0');
  const initial = { companyId: first.companies[0].id, cookies: first.cookies };
  const manifest = request(initial, 'GET', '/capacity/manifest');
  if (manifest.response.status !== 200) throw new Error('容量夹具元数据加载失败');
  if (scopeFixture) return prepareScopeRead(manifest.data, first);
  const second = login('LOAD-' + __ENV.ERP_LOAD_RUN_ID + '-1');
  const reviewers = [login('LOAD-' + __ENV.ERP_LOAD_RUN_ID + '-0', 'reviewer'), login('LOAD-' + __ENV.ERP_LOAD_RUN_ID + '-1', 'reviewer')];
  const contexts = manifest.data.map(context => ({ ...context, cookies: context.tenantCode.endsWith('-0') ? first.cookies : second.cookies, reviewerCookies: reviewers[context.tenantCode.endsWith('-0') ? 0 : 1].cookies }));
  if (workflowSalesFixture) return prepareWorkflowSales(contexts, [first, second], reviewers);
  if (wmsFixture) return prepareWmsPicks(contexts, [first, second], reviewers);
  for (const c of contexts) {
    const diagnostic = request(c, 'GET', '/capacity/diagnostics');
    if (diagnostic.response.status !== 200) throw new Error('容量执行计划读取失败');
    console.log(JSON.stringify({ phase: 'diagnostics', company: c.companyId, ...diagnostic.data }));
    const paths = queryPaths(c);
    for (const path of paths) {
      const result = request(c, 'GET', path);
      if (result.response.status !== 200 || !Array.isArray(result.data?.items)) {
        throw new Error('容量查询温启动失败：' + path.split('?')[0]);
      }
    }
    if (scenario === 'query') {
      c.queryHeaders = headers(c);
      c.queryRequests = paths.map(path => ({ path, operation: path.split('?')[0] }));
    }
  }
  if (hotPreconnect) { const barrier = Date.now() + 10000; for (const c of contexts) c.hotStartAt = barrier; }
  return contexts;
}
function context(data) { return data[exec.vu.idInTest % data.length]; }
function queryPaths(c) {
  const from = c.businessDate.substring(0, 8) + '01';
  return ['/sales-orders?limit=20', '/purchase-orders?limit=20', '/inventory-balances?limit=20', '/skus?keyword=LOAD-199&limit=20', '/document-browser/sales-orders?limit=20&status=approved&dateFrom=' + from + '&dateTo=' + c.businessDate, '/document-browser/purchase-orders?limit=20&status=cancelled&dateFrom=' + from + '&dateTo=' + c.businessDate];
}
export function query(data) {
  const c = context(data);
  measureRead(c, c.queryRequests);
}

function scopeProbeRead(actor, path) {
  http.cookieJar().clear(base);
  const response = http.get(base + '/api/v1' + path, {
    headers: headers(actor), timeout: '60s', tags: { phase: 'scope_probe', scenario },
  });
  return { status: response.status, data: body(response)?.data };
}

function prepareScopeRead(contexts, first) {
  const sample = contexts.find(({ companyId }) => companyId === scopeFixture.companyId);
  if (!sample || ![sample.warehouseId, sample.hotWarehouseId].includes(scopeFixture.warehouseId)) {
    throw new Error('部门范围容量库未包含该公司、仓库和验证快照');
  }
  const limited = login(sample.tenantCode, scopeFixture.username);
  const company = limited.companies[0];
  if (limited.companies.length !== 1 || company.id !== sample.companyId || company.allWarehouses ||
    company.warehouseIds.length !== 1 || !company.warehouseIds.includes(scopeFixture.warehouseId) ||
    !['sales_order:view', 'report:sales_summary'].every(permission => limited.permissions.includes(permission))) {
    throw new Error('部门范围容量账号必须仅可读指定公司、仓库和场景接口');
  }
  const sameTenant = contexts.find(({ tenantCode, companyId }) => tenantCode === sample.tenantCode && companyId !== sample.companyId);
  const foreignTenant = contexts.find(({ tenantCode }) => tenantCode !== sample.tenantCode);
  if (!sameTenant || !foreignTenant) throw new Error('部门范围容量库缺少同租户公司或外租户对照');
  const operator = sample.tenantCode.endsWith('-0') ? first : login(sample.tenantCode);
  const probe = {
    fixture: scopeFixture,
    control: { companyId: sample.companyId, cookies: operator.cookies },
    restricted: { companyId: sample.companyId, cookies: limited.cookies },
    sameTenantCompany: sameTenant.companyId,
    foreignTenantCompany: foreignTenant.companyId,
  };
  verifyScopeProbe(probe, scopeProbeRead);
  console.info(JSON.stringify({ phase: 'scope_probe', dataset: scopeFixture.dataset, result: 'passed' }));
  return { ...probe, queryHeaders: headers(probe.restricted), queryRequests: scopeRequests(scopeFixture) };
}

export function scopeRead(data) {
  measureRead(data, data.queryRequests);
}

function measureRead(c, requests) {
  const { path, operation } = requests[exec.scenario.iterationInTest % requests.length];
  http.cookieJar().clear(base);
  const start = Date.now();
  // 温启动已校验各公司、各路径的 JSON。计时阶段完整接收响应，不在同机重复解析并保留 JSON。
  const response = http.get(base + '/api/v1' + path, {
    headers: c.queryHeaders, timeout: '60s', responseType: 'none', tags: { operation, scenario },
  });
  readTime.add(Date.now() - start, { operation });
  if (scopeFixture && response.status === 200) scopedReads.add(1, { operation });
  systemError.add(response.status === 0 || response.status >= 400);
  check(response, { '查询成功': value => value.status === 200 });
}

function workflowSalesData(actor, method, path, expected, phase, data, key, timing) {
  const result = request(actor, method, path, data, key, timing, phase);
  if (result.response.status !== expected || !result.data) {
    throw new Error('销售审批请求失败：' + method + ' ' + path.replace(/\/[0-9]+/g, '/:id') + '，HTTP ' + result.response.status + ' ' + (result.errorCode || ''));
  }
  return result.data;
}

function workflowProbe(key) {
  return (actor, method, path, input, suffix) => {
    http.cookieJar().clear(base);
    const response = http.request(method, base + '/api/v1' + path, input ? JSON.stringify(input) : null, {
      headers: { ...headers(actor), ...(suffix ? { 'Idempotency-Key': key + suffix } : {}) },
      timeout: '60s', tags: { phase: 'workflow_isolation', scenario },
    });
    return response.status;
  };
}

function prepareWorkflowSales(contexts, makers, reviewers) {
  workflowSalesCompleted.add(0);
  const actor = contexts.find((item) => item.companyId === workflowSalesFixture.companyId);
  if (!actor || actor.skuIds.length < workflowSalesLines) throw new Error('销售审批容量库缺少目标公司或订单商品');
  const sameTenant = contexts.find((item) => item.tenantCode === actor.tenantCode && item.companyId !== actor.companyId);
  const foreignTenant = contexts.find((item) => item.tenantCode !== actor.tenantCode);
  if (!sameTenant || !foreignTenant) throw new Error('销售审批容量库缺少异公司或异租户对照');
  const tenantIndex = actor.tenantCode.endsWith('-0') ? 0 : 1;
  const sample = {
    fixture: workflowSalesFixture,
    actor,
    reviewer: { ...actor, cookies: actor.reviewerCookies },
    makerId: makers[tenantIndex].id,
    reviewerId: reviewers[tenantIndex].id,
    sameTenant: { ...sameTenant, cookies: sameTenant.reviewerCookies },
    foreignTenant: { ...foreignTenant, cookies: foreignTenant.reviewerCookies },
  };
  const diagnostic = workflowSalesData(actor, 'GET', '/capacity/diagnostics', 200, 'workflow_setup');
  console.info(JSON.stringify({ phase: 'diagnostics', company: actor.companyId, ...diagnostic }));
  const modelPath = '/workflow/models/' + workflowSalesFixture.modelId;
  const model = workflowSalesData(actor, 'GET', modelPath, 200, 'workflow_setup');
  const snapshot = request(actor, 'GET', modelPath + '/versions/' + workflowSalesFixture.modelVersion, undefined, undefined, undefined, 'workflow_setup');
  if (snapshot.response.status !== 200) throw new Error('销售审批发布快照读取失败');
  const published = parseWorkflowSalesSnapshot(snapshot.response.body);
  const bindings = workflowSalesData(actor, 'GET', '/workflow/bindings', 200, 'workflow_setup');
  verifyWorkflowSalesModel(sample, model, published, bindings);
  sample.warm = workflowSalesOrder(sample, 'warm', 0);
  console.info(JSON.stringify({ phase: 'workflow_preflight', dataset: workflowSalesFixture.dataset, company: actor.companyId,
    modelVersion: published.versionNo, contentHash: published.contentHash, order: sample.warm.order.id, result: 'passed' }));
  return { sample, contexts };
}

function workflowSalesOrder(sample, iteration, slot) {
  const { actor, reviewer } = sample;
  const warm = iteration === 'warm';
  const phase = warm ? 'workflow_warmup' : 'workflow_measured';
  const timing = warm ? undefined : workflowSalesWrites;
  const marker = [__ENV.ERP_LOAD_RUN_ID, __ENV.ERP_LOAD_PHASE || 'baseline', rate, workflowSalesLines, iteration].join('_');
  const key = 'wfs_' + marker + '_';
  const lines = Array.from({ length: workflowSalesLines }, (_, index) => ({
    skuId: actor.skuIds[(slot + index) % actor.skuIds.length], warehouseId: actor.warehouseId, unitId: actor.unitId,
    qty: '1', unitPrice: '10', lineDiscountAmount: '0', taxRate: '0',
  }));
  const created = workflowSalesData(actor, 'POST', '/sales-orders', 201, phase, {
    customerId: actor.customerId, businessDate: actor.businessDate, priceTaxInclusive: true,
    orderDiscountAmount: '0', lines, fees: [], remark: '容量审批 ' + marker,
  }, key + 'create');
  const path = '/sales-orders/' + created.id;
  workflowSalesData(actor, 'POST', path + '/submit', 200, phase, { version: created.version }, key + 'submit', timing);
  const pending = workflowSalesData(actor, 'GET', path, 200, phase);
  if (!pending.workflowInstanceId) throw new Error('销售订单提交后未生成流程实例');
  const flowPath = '/workflow/instances/' + pending.workflowInstanceId;
  const running = workflowSalesData(reviewer, 'GET', flowPath, 200, phase);
  const task = pendingWorkflowSalesTask(sample, pending, running);
  if (warm) verifyWorkflowSalesIsolation(sample, pending, running, workflowProbe(key), task);
  const approvalPath = '/workflow/tasks/' + task.id + '/approve';
  const decision = { version: task.version, comment: '容量审批复核' };
  const approved = workflowSalesData(reviewer, 'POST', approvalPath, 200, phase, decision, key + 'approve', timing);
  if (approved.id !== task.id || approved.instanceId !== running.id || approved.status !== 'approved') throw new Error('审批命令未通过指定任务');
  if (warm) {
    const replay = workflowSalesData(reviewer, 'POST', approvalPath, 200, phase, decision, key + 'approve');
    if (replay.id !== approved.id || replay.instanceId !== approved.instanceId || replay.status !== approved.status) {
      throw new Error('销售审批使用原键重试时未返回原结果');
    }
  }
  const order = workflowSalesData(actor, 'GET', path, 200, phase);
  const instance = workflowSalesData(reviewer, 'GET', flowPath, 200, phase);
  verifyApprovedWorkflowSales(sample, order, instance, task, workflowSalesLines);
  return { order: { id: order.id }, instance: { id: instance.id } };
}

export function workflowSales(data) {
  let passed = false;
  try {
    workflowSalesOrder(data.sample, exec.scenario.iterationInTest, exec.vu.idInTest);
    workflowSalesCompleted.add(1);
    passed = true;
  } catch (error) {
    invariantFailures.add(1);
    console.error(JSON.stringify({ phase: 'workflow_iteration_failed', iteration: exec.scenario.iterationInTest, message: error.message }));
  }
  check(passed, { '销售提交和流程审批事务及预占一致': (value) => value });
}

function wmsData(actor, method, path, phase, input, key, timing) {
  const result = request(actor, method, path, input, key, undefined, phase);
  const operation = path.split('?')[0].replace(/\/[0-9]+/g, '/:id');
  if (timing) timing.add(result.response.timings.duration, { operation });
  if (result.response.status !== 200 || !result.data) {
    throw new Error('波次拣货请求失败：' + method + ' ' + operation + '，HTTP ' + result.response.status);
  }
  return result.data;
}

function wmsPickPath(task) { return '/wms/picks/' + task.id + '/lines/' + task.line.id + '/confirm'; }

function wmsWriteKey(slot) {
  return [__ENV.ERP_LOAD_RUN_ID, __ENV.ERP_LOAD_PHASE || 'baseline', wmsFixture.mode, 'wms', slot].join('_');
}

function wmsConfirmPair(actor, wave, key, phase, timed) {
  http.cookieJar().clear(base);
  const operation = '/wms/picks/:id/lines/:id/confirm';
  const responses = http.batch(wave.tasks.map((task, index) => ({
    method: 'POST', url: base + '/api/v1' + wmsPickPath(task), body: JSON.stringify(wmsConfirmation(task)),
    params: { headers: { ...headers(actor), 'Idempotency-Key': key + '_pick_' + index }, timeout: '60s',
      tags: { operation, scenario, phase }, },
  })));
  let confirmedCount = 0;
  for (const [index, response] of responses.entries()) {
    const passed = response.status === 200 && body(response)?.data?.id === wave.tasks[index].id;
    systemError.add(!passed);
    if (timed) wmsWriteTime.add(response.timings.duration, { operation });
    if (passed) confirmedCount++;
  }
  if (confirmedCount !== wave.tasks.length) throw new Error('并发 PDA 确认未全部成功');
}

function wmsReadWave(actor, id, phase) { return wmsData(actor, 'GET', '/wms/waves/' + id, phase); }

function wmsPositionRead(actor, position, phase) {
  const query = '?locationId=' + position.locationId + '&skuId=' + position.skuId + '&limit=200';
  return wmsPosition(position, wmsData(actor, 'GET', '/warehouses/' + position.warehouseId + '/locations/balances' + query, phase));
}

function wmsVerifyTrace(sample, wave) {
  for (const task of wave.tasks) {
    const query = '?skuId=' + task.line.skuId + '&serialNo=' + encodeURIComponent(task.line.serialNos[0]);
    verifyWmsTrace(wave, task, wmsData(sample.actor, 'GET', '/serials/trace' + query, 'wms_verify'));
  }
}

function wmsProbeIsolation(sample, wave, key, readOnly = false) {
  const task = wave.tasks[0];
  const path = '/wms/waves/' + wave.id;
  const probe = (actor, method, url, input, name) => {
    http.cookieJar().clear(base);
    const response = http.request(method, base + '/api/v1' + url, input ? JSON.stringify(input) : null, {
      headers: { ...headers(actor), ...(input ? { 'Idempotency-Key': key + '_' + name } : {}) },
      timeout: '60s', tags: { scenario, phase: 'wms_isolation' },
    });
    return response.status;
  };
  if (probe(sample.otherCompany, 'GET', path) !== 404 || probe(sample.otherTenant, 'GET', path) !== 404 ||
      probe(sample.reviewer, 'GET', path) !== 200) throw new Error('波次拣货读取隔离校验失败');
  if (readOnly) return;
  const input = wmsConfirmation(task), confirm = wmsPickPath(task);
  if (probe(sample.otherCompany, 'POST', confirm, input, 'foreign_company') !== 404 ||
      probe(sample.otherTenant, 'POST', confirm, input, 'foreign_tenant') !== 404 ||
      probe(sample.reviewer, 'POST', confirm, input, 'reviewer') !== 403) throw new Error('波次拣货写入隔离或领取人校验失败');
}

function wmsPickWave(sample, wave, key, timed) {
  const phase = timed ? 'wms_timed' : 'wms_warmup';
  const timing = timed ? wmsWriteTime : undefined;
  if (wmsData(sample.actor, 'POST', '/wms/waves/' + wave.id + '/claim', phase, { version: wave.version }, key + '_claim', timing).id !== wave.id)
    throw new Error('返回了错误的波次编号');
  verifyWaveProgress(wave, wmsReadWave(sample.actor, wave.id, 'wms_verify'), sample.actorId, 'claim');
  if (!timed) wmsProbeIsolation(sample, wave, key);
  wmsConfirmPair(sample.actor, wave, key, phase, timed);
  if (!timed) wmsConfirmPair(sample.actor, wave, key, 'wms_idempotent_replay', false);
  verifyWaveProgress(wave, wmsReadWave(sample.actor, wave.id, 'wms_verify'), sample.actorId, 'confirm');
  for (const [index, task] of wave.tasks.entries()) {
    if (wmsData(sample.actor, 'POST', '/wms/picks/' + task.id + '/finish', phase, { version: task.version + 2 }, key + '_finish_' + index, timing).id !== task.id)
      throw new Error('返回了错误的拣货任务编号');
  }
  verifyWaveProgress(wave, wmsReadWave(sample.actor, wave.id, 'wms_verify'), sample.actorId, 'finish');
  wmsVerifyTrace(sample, wave);
}

function prepareWmsPicks(contexts, makers, reviewers) {
  wmsWavesCompleted.add(0); wmsPicksConfirmed.add(0); wmsBoundarySlots.add(0);
  const actor = contexts.find((item) => item.companyId === wmsFixture.companyId);
  if (!actor) throw new Error('容量夹具缺少给定波次公司');
  const otherCompany = contexts.find((item) => item.tenantCode === actor.tenantCode && item.companyId !== actor.companyId);
  const otherTenant = contexts.find((item) => item.tenantCode !== actor.tenantCode);
  const index = actor.tenantCode.endsWith('-0') ? 0 : 1;
  const needed = ['wms_wave:manage', 'wms_wave:view', 'wms_task:view', 'wms_task:execute', 'serial:trace',
    'inventory:view', 'wms_location:view', 'warehouse:view'];
  if (!otherCompany || !otherTenant || makers[index].id === reviewers[index].id ||
      !needed.every((permission) => makers[index].permissions.includes(permission)) ||
      !['wms_wave:view', 'wms_task:view', 'wms_task:execute'].every((permission) => reviewers[index].permissions.includes(permission))) {
    throw new Error('波次拣货容量夹具缺少权限或隔离对照账号');
  }
  const sample = { actor, actorId: makers[index].id, reviewer: { ...actor, cookies: actor.reviewerCookies }, otherCompany, otherTenant };
  const prepare = (id) => readyWave(id, wmsReadWave(actor, id, 'wms_setup'));
  const warm = prepare(wmsFixture.warmWaveId), waves = wmsFixture.waveIds.map(prepare);
  const { positions, sourceDimensions } = wmsDimensions(waves, warm, wmsFixture.mode);
  wmsPickWave(sample, warm, wmsWriteKey('warm'), false);
  const baselines = positions.map((position) => ({ position, balance: wmsPositionRead(actor, position, 'wms_before') }));
  console.info(JSON.stringify({ phase: 'wms_preflight', dataset: wmsFixture.dataset, mode: wmsFixture.mode,
    sourceDimensions, stockPositions: baselines.length, waveCount: waves.length, result: 'passed' }));
  return { sample, warm, waves, baselines, contexts };
}

export function wmsPicks(data) {
  const slot = exec.scenario.iterationInTest;
  // 调度器可能在结束边界分配一个空槽，单独记录，避免额外写入热身或计时波次。
  if (slot >= wmsTarget) {
    wmsBoundarySlots.add(1);
    return;
  }
  try {
    wmsPickWave(data.sample, data.waves[slot], wmsWriteKey(slot), true);
    wmsPicksConfirmed.add(2);
    wmsWavesCompleted.add(1);
    check(true, { 'PDA 并发拣货和库存事实完整': value => value });
  } catch (error) {
    invariantFailures.add(1);
    check(false, { 'PDA 并发拣货和库存事实完整': value => value });
    console.error(JSON.stringify({ phase: 'wms_timed', slot, message: error.message }));
  }
}

export function write(data) {
  const count = Number(__ENV.LINES || [1, 20, 100][exec.scenario.iterationInTest % 3]), key = __ENV.ERP_LOAD_RUN_ID + '_' + (__ENV.ERP_LOAD_PHASE || 'baseline') + '_' + scenario[0] + '_' + rate + '_' + count + '_' + exec.scenario.iterationInTest;
  const c = context(data), reviewer = { ...c, cookies: c.reviewerCookies }, start = Date.now(), slot = exec.vu.idInTest % 100;
  function step(actor, path, input, suffix, expected) {
    return writeStep(actor, path, input, key + suffix, expected, suffix);
  }
  let complete = false;
  try {
  const lines = Array.from({ length: count }, (_, index) => ({ skuId: c.skuIds[(slot + index) % c.skuIds.length], warehouseId: c.warehouseId, unitId: c.unitId, qty: '1', unitPrice: '10', lineDiscountAmount: '0', taxRate: '0' }));
  const order = step(c, '/sales-orders', { customerId: c.customerId, businessDate: c.businessDate, priceTaxInclusive: true, orderDiscountAmount: '0', lines, fees: [], remark: '容量真实销售' }, 'order_create', 201);
  if (!order) return;
  const submitted = step(c, '/sales-orders/' + order.id + '/submit', { version: order.version }, 'order_submit', 200);
  if (!submitted || !step(reviewer, '/sales-orders/' + order.id + '/approve', { version: submitted.version }, 'order_approve', 200)) return;
  const shipment = step(c, '/shipments', { customerId: c.customerId, warehouseId: c.warehouseId, businessDate: c.businessDate, deliveryMethod: 'pickup', remark: '容量真实出库', lines: order.lines.map(line => ({ orderLineId: line.id, unitId: c.unitId, qty: '1' })) }, 'shipment_create', 201);
  if (!shipment) return;
  const shipmentSubmitted = step(c, '/shipments/' + shipment.id + '/submit', { version: shipment.version }, 'shipment_submit', 200);
  if (!shipmentSubmitted) return;
  const receipt = step(c, '/receipts', { receiptType: 'customer', partnerId: c.customerId, cashAccountId: c.cashAccountId, amount: String(count * 10), businessDate: c.businessDate, relatedOrderId: order.id, remark: '容量自动核销' }, 'receipt_create', 201);
  if (!receipt) return;
  const receiptSubmitted = step(c, '/receipts/' + receipt.id + '/submit', { version: receipt.version }, 'receipt_submit', 200);
  if (!receiptSubmitted) return;
  const confirmed = step(reviewer, '/receipts/' + receipt.id + '/confirm', { version: receiptSubmitted.version }, 'receipt_confirm', 200);
  if (!confirmed || !step(c, '/shipments/' + shipment.id + '/post', { version: shipmentSubmitted.version }, 'shipment_post', 200)) return;
  const closed = request(c, 'GET', '/sales-orders/' + order.id, undefined, undefined, readTime);
  if (check(closed.response, { '真实预收销售已出库签收并完成核销': value => value.status === 200 && confirmed.status === 'confirmed' && closed.data.status === 'closed' && closed.data.fulfillmentStatus === 'full' && closed.data.settlementStatus === 'full' })) { committed.add(1); complete = true; }
  } finally { workflowTime.add(Date.now() - start, { lines: String(count), result: complete ? 'confirmed' : 'incomplete' }); }
}
function writeStep(actor, path, input, key, expected, label) {
  const start = Date.now(); let result;
  for (let attempt = 0; attempt < 6; attempt++) {
    result = request(actor, 'POST', path, input, key);
    if (result.response.status === expected || result.response.status !== 503 || !result.retryable) break;
    sleep(0.1 * (attempt + 1));
  }
  writeTime.add(Date.now() - start, { operation: path.split('?')[0].replace(/\/[0-9]+/g, '/:id') });
  const valid = check(result.response, { ['真实业务步骤成功 ' + label]: value => value.status === expected && result.data });
  if (!valid) console.error(JSON.stringify({ phase: 'business_step_failed', operation: label, status: result.response.status, errorCode: result.errorCode, message: result.message }));
  return valid ? result.data : null;
}
/** 压测失败后显式运行，通过正式命令处理遗留待过账/待确认；原失败结果不覆盖。 */
export function recover(data) {
  for (const c of data) {
    const reviewer = { ...c, cookies: c.reviewerCookies };
    for (const spec of [{ collection: 'shipments', status: 'submitted', action: 'post', actor: c }, { collection: 'receipts', status: 'pending_confirm', action: 'confirm', actor: reviewer }]) {
      const listed = request(c, 'GET', '/' + spec.collection + '?status=' + spec.status + '&limit=200');
      if (listed.response.status !== 200) throw new Error('遗留单据查询失败');
      for (const item of listed.data.items) {
        const detail = request(c, 'GET', '/' + spec.collection + '/' + item.id);
        if (detail.response.status !== 200) throw new Error('遗留单据详情读取失败');
        const done = writeStep(spec.actor, '/' + spec.collection + '/' + item.id + '/' + spec.action, { version: detail.data.version }, 'recover_' + item.id, 200, spec.collection + '_' + spec.action);
        if (!done) throw new Error('遗留单据恢复失败，保留现场');
      }
    }
  }
}
export function hotspot(data) {
  if (!hotPreconnect) hotDispatchDelay.add(Date.now() - exec.scenario.startTime);
  if (hotPreconnect) {
    sleep((exec.vu.idInTest - 1) * 3 / 1000);
    const warm = request(data[0], 'GET', '/capacity/manifest', undefined, undefined, preconnectTime);
    const ready = warm.response.status === 200;
    preconnectError.add(!ready);
    if (check(ready, { '预连接GET成功': value => value })) preconnected.add(1);
    const wait = data[0].hotStartAt - Date.now();
    if (wait <= 0) { preconnectLate.add(1); check(false, { '预连接在统一屏障前完成': value => value }); return; }
    sleep(wait / 1000);
    hotDispatchDelay.add(Date.now() - data[0].hotStartAt);
  }
  hotRequests.add(1);
  const result = request(data[0], 'POST', '/capacity/reserve/' + (exec.vu.idInTest - 1), {}, undefined, writeTime);
  if (!hotPreconnect && result.response.status !== 0 && result.response.timings.connecting > 0) hotCold.add(1);
  if (hotPreconnect && result.response.status !== 0 && result.response.timings.connecting === 0) hotReused.add(1);
  if (result.response.status === 200) hotAccepted.add(1);
  check(result.response, { '抢库存成功或明确拒绝': value => value.status === 200 || result.rejected });
}
export function duplicate(data) {
  const result = request(data[0], 'POST', '/capacity/duplicate', {}, 'load_duplicate_' + __ENV.ERP_LOAD_RUN_ID, writeTime);
  check(result.response, { '原键重复得到同一业务结果': value => value.status === 200 && result.data && result.data.journalId });
  if (result.data) console.log(JSON.stringify({ phase: 'duplicate_result', id: result.data.id, journalId: result.data.journalId }));
}
export function refund(data) {
  const result = request(data[0], 'POST', '/capacity/refund/' + (exec.vu.idInTest - 1), {}, undefined, writeTime);
  if (result.response.status === 200) refundAccepted.add(1);
  check(result.response, { '退款占用成功或明确拒绝': value => value.status === 200 || result.rejected });
}
export function mixed(data) {
  const step = exec.scenario.iterationInTest % 20;
  if (step < 12) query(data);
  else if (step < 16) write(data);
  else if (step < 18) {
    const result = writeStep(context(data), '/capacity/mixed-inbound', {}, __ENV.ERP_LOAD_RUN_ID + '_' + (__ENV.ERP_LOAD_PHASE || 'baseline') + '_i_' + scenario[0] + '_' + exec.scenario.iterationInTest, 200, 'mixed_inbound');
    if (result) mixedInboundCompleted.add(1);
  }
  else if (step === 18) mixedRefund(data);
  else {
    const c = context(data), result = request(c, 'POST', '/export-jobs', { exportType: 'inventory_summary', query: { warehouseId: c.warehouseId } }, __ENV.ERP_LOAD_RUN_ID + '_' + (__ENV.ERP_LOAD_PHASE || 'baseline') + '_e_' + scenario[0] + '_' + exec.scenario.iterationInTest);
    if (check(result.response, { '后台导出已受理': value => value.status === 202 })) exportsAccepted.add(1);
  }
}
function mixedRefund(data) {
  const c = context(data), reviewer = { ...c, cookies: c.reviewerCookies }, key = __ENV.ERP_LOAD_RUN_ID + '_' + (__ENV.ERP_LOAD_PHASE || 'baseline') + '_f_' + scenario[0] + '_' + exec.scenario.iterationInTest;
  const receipt = writeStep(c, '/receipts', { receiptType: 'customer', partnerId: c.customerId, cashAccountId: c.cashAccountId, amount: '1', businessDate: c.businessDate, remark: '容量混合退款来源' }, key + 'rc', 201, 'mixed_refund_receipt_create');
  if (!receipt) return;
  const submitted = writeStep(c, '/receipts/' + receipt.id + '/submit', { version: receipt.version }, key + 'rs', 200, 'mixed_refund_receipt_submit');
  if (!submitted || !writeStep(reviewer, '/receipts/' + receipt.id + '/confirm', { version: submitted.version }, key + 'rf', 200, 'mixed_refund_receipt_confirm')) return;
  const items = request(c, 'GET', '/open-items?side=ar&openOnly=true&limit=200&partnerId=' + c.customerId, undefined, undefined, readTime);
  const credit = items.response.status === 200 && items.data.items.find(item => item.itemType === 'receipt' && item.sourceId === receipt.id);
  if (!check(credit, { '混合退款使用本次真实收款额度': value => Boolean(value) })) return;
  const refund = writeStep(c, '/refund-requests', { customerId: c.customerId, originalReceiptId: receipt.id, cashAccountId: c.cashAccountId, amount: '1', businessDate: c.businessDate, reason: '容量混合退款', creditItemIds: [credit.id] }, key + 'fc', 201, 'mixed_refund_create');
  if (!refund) return;
  const refundSubmitted = writeStep(c, '/refund-requests/' + refund.id + '/submit', { version: refund.version }, key + 'fs', 200, 'mixed_refund_submit');
  if (!refundSubmitted) return;
  const approved = writeStep(reviewer, '/refund-requests/' + refund.id + '/approve', { version: refundSubmitted.version }, key + 'fa', 200, 'mixed_refund_approve');
  if (!approved) return;
  const paid = writeStep(c, '/refund-requests/' + refund.id + '/register-paid', { version: approved.version, paidDate: c.businessDate, bankSerialNo: 'LOAD-' + refund.id }, key + 'fp', 200, 'mixed_refund_register_paid');
  if (paid && check(paid, { '混合退款已真实登记并释放占用': value => value.status === 'refunded' })) mixedRefundCompleted.add(1);
}
export function teardown(data) {
  if (scopeFixture) {
    verifyScopeProbe(data, scopeProbeRead);
    console.info(JSON.stringify({ phase: 'scope_probe_after', dataset: scopeFixture.dataset, result: 'passed' }));
    return;
  }
  if (workflowSalesFixture) {
    const { sample } = data;
    try {
      verifyWorkflowSalesIsolation(sample, sample.warm.order, sample.warm.instance, workflowProbe());
      console.info(JSON.stringify({ phase: 'workflow_isolation_after', dataset: workflowSalesFixture.dataset, result: 'passed' }));
    } catch (error) {
      invariantFailures.add(1);
      console.error(JSON.stringify({ phase: 'workflow_isolation_after', dataset: workflowSalesFixture.dataset, result: 'failed', message: error.message }));
    }
    verifyCapacityLedgers(data.contexts, false);
    return;
  }
  if (wmsFixture) {
    let passed = false;
    try {
      wmsProbeIsolation(data.sample, data.warm, wmsWriteKey('after'), true);
      for (const { position, balance } of data.baselines) {
        verifyWmsPosition(position, balance, wmsPositionRead(data.sample.actor, position, 'wms_after'));
      }
      passed = true;
      console.info(JSON.stringify({ phase: 'wms_reconciliation', dataset: wmsFixture.dataset, mode: wmsFixture.mode,
        stockPositions: data.baselines.length, result: 'passed' }));
    } catch (error) {
      invariantFailures.add(1);
      console.error(JSON.stringify({ phase: 'wms_reconciliation', dataset: wmsFixture.dataset, message: error.message }));
    }
    check(passed, { '库位在手、占用和冻结核对': value => value });
    verifyCapacityLedgers(data.contexts, false);
    return;
  }
  if (scenario === 'refund') {
    const before = request(data[0], 'POST', '/capacity/verify', {});
    const limitHeld = before.response.status === 200 && Number(before.data.refundUsed) === 100;
    invariantFailures.add(limitHeld ? 0 : 1);
    check(limitHeld, { '释放前恰好占满100元退款额度': value => value });
    console.log(JSON.stringify({ phase: 'refund_before_cleanup', company: data[0].companyId, refundUsed: before.data && before.data.refundUsed }));
  }
  verifyCapacityLedgers(data, true);
}

function verifyCapacityLedgers(contexts, finish) {
  for (const c of contexts) {
    let result;
    for (let attempt = 0; attempt < 45; attempt++) {
      result = request(c, 'POST', '/capacity/verify?finish=' + finish, {});
      if (result.response.status !== 200) break;
      if (result.data.pendingEvents === 0 && result.data.pendingExports === 0) break;
      sleep(1);
    }
    const passed = result && result.response.status === 200 && result.data.differences.length === 0 && result.data.overFulfillment === 0 && result.data.duplicatePosting === 0 && Number(result.data.refundUsed) <= 100 && result.data.excessRefunds === 0 && result.data.pendingEvents === 0 && result.data.pendingExports === 0 && result.data.failedExports === 0 && (scenario !== 'hotspot' || c !== contexts[0] || Number(result.data.hotReserved) === 100) && (scenario !== 'duplicate' || c !== contexts[0] || result.data.duplicateFacts === 1);
    invariantFailures.add(passed ? 0 : 1);
    check(passed, { '压测后八类对账与额度及来源唯一性零差异': value => value });
    console.log(JSON.stringify({ phase: 'reconciliation', scenario, company: c.companyId, ...result.data }));
  }
}
export function handleSummary(data) {
  data.metadata = { scenario, runId: __ENV.ERP_LOAD_RUN_ID, label: __ENV.ERP_LOAD_PHASE, artifactSha256: __ENV.ERP_LOAD_ARTIFACT_SHA256 || '', generatorCpus: Number(__ENV.GOMAXPROCS || 0), preconnected: hotPreconnect, forceColdConnections: options.noConnectionReuse, preAllocatedVus: definitions[scenario].preAllocatedVUs || definitions[scenario].vus, maxVus: definitions[scenario].maxVUs || definitions[scenario].vus, rate, lines: Number(__ENV.LINES || 1), duration };
  if (['query', 'scope-read'].includes(scenario)) data.metadata.queryResponseType = 'none';
  if (scopeFixture) data.metadata.dataset = scopeFixture.dataset;
  if (workflowSalesFixture) Object.assign(data.metadata, {
    dataset: workflowSalesFixture.dataset, workflowCompanyId: workflowSalesFixture.companyId,
    workflowModelVersion: workflowSalesFixture.modelVersion, workflowTarget: workflowSalesTarget,
  });
  if (wmsFixture) Object.assign(data.metadata, {
    dataset: wmsFixture.dataset, wmsMode: wmsFixture.mode, wmsCompanyId: wmsFixture.companyId,
    wmsWaveTarget: wmsTarget, wmsConfirmTarget: 2 * wmsTarget,
  });
  return { [__ENV.SUMMARY_PATH || 'capacity-summary.json']: JSON.stringify(data, null, 2) };
}
