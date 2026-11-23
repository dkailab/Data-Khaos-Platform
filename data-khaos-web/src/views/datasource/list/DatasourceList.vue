<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="名称/地址/类型" clearable style="width: 200px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增数据源</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="dsName" label="数据源名称" min-width="140" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag>{{ dsTypeText(row.dsType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="host" label="主机" min-width="140" />
      <el-table-column prop="port" label="端口" width="80" />
      <el-table-column prop="databaseName" label="数据库" min-width="120" />
      <el-table-column prop="username" label="用户名" min-width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170" />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="success" @click="testById(row)">测试</el-button>
          <el-button link type="warning" @click="sync(row)">同步元数据</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      v-model:current-page="query.current"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      @change="load"
    />

    <!-- 新增 / 编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑数据源' : '新增数据源'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="数据源名称" prop="dsName">
          <el-input v-model="form.dsName" placeholder="请输入数据源名称" />
        </el-form-item>
        <el-form-item label="类型" prop="dsType">
          <el-select v-model="form.dsType" style="width: 100%">
            <el-option v-for="t in dsTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机" prop="host">
          <el-input v-model="form.host" placeholder="请输入主机地址" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item :label="form.dsType === 'DM8' ? '目标模式' : '数据库'" prop="databaseName">
          <el-input v-model="form.databaseName" :placeholder="form.dsType === 'DM8' ? '留空使用登录用户的默认模式' : '请输入默认数据库'" />
        </el-form-item>
        <el-form-item v-if="form.dsType === 'HIVE'" label="Hive 认证">
          <el-select v-model="hiveAuth" style="width:100%">
            <el-option label="默认（NONE）" value="NONE" />
            <el-option label="NOSASL" value="NOSASL" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item :label="isEdit ? '新密码' : '密码'" prop="password">
          <el-input v-model="form.password" type="password" show-password :placeholder="isEdit ? '留空表示不修改' : '请输入密码'" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button :loading="testing" @click="testConfig">测试连接</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { createDatasource, deleteDatasource, pageDatasources, testDatasourceById, testDatasourceConfig, updateDatasource } from '@/api/datasource'
import { syncMetadata } from '@/api/metadata'
import type { MetaDatasource } from '@/types'

const dsTypes = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: '达梦 DM8', value: 'DM8' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Hive', value: 'HIVE' },
  { label: 'Doris', value: 'DORIS' },
  { label: 'ClickHouse', value: 'CLICKHOUSE' },
  { label: '星环 Transwarp', value: 'TRANSWARP' },
  { label: 'Oracle', value: 'ORACLE' },
]

function dsTypeText(t?: string) {
  return dsTypes.find((d) => d.value === t)?.label ?? t ?? '-'
}

const loading = ref(false)
const submitting = ref(false)
const testing = ref(false)
const list = ref<MetaDatasource[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, keyword: '' })

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const isEdit = ref(false)
const form = reactive<MetaDatasource>({ status: 1, dsType: 'MYSQL', port: 3306 })
function datasourceProperties(): Record<string, unknown> {
  try {
    return JSON.parse(form.properties || '{}') || {}
  } catch {
    return {}
  }
}
const hiveAuth = computed({
  get: () => datasourceProperties().hiveAuth === 'NOSASL' ? 'NOSASL' : 'NONE',
  set: value => { form.properties = JSON.stringify({ ...datasourceProperties(), hiveAuth: value }) },
})
const formRules: FormRules = {
  dsName: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  dsType: [{ required: true, message: '请选择类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const data = await pageDatasources({ ...query })
    list.value = data.records
    total.value = Number(data.total)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.current = 1
  load()
}

function handleReset() {
  query.keyword = ''
  handleSearch()
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, { dsName: '', dsType: 'MYSQL', host: '', port: 3306, databaseName: '', username: '', password: '', properties: undefined, status: 1 })
  dialogVisible.value = true
}

function openEdit(row: MetaDatasource) {
  isEdit.value = true
  Object.assign(form, { properties: undefined, ...row, password: undefined })
  dialogVisible.value = true
}

async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateDatasource({ ...form })
    } else {
      await createDatasource({ ...form })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: MetaDatasource) {
  await ElMessageBox.confirm(`确认删除数据源「${row.dsName}」吗？`, '提示', { type: 'warning' })
  await deleteDatasource(row.id!)
  ElMessage.success('删除成功')
  load()
}

async function testConfig() {
  testing.value = true
  try {
    const ok = await testDatasourceConfig({ ...form } as any)
    ElMessage.success(ok ? '连接成功' : '连接失败')
  } finally {
    testing.value = false
  }
}

async function testById(row: MetaDatasource) {
  ElMessage.info('正在测试连接...')
  try {
    const ok = await testDatasourceById(row.id!)
    ElMessage.success(ok ? '连接成功' : '连接失败')
  } catch {
    ElMessage.error('连接失败')
  }
}

async function sync(row: MetaDatasource) {
  await ElMessageBox.confirm(`确认同步数据源「${row.dsName}」的元数据吗？`, '提示', { type: 'warning' })
  await syncMetadata(row.id!)
  ElMessage.success('元数据同步完成')
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
