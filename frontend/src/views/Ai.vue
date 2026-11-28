<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const router = useRouter()
const messages = ref<any[]>([
  {
    role: 'assistant',
    content: '你好，我是 TongLink Studio AI 助手。我可以基于平台真实数据回答采集任务运行情况、生成流程草稿、诊断执行失败。配置大模型后回答会逐字输出。'
  }
])
const input = ref('')
const loading = ref(false)
const savingDraft = ref('')
const elapsed = ref(0)
let elapsedTimer: number | undefined

function startTimer() {
  elapsed.value = 0
  elapsedTimer = window.setInterval(() => { elapsed.value++ }, 1000)
}
function stopTimer() {
  if (elapsedTimer !== undefined) window.clearInterval(elapsedTimer)
  elapsedTimer = undefined
}

/** AI 生成的图只是内存对象，必须显式建库才能在流程编排页编辑与自动保存。 */
async function saveDraft(message: any) {
  if (!message.flow?.id || message.saved) return
  savingDraft.value = message.flow.id
  try {
    await api.createFlow({
      id: message.flow.id,
      name: message.flow.name,
      projectId: message.flow.projectId || store.projectId,
      graph: message.flow
    })
    message.saved = true
    ElMessage.success('草稿已保存到流程库')
    router.push('/flows')
  } finally {
    savingDraft.value = ''
  }
}

const quickPrompts = [
  '最近有哪些温度异常？',
  '为什么流程执行失败？',
  '帮我生成一个飞书多维表格同步流程',
  '当前采集任务运行情况如何？'
]

function sourceLabel(meta: any): string {
  if (!meta) return '本地规则'
  return meta.source === 'llm' ? `大模型 · ${meta.provider || '已配置'}` : '本地规则'
}

async function send(text?: string) {
  const question = (text || input.value).trim()
  if (!question || loading.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: question })
  // 先占一个空气泡，流式内容到达后直接往里追加，用户不用等到回答全部生成
  const reply = { role: 'assistant', content: '', source: '', streaming: true, flow: undefined as any }
  messages.value.push(reply)
  loading.value = true
  startTimer()
  try {
    if (question.includes('生成') || question.includes('流程')) {
      const result: any = await api.aiGenerate(question, store.projectId)
      reply.content = result.explanation || ''
      reply.flow = result.flow
      reply.source = sourceLabel(result)
    } else {
      const meta: any = await api.aiAskStream(question, chunk => { reply.content += chunk })
      reply.source = sourceLabel(meta)
    }
  } catch (e: any) {
    if (!reply.content) {
      reply.content = `抱歉，请求失败：${e.message || '未知错误'}。请检查「设置 → AI 模型」中的连接信息，或稍后重试。`
    } else {
      reply.content += '\n\n（连接中断，以上为已收到的部分内容）'
    }
    reply.source = '请求失败'
  } finally {
    reply.streaming = false
    loading.value = false
    stopTimer()
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">AI 助手</h1>
        <div class="page-desc">基于平台真实数据问答；可生成流程草稿、诊断执行失败。未配置大模型时使用离线规则引擎</div>
      </div>
    </div>
    <div class="card" style="height:calc(100vh - 160px);display:flex;flex-direction:column">
      <div style="flex:1;overflow:auto;padding:8px 4px">
        <div v-for="(m, i) in messages" :key="i" style="display:flex;margin-bottom:14px"
             :style="{ justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }">
          <div :style="{
            maxWidth: '70%', padding: '10px 14px', borderRadius: '10px',
            background: m.role === 'user' ? '#2563eb' : '#f1f5f9',
            color: m.role === 'user' ? '#fff' : '#1e293b', whiteSpace: 'pre-wrap'
          }">
            <span>{{ m.content }}</span><span v-if="m.streaming" class="stream-cursor">▍</span>
            <div v-if="m.source && !m.streaming" class="muted" style="font-size:11px;margin-top:6px">
              来源：{{ m.source }}
            </div>
            <div v-if="m.flow" style="margin-top:8px">
              <div class="muted" style="font-size:11px">
                已生成草稿：{{ m.flow.name }}（{{ m.flow.nodes?.length }} 个节点）
              </div>
              <el-button size="small" type="primary" plain style="margin-top:6px"
                         :loading="savingDraft === m.flow.id" :disabled="m.saved"
                         @click="saveDraft(m)">
                {{ m.saved ? '已保存到流程库' : '保存到流程库并编辑' }}
              </el-button>
            </div>
          </div>
        </div>
        <div v-if="loading" class="muted">
          AI 正在思考… 已等待 {{ elapsed }} 秒
          <span v-if="elapsed > 8">（大模型生成较慢，内容会逐步显示）</span>
        </div>
      </div>
      <div style="border-top:1px solid var(--border);padding-top:10px">
        <div style="margin-bottom:8px">
          <el-button v-for="q in quickPrompts" :key="q" size="small" :disabled="loading" @click="send(q)">{{ q }}</el-button>
        </div>
        <el-input v-model="input" placeholder="输入你的问题，回车发送" @keyup.enter="send()">
          <template #append><el-button :loading="loading" @click="send()">发送</el-button></template>
        </el-input>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stream-cursor { animation: blink 1s steps(2, start) infinite; }
@keyframes blink { to { visibility: hidden; } }
</style>
