import { allRecords } from './admin'
import { departments } from './org'

export interface AiChoice {
  id: string
  label: string
}

export async function aiRoleChoices(): Promise<AiChoice[]> {
  const roles = await allRecords('roles')
  return roles
    .filter((role) => role.status !== 'disabled')
    .map((role) => ({
      id: role.id,
      label: String(role.name ?? role.code ?? role.id),
    }))
}

export async function aiDepartmentChoices(): Promise<AiChoice[]> {
  const items = await departments()
  return items
    .filter((department) => department.status === 'active')
    .map((department) => ({
      id: department.id,
      label: `${department.name}（${department.code}）`,
    }))
}
