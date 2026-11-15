<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {useRouter} from 'vue-router'
import {procurementList} from '../api/procurement'
import {isCancelled,messageOf} from '../api/http'
import {procurementSpecs} from '../types/procurement'
import type {ProcurementDocument,ProcurementKind} from '../types/procurement'
import {statusLabels} from '../types/sales'
import {useSessionStore} from '../stores/session'
import ProcurementEditor from '../components/procurement/ProcurementEditor.vue'
const props=defineProps<{kind:ProcurementKind}>(),router=useRouter(),session=useSessionStore()
const spec=computed(()=>procurementSpecs.find(s=>s.path===props.kind)!)
const rows=ref<ProcurementDocument[]>([]),busy=ref(false),error=ref(''),status=ref(''),editing=ref(false),cursors=ref(['']),next=ref<string|null>(null)
async function load():Promise<void>{busy.value=true;error.value='';try{const result=await procurementList(props.kind,{status:status.value||undefined,cursor:cursors.value.at(-1)||undefined,limit:20});rows.value=result.items;next.value=result.nextCursor}catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}finally{busy.value=false}}
function search():void{cursors.value=[''];void load()}
function paginate(forward:boolean):void{if(forward&&next.value)cursors.value.push(next.value);else if(!forward)cursors.value.pop();void load()}
async function saved(value:ProcurementDocument):Promise<void>{editing.value=false;await router.push('/'+props.kind+'/'+value.id)}
onMounted(load)
</script>
<template>
 <div class="page-heading"><h1>{{spec.title}}</h1><el-button v-if="session.can(spec.permission+':create')" type="primary" @click="editing=true">新建{{spec.singular}}</el-button></div>
 <section class="panel table-panel" :aria-busy="busy"><form class="filters" @submit.prevent="search"><div class="filter-field small"><label for="procurement-status">状态</label><el-select id="procurement-status" v-model="status" clearable><el-option v-for="s in spec.states" :key="s" :value="s" :label="s==='posted'?'已过账':statusLabels[s]??s"/></el-select></div><el-button native-type="submit">查询</el-button></form>
 <el-alert v-if="error" type="error" :title="error" :closable="false"/>
 <el-table :data="rows" row-key="id"><el-table-column label="单号" min-width="240"><template #default="{row}"><router-link class="text-link" :to="'/'+kind+'/'+row.id">{{row.documentNo??row.id}}</router-link></template></el-table-column><el-table-column :label="kind==='purchase-requisitions'?'申请人':'供应商 / 往来 / 账户'" min-width="160"><template #default="{row}">{{row.requesterName??row.supplier?.name??row.supplierName??row.partnerName??row.cashAccountName??row.fromAccountName??row.warehouseName??'—'}}</template></el-table-column><el-table-column prop="businessDate" label="业务日期" min-width="130"/><el-table-column label="状态" min-width="110"><template #default="{row}"><el-tag type="info">{{row.status==='posted'?'已过账':statusLabels[row.status]??row.status}}</el-tag></template></el-table-column><el-table-column label="金额" min-width="120"><template #default="{row}">{{row.totalAmount??row.payableAmount??row.debitAmount??row.amount??'—'}}</template></el-table-column><template #empty>{{busy?'正在加载…':error?'加载失败，请重试':'暂无单据'}}</template></el-table>
 <footer class="table-footer"><span>第 {{cursors.length}} 页</span><div><el-button :disabled="busy||cursors.length===1" @click="paginate(false)">上一页</el-button><el-button :disabled="busy||!next" @click="paginate(true)">下一页</el-button></div></footer></section>
 <ProcurementEditor v-if="editing" :kind="kind" @close="editing=false" @saved="saved"/>
</template>
