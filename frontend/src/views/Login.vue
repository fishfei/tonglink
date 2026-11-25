<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '../stores'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const store = useAppStore()
const username = ref(localStorage.getItem('tls_remember') || 'admin')
const password = ref('')
const remember = ref(!!localStorage.getItem('tls_remember'))
const loading = ref(false)
const error = ref('')

const accounts = [
  { label: '管理员', username: 'admin', password: 'Admin@123' }
]

const features = [
  { icon: 'workflow', text: 'DAG 可视化编排' },
  { icon: 'cpu', text: '多协议接入（MQTT / Kafka / JDBC）' },
  { icon: 'terminal', text: '节点级执行日志' },
  { icon: 'sparkles', text: 'AI 辅助诊断与生成' }
]

function fill(account: any) {
  username.value = account.username
  password.value = account.password
  error.value = ''
}

async function submit() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  error.value = ''
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
  } catch (e: any) {
    error.value = e?.response?.data?.message || '用户名或密码错误，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-hero">
      <div class="login-brand">
        <span class="brand-mark"><AppIcon name="layers" :size="22" /></span>
        <span>TongLink Studio</span>
      </div>
      <h1>让 IT 与 OT 数据<br />在同一条流水线流动</h1>
      <p class="login-hero-desc">面向工业现场的数据采集平台：可视化编排、多协议接入、代码节点、执行观测与 AI 辅助。</p>
      <div class="login-features">
        <div v-for="f in features" :key="f.text">
          <AppIcon :name="f.icon" :size="18" />
          <span>{{ f.text }}</span>
        </div>
      </div>
      <svg class="login-illus" viewBox="0 0 520 200" fill="none" aria-hidden="true">
        <path d="M60 100h110M225 100h100M380 100h70" stroke="#93C5FD" stroke-width="2" stroke-dasharray="5 5" />
        <rect x="10" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE" />
        <rect x="26" y="86" width="28" height="28" rx="6" fill="#BFDBFE" />
        <circle cx="40" cy="100" r="7" fill="#2563EB" />
        <rect x="170" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE" />
        <rect x="186" y="86" width="28" height="28" rx="6" fill="#BFDBFE" />
        <path d="M193 100h14M200 93v14" stroke="#2563EB" stroke-width="3" />
        <rect x="325" y="70" width="60" height="60" rx="12" fill="#fff" stroke="#BFDBFE" />
        <rect x="341" y="86" width="28" height="28" rx="6" fill="#BFDBFE" />
        <path d="M348 94h14M348 100h14M348 106h14" stroke="#2563EB" stroke-width="2.5" />
        <circle cx="465" cy="100" r="26" fill="#fff" stroke="#BFDBFE" />
        <path d="m457 100 5 5 10-11" stroke="#16A34A" stroke-width="3" fill="none" stroke-linecap="round" />
      </svg>
      <div class="login-hero-foot">MIT Open Source · TongLink Studio</div>
    </div>
    <div class="login-panel">
      <div class="login-card">
        <div class="login-card-head">
          <div class="login-mini-logo"><AppIcon name="layers" :size="20" /></div>
          <h2>欢迎回来</h2>
          <p>登录 TongLink Studio 控制台</p>
        </div>
        <form @submit.prevent="submit">
          <div class="field">
            <label>用户名</label>
            <el-input v-model="username" size="large" placeholder="请输入用户名" autocomplete="username" />
          </div>
          <div class="field">
            <label>密码</label>
            <el-input v-model="password" size="large" type="password" show-password placeholder="请输入密码"
                      autocomplete="current-password" @keyup.enter="submit" />
          </div>
          <div class="login-row">
            <el-checkbox v-model="remember">记住用户名</el-checkbox>
          </div>
          <div class="login-error">{{ error }}</div>
          <el-button type="primary" class="login-submit" :loading="loading" @click="submit">
            <AppIcon name="shield" :size="16" /> 登 录
          </el-button>
        </form>
        <div class="login-divider"><span>内置演示账号（点击自动填充）</span></div>
        <div class="login-accounts">
          <button v-for="a in accounts" :key="a.username" type="button" class="login-account" @click="fill(a)">
            <strong>{{ a.username }}</strong>
            <span>{{ a.label }}</span>
          </button>
        </div>
        <div class="login-tip">
          <AppIcon name="info" :size="13" /> 使用内置账号进入系统，生产环境可接入企业 SSO。
        </div>
      </div>
    </div>
  </div>
</template>
