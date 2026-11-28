<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const presets = ref<any[]>([])
const form = reactive<any>({ provider: 'local', endpoint: '', apiKey: '', model: '' })
const active = ref<any>({ active: false, activeProvider: 'local', activeSource: 'local' })
const apiKeyMask = ref('—')
const hasApiKey = ref(false)

const currentPreset = computed(() => presets.value.find(p => p.key === form.provider))
const isLocal = computed(() => form.provider === 'local')
const keyPlaceholder = computed(() => hasApiKey.value ? '已保存密钥，留空则不修改' : '请输入 API Key')
const activeLabel = computed(() => {
  if (!active.value.active) return '本地规则引擎'
  const p = presets.value.find(x => x.key === active.value.activeProvider)
  return p ? p.label : active.value.activeProvider
})

async function load() {
  loading.value = true
  try {
    const data: any = await api.aiSettings()
    presets.value = data.presets || []
    form.provider = data.provider || 'local'
    form.endpoint = data.endpoint || ''
    form.model = data.model || ''
    form.apiKey = ''
    apiKeyMask.value = data.apiKeyMask || '—'
    hasApiKey.value = !!data.hasApiKey
    active.value = data
  } finally {
    loading.value = false
  }
}

function choosePreset(preset: any) {
  form.provider = preset.key
  if (preset.key === 'local') {
    form.endpoint = ''
    form.model = ''
  } else {
    form.endpoint = preset.endpoint || ''
    form.model = preset.model || ''
  }
}

function validate(): boolean {
  if (isLocal.value) return true
  if (!form.endpoint?.trim()) {
    ElMessage.warning('请填写接口地址（Chat Completions 完整地址）')
    return false
  }
  if (!form.model?.trim()) {
    ElMessage.warning('请填写模型名')
    return false
  }
  if (!hasApiKey.value && !form.apiKey?.trim()) {
    ElMessage.warning('请填写 API Key')
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
    ElMessage.success('AI 设置已保存，立即生效')
    await load()
  } finally {
    saving.value = false
  }
}

async function reset() {
  await ElMessageBox.confirm('确认恢复默认？将清除已保存的大模型配置，改用环境变量或本地规则引擎。', '提示', { type: 'warning' })
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
        <h1 class="page-title">AI 设置</h1>
        <div class="page-desc">配置 AI 诊断与 AI 建工作流所用的大模型；未配置时使用离线规则引擎</div>
      </div>
      <el-tag :type="active.active ? 'success' : 'info'" effect="plain" size="small" style="margin-left:14px; align-self:flex-start">
        当前生效：{{ activeLabel }}
      </el-tag>
    </div>

    <div v-loading="loading" class="card">
      <div class="section-label">选择引擎</div>
      <div class="preset-grid">
        <button v-for="p in presets" :key="p.key" type="button" class="preset-card"
                :class="{ selected: form.provider === p.key }" @click="choosePreset(p)">
          <span class="preset-name">{{ p.label }}</span>
          <span class="preset-key">{{ p.key }}</span>
        </button>
      </div>

      <el-alert v-if="active.active" type="success" :closable="false" show-icon style="margin:16px 0"
                :title="`当前由「${activeLabel}」提供服务，来源：${active.activeSource === 'settings' ? '本页设置' : '环境变量'}`" />
      <el-alert v-else type="info" :closable="false" show-icon style="margin:16px 0"
                title="当前使用本地规则引擎，离线可用、无需 API Key。选择下方厂商并填写连接信息即可切换到大模型。" />

      <template v-if="!isLocal">
        <el-divider content-position="left">连接信息</el-divider>
        <el-form label-position="top">
          <el-form-item label="接口地址（Chat Completions 完整地址）">
            <el-input v-model="form.endpoint" placeholder="https://api.deepseek.com/v1/chat/completions" />
            <div class="field-hint">{{ currentPreset?.hint || '以各厂商官方文档为准，必须是完整的 /chat/completions 地址' }}</div>
          </el-form-item>
          <el-form-item label="模型名">
            <el-input v-model="form.model" :placeholder="currentPreset?.model || '例如 deepseek-chat'" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="form.apiKey" type="password" show-password :placeholder="keyPlaceholder" autocomplete="off" />
            <div class="field-hint">当前密钥：{{ apiKeyMask }}。留空或提交掩码占位符表示不修改。</div>
          </el-form-item>
        </el-form>
      </template>

      <div class="actions">
        <el-button :loading="testing" :disabled="isLocal" @click="testConnection">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="reset">恢复默认</el-button>
      </div>
    </div>

    <div class="card tips">
      <div class="section-label">说明</div>
      <ul>
        <li>四家国内主流厂商均为 OpenAI 兼容接口，差异仅在地址、模型名与 Key，本页已内置预设。</li>
        <li>API Key 使用平台主密钥加密存储，接口与页面只回显掩码，明文不会下发到浏览器。</li>
        <li>设置保存后立即生效，无需重启；大模型调用失败时自动回退本地规则引擎，不影响流程运行。</li>
        <li>切换为「本地规则引擎」或恢复默认，即可回到完全离线的运行状态。</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.section-label { font-size: 13px; font-weight: 600; color: var(--text); margin-bottom: 10px; }
.preset-grid { display: flex; flex-wrap: wrap; gap: 10px; }
.preset-card {
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  min-width: 130px; padding: 10px 14px; border: 1px solid var(--border);
  border-radius: 8px; background: #fff; color: var(--text); cursor: pointer;
  transition: border-color .15s, background .15s; text-align: left;
}
.preset-card:hover { border-color: var(--primary); }
.preset-card.selected { border-color: var(--primary); background: var(--primary-soft, rgba(64,158,255,.08)); }
.preset-name { font-size: 14px; font-weight: 600; }
.preset-key { font-size: 11px; color: var(--muted); }
.field-hint { font-size: 12px; color: var(--muted); line-height: 1.5; margin-top: 4px; }
.actions { display: flex; gap: 10px; margin-top: 18px; }
.tips ul { margin: 0; padding-left: 18px; color: var(--muted); font-size: 13px; line-height: 1.9; }
</style>
