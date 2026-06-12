import { get, post } from './http'

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

export function fetchProducts() {
  return get<Product[]>('/api/trade/products')
}

export function createOrder(productCode: string) {
  return post<CreateOrderResult>('/api/trade/order', { productCode })
}

export function fetchOrders() {
  return get<OrderView[]>('/api/trade/orders')
}
