<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const tab = ref('credentials')
const credentials = ref<any[]>([])
const variables = ref<any[]>([])
const drawer = ref(false)
const editing = ref<any>(null)
const form = reactive<any>({ name: '', key: '', type: 'API Key', scope: 'prod', value: '', description: '' })

/** 变量作用域：__all__ 表示全部项目，其余为项目 ID；可多选。 */
const ALL_SCOPE = '__all__'
const scopeSel = ref<string[]>([ALL_SCOPE])
const scopeOptions = () => [
  { label: '全部项目', value: ALL_SCOPE },
  ...store.projects.map(p => ({ label: p.name, value: p.id }))
]
function setScopeFromValue(value: any) {
  const raw = String(value || '')
  scopeSel.value = (!raw || raw === 'all' || raw === 'global' || raw === ALL_SCOPE)
    ? [ALL_SCOPE]
    : raw.split(',').map(s => s.trim()).filter(Boolean)
}
function scopePayload() {
  return scopeSel.value.includes(ALL_SCOPE) ? 'all' : scopeSel.value.join(',')
}
function scopeText(value: any) {
  const raw = String(value || '')
  if (!raw || raw === 'all' || raw === 'global' || raw === ALL_SCOPE) return '全部项目'
  return raw.split(',').map(id => store.projects.find(p => p.id === id)?.name || id).join('、')
}
watch(scopeSel, values => {
  if (values.includes(ALL_SCOPE) && values.length > 1) {
    scopeSel.value = values[values.length - 1] === ALL_SCOPE ? [ALL_SCOPE] : values.filter(v => v !== ALL_SCOPE)
  }
})

async function load() {
  await store.loadProjects()
  credentials.value = await api.credentials()
  variables.value = await api.variables()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', key: '', type: 'API Key', scope: 'prod', value: '', description: '' })
  scopeSel.value = [ALL_SCOPE]
  drawer.value = true
}

function openEdit(row: any) {
  editing.value = row
  Object.assign(form, { ...row, value: '' })
  setScopeFromValue(row.scope)
  drawer.value = true
}

async function save() {
  if (!form.name) {
    ElMessage.warning('请填写名称')
    return
  }
  const isVariable = tab.value === 'variables'
  const apiCall = isVariable ? api.saveVariable : api.saveCredential
  const payload: any = { ...form }
  if (isVariable) {
    payload.scope = scopePayload()
    // 后端仍保留环境字段以兼容历史数据，前端不再暴露这个无意义字段
    payload.env = 'prod'
  }
  if (editing.value) {
    payload.id = editing.value.id
  }
  await apiCall(payload)
  ElMessage.success('已保存' + (editing.value ? '（留空表示不修改原值）' : ''))
  drawer.value = false
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除「${row.name}」？`, '提示', { type: 'warning' })
  if (tab.value === 'credentials') await api.deleteCredential(row.id)
  else await api.deleteVariable(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">变量与凭据</h1>
        <div class="page-desc">凭据使用 AES-GCM 加密存储，接口永不返回明文；节点配置中以下拉方式引用</div>
      </div>
      <el-button type="primary" @click="openCreate">新建{{ tab === 'credentials' ? '凭据' : '变量' }}</el-button>
    </div>

    <div class="card">
      <el-tabs v-model="tab">
        <el-tab-pane label="凭据" name="credentials">
          <el-table :data="credentials" size="small">
            <el-table-column prop="id" label="ID" width="100" />
            <el-table-column prop="name" label="名称" min-width="180" />
            <el-table-column prop="type" label="类型" width="120" />
            <el-table-column prop="scope" label="作用域" width="120" />
            <el-table-column prop="mask" label="值" width="160" />
            <el-table-column prop="updatedAt" label="更新时间" width="180" />
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="变量" name="variables">
          <el-table :data="variables" size="small">
            <el-table-column prop="id" label="ID" width="100" />
            <el-table-column prop="name" label="名称" width="160" />
            <el-table-column prop="key" label="键" width="180" />
            <el-table-column prop="mask" label="值" width="160" />
            <el-table-column label="作用域" min-width="180">
              <template #default="{ row }">{{ scopeText(row.scope) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer v-model="drawer" :title="`${editing ? '编辑' : '新建'}${tab === 'credentials' ? '凭据' : '变量'}`" size="480px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <template v-if="tab === 'credentials'">
          <el-form-item label="类型"><el-input v-model="form.type" /></el-form-item>
          <el-form-item label="作用域"><el-input v-model="form.scope" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="键"><el-input v-model="form.key" placeholder="TEMP_THRESHOLD" /></el-form-item>
          <el-form-item label="作用域">
            <el-select v-model="scopeSel" multiple collapse-tags collapse-tags-tooltip
                       placeholder="选择项目（可多选）" style="width:100%">
              <el-option v-for="o in scopeOptions()" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <div class="muted" style="font-size:11px;margin-top:4px">选择「全部项目」表示该变量对所有项目生效</div>
          </el-form-item>
        </template>
        <el-form-item :label="editing ? '新值（留空不修改）' : '值'">
          <el-input v-model="form.value" type="password" show-password />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawer = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-drawer>
  </div>
</template>
