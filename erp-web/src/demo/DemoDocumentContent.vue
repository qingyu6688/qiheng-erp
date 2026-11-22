<script setup lang="ts">
import { computed, defineAsyncComponent, provide } from 'vue'
import { routeLocationKey } from 'vue-router'
import type { RouteLocationNormalized } from 'vue-router'
import { demoSalesKinds, demoPurchaseKinds } from './scope'

const props = defineProps<{ route: RouteLocationNormalized }>()
// 详情读取自己的单据编号，主页面路由与列表查询始终保持原值。
provide(routeLocationKey, props.route)
const salesKind = computed(() => demoSalesKinds.find((item) => item.path === props.route.path.split('/')[1])?.path)
const purchaseKind = computed(() => demoPurchaseKinds.find((item) => item.path === props.route.path.split('/')[1])?.path)
const SalesDetail = defineAsyncComponent(() => import('../pages/SalesDetailPage.vue'))
const PurchaseDetail = defineAsyncComponent(() => import('../pages/ProcurementDetailPage.vue'))
const WorkOrderDetail = defineAsyncComponent(() => import('../pages/mfg/ManufacturingPage.vue'))
const VoucherDetail = defineAsyncComponent(() => import('../pages/gl/GlVoucherEditPage.vue'))
const WikiDetail = defineAsyncComponent(() => import('../pages/ai/AiWikiPage.vue'))
const AiDetail = defineAsyncComponent(() => import('./ai/DemoAiPreview.vue'))
</script>

<template>
  <SalesDetail v-if="salesKind" :kind="salesKind" />
  <PurchaseDetail v-else-if="purchaseKind" :kind="purchaseKind" />
  <WorkOrderDetail area="work-orders" v-else-if="route.path.startsWith('/mfg/work-orders/')" />
  <VoucherDetail v-else-if="route.path.startsWith('/gl/vouchers/')" />
  <WikiDetail v-else-if="route.path.startsWith('/ai/wiki/')" />
  <AiDetail v-else-if="route.path.startsWith('/ai/')" />
</template>
