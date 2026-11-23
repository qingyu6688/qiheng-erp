export interface PaymentTerm {
  type: 'days' | 'monthly' | null
  value: number | null
}
