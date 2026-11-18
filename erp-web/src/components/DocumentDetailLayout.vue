<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useOrderRelations } from '../hooks/useOrderRelations'
import type { OrderRelationContext } from '../hooks/useOrderRelations'
import { useSessionStore } from '../stores/session'
import { documentStateLabel, documentStateTag } from '../types/document-ui'
import { formatMoney } from '../utils/format'
import DocumentAuditTrail from './DocumentAuditTrail.vue'
import StatusTag from './StatusTag.vue'

const props = defineProps<{ split: boolean; order?: OrderRelationContext }>()
const session = useSessionStore()
const active = ref('details')
const { sections, load } = useOrderRelations(() => props.order)
const panes = computed(() =>
  [
    {
      key: 'fulfillment',
      label: '收发记录',
      groups: sections.value.filter((group) => group.section === 'fulfillment'),
    },
    {
      key: 'settlement',
      label: '结算',
      groups: sections.value.filter((group) => group.section === 'settlement'),
    },
  ].filter((pane) => pane.groups.length),
)
const references = computed(() => {
  const seen = new Set<string>()
  return sections.value
    .flatMap((group) =>
      group.page.items.flatMap((item) => {
        const resource = group.linkResource ?? group.resource
        const key = `${resource}:${item.documentId}`
        if (seen.has(key)) return []
        seen.add(key)
        return [{ key, item, label: group.label, resource }]
      }),
    )
    .slice(0, 4)
})
const showAudit = computed(() => !!props.order && !!session.companyId && session.can('audit:view'))

watch(
  () => [props.order?.id, session.companyId],
  () => {
    active.value = 'details'
  },
)
</script>

<template>
  <div v-if="split" class="document-detail-grid">
    <section class="document-detail-main" aria-label="订单内容">
      <el-tabs v-model="active">
        <el-tab-pane label="明细" name="details"><slot /></el-tab-pane>
        <el-tab-pane
          v-for="pane in panes"
          :key="pane.key"
          :label="pane.label"
          :name="pane.key"
          lazy
        >
          <div
            v-for="group in pane.groups"
            :key="group.resource"
            v-loading="group.page.busy"
            class="panel order-relation-group"
          >
            <div class="document-section-title">
              <h2>{{ group.label }}</h2>
            </div>
            <el-alert
              v-if="group.page.error"
              type="error"
              :title="group.page.error"
              :closable="false"
            />
            <el-table v-if="group.page.items.length" :data="group.page.items" row-key="id">
              <el-table-column label="单号" min-width="160">
                <template #default="{ row }">
                  <RouterLink
                    :to="{
                      name: (group.linkResource ?? group.resource) + '-detail',
                      params: { id: row.documentId },
                    }"
                    >{{ row.documentNo }}</RouterLink
                  >
                </template>
              </el-table-column>
              <el-table-column
                prop="businessDate"
                :label="group.linkResource ? '核销日期' : '业务日期'"
                min-width="108"
              />
              <el-table-column
                v-if="group.linkResource"
                label="核销金额"
                min-width="110"
                align="right"
              >
                <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="状态" min-width="92">
                <template #default="{ row }">
                  <StatusTag
                    v-if="group.linkResource"
                    :tone="row.status === 'active' ? 'success' : 'neutral'"
                    :label="
                      row.status === 'active'
                        ? '已核销'
                        : documentStateLabel(group.resource, row.status)
                    "
                  />
                  <StatusTag
                    v-else
                    :tone="documentStateTag(group.resource, row.status)"
                    :label="documentStateLabel(group.resource, row.status)"
                  />
                </template>
              </el-table-column>
            </el-table>
            <el-empty
              v-else-if="!group.page.busy && !group.page.error"
              description="当前权限下暂无关联记录"
              :image-size="56"
            />
            <el-button
              v-if="group.page.error || group.page.next"
              :loading="group.page.busy"
              @click="load(group.resource, !!group.page.next)"
            >
              {{ group.page.error ? '重试' : '加载更多' }}
            </el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="附件" name="attachments" lazy><slot name="attachments" /></el-tab-pane>
        <el-tab-pane
          v-if="showAudit && order && session.companyId"
          label="操作日志"
          name="audit"
          lazy
        >
          <DocumentAuditTrail
            :key="order.version"
            :owner-type="order.kind === 'sales-orders' ? 'sales_order' : 'purchase_order'"
            :owner-id="order.id"
            :company-id="session.companyId"
          />
        </el-tab-pane>
      </el-tabs>
    </section>
    <aside class="document-detail-side" aria-label="审批与关联信息">
      <slot name="progress" />
      <section v-if="panes.length" class="panel order-related-side" aria-label="关联单据">
        <h2>关联单据</h2>
        <ul v-if="references.length">
          <li v-for="entry in references" :key="entry.key">
            <span>{{ entry.label }}</span>
            <RouterLink
              class="text-link"
              :to="{ name: entry.resource + '-detail', params: { id: entry.item.documentId } }"
              >{{ entry.item.documentNo }}</RouterLink
            >
          </li>
        </ul>
        <p v-else-if="sections.some((group) => group.page.busy)" role="status">正在读取关联单据</p>
        <p v-else-if="sections.some((group) => group.page.error)">
          关联单据加载失败，请在对应页签重试。
        </p>
        <p v-else>当前权限下暂无关联单据</p>
        <div class="order-related-shortcuts">
          <el-button
            v-for="pane in panes"
            :key="pane.key"
            link
            type="primary"
            @click="active = pane.key"
            >查看{{ pane.label }}</el-button
          >
        </div>
      </section>
    </aside>
  </div>
  <template v-else>
    <slot />
    <slot name="progress" />
    <slot name="attachments" />
  </template>
</template>

<style scoped>
.document-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr);
  align-items: start;
  gap: 16px;
}
.document-detail-main,
.document-detail-side {
  min-width: 0;
}
.document-detail-main :deep(.el-tabs__header) {
  margin-bottom: 8px;
}
.document-detail-side,
.order-relation-group + .order-relation-group {
  margin-top: 8px;
}
.order-related-side ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.order-related-side {
  padding: 16px;
}
.order-related-side h2 {
  margin: 0 0 8px;
  font-size: 16px;
  line-height: 24px;
}
.order-related-side li {
  display: grid;
  gap: 4px;
  padding: 6px 0;
  overflow-wrap: anywhere;
}
.order-related-side li + li {
  border-top: 1px solid var(--el-border-color-lighter);
}
.order-related-side li span,
.order-related-side p {
  color: var(--el-text-color-secondary);
}
.order-related-shortcuts {
  display: flex;
  margin-top: 8px;
  gap: 8px;
  flex-wrap: wrap;
}
.order-related-shortcuts .el-button + .el-button {
  margin-left: 0;
}
@media (max-width: 1199px) {
  .document-detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
