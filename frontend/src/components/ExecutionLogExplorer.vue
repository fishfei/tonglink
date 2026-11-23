<script setup lang="ts">
/**
 * 执行日志浏览器（流程编排页与「执行日志」菜单共用同一套界面）。
 * - 传入 fixedFlowId 时锁定到指定流程，用于编排页（只看当前流程）；
 * - 不传时展示流程下拉，可查询所有流程，用于独立日志菜单；
 * - 左侧服务端分页记录列表 + 右侧只读流程图/节点输入输出/运行日志/标签/AI 诊断。
 */
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VueFlow, MarkerType } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import FlowNode from './FlowNode.vue'
import FlowEdge from './FlowEdge.vue'
import { api } from '../api'
import { useAppStore } from '../stores'

const props = defineProps<{ fixedFlowId?: string }>()
const store = useAppStore()

const flows = ref<any[]>([])
const flowFilter = ref(props.fixedFlowId || '')
const status = ref('')
const tag = ref('')
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref<any[]>([])
const selected = ref<any>(null)
const nodes = ref<any[]>([])
const lines = ref<any[]>([])
const diagramNodes = ref<any[]>([])
const diagramEdges = ref<any[]>([])
const tagDraft = ref<string[]>([])
const loading = ref(false)

// AI 诊断
const diagnosis = ref<any>(null)
const diagVisible = ref(false)
const diagLoading = ref(false)

const nodeTypes = { dflow: FlowNode }
const edgeTypes = { deletable: FlowEdge }
// 与编排页一致：节点统一中性外观，图标按 subtype 自动选择
const NODE_THEME: Record<string, any> = {
  trigger: { color: '#475569', soft: '#f1f5f9' },
  source: { color: '#475569', soft: '#f1f5f9' },
  code: { color: '#475569', soft: '#f1f5f9' },
  logic: { color: '#475569', soft: '#f1f5f9' },
  output: { color: '#475569', soft: '#f1f5f9' }
}

function statusTag(s: string) {
  return s === 'SUCCESS' ? 'success' : (s === 'FAILED' ? 'danger' : (s === 'RUNNING' ? 'warning' : 'info'))
}

// n8n 风格彩色标签：同名标签颜色稳定，列表与详情保持一致
const TAG_PALETTE = [
  { bg: '#f5f3ff', fg: '#6d28d9', bd: '#ddd6fe' },
  { bg: '#eff6ff', fg: '#1d4ed8', bd: '#bfdbfe' },
  { bg: '#ecfdf5', fg: '#047857', bd: '#a7f3d0' },
  { bg: '#fff7ed', fg: '#c2410c', bd: '#fed7aa' },
  { bg: '#fdf2f8', fg: '#be185d', bd: '#fbcfe8' },
  { bg: '#f8fafc', fg: '#475569', bd: '#cbd5e1' }
]
function tagStyle(name: string) {
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) | 0
  const c = TAG_PALETTE[Math.abs(hash) % TAG_PALETTE.length]
  return { background: c.bg, color: c.fg, borderColor: c.bd }
}

function prettyJson(raw: any) {
  if (!raw) return '—'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return String(raw)
  }
}

function toVueNode(n: any) {
  const theme = NODE_THEME[n.type] || { color: '#475569', soft: '#f1f5f9' }
  return {
    id: n.id,
    type: 'dflow',
    position: { x: n.position?.x || 0, y: n.position?.y || 0 },
    data: {
      title: n.title || n.subtype,
      subtype: n.subtype,
      type: n.type,
      nodeVersion: n.nodeVersion || '1',
      config: n.config || {},
      accent: theme.color,
      soft: theme.soft
    }
  }
}

function makeEdge(id: string, source: string, target: string, sourceHandle = 'out', targetHandle = 'in') {
  return {
    id, source, target, sourceHandle, targetHandle,
    type: 'deletable',
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#94a3b8', strokeWidth: 1.8 }
  }
}

function runStatusMap(rows: any[]) {
  const map = new Map<string, string>()
  rows.forEach(n => map.set(n.nodeId, n.status))
  return map
}

function decoratedNodes(rows: any[], statuses: Map<string, string>) {
  return rows.map(n => ({ ...n, data: { ...n.data, runStatus: statuses.get(n.id) } }))
}

function decoratedEdges(rows: any[], statuses: Map<string, string>) {
  return rows.map(e => {
    const from = statuses.get(e.source)
    const to = statuses.get(e.target)
    const success = from === 'SUCCESS' && (to === undefined || to === 'SUCCESS')
    return {
      ...e,
      data: { ...(e.data || {}), deletable: false },
      style: success ? { stroke: '#16a34a', strokeWidth: 2.2 } : { stroke: '#94a3b8', strokeWidth: 1.8 },
      markerEnd: success ? { type: MarkerType.ArrowClosed, color: '#16a34a' } : MarkerType.ArrowClosed
    }
  })
}

