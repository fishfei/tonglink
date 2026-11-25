<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import request from '../api'

const list = ref<any[]>([])
const credentialTypes = ref<any[]>([])
const keyword = ref('')
const page = ref(1)
const pageSize = ref(10)
const filtered = computed(() => list.value.filter(s =>
  !keyword.value || s.name?.includes(keyword.value) || s.protocol?.includes(keyword.value)
    || s.credentialTypeLabel?.includes(keyword.value)))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([keyword, pageSize], () => { page.value = 1 })

const drawer = ref(false)
const editing = ref<any>(null)
const testing = ref(false)
const form = reactive<any>({ name: '', credentialType: '', credentialFields: {}, config: {}, status: 'connected' })
const fileInput = ref<HTMLInputElement>()

/**
 * 测试连接的提示只保留一条：每次测试先关掉上一条，避免失败提示一直堆叠在页面上。
 * duration 设为有限值，成功后也不会永久停留。
 */
let testMessage: ReturnType<typeof ElMessage> | null = null
function clearTestMessage() {
  testMessage?.close()
  testMessage = null
}
function showTestMessage(success: boolean, message: string) {
  clearTestMessage()
  testMessage = ElMessage({
    type: success ? 'success' : 'error',
    message,
    duration: 5000,
    showClose: true,
    onClose: () => { testMessage = null }
  })
}

/**
 * 一个数据源只由「类型」定义：连接地址与认证信息都在同一组字段里，
 * 不再单独填写与凭证重复的连接参数。类别和协议由后端按类型推导。
 */
const currentType = computed(() =>
  credentialTypes.value.find(t => t.key === form.credentialType) || credentialTypes.value[0])
const typeFields = computed<any[]>(() => currentType.value?.fields || [])
const typeLabel = (row: any) => row.credentialTypeLabel || row.credentialType || '—'

/** 切换类型时清空上一类型的字段，避免把无关参数带过去。 */
function onTypeChange() {
  form.credentialFields = {}
}

async function load() {
  list.value = await api.sources()
  credentialTypes.value = await api.credentialTypes()
}

function openCreate() {
  clearTestMessage()
  editing.value = null
  Object.assign(form, {
    name: '', credentialType: credentialTypes.value[0]?.key || '',
    credentialFields: {}, config: {}, status: 'connected'
  })
  drawer.value = true
}

function openEdit(row: any) {
  clearTestMessage()
  editing.value = row
  Object.assign(form, {
    name: row.name,
    credentialType: row.credentialType || credentialTypes.value[0]?.key || '',
    credentialFields: { ...(row.credentialFields || {}) },
    config: { ...(row.config || {}) },
    status: row.status
  })
  drawer.value = true
}

function validateForm(): boolean {
  if (!form.name) {
    ElMessage.warning('请填写数据源名称')
    return false
  }
  if (!form.credentialType) {
    ElMessage.warning('请选择连接类型')
    return false
  }
  const missing = typeFields.value.filter((f: any) => f.required && !String(form.credentialFields[f.key] ?? '').trim())
  if (missing.length) {
    ElMessage.warning('请填写：' + missing.map((f: any) => f.label).join('、'))
    return false
  }
  if (form.credentialType === 'database' && !String(form.credentialFields.jdbcUrl ?? '').trim()) {
    if (!String(form.credentialFields.host ?? '').trim()) {
      ElMessage.warning('请填写主机，或填写完整 JDBC URL')
      return false
    }
    // MySQL/PostgreSQL 不填写库名也能连接实例；Oracle/SQL Server 仍需服务名或库名
    const driver = String(form.credentialFields.driver || 'MySQL')
    if (['Oracle', 'SQL Server'].includes(driver) && !String(form.credentialFields.database ?? '').trim()) {
      ElMessage.warning('请填写数据库名（服务名）')
      return false
    }
  }
  return true
}

