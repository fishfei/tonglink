<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VueFlow, MarkerType, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import FlowNode from '../components/FlowNode.vue'
import FlowEdge from '../components/FlowEdge.vue'
import ExecutionLogExplorer from '../components/ExecutionLogExplorer.vue'
import { api, notifyError } from '../api'
import request from '../api'
import { useAppStore } from '../stores'
import { filterDatasources } from '../utils/graph'

/**
 * 流程编排页：项目过滤 + DAG 画布 + Schema 驱动属性面板 + 节点安装。
 * 交互约定：
 * - 单击节点拖动，双击节点配置属性；
 * - 节点库支持「点击添加」与「拖拽入画布」两种方式；
 * - 节点 type/subtype 存进 node.data 元数据，避免从 label 反查导致配置丢失。
 */
const store = useAppStore()
const flows = ref<any[]>([])
const current = ref<any>(null)
const nodes = ref<any[]>([])
const edges = ref<any[]>([])
const drawer = ref(false)
const draft = reactive<any>({})
const draftNodeId = ref('')
const managerVisible = ref(false)
const market = ref<any[]>([])
const aiVisible = ref(false)
const aiPrompt = ref('')
const fileInput = ref<HTMLInputElement>()
const localNodeInput = ref<HTMLInputElement>()
const running = ref(false)

// ---- 集成执行日志 ----
const activeExecution = ref<any>(null)
const execNodes = ref<any[]>([])
const execLogs = ref<any[]>([])
const inlineLogOpen = ref(false)
const inlineLogTab = ref('io')
const inlineHeight = ref(280)
let inlineResize: { startY: number; startH: number } | null = null
function startInlineResize(e: MouseEvent) {
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  inlineResize = { startY: e.clientY, startH: inlineHeight.value }
  window.addEventListener('mousemove', onInlineResize)
  window.addEventListener('mouseup', endInlineResize)
}
function onInlineResize(e: MouseEvent) {
  if (!inlineResize) return
  // 视口上方只保留标签页/工具栏/状态栏与一小条画布（约 380px），日志可尽量拉高
  const max = Math.max(200, window.innerHeight - 380)
  inlineHeight.value = Math.min(max, Math.max(120, inlineResize.startH - (e.clientY - inlineResize.startY)))
}
function endInlineResize() {
  window.removeEventListener('mousemove', onInlineResize)
  window.removeEventListener('mouseup', endInlineResize)
  inlineResize = null
}
let execPollTimer: number | undefined

// 草稿自动保存：切换流程/项目/菜单或定时触发时把画布变更落库，避免误操作丢数据
const dirty = ref(false)
const saving = ref(false)
const lastSavedAt = ref('')
let autosaveTimer: number | undefined
function markDirty() { dirty.value = true }

// 只在加载已有流程的瞬间屏蔽标脏；UI 状态（选中、执行勾选）不算流程变更
let hydrating = false
let savedSignature = ''
/** 图结构指纹：只覆盖真正需要落库的数据，避免选中/执行状态误触发保存。 */
function graphSignature() {
  return JSON.stringify({
    n: nodes.value.map((n: any) => [
      n.id, n.position?.x, n.position?.y,
      n.data?.title, n.data?.type, n.data?.subtype, n.data?.config
    ]),
    e: edges.value.map((e: any) => [e.id, e.source, e.target, e.sourceHandle, e.targetHandle])
  })
}
watch(graphSignature, (now, before) => {
  if (hydrating || now === before) return
  markDirty()
})

// 多选框选：默认左键在空白处拖动即框选（工作流风格），平移改用中键/右键。
// 不依赖 VueFlow 内置 selectionKeyCode（其 prop 只接受 Boolean/null），自己画选框并写自定义选中标记。
const selectionRect = ref<{ left: number; top: number; width: number; height: number } | null>(null)
const viewportRef = ref<HTMLElement>()
const flowRef = ref<any>(null)
let selecting = false
let selectStart: { x: number; y: number } | null = null
let selectAdditive = false

// 流程名称双击重命名
const renaming = ref(false)
const renameDraft = ref('')

// 版本发布历史
const timelineVisible = ref(false)
const versions = ref<any[]>([])
const loadingVersions = ref(false)
const expandedVersion = ref('')

// 项目管理（已从顶部栏迁移到本页）
const projectDialog = ref(false)
const editingProjectId = ref('')
const projectForm = reactive<any>({ name: '', code: '', color: '#2563EB', description: '' })
const currentProjectName = computed(() =>
  store.projects.find(p => p.id === store.projectId)?.name || '选择项目')

// 自定义节点/连线组件：左进右出连接点 + 连线中点删除按钮
const nodeTypes = { dflow: FlowNode }
const edgeTypes = { deletable: FlowEdge }
const { screenToFlowCoordinate, viewport } = useVueFlow('editor-flow')
// 节点外框尺寸（与 FlowNode.vue 保持一致），用于框选命中与多选外框定位
const NODE_W = 96, NODE_H = 124
const draggingType = ref('') // 当前从节点库拖动的 def key

// 页面顶部 Tab：编排 / 日志（日志不再跳转到独立页面）
const mainTab = ref<'editor' | 'logs'>('editor')

const definitions = computed(() => store.nodeDefinitions)
const groups = computed(() => {
  const map: Record<string, any[]> = {}
  definitions.value.forEach(d => {
    (map[d.type] ||= []).push(d)
  })
  return map
})

// 画布节点统一中性外观；图标由 FlowNode 按 subtype 选择 SVG，不再按类型配色
const NODE_THEME: Record<string, any> = {
  trigger: { color: '#475569', soft: '#f1f5f9' },
  source: { color: '#475569', soft: '#f1f5f9' },
  code: { color: '#475569', soft: '#f1f5f9' },
  logic: { color: '#475569', soft: '#f1f5f9' },
  output: { color: '#475569', soft: '#f1f5f9' }
}

const PROTOCOL_MAP: Record<string, string> = {
  opcua: 'OPC UA', modbus: 'Modbus', mqtt: 'MQTT',
  kafka: 'Kafka', http: 'HTTP', mysql: 'MySQL', oracle: 'Oracle',
  postgres: 'PostgreSQL', sqlserver: 'SQL Server', mongodb: 'MongoDB'
}

async function loadFlows() {
  flows.value = await api.flows(store.projectId)
  const target = flows.value.find(f => f.id === current.value?.id) || flows.value[0]
  if (target) {
    await selectFlow(target)
  } else {
    hydrating = true
    current.value = null
    nodes.value = []
    edges.value = []
    await nextTick()
    savedSignature = ''
    hydrating = false
  }
}

function parseGraph(flow: any) {
  if (!flow?.graphJson) return null
  return typeof flow.graphJson === 'string' ? JSON.parse(flow.graphJson) : flow.graphJson
}

/**
 * 让视口重新框住当前流程的全部节点。
 * fit-view-on-init 只在画布首次挂载时生效；切换流程后节点坐标可能差别很大
 * （例如上一个流程把节点拖到了画布上方），必须主动定位一次，否则会看到空白。
 *
 * 这里不用 store 的 fitView：新替换的节点在 VueFlow 内部尚未完成尺寸测量时它不会
 * 真正移动视口。直接按已知的画布坐标/节点尺寸算出中心与缩放，调用画布实例的
 * setCenter，稳定且不依赖测量时序。
 */
function fitFlowView(attempt = 0) {
  if (!nodes.value.length) return
  window.requestAnimationFrame(() => {
    const el = viewportRef.value
    const vf: any = flowRef.value
    if (!el || !vf || typeof vf.setCenter !== 'function') {
      if (attempt < 8) window.setTimeout(() => fitFlowView(attempt + 1), 120)
      return
    }
    const pts = nodes.value.map((n: any) => ({
      x: n.position?.x || 0, y: n.position?.y || 0
    }))
    const minX = Math.min(...pts.map(p => p.x))
    const maxX = Math.max(...pts.map(p => p.x + NODE_W))
    const minY = Math.min(...pts.map(p => p.y))
    const maxY = Math.max(...pts.map(p => p.y + NODE_H))
    const pad = 56
    const vw = el.clientWidth || 900
    const vh = el.clientHeight || 600
    const zoom = Math.max(0.3, Math.min(1.15,
      (vw - pad * 2) / Math.max(1, maxX - minX),
      (vh - pad * 2) / Math.max(1, maxY - minY)))
    void vf.setCenter((minX + maxX) / 2, (minY + maxY) / 2, { zoom, duration: 240 }).catch(() => {
      // 画布尚未就绪时忽略，下一次切换会重新定位
    })
  })
}

