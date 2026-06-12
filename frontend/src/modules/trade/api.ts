import { get, post } from '../../shared/api/request'
import type { Product, CreateOrderResult, OrderView } from './types'

export function fetchProducts() {
  return get<Product[]>('/api/trade/products')
}

export function createOrder(productCode: string) {
  return post<CreateOrderResult>('/api/trade/order', { productCode })
}

export function fetchOrders() {
  return get<OrderView[]>('/api/trade/orders')
}
