<script setup lang="ts">
import { Refresh, Setting } from '@element-plus/icons-vue'
import { nextTick } from 'vue'
import DisplayDensitySelect from './DisplayDensitySelect.vue'
import type {
  DocumentColumnFixed,
  DocumentListColumnPreference,
} from '../hooks/useDocumentListPreferences'

const props = defineProps<{
  columns: DocumentListColumnPreference[]
  hidden: string[]
  busy: boolean
}>()
const emit = defineEmits<{
  refresh: []
  'toggle-column': [key: string]
  'move-column': [key: string, direction: -1 | 1]
  'fix-column': [key: string, side?: DocumentColumnFixed]
  'reset-columns': []
}>()

function canMove(index: number, direction: -1 | 1): boolean {
  const column = props.columns[index]
  const adjacent = props.columns[index + direction]
  return Boolean(
    column && adjacent && !column.required && !adjacent.required && column.fixed === adjacent.fixed,
  )
}

async function changeFixed(key: string, event: Event): Promise<void> {
  const control = event.target as HTMLSelectElement
  const value = control.value
  emit('fix-column', key, value === 'left' || value === 'right' ? value : undefined)
  await nextTick()
  control.focus()
}

async function shiftColumn(key: string, direction: -1 | 1, event: Event): Promise<void> {
  const button = event.currentTarget as HTMLButtonElement
  emit('move-column', key, direction)
  await nextTick()
  const target = button.disabled ? button.closest('li')?.querySelector('select') : button
  target?.focus()
}

function closeColumns(event: KeyboardEvent): void {
  const details = event.currentTarget
  if (!(details instanceof HTMLDetailsElement) || !details.open) return
  event.stopPropagation()
  details.open = false
  details.querySelector('summary')?.focus()
}
</script>

<template>
  <div class="document-table-toolbar" role="group" aria-label="表格工具">
    <el-button
      :icon="Refresh"
      :disabled="busy"
      aria-label="刷新列表"
      title="刷新列表"
      @click="emit('refresh')"
    />
    <DisplayDensitySelect />
    <details v-if="columns.length" class="document-column-options" @keydown.esc="closeColumns">
      <summary>
        <Setting aria-hidden="true" class="icon" />
        列设置
      </summary>
      <div class="document-column-options-list">
        <ol aria-label="显示列顺序">
          <li v-for="(column, index) in columns" :key="column.key">
            <label>
              <input
                type="checkbox"
                :checked="column.required || !hidden.includes(column.key)"
                :disabled="column.required"
                @change="emit('toggle-column', column.key)"
              />
              {{ column.label }}
            </label>
            <div class="column-placement">
              <select
                :aria-label="column.label + '固定位置'"
                :value="column.fixed ?? ''"
                :disabled="column.required"
                @change="changeFixed(column.key, $event)"
              >
                <option value="">不固定</option>
                <option value="left">左侧固定</option>
                <option value="right">右侧固定</option>
              </select>
              <button
                type="button"
                :disabled="!canMove(index, -1)"
                :aria-label="'上移' + column.label"
                @click="shiftColumn(column.key, -1, $event)"
              >
                ↑
              </button>
              <button
                type="button"
                :disabled="!canMove(index, 1)"
                :aria-label="'下移' + column.label"
                @click="shiftColumn(column.key, 1, $event)"
              >
                ↓
              </button>
            </div>
          </li>
        </ol>
        <button type="button" class="reset-columns" @click="emit('reset-columns')">
          恢复默认列
        </button>
      </div>
    </details>
    <slot />
  </div>
</template>

<style scoped>
.document-column-options-list {
  width: min(380px, calc(100vw - 32px));
}

.document-column-options-list ol {
  list-style: none;
  margin: 0;
  padding: 0;
}

.document-column-options-list li {
  display: grid;
  grid-template-columns: minmax(90px, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding-block: 4px;
}

.document-column-options-list label {
  padding: 0;
  min-height: 28px;
}

.column-placement {
  display: flex;
  align-items: center;
  gap: 4px;
}

.column-placement select,
.column-placement button,
.reset-columns {
  min-height: 28px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--erp-radius);
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
}

.column-placement select {
  max-width: 95px;
}

.column-placement button {
  width: 28px;
  cursor: pointer;
}

.column-placement :disabled {
  color: var(--el-text-color-disabled);
  cursor: default;
}

.reset-columns {
  margin-top: 8px;
  padding-inline: 8px;
  cursor: pointer;
}

@media (max-width: 767px) {
  .document-column-options-list {
    position: fixed;
    inset: auto 16px 16px;
    width: auto;
    max-height: min(60dvh, 360px);
  }
}
</style>
