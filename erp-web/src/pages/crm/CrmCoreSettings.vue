<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { crmEmployees, readCrmCore, writeCrmCore } from '../../api/crm-core'
import { isCancelled, messageOf } from '../../api/http'
import { dictionaries } from '../../api/platform'
import type { DictionaryItem } from '../../api/platform'
import type { CrmEmployee, CrmOwnerLimit, CrmPoolRule } from '../../types/crm-core'

const rule = ref<CrmPoolRule>()
const limits = ref<CrmOwnerLimit[]>([])
const levels = ref<DictionaryItem[]>([])
const staff = ref<CrmEmployee[]>([])
const units = ref<{ id: string; name: string }[]>([])
const working = ref(false)
const editing = ref(false)
const error = ref('')
const limitForm = reactive<CrmOwnerLimit>(blankLimit())

function blankLimit(): CrmOwnerLimit {
  return { targetType: 'user', targetId: '', maxOwned: 30, maxLocked: 5, includeSold: false }
}

async function load(): Promise<void> {
  await perform(async () => {
    const results = await Promise.all([
      readCrmCore<CrmPoolRule>('/pool-rules'),
      readCrmCore<{ items: CrmOwnerLimit[] }>('/owner-limits'),
      dictionaries('crm_level'),
      crmEmployees(),
      readCrmCore<{ id: string; name: string }[]>('/department-options'),
    ])
    rule.value = results[0]
    limits.value = results[1].items
    levels.value = results[2].items?.crm_level ?? []
    staff.value = results[3]
    units.value = results[4]
  })
}

async function perform(action: () => Promise<void>): Promise<void> {
  if (working.value) return
  error.value = ''
  working.value = true
  try {
    await action()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    working.value = false
  }
}

async function saveRule(): Promise<void> {
  const current = rule.value
  if (!current) return
  await perform(async () => {
    rule.value = await writeCrmCore<CrmPoolRule>('/pool-rules', current, 'PUT')
    ElMessage.success('公海规则已保存')
  })
}

function editLimit(item?: CrmOwnerLimit): void {
  Object.assign(limitForm, blankLimit(), { version: undefined, targetName: undefined }, item)
  editing.value = true
}

async function saveLimit(): Promise<void> {
  await perform(async () => {
    const { targetType, targetId, maxOwned, maxLocked, includeSold, version } = limitForm
    await writeCrmCore(
      '/owner-limits',
      { targetType, targetId, maxOwned, maxLocked, includeSold, version },
      'PUT',
    )
    limits.value = (await readCrmCore<{ items: CrmOwnerLimit[] }>('/owner-limits')).items
    editing.value = false
    ElMessage.success('领取限制已保存')
  })
}

async function removeLimit(item: CrmOwnerLimit): Promise<void> {
  await perform(async () => {
    await writeCrmCore(`/owner-limits/${item.targetType}/${item.targetId}`, undefined, 'DELETE', {
      version: item.version,
    })
    limits.value = limits.value.filter(
      (row) => row.targetType !== item.targetType || row.targetId !== item.targetId,
    )
    ElMessage.success('已取消该领取限制')
  })
}

onMounted(load)
</script>

