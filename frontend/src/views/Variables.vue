<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { useAppStore } from '../stores'

const store = useAppStore()
const variables = ref<any[]>([])
const drawer = ref(false)
const editing = ref<any>(null)
const form = reactive<any>({ name: '', key: '', value: '', scope: 'global', description: '' })

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
  variables.value = await api.variables()
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', key: '', value: '', scope: 'global', description: '' })
  scopeSel.value = [ALL_SCOPE]
  drawer.value = true
}

function openEdit(row: any) {
  editing.value = row
  Object.assign(form, { ...row, value: row.value ?? '' })
  setScopeFromValue(row.scope)
  drawer.value = true
}

async function save() {
  if (!form.name || !form.key) {
    ElMessage.warning('请填写名称与键')
    return
  }
  const payload: any = { ...form, scope: scopePayload(), env: 'prod' }
  if (editing.value) {
    payload.id = editing.value.id
    await api.updateVariable(editing.value.id, payload)
  } else {
    await api.saveVariable(payload)
  }
  ElMessage.success('变量已保存（按所选项目隔离）')
  drawer.value = false
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除变量「${row.name}」？`, '提示', { type: 'warning' })
  await api.deleteVariable(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">变量管理</h1>
        <div class="page-desc">变量按项目作用域隔离，流程编排时通过 $vars.键 直接引用；连接与认证信息请到「数据源」配置</div>
      </div>
      <el-button type="primary" @click="openCreate">新建变量</el-button>
    </div>

    <div class="card">
      <el-table :data="variables" size="small">
        <el-table-column prop="id" label="ID" width="100" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column prop="key" label="键" width="180" />
        <el-table-column prop="value" label="值" min-width="180" show-overflow-tooltip />
        <el-table-column label="作用域" min-width="180">
          <template #default="{ row }">{{ scopeText(row.scope) }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" :title="`${editing ? '编辑' : '新建'}变量`" size="480px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="键"><el-input v-model="form.key" placeholder="TEMP_THRESHOLD" /></el-form-item>
        <el-form-item label="值">
          <el-input v-model="form.value" placeholder="变量值，流程中通过 $vars.键 引用" />
        </el-form-item>
        <el-form-item label="作用域">
          <el-select v-model="scopeSel" multiple collapse-tags collapse-tags-tooltip
                     placeholder="选择项目（可多选）" style="width:100%">
            <el-option v-for="o in scopeOptions()" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div class="muted" style="font-size:11px;margin-top:4px">选择「全部项目」表示该变量对所有项目生效</div>
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
