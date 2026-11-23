<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import request from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const list = ref<any[]>([])
const protocols = ref<Record<string, string[]>>({})
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const filtered = computed(() => list.value.filter(s =>
  !keyword.value || s.name?.includes(keyword.value) || s.protocol?.includes(keyword.value)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([keyword, pageSize], () => { page.value = 1 })
const drawer = ref(false)
const editing = ref<any>(null)
const form = reactive<any>({ name: '', category: 'OT', protocol: 'OPC UA', status: 'connected' })
const fileInput = ref<HTMLInputElement>()

async function load() {
  list.value = await api.sources()
  if (!Object.keys(protocols.value).length) {
    protocols.value = await api.protocols()
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', category: 'OT', protocol: 'OPC UA', status: 'connected' })
  drawer.value = true
}

function openEdit(row: any) {
  editing.value = row
  const config = JSON.parse(row.configJson || '{}')
  Object.assign(form, { ...row, ...config })
  drawer.value = true
}

async function save() {
  if (!form.name || !form.protocol) {
    ElMessage.warning('请填写名称与协议')
    return
  }
  const keys = protocols.value[form.protocol] || []
  const config: any = {}
  keys.forEach(k => { config[k] = form[k] ?? '' })
  if (form.jdbcUrl) config.jdbcUrl = form.jdbcUrl
  if (form.url) config.url = form.url
  const payload: any = {
    name: form.name, category: form.category, protocol: form.protocol,
    status: form.status || 'connected', config
  }
  if (editing.value) {
    payload.id = editing.value.id
    await api.updateSource(editing.value.id, payload)
    ElMessage.success('已更新数据源')
  } else {
    await api.createSource(payload)
    ElMessage.success('已创建数据源')
  }
  drawer.value = false
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除数据源「${row.name}」？`, '提示', { type: 'warning' })
  await api.deleteSource(row.id)
  ElMessage.success('已删除')
  load()
}

async function test(row: any) {
  const result: any = await api.testSource(row.id)
  ElMessage({ type: result.success ? 'success' : 'error', message: result.message })
  load()
}

async function importFile(event: any) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const payload = JSON.parse(await file.text())
    await request.post('/datasources/import', payload)
    ElMessage.success('导入成功')
    load()
  } catch (e: any) {
    ElMessage.error('导入失败：' + e.message)
  }
  event.target.value = ''
}

function download(path: string) {
  const token = localStorage.getItem('tls_token')
  fetch(path, { headers: { Authorization: `Bearer ${token}` } })
    .then(r => r.blob())
    .then(blob => {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      const disposition = '' // 浏览器同源下载直接使用响应文件名
      a.download = path.split('/').pop() || 'export.json'
      void disposition
      a.click()
      URL.revokeObjectURL(a.href)
    })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">数据源管理</h1>
        <div class="page-desc">统一维护 IT / OT 数据源，节点配置时按协议自动过滤下拉候选</div>
      </div>
      <div style="display:flex;align-items:center;gap:8px">
        <el-button @click="download('/api/v1/datasources/export')">导出全部</el-button>
        <el-button @click="fileInput?.click()">导入</el-button>
        <input ref="fileInput" type="file" accept="application/json" style="display:none" @change="importFile" />
        <el-button type="primary" @click="openCreate" style="margin-left:14px">新建数据源</el-button>
      </div>
    </div>

    <div class="card">
      <el-input v-model="keyword" placeholder="搜索名称或协议" style="width:260px" clearable />
      <el-table :data="paged" size="small" style="margin-top:12px">
        <el-table-column prop="id" label="ID" width="110" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="category" label="类型" width="80" />
        <el-table-column prop="protocol" label="协议" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <span :class="['status-dot', row.status === 'connected' ? 'status-success' : 'status-failed']" />
            {{ row.status === 'connected' ? '已连接' : '异常' }}
          </template>
        </el-table-column>
        <el-table-column prop="lastTest" label="最近测试" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="test(row)">测试</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="download(`/api/v1/datasources/${row.id}/export`)">导出</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager-row">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="filtered.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </div>

    <el-drawer v-model="drawer" :title="editing ? '编辑数据源' : '新建数据源'" size="520px">
      <el-form label-position="top">
        <el-form-item label="数据源名称"><el-input v-model="form.name" placeholder="例如：OPC UA-反应釜温度" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category"><el-option label="OT" value="OT" /><el-option label="IT" value="IT" /></el-select>
        </el-form-item>
        <el-form-item label="协议">
          <el-select v-model="form.protocol"><el-option v-for="(_, p) in protocols" :key="p" :label="p" :value="p" /></el-select>
        </el-form-item>
        <el-form-item v-for="field in (protocols[form.protocol] || [])" :key="field" :label="field">
          <el-input v-model="form[field]" :placeholder="field" />
        </el-form-item>
        <el-form-item label="JDBC URL（数据库协议可选）"><el-input v-model="form.jdbcUrl" placeholder="jdbc:mysql://host:3306/db" /></el-form-item>
        <el-form-item label="HTTP 地址（HTTP 协议可选）"><el-input v-model="form.url" placeholder="https://..." /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>
