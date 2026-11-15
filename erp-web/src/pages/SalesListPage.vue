<script setup lang="ts">
import { computed,onMounted,ref,watch } from 'vue'
import { useRouter } from 'vue-router'
import { documents } from '../api/sales'
import { isCancelled,messageOf } from '../api/http'
import { salesSpecs,salesStatuses,salesStatusLabel } from '../types/sales'
import type { SalesDocument,SalesKind } from '../types/sales'
import { useSessionStore } from '../stores/session'
import OrderEditor from '../components/sales/OrderEditor.vue'
import DocumentEditor from '../components/sales/DocumentEditor.vue'
const props=defineProps<{kind:SalesKind}>()
const session=useSessionStore(),router=useRouter()
const spec=computed(()=>salesSpecs.find(s=>s.path===props.kind)!)
const statuses=computed(()=>salesStatuses[props.kind])
const items=ref<SalesDocument[]>([]),cursors=ref(['']),next=ref<string|null>(null)
const keyword=ref(''),status=ref(''),overdue=ref(false),busy=ref(false),error=ref(''),editing=ref(false)
async function load():Promise<void>{
 busy.value=true;error.value=''
 try{const page=await documents(props.kind,{keyword:props.kind==='sales-orders'?keyword.value||undefined:undefined,status:props.kind!=='invoice-registrations'?status.value||undefined:undefined,overdue:props.kind==='shipments'?overdue.value||undefined:undefined,cursor:cursors.value.at(-1)||undefined,limit:20});items.value=page.items;next.value=page.nextCursor}
 catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}
 finally{busy.value=false}
}
function search():void{cursors.value=[''];void load()}
function paginate(forward:boolean):void{if(forward&&next.value)cursors.value.push(next.value);else if(!forward)cursors.value.pop();void load()}
async function saved(value:SalesDocument):Promise<void>{editing.value=false;if(value.id)await router.push('/'+props.kind+'/'+value.id);else await load()}
onMounted(load)
watch(()=>props.kind,()=>{keyword.value='';status.value='';overdue.value=false;items.value=[];next.value=null;search()})
</script>
<template>
 <div class="page-heading"><h1>{{ spec.title }}</h1><el-button v-if="session.can(spec.permission+':create')" type="primary" @click="editing=true">新建{{ spec.singular }}</el-button></div>
 <section class="panel table-panel" :aria-busy="busy">
  <form class="filters" @submit.prevent="search"><div v-if="kind==='sales-orders'" class="filter-field"><label for="sales-search">单号 / 备注</label><el-input id="sales-search" v-model="keyword" clearable/></div>
   <div v-if="kind!=='invoice-registrations'" class="filter-field small"><label for="sales-status">状态</label><el-select id="sales-status" v-model="status" clearable><el-option v-for="value in statuses" :key="value" :value="value" :label="salesStatusLabel(kind,value)"/></el-select></div>
   <el-checkbox v-if="kind==='shipments'" v-model="overdue">仅签收超期</el-checkbox><el-button native-type="submit">查询</el-button>
  </form>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/>
  <el-table :data="items" row-key="id">
   <el-table-column label="单号" min-width="235"><template #default="{row}"><router-link class="text-link" :to="'/'+kind+'/'+row.id">{{ row.documentNo??row.invoiceNo??row.id }}</router-link></template></el-table-column>
   <el-table-column label="客户 / 往来" min-width="150"><template #default="{row}">{{ row.customer?.name??row.customerName??row.partnerName??'—' }}</template></el-table-column>
   <el-table-column label="业务日期" min-width="130"><template #default="{row}">{{ row.businessDate??row.invoiceDate }}</template></el-table-column>
   <el-table-column label="状态" min-width="120"><template #default="{row}"><el-tag type="info">{{ salesStatusLabel(kind,row.status) }}</el-tag></template></el-table-column>
   <el-table-column label="金额（元）" min-width="125" align="right"><template #default="{row}">{{ row.totalAmount??row.receivableAmount??row.creditAmount??row.amount??'—' }}</template></el-table-column>
   <el-table-column label="操作" width="90"><template #default="{row}"><router-link class="text-link" :to="'/'+kind+'/'+row.id">查看</router-link></template></el-table-column>
   <template #empty><div class="empty-state">{{ busy?'正在加载…':error?'加载失败，请重新查询':'暂无单据' }}</div></template>
  </el-table>
  <footer class="table-footer"><span>第 {{ cursors.length }} 页</span><div><el-button :disabled="busy||cursors.length===1" @click="paginate(false)">上一页</el-button><el-button :disabled="busy||!next" @click="paginate(true)">下一页</el-button></div></footer>
 </section>
 <OrderEditor v-if="editing&&kind==='sales-orders'" @close="editing=false" @saved="saved"/>
 <DocumentEditor v-if="editing&&kind!=='sales-orders'" :kind="kind" @close="editing=false" @saved="saved"/>
</template>
