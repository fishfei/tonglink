<script setup lang="ts">
import { onMounted, ref, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { api } from '../api'
import { useAppStore } from '../stores'

const router = useRouter()
const store = useAppStore()
const summary = ref<any>({})
const stats = ref<any[]>([])
const range = ref('24h')
const trendChart = ref<HTMLElement>()
const barChart = ref<HTMLElement>()
let trendInstance: echarts.ECharts | null = null
let barInstance: echarts.ECharts | null = null

/** 流程运行状态中文化：运行中的发布版 / 已下线 / 草稿。 */
function flowStatusText(row: any) {
  if (row.status === 'active') return '运行中'
  return Number(row.publishedVersion) > 0 ? '已下线' : '草稿'
}

async function load() {
  summary.value = await api.dashboardSummary()
  stats.value = await api.dashboardFlowStats()
  const trend = await api.dashboardTrend(range.value)
  await nextTick()
  if (!trendInstance && trendChart.value) {
    trendInstance = echarts.init(trendChart.value)
  }
  trendInstance?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['执行次数', '成功次数'] },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: trend.map((p: any) => p.time.slice(5, 16)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '执行次数', type: 'line', smooth: true, data: trend.map((p: any) => p.total), areaStyle: {}, itemStyle: { color: '#2563eb' } },
      { name: '成功次数', type: 'line', smooth: true, data: trend.map((p: any) => p.success), itemStyle: { color: '#059669' } }
    ]
  })
  if (!barInstance && barChart.value) {
    barInstance = echarts.init(barChart.value)
  }
  barInstance?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 120, right: 30, top: 20, bottom: 30 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: stats.value.map(s => s.name) },
    series: [{ type: 'bar', data: stats.value.map(s => s.records), itemStyle: { color: '#8b5cf6', borderRadius: [0, 4, 4, 0] } }]
  })
}

watch(range, load)
onMounted(() => {
  store.loadProjects()
  load()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">数据看板</h1>
        <div class="page-desc">流程执行情况、下次执行时间与采集数据量总览</div>
      </div>
      <el-radio-group v-model="range" size="small">
        <el-radio-button value="24h">24 小时</el-radio-button>
        <el-radio-button value="7d">7 天</el-radio-button>
        <el-radio-button value="30d">30 天</el-radio-button>
      </el-radio-group>
    </div>

    <div class="grid-4">
      <div class="card"><div class="kpi-label">流程总数</div><div class="kpi-value">{{ summary.flowCount || 0 }}</div></div>
      <div class="card"><div class="kpi-label">运行中流程</div><div class="kpi-value" style="color:var(--success)">{{ summary.activeFlows || 0 }}</div></div>
      <div class="card"><div class="kpi-label">累计采集记录</div><div class="kpi-value">{{ summary.totalRecords || 0 }}</div></div>
      <div class="card"><div class="kpi-label">执行成功率</div><div class="kpi-value">{{ summary.successRate || '—' }}</div></div>
    </div>

    <div class="grid-2 mt-14">
      <div class="card">
        <div class="card-title">执行趋势</div>
        <div ref="trendChart" style="height:240px" />
      </div>
      <div class="card">
        <div class="card-title">各流程采集记录数</div>
        <div ref="barChart" style="height:240px" />
      </div>
    </div>

    <div class="card mt-14">
      <div class="card-title">流程运行状态</div>
      <el-table :data="stats" size="small">
        <el-table-column prop="name" label="流程名称" min-width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push('/flows')">{{ row.name }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="projectId" label="所属项目" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small" effect="plain">
              {{ flowStatusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nextRun" label="下次执行时间" width="190" />
        <el-table-column prop="records" label="采集记录数" width="120" />
        <el-table-column prop="successRate" label="成功率" width="100" />
      </el-table>
    </div>
  </div>
</template>
