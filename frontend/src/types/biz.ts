export interface Profile {
  id: number
  username: string
  member: boolean
  memberExpireAt: string | null
}

export interface AuthResult {
  token: string
}

export interface NodeBrief {
  id: number
  code: string
  name: string
  era: string
  eraRank: number
  yearLabel: string
  summary: string
  premium: boolean
}

export interface EdgeBrief {
  from: number
  to: number
}

export interface Tree {
  nodes: NodeBrief[]
  edges: EdgeBrief[]
}

export interface NodeDetail {
  id: number
  code: string
  name: string
  era: string
  eraRank: number
  yearLabel: string
  summary: string
  detail: string | null
  premium: boolean
  locked: boolean
  prerequisites: NodeBrief[]
  unlocks: NodeBrief[]
}

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
