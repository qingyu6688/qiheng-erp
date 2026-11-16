<script setup lang="ts">
import { ref, watch, computed, onScopeDispose } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import { glBook, glCommand, glRead, glSubjects } from '../../api/gl'
import GlAuxFields from '../../components/GlAuxFields.vue'
import { subjectAux } from '../../types/gl'
import { records } from '../../api/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { GlBook, GlStandard, GlSubject, GlAux } from '../../types/gl'
import type { DataRow } from '../../types/admin'
const session = useSessionStore(),
  book = ref<GlBook>({}),
  periods = ref<DataRow[]>([]),
  subjects = ref<GlSubject[]>([])
const auxOptions = ref<Record<string, { id: string; name: string; code?: string }[]>>({})
const standard = ref<GlStandard>('enterprise'),
  periodId = ref(''),
  error = ref(''),
  busy = ref(false),
  notice = ref('')
interface Opening {
  subjectId: string
  aux: GlAux
  openingDebit: string
  openingCredit: string
  yearDebit: string
  yearCredit: string
  annualOpeningDebit: string
  annualOpeningCredit: string
  quantity: string
}
const lines = ref<Opening[]>([]),
  trial = ref<{ balanced: boolean; debit: string; credit: string; difference: string }>()
const baseline = ref('')
const dirty = computed(
  () =>
    !!baseline.value &&
    book.value.status === 'setup' &&
    JSON.stringify(lines.value) !== baseline.value,
)
function leave(): boolean {
  return !dirty.value || window.confirm('期初明细尚未保存，确定离开？')
}
const unregister = registerCompanyLeaveGuard(leave)
onBeforeRouteLeave(leave)
let commandKey = newIdempotencyKey(),
  commandPayload = ''
