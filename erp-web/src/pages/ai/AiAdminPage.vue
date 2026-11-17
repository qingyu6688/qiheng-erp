<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { aiRead, aiWrite } from '../../api/ai'
import { aiRoleChoices, type AiChoice } from '../../api/ai-choices'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { formatDateTimeSeconds } from '../../utils/format'
import type {
  AiAssistantConfig,
  AiLibrary,
  AiModel,
  AiProvider,
  AiQuota,
  AiRequestLog,
  AiRoute,
  AiToolDefinition,
  AiUsage,
} from '../../types/ai'
import '../../styles/ai-workspace.css'

const tabs = [
  { id: 'providers', label: '模型提供方' },
  { id: 'models', label: '模型' },
  { id: 'routes', label: '场景路由' },
  { id: 'assistants', label: '助手与授权' },
  { id: 'tools', label: '只读工具' },
  { id: 'quota', label: '配额' },
  { id: 'usage', label: '用量' },
  { id: 'logs', label: '调用日志' },
] as const
type Tab = (typeof tabs)[number]['id']
type Config = 'providers' | 'models' | 'routes' | 'assistants'

const session = useSessionStore()
const editable = computed(() => session.can('ai_admin:manage'))
const tab = ref<Tab>('providers')
const error = ref('')
const notice = ref('')
const busy = ref(false)
let active = true

const providers = ref<AiProvider[]>([])
const endpoints = ref<{ id: string; name: string }[]>([])
const models = ref<AiModel[]>([])
const routes = ref<AiRoute[]>([])
const assistants = ref<AiAssistantConfig[]>([])
const libraries = ref<AiLibrary[]>([])
const roles = ref<AiChoice[]>([])
const tools = ref<AiToolDefinition[]>([])
const quota = ref<AiQuota>()
const usage = ref<AiUsage[]>([])
const logs = ref<AiRequestLog[]>([])

const dialogOpen = ref(false)
const editor = ref<Config>('providers')
const provider = ref<AiProvider>(blankProvider())
const clearProviderKey = ref(false)
const model = ref<AiModel>(blankModel())
const route = ref<AiRoute>(blankRoute())
const assistant = ref<AiAssistantConfig>(blankAssistant())
const editorTitle = computed(
  () =>
    ({ providers: '提供方', models: '模型', routes: '场景路由', assistants: '助手' })[editor.value],
)

const scenes = { kb_chat: '知识助手', wiki_maintain: 'Wiki 维护', customer_service: '门户客服' }
const purposes = { chat: '对话', embedding: '文本向量', rerank: '召回重排', vision: '视觉' }
const protocols = { openai_compatible: 'OpenAI 兼容', anthropic: 'Anthropic' }
const logStatuses: Record<string, string> = {
  success: '成功',
  failed: '失败',
  error: '失败',
  refused: '拒答',
  fallback: '已切换备用模型',
}

function blankProvider(): AiProvider {
  return {
    name: '',
    type: 'openai_compatible',
    endpointKey: '',
    enabled: true,
    dataRegion: 'domestic',
    outboundConfirmed: false,
  }
}
function blankModel(): AiModel {
  return {
    providerId: '',
    name: '',
    modelKey: '',
    purpose: 'chat',
    contextWindow: 8192,
    priceIn: '0',
    priceOut: '0',
    enabled: true,
  }
}
function blankRoute(): AiRoute {
  return {
    scene: 'kb_chat',
    modelId: '',
    fallbackModelId: null,
    maxTokens: 1024,
    temperature: null,
  }
}
function blankAssistant(): AiAssistantConfig {
  return {
    code: 'internal',
    name: '',
    systemPrompt: '',
    libraryIds: [],
    toolCodes: [],
    roleIds: [],
    enabled: true,
  }
}

function nameOfModel(id?: string | null): string {
  return models.value.find((item) => item.id === id)?.name ?? id ?? '—'
}

