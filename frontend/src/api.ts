import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求封装：
 * - 自动携带 JWT
 * - 统一解包 Result<T>
 * - 401 时清理登录态并跳转登录页
 */
const request: any = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 30000
})

/**
 * 相同错误 4 秒内只提示一次。自动保存/轮询可能连续拿到同一个业务错误，
 * 不再让一模一样的红色提示反复堆叠，影响用户操作。
 */
let lastError: { message: string; at: number } = { message: '', at: 0 }
export function notifyError(message: string) {
  const now = Date.now()
  if (message === lastError.message && now - lastError.at < 4000) return
  lastError = { message, at: now }
  ElMessage.error(message)
}

request.interceptors.request.use(config => {
  const token = localStorage.getItem('tls_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  response => {
    const data = response.data
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code !== 0) {
        notifyError(data.message || '请求失败')
        return Promise.reject(new Error(data.message))
      }
      return data.data
    }
    return data
  },
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('tls_token')
      if (location.hash !== '#/login') {
        location.hash = '#/login'
      }
    }
    notifyError(error.response?.data?.message || error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request

/** 常用 API 集合，集中管理后端路径。 */
export const api = {
  login: (username: string, password: string) =>
    request.post('/auth/login', { username, password }),
  me: () => request.get('/auth/me'),

  projects: () => request.get('/projects'),
  createProject: (data: any) => request.post('/projects', data),
  updateProject: (id: string, data: any) => request.put(`/projects/${id}`, data),
  deleteProject: (id: string) => request.delete(`/projects/${id}`),

  sources: (protocol?: string) => request.get('/datasources', { params: { protocol } }),
  createSource: (data: any) => request.post('/datasources', data),
  updateSource: (id: string, data: any) => request.put(`/datasources/${id}`, data),
  deleteSource: (id: string) => request.delete(`/datasources/${id}`),
  testSource: (id: string) => request.post(`/datasources/${id}/test`),
  testSourceDraft: (data: any) => request.post('/datasources/test', data),

  // 凭据类型归在数据源下，认证配置本身也保存在数据源里
  credentialTypes: () => request.get('/datasources/credential-types'),
  variables: () => request.get('/variables'),
  saveVariable: (data: any) => request.post('/variables', data),
  updateVariable: (id: string, data: any) => request.put(`/variables/${id}`, data),
  deleteVariable: (id: string) => request.delete(`/variables/${id}`),

  flows: (projectId?: string) => request.get('/flows', { params: { projectId } }),
  flow: (id: string) => request.get(`/flows/${id}`),
  createFlow: (data: any) => request.post('/flows', data),
  updateFlow: (id: string, data: any) => request.put(`/flows/${id}`, data),
  deleteFlow: (id: string) => request.delete(`/flows/${id}`),
  publishFlow: (id: string) => request.post(`/flows/${id}/publish`),
  unpublishFlow: (id: string) => request.post(`/flows/${id}/unpublish`),
  runFlow: (id: string) => request.post(`/flows/${id}/run`, {}),
  flowVersions: (id: string) => request.get(`/flows/${id}/versions`),

  nodeDefinitions: () => request.get('/nodes/definitions'),
  nodeMarket: () => request.get('/nodes/market'),
  nodePackages: () => request.get('/nodes/packages'),
  installNode: (data: any) => request.post('/nodes/install', data),
  uninstallNode: (id: string) => request.delete(`/nodes/${id}`),

  executions: (params: any) => request.get('/executions', { params }),
  executionPage: (params: any) => request.get('/executions/page', { params }),
  execution: (id: string) => request.get(`/executions/${id}`),
  executionNodes: (id: string) => request.get(`/executions/${id}/nodes`),
  executionLogs: (id: string) => request.get(`/executions/${id}/logs`),

  dashboardSummary: () => request.get('/dashboard/summary'),
  dashboardTrend: (range: string) => request.get('/dashboard/trend', { params: { range } }),
  dashboardFlowStats: () => request.get('/dashboard/flow-stats'),

  aiGenerate: (prompt: string, projectId: string) =>
    request.post('/ai/generate-flow', { prompt, projectId }),
  aiDiagnose: (executionId: string) => request.post('/ai/diagnose', { executionId }),
  aiAsk: (question: string) => request.post('/ai/ask', { question }),

  aiSettings: () => request.get('/ai/settings'),
  saveAiSettings: (data: any) => request.put('/ai/settings', data),
  testAiSettings: (data: any) => request.post('/ai/settings/test', data),
  resetAiSettings: () => request.post('/ai/settings/reset')
}
