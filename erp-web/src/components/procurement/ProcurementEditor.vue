<script setup lang="ts">
import { computed,onMounted,onUnmounted,reactive,ref,watch } from 'vue'
import { allRecords,record } from '../../api/admin'
import { candidates,commandResult,pricePreview } from '../../api/sales'
import { procurementCommand,saveProcurement } from '../../api/procurement'
import { ApiError,isCancelled,messageOf,newIdempotencyKey } from '../../api/http'
import { procurementSpecs,adjustmentLabels } from '../../types/procurement'
import type { ProcurementDocument,ProcurementKind } from '../../types/procurement'
import type { DataRow } from '../../types/admin'
import type { SalesSkuUnit } from '../../types/sales'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
const props=defineProps<{kind:ProcurementKind;original?:ProcurementDocument;change?:boolean}>()
const emit=defineEmits<{close:[];saved:[ProcurementDocument]}>()
const session=useSessionStore(),spec=computed(()=>procurementSpecs.find(s=>s.path===props.kind)!)
const form=reactive({
 supplierId:props.original?.supplierId??props.original?.supplier?.id??props.original?.partnerId??'',
 warehouseId:props.original?.warehouseId??'',businessDate:props.original?.businessDate??today(),deliveryDate:props.original?.deliveryDate??'',
 priceTaxInclusive:props.original?.priceTaxInclusive??true,orderDiscountAmount:props.original?.orderDiscountAmount??'0',
 reason:props.change?'':props.original?.reason??'',remark:props.original?.remark??'',
 paymentType:props.original?.paymentType??'supplier',cashAccountId:props.original?.cashAccountId??'',relatedOrderId:props.original?.relatedOrderId??'',
 expenseItemId:props.original?.expenseItemId??'',amount:props.original?.amount??'',bankSerialNo:props.original?.bankSerialNo??'',
 fromAccountId:props.original?.fromAccountId??'',toAccountId:props.original?.toAccountId??'',feeAmount:props.original?.feeAmount??'0',
 entryType:props.original?.entryType??'cash',direction:props.original?.direction??'D',adjustType:props.original?.adjustType??'opening',
})
interface InputLine {id?:string;skuId:string;warehouseId:string;unitId:string;qty:string;unitPrice:string;lineDiscountAmount:string;taxRate:string;sourceId:string;inputTotalCost:string;needDate:string}
const lines=ref<InputLine[]>((props.original?.lines??[]).map(l=>({
 id:l.id,skuId:l.skuId??l.sku?.id??'',warehouseId:l.warehouseId??l.warehouse?.id??'',unitId:l.unitId??l.unit?.id??'',qty:l.qty??'1',
 unitPrice:l.unitPrice??'',lineDiscountAmount:l.lineDiscountAmount??String(l.lineDiscount??'0'),taxRate:l.taxRate??'0.13',
 sourceId:l.requisitionLineId??l.orderLineId??l.receiptLineId??'',inputTotalCost:l.inputTotalCost??'',needDate:l.needDate??'',
})))
const fees=ref((props.original?.fees??[]).map(f=>({...f})))
const partners=ref<DataRow[]>([]),warehouses=ref<DataRow[]>([]),skus=ref<DataRow[]>([]),accounts=ref<DataRow[]>([]),expenses=ref<DataRow[]>([]),orders=ref<DataRow[]>([])
const sources=ref<Record<string,unknown>[]>([]),units=reactive<Record<string,SalesSkuUnit[]>>({})
const loading=ref(true),busy=ref(false),uncertain=ref(false),conflict=ref(false),error=ref(''),preview=ref<ProcurementDocument>()
const key=newIdempotencyKey()
const order=computed(()=>props.kind==='purchase-orders'),requisition=computed(()=>props.kind==='purchase-requisitions'),stock=computed(()=>props.kind==='stock-adjustments')
const products=computed(()=>order.value||requisition.value||stock.value),sourceLines=computed(()=>['goods-receipts','purchase-returns'].includes(props.kind))
const inputCost=computed(()=>stock.value&&['opening','gain'].includes(form.adjustType))
const hasLines=computed(()=>products.value||sourceLines.value),warehouseHead=computed(()=>stock.value||sourceLines.value)
const hasPartner=computed(()=>order.value||sourceLines.value||props.kind==='payments'&&form.paymentType==='supplier'||props.kind==='opening-entries'&&form.entryType!=='cash')
let timer:ReturnType<typeof setTimeout>|undefined,sequence=0,sourceSequence=0
function addLine():void{lines.value.push({skuId:'',warehouseId:'',unitId:'',qty:'1',unitPrice:'',lineDiscountAmount:'0',taxRate:'0.13',sourceId:'',inputTotalCost:'',needDate:''})}
async function chooseSku(line:InputLine,preferredUnit?:string):Promise<void>{
 if(!preferredUnit)line.sourceId=''
 try{const sku=await record('skus/'+line.skuId);units[line.skuId]=(sku.units as SalesSkuUnit[])??[];line.unitId=preferredUnit&&units[line.skuId].some(u=>u.unitId===preferredUnit)?preferredUnit:units[line.skuId].find(u=>u.isBase)?.unitId??'';line.unitPrice='';line.taxRate=String(sku.defaultTaxRate??'0.13')}
 catch(cause:unknown){error.value=messageOf(cause)}
}
function sourceLabel(row:Record<string,unknown>):string{return String(row.orderNo??row.receiptNo??row.requisitionNo??row.documentNo??row.id)+' · '+String(row.skuName??'商品')+' · 可用 '+String(row.receivableQty??row.returnableQty??row.orderableQty??'')+'（基础单位） · 按'+String(row.unitName??'原单位')+'录入'}
function chooseSource(line:InputLine):void{
 const source=sources.value.find(s=>String(s.id)===line.sourceId)
 if(!source)return
 line.unitId=String(source?.unitId??'')
 if(products.value){line.skuId=String(source?.skuId??'');void chooseSku(line,line.unitId)}
}
async function loadSources():Promise<void>{
 const current=++sourceSequence
 const path=order.value?'purchase-requisition-lines':props.kind==='goods-receipts'?'purchase-order-lines':'goods-receipt-lines'
 if(!order.value&&!form.supplierId){sources.value=[];return}
 try{const result=await candidates(path,{supplierId:form.supplierId||undefined,warehouseId:form.warehouseId||undefined,receivable:props.kind==='goods-receipts'||undefined,returnable:props.kind==='purchase-returns'||undefined,orderable:order.value||undefined});if(current===sourceSequence)sources.value=result.items}
 catch(cause:unknown){if(current===sourceSequence&&!isCancelled(cause))error.value=messageOf(cause)}
}
watch(()=>[form.supplierId,form.warehouseId],()=>{
 form.relatedOrderId=''
 if(sourceLines.value){lines.value=[];addLine();sources.value=[];void loadSources()}
})
watch(()=>form.entryType,()=>{form.direction=form.entryType==='payable'?'C':'D';form.supplierId='';form.cashAccountId=''})
function body():Record<string,unknown>{
 const common={businessDate:form.businessDate,remark:form.remark,version:props.original?.version}
 if(order.value)return {...common,supplierId:form.supplierId,deliveryDate:form.deliveryDate||null,priceTaxInclusive:form.priceTaxInclusive,orderDiscountAmount:form.orderDiscountAmount,reason:form.reason,
  lines:lines.value.map(l=>({id:l.id,requisitionLineId:l.sourceId||null,skuId:l.skuId,warehouseId:l.warehouseId||null,unitId:l.unitId,qty:l.qty,unitPrice:l.unitPrice||undefined,lineDiscountAmount:l.lineDiscountAmount,taxRate:session.can('document:change_tax_rate')?l.taxRate:undefined})),
  fees:fees.value.map(f=>({id:f.id,feeType:f.feeType,bearer:f.bearer,amount:f.amount,taxRate:session.can('document:change_tax_rate')?f.taxRate:undefined}))}
 if(requisition.value)return {...common,lines:lines.value.map(l=>({id:l.id,skuId:l.skuId,unitId:l.unitId,qty:l.qty,needDate:l.needDate||null}))}
 if(stock.value)return {...common,adjustType:form.adjustType,warehouseId:form.warehouseId,reason:form.reason,lines:lines.value.map(l=>({skuId:l.skuId,unitId:l.unitId,qty:l.qty,inputTotalCost:inputCost.value?l.inputTotalCost:undefined}))}
 if(sourceLines.value)return {...common,supplierId:form.supplierId,warehouseId:form.warehouseId,reason:form.reason,lines:lines.value.map(l=>({[props.kind==='goods-receipts'?'orderLineId':'receiptLineId']:l.sourceId,unitId:l.unitId,qty:l.qty}))}
 if(props.kind==='payments')return {...common,paymentType:form.paymentType,partnerId:form.paymentType==='supplier'?form.supplierId:null,expenseItemId:form.paymentType==='other_expense'?form.expenseItemId:null,cashAccountId:form.cashAccountId,amount:form.amount,relatedOrderId:form.paymentType==='supplier'?(form.relatedOrderId||null):null,bankSerialNo:form.bankSerialNo||null}
 if(props.kind==='account-transfers')return {...common,fromAccountId:form.fromAccountId,toAccountId:form.toAccountId,amount:form.amount,feeAmount:form.feeAmount}
 return {...common,entryType:form.entryType,direction:form.direction,partnerId:form.entryType==='cash'?null:form.supplierId,cashAccountId:form.entryType==='cash'?form.cashAccountId:null,amount:form.amount}
}
async function calculate():Promise<void>{
 const current=++sequence
 if(!order.value||!form.supplierId||!lines.value.length||lines.value.some(l=>!l.skuId||!l.unitId||!l.qty)){preview.value=undefined;return}
 try{const result=await pricePreview({...body(),documentType:'purchase_order'});if(current===sequence){preview.value=result;error.value=''}}
 catch(cause:unknown){if(current===sequence&&!isCancelled(cause)){preview.value=undefined;error.value=messageOf(cause)}}
}
watch([form,lines,fees],()=>{clearTimeout(timer);if(order.value)timer=setTimeout(()=>{void calculate()},350)},{deep:true})
onUnmounted(()=>{clearTimeout(timer);sequence++;sourceSequence++})
onMounted(async()=>{
 try{
  if(hasPartner.value||order.value||props.kind==='opening-entries')partners.value=await allRecords('partners')
  if(products.value){skus.value=await allRecords('skus');if(order.value||stock.value)warehouses.value=await allRecords('warehouses',true)}
  if(sourceLines.value)warehouses.value=await allRecords('warehouses',true)
  if(['payments','account-transfers','opening-entries'].includes(props.kind))accounts.value=await allRecords('cash-accounts',true)
  if(props.kind==='payments'){if(session.can('purchase_order:view'))orders.value=await allRecords('purchase-orders',true);if(session.can('income_expense_item:view'))expenses.value=await allRecords('income-expense-items')}
  for(const line of lines.value)if(line.skuId){const sku=await record('skus/'+line.skuId);units[line.skuId]=(sku.units as SalesSkuUnit[])??[]}
  if(hasLines.value&&!lines.value.length)addLine()
  if(sourceLines.value||order.value&&session.can('purchase_requisition:view'))await loadSources()
  await calculate()
 }catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}finally{loading.value=false}
})
async function save():Promise<void>{
 if(busy.value)return
 busy.value=true;error.value=''
 try{
  if(order.value){await calculate();if(!preview.value)return}
  const result=props.change&&props.original?await procurementCommand(props.kind,props.original.id,'changes',body(),key):await saveProcurement(props.kind,body(),props.original?.id,props.original?undefined:key)
  emit('saved',result)
 }catch(cause:unknown){
  if(!isCancelled(cause)){error.value=messageOf(cause);if(cause instanceof ApiError){const pending=cause.status===0||cause.code==='REQUEST_IN_PROGRESS';uncertain.value=pending&&(!props.original||props.change===true);conflict.value=cause.code==='VERSION_CONFLICT'||pending&&Boolean(props.original)&&!props.change}}
 }finally{busy.value=false}
}
async function verify():Promise<void>{
 try{const result=await commandResult(spec.value.permission+'.'+(props.change?'change':'create'),key);if(result.status==='succeeded')emit('saved',result.response)}
 catch(cause:unknown){error.value=messageOf(cause);if(cause instanceof ApiError&&cause.status===404)uncertain.value=false}
}
</script>
<template>
 <el-dialog :model-value="true" :title="(change?'变更':original?'编辑':'新建')+spec.singular" class="sales-editor-dialog" top="5vh" width="min(1100px,96vw)" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
  <fieldset class="form-fieldset" :disabled="busy||loading||uncertain||conflict">
   <el-form label-position="top" class="sales-header-form">
    <el-form-item v-if="stock" label="调整类型"><el-select v-model="form.adjustType" aria-label="调整类型" :disabled="Boolean(original)"><el-option v-for="(label,type) in adjustmentLabels" :key="type" :value="type" :label="label"/></el-select></el-form-item>
    <el-form-item v-if="kind==='payments'" label="付款类型"><el-select v-model="form.paymentType" aria-label="付款类型"><el-option value="supplier" label="供应商付款"/><el-option value="other_expense" label="其他支出"/></el-select></el-form-item>
    <el-form-item v-if="kind==='opening-entries'" label="期初类型"><el-select v-model="form.entryType" aria-label="期初类型"><el-option value="cash" label="资金期初"/><el-option value="receivable" label="客户往来期初"/><el-option value="payable" label="供应商往来期初"/></el-select></el-form-item>
    <el-form-item v-if="hasPartner" :label="kind==='opening-entries'?'往来单位':'供应商'" for="purchase-partner"><el-select id="purchase-partner" v-model="form.supplierId" filterable :disabled="change"><el-option v-for="p in partners.filter(p=>p.status==='active')" :key="p.id" :value="p.id" :label="String(p.name)"/></el-select></el-form-item>
    <el-form-item v-if="warehouseHead" label="仓库" for="purchase-warehouse"><el-select id="purchase-warehouse" v-model="form.warehouseId"><el-option v-for="w in warehouses.filter(w=>w.status==='active')" :key="w.id" :value="w.id" :label="String(w.name)"/></el-select></el-form-item>
    <el-form-item label="业务日期" for="purchase-date"><el-input id="purchase-date" v-model="form.businessDate" type="date"/></el-form-item>
    <el-form-item v-if="order" label="交货日期" for="purchase-delivery"><el-input id="purchase-delivery" v-model="form.deliveryDate" type="date"/></el-form-item>
    <el-form-item v-if="order" label="价格口径"><el-switch v-model="form.priceTaxInclusive" active-text="含税" inactive-text="未税" :disabled="change"/></el-form-item>
    <el-form-item v-if="order" label="整单优惠" for="purchase-discount"><el-input id="purchase-discount" v-model="form.orderDiscountAmount"/></el-form-item>
    <el-form-item v-if="kind==='payments'||kind==='opening-entries'&&form.entryType==='cash'" label="结算账户" for="purchase-account"><el-select id="purchase-account" v-model="form.cashAccountId"><el-option v-for="a in accounts.filter(a=>a.status==='active')" :key="a.id" :value="a.id" :label="String(a.name)"/></el-select></el-form-item>
    <el-form-item v-if="kind==='payments'&&form.paymentType==='supplier'&&session.can('purchase_order:view')" label="关联采购订单" for="purchase-related"><el-select id="purchase-related" v-model="form.relatedOrderId" clearable><el-option v-for="o in orders.filter(o=>o.supplierId===form.supplierId||(o.supplier as {id?:string}|undefined)?.id===form.supplierId)" :key="o.id" :value="o.id" :label="String(o.documentNo)"/></el-select></el-form-item>
    <el-form-item v-if="kind==='payments'&&form.paymentType==='other_expense'" label="支出项目"><el-select v-model="form.expenseItemId" aria-label="支出项目"><el-option v-for="e in expenses.filter(e=>e.itemType==='expense'&&e.status==='active')" :key="e.id" :value="e.id" :label="String(e.name)"/></el-select></el-form-item>
    <template v-if="kind==='account-transfers'"><el-form-item label="转出账户" for="transfer-from"><el-select id="transfer-from" v-model="form.fromAccountId"><el-option v-for="a in accounts.filter(a=>a.status==='active')" :key="a.id" :value="a.id" :label="String(a.name)"/></el-select></el-form-item><el-form-item label="转入账户" for="transfer-to"><el-select id="transfer-to" v-model="form.toAccountId"><el-option v-for="a in accounts.filter(a=>a.status==='active'&&a.id!==form.fromAccountId)" :key="a.id" :value="a.id" :label="String(a.name)"/></el-select></el-form-item><el-form-item label="手续费" for="transfer-fee"><el-input id="transfer-fee" v-model="form.feeAmount"/></el-form-item></template>
    <el-form-item v-if="!hasLines" label="金额" for="purchase-amount"><el-input id="purchase-amount" v-model="form.amount"/></el-form-item>
    <el-form-item v-if="kind==='opening-entries'&&form.entryType!=='cash'" label="借贷方向"><el-select v-model="form.direction" aria-label="借贷方向"><el-option value="D" label="借方 D"/><el-option value="C" label="贷方 C"/></el-select></el-form-item>
    <el-form-item v-if="kind==='payments'" label="银行流水号" for="purchase-bank"><el-input id="purchase-bank" v-model="form.bankSerialNo" maxlength="64"/></el-form-item>
    <el-form-item v-if="stock||kind==='purchase-returns'||change" label="原因" for="purchase-reason"><el-input id="purchase-reason" v-model="form.reason" maxlength="200"/></el-form-item>
    <el-form-item label="备注" for="purchase-remark"><el-input id="purchase-remark" v-model="form.remark" maxlength="200"/></el-form-item>
   </el-form>
   <div v-if="hasLines" class="sales-lines-scroll"><table class="sales-input-table"><thead><tr><th v-if="sourceLines||order&&session.can('purchase_requisition:view')">来源{{order?'（可选）':''}}</th><th v-if="products">商品</th><th v-if="order">收货仓库</th><th v-if="products">单位</th><th>数量</th><template v-if="order"><th>单价</th><th>行优惠</th><th v-if="session.can('document:change_tax_rate')">税率</th></template><th v-if="inputCost">总成本</th><th v-if="requisition">需求日期</th><th></th></tr></thead><tbody>
    <tr v-for="(line,index) in lines" :key="index">
     <td v-if="sourceLines||order&&session.can('purchase_requisition:view')"><el-select v-model="line.sourceId" :aria-label="'采购来源'+(index+1)" clearable filterable @change="chooseSource(line)"><el-option v-for="s in sources" :key="String(s.id)" :value="String(s.id)" :label="sourceLabel(s)"/></el-select></td>
     <td v-if="products"><el-select v-model="line.skuId" :aria-label="'采购商品'+(index+1)" filterable @change="chooseSku(line)"><el-option v-for="s in skus.filter(s=>s.status==='active')" :key="s.id" :value="s.id" :label="String(s.code)+' '+String(s.name)"/></el-select></td>
     <td v-if="order"><el-select v-model="line.warehouseId" :aria-label="'采购仓库'+(index+1)" clearable><el-option v-for="w in warehouses.filter(w=>w.status==='active')" :key="w.id" :value="w.id" :label="String(w.name)"/></el-select></td>
     <td v-if="products"><el-select v-model="line.unitId" :aria-label="'采购单位'+(index+1)" @change="line.unitPrice=''"><el-option v-for="u in units[line.skuId]??[]" :key="u.unitId" :value="u.unitId" :label="u.unitName"/></el-select></td>
     <td><el-input v-model="line.qty" :aria-label="'采购数量'+(index+1)"/></td>
     <template v-if="order"><td><el-input v-model="line.unitPrice" :aria-label="'采购单价'+(index+1)" placeholder="参考价"/></td><td><el-input v-model="line.lineDiscountAmount" :aria-label="'采购行优惠'+(index+1)"/></td><td v-if="session.can('document:change_tax_rate')"><el-input v-model="line.taxRate" :aria-label="'采购税率'+(index+1)"/></td></template>
     <td v-if="inputCost"><el-input v-model="line.inputTotalCost" :aria-label="(form.adjustType==='opening'?'期初总成本':'调整总成本')+(index+1)"/></td>
     <td v-if="requisition"><el-input v-model="line.needDate" type="date" :aria-label="'需求日期'+(index+1)"/></td>
     <td><el-button text @click="lines.splice(index,1)">移除</el-button></td>
    </tr>
   </tbody></table><el-button @click="addLine">添加明细</el-button></div>
   <section v-if="order" class="sale-fees"><div v-for="(fee,index) in fees" :key="index" class="relation-row"><el-select v-model="fee.feeType" aria-label="采购费用类型"><el-option value="freight" label="运费"/><el-option value="other" label="其他费用"/></el-select><el-select v-model="fee.bearer" aria-label="采购费用承担方"><el-option value="buyer" label="我方承担"/><el-option value="seller" label="供应商承担"/></el-select><el-input v-model="fee.amount" aria-label="采购费用金额"/><el-button text @click="fees.splice(index,1)">移除</el-button></div><el-button text @click="fees.push({feeType:'freight',bearer:'buyer',amount:'0',taxRate:'0.13'})">添加费用</el-button></section>
  </fieldset>
  <div v-if="preview" class="sales-totals"><span>税额 {{preview.taxAmount}}</span><strong>合计 ¥{{preview.totalAmount}}</strong></div>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/><p v-if="conflict">保存版本或结果需要核对，请关闭并刷新后重试。</p>
  <template #footer><el-button :disabled="busy" @click="emit('close')">取消</el-button><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button><el-button v-else type="primary" :loading="busy" :disabled="loading||conflict" @click="save">保存{{spec.singular}}</el-button></template>
 </el-dialog>
</template>