/** 从节点定义里挑一句话作为节点摘要，让画布上的节点更有信息量。 */
function nodeSummary(def: any, config: any): string {
  const c = config || {}
  const ds = c.datasource || ''
  if (ds) return `数据源：${ds}`
  if (c.code) return String(c.code).trim().split('\n')[0].slice(0, 24) || '脚本'
  if (c.topic) return `Topic：${c.topic}`
  if (c.table) return `表：${c.table}`
  if (c.expression) return String(c.expression).slice(0, 24)
  if (c.cron) return `Cron：${c.cron}`
  return def?.desc || ''
}

/** 把后端 GraphNode 转成 VueFlow 节点，元数据显式写入 data。 */
function toVueNode(n: any) {
  const theme = NODE_THEME[n.type] || { color: '#475569', soft: '#f1f5f9' }
  const def = definitions.value.find(d => d.type === n.type && d.subtype === n.subtype)
  const config = n.config || {}
  return {
    id: n.id,
    type: 'dflow',
    position: { x: n.position?.x || 0, y: n.position?.y || 0 },
    data: {
      title: n.title || n.subtype,
      subtype: n.subtype,
      type: n.type,
      nodeVersion: n.nodeVersion || '1',
      config,
      summary: nodeSummary(def, config),
      accent: theme.color,
      soft: theme.soft
    }
  }
}

/** 统一生成 VueFlow 连线，保证所有入口都使用可删除的自定义边。 */
function makeEdge(id: string, source: string, target: string, sourceHandle = 'out', targetHandle = 'in') {
  return {
    id, source, target, sourceHandle, targetHandle,
    type: 'deletable',
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: '#94a3b8', strokeWidth: 1.8 }
  }
}

function isMergeNode(n: any) {
  return n?.data?.type === 'logic' && n?.data?.subtype === 'merge'
}

/**
 * 合并节点按上游连线数量显示输入端口：每条入边独占一个 handle，
 * 增删连线后重新从左到右编号，画布上的箭头数量与上游节点数一致。
 */
function syncMergeInputs() {
  const merges = nodes.value.filter(isMergeNode)
  if (!merges.length) return
  const incoming = new Map<string, any[]>()
  edges.value.forEach((e: any) => {
    if (!incoming.has(e.target)) incoming.set(e.target, [])
    incoming.get(e.target)!.push(e)
  })
  const portIndex = (e: any) => {
    const m = /^in-(\d+)$/.exec(String(e.targetHandle || ''))
    return m ? Number(m[1]) : Number.MAX_SAFE_INTEGER
  }
  merges.forEach((node: any) => {
    const list = (incoming.get(node.id) || []).slice()
      .sort((a, b) => portIndex(a) - portIndex(b))
    list.forEach((edge: any, index: number) => { edge.targetHandle = `in-${index}` })
    node.data.inputCount = list.length
  })
}

/** 把节点时间线转成 nodeId → 状态，供画布与日志流程图复用。 */
function runStatusMap(rows: any[]) {
  const map = new Map<string, string>()
  rows.forEach(n => map.set(n.nodeId, n.status))
  return map
}

/** 成功节点右下角打勾、失败节点标红。 */
function decoratedNodes(list: any[], statuses: Map<string, string>) {
  return list.map(n => ({ ...n, data: { ...n.data, runStatus: statuses.get(n.id) } }))
}

