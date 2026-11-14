<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider size="large" :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>
