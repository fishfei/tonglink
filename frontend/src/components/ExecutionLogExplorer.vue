<script setup lang="ts">
/**
 * 执行日志浏览器（流程编排页与「执行日志」菜单共用同一套界面）。
 * - 传入 fixedFlowId 时锁定到指定流程，用于编排页（只看当前流程）；
 * - 不传时展示流程下拉，可查询所有流程，用于独立日志菜单；
 * - 左侧服务端分页记录列表 + 右侧只读流程图/节点输入输出/运行日志/AI 诊断。
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
const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const list = ref<any[]>([])
const selected = ref<any>(null)
const nodes = ref<any[]>([])
const lines = ref<any[]>([])
const diagramNodes = ref<any[]>([])
const diagramEdges = ref<any[]>([])
const loading = ref(false)
// 漏斗式过滤：漏斗按钮 + 弹出过滤面板
const filterVisible = ref(false)
const timeRange = ref<any[]>([])
const selectedFlowName = ref('')

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

function formatDateTime(value: any) {
  if (!value) return ''
  if (typeof value === 'string') return value
  const d = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
         `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function flowNameOf(id: string) {
  return flows.value.find(f => f.id === id)?.name || id
}

function activeFilterCount() {
  let n = 0
  if (!props.fixedFlowId && flowFilter.value) n++
  if (status.value) n++
  if (timeRange.value?.length === 2 && timeRange.value[0] && timeRange.value[1]) n++
  return n
}

async function applyFilters() {
  // 手动弹窗：点「应用」立即回到第一页并刷新，不需要再点刷新按钮
  filterVisible.value = false
  await reloadFromFirst()
}

function resetFilters() {
  flowFilter.value = props.fixedFlowId || ''
  status.value = ''
  timeRange.value = []
  void applyFilters()
}

async function load() {
  loading.value = true
  try {
    flows.value = await api.flows()
    const params: any = {
      page: page.value, size: pageSize.value, status: status.value
    }
    const fixed = props.fixedFlowId || flowFilter.value
    if (fixed) params.flowId = fixed
    if (timeRange.value?.length === 2 && timeRange.value[0] && timeRange.value[1]) {
      params.timeFrom = formatDateTime(timeRange.value[0])
      params.timeTo = formatDateTime(timeRange.value[1])
    }
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
  selectedFlowName.value = ''
  diagnosis.value = null
}

function reloadFromFirst() {
  page.value = 1
  return load()
}

async function select(row: any) {
  selected.value = row
  diagnosis.value = null
  const [nodeRows, logRows]: any[] = await Promise.all([
    api.executionNodes(row.id), api.executionLogs(row.id)
  ])
  nodes.value = nodeRows
  lines.value = logRows
  const statuses = runStatusMap(nodeRows)
  try {
    const flow: any = await api.flow(row.flowId)
    selectedFlowName.value = flow?.name || row.flowId
  } catch {
    selectedFlowName.value = row.flowId
  }
  try {
    // 只用本次执行冻结的图：新执行是入队时的快照，老执行是对应发布版本的图。
    // 绝不回退到当前草稿/发布图，否则历史流程图会随编排变化。
    const raw = row.graphJson || ''
    const graph = typeof raw === 'string' ? JSON.parse(raw || '{}') : (raw || {})
    diagramNodes.value = decoratedNodes((graph.nodes || []).map(toVueNode), statuses)
    diagramEdges.value = decoratedEdges((graph.connections || []).map((e: any) =>
      makeEdge(e.id, e.source, e.target, e.sourceHandle || 'out', e.targetHandle || 'in')), statuses)
  } catch {
    diagramNodes.value = []
    diagramEdges.value = []
  }
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
      <span v-if="fixedFlowId" class="log-scope-label">当前流程：{{ flowNameOf(fixedFlowId) }}</span>
      <div class="filter-anchor">
        <button type="button" class="log-filter-btn" @click="filterVisible = !filterVisible">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z"/></svg>
          过滤<span v-if="activeFilterCount()" class="filter-count">{{ activeFilterCount() }}</span>
        </button>
        <!-- 手动面板：不使用 popover 的 clickoutside，选择条件时不会误关闭 -->
        <div v-if="filterVisible" class="filter-panel" @click.stop>
          <div class="filter-panel-head">
            <span>过滤条件</span>
            <button type="button" class="filter-close" title="关闭" @click="filterVisible = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
            </button>
          </div>
          <div class="filter-field">
            <label>流程名称</label>
            <el-select v-if="!fixedFlowId" v-model="flowFilter" placeholder="全部流程" clearable filterable size="small"
                       style="width:100%">
              <el-option v-for="f in flows" :key="f.id" :label="f.name" :value="f.id" />
            </el-select>
            <div v-else class="filter-fixed">{{ flowNameOf(fixedFlowId) }}</div>
          </div>
          <div class="filter-field">
            <label>状态</label>
            <el-select v-model="status" placeholder="全部状态" clearable size="small" style="width:100%">
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
              <el-option label="运行中" value="RUNNING" />
            </el-select>
          </div>
          <div class="filter-field">
            <label>执行时间</label>
            <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
                            start-placeholder="开始时间" end-placeholder="结束时间"
                            size="small" style="width:100%" format="YYYY-MM-DD HH:mm:ss"
                            value-format="YYYY-MM-DD HH:mm:ss" />
          </div>
          <div class="filter-panel-foot">
            <el-button size="small" @click="resetFilters">重置</el-button>
            <el-button size="small" type="primary" @click="applyFilters">应用</el-button>
          </div>
        </div>
      </div>
      <span class="muted">共 {{ total }} 条{{ fixedFlowId ? ' · 仅当前流程' : ' · 全部流程' }}</span>
    </div>
    <div class="log-explorer-body">
      <div class="log-list-pane">
        <div class="pane-title">执行记录</div>
        <div class="log-rows">
          <div v-for="row in list" :key="row.id" class="log-row"
               :class="{ active: selected?.id === row.id }" @click="select(row)">
            <div class="log-row-top">
              <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
            </div>
            <div class="muted log-row-meta">{{ row.startTime }} · {{ row.records }} 条 · {{ row.triggerType }}</div>
          </div>
          <el-empty v-if="!list.length" description="暂无执行记录" :image-size="50" />
        </div>
        <div class="pagination-bar">
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize"
                         :total="total" :page-sizes="[10, 20, 50, 100]"
                         layout="total, sizes, prev, next, jumper"
                         background small @current-change="load" @size-change="reloadFromFirst" />
        </div>
      </div>
      <div class="log-detail-pane">
        <template v-if="selected">
          <div class="log-detail-head">
            <div>
              <div class="pane-title" style="margin:0">{{ selectedFlowName || selected.flowId }}</div>
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
