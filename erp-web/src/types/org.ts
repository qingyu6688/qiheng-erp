export interface Department {
  id: string
  parentId: string
  code: string
  name: string
  leaderUserId: string | null
  leaderName: string | null
  sortNo: number
  path: string
  depth: number
  status: 'active' | 'disabled'
  version: number
  memberCount: number
}
export interface DepartmentNode extends Department {
  children: DepartmentNode[]
}
export interface OrgUser {
  id: string
  username: string
  displayName: string
  status: string
}
export interface OrgPost {
  id: string
  code: string
  name: string
  status: string
  version: number
}
export interface UserOrganization {
  userId: string
  companyId: string
  version: number
  departments: { id: string; primary: boolean }[]
  postIds: string[]
}
export interface AssignmentOptions {
  departments: Department[]
  posts: OrgPost[]
}
export interface DepartmentInput {
  code: string
  name: string
  parentId: string
  leaderUserId: string | null
  sortNo: number
  version?: number
}
export function departmentTree(rows: Department[]): DepartmentNode[] {
  const nodes = new Map(rows.map((row) => [row.id, { ...row, children: [] } as DepartmentNode]))
  const roots: DepartmentNode[] = []
  for (const node of nodes.values()) {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
