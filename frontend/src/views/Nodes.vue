<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const tab = ref('builtin')
const market = ref<any[]>([])
const packages = ref<any[]>([])
const manifestText = ref('')
const installing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function pickFile() {
  fileInput.value?.click()
}

const runtimeLabel: Record<string, string> = {
  http: 'HTTP 调用',
  script: '脚本（Node.js / Python）',
  redis: 'Redis 写入',
  wecom: '企业微信机器人',
  influxdb: 'InfluxDB 写入'
}

async function load() {
  const [defs, marketData, installed] = await Promise.all([
    store.loadNodeDefinitions(), api.nodeMarket(), api.nodePackages()
  ])
  void defs
  market.value = marketData || []
  packages.value = installed || []
}

async function install(packageId: string) {
  await api.installNode({ packageId })
  ElMessage.success('节点包已安装，可直接在流程编排中使用')
  await load()
}

async function uninstall(pkg: any) {
  await ElMessageBox.confirm(`确认卸载「${pkg.packageId}」？已使用该节点的流程将无法发布。`, '提示', { type: 'warning' })
  await api.uninstallNode(pkg.packageId)
  ElMessage.success('已卸载')
  await load()
}

async function installLocal() {
  if (!manifestText.value.trim()) {
    ElMessage.warning('请粘贴节点包 JSON')
    return
  }
  let manifest: any
  try {
    manifest = JSON.parse(manifestText.value)
  } catch (e) {
    ElMessage.error('JSON 格式不正确')
    return
  }
  installing.value = true
  try {
    await api.installNode({ manifest })
    ElMessage.success('本地节点包已安装')
    manifestText.value = ''
    await load()
  } finally {
    installing.value = false
  }
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  manifestText.value = await file.text()
  input.value = ''
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">节点管理</h1>
        <div class="page-desc">安装扩展节点包。每个包必须声明可执行运行时，安装后即可在流程编排中真实运行</div>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="tab">
        <el-tab-pane label="内置节点" name="builtin">
          <el-table :data="store.nodeDefinitions" size="small">
            <el-table-column prop="type" label="类型" width="110" />
            <el-table-column prop="subtype" label="标识" width="150" />
            <el-table-column prop="title" label="名称" min-width="180" />
            <el-table-column label="来源" width="140">
              <template #default="{ row }">{{ row.builtIn ? '内置' : '扩展包' }}</template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="100" />
            <el-table-column prop="author" label="作者" min-width="160" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="节点市场" name="market">
          <div class="muted" style="margin-bottom:12px">
            共 {{ market.length }} 个可扩展节点。安装后节点纳入节点库，并由运行时执行器处理，不是仅展示表单。
          </div>
          <div v-for="item in market" :key="item.key" class="pkg-card">
            <div>
              <div class="pkg-title">{{ item.title }} <span class="pkg-tag">{{ runtimeLabel[item.runtime?.kind] || item.runtime?.kind }}</span></div>
              <div class="muted">{{ item.desc }}</div>
              <div class="muted pkg-meta">{{ item.author }} · v{{ item.version }} · {{ item.type }}:{{ item.subtype }}</div>
            </div>
            <el-button type="primary" size="small" @click="install(item.key)">安装</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="已安装" name="installed">
          <el-table :data="packages" size="small">
            <el-table-column prop="packageId" label="包 ID" min-width="160" />
            <el-table-column prop="version" label="版本" width="100" />
            <el-table-column label="运行时" width="180">
              <template #default="{ row }">{{ runtimeLabel[row.runtime?.kind] || row.runtime?.kind || '—' }}</template>
            </el-table-column>
            <el-table-column prop="nodeCount" label="节点数" width="90" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="installedAt" label="安装时间" min-width="180" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="uninstall(row)">卸载</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!packages.length" description="还没有安装扩展节点" />
        </el-tab-pane>

        <el-tab-pane label="导入本地包" name="local">
          <div class="muted" style="margin-bottom:12px">
            节点包 JSON 需包含 type、subtype、schema.fields 与 runtime.kind（http / script / redis / wecom / influxdb）。
          </div>
          <el-button size="small" @click="pickFile">选择 JSON 文件</el-button>
          <input ref="fileInput" type="file" accept="application/json" style="display:none" @change="onFileChange" />
          <el-input v-model="manifestText" type="textarea" :rows="14" style="margin-top:12px"
                    placeholder='{"id":"pkg-demo","type":"output","subtype":"demo","title":"示例节点","schema":{"title":"示例","fields":[]},"runtime":{"kind":"http","method":"GET","url":"https://example.com"}}' />
          <div style="margin-top:12px">
            <el-button type="primary" :loading="installing" @click="installLocal">安装此节点包</el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.pkg-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 10px;
}
.pkg-title { font-weight: 600; margin-bottom: 4px; }
.pkg-tag {
  font-size: 11px;
  font-weight: 400;
  color: #2563eb;
  background: #eff6ff;
  border-radius: 4px;
  padding: 2px 6px;
  margin-left: 6px;
}
.pkg-meta { font-size: 12px; margin-top: 4px; }
</style>
