import { createRouter, createWebHistory } from 'vue-router'
import { aiRoutes } from '../../modules/ai/routes'
import { adminRoutes } from '../../modules/admin/routes'
import { chainRoutes } from '../../modules/industry-chain/routes'
import { graphRoutes } from '../../modules/graph/routes'
import { tradeRoutes } from '../../modules/trade/routes'
import { useUserStore } from '../../modules/user/store'

const router = createRouter({
  history: createWebHistory(),
  routes: [...graphRoutes, ...tradeRoutes, ...aiRoutes, ...chainRoutes, ...adminRoutes],
})

// 管理端路由守卫:meta.requiresAdmin 时校验角色,非 admin 跳首页。
router.beforeEach(to => {
  if (to.meta.requiresAdmin) {
    const user = useUserStore()
    if (!user.isLoggedIn() || !user.isAdmin()) {
      return { path: '/' }
    }
  }
})

export default router
