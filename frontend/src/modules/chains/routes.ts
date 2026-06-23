import type { RouteRecordRaw } from 'vue-router'

export const chainRoutes: RouteRecordRaw[] = [
  {
    path: '/chains',
    name: 'chain-list',
    component: () => import('./views/ChainListView.vue'),
  },
  {
    path: '/chains/:slug',
    name: 'chain-detail',
    component: () => import('./views/ChainDetailView.vue'),
    props: true,
  },
]