async function perform(action: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (cause: unknown) {
    if (active && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (active) busy.value = false
  }
}

async function load(): Promise<void> {
  switch (tab.value) {
    case 'providers':
      ;[providers.value, endpoints.value] = await Promise.all([
        aiRead<AiProvider[]>('/admin/providers'),
        aiRead<{ id: string; name: string }[]>('/admin/provider-endpoints'),
      ])
      break
    case 'models':
      ;[models.value, providers.value] = await Promise.all([
        aiRead<AiModel[]>('/admin/models'),
        aiRead<AiProvider[]>('/admin/providers'),
      ])
      break
    case 'routes':
      ;[routes.value, models.value] = await Promise.all([
        aiRead<AiRoute[]>('/admin/routes'),
        aiRead<AiModel[]>('/admin/models'),
      ])
      break
    case 'assistants':
      ;[assistants.value, libraries.value, tools.value, roles.value] = await Promise.all([
        aiRead<AiAssistantConfig[]>('/admin/assistants'),
        aiRead<AiLibrary[]>('/admin/libraries'),
        aiRead<AiToolDefinition[]>('/admin/tools'),
        editable.value && session.can('role:view') ? aiRoleChoices() : Promise.resolve([]),
      ])
      break
    case 'tools':
      tools.value = await aiRead<AiToolDefinition[]>('/admin/tools')
      break
    case 'quota': {
      const result = await aiRead<AiQuota>('/admin/quota')
      quota.value = {
        dailyTokenLimit: result.dailyTokenLimit,
        monthlyCostLimit: String(result.monthlyCostLimit),
      }
      break
    }
    case 'usage':
      ;[usage.value, models.value] = await Promise.all([
        aiRead<AiUsage[]>('/admin/usage'),
        aiRead<AiModel[]>('/admin/models'),
      ])
      break
    case 'logs':
      ;[logs.value, models.value] = await Promise.all([
        aiRead<AiRequestLog[]>('/admin/logs'),
        aiRead<AiModel[]>('/admin/models'),
      ])
      break
  }
}

async function selectTab(id: Tab): Promise<void> {
  if (tab.value === id || busy.value) return
  tab.value = id
  await perform(load)
}

function editProvider(item?: AiProvider): void {
  provider.value = item ? { ...item, apiKey: undefined } : blankProvider()
  clearProviderKey.value = false
  editor.value = 'providers'
  dialogOpen.value = true
}

function editModel(item?: AiModel): void {
  model.value = item
    ? { ...item, priceIn: String(item.priceIn), priceOut: String(item.priceOut) }
    : blankModel()
  editor.value = 'models'
  dialogOpen.value = true
}

function editRoute(item?: AiRoute): void {
  route.value = item ? { ...item } : blankRoute()
  editor.value = 'routes'
  dialogOpen.value = true
}

function editAssistant(item?: AiAssistantConfig): void {
  assistant.value = item
    ? {
        ...item,
        libraryIds: [...item.libraryIds],
        toolCodes: [...item.toolCodes],
        roleIds: [...item.roleIds],
      }
    : blankAssistant()
  editor.value = 'assistants'
  dialogOpen.value = true
}

function createConfig(): void {
  error.value = ''
  if (tab.value === 'providers') editProvider()
  else if (tab.value === 'models') editModel()
  else if (tab.value === 'routes') editRoute()
  else if (tab.value === 'assistants') editAssistant()
}

const canSave = computed(() => {
  if (editor.value === 'providers') {
    const item = provider.value
    const hasCredential = !clearProviderKey.value && (item.apiKey?.trim() || item.hasKey)
    return (
      item.name.trim() &&
      item.endpointKey &&
      (!item.enabled || item.dataRegion === 'domestic' || item.outboundConfirmed) &&
      (!item.enabled || item.type !== 'anthropic' || hasCredential)
    )
  }
  if (editor.value === 'models') {
    const item = model.value
    return item.name.trim() && item.modelKey.trim() && item.providerId && item.contextWindow
  }
  if (editor.value === 'routes') return route.value.modelId && route.value.maxTokens
  return /^[a-z][a-z0-9_-]{0,39}$/.test(assistant.value.code.trim()) && assistant.value.name.trim()
})

async function save(): Promise<void> {
  if (!editable.value || !canSave.value) return
  await perform(async () => {
    switch (editor.value) {
      case 'providers': {
        const item = provider.value
        await aiWrite('/admin/providers', {
          id: item.id,
          name: item.name.trim(),
          type: item.type,
          endpointKey: item.endpointKey,
          apiKey: clearProviderKey.value ? '' : item.apiKey?.trim() || undefined,
          enabled: item.enabled,
          dataRegion: item.dataRegion,
          outboundConfirmed: item.dataRegion === 'overseas' && item.outboundConfirmed,
        })
        item.apiKey = undefined
        break
      }
      case 'models':
        await aiWrite('/admin/models', model.value)
        break
      case 'routes':
        await aiWrite('/admin/routes', {
          ...route.value,
          fallbackModelId: route.value.fallbackModelId || null,
          temperature: route.value.temperature ?? null,
        })
        break
      case 'assistants':
        await aiWrite('/admin/assistants', assistant.value)
        break
    }
    if (!active) return
    dialogOpen.value = false
    await load()
    if (active) notice.value = '已保存配置。'
  })
}

async function saveQuota(): Promise<void> {
  if (!editable.value || !quota.value) return
  await perform(async () => {
    await aiWrite('/admin/quota', quota.value)
    if (!active) return
    await load()
    if (active) notice.value = '已保存配额。'
  })
}

onMounted(() => perform(load))
onScopeDispose(() => {
  active = false
})
</script>

<template>
  <div class="ai-page">
    <div class="page-heading">
      <div>
        <h1>AI 管理</h1>
        <p>配置模型、助手可读资料和只读工具，查看模型用量与调用记录。</p>
      </div>
      <div class="ai-actions">
        <el-button :disabled="busy" @click="perform(load)">刷新</el-button>
        <el-button
          v-if="editable && ['providers', 'models', 'routes', 'assistants'].includes(tab)"
          :disabled="busy"
          type="primary"
          @click="createConfig"
          >{{ tab === 'routes' ? '配置路由' : '新增' }}</el-button
        >
      </div>
    </div>
    <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" class="ai-error" :title="notice" type="success" :closable="false" />
    <section class="panel ai-section" :aria-busy="busy">
      <nav class="ai-actions settings-tabs" aria-label="AI 管理分区">
        <el-button
          v-for="item in tabs"
          :key="item.id"
          :type="tab === item.id ? 'primary' : 'default'"
          :aria-pressed="tab === item.id"
          :disabled="busy"
          @click="selectTab(item.id)"
          >{{ item.label }}</el-button
        >
      </nav>

      <el-table
        v-if="tab === 'providers'"
        :data="providers"
        :empty-text="busy ? '正在读取…' : '暂无提供方'"
      >
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="接口协议" min-width="135"
          ><template #default="{ row }">{{
            protocols[row.type as AiProvider['type']]
          }}</template></el-table-column
        >
        <el-table-column label="连接端点" min-width="140"
          ><template #default="{ row }">{{
            endpoints.find((item) => item.id === row.endpointKey)?.name ?? row.endpointKey
          }}</template></el-table-column
        >
        <el-table-column label="数据区域" width="90"
          ><template #default="{ row }">{{
            row.dataRegion === 'overseas' ? '境外' : '境内'
          }}</template></el-table-column
        >
        <el-table-column label="密钥" width="90"
          ><template #default="{ row }">{{
            row.hasKey ? '已配置' : '未配置'
          }}</template></el-table-column
        >
        <el-table-column label="状态" width="80"
          ><template #default="{ row }">{{
            row.enabled ? '启用' : '停用'
          }}</template></el-table-column
        >
        <el-table-column v-if="editable" label="操作" width="80"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="busy" @click="editProvider(row)"
              >编辑</el-button
            ></template
          ></el-table-column
        >
      </el-table>

      <el-table
        v-else-if="tab === 'models'"
        :data="models"
        :empty-text="busy ? '正在读取…' : '暂无模型'"
      >
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="modelKey" label="服务模型标识" min-width="170" />
        <el-table-column label="提供方" min-width="110"
          ><template #default="{ row }">{{
            providers.find((item) => item.id === row.providerId)?.name ?? row.providerId
          }}</template></el-table-column
        >
        <el-table-column label="用途" width="90"
          ><template #default="{ row }">{{
            purposes[row.purpose as AiModel['purpose']]
          }}</template></el-table-column
        >
        <el-table-column prop="contextWindow" label="上下文词元" width="110" />
        <el-table-column label="状态" width="80"
          ><template #default="{ row }">{{
            row.enabled ? '启用' : '停用'
          }}</template></el-table-column
        >
        <el-table-column v-if="editable" label="操作" width="80"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="busy" @click="editModel(row)"
              >编辑</el-button
            ></template
          ></el-table-column
        >
      </el-table>

      <el-table
        v-else-if="tab === 'routes'"
        :data="routes"
        :empty-text="busy ? '正在读取…' : '暂无场景路由'"
      >
        <el-table-column label="场景" min-width="120"
          ><template #default="{ row }">{{
            scenes[row.scene as AiRoute['scene']]
          }}</template></el-table-column
        >
        <el-table-column label="主模型" min-width="120"
          ><template #default="{ row }">{{ nameOfModel(row.modelId) }}</template></el-table-column
        >
        <el-table-column label="备用模型" min-width="120"
          ><template #default="{ row }">{{
            nameOfModel(row.fallbackModelId)
          }}</template></el-table-column
        >
        <el-table-column prop="maxTokens" label="最大输出词元" width="120" />
        <el-table-column v-if="editable" label="操作" width="80"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="busy" @click="editRoute(row)"
              >编辑</el-button
            ></template
          ></el-table-column
        >
      </el-table>

      <el-table
        v-else-if="tab === 'assistants'"
        :data="assistants"
        :empty-text="busy ? '正在读取…' : '暂无助手'"
      >
        <el-table-column prop="name" label="助手" min-width="140" />
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column label="知识库" min-width="130"
          ><template #default="{ row }">{{ row.libraryIds.length }} 个</template></el-table-column
        >
        <el-table-column label="授权工具" min-width="160"
          ><template #default="{ row }">{{
            (row.toolCodes as string[])
              .map((code) => tools.find((item) => item.code === code)?.label ?? code)
              .join('、') || '未授权'
          }}</template></el-table-column
        >
        <el-table-column label="角色范围" min-width="130"
          ><template #default="{ row }">{{
            row.roleIds.length ? `${row.roleIds.length} 个角色` : '具备 AI 使用权限的成员'
          }}</template></el-table-column
        >
        <el-table-column label="状态" width="80"
          ><template #default="{ row }">{{
            row.enabled ? '启用' : '停用'
          }}</template></el-table-column
        >
        <el-table-column v-if="editable" label="操作" width="80"
          ><template #default="{ row }"
            ><el-button link type="primary" :disabled="busy" @click="editAssistant(row)"
              >编辑</el-button
            ></template
          ></el-table-column
        >
      </el-table>

      <el-table
        v-else-if="tab === 'tools'"
        :data="tools"
        :empty-text="busy ? '正在读取…' : '暂无可用工具'"
      >
        <el-table-column prop="label" label="工具名称" min-width="155" />
        <el-table-column prop="code" label="编码" min-width="160" />
        <el-table-column prop="description" label="工具说明" min-width="260" />
      </el-table>

      <el-form
        v-else-if="tab === 'quota' && quota"
        class="quota-form"
        label-position="top"
        @submit.prevent="saveQuota"
      >
        <p>每日上限设为 0 时暂停模型调用。月度费用上限设为 0 时仅允许零价格模型。</p>
        <el-form-item label="每日词元用量上限"
          ><el-input-number
            v-model="quota.dailyTokenLimit"
            :disabled="!editable || busy"
            :min="0"
            :precision="0"
        /></el-form-item>
        <el-form-item label="月度费用上限"
          ><el-input
            v-model="quota.monthlyCostLimit"
            :disabled="!editable || busy"
            inputmode="decimal"
            maxlength="24"
        /></el-form-item>
        <el-button v-if="editable" type="primary" native-type="submit" :loading="busy"
          >保存配额</el-button
        >
      </el-form>

      <el-table
        v-else-if="tab === 'usage'"
        :data="usage"
        :empty-text="busy ? '正在读取…' : '暂无用量记录'"
      >
        <el-table-column prop="date" label="日期" min-width="115" />
        <el-table-column label="场景" min-width="130"
          ><template #default="{ row }">{{
            scenes[row.scene as AiRoute['scene']] ?? row.scene
          }}</template></el-table-column
        >
        <el-table-column label="模型" min-width="130"
          ><template #default="{ row }">{{ nameOfModel(row.modelId) }}</template></el-table-column
        >
        <el-table-column prop="requests" label="调用次数" min-width="100" />
        <el-table-column prop="inputTokens" label="输入词元" min-width="110" />
        <el-table-column prop="outputTokens" label="输出词元" min-width="110" />
        <el-table-column prop="cost" label="估算费用" min-width="115" />
      </el-table>

      <el-table
        v-else-if="tab === 'logs'"
        :data="logs"
        :empty-text="busy ? '正在读取…' : '暂无调用日志'"
      >
        <el-table-column label="发起时间" min-width="155"
          ><template #default="{ row }">{{
            formatDateTimeSeconds(row.createdAt)
          }}</template></el-table-column
        >
        <el-table-column label="场景" min-width="130"
          ><template #default="{ row }">{{
            scenes[row.scene as AiRoute['scene']] ?? row.scene
          }}</template></el-table-column
        >
        <el-table-column label="模型" min-width="125"
          ><template #default="{ row }">{{ nameOfModel(row.modelId) }}</template></el-table-column
        >
        <el-table-column label="状态" width="100"
          ><template #default="{ row }">{{
            logStatuses[row.status] ?? row.status
          }}</template></el-table-column
        >
        <el-table-column prop="errorCode" label="异常代码" min-width="145" />
        <el-table-column prop="latencyMs" label="耗时（毫秒）" width="120" />
        <el-table-column prop="inputTokens" label="输入词元" width="110" />
        <el-table-column prop="outputTokens" label="输出词元" width="110" />
      </el-table>
    </section>

    <el-dialog
      v-model="dialogOpen"
      :title="editorTitle"
      width="min(600px, 94vw)"
      :close-on-click-modal="false"
      @closed="provider.apiKey = undefined"
    >
      <el-alert v-if="error" class="ai-error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="editor === 'providers'">
          <el-form-item label="提供方名称"
            ><el-input v-model="provider.name" maxlength="100"
          /></el-form-item>
          <div class="ai-grid">
            <el-form-item label="接口协议"
              ><el-select v-model="provider.type"
                ><el-option
                  v-for="(label, value) in protocols"
                  :key="value"
                  :value="value"
                  :label="label" /></el-select
            ></el-form-item>
            <el-form-item label="连接端点"
              ><el-select v-model="provider.endpointKey" placeholder="选择部署方配置的端点"
                ><el-option
                  v-for="item in endpoints"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id" /></el-select
            ></el-form-item>
          </div>
          <el-form-item label="接口密钥"
            ><el-input
              v-model="provider.apiKey"
              type="password"
              :disabled="clearProviderKey"
              autocomplete="new-password"
              :placeholder="provider.hasKey ? '留空保留现有密钥' : '端点要求密钥时填写'"
              maxlength="4096"
          /></el-form-item>
          <el-form-item v-if="provider.hasKey"
            ><el-checkbox v-model="clearProviderKey">清除已保存密钥</el-checkbox></el-form-item
          >
          <el-form-item label="数据区域"
            ><el-select v-model="provider.dataRegion"
              ><el-option label="境内" value="domestic" /><el-option
                label="境外"
                value="overseas" /></el-select
          ></el-form-item>
          <el-form-item v-if="provider.dataRegion === 'overseas'"
            ><el-checkbox v-model="provider.outboundConfirmed"
              >已确认该端点可接收出境数据</el-checkbox
            ></el-form-item
          >
          <el-form-item label="启用"><el-switch v-model="provider.enabled" /></el-form-item>
        </template>
        <template v-else-if="editor === 'models'">
          <el-form-item label="模型名称"
            ><el-input v-model="model.name" maxlength="100"
          /></el-form-item>
          <el-form-item label="服务模型标识"
            ><el-input v-model="model.modelKey" maxlength="160"
          /></el-form-item>
          <div class="ai-grid">
            <el-form-item label="模型提供方"
              ><el-select v-model="model.providerId" filterable
                ><el-option
                  v-for="item in providers"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id" /></el-select
            ></el-form-item>
            <el-form-item label="模型用途"
              ><el-select v-model="model.purpose"
                ><el-option
                  v-for="(label, value) in purposes"
                  :key="value"
                  :label="label"
                  :value="value" /></el-select
            ></el-form-item>
            <el-form-item label="输入单价（每百万词元）"
              ><el-input v-model="model.priceIn" inputmode="decimal" maxlength="24"
            /></el-form-item>
            <el-form-item label="输出单价（每百万词元）"
              ><el-input v-model="model.priceOut" inputmode="decimal" maxlength="24"
            /></el-form-item>
            <el-form-item label="上下文词元"
              ><el-input-number
                v-model="model.contextWindow"
                :min="1024"
                :max="2000000"
                :precision="0"
            /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="model.enabled" /></el-form-item>
          </div>
        </template>
        <template v-else-if="editor === 'routes'">
          <el-form-item label="场景"
            ><el-select v-model="route.scene"
              ><el-option
                v-for="(label, value) in scenes"
                :key="value"
                :label="label"
                :value="value" /></el-select
          ></el-form-item>
          <div class="ai-grid">
            <el-form-item label="主模型"
              ><el-select v-model="route.modelId" filterable
                ><el-option
                  v-for="item in models.filter(
                    (entry) => entry.purpose === 'chat' && entry.enabled,
                  )"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id" /></el-select
            ></el-form-item>
            <el-form-item label="备用模型"
              ><el-select v-model="route.fallbackModelId" filterable clearable
                ><el-option
                  v-for="item in models.filter(
                    (entry) =>
                      entry.purpose === 'chat' && entry.enabled && entry.id !== route.modelId,
                  )"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id" /></el-select
            ></el-form-item>
            <el-form-item label="最大输出词元"
              ><el-input-number v-model="route.maxTokens" :min="64" :max="4096" :precision="0"
            /></el-form-item>
            <el-form-item label="生成温度（可选）"
              ><el-input-number
                v-model="route.temperature"
                :min="0"
                :max="2"
                :step="0.1"
                :precision="2"
                placeholder="由模型决定"
            /></el-form-item>
          </div>
        </template>
        <template v-else>
          <div class="ai-grid">
            <el-form-item label="助手编码"
              ><el-input v-model="assistant.code" :disabled="Boolean(assistant.id)" maxlength="40"
            /></el-form-item>
            <el-form-item label="助手名称"
              ><el-input v-model="assistant.name" maxlength="100"
            /></el-form-item>
          </div>
          <el-form-item label="补充要求（可选）"
            ><el-input v-model="assistant.systemPrompt" type="textarea" :rows="5" maxlength="4000"
          /></el-form-item>
          <el-form-item label="关联知识库"
            ><el-select
              v-model="assistant.libraryIds"
              multiple
              filterable
              placeholder="选择可用于检索的知识库"
              ><el-option
                v-for="item in libraries"
                :key="item.id"
                :label="item.name"
                :value="item.id" /></el-select
          ></el-form-item>
          <el-form-item label="只读工具授权"
            ><el-select v-model="assistant.toolCodes" multiple placeholder="不选则不授权工具"
              ><el-option
                v-for="item in tools"
                :key="item.code"
                :label="item.label"
                :value="item.code" /></el-select
          ></el-form-item>
          <el-form-item label="可使用助手的角色"
            ><el-select
              v-model="assistant.roleIds"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="留空时允许所有具备 AI 使用权限的成员"
              ><el-option
                v-for="item in roles"
                :key="item.id"
                :label="item.label"
                :value="item.id" /></el-select
          ></el-form-item>
          <el-form-item label="启用"><el-switch v-model="assistant.enabled" /></el-form-item>
        </template>
        <div class="ai-actions">
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="!canSave"
            >保存</el-button
          >
          <el-button :disabled="busy" @click="dialogOpen = false">取消</el-button>
        </div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-tabs {
  margin-bottom: 18px;
}
.quota-form {
  max-width: 400px;
}
</style>
