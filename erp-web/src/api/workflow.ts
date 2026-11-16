import { request } from './http'
import type {
  WorkflowNode,
  BindingInput,
  Choice,
  ModelInput,
  StartOptions,
  TaskDecision,
  WorkflowBinding,
  WorkflowBusinessType,
  WorkflowInstance,
  WorkflowListRow,
  WorkflowMetadata,
  WorkflowModel,
  WorkflowPage,
  WorkflowProgress,
} from '../types/workflow'
export function workflowModels(): Promise<WorkflowModel[]> {
  return request({ method: 'GET', url: '/workflow/models' }, true)
}
export function workflowModel(id: string): Promise<WorkflowModel> {
  return request({ method: 'GET', url: '/workflow/models/' + id }, true)
}
export function workflowMetadata(): Promise<WorkflowMetadata> {
  return request({ method: 'GET', url: '/workflow/metadata' }, true)
}
export function saveWorkflowModel(
  id: string | undefined,
  data: ModelInput,
  key: string,
): Promise<{ id: string; version: number }> {
  return request(
    {
      method: id ? 'PUT' : 'POST',
      url: '/workflow/models' + (id ? '/' + id : ''),
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function workflowModelAction(
  id: string,
  action: 'publish' | 'disable',
  version: number,
  key: string,
): Promise<{ id: string; version: number }> {
  return request(
    {
      method: 'POST',
      url: '/workflow/models/' + id + '/' + action,
      data: { version },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function workflowBindings(): Promise<WorkflowBinding[]> {
  return request({ method: 'GET', url: '/workflow/bindings' }, true)
}
export function saveWorkflowBinding(
  id: string | undefined,
  data: BindingInput,
  key: string,
): Promise<{ id: string; version: number }> {
  return request(
    {
      method: id ? 'PUT' : 'POST',
      url: '/workflow/bindings' + (id ? '/' + id : ''),
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function workflowList(
  kind: 'todo' | 'done' | 'mine' | 'copies' | 'all',
  params: Record<string, unknown>,
): Promise<WorkflowPage<WorkflowListRow>> {
  const path =
    kind === 'todo' || kind === 'done'
      ? 'tasks/' + kind
      : kind === 'copies'
        ? 'copies'
        : 'instances/' + kind
  return request({ method: 'GET', url: '/workflow/' + path, params }, true)
}
export function workflowInstance(id: string): Promise<WorkflowInstance> {
  return request({ method: 'GET', url: '/workflow/instances/' + id }, true)
}
export function workflowTaskCandidates(id: string): Promise<Choice[]> {
  return request({ method: 'GET', url: '/workflow/tasks/' + id + '/candidates' }, true)
}
export function workflowTaskAction(
  id: string,
  action: string,
  data: TaskDecision,
  key: string,
): Promise<{ id: string; instanceId: string; status: string }> {
  return request(
    {
      method: 'POST',
      url: '/workflow/tasks/' + id + '/' + action,
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function workflowEnd(
  id: string,
  action: 'cancel' | 'terminate',
  version: number,
  comment: string,
  key: string,
): Promise<{ id: string; status: string }> {
  return request(
    {
      method: 'POST',
      url: '/workflow/instances/' + id + '/' + action,
      data: { version, comment },
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}
export function readWorkflowCopy(id: string): Promise<{ id: string; read: boolean }> {
  return request({ method: 'PUT', url: '/workflow/copies/' + id + '/read' }, true)
}
export function workflowProgress(
  businessType: WorkflowBusinessType,
  businessId: string,
): Promise<WorkflowProgress> {
  return request(
    { method: 'GET', url: '/workflow/progress', params: { businessType, businessId } },
    true,
  )
}
export function workflowStartOptions(
  businessType: WorkflowBusinessType,
  businessId: string,
): Promise<StartOptions> {
  return request(
    { method: 'GET', url: '/workflow/start-options', params: { businessType, businessId } },
    true,
  )
}
export function workflowCategories(): Promise<
  { id: string; code: string; name: string; sort: number; version: number }[]
> {
  return request({ method: 'GET', url: '/workflow/categories' }, true)
}
export function saveWorkflowCategory(
  id: string | undefined,
  data: { code: string; name: string; sort: number; version: number },
  key: string,
): Promise<{ id: string; version: number }> {
  return request(
    {
      method: id ? 'PUT' : 'POST',
      url: '/workflow/categories' + (id ? '/' + id : ''),
      data,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function workflowReversalStartOptions(
  sourceType: string,
  sourceId: string,
  businessDate: string,
): Promise<StartOptions> {
  return request(
    { url: '/workflow/reversal-start-options', params: { sourceType, sourceId, businessDate } },
    true,
  )
}
export function workflowModelVersion(
  id: string,
  version: number,
): Promise<{ versionNo: number; simple: WorkflowNode; contentHash: string }> {
  return request({ url: `/workflow/models/${id}/versions/${version}` }, true)
}