/** 成功链路的箭头与连线变绿；日志页里的只读流程图隐藏删除按钮。 */
function decoratedEdges(list: any[], statuses: Map<string, string>, readonly = false) {
  return list.map(e => {
    const sourceStatus = statuses.get(e.source)
    const targetStatus = statuses.get(e.target)
    const success = sourceStatus === 'SUCCESS' && (targetStatus === undefined || targetStatus === 'SUCCESS')
    return {
      ...e,
      data: { ...(e.data || {}), deletable: !readonly },
      style: success ? { stroke: '#16a34a', strokeWidth: 2.2 } : { stroke: '#94a3b8', strokeWidth: 1.8 },
      markerEnd: success ? { type: MarkerType.ArrowClosed, color: '#16a34a' } : MarkerType.ArrowClosed
    }
  })
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

/** 新节点不与已有节点重叠；优先排在上游节点右侧，形成从左到右的流水线布局。 */
function findFreePosition(preferred: { x: number; y: number }) {
  const pos = { ...preferred }
  let guard = 0
  while (guard++ < 60 && nodes.value.some((n: any) =>
    Math.abs(n.position.x - pos.x) < 125 && Math.abs(n.position.y - pos.y) < 115)) {
    pos.y += 130
    if (guard % 4 === 0) { pos.y = preferred.y; pos.x += 150 }
  }
  return pos
}

async function selectFlow(flow: any) {
  hydrating = true
  current.value = flow
  dirty.value = false
  const graph = parseGraph(flow)
  nodes.value = (graph?.nodes || []).map(toVueNode)
  edges.value = (graph?.connections || []).map((e: any) =>
    makeEdge(e.id, e.source, e.target, e.sourceHandle || 'out', e.targetHandle || 'in'))
  syncMergeInputs()
  // 切换流程后，上一次执行的成功勾/绿色箭头不再适用
  activeExecution.value = null
  execNodes.value = []
  execLogs.value = []
  await nextTick()
  savedSignature = graphSignature()
  hydrating = false
  fitFlowView()
}

function graphPayload() {
  return {
    schemaVersion: 1,
    id: current.value?.id || '',
    name: current.value?.name || '未命名流程',
    projectId: store.projectId,
    settings: {},
    nodes: nodes.value.map(n => ({
      id: n.id,
      type: n.data.type,
      subtype: n.data.subtype,
      nodeVersion: n.data.nodeVersion || '1',
      title: n.data.title,
      position: { x: Math.round(n.position?.x || 0), y: Math.round(n.position?.y || 0) },
      config: n.data.config || {}
    })),
    connections: edges.value.map(e => ({
      id: e.id, source: e.source, target: e.target,
      sourceHandle: e.sourceHandle || 'out', targetHandle: e.targetHandle || 'in'
    }))
  }
}

/** 保存画布草稿；silent=true 用于自动保存，不弹成功提示。 */
async function saveFlow(silent = false): Promise<boolean> {
  if (!current.value) return false
  const signature = graphSignature()
  saving.value = true
  try {
    const payload = { name: current.value.name, projectId: store.projectId, graph: graphPayload() }
    const saved: any = await api.updateFlow(current.value.id, payload)
    current.value = saved
    // 同步左侧流程列表：否则切换流程再切回来会读到旧 graphJson，看起来像「没保存」
    const idx = flows.value.findIndex((f: any) => f.id === saved.id)
    if (idx >= 0) flows.value[idx] = saved
    savedSignature = signature
    // 保存期间若又改了图，保持脏状态，交给下一次自动保存
    dirty.value = graphSignature() !== signature
    lastSavedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    await store.loadProjects()
    if (!silent) ElMessage.success('流程已保存')
    return true
  } catch (e: any) {
    notifyError('流程保存失败：' + (e.message || '未知错误'))
    return false
  } finally {
    saving.value = false
  }
}

/**
 * 有未保存修改才真正写库；以图结构指纹为准，避免某些交互漏掉标脏。
 *
 * 返回值语义：true = 「可以继续后续动作」（已保存，或本来就干净），
 * 只有真正保存失败才返回 false。旧实现把「无需保存」也返回 false，
 * switchFlow 将其误判为失败后直接 return，导致点击别的流程毫无反应、页面像卡死。
 */
let saveInFlight: Promise<boolean> | null = null
async function autoSave(): Promise<boolean> {
  if (!current.value) return true
  // 已有保存在进行中：先等它结束，再判断是否又有新改动
  if (saveInFlight) {
    if (!await saveInFlight) return false
  }
  if (!dirty.value && graphSignature() === savedSignature) return true
  saveInFlight = saveFlow(true).finally(() => { saveInFlight = null })
  return saveInFlight
}

// 正在切换的流程 ID：防止连点重复触发保存/加载，也给列表一个明确的进行中状态
const switchingFlow = ref('')

/** 切换流程前先把当前草稿落库；保存失败才留在原流程，避免未落库草稿被覆盖丢失。 */
async function switchFlow(flow: any) {
  if (!flow || switchingFlow.value) return
  // 点击当前流程是空操作：不能用列表里的旧 graphJson 重载画布，否则刚编辑的草稿会丢失
  if (current.value?.id === flow.id) return
  switchingFlow.value = flow.id
  try {
    const wasDirty = dirty.value || graphSignature() !== savedSignature
    if (!await autoSave()) return
    if (wasDirty) ElMessage.info('已自动保存上一流程的草稿')
    await selectFlow(flow)
  } finally {
    switchingFlow.value = ''
  }
}

async function refreshActiveExecution() {
  if (!activeExecution.value) return false
  const id = activeExecution.value.id
  const [execution, nodeRows, lines]: any[] = await Promise.all([
    api.execution(id), api.executionNodes(id), api.executionLogs(id)
  ])
  activeExecution.value = execution
  execNodes.value = nodeRows
  execLogs.value = lines
  const statuses = runStatusMap(nodeRows)
  nodes.value = decoratedNodes(nodes.value, statuses)
  edges.value = decoratedEdges(edges.value, statuses)
  current.value.lastRun = execution.startTime
  current.value.records = execution.records
  return execution.status === 'SUCCESS' || execution.status === 'FAILED'
}

async function runFlow() {
  if (!current.value) return
  // 先落库，确保执行的是画布上刚改过的图
  if (dirty.value && !await saveFlow(true)) return
  running.value = true
  inlineLogOpen.value = true
  inlineLogTab.value = 'io'
  mainTab.value = 'editor'
  try {
    const result: any = await api.runFlow(current.value.id)
    activeExecution.value = result
    execNodes.value = []
    execLogs.value = []
    ElMessage.success('已触发执行：' + result.id)
    if (execPollTimer) window.clearInterval(execPollTimer)
    execPollTimer = window.setInterval(async () => {
      try {
        if (await refreshActiveExecution()) {
          window.clearInterval(execPollTimer)
          execPollTimer = undefined
          ElMessage.success(activeExecution.value.status === 'SUCCESS' ? '执行成功' : '执行失败，请查看下方日志')
        }
      } catch {
        // 网络抖动时保留弹窗，下一轮继续轮询
      }
    }, 1000)
  } finally {
    running.value = false
  }
}

// ---------------- 日志 Tab（复用独立日志页组件，锁定当前流程） ----------------
async function switchMainTab(tab: 'editor' | 'logs') {
  await autoSave()
  mainTab.value = tab
}

async function publish() {
  if (!current.value) return
  await saveFlow() // 发布前先把画布草稿落库，保证发布的是当前所见内容
  const version: any = await api.publishFlow(current.value.id)
  ElMessage.success(`已发布 v${version.version}，定时任务现在开始生效`)
  await loadFlows()
}

async function unpublish() {
  if (!current.value) return
  // 只有已发布的流程才有「下线」语义；草稿本就未生效，不允许下线
  if (!(current.value.publishedVersion > 0 && current.value.status === 'active')) {
    ElMessage.warning('该流程尚未发布，无法下线')
    return
  }
  await ElMessageBox.confirm(
    '下线后流程将停止自动运行（手动运行仍可用），历史版本会保留。确认继续？',
    '下线流程', { type: 'warning' })
  current.value = await api.unpublishFlow(current.value.id)
  ElMessage.success('流程已下线，自动调度已停止')
  await loadFlows()
}

/** 双击标题进入改名状态；回车/失焦提交，Esc 取消。 */
function startRename() {
  if (!current.value) return
  renameDraft.value = current.value.name || ''
  renaming.value = true
}
async function commitRename() {
  if (!renaming.value) return
  renaming.value = false
  const name = String(renameDraft.value || '').trim()
  if (!current.value || !name || name === current.value.name) return
  try {
    current.value = await api.updateFlow(current.value.id, { name })
    await loadFlows()
    ElMessage.success('流程名称已更新')
  } catch (e: any) {
    ElMessage.error('改名失败：' + (e.message || '未知错误'))
  }
}
function cancelRename() { renaming.value = false }

/** 打开版本发布历史。 */
async function openTimeline() {
  if (!current.value) return
  timelineVisible.value = true
  loadingVersions.value = true
  expandedVersion.value = ''
  try {
    versions.value = await api.flowVersions(current.value.id)
  } finally {
    loadingVersions.value = false
  }
}

/** 把历史版本快照恢复成当前草稿（不会改变发布状态，需再次发布才生效）。 */
async function restoreVersion(v: any) {
  if (!current.value) return
  await ElMessageBox.confirm(
    `确认将 v${v.version} 的图恢复到草稿？当前未发布的修改会被覆盖。`,
    '恢复历史版本', { type: 'warning' })
  const graph = typeof v.graphJson === 'string' ? JSON.parse(v.graphJson) : v.graphJson
  current.value = await api.updateFlow(current.value.id, { graph })
  await loadFlows()
  timelineVisible.value = false
  ElMessage.success(`已恢复 v${v.version} 到草稿，确认后点「发布」生效`)
}

/** 选中节点后按 Delete / Backspace 删除节点及其连线；输入框内不拦截。 */
function onGlobalKeydown(e: KeyboardEvent) {
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  const target = e.target as HTMLElement | null
  if (target && (['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable)) return
  if (drawer.value || managerVisible.value || timelineVisible.value || projectDialog.value || aiVisible.value) return
  const selectedIds = new Set(nodes.value.filter((n: any) => n.selected).map((n: any) => n.id))
  const selectedEdgeIds = new Set(edges.value.filter((ed: any) => ed.selected).map((ed: any) => ed.id))
  if (!selectedIds.size && !selectedEdgeIds.size) return
  e.preventDefault()
  nodes.value = nodes.value.filter((n: any) => !selectedIds.has(n.id))
  edges.value = edges.value.filter((ed: any) =>
    !selectedEdgeIds.has(ed.id) && !selectedIds.has(ed.source) && !selectedIds.has(ed.target))
  syncMergeInputs()
  markDirty()
  ElMessage.success(`已删除 ${selectedIds.size} 个节点、${selectedEdgeIds.size} 条连线`)
}

// ---------------- 多选框选 ----------------
function isCanvasBlank(target: EventTarget | null) {
  const el = target as HTMLElement | null
  if (!el || !el.closest) return false
  return !!el.closest('.vue-flow__pane')
    && !el.closest('.vue-flow__node')
    && !el.closest('.vue-flow__edge')
    && !el.closest('.vue-flow__handle')
}

/** 工作流交互：画布空白处左键拖动即框选多选；平移画布请用中键或右键。 */
function onViewportMouseDown(e: MouseEvent) {
  if (e.button !== 0 || !current.value || !viewportRef.value) return
  if (!isCanvasBlank(e.target)) return
  // 已有多选且按在蓝框内部：直接整体拖动（同类产品通用行为），不再画新选框
  const box = viewportRef.value.getBoundingClientRect()
  const px = e.clientX - box.left, py = e.clientY - box.top
  const sel = selectionBox.value
  if (sel && !e.ctrlKey && !e.metaKey
      && px >= sel.left && px <= sel.left + sel.width
      && py >= sel.top && py <= sel.top + sel.height) {
    e.preventDefault()
    e.stopPropagation()
    startBoxDrag(e)
    return
  }
  // 拦截 VueFlow 的平移与内置框选，避免它和我们自己的选框抢同一次拖拽
  e.preventDefault()
  e.stopPropagation()
  selecting = true
  selectAdditive = e.ctrlKey || e.metaKey
  selectStart = { x: px, y: py }
  selectionRect.value = { left: selectStart.x, top: selectStart.y, width: 0, height: 0 }
  window.addEventListener('mousemove', onSelectMove)
  window.addEventListener('mouseup', onSelectUp)
}

function onSelectMove(e: MouseEvent) {
  if (!selecting || !selectStart || !viewportRef.value) return
  const box = viewportRef.value.getBoundingClientRect()
  const x = e.clientX - box.left
  const y = e.clientY - box.top
  selectionRect.value = {
    left: Math.min(selectStart.x, x),
    top: Math.min(selectStart.y, y),
    width: Math.abs(x - selectStart.x),
    height: Math.abs(y - selectStart.y)
  }
}

function onSelectUp() {
  window.removeEventListener('mousemove', onSelectMove)
  window.removeEventListener('mouseup', onSelectUp)
  if (!selecting || !selectStart || !viewportRef.value || !selectionRect.value) {
    selecting = false
    selectStart = null
    selectionRect.value = null
    return
  }
  selecting = false
  const rect = selectionRect.value
  const start = selectStart
  selectionRect.value = null
  selectStart = null
  if (rect.width < 5 || rect.height < 5) {
    // 空白处单击：清空选中，工作流风格的多选框随之消失
    if (!selectAdditive) clearNodeSelection()
    return
  }
  const box = viewportRef.value.getBoundingClientRect()
  const a = screenToFlowCoordinate({ x: box.left + rect.left, y: box.top + rect.top })
  const b = screenToFlowCoordinate({
    x: box.left + rect.left + rect.width,
    y: box.top + rect.top + rect.height
  })
  const minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x)
  const minY = Math.min(a.y, b.y), maxY = Math.max(a.y, b.y)
  // 节点外框约 96px 宽、124px 高（方框 96 + 下方名称），与画布坐标一致
  const picked = nodes.value.filter((n: any) => {
    const x = n.position?.x || 0
    const y = n.position?.y || 0
    return x < maxX && x + NODE_W > minX && y < maxY && y + NODE_H > minY
  })
  // 用画布组件暴露的实例设置选中，确保 VueFlow 内部 store 与 DOM 同步；
  // 之后拖动任一选中节点，其余选中节点会按 getSelectedNodes 一起移动。
  const pickedIds = new Set(picked.map((n: any) => n.id))
  // 替换整个数组引用（VueFlow 对 props.nodes 非 deep 监听），
  // 并写入自定义选中标记，确保高亮和整体拖动不依赖 VueFlow 内部状态。
  nodes.value = nodes.value.map((n: any) => ({
    ...n,
    selected: pickedIds.has(n.id),
    data: { ...n.data, boxSelected: pickedIds.has(n.id) }
  }))
  const vf = flowRef.value
  if (vf && typeof vf.addSelectedNodes === 'function') {
    if (!selectAdditive) vf.removeSelectedNodes([...nodes.value])
    vf.addSelectedNodes(picked)
  }
  ElMessage.info(picked.length
    ? `已选中 ${picked.length} 个节点，可拖动外框或任一节点整体移动`
    : '选框内没有节点')
}

// ---------------- 多选外框：工作流风格蓝色虚框 + 整体拖动 ----------------
const selectedNodeIds = computed(() => nodes.value
  .filter((n: any) => n.selected || n.data?.boxSelected)
  .map((n: any) => n.id))

/** 根据选中节点的画布坐标 + 当前缩放，算出覆盖它们的屏幕矩形（相对画布容器）。 */
const selectionBox = computed(() => {
  if (selectedNodeIds.value.length < 2) return null
  const ids = new Set(selectedNodeIds.value)
  const picked = nodes.value.filter((n: any) => ids.has(n.id))
  if (picked.length < 2) return null
  const minX = Math.min(...picked.map((n: any) => n.position?.x || 0))
  const minY = Math.min(...picked.map((n: any) => n.position?.y || 0))
  const maxX = Math.max(...picked.map((n: any) => (n.position?.x || 0) + NODE_W))
  const maxY = Math.max(...picked.map((n: any) => (n.position?.y || 0) + NODE_H))
  const vp = viewport.value || { x: 0, y: 0, zoom: 1 }
  const pad = 12
  return {
    left: minX * vp.zoom + vp.x - pad,
    top: minY * vp.zoom + vp.y - pad,
    width: (maxX - minX) * vp.zoom + pad * 2,
    height: (maxY - minY) * vp.zoom + pad * 2,
    count: picked.length
  }
})

function clearNodeSelection() {
  nodes.value = nodes.value.map((n: any) => ({
    ...n, selected: false, data: { ...n.data, boxSelected: false }
  }))
  edges.value = edges.value.map((e: any) => ({ ...e, selected: false }))
}

// 外框整体拖动：按屏幕位移 / 缩放换算成画布位移，平移所有选中节点
const boxDragging = ref(false)
let boxDrag: { startX: number; startY: number; base: Map<string, { x: number; y: number }> } | null = null
function startBoxDrag(e: MouseEvent) {
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  const ids = new Set(selectedNodeIds.value)
  boxDrag = {
    startX: e.clientX,
    startY: e.clientY,
    base: new Map(nodes.value
      .filter((n: any) => ids.has(n.id))
      .map((n: any) => [n.id, { x: n.position?.x || 0, y: n.position?.y || 0 }] as const))
  }
  boxDragging.value = true
  window.addEventListener('mousemove', onBoxDragMove)
  window.addEventListener('mouseup', onBoxDragEnd)
}
function onBoxDragMove(e: MouseEvent) {
  if (!boxDrag) return
  const zoom = viewport.value?.zoom || 1
  const dx = (e.clientX - boxDrag.startX) / zoom
  const dy = (e.clientY - boxDrag.startY) / zoom
  nodes.value = nodes.value.map((n: any) => {
    const base = boxDrag!.base.get(n.id)
    return base ? { ...n, position: { x: base.x + dx, y: base.y + dy } } : n
  })
}
function onBoxDragEnd() {
  window.removeEventListener('mousemove', onBoxDragMove)
  window.removeEventListener('mouseup', onBoxDragEnd)
  if (boxDrag) markDirty()
  boxDrag = null
  boxDragging.value = false
}

// ---------------- 项目管理（流程编排页内） ----------------
function openProjectDialog() { projectDialog.value = true }
function resetProjectForm() {
  editingProjectId.value = ''
  Object.assign(projectForm, { name: '', code: '', color: '#2563EB', description: '' })
}
function editProject(p: any) {
  editingProjectId.value = p.id
  Object.assign(projectForm, {
    name: p.name, code: p.code || '', color: p.color || '#2563EB', description: p.description || ''
  })
}
async function saveProject() {
  if (!String(projectForm.name || '').trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  const payload = {
    name: String(projectForm.name).trim(),
    code: String(projectForm.code || '').trim(),
    color: projectForm.color,
    description: String(projectForm.description || '').trim()
  }
  if (editingProjectId.value) {
    await api.updateProject(editingProjectId.value, payload)
    ElMessage.success('项目已更新')
  } else {
    const created: any = await api.createProject(payload)
    store.setProject(created.id)
    ElMessage.success('项目已创建')
  }
  resetProjectForm()
  await store.loadProjects()
  await loadFlows()
}
async function removeProject(p: any) {
  await ElMessageBox.confirm(`确认删除项目「${p.name}」？若项目下仍有流程需先删除。`, '提示', { type: 'warning' })
  await api.deleteProject(p.id)
  ElMessage.success('项目已删除')
  await store.loadProjects()
  await loadFlows()
}
async function selectProject(command: string) {
  if (command === '__manage__') {
    openProjectDialog()
    return
  }
  if (!await autoSave() && dirty.value) return
  store.setProject(command)
  await loadFlows()
}

/** 版本快照格式化展示，方便审计发布当时到底有哪些节点与配置。 */
function formatGraph(raw: any): string {
  try {
    const graph = typeof raw === 'string' ? JSON.parse(raw) : raw
    return JSON.stringify(graph, null, 2)
  } catch {
    return String(raw || '')
  }
}

/** 画布右上角发布菜单。 */
async function onFlowMenuCommand(command: string) {
  if (command === 'publish') return publish()
  if (command === 'timeline') return openTimeline()
  if (command === 'unpublish') return unpublish()
}

async function createFlow() {
  if (!await autoSave() && dirty.value) return
  if (!store.projectId) {
    ElMessage.warning('请先新建或选择项目')
    return
  }
  const flow = await api.createFlow({ name: '未命名流程', projectId: store.projectId })
  flows.value.unshift(flow)
  await selectFlow(flow)
  ElMessage.success('已创建空白流程')
}

async function removeFlow() {
  if (!current.value) return
  await ElMessageBox.confirm('确认删除该流程？', '提示', { type: 'warning' })
  await api.deleteFlow(current.value.id)
  current.value = null
  loadFlows()
}

// 多选后整体拖动：VueFlow 内部 getDragItems 不总是识别外部设置的选中态，
// 这里在拖动事件中按位移量同步其余选中节点，与主流工作流平台行为一致。
let dragSync: {
  others: string[]
  base: Record<string, { x: number; y: number }>
  origin: { x: number; y: number }
} | null = null

/** Ctrl 点选后同步自定义高亮标记，保证多选反馈与框选一致。 */
async function onNodeClick() {
  await nextTick()
  const vf: any = flowRef.value
  const gn: any = vf?.getNodes
  const list: any[] = (gn && typeof gn === 'object' && 'value' in gn ? gn.value : gn) || []
  const ids = new Set(list.filter((n: any) => n.selected).map((n: any) => n.id))
  nodes.value = nodes.value.map((n: any) => ({
    ...n,
    data: { ...n.data, boxSelected: ids.has(n.id) }
  }))
}

function onNodeDragStart(e: any) {
  const draggedId = e.node?.id
  const selectedIds = nodes.value
    .filter((n: any) => n.selected || n.data?.boxSelected)
    .map((n: any) => n.id)
  if (selectedIds.length > 1 && selectedIds.includes(draggedId)) {
    dragSync = {
      others: selectedIds.filter(id => id !== draggedId),
      base: Object.fromEntries(nodes.value
        .filter((n: any) => selectedIds.includes(n.id))
        .map((n: any) => [n.id, { x: n.position.x, y: n.position.y }])),
      origin: { x: e.node.position.x, y: e.node.position.y }
    }
  }
}

function onNodeDrag(e: any) {
  if (!dragSync || !e.node) return
  const dx = e.node.position.x - dragSync.origin.x
  const dy = e.node.position.y - dragSync.origin.y
  nodes.value = nodes.value.map((n: any) => {
    if (n.id === e.node.id || !dragSync!.others.includes(n.id)) return n
    const base = dragSync!.base[n.id]
    return { ...n, position: { x: base.x + dx, y: base.y + dy } }
  })
}

function onNodeDragStop() {
  dragSync = null
  markDirty()
}

function onConnect(params: any) {
  // 自连与重复连线没有意义，直接忽略
  if (!params.source || !params.target || params.source === params.target) return
  if (edges.value.some((e: any) => e.source === params.source && e.target === params.target
      && (e.sourceHandle || 'out') === (params.sourceHandle || 'out'))) return
  edges.value.push(makeEdge(
    'e-' + Date.now() + '-' + Math.floor(Math.random() * 1000),
    params.source, params.target, params.sourceHandle || 'out', params.targetHandle || 'in'))
  syncMergeInputs()
  markDirty()
}

/** 点击连线中点的 × 或选中后按 Delete，都会走到这里。 */
function removeEdge(id: string) {
  const before = edges.value.length
  edges.value = edges.value.filter((e: any) => e.id !== id)
  if (edges.value.length !== before) {
    syncMergeInputs()
    markDirty()
  }
}

/** 在指定连线中间插入节点：source → 新节点 → target。 */
function insertNodeIntoEdge(edge: any, nodeId: string) {
  removeEdge(edge.id)
  edges.value.push(makeEdge('e-' + Date.now() + '-a' + Math.floor(Math.random() * 1000), edge.source, nodeId,
    edge.sourceHandle || 'out', 'in'))
  edges.value.push(makeEdge('e-' + Date.now() + '-b' + Math.floor(Math.random() * 1000), nodeId, edge.target,
    'out', edge.targetHandle || 'in'))
  syncMergeInputs()
  markDirty()
}

/** 双击节点打开属性配置（单击仅选中并拖动）。 */
function onNodeDoubleClick({ node }: any) {
  const def = definitions.value.find(d => d.type === node.data.type && d.subtype === node.data.subtype)
  if (!def) {
    ElMessage.warning('未找到节点定义，请先安装该节点')
    return
  }
  draftNodeId.value = node.id
  const config = { ...(node.data.config || {}) }
  ;(def.schema?.fields || []).forEach((f: any) => {
    if (config[f.key] === undefined && f.default !== undefined) config[f.key] = f.default
  })
  Object.keys(draft).forEach(k => delete draft[k])
  Object.assign(draft, config)
  draft.title = node.data.title
  drawer.value = true
}

function visibleFields(def: any) {
  return (def.schema?.fields || []).filter((f: any) => {
    if (!f.showWhen) return true
    const values = f.showWhen.in || []
    return values.map(String).includes(String(draft[f.showWhen.key]))
  })
}

function datasourceOptions(def: any) {
  return filterDatasources(store.sources, String(def?.subtype || ''))
}

/** 认证类字段不再按协议过滤：凭据统一存放在数据源里，可以是纯 CREDENTIAL 类别。 */
const CREDENTIAL_FIELD_KEYS = ['credential', 'auth', 'username', 'password', 'token', 'secret']
function optionsForField(def: any, f: any) {
  return CREDENTIAL_FIELD_KEYS.includes(f.key)
    ? [...store.sources].sort((a, b) =>
        (a.category === 'CREDENTIAL' ? -1 : 1) - (b.category === 'CREDENTIAL' ? -1 : 1))
    : datasourceOptions(def)
}

function currentDef() {
  const node = nodes.value.find(n => n.id === draftNodeId.value)
  if (!node) return undefined
  return definitions.value.find(d => d.type === node.data.type && d.subtype === node.data.subtype)
}

function saveNodeConfig() {
  const node = nodes.value.find(n => n.id === draftNodeId.value)
  const def = currentDef()
  if (!node || !def) return
  const config: any = {}
  Object.keys(draft).forEach(k => {
    if (k !== 'title') config[k] = draft[k]
  })
  node.data.config = config
  node.data.title = draft.title || def.title
  node.data.subtype = def.subtype
  node.data.type = def.type
  node.data.summary = nodeSummary(def, config)
  drawer.value = false
  markDirty()
  ElMessage.success('节点配置已更新，切换流程时会自动保存')
}

function deleteNode() {
  nodes.value = nodes.value.filter(n => n.id !== draftNodeId.value)
  edges.value = edges.value.filter(e => e.source !== draftNodeId.value && e.target !== draftNodeId.value)
  syncMergeInputs()
  drawer.value = false
  markDirty()
}

/** 在指定画布坐标添加一个节点（点击添加与拖拽添加共用）。 */
function addNodeAt(def: any, position: { x: number; y: number }, autoPosition = true) {
  const id = 'n-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
  const config: any = {}
  ;(def.schema?.fields || []).forEach((f: any) => {
    if (f.default !== undefined) config[f.key] = f.default
  })
  // 代码节点给出可直接运行的示例，避免用户打开面板时是空白
  if (def.subtype === 'nodejs') {
    config.code = '// $input 为输入数组，$json 为第一条；返回结果数组\nreturn $input.map(item => ({ ...item, processed: true }));'
  } else if (def.subtype === 'python') {
    config.code = '# _items 为输入列表，_json 为第一条；用 result 返回结果\nresult = [dict(item, processed=True) for item in _items]'
  }
  // 默认接在当前选中的连线上（中间插节点），否则接在流程末端节点后面
  const selectedEdges = edges.value.filter((e: any) => e.selected)
  const previous = nodes.value[nodes.value.length - 1]
  let targetPos = position
  if (autoPosition) {
    targetPos = previous
      ? findFreePosition({ x: previous.position.x + 220, y: previous.position.y })
      : findFreePosition(position)
  } else {
    targetPos = findFreePosition(position)
  }
  const node = toVueNode({
    id, type: def.type, subtype: def.subtype, nodeVersion: def.version || '1',
    title: def.title, position: targetPos, config
  })
  nodes.value.push(node)

  if (selectedEdges.length === 1) {
    insertNodeIntoEdge(selectedEdges[0], id)
  } else if (previous && def.type !== 'trigger') {
    // 触发器是流程入口，不能被自动接到别的节点后面
    edges.value.push(makeEdge('e-' + Date.now() + '-' + Math.floor(Math.random() * 1000), previous.id, id))
  }
  syncMergeInputs()
  markDirty()
  return node
}

/** 点击节点库添加（放在视口中央偏移处）。 */
function addNode(def: any) {
  addNodeAt(def, { x: 160 + nodes.value.length * 40, y: 140 + nodes.value.length * 26 })
  ElMessage.success('已添加节点，双击可配置属性')
}

/** 节点库拖拽开始：把 def 的 type:subtype 写入 dataTransfer。 */
function onPaletteDragStart(event: DragEvent, def: any) {
  if (!event.dataTransfer) return
  draggingType.value = def.type + ':' + def.subtype
  event.dataTransfer.setData('text/plain', draggingType.value)
  event.dataTransfer.effectAllowed = 'copy'
}
function onPaletteDragEnd() { draggingType.value = '' }

/** 画布拖拽经过：允许放置。 */
function onDragOver(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

/** 放到画布：把屏幕坐标换算成画布坐标后落位，并立即打开属性面板。 */
function onDrop(event: DragEvent) {
  event.preventDefault()
  const payload = event.dataTransfer?.getData('text/plain') || draggingType.value
  draggingType.value = ''
  if (!payload) return
  const [type, subtype] = payload.split(':')
  const def = definitions.value.find(d => d.type === type && d.subtype === subtype)
  if (!def || !current.value) return
  const pos = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  addNodeAt(def, { x: pos.x - 48, y: pos.y - 48 }, false)
  ElMessage.success(`已添加节点「${def.title}」，双击可配置属性`)
}

function download(path: string) {
  fetch(path, { headers: { Authorization: `Bearer ${localStorage.getItem('tls_token')}` } })
    .then(r => r.blob())
    .then(blob => {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = path.split('/').pop() || 'flow.json'
      a.click()
      URL.revokeObjectURL(a.href)
    })
}

async function importFlow(event: any) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const payload = JSON.parse(await file.text())
    payload.projectId = store.projectId
    const flow = await request.post('/flows/import', payload)
    flows.value.unshift(flow)
    await selectFlow(flow)
    ElMessage.success('导入成功')
  } catch (e: any) {
    ElMessage.error('导入失败：' + e.message)
  }
  event.target.value = ''
}

function openManager() {
  // 节点管理已独立成菜单页，统一跳转到该页安装/卸载节点包
  location.hash = '#/nodes'
}

async function install(item: any) {
  await api.installNode({ packageId: item.key })
  await store.loadNodeDefinitions()
  ElMessage.success(`已安装节点：${item.title}`)
}

async function uninstall(def: any) {
  await ElMessageBox.confirm(`确认卸载节点「${def.title}」？`, '提示', { type: 'warning' })
  await api.uninstallNode(def.subtype)
  await store.loadNodeDefinitions()
  ElMessage.success('已卸载')
}

async function installLocal(event: any) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const manifest = JSON.parse(await file.text())
    await api.installNode({ manifest })
    await store.loadNodeDefinitions()
    ElMessage.success('本地节点包安装成功')
  } catch (e: any) {
    ElMessage.error('安装失败：' + e.message)
  }
  event.target.value = ''
}

async function generateFlow() {
  if (!await autoSave() && dirty.value) return
  const result: any = await api.aiGenerate(aiPrompt.value, store.projectId)
  const graph = result.flow
  // 草稿必须先真正落库：之前只放进 current，自动保存会对不存在的流程 ID 反复报
  // 「流程保存失败：流程不存在」。这里用 AI 返回的 ID 直接建库，后续即可正常自动保存。
  const created: any = await api.createFlow({
    id: graph.id,
    name: graph.name,
    projectId: graph.projectId || store.projectId,
    graph
  })
  flows.value.unshift(created)
  aiVisible.value = false
  await selectFlow(created)
  ElMessage.success('AI 已生成草稿并保存到流程库，可继续编辑')
}

function onRemoveEdgeEvent(event: Event) {
  const id = (event as CustomEvent).detail?.id
  if (id) removeEdge(id)
}

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') autoSave()
}
function onPageHide() { autoSave() }

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('dflow:remove-edge', onRemoveEdgeEvent)
  document.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', onPageHide)
  store.loadSources()
  store.loadNodeDefinitions().then(loadFlows)
  // 兜底自动保存：即使一直停留在同一画布，也定期把变更落盘
  autosaveTimer = window.setInterval(() => { autoSave() }, 15000)
})
// 点击左侧菜单离开本页时，先等草稿写库完成再放行路由
onBeforeRouteLeave(async () => {
  await autoSave()
  return true
})
onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('dflow:remove-edge', onRemoveEdgeEvent)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pagehide', onPageHide)
  window.removeEventListener('mousemove', onSelectMove)
  window.removeEventListener('mouseup', onSelectUp)
  window.removeEventListener('mousemove', onBoxDragMove)
  window.removeEventListener('mouseup', onBoxDragEnd)
  window.removeEventListener('mousemove', onInlineResize)
  window.removeEventListener('mouseup', endInlineResize)
  if (autosaveTimer !== undefined) window.clearInterval(autosaveTimer)
  if (execPollTimer !== undefined) window.clearInterval(execPollTimer)
  autoSave()
})
</script>

