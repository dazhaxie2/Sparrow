import { get, post } from '../request'
import type { Product, CreateOrderResult, OrderView } from '../../types/biz'

export function fetchProducts() {
  return get<Product[]>('/api/trade/products')
}

export function createOrder(productCode: string) {
  return post<CreateOrderResult>('/api/trade/order', { productCode })
}

export function fetchOrders() {
  return get<OrderView[]>('/api/trade/orders')
}
