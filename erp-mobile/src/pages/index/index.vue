<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import MobileLogin from '../../components/MobileLogin.vue'
import TaskPanel from '../../components/TaskPanel.vue'
import CountPanel from '../../components/CountPanel.vue'
import OutboundPanel from '../../components/OutboundPanel.vue'
import MovePanel from '../../components/MovePanel.vue'
import ReceiptPanel from '../../components/ReceiptPanel.vue'
import ProductionIssuePanel from '../../components/ProductionIssuePanel.vue'
import StockPanel from '../../components/StockPanel.vue'
import ApprovalPanel from '../../components/ApprovalPanel.vue'
import LabelPanel from '../../components/LabelPanel.vue'
import { errorText } from '../../api/http'
import { read } from '../../api/work'
import { nativeApp, useSession } from '../../stores/session'
import type { PairingCode } from '../../types/api'
import type { TaskStatistics } from '../../types/work'

defineOptions({ name: 'MobileWorkspace' })

type Menu = 'home' | 'receive' | 'production-issue' | 'putaway' | 'pick' | 'wave' | 'pack' | 'move' | 'replenish' | 'count' | 'labels' | 'stock' | 'approval' | 'pair'
const operations: { key: Menu; title: string; permission?: string; related?: string }[] = [
  { key: 'receive', title: '采购收货', permission: 'goods_receipt:view' },
  { key: 'production-issue', title: '生产领料', permission: 'mfg_issue:view', related: 'mfg_work_order:view' },
  { key: 'putaway', title: '上架', permission: 'wms_task:view' },
  { key: 'pick', title: '拣货', permission: 'wms_task:view' },
  { key: 'wave', title: '波次', permission: 'wms_wave:view' },
  { key: 'pack', title: '复核装箱', permission: 'wms_pack:view' },
  { key: 'move', title: '移库', permission: 'wms_move:view' },
  { key: 'replenish', title: '补货', permission: 'wms_move:view' },
  { key: 'count', title: '库位盘点', permission: 'stock_count:view' },
  { key: 'stock', title: '库存查询', permission: 'inventory:view' },
  { key: 'labels', title: '标签补打', permission: 'label:print' },
  { key: 'approval', title: '待办审批' },
]
const session = useSession()
const menu = ref<Menu>('home')
const error = ref('')
const restoring = ref(true)
const pairing = ref<PairingCode>()
const deviceName = ref('')
const pairBusy = ref(false)
const onDevice = nativeApp()
const approvals = ref<number>()
const doneToday = ref<number>()
const pendingToday = ref<number>()
const statisticsError = ref('')
const displayed = computed(() => operations.filter(
  (item) => (!item.permission || session.profile?.permissions.includes(item.permission)) && (!item.related || session.profile?.permissions.includes(item.related)),
))
const company = computed(() => session.profile?.companies.find((item) => item.id === session.company))
const index = computed(() => session.profile?.companies.findIndex((item) => item.id === session.company) ?? 0)
const title = computed(() => menu.value === 'pair' ? '设备配对' : displayed.value.find((item) => item.key === menu.value)?.title)

watch(() => session.company, () => { menu.value = 'home' })
watch([() => session.company, menu], ([current, page]) => {
  if (page === 'home') void loadNumbers(current)
})

async function loadNumbers(companyId: string | undefined): Promise<void> {
  approvals.value = doneToday.value = pendingToday.value = undefined
  statisticsError.value = ''
  if (!companyId) return
  const day = new Date(Date.now() + 8 * 3_600_000).toISOString().slice(0, 10)
  const responses = await Promise.allSettled([
    read<{ count: number }>('/workflow/todo-count'),
    session.profile?.permissions.includes('wms_task:view')
      ? read<TaskStatistics>('/wms/task-admin/statistics', { from: day, to: day })
      : Promise.resolve(undefined),
  ])
  if (session.company !== companyId || menu.value !== 'home') return
  if (responses[0]?.status === 'fulfilled') approvals.value = responses[0].value.count
  if (responses[1]?.status === 'fulfilled' && responses[1].value) {
    pendingToday.value = responses[1].value.myPendingToday
    doneToday.value = responses[1].value.items
      .filter((entry) => entry.assigneeId === session.profile?.id)
      .reduce((total, entry) => total + entry.taskCount, 0)
  }
  statisticsError.value = responses.flatMap((result, index) => result.status === 'rejected'
    ? [`${index === 0 ? '待审批数量' : '作业统计'}加载失败：${errorText(result.reason)}`] : []).join('。')
}

onMounted(async () => {
  try {
    await session.restore()
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    restoring.value = false
  }
})