<template>
  <div class="flow-layout">
    <aside class="flow-side">
      <div class="flow-side-head">
        <div class="flow-side-head-row">
          <div class="flow-side-title">流程库</div>
          <div class="flow-side-actions">
            <el-button size="small" type="primary" @click="createFlow">新建流程</el-button>
            <el-dropdown trigger="click" @command="selectProject">
              <el-button size="small" class="project-select-btn" :title="'切换或管理项目'">
                {{ currentProjectName }} ▾
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="p in store.projects" :key="p.id" :command="p.id">
                    <span :style="{ display:'inline-block',width:'8px',height:'8px',borderRadius:'50%',background:p.color||'#2563EB',marginRight:'6px' }" />
                    {{ p.name }}（{{ p.flowCount }} 个流程）{{ p.id === store.projectId ? ' ✓' : '' }}
                  </el-dropdown-item>
                  <el-dropdown-item divided command="__manage__">⚙ 管理项目…</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>
      <div class="flow-list">
        <div v-for="f in flows" :key="f.id" class="flow-item" :class="{ active: current?.id === f.id }" @click="switchFlow(f)">
          <div class="flow-name">
            {{ f.name }}
            <span v-if="switchingFlow === f.id" class="flow-switching">切换中…</span>
            <el-tag v-if="f.publishedVersion > 0 && f.status === 'active'" size="small" type="success" effect="plain">已发布 v{{ f.publishedVersion }}</el-tag>
            <el-tag v-else-if="f.publishedVersion > 0" size="small" type="warning" effect="plain">已下线</el-tag>
            <el-tag v-else size="small" type="info" effect="plain">草稿</el-tag>
          </div>
          <div class="flow-meta"><span>{{ f.lastRun || '尚未运行' }}</span><span>{{ f.updatedAt?.slice(0, 10) || '—' }}</span></div>
        </div>
        <el-empty v-if="!flows.length" description="该项目暂无流程" :image-size="50" />
      </div>
      <div class="node-palette">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div class="palette-title" style="margin:0">节点库 · 点击或拖入画布</div>
          <el-button link type="primary" size="small" @click="openManager">节点管理</el-button>
        </div>
        <div v-for="(items, type) in groups" :key="type">
          <div class="palette-title">{{ type }}</div>
          <div class="palette-grid">
            <button v-for="d in items" :key="d.type + d.subtype" class="palette-item"
                    draggable="true" :title="`点击添加，或拖到画布：${d.desc || d.title}`"
                    @click="addNode(d)" @dragstart="onPaletteDragStart($event, d)" @dragend="onPaletteDragEnd">
              {{ d.title }}
            </button>
          </div>
        </div>
      </div>
    </aside>

    <div class="flow-main">
      <div class="flow-top-tabs">
        <button class="flow-top-tab" :class="{ active: mainTab === 'editor' }" @click="switchMainTab('editor')">流程编排</button>
        <button class="flow-top-tab" :class="{ active: mainTab === 'logs' }" @click="switchMainTab('logs')">执行日志</button>
        <span class="flow-top-hint">日志与编排在一起：运行时可在画布下方看节点输入输出，切到日志页可检索全部历史</span>
      </div>
      <template v-if="mainTab === 'editor'">
      <div class="canvas-toolbar">
        <div class="canvas-title-area">
          <el-input v-if="renaming" v-model="renameDraft" size="small" class="rename-input"
                    autofocus @keyup.enter="commitRename" @keyup.esc="cancelRename" @blur="commitRename" />
          <span v-else class="canvas-title" title="双击修改流程名称" @dblclick="startRename">
            {{ current?.name || '未选择流程' }}
          </span>
          <el-tag v-if="current" size="small" :type="current.status === 'active' ? 'success' : 'info'" effect="light">
            {{ current.status === 'active' ? `已发布 v${current.publishedVersion}` : '未发布' }}
          </el-tag>
        </div>
        <div class="toolbar-actions">
          <el-button type="primary" size="small" :loading="running" @click="runFlow">运行</el-button>
          <el-button size="small" @click="saveFlow">保存</el-button>
          <el-button size="small" @click="download(`/api/v1/flows/${current?.id}/export`)">导出</el-button>
          <el-button size="small" @click="fileInput?.click()">导入</el-button>
          <input ref="fileInput" type="file" accept="application/json" style="display:none" @change="importFlow" />
          <el-button size="small" type="danger" plain @click="removeFlow">删除</el-button>
          <el-button size="small" style="color:#7c3aed;border-color:#ddd6fe" @click="aiVisible = true">AI 生成流程</el-button>
          <el-dropdown trigger="click" @command="onFlowMenuCommand">
            <el-button size="small" type="primary" plain>发布管理 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="publish">发布</el-dropdown-item>
                <el-dropdown-item command="timeline">版本管理</el-dropdown-item>
                <el-dropdown-item command="unpublish" divided
                  :disabled="!(current?.publishedVersion > 0 && current?.status === 'active')">下线</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
      <div ref="viewportRef" class="dag-viewport"
           :class="{ 'multi-select-active': !!selectionBox, 'multi-select-dragging': boxDragging }"
           @mousedown.capture="onViewportMouseDown"
           @dragover="onDragOver" @drop="onDrop">
        <VueFlow v-if="current" ref="flowRef" id="editor-flow" v-model:nodes="nodes" v-model:edges="edges"
                 :node-types="nodeTypes" :edge-types="edgeTypes"
                 fit-view-on-init :min-zoom="0.3" :max-zoom="1.8" :snap-to-grid="true" :snap-grid="[16, 16]"
                 :connection-radius="32" :delete-key-code="null"
                 :selection-key-code="null"
                 :multi-selection-key-code="'Control'"
                 :pan-on-drag="[1, 2]"
                 :default-edge-options="{ type: 'deletable' }"
                 @connect="onConnect" @node-double-click="onNodeDoubleClick" @node-click="onNodeClick"
                 @node-drag-start="onNodeDragStart" @node-drag="onNodeDrag" @node-drag-stop="onNodeDragStop">
          <Background :gap="16" pattern-color="#e2e8f0" />
          <Controls />
          <MiniMap />
        </VueFlow>
        <div v-else class="flow-empty">请先在左侧选择或新建流程（也可从节点库拖入节点）</div>
        <div v-if="selectionRect" class="canvas-selection-rect" :style="{
          left: selectionRect.left + 'px', top: selectionRect.top + 'px',
          width: selectionRect.width + 'px', height: selectionRect.height + 'px'
        }" />
        <!-- 多选完成后保留的蓝色虚框：边框可拖动，内部不挡节点 -->
        <div v-if="selectionBox" class="multi-select-box" :style="{
          left: selectionBox.left + 'px', top: selectionBox.top + 'px',
          width: selectionBox.width + 'px', height: selectionBox.height + 'px'
        }">
          <div class="ms-edge ms-top" @mousedown="startBoxDrag"></div>
          <div class="ms-edge ms-bottom" @mousedown="startBoxDrag"></div>
          <div class="ms-edge ms-left" @mousedown="startBoxDrag"></div>
          <div class="ms-edge ms-right" @mousedown="startBoxDrag"></div>
          <span class="ms-count">已选 {{ selectionBox.count }} 个 · 拖动边框整体移动</span>
        </div>
      </div>
      <div class="canvas-status">
        <span>节点 <strong>{{ nodes.length }}</strong></span>
        <span>连线 <strong>{{ edges.length }}</strong></span>
        <span :class="dirty ? 'save-state dirty' : 'save-state'">
          {{ saving ? '保存中…' : (dirty ? '● 有未保存修改（切换流程自动保存）' : (lastSavedAt ? `✓ 已自动保存 ${lastSavedAt}` : '✓ 已是最新')) }}
        </span>
        <span>空白处左键拖框多选 · Ctrl 拖框追加 · 蓝框内任意位置或任一选中节点可整体拖动 · 中键/右键平移画布 · 改动自动保存</span>
      </div>

      <!-- 点「运行」后日志直接出现在画布下方，可折叠 -->
      <div v-if="activeExecution" class="inline-exec" :class="{ collapsed: !inlineLogOpen }"
           :style="inlineLogOpen ? { height: inlineHeight + 'px' } : {}">
        <div v-show="inlineLogOpen" class="inline-resizer" title="上下拖动调整日志栏高度"
             @mousedown="startInlineResize"></div>
        <div class="inline-exec-head" @click="inlineLogOpen = !inlineLogOpen">
          <span class="inline-exec-title">
            <el-tag :type="statusTag(activeExecution.status)" size="small">{{ activeExecution.status }}</el-tag>
            执行 {{ activeExecution.id }} · {{ activeExecution.startTime }} · {{ activeExecution.records }} 条
          </span>
          <span class="muted">{{ inlineLogOpen ? '点击收起' : '点击展开' }}</span>
        </div>
        <div v-show="inlineLogOpen" class="inline-exec-body">
          <el-tabs v-model="inlineLogTab" size="small">
            <el-tab-pane label="节点输入输出" name="io">
              <div v-if="!execNodes.length" class="muted" style="padding:10px">等待节点产出…</div>
              <div v-for="n in execNodes" :key="n.id" class="io-node">
                <div class="io-node-head">
                  <strong>{{ n.nodeTitle || n.nodeId }}</strong>
                  <el-tag size="small" :type="statusTag(n.status)">{{ n.status }}</el-tag>
                  <span class="muted">{{ n.durationMs }} ms</span>
                  <span class="muted">{{ n.nodeType }}</span>
                </div>
                <div class="io-pair">
                  <details open><summary>输入</summary><pre>{{ prettyJson(n.inputJson) }}</pre></details>
                  <details open><summary>输出</summary><pre>{{ prettyJson(n.outputJson) }}</pre></details>
                </div>
                <div v-if="n.errorMessage" class="io-error">{{ n.errorMessage }}</div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="运行日志" name="logs">
              <div class="inline-log-console">
                <div v-for="line in execLogs" :key="line.id" :class="{ 'log-error': line.level === 'ERROR' }">
                  [{{ line.ts }}] [{{ line.level }}] {{ line.message }}
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
      </template>

      <template v-else>
        <ExecutionLogExplorer :fixed-flow-id="current?.id || ''" />
      </template>
    </div>

    <el-drawer v-model="drawer" title="节点属性配置" size="540px">
      <template v-if="currentDef()">
        <div class="muted" style="margin-bottom:12px">{{ currentDef()?.schema?.note }}</div>
        <el-form label-position="top">
          <el-form-item label="节点名称"><el-input v-model="draft.title" /></el-form-item>
          <el-form-item v-for="f in visibleFields(currentDef())" :key="f.key" :label="f.label">
            <el-select v-if="f.type === 'select'" v-model="draft[f.key]" style="width:100%">
              <el-option v-for="o in f.options || []" :key="o" :label="f.labels?.[o] || o" :value="o" />
            </el-select>
            <el-select v-else-if="f.type === 'datasource'" v-model="draft[f.key]" style="width:100%" filterable>
              <el-option v-for="s in optionsForField(currentDef(), f)" :key="s.id"
                         :label="s.name" :value="s.id" />
            </el-select>
            <el-select v-else-if="f.type === 'credential'" v-model="draft[f.key]" style="width:100%" filterable>
              <el-option v-for="s in store.sources" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-input v-else-if="f.type === 'code'" v-model="draft[f.key]" type="textarea"
                      class="code-editor" :rows="f.rows || 12" spellcheck="false" />
            <el-input v-else-if="f.type === 'textarea' || f.type === 'json'" v-model="draft[f.key]" type="textarea" :rows="f.rows || 4" />
            <el-switch v-else-if="f.type === 'boolean'" v-model="draft[f.key]" />
            <el-input-number v-else-if="f.type === 'number'" v-model="draft[f.key]" style="width:100%" />
            <el-input v-else v-model="draft[f.key]" :placeholder="f.placeholder || ''" />
            <div v-if="f.hint" class="muted" style="font-size:11px;margin-top:4px">{{ f.hint }}</div>
          </el-form-item>
        </el-form>
      </template>
      <el-empty v-else description="未找到节点定义，请先在节点管理中安装" />
      <template #footer>
        <el-button type="danger" @click="deleteNode">删除节点</el-button>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" @click="saveNodeConfig">保存配置</el-button>
      </template>
    </el-drawer>

    <el-drawer v-model="managerVisible" title="节点管理" size="560px">
      <div class="muted" style="margin-bottom:12px">
        内置节点 {{ definitions.filter(d => d.builtIn).length }} 个 · 已安装扩展 {{ definitions.filter(d => !d.builtIn).length }} 个
      </div>
      <div v-for="item in market" :key="item.key" class="card" style="margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div><strong>{{ item.title }}</strong><div class="muted" style="font-size:12px">{{ item.desc }}</div></div>
          <el-button size="small" type="primary" @click="install(item)">安装</el-button>
        </div>
      </div>
      <div v-for="d in definitions.filter(x => !x.builtIn)" :key="d.subtype" class="card" style="margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;align-items:center">
          <div><strong>{{ d.title }}</strong><div class="muted" style="font-size:12px">{{ d.author }} · {{ d.version }}</div></div>
          <el-button size="small" @click="uninstall(d)">卸载</el-button>
        </div>
      </div>
      <el-divider />
      <el-button @click="localNodeInput?.click()">导入本地节点包 JSON</el-button>
      <input ref="localNodeInput" type="file" accept="application/json" style="display:none" @change="installLocal" />
    </el-drawer>

    <el-dialog v-model="aiVisible" title="AI 生成流程" width="520px">
      <el-input v-model="aiPrompt" type="textarea" :rows="4" placeholder="例如：每 10 分钟读取 OPC UA 温度点位，超过 85 度推送飞书告警" />
      <template #footer>
        <el-button @click="aiVisible = false">取消</el-button>
        <el-button type="primary" @click="generateFlow">生成草稿</el-button>
      </template>
    </el-dialog>

    <!-- 版本发布时间线 -->
    <el-drawer v-model="timelineVisible" title="版本管理" size="620px">
      <div class="muted" style="margin-bottom:14px">
        只有发布生成的版本会被调度器执行；下线不会删除历史快照。
      </div>
      <el-skeleton v-if="loadingVersions" :rows="5" animated />
      <el-empty v-else-if="!versions.length" description="还没有发布过版本" :image-size="60" />
      <div v-else class="version-timeline">
        <div v-for="(v, i) in versions" :key="v.id" class="version-item">
          <div class="version-node" :class="{ latest: i === 0 }">v{{ v.version }}</div>
          <div class="version-card">
            <div style="display:flex;justify-content:space-between;align-items:center">
              <strong>{{ v.changelog || `版本 ${v.version}` }}</strong>
              <el-tag v-if="i === 0" size="small" type="success" effect="light">最新</el-tag>
            </div>
            <div class="muted" style="font-size:12px;margin:4px 0 8px">
              {{ v.createdAt }} · ID {{ v.id }}
            </div>
            <el-button link type="primary" size="small" @click="expandedVersion = expandedVersion === v.id ? '' : v.id">
              {{ expandedVersion === v.id ? '收起快照' : '查看快照' }}
            </el-button>
            <el-button link type="warning" size="small" @click="restoreVersion(v)">恢复到草稿</el-button>
            <pre v-if="expandedVersion === v.id" class="version-snapshot">{{ formatGraph(v.graphJson) }}</pre>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 项目管理：新建 / 重命名 / 删除 -->
    <el-dialog v-model="projectDialog" title="项目管理" width="620px">
      <div class="muted" style="margin-bottom:12px">
        共 {{ store.projects.length }} 个项目。流程按项目分类管理，切换项目会过滤流程与看板数据。
      </div>
      <div class="project-manage-list">
        <div v-for="p in store.projects" :key="p.id" class="project-manage-item"
             :class="{ active: p.id === store.projectId }">
          <span class="project-dot" :style="{ background: p.color || '#2563EB' }"></span>
          <div class="project-manage-info">
            <div class="project-manage-name">{{ p.name }} <span class="muted">({{ p.flowCount }} 个流程)</span></div>
            <div class="muted" style="font-size:11px">{{ p.code || p.id }}{{ p.description ? ' · ' + p.description : '' }}</div>
          </div>
          <el-button link size="small" @click="editProject(p)">编辑</el-button>
          <el-button link size="small" type="danger" @click="removeProject(p)">删除</el-button>
        </div>
        <el-empty v-if="!store.projects.length" description="暂无项目，请在下方新建" :image-size="50" />
      </div>
      <el-divider />
      <div style="font-weight:600;margin-bottom:10px">{{ editingProjectId ? '编辑项目' : '新建项目' }}</div>
      <el-form label-position="top" @submit.prevent="saveProject">
        <el-form-item label="项目名称"><el-input v-model="projectForm.name" placeholder="例如：华东工厂数据采集" /></el-form-item>
        <el-form-item label="项目编码"><el-input v-model="projectForm.code" placeholder="例如：EAST-PLANT" /></el-form-item>
        <el-form-item label="标识颜色"><el-color-picker v-model="projectForm.color" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="projectForm.description" placeholder="项目用途说明（可选）" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDialog = false; resetProjectForm()">关闭</el-button>
        <el-button type="primary" @click="saveProject">{{ editingProjectId ? '保存修改' : '创建项目' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
