<script setup lang="ts">
/**
 * n8n 风格 DAG 节点：统一中性外观，图标按节点子类型选择专业线性 SVG。
 * - 触发器节点左侧（流程入口侧）上下两个角弧度更大，画布上一眼可辨；
 * - 左进右出的连接点与流程 JSON 的 in/out handle 对应；
 * - 执行成功后右下角显示绿色勾选，失败显示红色叹号；
 * - 单击由画布负责选中/拖动，双击打开属性面板；空白处左键拖框可多选后一起拖动。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import NodeIcon from './NodeIcon.vue'

const props = defineProps<{
  data: {
    title: string
    subtype: string
    type: string
    config?: Record<string, any>
    summary?: string
    accent?: string
    soft?: string
    icon?: string
    runStatus?: string
    boxSelected?: boolean
  }
  selected?: boolean
}>()

// 与后端 node-definitions.json 的 subtype 对齐：同类节点用同一图标，颜色不再按类型区分
const SUBTYPE_ICON: Record<string, string> = {
  schedule: 'clock', manual: 'hand',
  opcua: 'chip', modbus: 'chip', mqtt: 'cloud', http: 'globe', kafka: 'layers',
  mysql: 'database', oracle: 'database', postgres: 'database',
  sqlserver: 'database', mongodb: 'database',
  nodejs: 'code', python: 'code',
  condition: 'branch', filter: 'filter', merge: 'merge', delay: 'clock',
  timeseries: 'database', feishu_bitable: 'table', webhook: 'send', alert: 'bell'
}
const TYPE_ICON: Record<string, string> = {
  trigger: 'clock', source: 'database', code: 'code', logic: 'branch', output: 'send'
}
const iconName = computed(() =>
  SUBTYPE_ICON[props.data.subtype] || TYPE_ICON[props.data.type] || 'cube')
</script>

<template>
  <div
    class="dflow-node-wrap"
    :class="{ 'is-selected': selected || data.boxSelected, 'is-trigger': data.type === 'trigger', 'is-success': data.runStatus === 'SUCCESS', 'is-failed': data.runStatus === 'FAILED' }"
    :title="`${data.title}${data.summary ? ' · ' + data.summary : ''}（双击配置）`"
  >
    <div class="dflow-node">
      <!-- 触发器没有输入连接点；其余节点左进右出 -->
      <Handle v-if="data.type !== 'trigger'" id="in" type="target" :position="Position.Left" class="dflow-handle dflow-handle-in" />
      <div class="dflow-node-icon"><NodeIcon :name="iconName" /></div>
      <span v-if="data.runStatus === 'SUCCESS'" class="dflow-run-badge success" title="该节点执行成功">✓</span>
      <span v-else-if="data.runStatus === 'FAILED'" class="dflow-run-badge failed" title="该节点执行失败">!</span>
      <span v-else-if="data.runStatus === 'RUNNING'" class="dflow-run-badge running" title="执行中">…</span>
      <Handle id="out" type="source" :position="Position.Right" class="dflow-handle dflow-handle-out" />
    </div>
    <div class="dflow-node-name">{{ data.title }}</div>
  </div>
</template>

<style scoped>
.dflow-node-wrap {
  --accent: #475569;
  width: 96px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  user-select: none;
}
.dflow-node {
  position: relative;
  width: 96px;
  height: 96px;
  box-sizing: border-box;
  display: grid;
  place-items: center;
  background: #fff;
  border: 1.5px solid #cbd5e1;
  border-radius: 16px;
  color: #334155;
  cursor: grab;
  transition: box-shadow .18s ease, transform .18s ease, border-color .18s ease;
  box-shadow: 0 2px 6px rgba(15, 23, 42, .08);
}
.dflow-node-wrap.is-trigger .dflow-node { border-radius: 38px 16px 16px 38px; }
.dflow-node-wrap:hover .dflow-node { box-shadow: 0 6px 18px rgba(15, 23, 42, .15); transform: translateY(-1px); }
.dflow-node:active { cursor: grabbing; }
.dflow-node-wrap.is-selected .dflow-node {
  border-color: #3b82f6;
  border-width: 2px;
  box-shadow: 0 0 0 4px rgba(59, 130, 246, .2), 0 8px 20px rgba(15, 23, 42, .16);
}
.dflow-node-wrap.is-success .dflow-node { border-color: #16a34a; }
.dflow-node-wrap.is-failed .dflow-node { border-color: #dc2626; }
.dflow-node-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  /* 图标背景与节点底色一致（透明），避免节点内部再出现一块色块 */
  background: transparent;
  color: #334155;
}
.dflow-node-name {
  max-width: 148px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.25;
  text-align: center;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dflow-run-badge {
  position: absolute;
  right: -6px;
  bottom: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  border: 2px solid #fff;
  z-index: 4;
}
.dflow-run-badge.success { background: #16a34a; }
.dflow-run-badge.failed { background: #dc2626; }
.dflow-run-badge.running { background: #2563eb; }
/* 连接点：左进右出，放大命中区域，hover 高亮 */
.dflow-handle {
  width: 13px !important;
  height: 13px !important;
  background: #fff !important;
  border: 2px solid var(--accent) !important;
  cursor: crosshair !important;
  transition: transform .15s ease, background .15s ease, box-shadow .15s ease;
  z-index: 3;
}
.dflow-handle::after {
  content: '';
  position: absolute;
  inset: -11px;
  border-radius: 50%;
}
.dflow-handle:hover {
  transform: scale(1.4);
  background: var(--accent) !important;
  box-shadow: 0 0 0 5px rgba(71, 85, 105, .18);
}
.vue-flow__handle.connectingto { background: var(--accent) !important; transform: scale(1.5); }
</style>
