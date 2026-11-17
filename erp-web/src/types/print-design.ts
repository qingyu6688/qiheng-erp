export type PrintElementType =
  'text' | 'field' | 'barcode' | 'qrcode' | 'image' | 'line' | 'rectangle' | 'pageNumber'
export type PrintFormat = 'text' | 'money' | 'quantity' | 'datetime'
export type PrintLocale = 'zh-CN' | 'en-US'
export type PrintSection = 'header' | 'footer'
export type PrintImage = 'companyLogo' | 'seal'
export interface PrintPaper {
  kind: 'A4' | 'A5' | 'third' | 'custom'
  orientation: 'portrait' | 'landscape'
  width?: number
  height?: number
  margins: { top: number; right: number; bottom: number; left: number }
}
export interface ElementStyle {
  fontSize: number
  align: 'left' | 'center' | 'right'
  bold: boolean
}
export interface PrintElement {
  type: PrintElementType
  x: number
  y: number
  w: number
  h: number
  field?: string
  text?: string
  textEn?: string
  label?: string
  labelEn?: string
  format?: PrintFormat
  source?: PrintImage
  symbology?: 'code128' | 'qr'
  style: ElementStyle
}
export interface PrintTableColumn {
  field: string
  title: string
  titleEn?: string
  w: number
  total: boolean
  format?: PrintFormat
}
export interface PrintDesignTable {
  x: number
  y: number
  rowHeight: number
  rowsPerPage: number
  repeatHeader: boolean
  showTotalRow: boolean
  columns: PrintTableColumn[]
}
export interface PrintDesignLayout {
  paper: PrintPaper
  locale: PrintLocale
  header: PrintElement[]
  table: PrintDesignTable | null
  footer: PrintElement[]
  images?: Partial<Record<PrintImage, string>>
  background?: string | null
}
export interface PrintTemplateSummary {
  id: string
  documentType: string
  name: string
  shared: boolean
  status: 'draft' | 'active' | 'disabled'
  isDefault: boolean
  version: number
}
export interface PrintTemplate extends PrintTemplateSummary {
  layout: PrintDesignLayout
}
export type PrintTemplateInput = Omit<PrintTemplate, 'id' | 'version'> & { version?: number }
export interface PrintDocumentType {
  id: string
  label: string
  english: string
}
export interface PrintDesignField extends PrintDocumentType {
  kind: PrintFormat
  permission?: string
  line: boolean
}
export interface PrintInk {
  type: 'text' | 'rectangle' | 'line' | 'image'
  x: number
  y: number
  w: number
  h: number
  lines: string[]
  image?: string
  fontSize: number
  align: 'left' | 'center' | 'right'
  bold: boolean
}
export interface PrintModel {
  width: number
  height: number
  background?: string | null
  pages: Array<{ items: PrintInk[] }>
  assets?: Record<string, string>
}
export interface PrintOutput {
  templates: PrintTemplateSummary[]
  templateId: string | null
  version: number | null
  model: PrintModel | null
}

export const printElementLabels: Record<PrintElementType, string> = {
  text: '文本',
  field: '业务字段',
  barcode: '条码',
  qrcode: '二维码',
  image: '图片',
  line: '线条',
  rectangle: '矩形',
  pageNumber: '页码',
}

export function paperDimensions(paper: PrintPaper): { width: number; height: number } {
  let size: [number, number]
  if (paper.kind === 'A4') size = [210, 297]
  else if (paper.kind === 'A5') size = [148, 210]
  else if (paper.kind === 'third') size = [241, 93]
  else size = [paper.width ?? 210, paper.height ?? 297]
  const [width, height] = paper.orientation === 'portrait' ? size : [size[1], size[0]]
  return { width, height }
}

export function createElement(type: PrintElementType): PrintElement {
  return {
    type,
    x: 0,
    y: 0,
    w: type === 'qrcode' ? 22 : 55,
    h: type === 'qrcode' || type === 'barcode' ? 22 : 8,
    style: { fontSize: 10, align: 'left', bold: false },
    ...(type === 'text' ? { text: '模板文字', textEn: 'Print text' } : {}),
    ...(['field', 'qrcode', 'barcode'].includes(type) ? { field: 'documentNo' } : {}),
    ...(type === 'image' ? { source: 'companyLogo' as const } : {}),
    ...(type === 'barcode' ? { symbology: 'code128' as const } : {}),
  }
}

export function emptyTemplate(type: string): PrintTemplateInput {
  return {
    documentType: type,
    name: '',
    shared: false,
    status: 'draft',
    isDefault: false,
    layout: {
      paper: {
        kind: 'A4',
        orientation: 'portrait',
        width: 210,
        height: 297,
        margins: { top: 10, right: 10, bottom: 10, left: 10 },
      },
      locale: 'zh-CN',
      header: [
        {
          ...createElement('field'),
          field: 'documentTitle',
          w: 120,
          style: { fontSize: 18, bold: true, align: 'left' },
        },
        { ...createElement('field'), y: 14, w: 95, label: '单号：', labelEn: 'Document no.: ' },
      ],
      footer: [
        {
          ...createElement('pageNumber'),
          x: 110,
          y: 255,
          w: 60,
          style: { fontSize: 9, bold: false, align: 'right' },
        },
      ],
      table: null,
    },
  }
}