<template>
  <el-alert
    v-if="error && !editing"
    class="crm-error"
    :title="error"
    type="error"
    :closable="false"
  />
  <section v-if="rule" class="panel crm-settings">
    <div class="page-heading">
      <h2>公海规则</h2>
      <el-button type="primary" :loading="working" @click="saveRule">保存规则</el-button>
    </div>
    <el-form label-position="top">
      <div class="crm-form-grid">
        <el-form-item label="自动回收"
          ><el-switch v-model="rule.enabled" aria-label="启用自动回收"
        /></el-form-item>
        <el-form-item label="未跟进天数"
          ><el-input-number
            v-model="rule.noFollowDays"
            :min="1"
            :max="3650"
            aria-label="未跟进天数"
        /></el-form-item>
        <el-form-item label="未成交天数"
          ><el-input-number v-model="rule.noDealDays" :min="1" :max="3650" aria-label="未成交天数"
        /></el-form-item>
        <el-form-item label="提前提醒天数"
          ><el-input-number
            v-model="rule.notifyBeforeDays"
            :min="0"
            :max="3650"
            aria-label="提前提醒天数"
        /></el-form-item>
      </div>
      <el-form-item label="适用客户级别">
        <el-select
          v-model="rule.applyToLevels"
          multiple
          clearable
          placeholder="未选时适用所有级别"
          aria-label="适用客户级别"
        >
          <el-option
            v-for="level in levels"
            :key="level.value"
            :label="level.label"
            :value="level.value"
            :disabled="!level.enabled"
          />
        </el-select>
      </el-form-item>
    </el-form>
  </section>
  <section class="panel crm-settings">
    <div class="page-heading">
      <h2>拥有与锁定限制</h2>
      <el-button @click="editLimit()">新增限制</el-button>
    </div>
    <el-table v-loading="working" :data="limits" empty-text="尚未配置限制">
      <el-table-column label="对象"
        ><template #default="{ row }"
          >{{ row.targetType === 'user' ? '员工' : '部门' }} ·
          {{ row.targetName || row.targetId }}</template
        ></el-table-column
      >
      <el-table-column prop="maxOwned" label="最多拥有" width="105" />
      <el-table-column prop="maxLocked" label="最多锁定" width="105" />
      <el-table-column label="包含已成交" width="105"
        ><template #default="{ row }">{{
          row.includeSold ? '是' : '否'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="142"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="editLimit(row)">编辑</el-button
          ><el-button link type="danger" :disabled="working" @click="removeLimit(row)"
            >取消限制</el-button
          ></template
        ></el-table-column
      >
    </el-table>
  </section>
  <el-dialog
    v-model="editing"
    :title="limitForm.version === undefined ? '新增领取限制' : '编辑领取限制'"
    width="min(520px, 95vw)"
  >
    <el-alert v-if="error" class="crm-error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="saveLimit">
      <el-form-item label="限制类型"
        ><el-radio-group
          v-model="limitForm.targetType"
          :disabled="limitForm.version !== undefined"
          @change="limitForm.targetId = ''"
          ><el-radio value="user">员工</el-radio
          ><el-radio value="department">部门</el-radio></el-radio-group
        ></el-form-item
      >
      <el-form-item label="限制对象" required>
        <el-select
          v-model="limitForm.targetId"
          :disabled="limitForm.version !== undefined"
          filterable
          aria-label="限制对象"
          placeholder="请选择"
        >
          <template v-if="limitForm.targetType === 'user'"
            ><el-option
              v-for="item in staff"
              :key="item.id"
              :label="item.displayName"
              :value="item.id"
          /></template>
          <template v-else
            ><el-option v-for="item in units" :key="item.id" :label="item.name" :value="item.id"
          /></template>
          <el-option
            v-if="limitForm.version !== undefined && limitForm.targetName"
            :label="limitForm.targetName"
            :value="limitForm.targetId"
          />
        </el-select>
      </el-form-item>
      <div class="crm-form-grid">
        <el-form-item label="最多拥有"
          ><el-input-number v-model="limitForm.maxOwned" :min="0" aria-label="最多拥有"
        /></el-form-item>
        <el-form-item label="最多锁定"
          ><el-input-number
            v-model="limitForm.maxLocked"
            :min="0"
            :max="limitForm.maxOwned"
            aria-label="最多锁定"
        /></el-form-item>
      </div>
      <el-form-item label="已成交客户计入限制"
        ><el-switch v-model="limitForm.includeSold" aria-label="已成交客户计入限制"
      /></el-form-item>
      <el-button
        type="primary"
        native-type="submit"
        :disabled="!limitForm.targetId || limitForm.maxLocked > limitForm.maxOwned"
        :loading="working"
        >保存限制</el-button
      >
    </el-form>
  </el-dialog>
</template>
