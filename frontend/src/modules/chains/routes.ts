import type { RouteRecordRaw } from 'vue-router'

export const chainRoutes: RouteRecordRaw[] = [
  {
    path: '/chains',
    name: 'chain-list',
    component: () => import('./views/ChainListView.vue'),
  },
  {
    path: '/chains/research/:id',
    name: 'chain-research-workbench',
    component: () => import('./views/ChainResearchWorkbenchView.vue'),
    props: route => ({ id: Number(route.params.id) }),
  },
  {
    path: '/chains/:slug',
    name: 'chain-detail',
    component: () => import('./views/ChainDetailView.vue'),
    props: true,
  },
]
