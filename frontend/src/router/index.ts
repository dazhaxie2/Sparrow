import { createRouter, createWebHistory } from 'vue-router'
import { bizRoutes } from './routes/biz'
import { aiRoutes } from './routes/ai'

const router = createRouter({
  history: createWebHistory(),
  routes: [...bizRoutes, ...aiRoutes],
})

export default router
