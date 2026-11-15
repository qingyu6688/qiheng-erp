<script setup lang="ts">
import { onMounted,ref } from 'vue'
import { candidates } from '../api/sales'
import { isCancelled,messageOf } from '../api/http'
const rows=ref<Record<string,unknown>[]>([]),error=ref(''),keyword=ref(''),busy=ref(false),cursors=ref(['']),next=ref<string|null>(null)
async function load():Promise<void>{
 busy.value=true;error.value=''
 try{const result=await candidates('inventory-balances',{keyword:keyword.value||undefined,cursor:cursors.value.at(-1)||undefined,limit:20});rows.value=result.items;next.value=result.nextCursor}
 catch(cause:unknown){if(!isCancelled(cause))error.value=messageOf(cause)}finally{busy.value=false}
}
function search():void{cursors.value=[''];void load()}
function paginate(forward:boolean):void{if(forward&&next.value)cursors.value.push(next.value);else if(!forward)cursors.value.pop();void load()}
onMounted(load)
</script>
<template>
 <div class="page-heading"><h1>实时库存</h1></div><section class="panel table-panel" :aria-busy="busy">
  <form class="filters" @submit.prevent="search"><div class="filter-field"><label for="inventory-search">商品名称或编码</label><el-input id="inventory-search" v-model="keyword"/></div><el-button native-type="submit">查询</el-button></form>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/>
  <el-table :data="rows"><el-table-column prop="warehouseName" label="仓库"/><el-table-column prop="skuCode" label="商品编码"/><el-table-column prop="skuName" label="商品名称"/><el-table-column prop="onHandQty" label="在库"/><el-table-column prop="reservedQty" label="预留"/><el-table-column prop="blockedQty" label="冻结"/><el-table-column prop="availableQty" label="可用"/><el-table-column prop="pendingDemandQty" label="待审核需求"/><template #empty>{{ busy?'正在加载…':'暂无库存记录' }}</template></el-table>
  <footer class="table-footer"><span>第 {{ cursors.length }} 页</span><div><el-button :disabled="cursors.length===1||busy" @click="paginate(false)">上一页</el-button><el-button :disabled="!next||busy" @click="paginate(true)">下一页</el-button></div></footer>
 </section>
</template>
