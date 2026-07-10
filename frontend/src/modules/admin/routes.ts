import type { RouteRecordRaw } from 'vue-router'

export const adminRoutes: RouteRecordRaw[] = [
  {
    path: '/admin/models',
    name: 'admin-model-config',
    component: () => import('./views/ModelConfigView.vue'),
    meta: { requiresAdmin: true },
  },
]
