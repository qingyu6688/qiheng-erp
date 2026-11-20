<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { ArrowDown, CircleCheckFilled } from '@element-plus/icons-vue'
import { setupStatus } from '../api/workbench'
import type { SetupStatus, SetupStepKey } from '../api/workbench'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'

interface StepGuide {
  title: string
  desc: string
  links: { label: string; to: string; permission?: string }[]
}

const props = defineProps<{ scope?: string }>()
const session = useSessionStore()

// 顺序与服务端一致：先有仓库和往来、商品，再开期间、录期初，最后开第一张单
const guides: Record<SetupStepKey, StepGuide> = {
  warehouse: {
    title: '建立仓库',
    desc: '至少启用一个仓库，库存和出入库单据都按仓库记账。',
    links: [{ label: '去建仓库', to: '/warehouses' }],
  },
  customer: {
    title: '添加客户',
    desc: '往来单位勾选“客户”角色后，才会出现在销售订单的客户列表里。',
    links: [{ label: '去添加', to: '/partners' }],
  },
  supplier: {
    title: '添加供应商',
    desc: '同一家单位可以既是客户又是供应商，勾选两个角色即可。',
    links: [{ label: '去添加', to: '/partners' }],
  },
  sku: {
    title: '建立商品与 SKU',
    desc: '维护基础单位、换算和默认税率，下单时自动带出。',
    links: [{ label: '去建商品', to: '/skus' }],
  },
  'cash-account': {
    title: '核对结算账户',
    desc: '收款、付款登记到哪个银行或现金账户；可设默认收付款账户。',
    links: [{ label: '去核对', to: '/cash-accounts' }],
  },
  period: {
    title: '开放本月会计期间',
    desc: '业务日期必须落在开放期间内，单据才能过账。',
    links: [{ label: '去开放', to: '/accounting-periods' }],
  },
  'stock-opening': {
    title: '录入库存期初（可选）',
    desc: '已有库存时，在库存调整中选“期初”录入数量和成本，由另一人审核过账。',
    links: [{ label: '去录入', to: '/stock-adjustments' }],
  },
  'partner-opening': {
    title: '录入往来与资金期初（可选）',
    desc: '已有应收、应付或账户余额时录入；没有可以跳过。',
    links: [{ label: '去录入', to: '/opening-entries' }],
  },
  'first-document': {
    title: '开出第一张单据',
    desc: '资料准备好后，从采购订单或销售订单开始日常业务。',
    links: [
      { label: '新建采购订单', to: '/purchase-orders/new', permission: 'purchase_order:create' },
      { label: '新建销售订单', to: '/sales-orders/new', permission: 'sales_order:create' },
    ],
  },
}

const status = ref<SetupStatus>()
const error = ref('')
const collapsed = ref(readCollapsed())
let controller = new AbortController()

const steps = computed(() =>
  (status.value?.steps ?? []).map((step) => ({
    ...step,
    ...guides[step.key],
    links: guides[step.key].links
      .filter((link) => !link.permission || session.can(link.permission))
      .map((link) => ({
        ...link,
        to: link.to === '/skus' && !session.can('sku:view') ? '/products' : link.to,
      })),
  })),
)
const required = computed(() => steps.value.filter((step) => step.required))
const finished = computed(() => required.value.filter((step) => step.done).length)
const nextKey = computed(() => steps.value.find((step) => step.required && !step.done)?.key)
const visible = computed(() =>
  Boolean(status.value && !status.value.completed && steps.value.length),
)

function collapseKey(): string | undefined {
  return props.scope ? `${props.scope}.setup-collapsed` : undefined
}
function readCollapsed(): boolean {
  const key = collapseKey()
  if (!key) return false
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    // 浏览器禁用存储时按展开处理，不影响使用
    return false
  }
}
function toggle(): void {
  collapsed.value = !collapsed.value
  const key = collapseKey()
  if (!key) return
  try {
    localStorage.setItem(key, collapsed.value ? '1' : '0')
  } catch {
    // 记不住收起状态只影响下次打开时的默认展开，不提示用户
    return
  }
}

