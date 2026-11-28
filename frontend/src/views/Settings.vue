<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

/** 设置页用 Tab 分区，后续新增设置项只需追加一个 el-tab-pane。 */
const activeTab = ref('ai')
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const presets = ref<any[]>([])
/** 各厂商各自保存的配置，切换厂商时从这里回显，避免共用同一份密钥。 */
const configs = ref<Record<string, any>>({})
const form = reactive<any>({ provider: 'local', endpoint: '', model: '', apiKey: '' })
const active = ref<any>({ active: false, activeProvider: 'local', activeSource: 'local' })
const apiKeyMask = ref('—')
const hasApiKey = ref(false)

const currentPreset = computed(() => presets.value.find(p => p.key === form.provider))
const isLocal = computed(() => form.provider === 'local')
const keyPlaceholder = computed(() => hasApiKey.value ? '已保存密钥，留空则不修改' : '请输入 API Key')
const activeLabel = computed(() => active.value.activeLabel
  || (active.value.active ? active.value.activeProvider : '本地规则引擎'))

async function load() {
  loading.value = true
  try {
    const data: any = await api.aiSettings()
    presets.value = data.presets || []
    configs.value = data.configs || {}
    active.value = data
    // 默认展示当前生效引擎自己的配置
    applyProvider(data.activeProvider || data.provider || 'local')
  } finally {
    loading.value = false
  }
}

/** 切换厂商时只读取该厂商保存的配置，绝不沿用其他厂商的地址、模型与密钥。 */
function applyProvider(key: string) {
  const preset = presets.value.find(p => p.key === key)
  const saved = configs.value[key]
  form.provider = key
  if (key === 'local') {
    form.endpoint = ''
    form.model = ''
  } else {
    form.endpoint = saved?.endpoint || preset?.endpoint || ''
    form.model = saved?.model || preset?.model || ''
  }
  form.apiKey = ''
  apiKeyMask.value = saved?.apiKeyMask || '—'
  hasApiKey.value = !!saved?.hasApiKey
}

function choosePreset(preset: any) {
  applyProvider(preset.key)
}

function useModel(name: string) {
  form.model = name
}

function validate(): boolean {
  if (isLocal.value) return true
  if (!form.endpoint?.trim()) {
    ElMessage.warning('请填写接口地址（Chat Completions 完整地址）')
    return false
  }
  if (!form.model?.trim()) {
    ElMessage.warning('请填写模型名，可从下方常用模型中选择')
    return false
  }
  if (!hasApiKey.value && !form.apiKey?.trim()) {
    ElMessage.warning('请填写当前厂商的 API Key')
    return false
  }
  return true
}

function payload() {
  return {
    provider: form.provider,
    endpoint: form.endpoint?.trim() || '',
    model: form.model?.trim() || '',
    apiKey: form.apiKey?.trim() || ''
  }
}

async function testConnection() {
  if (!validate()) return
  testing.value = true
  try {
    const result: any = await api.testAiSettings(payload())
    if (result.success) ElMessage.success(result.message)
    else ElMessage.error(result.message)
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!validate()) return
  saving.value = true
  try {
    await api.saveAiSettings(payload())
    ElMessage.success('设置已保存，立即生效')
    await load()
  } finally {
    saving.value = false
  }
}

