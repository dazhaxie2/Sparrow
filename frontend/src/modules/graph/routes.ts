import type { RouteRecordRaw } from 'vue-router'

export const graphRoutes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('./views/HomeView.vue'),
  },
]
