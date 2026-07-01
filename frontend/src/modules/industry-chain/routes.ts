import type { RouteRecordRaw } from 'vue-router'

export const chainRoutes: RouteRecordRaw[] = [
  {
    path: '/chains',
    name: 'industry-chain-home',
    component: () => import('./views/IndustryChainHomeView.vue'),
  },
  {
    path: '/chains/research/:id',
    redirect: route => `/chains/${route.params.id}`,
  },
  {
    path: '/chains/:id(\\d+)',
    name: 'industry-chain-workbench',
    component: () => import('./views/IndustryChainWorkbenchView.vue'),
    props: route => ({ id: Number(route.params.id) }),
  },
]
