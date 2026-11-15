import type { DataRow, FormField, SelectOption } from '../types/admin'

export function flattenRows(rows: DataRow[], prefix = ''): DataRow[] {
  return rows.flatMap((row) => [
    { ...row, treeLabel: prefix + String(row.name ?? row.code ?? row.id) },
    ...flattenRows(row.children ?? [], prefix + '　'),
  ])
}

export function optionsFrom(rows: DataRow[]): SelectOption[] {
  return flattenRows(rows).map((row) => ({
    value: row.id,
    label: String(row.treeLabel ?? row.name ?? row.code ?? row.id),
  }))
}

export function fieldValues(fields: FormField[], row?: DataRow): Record<string, unknown> {
  return Object.fromEntries(
    fields
      .filter((f) => !row || !f.createOnly)
      .map((f) => [
        f.key,
        row?.[f.key] ??
          f.initial ??
          (f.kind === 'boolean' ? false : f.kind === 'multiple' ? [] : ''),
      ]),
  )
}

export function editorPayload(
  fields: FormField[],
  values: Record<string, unknown>,
  row?: DataRow,
): Record<string, unknown> {
  const data: Record<string, unknown> = {}
  for (const field of fields) {
    if (row && field.createOnly) continue
    const value = row && field.readonlyOnEdit ? row[field.key] : values[field.key]
    if (field.kind === 'password') data[field.key] = value
    else if (typeof value === 'string') data[field.key] = value.trim() || (field.emptyAsNull === false ? '' : null)
    else if (field.kind === 'number' && value !== '') data[field.key] = Number(value)
    else data[field.key] = value
  }
  if (row?.version !== undefined) data.version = row.version
  return data
}
