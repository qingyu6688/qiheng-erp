<script setup lang="ts">
import { onMounted, ref } from 'vue'
import WorkList from './WorkList.vue'
import { errorText } from '../api/http'
import { approvalInstance, approvals, command, idPart } from '../api/work'
import { usePage } from '../composables/usePage'
import { useTaskFlow } from '../composables/useTaskFlow'
import type { Id } from '../types/api'
import type { ApprovalInstance, ApprovalRow } from '../types/work'

const { items, cursor, busy, failure, load } = usePage(approvals)
const { busy: sending, failure: commandError, success, run } = useTaskFlow()
const selected = ref<ApprovalRow>()
const instance = ref<ApprovalInstance>()
const comment = ref('')
const error = ref('')

onMounted(() => load())

const summaryLabels: Record<string, string> = {
  documentNo: '单据编号', title: '标题', amount: '金额', totalAmount: '合计',
  businessDate: '业务日期', content: '内容', status: '状态', supplierName: '供应商',
  customerName: '客户', warehouseName: '仓库', reason: '原因',
}

function summary(value: Record<string, unknown>): { key: string; title: string; text: string }[] {
  return Object.entries(value).flatMap(([key, data]) =>
    summaryLabels[key] && (typeof data === 'string' || typeof data === 'number')
      ? [{ key, title: summaryLabels[key], text: String(data) }]
      : [],
  )
}

async function open(id: Id): Promise<void> {
  const item = items.value.find((row) => row.id === id)
  if (!item) return
  error.value = ''
  try {
    instance.value = await approvalInstance(item.instanceId)
    selected.value = item
    comment.value = ''
  } catch (cause: unknown) {
    error.value = errorText(cause)
  }
}

async function decide(action: 'approve' | 'reject'): Promise<void> {
  const current = selected.value
  if (!current || current.taskVersion === undefined || sending.value) return
  if (action === 'reject' && !comment.value.trim()) {
    error.value = '请填写驳回原因'
    return
  }
  error.value = ''
  const path = `/workflow/tasks/${idPart(current.id)}/${action}`
  const body = { version: current.taskVersion, comment: comment.value.trim() || undefined }
  const accepted = await run([path, body], async (key) => {
    await command(path, body, key)
    return true
  }, action === 'approve' ? '已同意' : '已驳回')
  if (accepted) {
    selected.value = undefined
    instance.value = undefined
    await load()
  }
}
</script>

<template>
  <view class="panel">
    <view class="section-title">
      <text class="heading">待办审批</text>
      <button v-if="selected" class="compact secondary" :disabled="sending" @click="selected = undefined">返回</button>
    </view>
    <text v-if="failure || commandError || error" class="error" role="alert">{{ error || commandError || failure }}</text>
    <text v-if="success" class="success">{{ success }}</text>
    <template v-if="selected && instance">
      <text class="subheading">{{ instance.title }}</text>
      <text class="muted">{{ instance.businessNo }} · {{ selected.nodeName }}</text>
      <view v-for="item in summary(instance.summary)" :key="item.key" class="inline summary-line">
        <text class="muted">{{ item.title }}</text><text>{{ item.text }}</text>
      </view>
      <text class="field-label">审批意见</text>
      <textarea v-model="comment" class="field field-text" aria-label="审批意见"
        placeholder="驳回时必填" :maxlength="500" :disabled="sending" auto-height />
      <wd-button block :loading="sending" @click="decide('approve')">同意</wd-button>
      <button class="secondary danger" :disabled="sending" @click="decide('reject')">驳回</button>
    </template>
    <WorkList v-else :rows="items.map((item) => ({
      id: item.id, title: item.title, subtitle: item.businessNo + ' · ' + (item.nodeName ?? ''),
    }))" :more="!!cursor" :busy="busy" @select="open" @more="load(true)" />
  </view>
</template>
