<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from './stores'

const route = useRoute()
const router = useRouter()
const store = useAppStore()

const isLogin = computed(() => route.path === '/login')

// 项目选择/管理已迁移到「流程编排」页，顶部栏只保留全局导航与账号菜单

const menus = [
  { path: '/dashboard', label: '数据看板' },
  { path: '/sources', label: '数据源' },
  { path: '/flows', label: '流程编排' },
  { path: '/nodes', label: '节点管理' },
  { path: '/variables', label: '变量' },
  { path: '/executions', label: '执行日志' },
  { path: '/ai', label: 'AI 助手' }
]

onMounted(() => {
  if (!isLogin.value) {
    store.loadProjects().then(() => store.loadNodeDefinitions())
  }
})

function logout() {
  store.logout()
  router.push('/login')
}
</script>

<template>
  <router-view v-if="isLogin" />
  <el-container v-else class="app-shell">
    <el-aside width="220px" class="sidebar">
      <div class="brand">
        <div class="brand-mark">TL</div>
        <div>
          <div class="brand-name">TongLink Studio</div>
          <div class="brand-sub">IT / OT 数据采集与编排</div>
        </div>
      </div>
      <nav class="nav">
        <router-link v-for="m in menus" :key="m.path" :to="m.path" class="nav-item"
                     :class="{ active: route.path === m.path }">
          {{ m.label }}
        </router-link>
      </nav>
      <div class="sidebar-foot">MIT 开源 · v0.1.0</div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-left">
          <span class="topbar-title">{{ menus.find(m => m.path === route.path)?.label || 'TongLink Studio' }}</span>
        </div>
        <div class="topbar-right">
          <el-dropdown @command="logout">
            <span class="avatar">{{ store.user?.displayName?.[0] || '管' }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