async function load(): Promise<void> {
  controller.abort()
  controller = new AbortController()
  error.value = ''
  try {
    status.value = await setupStatus(controller.signal)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}
void load()
onScopeDispose(() => controller.abort())
defineExpose({ load })
</script>

<template>
  <section v-if="visible" class="panel setup-guide" aria-labelledby="setup-guide-title">
    <div class="setup-head">
      <div>
        <h2 id="setup-guide-title">上线准备</h2>
        <p>按顺序完成下面几步，就可以开始录单。完成必做项后这里会自动收起。</p>
      </div>
      <div class="setup-progress">
        <span>必做 {{ finished }} / {{ required.length }}</span>
        <span class="setup-bar" aria-hidden="true"
          ><span
            :style="{
              width: (required.length ? Math.round((finished / required.length) * 100) : 0) + '%',
            }"
        /></span>
        <button
          type="button"
          class="setup-toggle"
          :aria-expanded="!collapsed"
          aria-controls="setup-guide-steps"
          @click="toggle"
        >
          {{ collapsed ? '展开' : '收起'
          }}<ArrowDown class="setup-toggle-icon" :class="{ open: !collapsed }" aria-hidden="true" />
        </button>
      </div>
    </div>
    <ol v-show="!collapsed" id="setup-guide-steps" class="setup-steps">
      <li
        v-for="(step, index) in steps"
        :key="step.key"
        :class="{ done: step.done, next: step.key === nextKey }"
      >
        <span class="setup-mark" aria-hidden="true">
          <CircleCheckFilled v-if="step.done" />
          <template v-else>{{ index + 1 }}</template>
        </span>
        <div class="setup-text">
          <strong>{{ step.title }}<span v-if="step.done" class="sr-only">，已完成</span></strong>
          <p>{{ step.desc }}</p>
        </div>
        <div v-if="!step.done" class="setup-actions">
          <router-link
            v-for="link in step.links"
            :key="link.to"
            :to="link.to"
            class="el-button el-button--small"
            :class="{ 'el-button--primary': step.key === nextKey }"
            >{{ link.label }}</router-link
          >
        </div>
        <span v-else class="setup-done">已完成</span>
      </li>
    </ol>
  </section>
  <el-alert
    v-else-if="error"
    :title="'上线准备加载失败：' + error"
    type="warning"
    :closable="false"
  />
</template>

<style scoped>
.setup-guide {
  margin-bottom: 16px;
  padding: 20px 24px;
  border-color: var(--erp-primary-2);
  background: linear-gradient(180deg, var(--erp-primary-1) 0, var(--el-bg-color) 96px);
}
.setup-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 24px;
}
.setup-head h2 {
  margin: 0;
  font-size: 16px;
}
.setup-head p {
  margin: 4px 0 0;
  font-size: 13px;
}
.setup-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--erp-text-2);
  font-size: 13px;
  white-space: nowrap;
}
.setup-bar {
  width: 140px;
  height: 6px;
  overflow: hidden;
  border-radius: var(--erp-radius-pill);
  background: var(--erp-fill-2);
}
.setup-bar > span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--el-color-primary);
  transition: width var(--el-transition-duration);
}
.setup-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 0;
  border-radius: var(--erp-radius-sm);
  background: transparent;
  color: var(--erp-primary-7);
  font: inherit;
  cursor: pointer;
}
.setup-toggle:hover {
  background: var(--erp-primary-1);
}
.setup-toggle-icon {
  width: 12px;
  height: 12px;
  transform: rotate(-90deg);
  transition: transform var(--el-transition-duration-fast);
}
.setup-toggle-icon.open {
  transform: none;
}
.setup-steps {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 300px), 1fr));
  gap: 10px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}
.setup-steps li {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 4px 12px;
  padding: 14px;
  border: 1px solid var(--erp-divider);
  border-radius: var(--erp-radius-card);
  background: var(--el-bg-color);
}
.setup-steps li.next {
  border-color: var(--erp-primary-3);
  box-shadow: 0 0 0 3px var(--erp-primary-1);
}
.setup-steps li.done {
  background: var(--erp-fill-1);
}
.setup-mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--erp-fill-2);
  color: var(--erp-text-2);
  font-size: 13px;
  font-weight: 600;
}
.setup-steps li.next .setup-mark {
  background: var(--el-color-primary);
  color: #fff;
}
.setup-steps li.done .setup-mark {
  background: transparent;
  color: var(--el-color-success);
}
.setup-mark svg {
  width: 24px;
  height: 24px;
}
.setup-text strong {
  font-size: 14px;
}
.setup-steps li.done .setup-text strong {
  color: var(--erp-text-2);
}
.setup-text p {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 18px;
}
.setup-actions,
.setup-done {
  grid-column: 2;
}
.setup-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.setup-actions .el-button {
  margin: 0;
  text-decoration: none;
}
.setup-done {
  color: var(--el-color-success);
  font-size: 12px;
}
</style>
