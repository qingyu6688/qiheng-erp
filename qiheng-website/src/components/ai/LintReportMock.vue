<script setup lang="ts">
/** Wiki 每周体检报告示意。体检时间取 erp.ai.wiki-lint-cron 的默认值（每周一 03:30），内容为虚构示例 */
const issues = [
  { type: '数值矛盾', tone: 'hot', page: '信用额度 ↔ 销售退货与退款', detail: '两页对“退款是否需要复核”写法不一致' },
  { type: '来源失效', tone: '', page: '月末结账', detail: '引用的结账手册已换版，需要重新审核' },
  { type: '断链', tone: '', page: '采购收货', detail: '链接到已归档的“来料质检（旧）”' },
  { type: '孤立页', tone: '', page: '保温杯批次规则', detail: '没有其他页面链接到这一页' },
  { type: '半年未访问', tone: 'cold', page: '旧版价格表说明', detail: '建议确认是否仍然有效' },
]
</script>

<template>
  <div class="wiki-mock lint-mock" aria-label="Wiki 每周体检示意" data-reveal="scale">
    <div class="top"><b>Wiki 体检报告</b><span class="tagx">{{ issues.length }} 项待处理</span><span class="meta">每周一 03:30 自动运行 · 示例</span></div>
    <ul class="lint-list">
      <li v-for="issue in issues" :key="issue.page">
        <span class="lt" :class="issue.tone">{{ issue.type }}</span>
        <span><b>{{ issue.page }}</b><small>{{ issue.detail }}</small></span>
      </li>
    </ul>
    <div class="foot">
      <button class="btn btn-sm" type="button">导出清单</button>
      <button class="btn btn-sm btn-primary" type="button">逐项处理</button>
    </div>
  </div>
</template>
