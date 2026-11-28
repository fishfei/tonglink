import axios from 'axios'
import { ElMessage } from 'element-plus'

/** 大模型生成长文本可能超过 30 秒；只有 AI 接口放宽，普通接口仍保持 30 秒。 */
const AI_TIMEOUT = 180000

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
    request.post('/ai/generate-flow', { prompt, projectId }, { timeout: AI_TIMEOUT }),
  aiDiagnose: (executionId: string) =>
    request.post('/ai/diagnose', { executionId }, { timeout: AI_TIMEOUT }),
  aiAsk: (question: string) =>
    request.post('/ai/ask', { question }, { timeout: AI_TIMEOUT }),

  /**
   * 流式问答：逐段回调模型输出，返回最后一帧的 meta（来源/厂商）。
   *
   * 结束判定以服务端下发的 `meta` 事件为准，而不死等 reader 的 done 信号：
   * SseEmitter 有时不会及时送达流关闭信号，若最后一块 meta 又没带尾部空行，
   * 它会一直滞留在 buffer 里，导致回答显示完了「正在思考」却不结束。
   */
  aiAskStream: async (question: string, onDelta: (chunk: string) => void): Promise<any> => {
    const baseURL = import.meta.env.VITE_API_BASE || '/api/v1'
    const token = localStorage.getItem('tls_token')
    const controller = new AbortController()
    // 兜底超时：即使服务端既不发 meta 也不关流，也要让前端结束 loading
    const timeoutId = setTimeout(() => controller.abort(), AI_TIMEOUT)
    let response: Response
    try {
      response = await fetch(`${baseURL}/ai/ask/stream`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify({ question })
      })
    } catch (e: any) {
      clearTimeout(timeoutId)
      if (e?.name === 'AbortError') throw new Error('等待模型响应超时，请稍后重试')
      throw e
    }
    if (response.status === 401) {
      clearTimeout(timeoutId)
      localStorage.removeItem('tls_token')
      if (location.hash !== '#/login') location.hash = '#/login'
      throw new Error('登录已过期，请重新登录')
    }
    if (!response.ok || !response.body) {
      clearTimeout(timeoutId)
      let message = `请求失败（HTTP ${response.status}）`
      try {
        const body: any = await response.json()
        message = body.message || body.error || message
      } catch { /* 非 JSON 错误体，保留默认提示 */ }
      throw new Error(message)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let meta: any = {}
    let gotMeta = false

    /** 解析一个 SSE 块；返回是否收到 meta。 */
    const consume = (block: string): boolean => {
      let event = 'message'
      let data = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        // SseEmitter 会把多行内容拆成多条 data 行，拼回时保留换行
        else if (line.startsWith('data:')) data += (data ? '\n' : '') + line.slice(5).trim()
      }
      if (!data) return false
      if (event === 'delta') {
        onDelta(data)
      } else if (event === 'meta') {
        try { meta = JSON.parse(data) } catch { /* 忽略非法 meta */ }
        return true
      }
      return false
    }

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (value) {
          buffer += decoder.decode(value, { stream: true })
          const blocks = buffer.split('\n\n')
          buffer = blocks.pop() || ''
          for (const block of blocks) {
            if (consume(block)) gotMeta = true
          }
        }
        // 收到 meta 即完成；或服务流真正关闭
        if (gotMeta || done) {
          // 处理尾部没有空行结尾的残留块（常见于 meta 帧）
          if (buffer.trim()) consume(buffer)
          break
        }
      }
    } finally {
      clearTimeout(timeoutId)
      // 主动取消流，避免服务端 SseEmitter 未及时 complete 时连接悬挂
      try { reader.cancel() } catch { /* 流可能已关闭 */ }
    }
    return meta
  },

  aiSettings: () => request.get('/ai/settings'),
  saveAiSettings: (data: any) => request.put('/ai/settings', data),
  testAiSettings: (data: any) => request.post('/ai/settings/test', data, { timeout: AI_TIMEOUT }),
  resetAiSettings: () => request.post('/ai/settings/reset')
}
