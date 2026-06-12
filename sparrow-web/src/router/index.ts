import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('../views/HomeView.vue'),
    },
    {
      path: '/pay',
      name: 'pay',
      component: () => import('../views/PayView.vue'),
    },
  ],
})

export default router
