import { ttlCache } from '../../shared/utils/ttlCache'
import { get } from '../../shared/api/request'
import type { ChainDetail, ChainGraph, ChainSummary } from './types'

export function fetchChains() {
  return ttlCache('chains:list', 300000, () => get<ChainSummary[]>('/api/chains'))
}

export function fetchChainGraph(slug: string) {
  return get<ChainGraph>(`/api/chains/${slug}/graph`)
}

export function fetchChain(slug: string) {
  return get<ChainDetail>(`/api/chains/${slug}`)
}
