import { request } from './http'
import type { PageResult } from '../types/api'
import type {
  PrintDocumentType,
  PrintDesignField,
  PrintDesignLayout,
  PrintModel,
  PrintOutput,
  PrintTemplate,
  PrintTemplateInput,
  PrintTemplateSummary,
} from '../types/print-design'

const path = '/print-templates'
export interface PrintableDocument {
  id: string
  documentNo: string
}
export function printableDocuments(
  type: string,
  keyword: string,
  signal?: AbortSignal,
): Promise<PrintableDocument[]> {
  return request({ url: `${path}/documents/${type}`, params: { keyword }, signal }, true)
}
export function printTemplates(
  documentType?: string,
  cursor?: string,
  signal?: AbortSignal,
): Promise<PageResult<PrintTemplateSummary>> {
  return request({ url: path, params: { documentType, cursor }, signal }, true)
}
export function printTemplate(id: string, signal?: AbortSignal): Promise<PrintTemplate> {
  return request({ url: `${path}/${id}`, signal }, true)
}
export function printTemplateTypes(signal?: AbortSignal): Promise<PrintDocumentType[]> {
  return request({ url: `${path}/document-types`, signal }, true)
}
export function printFields(type: string, signal?: AbortSignal): Promise<PrintDesignField[]> {
  return request({ url: `${path}/fields/${type}`, signal }, true)
}
export function savePrintTemplate(
  id: string | undefined,
  data: PrintTemplateInput,
  signal?: AbortSignal,
): Promise<PrintTemplate> {
  return request(
    { url: id ? `${path}/${id}` : path, method: id ? 'PUT' : 'POST', data, signal },
    true,
  )
}
export function printDesignPreview(
  documentType: string,
  layout: PrintDesignLayout,
  documentId?: string,
  signal?: AbortSignal,
): Promise<PrintModel> {
  return request(
    { url: `${path}/preview`, method: 'POST', data: { documentType, documentId, layout }, signal },
    true,
  )
}
export function customPrintOutput(
  type: string,
  id: string,
  templateId?: string,
  signal?: AbortSignal,
): Promise<PrintOutput> {
  return request({ url: `${path}/output/${type}/${id}`, params: { templateId }, signal }, true)
}
export function logTemplatePrint(
  type: string,
  id: string,
  templateId: string,
  version: number,
  key: string,
  signal?: AbortSignal,
): Promise<void> {
  return request(
    {
      url: `${path}/output/${type}/${id}/log`,
      method: 'POST',
      data: { templateId, version, copies: 1 },
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}
export function createPrintPdf(
  documentType: string,
  templateId: string,
  documentIds: string[],
  copies: number,
  key: string,
  signal?: AbortSignal,
): Promise<{ taskId: string }> {
  return request(
    {
      url: '/export-jobs',
      method: 'POST',
      data: {
        exportType: 'print-pdf',
        financeQuery: { documentType, templateId, documentIds, copies },
      },
      headers: { 'Idempotency-Key': key },
      signal,
    },
    true,
  )
}
