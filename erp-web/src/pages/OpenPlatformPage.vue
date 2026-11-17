<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { messageOf } from '../api/http'
import {
  openApps,
  openCalls,
  openCapabilities,
  openDeliveries,
  openHooks,
  openSpec,
  retryOpenDelivery,
  rotateOpenSecret,
  testOpenHook,
  writeOpenApp,
  writeOpenHook,
} from '../api/open-platform'
import type {
  AppInput,
  Capability,
  HookInput,
  OpenApp,
  OpenCall,
  OpenDelivery,
  OpenHook,
  OpenSecret,
} from '../api/open-platform'
import { useSessionStore } from '../stores/session'
import { formatDateTime, formatPrintTime } from '../utils/format'

const session = useSessionStore(),
  tab = ref('apps'),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const apps = ref<OpenApp[]>([]),
  hooks = ref<OpenHook[]>([]),
  calls = ref<OpenCall[]>([]),
  deliveries = ref<OpenDelivery[]>([])
const scopes = ref<Capability[]>([]),
  events = ref<Capability[]>([]),
  spec = ref<Record<string, unknown>>()
const mode = ref<'apps' | 'webhooks'>('apps'),
  editing = ref(false),
  recordId = ref<string>(),
  shownSecret = ref<OpenSecret>()
const logApp = ref(''),
  logHook = ref('')
const app = reactive<AppInput>({
  name: '',
  companyIds: [],
  scopes: [],
  ipWhitelist: [],
  rateLimitPerMinute: 60,
  enabled: true,
  expiresAt: null,
})
const hook = reactive<HookInput>({ appId: '', url: '', events: [], enabled: true })
const ips = ref(''),
  expires = ref('')
const statuses: Record<string, string> = {
  pending: '待推送',
  delivered: '已送达',
  failed: '失败',
  running: '推送中',
}

