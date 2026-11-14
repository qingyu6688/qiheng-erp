export type Id = string
export type Money = string
export type Price = string
export type Quantity = string

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  requestId: string
}

export interface ApiErrorBody {
  code: number
  errorCode: string
  message: string
  data: null
  requestId: string
  details?: unknown
}

export interface PageResult<T> {
  items: T[]
  nextCursor: string | null
  total?: number
  totalExact?: boolean
}
