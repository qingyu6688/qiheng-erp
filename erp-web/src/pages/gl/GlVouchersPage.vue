<script setup lang="ts">
import { ref, watch, onScopeDispose } from 'vue'
import { ElMessageBox } from 'element-plus'
import { glVouchers, glCommand, glConfig } from '../../api/gl'
import { records } from '../../api/admin'
import { messageOf, isCancelled, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import GlOutputActions from '../../components/GlOutputActions.vue'
import { glStatus } from '../../types/gl'
import type { GlVoucher, GlConfigRow } from '../../types/gl'
import type { DataRow } from '../../types/admin'
const session = useSessionStore(),
  rows = ref<GlVoucher[]>([]),
  periods = ref<DataRow[]>([]),
  words = ref<GlConfigRow[]>([])
const periodId = ref(''),
  wordId = ref(''),
  status = ref(''),
  source = ref(''),
  loading = ref(false),
  error = ref(''),
  selected = ref<GlVoucher[]>([]),
  hasMore = ref(false)
let revision = 0
async function load(append = false): Promise<void> {
  const cursor = append ? rows.value.at(-1)?.id : undefined
  const current = ++revision
  if (!append) rows.value = []
  selected.value = []
  error.value = ''
  loading.value = true
  try {
    const result = await glVouchers({
      periodId: periodId.value || undefined,
      wordId: wordId.value || undefined,
      status: status.value || undefined,
      source: source.value || undefined,
      limit: 200,
      cursor,
    })
    if (current === revision) {
      rows.value = append ? [...rows.value, ...result] : result
      hasMore.value = result.length === 200
    }
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function init(): Promise<void> {
  const current = ++revision
  rows.value = []
  selected.value = []
  periods.value = []
  words.value = []
  hasMore.value = false
  error.value = ''
  loading.value = true
  try {
    const [p, w] = await Promise.all([records('accounting-periods', true), glConfig('words')])
    if (current !== revision) return
    periods.value = p.items
    words.value = w
    await load()
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) loading.value = false
  }
}
function word(id: string): string {
  return words.value.find((w) => w.id === id)?.code || ''
}
async function action(row: GlVoucher, action: string): Promise<void> {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '请填写处理说明',
      action === 'reverse' ? '申请红字冲销' : '确认凭证操作',
      { inputValue: '', inputValidator: (v: string) => (v.trim() ? true : '请填写说明') },
    )
    await glCommand(
      'vouchers/' + row.id + '/' + action,
      {
        version: row.version,
        reason,
        ...(action === 'reverse' ? { date: new Date().toLocaleDateString('en-CA') } : {}),
      },
      newIdempotencyKey(),
    )
    await load()
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close' && !isCancelled(e)) error.value = messageOf(e)
  }
}
async function organize(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '已记账和已删除凭证编号保留，按日期整理所选期间、凭证字的未记账凭证。',
      '整理凭证编号',
      { type: 'warning', confirmButtonText: '整理' },
    )
    await glCommand(
      'vouchers/organize',
      { periodId: periodId.value, wordId: wordId.value },
      newIdempotencyKey(),
    )
    await load()
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close' && !isCancelled(e)) error.value = messageOf(e)
  }
}
async function batch(action: string): Promise<void> {
  try {
    await glCommand(
      'vouchers/batch/' + action,
      {
        vouchers: selected.value.map((v) => ({ id: v.id, version: v.version })),
        reason: '批量处理',
      },
      newIdempotencyKey(),
    )
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  }
}
watch(
  () => session.companyId,
  () => {
    periodId.value = ''
    wordId.value = ''
    status.value = ''
    source.value = ''
    void init()
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
      <h1>总账凭证</h1>
      <p>业务凭证自动生成并保持只读；手工凭证经另一人复核后记账。</p>
    </div>
    <router-link v-if="session.can('gl_voucher:edit')" to="/gl/vouchers/new"
      ><el-button type="primary">新增手工凭证</el-button></router-link
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel">
    <div class="filter-bar">
      <el-select v-model="periodId" placeholder="全部期间" clearable style="width: 150px"
        ><el-option v-for="p in periods" :key="p.id" :label="String(p.period)" :value="p.id"
      /></el-select>
      <el-select v-model="wordId" placeholder="全部凭证字" clearable style="width: 140px"
        ><el-option v-for="w in words" :key="w.id" :label="w.code" :value="w.id"
      /></el-select>
      <el-select v-model="status" placeholder="全部状态" clearable style="width: 140px"
        ><el-option v-for="(label, key) in glStatus" :key="key" :label="label" :value="key"
      /></el-select>
      <el-select v-model="source" placeholder="全部来源" clearable style="width: 140px"
        ><el-option label="手工" value="manual" /><el-option
          label="业务"
          value="business" /><el-option label="结转" value="closing"
      /></el-select>
      <el-button :loading="loading" @click="load()">查询</el-button
      ><GlOutputActions type="vouchers" :query="{ periodFrom: periodId, periodTo: periodId }" />
      <el-button
        v-if="session.can('gl_voucher:edit')"
        :disabled="
          !periodId ||
          !wordId ||
          periods.find((p) => p.id === periodId)?.status !== 'open' ||
          loading
        "
        @click="organize"
        >整理未记账编号</el-button
      >
      <el-button
        v-if="session.can('gl_voucher:approve')"
        :disabled="!selected.length"
        @click="batch('approve')"
        >批量复核</el-button
      ><el-button
        v-if="session.can('gl_voucher:post')"
        :disabled="!selected.length"
        @click="batch('post')"
        >批量记账</el-button
      >
    </div>
    <el-table :data="rows" row-key="id" @selection-change="selected = $event"
      ><el-table-column type="selection" width="45" /><el-table-column
        prop="voucherDate"
        label="日期"
        width="120"
      /><el-table-column label="凭证号" width="110"
        ><template #default="{ row }"
          ><router-link class="text-link" :to="'/gl/vouchers/' + row.id"
            >{{ word(row.wordId) }}-{{ row.number }}</router-link
          ></template
        ></el-table-column
      ><el-table-column label="来源" width="90"
        ><template #default="{ row }">{{
          row.source === 'manual' ? '手工' : row.source === 'business' ? '业务' : '结转'
        }}</template></el-table-column
      ><el-table-column prop="debitTotal" label="借方合计" align="right" /><el-table-column
        prop="creditTotal"
        label="贷方合计"
        align="right"
      /><el-table-column label="状态" width="100"
        ><template #default="{ row }"
          ><el-tag>{{ glStatus[row.status as keyof typeof glStatus] }}</el-tag></template
        ></el-table-column
      ><el-table-column label="操作" min-width="230"
        ><template #default="{ row }">
          <template v-if="row.source === 'manual'">
            <el-button
              v-if="row.status === 'draft' && session.can('gl_voucher:edit')"
              link
              @click="action(row, 'submit')"
              >提交</el-button
            ><el-button
              v-if="row.status === 'draft' && session.can('gl_voucher:edit')"
              link
              @click="action(row, 'delete')"
              >删除</el-button
            >
            <el-button
              v-if="row.status === 'pending' && session.can('gl_voucher:approve')"
              link
              @click="action(row, 'approve')"
              >复核</el-button
            ><el-button
              v-if="row.status === 'pending' && session.can('gl_voucher:approve')"
              link
              @click="action(row, 'reject')"
              >驳回</el-button
            >
            <el-button
              v-if="row.status === 'approved' && session.can('gl_voucher:post')"
              link
              @click="action(row, 'post')"
              >记账</el-button
            ><el-button
              v-if="
                row.status === 'posted' && !row.reversalOfId && session.can('gl_voucher:reverse')
              "
              link
              @click="action(row, 'reverse')"
              >红字申请</el-button
            >
          </template></template
        ></el-table-column
      ><template #empty>{{ loading ? '正在读取凭证…' : '本次查询没有凭证' }}</template></el-table
    >
    <div class="filter-bar">
      <span>已显示 {{ rows.length }} 张凭证</span
      ><el-button v-if="hasMore" :loading="loading" @click="load(true)">继续加载</el-button>
    </div>
  </section>
</template>
<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 16px;
}
</style>
