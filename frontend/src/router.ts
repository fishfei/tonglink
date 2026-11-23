import { createRouter, createWebHashHistory } from 'vue-router'

/** 路由表；未登录统一跳转到内置登录页。 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: () => import('./views/Login.vue') },
    { path: '/dashboard', component: () => import('./views/Dashboard.vue') },
    { path: '/sources', component: () => import('./views/Sources.vue') },
    { path: '/flows', component: () => import('./views/Flows.vue') },
    { path: '/variables', component: () => import('./views/Variables.vue') },
    { path: '/executions', component: () => import('./views/Executions.vue') },
    { path: '/ai', component: () => import('./views/Ai.vue') }
  ]
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('tls_token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else if (to.path === '/login' && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
