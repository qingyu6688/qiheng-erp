<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { readCrmCore, writeCrmCore } from '../../api/crm-core'
import { isCancelled, messageOf } from '../../api/http'
import type { DictionaryItem, Page } from '../../api/platform'
import type { CrmEmployee, CrmFollowSubject, CrmFollowUp } from '../../types/crm-core'
import { formatDateTime } from '../../utils/format'
import { crmDictionaryLabel, crmTimestamp } from './crm-core-format'

const props = defineProps<{
  type: CrmFollowSubject
  id: string
  canWrite: boolean
  methods: DictionaryItem[]
  employees: CrmEmployee[]
}>()
const emit = defineEmits<{ changed: [] }>()
const rows = ref<CrmFollowUp[]>([])
const after = ref<string | null>(null)
const method = ref('')
const content = ref('')
const nextContactAt = ref('')
const mentions = ref<string[]>([])
const busy = ref(false)
const error = ref('')
const availableMethods = computed(() => props.methods.filter((item) => Boolean(item.enabled)))

async function load(append = false): Promise<void> {
  const id = props.id
  const type = props.type
  error.value = ''
  try {
    const page = await readCrmCore<Page<CrmFollowUp>>('/follow-ups', {
      bizId: id,
      bizType: type,
      cursor: append ? after.value : undefined,
    })
    if (id !== props.id || type !== props.type) return
    rows.value = append ? [...rows.value, ...page.items] : page.items
    after.value = page.nextCursor
  } catch (cause: unknown) {
    if (!isCancelled(cause) && id === props.id && type === props.type)
      error.value = messageOf(cause)
  }
}

async function save(): Promise<void> {
  if (busy.value || !method.value || !content.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    await writeCrmCore('/follow-ups', {
      bizType: props.type,
      bizId: props.id,
      method: method.value,
      content: content.value.trim(),
      nextContactAt: crmTimestamp(nextContactAt.value),
      mentionedUserIds: mentions.value,
    })
    content.value = ''
    nextContactAt.value = ''
    mentions.value = []
    emit('changed')
    await load()
    ElMessage.success('跟进已登记')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

watch(
  () => [props.type, props.id],
  () => {
    rows.value = []
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <el-alert v-if="error" class="crm-error" :title="error" type="error" :closable="false" />
  <el-form v-if="canWrite" label-position="top" @submit.prevent="save">
    <div class="crm-form-grid">
      <el-form-item label="跟进方式" required>
        <el-select v-model="method" aria-label="跟进方式" placeholder="请选择">
          <el-option
            v-for="item in availableMethods"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="下次联系">
        <el-input v-model="nextContactAt" type="datetime-local" aria-label="下次联系" />
      </el-form-item>
    </div>
    <el-form-item label="跟进内容" required>
      <el-input
        v-model="content"
        type="textarea"
        :rows="3"
        :maxlength="5000"
        aria-label="跟进内容"
      />
    </el-form-item>
    <el-form-item label="提及同事">
      <el-select
        v-model="mentions"
        multiple
        filterable
        clearable
        :multiple-limit="20"
        aria-label="提及同事"
        placeholder="请选择同事"
      >
        <el-option
          v-for="employee in employees"
          :key="employee.id"
          :label="employee.displayName"
          :value="employee.id"
        />
      </el-select>
    </el-form-item>
    <el-button
      type="primary"
      native-type="submit"
      :loading="busy"
      :disabled="!method || !content.trim()"
      >登记跟进</el-button
    >
  </el-form>
  <el-empty v-if="!rows.length" description="暂无跟进记录" :image-size="52" />
  <el-timeline v-else class="crm-timeline">
    <el-timeline-item v-for="row in rows" :key="row.id" :timestamp="formatDateTime(row.createdAt)">
      <strong>{{ crmDictionaryLabel(methods, row.method) }}</strong>
      <span v-if="row.authorName"> · {{ row.authorName }}</span>
      <p class="crm-note">{{ row.content }}</p>
      <span v-if="row.nextContactAt" class="crm-muted"
        >下次联系 {{ formatDateTime(row.nextContactAt) }}</span
      >
    </el-timeline-item>
  </el-timeline>
  <el-button v-if="after" @click="load(true)">更多跟进</el-button>
</template>

<style scoped>
.crm-timeline {
  margin-top: 20px;
}
.crm-note {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