function companyChanged(event: { detail: { value: string | number } }): void {
  const option = session.profile?.companies[Number(event.detail.value)]
  if (option) session.selectCompany(option.id)
}

async function logout(): Promise<void> {
  error.value = ''
  try {
    await session.logout()
    menu.value = 'home'
    pairing.value = undefined
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function issue(): Promise<void> {
  if (pairBusy.value || !deviceName.value.trim()) return
  pairBusy.value = true
  pairing.value = undefined
  error.value = ''
  try {
    pairing.value = await session.pairingCode(deviceName.value.trim())
  } catch (cause: unknown) {
    error.value = errorText(cause)
  } finally {
    pairBusy.value = false
  }
}

function copyPairingCode(): void {
  const value = pairing.value?.pairingCode
  if (value) uni.setClipboardData({ data: value, fail: () => { error.value = '复制失败，请手动记录配对码' } })
}
</script>

<template>
  <view class="layout">
    <view class="masthead">
      <view>
        <text class="product">柒衡 ERP</text>
        <text class="mast-context">{{ title ?? '仓储工作台' }}</text>
      </view>
      <button v-if="session.profile" class="compact mast-button" @click="logout">退出</button>
    </view>
    <text v-if="error" class="error" role="alert">{{ error }}</text>
    <view v-if="restoring" class="panel"><text class="muted">正在读取会话…</text></view>
    <MobileLogin v-else-if="!session.profile" />
    <template v-else>
      <view v-if="session.profile.mustChangePassword" class="panel">
        <text class="error">请先在 ERP 管理端修改密码，再登录移动端。</text>
      </view>
      <template v-else>
        <view class="workspace">
          <button v-if="menu !== 'home'" class="compact secondary" @click="menu = 'home'; pairing = undefined">工作台</button>
          <picker v-if="session.profile.companies.length > 1" :range="session.profile.companies"
            range-key="name" :value="Math.max(index, 0)" @change="companyChanged">
            <view role="button" aria-label="切换公司" class="company company-picker">{{ company?.name ?? '请选择公司' }} ▾</view>
          </picker>
          <text v-else class="company">{{ company?.name ?? session.profile.displayName }}</text>
        </view>
        <view v-if="menu === 'home'" class="panel">
          <text class="heading">工作台</text>
          <text v-if="!company" class="muted">当前账号未分配公司，请联系管理员。</text>
          <view v-else class="module-grid">
            <text v-if="pendingToday !== undefined" class="muted">今日待处理 {{ pendingToday }} 项作业</text>
            <text v-if="doneToday !== undefined" class="muted">今日完成 {{ doneToday }} 项作业</text>
            <text v-if="approvals !== undefined" class="muted">待审批 {{ approvals }} 项</text>
            <button v-for="item in displayed" :key="item.key" class="secondary"
              @click="menu = item.key">{{ item.title }}</button>
            <button v-if="!onDevice" class="secondary" @click="menu = 'pair'">设备配对</button>
          </view>
          <template v-if="statisticsError">
            <text class="error" role="alert">{{ statisticsError }}</text>
            <button class="secondary" @click="loadNumbers(session.company)">重新加载工作台统计</button>
          </template>
        </view>
        <ReceiptPanel v-else-if="menu === 'receive'" />
        <ProductionIssuePanel v-else-if="menu === 'production-issue'" />
        <TaskPanel v-else-if="menu === 'putaway' || menu === 'pick'" :key="menu" :type="menu" />
        <OutboundPanel v-else-if="menu === 'wave' || menu === 'pack'" :key="menu" :type="menu" />
        <MovePanel v-else-if="menu === 'move' || menu === 'replenish'" :key="menu" :type="menu" />
        <CountPanel v-else-if="menu === 'count'" />
        <StockPanel v-else-if="menu === 'stock'" />
        <LabelPanel v-else-if="menu === 'labels'" />
        <ApprovalPanel v-else-if="menu === 'approval'" />
        <view v-else-if="menu === 'pair'" class="panel">
          <text class="heading">设备配对</text>
          <text class="field-label">设备显示名称</text>
          <input v-model="deviceName" class="field" aria-label="设备显示名称" :maxlength="80"
            :disabled="pairBusy" @confirm="issue">
          <wd-button block :loading="pairBusy" :disabled="!deviceName.trim()" @click="issue">生成配对码</wd-button>
          <template v-if="pairing">
            <text class="muted">请在五分钟内将配对码输入指定 PDA。</text>
            <text class="pair-code" selectable>{{ pairing.pairingCode }}</text>
            <button class="secondary" @click="copyPairingCode">复制配对码</button>
          </template>
        </view>
      </template>
    </template>
  </view>
</template>
