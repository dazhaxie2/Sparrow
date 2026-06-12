import type { RouteRecordRaw } from 'vue-router'

export const tradeRoutes: RouteRecordRaw[] = [
  {
    path: '/pay',
    name: 'pay',
    component: () => import('./views/PayView.vue'),
  },
]
