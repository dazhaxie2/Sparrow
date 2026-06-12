export interface Product {
  code: string
  name: string
  amountCent: number
  memberDays: number
}

export interface CreateOrderResult {
  orderNo: string
  payUrl: string
  amountCent: number
}

export interface OrderView {
  orderNo: string
  productCode: string
  productName: string
  amountCent: number
  status: string
  createdAt: string
  paidAt: string | null
}
