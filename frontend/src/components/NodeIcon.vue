<script setup lang="ts">
/**
 * 画布节点的统一线性图标（Lucide 风格，24x24、currentColor 描边）。
 * 图标按节点 subtype 选择，触发器：定时=时钟、手动=手指；与业界常见工作流工具保持一致。
 */
defineProps<{ name: string }>()
</script>

<template>
  <svg class="dflow-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <!-- 触发：定时 -->
    <template v-if="name === 'clock'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3.2 2" />
    </template>
    <!-- 延时等待：沙漏 -->
    <template v-else-if="name === 'hourglass'">
      <path d="M5 22h14M5 2h14" />
      <path d="M17 22v-4.2a2 2 0 0 0-.6-1.4L12 12l4.4-4.4a2 2 0 0 0 .6-1.4V2" />
      <path d="M7 22v-4.2a2 2 0 0 1 .6-1.4L12 12 7.6 7.6A2 2 0 0 1 7 6.2V2" />
      <path d="m9.5 18 2.5-2.5L14.5 18" />
    </template>
    <!-- 触发：手动（手指点击） -->
    <template v-else-if="name === 'hand'">
      <path d="M9 12V6.2a1.6 1.6 0 0 1 3.2 0V11" />
      <path d="M12.2 11V4.8a1.6 1.6 0 0 1 3.2 0V11" />
      <path d="M15.4 11V7.2a1.6 1.6 0 0 1 3.2 0v6.2a6.6 6.6 0 0 1-6.6 6.6h-.7A6 6 0 0 1 5.9 16L3.8 13a1.6 1.6 0 0 1 2.5-2L9 13.8" />
    </template>
    <!-- 工业采集：芯片 -->
    <template v-else-if="name === 'chip'">
      <rect x="7" y="7" width="10" height="10" rx="2" />
      <rect x="10" y="10" width="4" height="4" rx="0.6" />
      <path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3" />
    </template>
    <!-- MQTT 云 -->
    <template v-else-if="name === 'cloud'">
      <path d="M6.5 18.5A4.5 4.5 0 0 1 6 9.6 6 6 0 0 1 17.8 9a4.8 4.8 0 0 1-.3 9.5H6.5Z" />
    </template>
    <!-- HTTP / API 网络 -->
    <template v-else-if="name === 'globe'">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3c2.7 2.7 2.7 15.3 0 18M12 3c-2.7 2.7-2.7 15.3 0 18" />
    </template>
    <!-- Kafka / 合并：多层 -->
    <template v-else-if="name === 'layers' || name === 'merge'">
      <path d="m12 3 9 5-9 5-9-5 9-5Z" />
      <path d="m3 12 9 5 9-5M3 16l9 5 9-5" />
    </template>
    <!-- 数据库 -->
    <template v-else-if="name === 'database'">
      <ellipse cx="12" cy="5.5" rx="8" ry="3" />
      <path d="M4 5.5v13c0 1.7 3.6 3 8 3s8-1.3 8-3v-13" />
      <path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" />
    </template>
    <!-- 代码 -->
    <template v-else-if="name === 'code'">
      <path d="m8.5 8-4 4 4 4M15.5 8l4 4-4 4M13.8 4.5l-3.6 15" />
    </template>
    <!-- 条件分支 -->
    <template v-else-if="name === 'branch'">
      <circle cx="6" cy="5.5" r="2" />
      <circle cx="18" cy="5.5" r="2" />
      <circle cx="12" cy="18.5" r="2" />
      <path d="M6 7.5v4a4 4 0 0 0 4 4h4M18 7.5v4a4 4 0 0 1-4 4" />
    </template>
    <!-- 过滤 -->
    <template v-else-if="name === 'filter'">
      <path d="M3.5 5h17l-6.8 7.8v5.7l-3.4 1.8v-7.5L3.5 5Z" />
    </template>
    <!-- 推送 / Webhook -->
    <template v-else-if="name === 'send'">
      <path d="m21.5 2.5-7 19-3.8-8.2-8.2-3.8 19-7Z" />
      <path d="M21.5 2.5 10.7 13.3" />
    </template>
    <!-- 飞书多维表格：表格 -->
    <template v-else-if="name === 'table'">
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M3 9.5h18M9 9.5V20M15 9.5V20" />
    </template>
    <!-- 告警 -->
    <template v-else-if="name === 'bell'">
      <path d="M18 8.5a6 6 0 1 0-12 0c0 6.5-2.5 7-2.5 9h17c0-2-2.5-2.5-2.5-9Z" />
      <path d="M10 20.5h4" />
    </template>
    <!-- 兜底 -->
    <template v-else>
      <rect x="4" y="4" width="16" height="16" rx="4" />
      <path d="M9 12h6M12 9v6" />
    </template>
  </svg>
</template>

<style scoped>
.dflow-icon-svg { width: 24px; height: 24px; display: block; }
</style>