async function initial(): Promise<void> {
  loading.value = true
  try {
    const [a, h, c] = await Promise.all([openApps(), openHooks(), openCapabilities()])
    apps.value = a
    hooks.value = h
    scopes.value = c.scopes
    events.value = c.events
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}
async function review(): Promise<void> {
  const current = tab.value
  error.value = ''
  try {
    if (current === 'calls') {
      const data = await openCalls(logApp.value || undefined)
      if (tab.value === current) calls.value = data
    }
    if (current === 'deliveries') {
      const data = await openDeliveries(logHook.value || undefined)
      if (tab.value === current) deliveries.value = data
    }
    if (current === 'spec' && !spec.value) {
      const data = await openSpec()
      if (tab.value === current) spec.value = data
    }
  } catch (cause: unknown) {
    if (tab.value === current) error.value = messageOf(cause)
  }
}
function editApp(row?: OpenApp): void {
  mode.value = 'apps'
  recordId.value = row?.id
  error.value = ''
  Object.assign(app, {
    name: row?.name ?? '',
    companyIds: [...(row?.companyIds ?? [])],
    scopes: [...(row?.scopes ?? [])],
    ipWhitelist: [],
    rateLimitPerMinute: row?.rateLimitPerMinute ?? 60,
    enabled: row?.enabled ?? true,
    expiresAt: row?.expiresAt ?? null,
    version: row?.version,
  })
  ips.value = row?.ipWhitelist.join('\n') ?? ''
  expires.value = row?.expiresAt ? formatPrintTime(row.expiresAt).replace(' ', 'T').slice(0, 16) : ''
  editing.value = true
}
function editHook(row?: OpenHook): void {
  mode.value = 'webhooks'
  recordId.value = row?.id
  error.value = ''
  Object.assign(hook, {
    appId: row?.appId ?? apps.value[0]?.id ?? '',
    url: row?.url ?? '',
    events: [...(row?.events ?? [])],
    enabled: row?.enabled ?? true,
    version: row?.version,
  })
  editing.value = true
}
async function perform(action: () => Promise<unknown>, success: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = notice.value = ''
  try {
    await action()
    notice.value = success
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
function save(): void {
  void perform(async () => {
    if (mode.value === 'apps') {
      const data = {
        ...app,
        ipWhitelist: ips.value.split(/[\s,]+/).filter(Boolean),
        expiresAt: expires.value ? new Date(expires.value + '+08:00').toISOString() : null,
      }
      const result = await writeOpenApp(data, recordId.value)
      if (result.secret)
        shownSecret.value = { id: result.id, appKey: result.appKey, secret: result.secret }
    } else {
      const result = await writeOpenHook(hook, recordId.value)
      if (result.secret) shownSecret.value = { id: result.id, secret: result.secret }
    }
    editing.value = false
    await initial()
  }, '配置已保存')
}
function rotate(kind: 'apps' | 'webhooks', id: string): void {
  if (!window.confirm('确认更换密钥？旧密钥将立即失效。')) return
  void perform(async () => {
    shownSecret.value = await rotateOpenSecret(kind, id)
    await initial()
  }, '新密钥已生成')
}
function saveSpec(): void {
  const file = new Blob([JSON.stringify(spec.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(file),
    link = document.createElement('a')
  link.href = url
  link.download = 'qiheng-open-v1.json'
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
onMounted(initial)
watch([tab, logApp, logHook], review)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>开放平台</h1>
      <p class="muted">管理应用访问范围、回调和调用记录。</p>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
  <section v-loading="loading" class="panel">
    <el-tabs v-model="tab">
      <el-tab-pane label="应用管理" name="apps">
        <el-button
          v-if="session.can('open_platform:manage')"
          type="primary"
          class="open-action"
          @click="editApp()"
          >新建应用</el-button
        >
        <el-table :data="apps" row-key="id" empty-text="暂无开放应用">
          <el-table-column prop="name" label="名称" min-width="130" /><el-table-column
            prop="appKey"
            label="应用标识"
            min-width="250"
          />
          <el-table-column label="授权公司" min-width="150"
            ><template #default="{ row }">{{
              (row.companyIds as string[])
                .map(
                  (id) => session.user?.companies.find((company) => company.id === id)?.name ?? id,
                )
                .join('、')
            }}</template></el-table-column
          >
          <el-table-column prop="rateLimitPerMinute" label="每分钟限额" width="120" />
          <el-table-column label="状态" width="90"
            ><template #default="{ row }">{{
              row.enabled ? '启用' : '停用'
            }}</template></el-table-column
          >
          <el-table-column v-if="session.can('open_platform:manage')" label="操作" min-width="160"
            ><template #default="{ row }">
              <el-button link type="primary" @click="editApp(row as OpenApp)">编辑</el-button
              ><el-button link @click="rotate('apps', row.id)">换密钥</el-button>
            </template></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="Webhook" name="webhooks">
        <el-button
          v-if="session.can('open_platform:manage')"
          type="primary"
          class="open-action"
          :disabled="!apps.length"
          @click="editHook()"
          >新建推送</el-button
        >
        <el-table :data="hooks" row-key="id" empty-text="暂无 Webhook">
          <el-table-column prop="url" label="接收地址" min-width="240" show-overflow-tooltip />
          <el-table-column label="订阅事件" min-width="220"
            ><template #default="{ row }">{{
              (row.events as string[]).join('、')
            }}</template></el-table-column
          >
          <el-table-column prop="consecutiveFailures" label="连续失败" width="110" />
          <el-table-column label="状态" width="90"
            ><template #default="{ row }">{{
              row.enabled ? '启用' : '停用'
            }}</template></el-table-column
          >
          <el-table-column v-if="session.can('open_platform:manage')" label="操作" min-width="200"
            ><template #default="{ row }">
              <el-button link type="primary" @click="editHook(row as OpenHook)">编辑</el-button
              ><el-button
                link
                :disabled="busy"
                @click="perform(() => testOpenHook(row.id), '测试推送已加入队列')"
                >测试</el-button
              ><el-button link @click="rotate('webhooks', row.id)">换密钥</el-button>
            </template></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="调用日志" name="calls">
        <el-select v-model="logApp" clearable placeholder="全部应用" class="open-select"
          ><el-option v-for="item in apps" :key="item.id" :label="item.name" :value="item.id"
        /></el-select>
        <el-button @click="review">刷新</el-button>
        <el-table :data="calls" row-key="id" empty-text="暂无调用记录"
          ><el-table-column prop="method" label="方法" width="90" /><el-table-column
            prop="pathTemplate"
            label="路径"
            min-width="180"
          />
          <el-table-column prop="status" label="状态码" width="100" /><el-table-column
            prop="latencyMs"
            label="耗时（毫秒）"
            width="140"
          />
          <el-table-column label="时间" min-width="150"
            ><template #default="{ row }">{{
              formatDateTime(row.createdAt)
            }}</template></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="推送记录" name="deliveries">
        <el-select v-model="logHook" clearable placeholder="全部 Webhook" class="open-select"
          ><el-option
            v-for="item in hooks"
            :key="item.id"
            :label="item.url"
            :value="item.id" /></el-select
        ><el-button @click="review">刷新</el-button>
        <el-table :data="deliveries" row-key="id" empty-text="暂无推送记录"
          ><el-table-column prop="eventType" label="事件" min-width="180" />
          <el-table-column label="状态" width="100"
            ><template #default="{ row }">{{
              statuses[row.status as string] ?? row.status
            }}</template></el-table-column
          >
          <el-table-column prop="attempts" label="尝试次数" width="110" /><el-table-column
            prop="lastStatusCode"
            label="返回码"
            width="100"
          />
          <el-table-column label="下次重试" min-width="150"
            ><template #default="{ row }">{{
              formatDateTime(row.nextAttemptAt)
            }}</template></el-table-column
          >
          <el-table-column v-if="session.can('open_platform:manage')" label="操作" width="100"
            ><template #default="{ row }"
              ><el-button
                v-if="row.status === 'failed'"
                link
                type="primary"
                @click="
                  perform(async () => {
                    await retryOpenDelivery(row.id)
                    await review()
                  }, '已提交重试')
                "
                >重推</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="接口文档" name="spec">
        <el-button v-if="spec" type="primary" @click="saveSpec">下载 OpenAPI JSON</el-button>
        <pre class="open-spec" v-if="spec">{{ JSON.stringify(spec, null, 2) }}</pre>
      </el-tab-pane>
    </el-tabs>
  </section>
  <el-dialog
    v-model="editing"
    :title="mode === 'apps' ? '应用配置' : 'Webhook 配置'"
    width="min(640px,calc(100vw - 32px))"
    :close-on-click-modal="false"
  >
    <el-form v-if="mode === 'apps'" label-width="120px" @submit.prevent="save">
      <el-form-item label="应用名称" required
        ><el-input v-model="app.name" maxlength="100"
      /></el-form-item>
      <el-form-item label="授权公司" required
        ><el-select v-model="app.companyIds" multiple
          ><el-option
            v-for="company in session.user?.companies ?? []"
            :key="company.id"
            :value="company.id"
            :label="company.name" /></el-select
      ></el-form-item>
      <el-form-item label="业务权限" required
        ><el-select v-model="app.scopes" multiple filterable
          ><el-option
            v-for="item in scopes"
            :key="item.code"
            :value="item.code"
            :label="item.label" /></el-select
      ></el-form-item>
      <el-form-item label="IP 白名单"
        ><el-input v-model="ips" type="textarea" placeholder="每行一个 IP 或 CIDR 网段"
      /></el-form-item>
      <el-form-item label="每分钟限额"
        ><el-input-number v-model="app.rateLimitPerMinute" :min="1" :max="10000"
      /></el-form-item>
      <el-form-item label="到期时间（北京时间）"
        ><el-input v-model="expires" type="datetime-local"
      /></el-form-item>
      <el-form-item label="启用应用"><el-switch v-model="app.enabled" /></el-form-item>
    </el-form>
    <el-form v-else label-width="120px" @submit.prevent="save">
      <el-form-item label="所属应用" required
        ><el-select v-model="hook.appId" :disabled="!!recordId"
          ><el-option
            v-for="item in apps"
            :key="item.id"
            :value="item.id"
            :label="item.name" /></el-select
      ></el-form-item>
      <el-form-item label="接收地址" required
        ><el-input v-model="hook.url" placeholder="https://" maxlength="500"
      /></el-form-item>
      <el-form-item label="订阅事件" required
        ><el-select v-model="hook.events" multiple
          ><el-option
            v-for="item in events"
            :key="item.code"
            :value="item.code"
            :label="item.label" /></el-select
      ></el-form-item>
      <el-form-item label="启用推送"><el-switch v-model="hook.enabled" /></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer
      ><el-button @click="editing = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存</el-button></template
    >
  </el-dialog>
  <el-dialog
    :model-value="!!shownSecret"
    title="保存密钥"
    width="min(560px,calc(100vw - 32px))"
    @close="shownSecret = undefined"
    :close-on-click-modal="false"
  >
    <p>密钥仅在本次显示，请安全保存。</p>
    <p v-if="shownSecret?.appKey">
      <code>{{ shownSecret.appKey }}</code>
    </p>
    <p class="open-secret">
      <code>{{ shownSecret?.secret }}</code>
    </p>
    <template #footer
      ><el-button type="primary" @click="shownSecret = undefined">已保存</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.open-action,
.open-select {
  margin-bottom: 14px;
}
.open-select {
  width: 245px;
  margin-right: 10px;
}
.open-secret {
  overflow-wrap: anywhere;
  user-select: all;
}
.open-spec {
  white-space: pre-wrap;
  overflow: auto;
  max-height: 520px;
}
.el-form .el-select {
  width: 100%;
}
</style>