function allTags() {
  const set = new Set<string>()
  list.value.forEach(row => (row.tags || []).forEach((t: string) => set.add(t)))
  return [...set].sort()
}

async function load() {
  loading.value = true
  try {
    if (!props.fixedFlowId) flows.value = await api.flows()
    const params: any = {
      page: page.value, size: pageSize.value, status: status.value, tag: tag.value
    }
    const fixed = props.fixedFlowId || flowFilter.value
    if (fixed) params.flowId = fixed
    const result: any = await api.executionPage(params)
    if (page.value > (result.totalPages || 1)) {
      page.value = result.totalPages || 1
      return load()
    }
    list.value = result.items || []
    total.value = result.total || 0
    const still = list.value.find(e => e.id === selected.value?.id)
    if (still) await select(still)
    else if (list.value.length) await select(list.value[0])
    else clearSelection()
  } finally {
    loading.value = false
  }
}

function clearSelection() {
  selected.value = null
  nodes.value = []
  lines.value = []
  diagramNodes.value = []
  diagramEdges.value = []
  tagDraft.value = []
  diagnosis.value = null
}

function reloadFromFirst() {
  page.value = 1
  load()
}

async function select(row: any) {
  selected.value = row
  tagDraft.value = [...(row.tags || [])]
  diagnosis.value = null
  const [nodeRows, logRows]: any[] = await Promise.all([
    api.executionNodes(row.id), api.executionLogs(row.id)
  ])
  nodes.value = nodeRows
  lines.value = logRows
  const statuses = runStatusMap(nodeRows)
  try {
    const flow: any = await api.flow(row.flowId)
    const raw = (row.flowVersion > 0 && flow.publishedGraphJson) ? flow.publishedGraphJson : flow.graphJson
    const graph = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
    diagramNodes.value = decoratedNodes((graph.nodes || []).map(toVueNode), statuses)
    diagramEdges.value = decoratedEdges((graph.connections || []).map((e: any) =>
      makeEdge(e.id, e.source, e.target, e.sourceHandle || 'out', e.targetHandle || 'in')), statuses)
  } catch {
    diagramNodes.value = []
    diagramEdges.value = []
  }
}

async function saveTags() {
  if (!selected.value) return
  const id = selected.value.id
  const tags: string[] = await api.setExecutionTags(id, tagDraft.value)
  // 以服务端结果为准重新拉当前页：确保左侧列表与右侧详情都立刻带上标签，
  // 避免只改了内存里的选中对象、翻页或被定时刷新覆盖后又"消失"。
  await load()
  const row = list.value.find(e => e.id === id)
  if (row) {
    row.tags = tags
    tagDraft.value = [...tags]
    if (selected.value?.id !== id) await select(row)
  }
  ElMessage.success('标签已保存')
}

async function diagnose() {
  if (!selected.value) return
  diagLoading.value = true
  try {
    diagnosis.value = await api.aiDiagnose(selected.value.id)
    diagVisible.value = true
  } catch (e: any) {
    ElMessage.error('诊断失败：' + (e.message || '未知错误'))
  } finally {
    diagLoading.value = false
  }
}

watch(() => props.fixedFlowId, (id) => {
  flowFilter.value = id || ''
  clearSelection()
  reloadFromFirst()
})

onMounted(() => {
  store.loadNodeDefinitions()
  load()
})

defineExpose({ refresh: load })
</script>

