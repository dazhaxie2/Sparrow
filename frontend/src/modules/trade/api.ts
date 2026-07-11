import { post } from '../../shared/api/request'
import type { CreateOrderResult } from './types'

export function createOrder(productCode: string) {
  return post<CreateOrderResult>('/api/trade/order', { productCode })
}
