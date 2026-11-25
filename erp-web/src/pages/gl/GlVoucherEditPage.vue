<script setup lang="ts">
import { ElDatePicker } from 'element-plus/es/components/date-picker/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ref, computed, inject, watch, onScopeDispose } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { glSubjects, glVoucher, glConfig, glRead, saveGlVoucher } from '../../api/gl'
import { messageOf, isCancelled, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { subjectAux, glStatus } from '../../types/gl'
import type { GlLine, GlSubject, GlConfigRow, GlVoucher, GlVoucherInput } from '../../types/gl'
import GlOutputActions from '../../components/GlOutputActions.vue'
import GlAuxFields from '../../components/GlAuxFields.vue'
import { registerCompanyLeaveGuard } from '../../utils/unsaved'
import SourcelessSourceLink from '../../components/sales/SourcelessSourceLink.vue'
import SalesReturnSourceTag from '../../components/sales/SalesReturnSourceTag.vue'
import StatusTag from '../../components/StatusTag.vue'
const readOnlyPreview = inject('erp.readOnlyPreview', false)
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const subjects = ref<GlSubject[]>([]),
  words = ref<GlConfigRow[]>([]),
  cash = ref<GlConfigRow[]>([]),
  templates = ref<GlConfigRow[]>([]),
  saved = ref<GlVoucher>()
const options = ref<Record<string, { id: string; name: string; code?: string }[]>>({})
function blankLine(): GlLine {
  return { digest: '', subjectId: '', direction: 'D', amount: '', quantity: '0', aux: {} }
}
function initial(): GlVoucherInput {
  return {
    wordId: '',
    voucherDate: new Date().toLocaleDateString('en-CA'),
    attachmentCount: 0,
    lines: [blankLine(), { ...blankLine(), direction: 'C' }],
    remark: '',
  }
}
const form = ref<GlVoucherInput>(initial()),
  busy = ref(false),
  error = ref(''),
  baseline = ref(''),
  selectedTemplate = ref('')
const editable = computed(
  () =>
    session.can('gl_voucher:edit') &&
    (!saved.value ||
      (saved.value.source === 'manual' &&
        saved.value.status === 'draft' &&
        !saved.value.reversalOfId)),
)
const dirty = computed(() => editable.value && JSON.stringify(form.value) !== baseline.value)
let revision = 0,
  saveKey = newIdempotencyKey()
function leave(): boolean {
  return !dirty.value || window.confirm('凭证尚未保存，确定离开？')
}
const unregister = registerCompanyLeaveGuard(leave)
onBeforeRouteLeave(leave)
function subject(id: string): GlSubject | undefined {
  return subjects.value.find((s) => s.id === id)
}
function changed(line: GlLine): void {
  line.aux = {}
  line.cashFlowItemId = undefined
  line.quantity = '0'
  line.unitPrice = undefined
}
async function load(): Promise<void> {
  const current = ++revision
  saveKey = newIdempotencyKey()
  subjects.value = []
  words.value = []
  cash.value = []
  templates.value = []
  options.value = {}
  saved.value = undefined
  form.value = initial()
  baseline.value = JSON.stringify(form.value)
  busy.value = true
  error.value = ''
  try {
    const [s, w, c, t, o] = await Promise.all([
      glSubjects(),
      glConfig('words'),
      glConfig('cash-flow-items'),
      glConfig('voucher-templates'),
      glRead<Record<string, { id: string; name: string; code?: string }[]>>('choices'),
    ])
    if (current !== revision) return
    subjects.value = s
    words.value = w
    cash.value = c
    templates.value = t
    options.value = o
    form.value.wordId = w.find((x) => x.isDefault)?.id || w[0]?.id || ''
    if (route.params.id) {
      const v = await glVoucher(String(route.params.id))
      if (current !== revision) return
      saved.value = v
      form.value = {
        wordId: v.wordId,
        voucherDate: v.voucherDate,
        attachmentCount: v.attachmentCount,
        remark: v.remark || '',
        version: v.version,
        lines: v.lines.map((l) => ({
          ...l,
          aux: Object.fromEntries(
            ['customer', 'supplier', 'dept', 'employee', 'project', 'sku', 'custom'].map((type) => [
              type + 'Id',
              l[('aux' + type[0]!.toUpperCase() + type.slice(1) + 'Id') as keyof GlLine] || '0',
            ]),
          ),
        })),
      }
    }
    baseline.value = JSON.stringify(form.value)
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function save(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const v = await saveGlVoucher(saved.value?.id, form.value, saveKey)
    baseline.value = JSON.stringify(form.value)
    await router.replace('/gl/vouchers/' + v.id)
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
function applyTemplate(): void {
  const t = templates.value.find((v) => v.id === selectedTemplate.value)
  if (!t) return
  const parsed: unknown = JSON.parse(String(t.linesJson))
  if (!Array.isArray(parsed)) return
  form.value.wordId = String(t.wordId)
  form.value.lines = parsed as GlLine[]
}
watch(
  [() => session.companyId, () => route.params.id],
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
  unregister()
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>
        {{ saved ? '凭证详情' : '新增手工凭证' }}
        <small v-if="saved">{{ glStatus[saved.status] }}</small>
      </h1>
      <p>
        {{
          saved?.source === 'business'
            ? '由运营账本生成，修改和冲销请回来源业务办理。'
            : '金额使用十进制填写，提交时服务器核对借贷平衡和辅助核算。'
        }}
      </p>
    </div>
    <div>
      <router-link v-if="!readOnlyPreview" class="text-link" to="/gl/vouchers">返回凭证列表</router-link
      ><el-button v-if="editable" type="primary" :loading="busy" @click="save">保存草稿</el-button>
    </div>
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section v-if="saved?.source === 'business' && saved.sourceNo" class="voucher-source">
    <strong>业务来源：</strong><SourcelessSourceLink :source="saved" />
    <SalesReturnSourceTag :mode="saved.returnMode" />
    <StatusTag v-if="saved.refundMode === 'sourceless_credit'" label="退货信用额退款" tone="info" />
    <SourcelessSourceLink v-if="saved.sourceType === 'document_reversal'" :source="saved" original />
  </section>
  <GlOutputActions v-if="saved" type="vouchers" :print-id="saved.id" />
  <section v-loading="busy" class="panel voucher-form">
    <div class="voucher-header">
      <label
        >凭证字<el-select v-model="form.wordId" :disabled="!editable || !!saved"
          ><el-option
            v-for="w in words"
            :key="w.id"
            :value="w.id"
            :label="w.code" /></el-select></label
      ><label
        >凭证日期<el-date-picker
          v-model="form.voucherDate"
          value-format="YYYY-MM-DD"
          :disabled="!editable" /></label
      ><label
        >附件张数<el-input-number
          v-model="form.attachmentCount"
          :min="0"
          :max="9999"
          :disabled="!editable" /></label
      ><label v-if="editable"
        >套用模板<el-select
          v-model="selectedTemplate"
          placeholder="选择模板"
          @change="applyTemplate"
          ><el-option v-for="t in templates" :key="t.id" :value="t.id" :label="t.name" /></el-select
      ></label>
    </div>
    <el-table :data="form.lines"
      ><el-table-column type="index" width="45" /><el-table-column label="摘要" min-width="160"
        ><template #default="{ row }"
          ><el-input
            v-model="row.digest"
            :disabled="!editable"
            maxlength="200" /></template></el-table-column
      ><el-table-column label="科目" min-width="230"
        ><template #default="{ row }"
          ><el-select
            v-model="row.subjectId"
            filterable
            :disabled="!editable"
            @change="changed(row)"
            ><el-option
              v-for="s in subjects.filter((s) => s.isLeaf && s.status === 'active')"
              :key="s.id"
              :value="s.id"
              :label="s.code + ' ' + s.name" /></el-select></template></el-table-column
      ><el-table-column label="方向" width="95"
        ><template #default="{ row }"
          ><el-select v-model="row.direction" :disabled="!editable"
            ><el-option label="借" value="D" /><el-option
              label="贷"
              value="C" /></el-select></template></el-table-column
      ><el-table-column label="金额" min-width="145"
        ><template #default="{ row }"
          ><el-input
            v-model="row.amount"
            :disabled="!editable"
            inputmode="decimal" /></template></el-table-column
      ><el-table-column label="数量 / 单价" min-width="150"
        ><template #default="{ row }"
          ><template v-if="subject(row.subjectId)?.quantityUnit"
            ><el-input
              v-model="row.quantity"
              :disabled="!editable"
              :placeholder="subject(row.subjectId)?.quantityUnit" /><el-input
              v-model="row.unitPrice"
              :disabled="!editable"
              placeholder="单价" /></template
          ><span v-else>—</span></template
        ></el-table-column
      ><el-table-column label="辅助核算 / 现金流量" min-width="220"
        ><template #default="{ row }"
          ><GlAuxFields
            v-model="row.aux"
            :required="subjectAux(subject(row.subjectId))"
            :options="options"
            :disabled="!editable" /><el-select
            v-if="subject(row.subjectId)?.isCash || subject(row.subjectId)?.isBank"
            v-model="row.cashFlowItemId"
            :disabled="!editable"
            placeholder="现金流量项目"
            ><el-option
              v-for="c in cash"
              :key="c.id"
              :value="c.id"
              :label="c.name" /></el-select></template></el-table-column
      ><el-table-column v-if="editable" label="操作" width="65"
        ><template #default="{ $index }"
          ><el-button link :disabled="form.lines.length <= 2" @click="form.lines.splice($index, 1)"
            >移除</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-button
      v-if="editable"
      :disabled="form.lines.length >= 1000"
      @click="form.lines.push(blankLine())"
      >添加分录</el-button
    ><el-form-item label="备注"
      ><el-input v-model="form.remark" :disabled="!editable" maxlength="500"
    /></el-form-item>
  </section>
</template>
<style scoped>
.voucher-source {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-block: 16px;
}
.voucher-form {
  padding: 20px;
}
.voucher-header {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.voucher-header label {
  display: grid;
  gap: 8px;
}
.voucher-header .el-select {
  width: 150px;
}
.voucher-form > .el-button {
  margin: 16px 0;
}
.page-heading .el-button {
  margin-left: 14px;
}
</style>
