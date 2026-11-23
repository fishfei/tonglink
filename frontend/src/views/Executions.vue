<script setup lang="ts">
import { ref } from 'vue'
import ExecutionLogExplorer from '../components/ExecutionLogExplorer.vue'

// 独立「执行日志」菜单：与流程编排页共用同一套日志浏览器，但不锁定流程，可查询所有流程。
const explorer = ref<any>(null)
</script>

<template>
  <div class="exec-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">执行日志</h1>
        <div class="page-desc">全部流程的每次手动或定时执行记录，支持节点级输入输出、流程图、标签与 AI 失败诊断</div>
      </div>
      <el-button @click="explorer?.refresh()">刷新</el-button>
    </div>
    <div class="exec-explorer-host">
      <ExecutionLogExplorer ref="explorer" />
    </div>
  </div>
</template>

<style scoped>
.exec-page { display: flex; flex-direction: column; min-height: 0; }
.exec-explorer-host { flex: 1; height: calc(100vh - 190px); min-height: 520px; background: #fff; border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
</style>
