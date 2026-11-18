import type { CSSProperties } from 'vue'

/** 单表布局由浏览器分配列宽，列头保留单据列声明的最小可读宽度。 */
export function documentHeaderCellStyle({
  column,
}: {
  column: { minWidth: number | string }
}): CSSProperties {
  return { minWidth: column.minWidth + 'px' }
}
