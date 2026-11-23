<script setup lang="ts">
/**
 * 可删除连线：线条中点提供一个 × 按钮，方便在两个节点中间插入新节点。
 * 删除动作通过全局事件交给编排页处理（VueFlow 内部渲染边组件，组件 emit 不会直达页面）。
 */
import { computed } from 'vue'
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath } from '@vue-flow/core'

const props = defineProps<{
  id: string
  sourceX: number
  sourceY: number
  targetX: number
  targetY: number
  sourcePosition?: any
  targetPosition?: any
  markerEnd?: string
  style?: any
  selected?: boolean
  data?: { deletable?: boolean }
}>()

const path = computed(() => getSmoothStepPath({
  sourceX: props.sourceX,
  sourceY: props.sourceY,
  targetX: props.targetX,
  targetY: props.targetY,
  sourcePosition: props.sourcePosition,
  targetPosition: props.targetPosition,
  borderRadius: 12
}))

function removeEdge() {
  window.dispatchEvent(new CustomEvent('dflow:remove-edge', { detail: { id: props.id } }))
}
</script>

<template>
  <BaseEdge :id="id" :path="path[0]" :marker-end="markerEnd" :style="style" />
  <EdgeLabelRenderer v-if="data?.deletable !== false">
    <div class="edge-action nodrag nopan"
         :class="{ 'is-selected': selected }"
         :style="{ transform: `translate(-50%, -50%) translate(${path[1]}px, ${path[2]}px)` }"
         title="删除这条连线（也可选中后按 Delete）"
         @click.stop="removeEdge">
      ×
    </div>
  </EdgeLabelRenderer>
</template>

<style scoped>
.edge-action {
  position: absolute;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fff;
  border: 1px solid #cbd5e1;
  color: #64748b;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  opacity: .55;
  box-shadow: 0 1px 4px rgba(15, 23, 42, .12);
  transition: opacity .15s ease, transform .15s ease, color .15s ease, border-color .15s ease;
  pointer-events: all;
}
.edge-action:hover,
.edge-action.is-selected {
  opacity: 1;
  color: #dc2626;
  border-color: #fca5a5;
  box-shadow: 0 2px 8px rgba(220, 38, 38, .28);
}
</style>
