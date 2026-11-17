<script setup lang="ts">
import { ElStep, ElSteps } from 'element-plus/es/components/steps/index.mjs'
import { ref, watch, onScopeDispose } from 'vue'
import { ElMessageBox } from 'element-plus'
import { records } from '../../api/admin'
import { glRead, glCommand } from '../../api/gl'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import type { PeriodCheck } from '../../api/period'
const session = useSessionStore(),
  periods = ref<DataRow[]>([]),
  periodId = ref(''),
  check = ref<PeriodCheck>(),
  busy = ref(false),
  error = ref(''),
  syncErrors = ref<{ journalId: string; errorMessage: string }[]>([])
const steps = ['业务检查', '生成业务凭证', '复核手工凭证', '损益结转', '双重对账', '关闭并滚入下期']
let revision = 0
async function inspect(): Promise<void> {
  const current = ++revision
  busy.value = true
  error.value = ''
  check.value = undefined
  try {
    const [c, e] = await Promise.all([
      glRead<PeriodCheck>('closing/' + periodId.value),
      glRead<{ journalId: string; errorMessage: string }[]>('sync/errors'),
    ])
    if (current !== revision) return
    check.value = c
    syncErrors.value = e
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function load(): Promise<void> {
  const current = ++revision
  periods.value = []
  check.value = undefined
  try {
    const p = await records('accounting-periods', true)
    if (current !== revision) return
    periods.value = p.items
    periodId.value = p.items.find((p) => p.status === 'open')?.id || p.items[0]?.id || ''
    if (periodId.value) await inspect()
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  }
}
async function generate(): Promise<void> {
  busy.value = true
  try {
    await glCommand('sync', { periodId: periodId.value }, newIdempotencyKey())
    await inspect()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
async function change(action: 'close' | 'reopen'): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      action === 'close'
        ? '将执行结转、保存三表快照并同步关闭业务期间，请填写说明。'
        : '将回冲本轮结转并重新开放最近关闭期间，请填写原因。',
      action === 'close' ? '确认财务结账' : '确认反结账',
      { inputValidator: (v: string) => (v.trim() ? true : '请填写原因') },
    )
    const period = periods.value.find((p) => p.id === periodId.value)
    await glCommand(
      'closing/' + periodId.value + '/' + action,
      { version: period?.version, reason: value },
      newIdempotencyKey(),
    )
    await load()
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close' && !isCancelled(e)) error.value = messageOf(e)
  }
}
watch(
  () => session.companyId,
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>财务期末结账</h1>
      <p>与业务期间共同关闭。系统复核最新事实后，执行结转和余额滚入。</p>
    </div>
    <router-link class="text-link" to="/accounting-periods">管理会计期间</router-link>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel closing-panel">
    <el-steps
      :active="
        periods.find((p) => p.id === periodId)?.status === 'closed' ? 6 : check?.passed ? 3 : 0
      "
      align-center
      ><el-step v-for="step in steps" :key="step" :title="step"
    /></el-steps>
    <div class="closing-actions">
      <el-select v-model="periodId" style="width: 160px" @change="inspect"
        ><el-option
          v-for="p in periods"
          :key="p.id"
          :value="p.id"
          :label="String(p.period) + (p.status === 'closed' ? ' 已关闭' : ' 开放')" /></el-select
      ><el-button :loading="busy" @click="inspect">重新检查</el-button
      ><el-button v-if="session.can('gl_sync:run')" :loading="busy" @click="generate"
        >补齐业务凭证</el-button
      ><el-button
        v-if="
          periods.find((p) => p.id === periodId)?.status === 'open' &&
          session.can('gl_closing:close')
        "
        type="primary"
        :disabled="!check?.passed || busy"
        @click="change('close')"
        >结转并关闭</el-button
      ><el-button
        v-if="
          periods.find((p) => p.id === periodId)?.status === 'closed' &&
          session.can('gl_closing:reopen')
        "
        @click="change('reopen')"
        >反结账</el-button
      >
    </div>
    <div v-if="check" class="checks">
      <el-alert
        v-for="item in check.items"
        :key="item.check"
        :title="item.message + (item.count ? '（' + item.count + '）' : '')"
        :type="item.passed ? 'success' : 'warning'"
        :closable="false"
      />
    </div>
    <h3 v-if="syncErrors.length">业务凭证生成问题</h3>
    <el-table v-if="syncErrors.length" :data="syncErrors"
      ><el-table-column prop="journalId" label="运营凭证编号" /><el-table-column
        prop="errorMessage"
        label="需要处理的问题"
    /></el-table>
  </section>
</template>
<style scoped>
.closing-panel {
  padding: 26px;
}
.closing-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin: 28px 0;
}
.checks {
  display: grid;
  gap: 10px;
}
</style>