async function reset() {
  await ElMessageBox.confirm('确认恢复默认？将清除所有厂商已保存的配置，改用本地规则引擎。', '提示', { type: 'warning' })
  await api.resetAiSettings()
  ElMessage.success('已恢复默认')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">设置</h1>
        <div class="page-desc">系统级配置集中管理；AI 模型页配置 AI 诊断、AI 助手与 AI 建流程所用的大模型</div>
      </div>
      <el-tag :type="active.active ? 'success' : 'info'" effect="plain" size="small" style="margin-left:14px; align-self:flex-start">
        当前生效：{{ activeLabel }}
      </el-tag>
    </div>

    <!-- 设置项按 Tab 分区，新增设置只需追加一个 el-tab-pane -->
    <el-tabs v-model="activeTab" class="settings-tabs">
      <el-tab-pane label="AI 模型" name="ai">
    <div v-loading="loading" class="card">
      <div class="section-label">选择引擎</div>
      <div class="preset-grid">
        <button v-for="p in presets" :key="p.key" type="button" class="preset-card"
                :class="{ selected: form.provider === p.key, active: active.activeProvider === p.key }"
                @click="choosePreset(p)">
          <span class="preset-name">{{ p.label }}</span>
          <span class="preset-key">{{ p.key }}</span>
          <span v-if="active.activeProvider === p.key" class="preset-badge">生效中</span>
        </button>
      </div>

      <el-alert v-if="active.active" type="success" :closable="false" show-icon style="margin:16px 0"
                :title="`当前由「${activeLabel}」提供服务，配置来源：本页设置`" />
      <el-alert v-else type="info" :closable="false" show-icon style="margin:16px 0"
                title="当前使用本地规则引擎，离线可用、无需 API Key。选择厂商并填写连接信息即可切换到大模型。" />

      <template v-if="!isLocal">
        <el-divider content-position="left">连接信息 · {{ currentPreset?.label }}</el-divider>
        <el-form label-position="top">
          <el-form-item label="接口地址（Chat Completions 完整地址）">
            <el-input v-model="form.endpoint" placeholder="https://api.deepseek.com/v1/chat/completions" />
            <div class="field-hint">{{ currentPreset?.hint || '以各厂商官方文档为准，必须是完整的 /chat/completions 地址' }}</div>
          </el-form-item>
          <el-form-item label="模型名">
            <el-input v-model="form.model" :placeholder="currentPreset?.model || '例如 deepseek-chat'" />
            <div v-if="currentPreset?.models?.length" class="model-picker">
              <span class="model-picker-label">常用模型</span>
              <el-tag v-for="m in currentPreset.models" :key="m" size="small" class="model-tag"
                      :effect="form.model === m ? 'dark' : 'plain'" @click="useModel(m)">{{ m }}</el-tag>
              <el-link v-if="currentPreset.docsUrl" :href="currentPreset.docsUrl" target="_blank"
                       rel="noopener noreferrer" type="primary" class="docs-link">查看更多模型 →</el-link>
              <div v-else class="field-hint">模型名以服务商文档为准，可手动填写。</div>
            </div>
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="form.apiKey" type="password" show-password :placeholder="keyPlaceholder" autocomplete="off" />
            <div class="field-hint">当前厂商密钥：{{ apiKeyMask }}。留空或提交掩码占位符表示不修改本厂商密钥。</div>
          </el-form-item>
        </el-form>
      </template>

      <div class="actions">
        <el-button :loading="testing" :disabled="isLocal" @click="testConnection">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并启用</el-button>
        <el-button @click="reset">恢复默认</el-button>
      </div>
    </div>

    <div class="card tips">
      <div class="section-label">说明</div>
      <ul>
        <li>各厂商的接口地址、模型名与 API Key 独立保存，切换厂商不会串用密钥。</li>
        <li>模型更新较快，页面只列出常用模型；点击「查看更多模型」可前往厂商官方文档查看最新编码。</li>
        <li>四家国内主流厂商均为 OpenAI 兼容接口；自定义厂商可填写任意兼容 Chat Completions 的地址。</li>
        <li>API Key 使用平台主密钥加密存储，接口与页面只回显掩码，明文不会下发到浏览器。</li>
        <li>设置保存后立即生效；大模型调用失败时自动回退本地规则引擎，不影响流程运行。</li>
      </ul>
    </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.settings-tabs { margin-top: 4px; }
.section-label { font-size: 13px; font-weight: 600; color: var(--text); margin-bottom: 10px; }
.preset-grid { display: flex; flex-wrap: wrap; gap: 10px; }
.preset-card {
  position: relative;
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  min-width: 130px; padding: 10px 14px; border: 1px solid var(--border);
  border-radius: 8px; background: #fff; color: var(--text); cursor: pointer;
  transition: border-color .15s, background .15s; text-align: left;
}
.preset-card:hover { border-color: var(--primary); }
.preset-card.selected { border-color: var(--primary); background: var(--primary-soft, rgba(64,158,255,.08)); }
.preset-name { font-size: 14px; font-weight: 600; }
.preset-key { font-size: 11px; color: var(--muted); }
.preset-badge { position: absolute; top: -7px; right: 6px; font-size: 10px; color: #fff; background: var(--primary); border-radius: 8px; padding: 1px 6px; }
.field-hint { font-size: 12px; color: var(--muted); line-height: 1.5; margin-top: 4px; }
.model-picker { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.model-picker-label { font-size: 12px; color: var(--muted); margin-right: 2px; }
.model-tag { cursor: pointer; user-select: none; }
.docs-link { margin-left: 4px; font-size: 12px; }
.actions { display: flex; gap: 10px; margin-top: 18px; }
.tips ul { margin: 0; padding-left: 18px; color: var(--muted); font-size: 13px; line-height: 1.9; }
</style>
