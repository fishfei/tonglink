<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '../stores'

const router = useRouter()
const store = useAppStore()
const username = ref(localStorage.getItem('tls_remember') || 'admin')
const password = ref('')
const remember = ref(!!localStorage.getItem('tls_remember'))
const loading = ref(false)

const accounts = [
  { label: '管理员', username: 'admin', password: 'Admin@123' }
]

function fill(account: any) {
  username.value = account.username
  password.value = account.password
}

async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(username.value, password.value)
    if (remember.value) {
      localStorage.setItem('tls_remember', username.value)
    } else {
      localStorage.removeItem('tls_remember')
    }
    await store.loadProjects()
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-left">
      <h1>TongLink Studio</h1>
      <p>面向工业现场的 IT / OT 数据采集与编排平台。用 DAG 连接 OPC UA、Modbus、MQTT 与数据库，让数据流动起来。</p>
      <p>MIT 开源 · Vue 3 + Spring Boot · 支持单容器轻量部署</p>
    </div>
    <div class="login-right">
      <div class="login-card">
        <h2>登录</h2>
        <div class="login-tip">使用内置账号进入系统，生产环境可接入企业 SSO。</div>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名">
            <el-input v-model="username" size="large" placeholder="admin" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="password" size="large" type="password" show-password placeholder="请输入密码" @keyup.enter="submit" />
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="remember">记住用户名</el-checkbox>
          </el-form-item>
          <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">登 录</el-button>
        </el-form>
        <div class="login-accounts">
          <div class="muted" style="margin-bottom:8px">内置演示账号（点击填充）</div>
          <el-button v-for="a in accounts" :key="a.username" size="small" @click="fill(a)">{{ a.label }}</el-button>
        </div>
      </div>
    </div>
  </div>
</template>
