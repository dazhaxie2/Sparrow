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
router.beforeEach(async to => {
  if (to.meta.requiresAdmin) {
    const user = useUserStore()
    if (user.token && !user.profile) await user.loadProfile()
    if (!user.isLoggedIn() || !user.isAdmin()) {
      return { path: '/' }
    }
  }
})

// 记忆最后访问的产业链工作台,供顶栏"产业链"导航回到它而非列表页。
// 用 sessionStorage:同一会话内有效(含刷新),关浏览器后清空,符合"最近上下文"语义。
router.afterEach(to => {
  const match = /^\/chains\/(\d+)$/.exec(to.path)
  if (match) {
    sessionStorage.setItem('sparrow_last_chain_id', match[1])
  }
})

export default router
