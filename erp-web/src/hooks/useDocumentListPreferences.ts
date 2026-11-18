import { ref, watch } from 'vue'
import type { Ref } from 'vue'

export interface DocumentListColumnPreference {
  key: string
  label: string
  required?: boolean
  fixed?: DocumentColumnFixed
}

export type DocumentColumnFixed = 'left' | 'right'

export function arrangeDocumentColumns<T extends DocumentListColumnPreference>(
  columns: T[],
  order: string[],
  fixed: Record<string, DocumentColumnFixed>,
): T[] {
  const positions = new Map(order.map((key, index) => [key, index]))
  const arranged = columns
    .map((column, index) => ({
      ...column,
      fixed: column.required ? column.fixed : fixed[column.key],
      position: positions.get(column.key) ?? order.length + index,
    }))
    .sort((first, second) => first.position - second.position)
  const side = (column: DocumentListColumnPreference): number =>
    column.fixed === 'left' ? 0 : column.fixed === 'right' ? 2 : 1
  return arranged.sort((first, second) => {
    const difference = side(first) - side(second)
    if (difference) return difference
    if (first.required || second.required) {
      const direction = first.fixed === 'right' ? 1 : -1
      return (
        Number(Boolean(first.required)) * direction - Number(Boolean(second.required)) * direction
      )
    }
    return first.position - second.position
  })
}

export function useDocumentListPreferences(key: () => string | undefined): {
  expanded: Ref<boolean>
  hidden: Ref<string[]>
  columnOrder: Ref<string[]>
  columnFixed: Ref<Record<string, DocumentColumnFixed>>
  setExpanded: (value: boolean) => void
  toggleColumn: (column: string) => void
  moveColumn: (columns: DocumentListColumnPreference[], column: string, direction: -1 | 1) => void
  fixColumn: (column: string, side?: DocumentColumnFixed) => void
  resetColumns: () => void
} {
  const expanded = ref(false)
  const hidden = ref<string[]>([])
  const columnOrder = ref<string[]>([])
  const columnFixed = ref<Record<string, DocumentColumnFixed>>({})
  watch(
    key,
    (storageKey) => {
      expanded.value = false
      hidden.value = []
      columnOrder.value = []
      columnFixed.value = {}
      if (!storageKey) return
      try {
        const stored = JSON.parse(localStorage.getItem(storageKey) ?? 'null') as {
          expanded?: unknown
          hidden?: unknown
          order?: unknown
          fixed?: unknown
        } | null
        expanded.value = stored?.expanded === true
        if (Array.isArray(stored?.hidden)) {
          hidden.value = stored.hidden.filter(
            (column): column is string => typeof column === 'string',
          )
        }
        if (Array.isArray(stored?.order)) {
          columnOrder.value = [
            ...new Set(
              stored.order.filter((column): column is string => typeof column === 'string'),
            ),
          ]
        }
        if (stored?.fixed && typeof stored.fixed === 'object' && !Array.isArray(stored.fixed)) {
          columnFixed.value = Object.fromEntries(
            Object.entries(stored.fixed).filter(
              (entry): entry is [string, DocumentColumnFixed] =>
                entry[1] === 'left' || entry[1] === 'right',
            ),
          )
        }
      } catch {
        // 浏览器存储受限时仍可在当前页面收起查询和调整列。
        expanded.value = false
        hidden.value = []
        columnOrder.value = []
        columnFixed.value = {}
      }
    },
    { immediate: true, flush: 'sync' },
  )

  function save(): void {
    const storageKey = key()
    if (!storageKey) return
    try {
      localStorage.setItem(
        storageKey,
        JSON.stringify({
          expanded: expanded.value,
          hidden: hidden.value,
          order: columnOrder.value,
          fixed: columnFixed.value,
        }),
      )
    } catch {
      // 当次页面操作已生效。
      return
    }
  }

  function setExpanded(value: boolean): void {
    expanded.value = value
    save()
  }

  function toggleColumn(column: string): void {
    hidden.value = hidden.value.includes(column)
      ? hidden.value.filter((key) => key !== column)
      : [...hidden.value, column]
    save()
  }

  function moveColumn(
    columns: DocumentListColumnPreference[],
    column: string,
    direction: -1 | 1,
  ): void {
    const index = columns.findIndex((item) => item.key === column)
    const current = columns[index]
    const adjacent = columns[index + direction]
    if (
      !current ||
      !adjacent ||
      current.required ||
      adjacent.required ||
      current.fixed !== adjacent.fixed
    )
      return
    const keys = columns.map((item) => item.key)
    keys[index] = adjacent.key
    keys[index + direction] = current.key
    columnOrder.value = keys
    save()
  }

  function fixColumn(column: string, side?: DocumentColumnFixed): void {
    const next = { ...columnFixed.value }
    if (side) next[column] = side
    else delete next[column]
    columnFixed.value = next
    save()
  }

  function resetColumns(): void {
    hidden.value = []
    columnOrder.value = []
    columnFixed.value = {}
    save()
  }

  return {
    expanded,
    hidden,
    columnOrder,
    columnFixed,
    setExpanded,
    toggleColumn,
    moveColumn,
    fixColumn,
    resetColumns,
  }
}
