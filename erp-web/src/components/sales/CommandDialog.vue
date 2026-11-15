<script setup lang="ts">
import { computed, ref } from 'vue'
import { commandResult, document as getDocument, salesCommand } from '../../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { actionLabels, salesSpecs, today } from '../../types/sales'
import type { SalesDocument, SalesKind } from '../../types/sales'
const props=defineProps<{kind:SalesKind; document:SalesDocument; action:string}>()
const emit=defineEmits<{close:[];saved:[warnings?:{message:string}[]]}>()
const action=computed(()=>props.action.replaceAll('-','_'))
const label=computed(()=>actionLabels[props.action]??actionLabels[action.value]??props.action)
const reason=ref(''), date=ref(today()), bank=ref(''), busy=ref(false), error=ref(''), uncertain=ref(false), conflict=ref(false)
const key=newIdempotencyKey()
const rows=ref((props.document.lines??[]).map(line=>({
  id:line.id,name:line.sku?.name??line.skuName??String(line.lineNo??line.id),
  qty:action.value==='sign'?String(line.baseQty??line.qty??'0'):'0',
  remaining:line.progress?.shippableQty??'0',
  disposition:'returned', pending:line.diffDisposition==='pending',
})))
const operation=computed(()=>{
  const resource=salesSpecs.find(s=>s.path===props.kind)!.permission
  const name=action.value==='changes'?'change':action.value
  return resource+'.'+name
})
function body(): Record<string,unknown> {
  const value:Record<string,unknown>={version:props.document.version}
  if(reason.value){value.reason=reason.value;value.comment=reason.value}
  if(action.value==='sign'){value.signDate=date.value;value.lines=rows.value.map(r=>({lineId:r.id,signedQty:r.qty}))}
  if(action.value==='resolve_diff'){value.businessDate=date.value;value.lines=rows.value.filter(r=>r.pending).map(r=>({lineId:r.id,disposition:r.disposition,reason:reason.value}))}
  if(action.value==='close_lines'){value.lines=rows.value.filter(r=>r.qty!=='0'&&r.qty!=='').map(r=>({lineId:r.id,closeQty:r.qty}))}
  if(action.value==='register_paid'){value.paidDate=date.value;value.bankSerialNo=bank.value}
  return value
}
async function submit():Promise<void>{
  if(busy.value)return
  busy.value=true;error.value=''
  try{const result=await salesCommand(props.kind,props.document.id,props.action,body(),key);emit('saved',result?.warnings??[])}
  catch(cause:unknown){if(!isCancelled(cause)){error.value=messageOf(cause);if(cause instanceof ApiError){uncertain.value=cause.status===0||cause.code==='REQUEST_IN_PROGRESS';conflict.value=cause.code==='VERSION_CONFLICT'}}}
  finally{busy.value=false}
}
async function verify():Promise<void>{
  if(action.value==='delete') {
    try {
      const current=await getDocument(props.kind,props.document.id)
      uncertain.value=false
      conflict.value=current.version!==props.document.version
      error.value=conflict.value?'草稿已更新，请关闭并刷新':'草稿仍存在，可使用原版本重新删除'
    } catch(cause:unknown) {
      if(cause instanceof ApiError&&cause.status===404)emit('saved')
      else error.value=messageOf(cause)
    }
    return
  }
  try{const result=await commandResult(operation.value,key);if(result.status==='succeeded')emit('saved',result.response?.warnings??[])}
  catch(cause:unknown){error.value=messageOf(cause);if(cause instanceof ApiError&&cause.status===404)uncertain.value=false}
}
</script>
<template>
  <el-dialog :model-value="true" :title="label" width="600px" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <fieldset class="form-fieldset" :disabled="busy||uncertain||conflict">
      <p>单据 {{ document.documentNo??document.invoiceNo??document.id }}</p>
      <el-form label-position="top">
        <el-form-item v-if="['sign','resolve_diff','register_paid'].includes(action)" :label="action==='sign'?'签收日期':action==='register_paid'?'退款日期':'业务日期'" for="command-date"><el-input id="command-date" v-model="date" type="date"/></el-form-item>
        <el-form-item v-if="action==='register_paid'" label="银行流水号" for="command-bank"><el-input id="command-bank" v-model="bank" maxlength="64"/></el-form-item>
        <template v-if="action==='sign'||action==='close_lines'">
          <p>以下数量按商品的基础单位填写。</p>
          <div v-for="row in rows" :key="row.id" class="relation-row"><label :for="'command-line-'+row.id">{{ row.name }}{{ action==='close_lines'?' · 可关闭 '+row.remaining:'' }}</label><el-input :id="'command-line-'+row.id" v-model="row.qty" :aria-label="action==='sign'?'签收数量':'关闭数量'"/><el-button v-if="action==='close_lines'" text @click="row.qty=row.remaining">全部关闭</el-button></div>
        </template>
        <template v-if="action==='resolve_diff'"><div v-for="row in rows.filter(r=>r.pending)" :key="row.id" class="relation-row"><span>{{ row.name }}</span><el-select v-model="row.disposition" aria-label="差异处理方式"><el-option label="拒收退回（冻结待检）" value="returned"/><el-option label="运输损失" value="lost"/></el-select></div></template>
        <el-form-item v-if="['reject','cancel','close_lines','resolve_diff','revoke_approval','approve'].includes(action)" label="意见 / 原因" for="command-reason"><el-input id="command-reason" v-model="reason" type="textarea" maxlength="200"/></el-form-item>
      </el-form>
    </fieldset>
    <el-alert v-if="error" type="error" :title="error" :closable="false"/>
    <p v-if="conflict">单据已有更新，请关闭并刷新后重试。</p><p v-if="uncertain">结果确认中，请查询原请求结果。</p>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button><el-button v-if="uncertain" type="primary" @click="verify">查询结果</el-button><el-button v-else type="primary" :disabled="conflict" :loading="busy" @click="submit">确认{{ label }}</el-button></template>
  </el-dialog>
</template>
