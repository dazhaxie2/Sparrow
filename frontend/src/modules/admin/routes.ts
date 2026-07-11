import type { RouteRecordRaw } from 'vue-router'

export const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/agents',
    name: 'admin-agent-config',
    component: () => import('./views/AgentConfigView.vue'),
    meta: { requiresAdmin: true },
  },
  {
    path: '/admin/models',
    name: 'admin-model-config',
    component: () => import('./views/ModelConfigView.vue'),
    meta: { requiresAdmin: true },
  },
]
