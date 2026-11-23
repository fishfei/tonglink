<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const router = useRouter()
const messages = ref<any[]>([
  { role: 'assistant', content: '你好，我是 TongLink Studio AI 助手。我可以帮你生成流程草稿、诊断执行失败、分析数据异常。' }
])
const input = ref('')
const loading = ref(false)
const savingDraft = ref('')

/** AI 生成的图只是内存对象，必须显式建库才能在流程编排页编辑与自动保存。 */
async function saveDraft(message: any) {
  if (!message.flow?.id || message.saved) return
  savingDraft.value = message.flow.id
  try {
    const created: any = await api.createFlow({
      id: message.flow.id,
      name: message.flow.name,
      projectId: message.flow.projectId || store.projectId,
      graph: message.flow
    })
    message.saved = true
    ElMessage.success('草稿已保存到流程库')
    router.push('/flows')
    void created
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

async function send(text?: string) {
  const question = (text || input.value).trim()
  if (!question) return
  input.value = ''
  messages.value.push({ role: 'user', content: question })
  loading.value = true
  try {
    if (question.includes('生成') || question.includes('流程')) {
      const result: any = await api.aiGenerate(question, store.projectId)
      messages.value.push({ role: 'assistant', content: result.explanation, flow: result.flow })
    } else {
      const result: any = await api.aiAsk(question)
      messages.value.push({ role: 'assistant', content: result.answer })
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">AI 助手</h1>
        <div class="page-desc">自然语言生成流程草稿、失败诊断与数据异常问答；默认离线规则引擎，可接入 OpenAI 兼容模型</div>
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
            {{ m.content }}
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
        <div v-if="loading" class="muted">AI 正在思考…</div>
      </div>
      <div style="border-top:1px solid var(--border);padding-top:10px">
        <div style="margin-bottom:8px">
          <el-button v-for="q in quickPrompts" :key="q" size="small" @click="send(q)">{{ q }}</el-button>
        </div>
        <el-input v-model="input" placeholder="输入你的问题，回车发送" @keyup.enter="send()">
          <template #append><el-button :loading="loading" @click="send()">发送</el-button></template>
        </el-input>
      </div>
    </div>
  </div>
</template>
