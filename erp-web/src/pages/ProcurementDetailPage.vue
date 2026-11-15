<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {procurementDocument,procurementCommand} from '../api/procurement'
import {commandResult,financeWrite} from '../api/sales'
import {ApiError,isCancelled,messageOf,newIdempotencyKey} from '../api/http'
import {actionLabels,statusLabels} from '../types/sales'
import {procurementSpecs,procurementAction,adjustmentLabels} from '../types/procurement'
import type {ProcurementDocument,ProcurementKind} from '../types/procurement'
import ProcurementEditor from '../components/procurement/ProcurementEditor.vue'
import {useSessionStore} from '../stores/session'
import DocumentFilesPanel from '../components/DocumentFilesPanel.vue'
const props=defineProps<{kind:ProcurementKind}>(),route=useRoute(),router=useRouter(),session=useSessionStore()
const spec=computed(()=>procurementSpecs.find(s=>s.path===props.kind)!)
const value=ref<ProcurementDocument>(),loading=ref(false),error=ref(''),editing=ref(false),changing=ref(false),action=ref(''),reason=ref(''),busy=ref(false),uncertain=ref(false),conflict=ref(false)
const closeRows=ref<{lineId:string;name:string;qty:string;remaining:string}[]>([])
let key=newIdempotencyKey()
function label(name:string):string{return procurementAction(props.kind,name)||actionLabels[name]||name}
function confirmationLabel():string{const name=label(action.value);return name.startsWith('确认')?name:'确认'+name}
async function load():Promise<void>{loading.value=true;error.value='';try{value.value=await procurementDocument(props.kind,String(route.params.id))}catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}finally{loading.value=false}}
function choose(name:string):void{
 if(name==='update'){editing.value=true;return}
 if(name==='change'||name==='changes'){changing.value=true;return}
 action.value=name.replaceAll('-','_');key=newIdempotencyKey();reason.value='';uncertain.value=false;conflict.value=false
 closeRows.value=(value.value?.lines??[]).map(l=>({lineId:l.id,name:l.sku?.name??l.skuName??String(l.lineNo??l.id),qty:'0',remaining:String((l.progress as Record<string,unknown>|undefined)?.closableQty??l.closableQty??'0')}))
}
function closeEditor():void{editing.value=false;changing.value=false}
async function saved():Promise<void>{closeEditor();await load()}
async function command():Promise<void>{
 if(!value.value||busy.value)return
 busy.value=true;error.value=''
 try{
  const change=value.value.change
  if(action.value==='change_approve'||action.value==='change_reject'){
   if(!change)throw new Error('当前没有待处理变更，请刷新')
   await financeWrite('purchase-orders/changes/'+change.id+'/'+(action.value==='change_approve'?'approve':'reject'),{version:change.version,comment:reason.value,reason:reason.value},key)
  } else await procurementCommand(props.kind,value.value.id,action.value,{version:value.value.version,reason:reason.value||undefined,comment:reason.value||undefined,lines:action.value==='close_lines'?closeRows.value.filter(r=>r.qty!==''&&!/^0+(?:\.0+)?$/.test(r.qty)).map(r=>({lineId:r.lineId,closeQty:r.qty})):undefined},key)
  const deleted=action.value==='delete';action.value='';if(deleted)await router.replace('/'+props.kind);else await load()
 }catch(cause:unknown){if(!isCancelled(cause)){error.value=messageOf(cause);if(cause instanceof ApiError){uncertain.value=cause.status===0||cause.code==='REQUEST_IN_PROGRESS';conflict.value=cause.code==='VERSION_CONFLICT'}}}finally{busy.value=false}
}
async function verify():Promise<void>{
 try{
  if(action.value==='delete'){
   try{const current=await procurementDocument(props.kind,String(route.params.id));uncertain.value=false;conflict.value=current.version!==value.value?.version;error.value=conflict.value?'草稿已更新，请关闭并刷新':'草稿仍存在，可使用原版本重新删除'}
   catch(cause:unknown){if(cause instanceof ApiError&&cause.status===404){action.value='';await router.replace('/'+props.kind)}else throw cause}
   return
  }
  const result=await commandResult(spec.value.permission+'.'+action.value,key)
  if(result.status==='succeeded'){action.value='';uncertain.value=false;await load()}
 }catch(cause:unknown){error.value=messageOf(cause);if(cause instanceof ApiError&&cause.status===404)uncertain.value=false}
}
onMounted(load)
</script>
<template>
 <div class="page-heading"><div><router-link class="text-link" :to="'/'+kind">返回{{spec.title}}</router-link><h1>{{value?.documentNo??spec.singular}}</h1></div><div v-if="value" class="sales-actions"><el-button v-for="name in value.allowedActions" :key="name" :type="['approve','post','confirm'].includes(name)?'primary':undefined" @click="choose(name)">{{label(name)}}</el-button></div></div>
 <el-alert v-if="error" type="error" :title="error" :closable="false"/><p v-if="loading">正在加载…</p>
 <template v-if="value"><section class="panel sales-detail-header"><dl><dt>状态</dt><dd><el-tag type="info">{{value.status==='posted'?'已过账':statusLabels[value.status]??value.status}}</el-tag></dd><dt>业务日期</dt><dd>{{value.businessDate}}</dd><dt>供应商 / 往来</dt><dd>{{value.supplier?.name??value.supplierName??value.partnerName??'—'}}</dd><dt>金额</dt><dd>{{value.totalAmount??value.payableAmount??value.debitAmount??value.amount??'—'}}</dd><dt>仓库 / 账户</dt><dd>{{value.warehouseName??value.cashAccountName??value.fromAccountName??'—'}}</dd><dt>版本</dt><dd>{{value.version}}</dd></dl><p v-if="value.toAccountName">转入账户 {{value.toAccountName}}，手续费 {{value.feeAmount}}</p><p v-if="value.adjustType">调整类型 {{adjustmentLabels[value.adjustType]??value.adjustType}}</p><p v-if="value.reason">原因 {{value.reason}}</p><p v-if="value.remark">备注 {{value.remark}}</p></section>
 <section v-if="value.lines?.length" class="panel"><el-table :data="value.lines" row-key="id"><el-table-column label="商品" min-width="140"><template #default="{row}">{{row.sku?.name??row.skuName??row.skuId}}</template></el-table-column><el-table-column label="数量 / 单位"><template #default="{row}">{{row.qty}} {{row.unit?.name??row.unitName}}</template></el-table-column><el-table-column label="含税金额"><template #default="{row}">{{row.payableNet??row.payableAmount??row.debitAmount??'—'}}</template></el-table-column><el-table-column v-if="kind==='purchase-orders'" label="可收上限 / 已收"><template #default="{row}">{{row.receiveLimitBaseQty??row.receiveLimitQty??'—'}} / {{row.progress?.receivedQty??row.receivedBaseQty??'0'}}</template></el-table-column><el-table-column v-if="session.can('cost:view')" label="成本"><template #default="{row}">{{row.costAmount??row.inputTotalCost??'—'}}</template></el-table-column></el-table></section></template>
 <DocumentFilesPanel v-if="value" :owner-type="spec.permission" :owner-id="value.id" />
 <ProcurementEditor v-if="(editing||changing)&&value" :kind="kind" :original="value" :change="changing" @close="closeEditor" @saved="saved"/>
 <el-dialog :model-value="Boolean(action)" :title="label(action)" width="600px" :close-on-click-modal="false" :show-close="!busy" @close="action=''"><fieldset class="form-fieldset" :disabled="busy||uncertain||conflict"><p>{{value?.documentNo}}</p><template v-if="action==='close_lines'"><p>关闭数量按基础单位填写。</p><div v-for="row in closeRows" :key="row.lineId" class="relation-row"><label :for="'purchase-close-'+row.lineId">{{row.name}} · 可关闭 {{row.remaining}}</label><el-input :id="'purchase-close-'+row.lineId" v-model="row.qty"/><el-button text @click="row.qty=row.remaining">全部关闭</el-button></div></template><el-form-item v-if="['reject','cancel','close','close_lines','revoke_approval','change_reject'].includes(action)" label="意见 / 原因" for="purchase-command-reason"><el-input id="purchase-command-reason" v-model="reason" type="textarea" maxlength="200"/></el-form-item></fieldset><el-alert v-if="error" type="error" :title="error" :closable="false"/><p v-if="conflict">单据已更新，请关闭并刷新。</p><template #footer><el-button :disabled="busy" @click="action=''">关闭</el-button><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button><el-button v-else type="primary" :loading="busy" :disabled="conflict" @click="command">{{confirmationLabel()}}</el-button></template></el-dialog>
</template>