let revision = 0
function blank(): Opening {
  return {
    subjectId: '',
    aux: {},
    openingDebit: '0.00',
    openingCredit: '0.00',
    yearDebit: '0.00',
    yearCredit: '0.00',
    annualOpeningDebit: '0.00',
    annualOpeningCredit: '0.00',
    quantity: '0',
  }
}
async function load(): Promise<void> {
  const current = ++revision
  error.value = ''
  busy.value = true
  book.value = {}
  subjects.value = []
  auxOptions.value = {}
  baseline.value = ''
  lines.value = []
  trial.value = undefined
  try {
    const [b, p] = await Promise.all([glBook(), records('accounting-periods', true)])
    if (current !== revision) return
    book.value = b
    periods.value = p.items
    if (b.id) {
      const [s, opening, t, a] = await Promise.all([
        glSubjects(),
        glRead<Record<string, unknown>[]>('opening'),
        glRead<{ balanced: boolean; debit: string; credit: string; difference: string }>(
          'opening/trial',
        ),
        glRead<Record<string, { id: string; name: string; code?: string }[]>>('choices'),
      ])
      if (current !== revision) return
      subjects.value = s
      auxOptions.value = a
      trial.value = t
      lines.value = opening.map((row) => {
        const amount = BigInt(String(row.openingSigned).replace('.', ''))
        const annual = BigInt(String(row.annualOpeningSigned).replace('.', ''))
        const positive = (v: bigint): string => {
          const x = v < 0n ? 0n : v
          return `${x / 100n}.${String(x % 100n).padStart(2, '0')}`
        }
        return {
          ...blank(),
          subjectId: String(row.subjectId),
          aux: Object.fromEntries(
            ['customer', 'supplier', 'dept', 'employee', 'project', 'sku', 'custom']
              .filter((key) => row['aux' + key[0]!.toUpperCase() + key.slice(1) + 'Id'] !== '0')
              .map((key) => [key + 'Id', row['aux' + key[0]!.toUpperCase() + key.slice(1) + 'Id']]),
          ),
          openingDebit: positive(amount),
          openingCredit: positive(-amount),
          yearDebit: String(row.yearDebit),
          yearCredit: String(row.yearCredit),
          annualOpeningDebit: positive(annual),
          annualOpeningCredit: positive(-annual),
          quantity: String(row.openingQuantity),
        }
      })
    }
    baseline.value = JSON.stringify(lines.value)
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function perform(
  path: string,
  data: unknown,
  method: 'POST' | 'PUT' = 'POST',
): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const payload = JSON.stringify([path, data, method])
    if (payload !== commandPayload) {
      commandKey = newIdempotencyKey()
      commandPayload = payload
    }
    await glCommand(path, data, commandKey, method)
    commandPayload = ''
    commandKey = newIdempotencyKey()
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
async function confirm(): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      '请说明已核对的科目、辅助核算、映射和报表公式',
      '财务确认',
      { inputValidator: (v: string) => (v.trim() ? true : '请填写核对说明') },
    )
    await perform('book/confirm', { version: book.value.version, statement: value })
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') error.value = messageOf(e)
  }
}
async function suggest(): Promise<void> {
  try {
    const result = await glRead<{ rows: Partial<Opening>[]; notice: string }>('opening/suggest')
    lines.value = result.rows.map((row) => ({ ...blank(), ...row }))
    notice.value = result.notice
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  }
}
watch(
  () => session.companyId,
  () => {
    book.value = {}
    notice.value = ''
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  unregister()
  revision++
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>财务账套与期初</h1>
      <p>当前公司独立人民币账套。模板用于本机试运行，生产启用前须由财务核对。</p>
    </div>
    <el-button :loading="busy" @click="load">刷新</el-button>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section v-if="!book.id" class="panel book-form">
    <h2>创建账套</h2>
    <el-form label-position="top"
      ><el-form-item label="会计准则"
        ><el-select v-model="standard"
          ><el-option label="企业会计准则（通用样例）" value="enterprise" /><el-option
            label="小企业会计准则（通用样例）"
            value="small_enterprise" /></el-select></el-form-item
      ><el-form-item label="启用期间"
        ><el-select v-model="periodId" placeholder="选择已开放期间"
          ><el-option
            v-for="p in periods.filter((p) => p.status === 'open')"
            :key="p.id"
            :value="p.id"
            :label="String(p.period)" /></el-select></el-form-item
      ><el-button
        v-if="session.can('gl_config:edit')"
        type="primary"
        :disabled="!periodId"
        :loading="busy"
        @click="perform('book', { standard, startPeriodId: periodId, version: 0 })"
        >创建并复制模板</el-button
      ></el-form
    >
  </section>
  <template v-else
    ><section class="panel book-form">
      <h2>
        {{ book.standard === 'enterprise' ? '企业会计准则' : '小企业会计准则' }} ·
        {{ book.status === 'active' ? '已启用' : '准备中' }}
      </h2>
      <p>
        {{ book.confirmedHash ? '当前配置已确认' : '配置尚未确认或已发生变化' }} · 币种 CNY ·
        启用期间 {{ periods.find((p) => p.id === book.startPeriodId)?.period }}
      </p>
      <div class="actions">
        <router-link class="text-link" to="/gl/config">核对科目、映射与报表配置</router-link
        ><el-button v-if="session.can('gl_config:confirm')" :loading="busy" @click="confirm"
          >确认当前财务口径</el-button
        ><el-button
          v-if="session.can('gl_opening:activate')"
          type="primary"
          :loading="busy"
          @click="
            perform('book/' + (book.status === 'active' ? 'deactivate' : 'activate'), {
              version: book.version,
              reason: '本机账套操作',
            })
          "
          >{{ book.status === 'active' ? '反启用' : '启用账套' }}</el-button
        >
      </div>
      <el-alert
        v-if="trial"
        :title="`借方 ${trial.debit} / 贷方 ${trial.credit} / 差额 ${trial.difference}；${trial.balanced ? '试算平衡' : '期初、年初或本年累计未平衡'}`"
        :type="trial.balanced ? 'success' : 'warning'"
        :closable="false"
      />
    </section>
    <section class="panel table-panel">
      <div class="page-heading">
        <h2>期初明细</h2>
        <div v-if="book.status === 'setup' && session.can('gl_opening:edit')" class="actions">
          <el-button @click="suggest">从运营账本取得建议</el-button
          ><el-button @click="lines.push(blank())">添加行</el-button
          ><el-button
            type="primary"
            :loading="busy"
            @click="perform('opening', { version: book.version, lines }, 'PUT')"
            >保存全部期初</el-button
          >
        </div>
      </div>
      <el-alert v-if="notice" :title="notice" type="info" :closable="false" /><el-table
        :data="lines"
        ><el-table-column label="科目" min-width="240"
          ><template #default="{ row }"
            ><el-select v-model="row.subjectId" :disabled="book.status === 'active'" filterable
              ><el-option
                v-for="s in subjects.filter((s) => s.isLeaf)"
                :key="s.id"
                :value="s.id"
                :label="s.code + ' ' + s.name" /></el-select></template></el-table-column
        ><el-table-column label="辅助核算" min-width="220"
          ><template #default="{ row }"
            ><GlAuxFields
              v-model="row.aux"
              :required="subjectAux(subjects.find((s) => s.id === row.subjectId))"
              :options="auxOptions"
              :disabled="book.status === 'active'" /></template></el-table-column
        ><el-table-column
          v-for="[field, label] in [
            ['openingDebit', '期初借方'],
            ['openingCredit', '期初贷方'],
            ['yearDebit', '本年借方累计'],
            ['yearCredit', '本年贷方累计'],
            ['annualOpeningDebit', '年初借方'],
            ['annualOpeningCredit', '年初贷方'],
          ]"
          :key="field"
          :label="label"
          min-width="140"
          ><template #default="{ row }"
            ><el-input
              v-model="row[field!]"
              :disabled="book.status === 'active'" /></template></el-table-column
        ><el-table-column label="操作" width="70"
          ><template #default="{ $index }"
            ><el-button v-if="book.status === 'setup'" link @click="lines.splice($index, 1)"
              >移除</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <p class="opening-note">
        损益科目期初余额须先转入本年利润或利润分配。带辅助核算科目须补齐对应客户、供应商等维度后保存。
      </p>
    </section></template
  >
</template>
<style scoped>
.book-form {
  padding: 24px;
  margin-bottom: 18px;
}
.book-form .el-form {
  max-width: 520px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.opening-note {
  padding: 16px;
  color: var(--el-text-color-secondary);
}
</style>
