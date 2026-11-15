<script setup lang="ts">
import { computed,onMounted,ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { document,candidates,financeWrite,commandResult } from '../api/sales'
import { ApiError,isCancelled,messageOf,newIdempotencyKey } from '../api/http'
import type { SalesKind,SalesDocument } from '../types/sales'
import { salesSpecs,actionLabels,statusLabels,salesStatusLabel } from '../types/sales'
import OrderEditor from '../components/sales/OrderEditor.vue'
import DocumentEditor from '../components/sales/DocumentEditor.vue'
import CommandDialog from '../components/sales/CommandDialog.vue'
import { useSessionStore } from '../stores/session'
const props=defineProps<{kind:SalesKind}>()
const route=useRoute(),router=useRouter(),session=useSessionStore()
const spec=computed(()=>salesSpecs.find(s=>s.path===props.kind)!)
const value=ref<SalesDocument>(),error=ref(''),loading=ref(false),editing=ref(false),changing=ref(false),action=ref('')
const history=ref<Record<string,unknown>[]>([])
const commandWarnings=ref<{message:string}[]>([])
const decision=ref<{action:'approve'|'reject';changeId:string;version:number;key:string}>()
const decisionReason=ref(''),decisionBusy=ref(false),decisionError=ref(''),decisionUncertain=ref(false),decisionConflict=ref(false)
async function load():Promise<void>{
 loading.value=true;error.value=''
 try{value.value=await document(props.kind,String(route.params.id));if(props.kind==='sales-orders')history.value=(await candidates('sales-orders/'+route.params.id+'/reservations')).items}
 catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}
 finally{loading.value=false}
}
function choose(name:string):void{
 if(name==='update'){editing.value=true;return}
 if(name==='change'||name==='changes'){changing.value=true;return}
 action.value=name
}
async function saved():Promise<void>{editing.value=false;changing.value=false;action.value='';await load();if(!value.value&&error.value)await router.replace('/'+props.kind)}
async function commandSaved(warnings:{message:string}[]=[]):Promise<void>{
 commandWarnings.value=warnings
 const deleted=action.value==='delete';action.value=''
 if(deleted){await router.replace('/'+props.kind);return}await saved()
}
function changeDecision(name:'approve'|'reject'):void{
 if(!value.value)return
 const change=value.value.change
 if(!change||change.status!=='pending'){error.value='请刷新后重试，当前没有待处理的变更信息';return}
 decision.value={action:name,changeId:change.id,version:change.version,key:newIdempotencyKey()}
 decisionReason.value='';decisionError.value='';decisionUncertain.value=false;decisionConflict.value=false
}
async function submitDecision():Promise<void>{
 if(!decision.value||decisionBusy.value)return
 const current=decision.value
 if(current.action==='reject'&&!decisionReason.value.trim()){decisionError.value='请填写驳回意见';return}
 decisionBusy.value=true;decisionError.value=''
 try{
  await financeWrite('sales-orders/changes/'+current.changeId+'/'+current.action,{version:current.version,comment:decisionReason.value||undefined},current.key)
  decision.value=undefined;await load()
 }catch(cause:unknown){if(!isCancelled(cause)){
  decisionError.value=messageOf(cause)
  if(cause instanceof ApiError){decisionUncertain.value=cause.status===0||cause.code==='REQUEST_IN_PROGRESS';decisionConflict.value=cause.code==='VERSION_CONFLICT'}
 }}finally{decisionBusy.value=false}
}
async function verifyDecision():Promise<void>{
 if(!decision.value||decisionBusy.value)return
 decisionBusy.value=true
 try{
  const result=await commandResult('sales_order.change_'+decision.value.action,decision.value.key)
  if(result.status==='succeeded'){decision.value=undefined;await load()}
 }catch(cause:unknown){if(!isCancelled(cause)){
  decisionError.value=messageOf(cause)
  if(cause instanceof ApiError&&cause.status===404){decisionUncertain.value=false;decisionError.value='尚无已提交结果，可使用原请求重试'}
 }}finally{decisionBusy.value=false}
}
onMounted(load)
</script>
<template>
 <div class="page-heading"><div><router-link class="text-link" :to="'/'+kind">返回{{ spec.title }}</router-link><h1>{{ value?.documentNo??value?.invoiceNo??spec.singular }}</h1></div><div v-if="value" class="sales-actions">
  <template v-for="name in value.allowedActions" :key="name"><el-button v-if="name!=='change_approve'&&name!=='change_reject'" :type="['approve','post','sign','confirm','register_paid'].includes(name)?'primary':undefined" @click="choose(name)">{{ actionLabels[name]??name }}</el-button><el-button v-else @click="changeDecision(name==='change_approve'?'approve':'reject')">{{ actionLabels[name] }}</el-button></template>
 </div></div>
 <el-alert v-if="error" type="error" :title="error" :closable="false"/><p v-if="loading" role="status">正在加载单据…</p>
 <template v-if="value">
  <section class="panel sales-detail-header">
   <dl><dt>状态</dt><dd><el-tag type="info">{{ salesStatusLabel(kind,value.status) }}</el-tag></dd><dt>业务日期</dt><dd>{{ value.businessDate??value.invoiceDate }}</dd><dt>客户 / 往来</dt><dd>{{ value.customer?.name??value.customerName??value.partnerName??'—' }}</dd><dt>金额</dt><dd>¥{{ value.totalAmount??value.receivableAmount??value.creditAmount??value.amount??'—' }}</dd><dt>税额</dt><dd>{{ value.taxAmount??'—' }}</dd><dt>版本</dt><dd>{{ value.version }}</dd></dl>
   <div v-if="kind==='sales-orders'" class="sales-progress"><span>履约 {{ statusLabels[value.fulfillmentStatus??'none']??value.fulfillmentStatus }}</span><span>结算 {{ statusLabels[value.settlementStatus??'none']??value.settlementStatus }}</span><span>售后 {{ statusLabels[value.afterSaleStatus??'none']??value.afterSaleStatus }}</span></div>
   <p v-if="value.reason">原因 {{ value.reason }}</p><p v-if="value.remark">备注 {{ value.remark }}</p>
  </section>
  <el-alert v-for="(warning,index) in [...commandWarnings,...value.warnings??[]]" :key="index" type="warning" :title="warning.message" :closable="false"/>
  <section v-if="value.lines?.length" class="panel">
   <el-table :data="value.lines" row-key="id">
    <el-table-column label="商品 / 来源" min-width="160"><template #default="{row}">{{ row.sku?.name??row.skuName??row.sourceNo??row.orderLineId??row.receivableId??row.id }}</template></el-table-column>
    <el-table-column label="数量" min-width="100"><template #default="{row}">{{ row.qty??row.baseQty??'—' }}</template></el-table-column>
    <el-table-column label="单位" min-width="80"><template #default="{row}">{{ row.unit?.name??row.unitName??'—' }}</template></el-table-column>
    <el-table-column label="金额" min-width="120"><template #default="{row}">{{ row.receivableNet??row.receivableAmount??row.creditAmount??row.amount??'—' }}</template></el-table-column>
    <el-table-column v-if="kind==='sales-orders'" label="可发 / 预留" min-width="140"><template #default="{row}">{{ row.progress?.shippableQty }} / {{ statusLabels[row.reservationState??'none']??row.reservationState }}</template></el-table-column>
    <el-table-column v-if="kind==='shipments'" label="签收 / 差异" min-width="140"><template #default="{row}">{{ row.signedQty??'—' }} / {{ row.diffQty??'—' }} {{ row.diffDisposition==='pending'?'待处理':'' }}</template></el-table-column>
    <el-table-column v-if="session.can('cost:view')&&kind!=='sales-orders'" label="成本" min-width="100"><template #default="{row}">{{ row.costAmount??'—' }}</template></el-table-column>
   </el-table>
  </section>
  <section v-if="value.fees?.length" class="panel sales-detail-header"><h2>费用</h2><p v-for="(fee,index) in value.fees" :key="index">{{ fee.feeType==='freight'?'运费':'其他费用' }} · {{ fee.bearer==='buyer'?'买方承担':'我方承担' }} · ¥{{ fee.amount }}</p></section>
  <section v-if="kind==='sales-orders'&&history.length" class="panel sales-detail-header"><h2>库存预留记录</h2><el-table :data="history"><el-table-column prop="remainingQty" label="剩余预留"/><el-table-column label="状态"><template #default="{row}">{{ statusLabels[row.status]??row.status }}</template></el-table-column><el-table-column prop="expireAt" label="到期时间"/></el-table></section>
 </template>
 <OrderEditor v-if="(editing||changing)&&value&&kind==='sales-orders'" :original="value" :change="changing" @close="editing=false;changing=false" @saved="saved"/>
 <DocumentEditor v-if="editing&&value&&kind!=='sales-orders'" :kind="kind" :original="value" @close="editing=false" @saved="saved"/>
 <CommandDialog v-if="action&&value" :kind="kind" :document="value" :action="action" @close="action=''" @saved="commandSaved"/>
 <el-dialog v-if="decision" :model-value="true" :title="decision.action==='approve'?'批准订单变更':'驳回订单变更'" width="520px" :close-on-click-modal="false" :show-close="!decisionBusy" @close="decision=undefined">
  <fieldset class="form-fieldset" :disabled="decisionBusy||decisionUncertain||decisionConflict"><p>变更版本 {{ decision.version }}</p><el-form-item label="审批意见"><el-input v-model="decisionReason" aria-label="变更审批意见" type="textarea" maxlength="500"/></el-form-item></fieldset>
  <el-alert v-if="decisionError" type="error" :title="decisionError" :closable="false"/><p v-if="decisionUncertain">结果确认中，请查询原请求结果。</p><p v-if="decisionConflict">变更已更新，请关闭并刷新后重试。</p>
  <template #footer><el-button :disabled="decisionBusy" @click="decision=undefined">关闭</el-button><el-button v-if="decisionUncertain" type="primary" :loading="decisionBusy" @click="verifyDecision">查询结果</el-button><el-button v-else type="primary" :disabled="decisionConflict" :loading="decisionBusy" @click="submitDecision">确认</el-button></template>
 </el-dialog>
</template>