function formPayload() {
  return {
    // 编辑态携带 id：后端据此把掩码密码还原为已保存的解密值后再测试
    id: editing.value?.id || undefined,
    name: form.name,
    credentialType: form.credentialType,
    credentialFields: { ...form.credentialFields },
    config: { ...form.config },
    status: form.status || 'connected'
  }
}

/** 保存前直接测试表单里的连接参数，避免先保存再反复修改。 */
async function testDraft() {
  if (!validateForm()) return
  clearTestMessage()
  testing.value = true
  try {
    const result: any = await api.testSourceDraft(formPayload())
    showTestMessage(result.success, result.message)
  } finally {
    testing.value = false
  }
}

async function save() {
  if (!validateForm()) return
  const payload: any = formPayload()
  if (editing.value) {
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
  await ElMessageBox.confirm(`确认删除数据源「${row.name}」？引用它的节点将失去连接或认证信息。`, '提示', { type: 'warning' })
  await api.deleteSource(row.id)
  ElMessage.success('已删除')
  load()
}

async function test(row: any) {
  clearTestMessage()
  const result: any = await api.testSource(row.id)
  showTestMessage(result.success, result.message)
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
      a.download = path.split('/').pop() || 'export.json'
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
        <div class="page-desc">按类型统一填写连接与认证参数；节点配置中的连接、认证字段都从这里选择</div>
      </div>
      <div style="display:flex;align-items:center;gap:8px">
        <el-button @click="download('/api/v1/datasources/export')">导出全部</el-button>
        <el-button @click="fileInput?.click()">导入</el-button>
        <input ref="fileInput" type="file" accept="application/json" style="display:none" @change="importFile" />
        <el-button type="primary" @click="openCreate" style="margin-left:14px">新建数据源</el-button>
      </div>
    </div>

    <div class="card">
      <el-input v-model="keyword" placeholder="搜索名称、类型或协议" style="width:280px" clearable />
      <el-table :data="paged" size="small" style="margin-top:12px">
        <el-table-column prop="id" label="ID" width="110" />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="连接类型" min-width="170">
          <template #default="{ row }">{{ typeLabel(row) }}</template>
        </el-table-column>
        <el-table-column prop="protocol" label="协议" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <span :class="['status-dot', row.status === 'connected' ? 'status-success' : 'status-failed']" />
            {{ row.status === 'connected' ? '已连接' : '异常' }}
          </template>
        </el-table-column>
        <el-table-column prop="lastTest" label="最近测试" width="170" />
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

    <el-drawer v-model="drawer" :title="editing ? '编辑数据源' : '新建数据源'" size="560px">
      <el-form label-position="top">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="数据源名称">
          <el-input v-model="form.name" placeholder="例如：OPC UA-反应釜温度" />
        </el-form-item>
        <el-form-item label="连接类型">
          <el-select v-model="form.credentialType" style="width:100%" placeholder="选择连接类型" @change="onTypeChange">
            <el-option v-for="t in credentialTypes" :key="t.key" :label="t.label" :value="t.key" />
          </el-select>
          <div v-if="currentType" class="muted" style="font-size:11px;margin-top:4px">
            类别 {{ currentType.category }} · 协议 {{ currentType.protocol }}，由类型自动确定，无需重复填写
          </div>
        </el-form-item>

        <el-divider content-position="left">连接与认证参数</el-divider>
        <el-form-item v-for="f in typeFields" :key="f.key" :label="f.label" :required="f.required">
          <el-select v-if="f.type === 'select'" v-model="form.credentialFields[f.key]" style="width:100%">
            <el-option v-for="o in f.options || []" :key="o" :label="o" :value="o" />
          </el-select>
          <el-input v-else-if="f.type === 'password'" v-model="form.credentialFields[f.key]"
                    type="password" show-password :placeholder="f.placeholder || ''" />
          <el-input v-else v-model="form.credentialFields[f.key]" :placeholder="f.placeholder || ''" />
        </el-form-item>
        <div class="muted" style="font-size:11px">密码类字段保存后只回显掩码，留空表示不修改原值。</div>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button :loading="testing" @click="testDraft">测试连接</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>
