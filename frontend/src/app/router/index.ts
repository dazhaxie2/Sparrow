import { createRouter, createWebHistory } from 'vue-router'
import { aiRoutes } from '../../modules/ai/routes'
import { chainRoutes } from '../../modules/chains/routes'
import { graphRoutes } from '../../modules/graph/routes'
import { tradeRoutes } from '../../modules/trade/routes'

const router = createRouter({
  history: createWebHistory(),
  routes: [...graphRoutes, ...tradeRoutes, ...aiRoutes, ...chainRoutes],
})

export default router
