<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="规则类型">
          <el-select v-model="query.ruleType" clearable placeholder="全部类型" style="width: 160px" @change="handleSearch">
            <el-option v-for="t in templates" :key="t.type" :label="t.name" :value="t.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="规则名称/编码" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增规则</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="ruleName" label="规则名称" min-width="140" />
      <el-table-column prop="ruleCode" label="规则编码" min-width="120" />
      <el-table-column label="模板类型" min-width="120">
        <template #default="{ row }">
          <el-tag>{{ typeName(row.ruleType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="稽核对象" min-width="180">
        <template #default="{ row }">
          {{ row.databaseName }}.{{ row.tableName }}<template v-if="row.columnName">.{{ row.columnName }}</template>
        </template>
      </el-table-column>
      <el-table-column prop="weight" label="权重" width="70" />
      <el-table-column prop="alertThreshold" label="告警阈值" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑规则' : '新增规则'" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" placeholder="请输入规则名称" />
        </el-form-item>
        <el-form-item label="规则编码" prop="ruleCode">
          <el-input v-model="form.ruleCode" placeholder="请输入规则编码" />
        </el-form-item>
        <el-form-item label="模板类型" prop="ruleType">
          <el-select v-model="form.ruleType" style="width: 100%" @change="onTypeChange">
            <el-option v-for="t in templates" :key="t.type" :label="t.name" :value="t.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源" prop="datasourceId">
          <el-select v-model="form.datasourceId" style="width: 100%" filterable @change="onDsChange">
            <el-option v-for="d in dsOptions" :key="d.id" :label="d.dsName" :value="d.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库" prop="databaseName">
          <el-select v-model="form.databaseName" style="width: 100%" filterable @change="onDbChange">
            <el-option v-for="db in dbOptions" :key="db" :label="db" :value="db" />
          </el-select>
        </el-form-item>
        <el-form-item label="表" prop="tableName">
          <el-select v-model="form.tableName" style="width: 100%" filterable @change="onTableChange">
            <el-option v-for="t in tableOptions" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="needsColumn" label="字段" prop="columnName">
          <el-select v-model="form.columnName" style="width: 100%" filterable clearable>
            <el-option v-for="c in columnOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.ruleType === 'VALUE_RANGE'" label="值域范围">
          <div style="display: flex; gap: 8px; width: 100%">
            <el-input-number v-model="minValue" placeholder="最小值" :controls="false" style="flex: 1" />
            <el-input-number v-model="maxValue" placeholder="最大值" :controls="false" style="flex: 1" />
          </div>
        </el-form-item>
        <el-form-item v-if="form.ruleType === 'CUSTOM_SQL' || form.ruleType === 'CUSTOM_PROBE'" label="自定义SQL" prop="customSql">
          <el-input v-model="customSql" type="textarea" :rows="4" placeholder="请输入稽核 SQL（仅允许 SELECT）" />
        </el-form-item>
        <el-form-item v-if="form.ruleType === 'PIPELINE_COUNT'" label="阶段采样SQL">
          <el-input v-model="metricsSql" type="textarea" :rows="4"
            placeholder="SELECT stage, records_in, records_out FROM dk_pipeline_stage_count WHERE job_id = '${jobId}' ORDER BY stage_order" />
          <span class="hint">须返回 stage / records_in / records_out；有 stage_order 时按其排序。相邻阶段「上游输出 - 下游输入」计入计数偏差</span>
        </el-form-item>
        <el-form-item v-if="form.ruleType === 'PIPELINE_COUNT'" label="Flink 作业ID">
          <el-input v-model="metricsJobId" placeholder="可选，替换采样 SQL 中的 ${jobId}" />
          <span class="hint">留空时采样 SQL 不能包含 ${jobId} 占位，例如按全局最新批次对账</span>
        </el-form-item>
        <el-form-item label="告警阈值" prop="alertThreshold">
          <el-input-number v-model="form.alertThreshold" :precision="4" :step="0.01" :min="0" style="width: 200px" />
          <span class="hint">空值率/违规率/管道计数偏差率超过该值即告警</span>
        </el-form-item>
        <el-form-item label="权重" prop="weight">
          <el-input-number v-model="form.weight" :min="1" :max="100" style="width: 200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
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
import { createRule, deleteRule, pageRules, ruleTemplateOptions, updateRule } from '@/api/dquality'
import { pageDatasources, listDatabases, listTables, listColumns } from '@/api/datasource'
import type { DqRule, MetaDatasource } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const list = ref<DqRule[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, ruleType: undefined, keyword: '' })

const templates = ref<{ type: string; name: string; desc: string }[]>([])
const dsOptions = ref<MetaDatasource[]>([])
const dbOptions = ref<string[]>([])
const tableOptions = ref<string[]>([])
const columnOptions = ref<string[]>([])

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const isEdit = ref(false)
const form = reactive<any>({ ruleType: 'NOT_NULL', weight: 1, alertThreshold: 0, status: 1 })
const customSql = ref('')
const metricsSql = ref('')
const metricsJobId = ref('')
const minValue = ref<number | undefined>()
const maxValue = ref<number | undefined>()

const needsColumn = computed(() => ['NOT_NULL', 'UNIQUE', 'VALUE_RANGE'].includes(form.ruleType))

const formRules: FormRules = {
  ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择模板类型', trigger: 'change' }],
  datasourceId: [{ required: true, message: '请选择数据源', trigger: 'change' }],
  databaseName: [{ required: true, message: '请选择数据库', trigger: 'change' }],
  tableName: [{ required: true, message: '请选择表', trigger: 'change' }],
}

function typeName(type: string) {
  return templates.value.find((t) => t.type === type)?.name || type
}

async function load() {
  loading.value = true
  try {
    const data = await pageRules({ ...query })
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
  query.ruleType = undefined
  query.keyword = ''
  handleSearch()
}

async function loadTemplates() {
  templates.value = await ruleTemplateOptions()
}

async function loadDs() {
  const data = await pageDatasources({ current: 1, size: 100 })
  dsOptions.value = data.records || []
}

async function onDsChange() {
  form.databaseName = ''
  form.tableName = ''
  form.columnName = ''
  dbOptions.value = []
  tableOptions.value = []
  columnOptions.value = []
  if (form.datasourceId) {
    dbOptions.value = await listDatabases(form.datasourceId)
  }
}

async function onDbChange() {
  form.tableName = ''
  form.columnName = ''
  tableOptions.value = []
  columnOptions.value = []
  if (form.datasourceId && form.databaseName) {
    tableOptions.value = await listTables(form.datasourceId, form.databaseName)
  }
}

async function onTableChange() {
  form.columnName = ''
  columnOptions.value = []
  if (form.datasourceId && form.databaseName && form.tableName && needsColumn.value) {
    columnOptions.value = await listColumns(form.datasourceId, form.databaseName, form.tableName)
  }
}

function onTypeChange() {
  form.columnName = ''
  columnOptions.value = []
  if (form.datasourceId && form.databaseName && form.tableName && needsColumn.value) {
    onTableChange()
  }
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, { ruleName: '', ruleCode: '', ruleType: 'NOT_NULL', datasourceId: '', databaseName: '', tableName: '', columnName: '', weight: 1, alertThreshold: 0, status: 1 })
  customSql.value = ''
  metricsSql.value = ''
  metricsJobId.value = ''
  minValue.value = undefined
  maxValue.value = undefined
  dbOptions.value = []
  tableOptions.value = []
  columnOptions.value = []
  dialogVisible.value = true
}

function openEdit(row: DqRule) {
  isEdit.value = true
  Object.assign(form, { ...row })
  // 解析配置 JSON
  customSql.value = ''
  metricsSql.value = ''
  metricsJobId.value = ''
  minValue.value = undefined
  maxValue.value = undefined
  if (row.ruleConfig) {
    try {
      const cfg = JSON.parse(row.ruleConfig)
      customSql.value = cfg.customSql || ''
      metricsSql.value = cfg.metricsSql || ''
      metricsJobId.value = cfg.jobId || ''
      minValue.value = cfg.min
      maxValue.value = cfg.max
    } catch {
      /* ignore */
    }
  }
  // 加载级联数据
  if (row.datasourceId) {
    listDatabases(row.datasourceId).then((dbs) => {
      dbOptions.value = dbs
      if (row.databaseName) {
        listTables(row.datasourceId!, row.databaseName).then((tables) => {
          tableOptions.value = tables
          if (row.tableName && needsColumn.value) {
            listColumns(row.datasourceId!, row.databaseName!, row.tableName).then((cols) => {
              columnOptions.value = cols
            })
          }
        })
      }
    })
  }
  dialogVisible.value = true
}

async function submit() {
  await formRef.value?.validate()
  if (form.ruleType === 'PIPELINE_COUNT' && !metricsSql.value.trim()) {
    ElMessage.warning('管道阶段计数规则需要填写阶段采样 SQL')
    return
  }
  if (form.ruleType === 'PIPELINE_COUNT' && metricsSql.value.includes('${jobId}') && !metricsJobId.value.trim()) {
    ElMessage.warning('采样 SQL 使用了 ${jobId} 占位，请填写 Flink 作业ID')
    return
  }
  // 组装 ruleConfig
  const cfg: Record<string, any> = {}
  if (form.ruleType === 'VALUE_RANGE') {
    cfg.min = minValue.value
    cfg.max = maxValue.value
  }
  if (form.ruleType === 'CUSTOM_SQL' || form.ruleType === 'CUSTOM_PROBE') {
    cfg.customSql = customSql.value
  }
  if (form.ruleType === 'PIPELINE_COUNT') {
    cfg.metricsSql = metricsSql.value
    if (metricsJobId.value.trim()) cfg.jobId = metricsJobId.value.trim()
  }
  submitting.value = true
  try {
    const payload = { ...form, ruleConfig: JSON.stringify(cfg) }
    if (isEdit.value) {
      await updateRule(form.id, payload)
    } else {
      await createRule(payload)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: DqRule) {
  await ElMessageBox.confirm(`确认删除规则「${row.ruleName}」吗？`, '提示', { type: 'warning' })
  await deleteRule(row.id!)
  ElMessage.success('删除成功')
  load()
}

onMounted(() => {
  loadTemplates()
  loadDs()
  load()
})
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
.hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
</style>