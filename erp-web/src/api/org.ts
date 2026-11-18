import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  AssignmentOptions,
  Department,
  DepartmentInput,
  OrgUser,
  RoleDataScope,
  UserOrganization,
} from '../types/org'

export function roleDataScope(id: string, signal?: AbortSignal): Promise<RoleDataScope> {
  return request({ method: 'GET', url: '/org/roles/' + id + '/data-scope', signal }, true)
}
export function saveRoleDataScope(
  id: string,
  body: Pick<RoleDataScope, 'scopeType' | 'departmentIds' | 'version'>,
  key: string,
): Promise<RoleDataScope> {
  return request(
    {
      method: 'PUT',
      url: '/org/roles/' + id + '/data-scope',
      data: body,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function departments(signal?: AbortSignal): Promise<Department[]> {
  return request({ method: 'GET', url: '/org/departments/tree', signal }, true)
}
export function companyUsers(
  keyword = '',
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<OrgUser>> {
  return request(
    { method: 'GET', url: '/org/company-users', params: { keyword, cursor, limit: 50 }, signal },
    true,
  )
}
export function departmentMembers(
  id: string,
  cursor?: string,
  signal?: AbortSignal,
  includeChildren = false,
): Promise<PageResult<OrgUser>> {
  return request(
    {
      method: 'GET',
      url: '/org/departments/' + id + '/members',
      params: { cursor, limit: 50, includeChildren },
      signal,
    },
    true,
  )
}
export function assignmentOptions(signal?: AbortSignal): Promise<AssignmentOptions> {
  return request({ method: 'GET', url: '/org/assignment-options', signal }, true)
}
export function userOrganization(id: string, signal?: AbortSignal): Promise<UserOrganization> {
  return request({ method: 'GET', url: '/users/' + id + '/organization', signal }, true)
}
export function assignOrganization(
  id: string,
  body: Omit<UserOrganization, 'userId' | 'companyId'>,
  key: string,
): Promise<UserOrganization> {
  return request(
    {
      method: 'PUT',
      url: '/users/' + id + '/organization',
      data: body,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function saveDepartment(
  body: DepartmentInput,
  key: string,
  id?: string,
): Promise<Department> {
  return request(
    {
      method: id ? 'PUT' : 'POST',
      url: '/org/departments' + (id ? '/' + id : ''),
      data: body,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function moveDepartment(
  id: string,
  parentId: string,
  beforeId: string | null,
  version: number,
  key: string,
): Promise<Department> {
  return request(
    {
      method: 'POST',
      url: '/org/departments/' + id + '/move',
      data: { parentId, beforeId, version },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function departmentStatus(row: Department, key: string): Promise<Department> {
  return request(
    {
      method: 'POST',
      url: '/org/departments/' + row.id + '/' + (row.status === 'active' ? 'disable' : 'enable'),
      data: { version: row.version },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