<template>
  <div class="log-explorer log-explorer-host">
    <div class="log-filter-bar">
      <el-select v-if="!fixedFlowId" v-model="flowFilter" placeholder="全部流程" clearable filterable
                 size="small" style="width:210px" @change="reloadFromFirst">
        <el-option v-for="f in flows" :key="f.id" :label="f.name" :value="f.id" />
      </el-select>
      <span v-else class="log-scope-label">当前流程：{{ store.projects.length ? (flows.find(f => f.id === fixedFlowId)?.name || fixedFlowId) : fixedFlowId }}</span>
      <el-select v-model="status" placeholder="全部状态" clearable size="small" style="width:130px" @change="reloadFromFirst">
        <el-option label="成功" value="SUCCESS" />
        <el-option label="失败" value="FAILED" />
        <el-option label="运行中" value="RUNNING" />
      </el-select>
      <el-select v-model="tag" placeholder="按标签过滤" clearable filterable size="small" style="width:150px" @change="reloadFromFirst">
        <el-option v-for="t in allTags()" :key="t" :label="'#' + t" :value="t" />
      </el-select>
      <el-button size="small" @click="reloadFromFirst">刷新</el-button>
      <span class="muted">共 {{ total }} 条{{ fixedFlowId ? ' · 仅当前流程' : ' · 全部流程' }}</span>
    </div>
    <div class="log-explorer-body">
      <div class="log-list-pane">
        <div class="pane-title">执行记录</div>
        <div v-for="row in list" :key="row.id" class="log-row"
             :class="{ active: selected?.id === row.id }" @click="select(row)">
          <div class="log-row-top">
            <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
            <span class="log-row-id">{{ row.id }}</span>
          </div>
          <div class="muted log-row-meta">{{ row.startTime }} · {{ row.records }} 条 · {{ row.triggerType }}</div>
          <div v-if="row.tags?.length" class="log-row-tags">
            <span v-for="t in row.tags" :key="t" class="log-row-tag" :style="tagStyle(t)">#{{ t }}</span>
          </div>
        </div>
        <el-empty v-if="!list.length" description="暂无执行记录" :image-size="50" />
        <div class="pagination-bar">
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize"
                         :total="total" :page-sizes="[10, 20, 50, 100]"
                         layout="total, sizes, prev, pager, next, jumper"
                         background small @current-change="load" @size-change="reloadFromFirst" />
        </div>
      </div>
      <div class="log-detail-pane">
        <template v-if="selected">
          <div class="log-detail-head">
            <div>
              <div class="pane-title" style="margin:0">流程图 · 节点状态（成功节点打勾、成功链路绿色）</div>
              <div v-if="selected.tags?.length" class="log-row-tags">
                <span v-for="t in selected.tags" :key="t" class="log-row-tag" :style="tagStyle(t)">#{{ t }}</span>
              </div>
            </div>
            <el-button size="small" type="primary" plain :loading="diagLoading" @click="diagnose">AI 诊断</el-button>
          </div>
          <div class="log-diagram">
            <VueFlow v-if="diagramNodes.length" id="log-flow" v-model:nodes="diagramNodes" v-model:edges="diagramEdges"
                     :node-types="nodeTypes" :edge-types="edgeTypes"
                     :nodes-draggable="false" :nodes-connectable="false" :elements-selectable="false"
                     :pan-on-drag="true" fit-view-on-init :min-zoom="0.2" :max-zoom="1.5">
              <Background :gap="16" pattern-color="#e2e8f0" />
            </VueFlow>
            <div v-else class="muted" style="padding:20px">该执行没有可展示的流程图</div>
          </div>
          <div class="pane-title log-tag-row">
            <span>标签</span>
            <el-select v-model="tagDraft" multiple filterable allow-create default-first-option
                       :reserve-keyword="false" placeholder="输入后回车新增" size="small" style="flex:1">
              <el-option v-for="t in allTags()" :key="t" :label="t" :value="t" />
            </el-select>
            <el-button size="small" type="primary" @click="saveTags">保存标签</el-button>
          </div>
          <div class="pane-title">节点输入输出</div>
          <div class="log-node-list">
            <div v-for="n in nodes" :key="n.id" class="io-node">
              <div class="io-node-head">
                <strong>{{ n.nodeTitle || n.nodeId }}</strong>
                <el-tag size="small" :type="statusTag(n.status)">{{ n.status }}</el-tag>
                <span class="muted">{{ n.durationMs }} ms</span>
              </div>
              <div class="io-pair">
                <details><summary>输入 INPUT</summary><pre>{{ prettyJson(n.inputJson) }}</pre></details>
                <details><summary>输出 OUTPUT</summary><pre>{{ prettyJson(n.outputJson) }}</pre></details>
              </div>
              <div v-if="n.errorMessage" class="io-error">{{ n.errorMessage }}</div>
            </div>
          </div>
          <div class="pane-title">运行日志</div>
          <div class="inline-log-console">
            <div v-for="line in lines" :key="line.id" :class="{ 'log-error': line.level === 'ERROR' }">
              [{{ line.ts }}] [{{ line.level }}] {{ line.message }}
            </div>
          </div>
        </template>
        <el-empty v-else description="请选择一条执行记录" :image-size="60" />
      </div>
    </div>

    <el-dialog v-model="diagVisible" title="AI 失败诊断" width="600px">
      <template v-if="diagnosis">
        <el-alert :title="diagnosis.rootCause" :type="diagnosis.severity === 'error' ? 'error' : 'warning'" :closable="false" />
        <div class="card-title" style="margin-top:14px">证据</div>
        <div v-for="(e, i) in diagnosis.evidence" :key="i" class="muted" style="font-size:12px">· {{ e }}</div>
        <div class="card-title" style="margin-top:14px">修复步骤</div>
        <div v-for="(s, i) in diagnosis.steps" :key="i" style="font-size:13px;margin-bottom:6px">{{ i + 1 }}. {{ s }}</div>
      </template>
    </el-dialog>
  </div>
</template>
