import type { RouteRecordRaw } from 'vue-router'

export const bizRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('../../views/Biz/HomeView.vue'),
  },
  {
    path: '/pay',
    name: 'pay',
    component: () => import('../../views/Biz/PayView.vue'),
  },
]
