<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { allRecords } from '../api/admin'
import { candidates,financeWrite,commandResult } from '../api/sales'
import { ApiError,isCancelled,messageOf,newIdempotencyKey } from '../api/http'
import type { DataRow } from '../types/admin'
import { useSessionStore } from '../stores/session'
const session=useSessionStore()
const partners=ref<DataRow[]>([]),partner=ref(''),debits=ref<Record<string,unknown>[]>([]),credits=ref<Record<string,unknown>[]>([]),allocations=ref<Record<string,unknown>[]>([])
const debit=ref(''),credit=ref(''),amount=ref(''),busy=ref(false),error=ref(''),notice=ref(''),uncertain=ref(false)
const revoke=ref<Record<string,unknown>>(),reason=ref(''),revokeBusy=ref(false),revokeUncertain=ref(false),revokeError=ref('')
let key=newIdempotencyKey(),revokeKey=newIdempotencyKey()
function selected(row:Record<string,unknown>):string{return String(row.sourceNo??row.id)+' · 未核销 '+String(row.openAmount)}
function changePartner():void{debit.value='';credit.value='';debits.value=[];credits.value=[];allocations.value=[];notice.value='';void load()}
async function load():Promise<void>{
 if(!partner.value)return
 busy.value=true;error.value=''
 try{
  const [items,history]=await Promise.all([candidates('open-items',{partnerId:partner.value,side:'ar',openOnly:true}),candidates('settlement-allocations',{partnerId:partner.value})])
  debits.value=items.items.filter(i=>i.direction==='D');credits.value=items.items.filter(i=>i.direction==='C');allocations.value=history.items
 }catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}finally{busy.value=false}
}
async function allocate():Promise<void>{
 busy.value=true;error.value=''
 try{await financeWrite('settlement-allocations',{partnerId:partner.value,allocations:[{debitItemId:debit.value,creditItemId:credit.value,amount:amount.value}]},key);key=newIdempotencyKey();debit.value='';credit.value='';amount.value='';notice.value='核销已完成';await load()}
 catch(cause:unknown){error.value=messageOf(cause);if(cause instanceof ApiError)uncertain.value=cause.status===0||cause.code==='REQUEST_IN_PROGRESS'}finally{busy.value=false}
}
async function verify():Promise<void>{
 try{const result=await commandResult('settlement.allocate',key);if(result.status==='succeeded'){uncertain.value=false;key=newIdempotencyKey();await load();notice.value='核销已完成'}}
 catch(cause:unknown){error.value=messageOf(cause);if(cause instanceof ApiError&&cause.status===404)uncertain.value=false}
}
async function confirmRevoke():Promise<void>{
 if(!revoke.value||revokeBusy.value)return
 revokeBusy.value=true;revokeError.value=''
 try{await financeWrite('settlement-allocations/'+revoke.value.id+'/revoke',{version:revoke.value.version,reason:reason.value},revokeKey);revoke.value=undefined;await load()}
 catch(cause:unknown){revokeError.value=messageOf(cause);if(cause instanceof ApiError)revokeUncertain.value=cause.status===0||cause.code==='REQUEST_IN_PROGRESS'}
 finally{revokeBusy.value=false}
}
function openRevoke(row:Record<string,unknown>):void{revoke.value=row;reason.value='';revokeKey=newIdempotencyKey();revokeUncertain.value=false;revokeError.value=''}
async function verifyRevoke():Promise<void>{
 try{const result=await commandResult('settlement.revoke',revokeKey);if(result.status==='succeeded'){revoke.value=undefined;revokeUncertain.value=false;await load()}}
 catch(cause:unknown){revokeError.value=messageOf(cause);if(cause instanceof ApiError&&cause.status===404)revokeUncertain.value=false}
}
onMounted(async()=>{try{partners.value=await allRecords('partners')}catch(cause:unknown){error.value=messageOf(cause)}})
</script>
<template>
 <div class="page-heading"><h1>核销工作台</h1></div>
 <section class="panel sales-detail-header">
  <el-form label-position="top"><el-form-item label="客户" for="settlement-customer"><el-select id="settlement-customer" v-model="partner" :disabled="busy||uncertain" filterable @change="changePartner"><el-option v-for="p in partners" :key="p.id" :value="p.id" :label="String(p.name)"/></el-select></el-form-item></el-form>
  <div class="settlement-columns">
   <section><h2>待收应收</h2><el-radio-group v-model="debit" :disabled="busy||uncertain"><el-radio v-for="row in debits" :key="String(row.id)" :value="String(row.id)">{{ selected(row) }}</el-radio></el-radio-group><p v-if="partner&&!debits.length">暂无待收应收</p></section>
   <section><h2>可用预收 / 退货信用额</h2><el-radio-group v-model="credit" :disabled="busy||uncertain"><el-radio v-for="row in credits" :key="String(row.id)" :value="String(row.id)">{{ selected(row) }}</el-radio></el-radio-group><p v-if="partner&&!credits.length">暂无可核销额度</p></section>
  </div>
  <el-form v-if="session.can('settlement:allocate')" label-position="top"><el-form-item label="本次核销金额" for="settlement-amount"><el-input id="settlement-amount" v-model="amount" :disabled="uncertain"/></el-form-item><el-button v-if="uncertain" type="primary" @click="verify">查询原核销结果</el-button><el-button v-else type="primary" :disabled="!debit||!credit||!amount" :loading="busy" @click="allocate">确认核销</el-button></el-form>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/><el-alert v-if="notice" type="success" :title="notice" :closable="false"/>
 </section>
 <section class="panel"><el-table :data="allocations"><el-table-column prop="id" label="核销编号" min-width="200"/><el-table-column prop="amount" label="金额"/><el-table-column prop="status" label="状态"/><el-table-column label="操作"><template #default="{row}"><el-button v-if="row.allowedActions?.includes('revoke')" :disabled="uncertain" text @click="openRevoke(row)">撤销核销</el-button></template></el-table-column></el-table></section>
 <el-dialog :model-value="Boolean(revoke)" title="撤销核销" width="480px" :close-on-click-modal="false" :show-close="!revokeBusy" @close="revoke=undefined"><el-form-item label="撤销原因"><el-input v-model="reason" :disabled="revokeBusy||revokeUncertain" maxlength="200"/></el-form-item><el-alert v-if="revokeError" type="error" :title="revokeError" :closable="false"/><template #footer><el-button :disabled="revokeBusy" @click="revoke=undefined">取消</el-button><el-button v-if="revokeUncertain" type="primary" @click="verifyRevoke">查询原撤销结果</el-button><el-button v-else type="primary" :loading="revokeBusy" :disabled="!reason" @click="confirmRevoke">确认撤销</el-button></template></el-dialog>
</template>
