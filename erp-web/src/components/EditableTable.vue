<script setup lang="ts">
import { nextTick, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    count: number
    disabled?: boolean
    title?: string
  }>(),
  { title: '商品明细', disabled: false },
)
const emit = defineEmits<{ add: []; remove: [number]; paste: [string] }>()
const table = ref<HTMLTableElement>()

function rows(): HTMLTableRowElement[] {
  return [
    ...(table.value?.querySelectorAll<HTMLTableRowElement>('tbody > tr[data-edit-row]') ?? []),
  ]
}

function controls(row: HTMLTableRowElement): HTMLElement[] {
  return [
    ...row.querySelectorAll<HTMLElement>('input:not([type=hidden]), select, textarea'),
  ].filter(
    (control) =>
      !control.matches(':disabled, [tabindex="-1"]') &&
      (!control.hasAttribute('readonly') || control.getAttribute('role') === 'combobox'),
  )
}

function focus(rowIndex: number, column = 0): void {
  const row = rows()[rowIndex]
  if (row) {
    const fields = controls(row)
    const field = fields[Math.min(column, fields.length - 1)]
    field?.focus()
    if (field instanceof HTMLInputElement && field.type === 'text') field.select()
  }
}

async function navigate(event: KeyboardEvent): Promise<void> {
  if (props.disabled || event.isComposing || event.altKey || event.metaKey) return
  const target = event.target
  if (!(target instanceof HTMLElement) || !target.matches('input, select, textarea')) return
  const row = target.closest<HTMLTableRowElement>('tr[data-edit-row]')
  if (!row) return
  const currentRows = rows()
  const rowIndex = currentRows.indexOf(row)
  const fields = controls(row)
  const column = fields.indexOf(target)
  if (column < 0) return
  if (event.ctrlKey && event.key === 'Delete') {
    event.preventDefault()
    emit('remove', rowIndex)
    await nextTick()
    focus(Math.min(rowIndex, rows().length - 1), column)
    return
  }
  if (event.ctrlKey || target.getAttribute('aria-expanded') === 'true') return
  if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
    // 下拉框的方向键用于选项，明细换行只处理普通输入框。
    if (target.matches('select, textarea, [role=combobox]')) return
    const next = rowIndex + (event.key === 'ArrowDown' ? 1 : -1)
    if (next >= 0 && next < currentRows.length) {
      event.preventDefault()
      focus(next, column)
    }
    return
  }
  if (!['Enter', 'Tab'].includes(event.key) || target.matches('textarea')) return
  if (event.key === 'Enter' && target.matches('select, [role=combobox]')) return
  const previous = event.shiftKey
  const nextColumn = column + (previous ? -1 : 1)
  if (nextColumn >= 0 && nextColumn < fields.length) {
    event.preventDefault()
    fields[nextColumn]?.focus()
  } else if (previous && rowIndex > 0) {
    event.preventDefault()
    focus(rowIndex - 1, controls(currentRows[rowIndex - 1]!).length - 1)
  } else if (!previous && rowIndex < currentRows.length - 1) {
    event.preventDefault()
    focus(rowIndex + 1)
  } else if (!previous && event.key === 'Enter') {
    event.preventDefault()
    emit('add')
    await nextTick()
    if (rows().length > currentRows.length) focus(currentRows.length)
  }
}

function paste(event: ClipboardEvent): void {
  if (props.disabled || !(event.target instanceof HTMLElement) || !event.target.closest('tbody'))
    return
  const text = event.clipboardData?.getData('text/plain')
  if (!text?.includes('\t')) return
  event.preventDefault()
  emit('paste', text)
}

defineExpose({ focus })
</script>

<template>
  <section class="editable-section" :aria-label="title">
    <header class="editable-heading">
      <h3>
        {{ title }} <small>共 {{ count }} 行</small>
      </h3>
      <div class="editable-tools"><slot name="tools" /></div>
    </header>
    <div
      class="sales-lines-scroll editable-scroll"
      role="region"
      :aria-label="title + '横向滚动明细'"
      tabindex="0"
    >
      <table
        ref="table"
        class="sales-input-table editable-table"
        @keydown="navigate"
        @paste="paste"
      >
        <thead>
          <slot name="head" />
        </thead>
        <tbody>
          <slot />
        </tbody>
        <tfoot v-if="$slots.summary">
          <slot name="summary" />
        </tfoot>
      </table>
    </div>
  </section>
</template>

<style scoped>
.editable-section {
  margin-block: 16px;
  border: 1px solid var(--erp-border-1);
  border-radius: var(--erp-radius-card);
  overflow: hidden;
}
.editable-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 16px;
}
.editable-heading h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.editable-heading small {
  font-size: 12px;
  font-weight: 400;
  color: var(--erp-text-3);
  margin-left: 8px;
}
.editable-tools {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.editable-scroll {
  margin: 0;
}
.editable-scroll:focus-visible {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: -2px;
}
.editable-table :deep(th),
.editable-table :deep(td) {
  height: 36px;
  padding: 2px 6px;
}
.editable-table :deep(th:first-child),
.editable-table :deep(td:first-child) {
  width: 44px;
  min-width: 44px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.editable-table :deep(td:nth-child(2)) {
  min-width: 200px;
}
.editable-table :deep(.el-input__wrapper),
.editable-table :deep(.el-select__wrapper) {
  background: transparent;
  box-shadow: 0 0 0 1px transparent inset;
  border-radius: var(--erp-radius-sm);
  min-height: 30px;
}
.editable-table :deep(td:hover .el-input__wrapper),
.editable-table :deep(td:hover .el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--erp-border-2) inset;
}
.editable-table :deep(.el-input__wrapper.is-focus),
.editable-table :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--erp-primary-6) inset;
}
.editable-table :deep(td[data-error] .el-input__wrapper),
.editable-table :deep(td[data-error] .el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--erp-danger-text) inset;
}
.editable-table :deep(input) {
  font-variant-numeric: tabular-nums;
}
.editable-table :deep(.line-amount) {
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.editable-table :deep(tfoot td) {
  border-bottom: 0;
  background: var(--erp-fill-1);
  font-weight: 500;
}
</style>
