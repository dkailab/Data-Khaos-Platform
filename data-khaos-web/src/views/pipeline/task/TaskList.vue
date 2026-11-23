<template>
  <el-card shadow="never" class="pipeline-page">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="任务名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="引擎">
          <el-select v-model="query.engine" clearable placeholder="全部" style="width: 160px" @change="handleSearch">
            <el-option v-for="e in engineOptions" :key="e.type" :label="e.name" :value="e.type" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 110px" @change="handleSearch">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目组">
          <el-select
            v-model="filterGroupId"
            clearable
            placeholder="全部项目组"
            style="width: 180px"
            :loading="groupOptionsLoading"
            @change="handleGroupChange"
          >
            <el-option label="全部项目组" value="" />
            <el-option v-for="g in groupOptions" :key="g.id" :label="g.projectName || g.projectCode || g.id" :value="g.id!" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建同步任务</el-button>
    </div>

    <el-table v-loading="loading" :data="visibleList" border stripe>
      <el-table-column prop="taskName" label="任务名称" min-width="150" show-overflow-tooltip />
      <el-table-column label="项目组" width="150">
        <template #default="{ row }">
          <span>{{ groupName(row.projectGroupId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag>{{ taskTypeText(row.taskType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="引擎" width="150">
        <template #default="{ row }">
          <el-tag :type="engineTagType(row.engine)">{{ engineText(row.engine) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="源 → 目标" min-width="220">
        <template #default="{ row }">
          <span class="arrow">{{ dsName(row.sourceDsId) }}.{{ row.sourceTable }}</span>
          <span class="arrow-sep">→</span>
          <span class="arrow">{{ dsName(row.targetDsId) }}.{{ row.targetTable }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="cronExpr" label="定时表达式" min-width="120">
        <template #default="{ row }">
          <span>{{ row.cronExpr || '仅手动' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170" />
      <el-table-column label="操作" width="430" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!row.sourceDsId || !row.sourceTable" @click="openProbeRow(row, 'source')">探数</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status !== 1" link type="success" @click="handleEnable(row)">启用</el-button>
          <el-button v-else link type="warning" @click="handleDisable(row)">停用</el-button>
          <el-button v-if="canPreflight(row)" link type="primary" @click="preflightTask = row">预检</el-button>
          <el-button link type="info" @click="handleRun(row)">运行</el-button>
          <el-button link type="primary" @click="openInstances(row)">实例</el-button>
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

    <!-- ============ 新建 / 编辑：分步向导 ============ -->
    <el-dialog
      v-model="wizardVisible"
      :title="isEdit ? '编辑同步任务' : '新建同步任务'"
      width="920px"
      destroy-on-close
      class="dk-wizard"
      align-center
    >
      <div class="wizard-header">
        <div class="wh-title">{{ isEdit ? '编辑同步任务' : '配置数据同步' }}</div>
        <div class="wh-sub">源数据 → 同步引擎 → 目标数据，三步完成离线同步</div>
      </div>

      <el-steps :active="wizardStep" align-center finish-status="success" class="wizard-steps">
        <el-step title="数据源" :description="sourceType ? dbTypeText(sourceType) : '选择源类型'" />
        <el-step title="去向" :description="targetType ? dbTypeText(targetType) : '选择目标类型'" />
        <el-step title="同步工具" :description="wizardForm.engine ? engineText(wizardForm.engine) : '选择引擎'" />
        <el-step title="参数" description="运行参数与调度" />
      </el-steps>

      <!-- 步骤1：数据源 -->
      <div v-show="wizardStep === 0" class="wizard-panel">
        <div class="panel-label">第一步 · 选择数据源（源）</div>
        <div class="type-cards">
          <div
            v-for="t in dbTypes"
            :key="t.value"
            class="type-card"
            :class="{ active: sourceType === t.value }"
            @click="selectSourceType(t.value)"
          >
            <el-icon class="tc-icon" :style="{ color: t.color }"><component :is="t.icon" /></el-icon>
            <div class="tc-name">{{ t.label }}</div>
          </div>
        </div>
        <el-form :model="wizardForm" label-width="90px" class="panel-form">
          <el-form-item label="源数据源">
            <el-select v-model="wizardForm.sourceDsId" filterable placeholder="选择源数据源" style="width: 100%">
              <el-option v-for="d in sourceDsOptions" :key="d.id" :value="d.id" :label="`${d.dsName}（${d.host}:${d.port}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="源表">
            <div class="table-input-row">
              <el-input v-model="wizardForm.sourceTable" placeholder="源表名 / Hive 表（库.表）" />
              <el-button type="primary" plain :disabled="!wizardForm.sourceDsId || !wizardForm.sourceTable" @click="openProbe('source')">探数</el-button>
            </div>
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤2：去向 -->
      <div v-show="wizardStep === 1" class="wizard-panel">
        <div class="panel-label">第二步 · 选择数据去向（目标）</div>
        <div class="type-cards">
          <div
            v-for="t in dbTypes"
            :key="t.value"
            class="type-card"
            :class="{ active: targetType === t.value }"
            @click="selectTargetType(t.value)"
          >
            <el-icon class="tc-icon" :style="{ color: t.color }"><component :is="t.icon" /></el-icon>
            <div class="tc-name">{{ t.label }}</div>
          </div>
        </div>
        <el-form :model="wizardForm" label-width="90px" class="panel-form">
          <el-form-item label="目标数据源">
            <el-select v-model="wizardForm.targetDsId" filterable placeholder="选择目标数据源" style="width: 100%">
              <el-option v-for="d in targetDsOptions" :key="d.id" :value="d.id" :label="`${d.dsName}（${d.host}:${d.port}）`" />
            </el-select>
          </el-form-item>
          <el-form-item label="目标表">
            <div class="table-input-row">
              <el-input v-model="wizardForm.targetTable" placeholder="目标表名" />
              <el-button type="primary" plain :disabled="!wizardForm.targetDsId || !wizardForm.targetTable" @click="openProbe('target')">探数</el-button>
            </div>
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤3：同步工具 -->
      <div v-show="wizardStep === 2" class="wizard-panel">
        <div class="panel-label">第三步 · 选择同步工具</div>
        <div class="engine-cards">
          <div
            v-for="e in engineOptions"
            :key="e.type"
            class="engine-card"
            :class="{ active: wizardForm.engine === e.type }"
            @click="wizardForm.engine = e.type"
          >
            <el-icon class="ec-icon" :style="{ color: engineColor(e.type) }"><component :is="engineIcon(e.type)" /></el-icon>
            <div class="ec-name">{{ e.name }}</div>
            <div class="ec-desc">{{ engineDesc(e.type) }}</div>
            <el-tag :type="e.available ? 'success' : 'info'" size="small" class="ec-tag">
              {{ e.available ? '可用' : '未安装' }}
            </el-tag>
          </div>
        </div>
        <div class="tip">DB-Sync 为内置兜底引擎始终可用；DataX / SeaTunnel 需在引擎环境安装对应 CLI。</div>
      </div>

      <!-- 步骤4：参数 -->
      <div v-show="wizardStep === 3" class="wizard-panel">
        <div class="panel-label">第四步 · 运行参数与调度</div>
        <el-form ref="formRef" :model="wizardForm" :rules="formRules" label-width="110px" class="panel-form">
          <el-form-item label="任务名称" prop="taskName">
            <el-input v-model="wizardForm.taskName" placeholder="请输入任务名称" />
          </el-form-item>
          <el-form-item label="任务类型" prop="taskType">
            <el-radio-group v-model="wizardForm.taskType">
              <el-radio-button value="SYNC">同步 SYNC</el-radio-button>
              <el-radio-button value="ETL">加工 ETL</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="源查询(SQL)">
            <el-input v-model="wizardForm.sourceQuery" type="textarea" :rows="2" placeholder="缺省为 SELECT * FROM 源表（可选）" />
          </el-form-item>
          <el-form-item label="字段映射">
            <el-input v-model="wizardForm.fieldMapping" type="textarea" :rows="2" placeholder='JSON，如 {"col_a":"col_b"}（可选）' />
          </el-form-item>
          <template v-if="wizardForm.engine === 'DB_SYNC' && targetType !== 'HIVE'">
            <el-form-item label="写入策略">
              <el-radio-group v-model="wizardForm.writeMode">
                <el-radio value="APPEND">追加</el-radio>
                <el-radio value="REPLACE_BY_BIZ_DATE">按业务日覆盖</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="wizardForm.writeMode === 'REPLACE_BY_BIZ_DATE'" label="目标日期列" required>
              <el-input v-model="wizardForm.dateColumn" placeholder="例如 stat_date" />
              <div v-if="sourceType === 'HIVE'" class="tip">自定义源查询用 ${bizDate} 过滤。源日期列同名时可省略 SQL，异名时可在引擎配置填 sourceDateColumn。</div>
            </el-form-item>
          </template>
          <el-form-item label="Cron 表达式">
            <el-input v-model="wizardForm.cronExpr" placeholder="如 0 0 2 * * ?（空=仅手动）" />
          </el-form-item>
          <el-form-item label="引擎配置">
            <el-input v-model="wizardForm.config" type="textarea" :rows="2" placeholder='JSON，如 {"timeoutSeconds":600,"batchSize":500}（可选）' />
            <div v-if="wizardForm.engine === 'DB_SYNC' && targetType === 'MYSQL' && wizardForm.writeMode === 'REPLACE_BY_BIZ_DATE'" class="tip">
              目标库已部署物化版本表时，可配置 <code>"serveMaterialization": true</code>。
            </div>
          </el-form-item>
          <el-form-item label="状态">
            <el-radio-group v-model="wizardForm.status">
              <el-radio :value="1">启用</el-radio>
              <el-radio :value="0">停用</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <div class="wizard-footer">
          <el-button v-if="wizardStep > 0" @click="wizardStep--">上一步</el-button>
          <el-button v-if="wizardStep < 3" type="primary" @click="nextStep">下一步</el-button>
          <template v-else>
            <el-button @click="wizardVisible = false">取消</el-button>
            <el-button type="primary" :loading="submitting" @click="submit">保存任务</el-button>
          </template>
        </div>
      </template>
    </el-dialog>

    <PipelinePreflightDialog v-if="preflightTask" :task="preflightTask" @close="preflightTask = undefined" />

    <!-- 执行实例对话框 -->
    <el-dialog v-model="instanceVisible" :title="`执行实例 - ${currentTask?.taskName || ''}`" width="920px">
      <el-table v-loading="instanceLoading" :data="instances" size="small" border max-height="420">
        <el-table-column prop="engine" label="引擎" width="120" />
        <el-table-column label="触发方式" width="90">
          <template #default="{ row }">
            {{ row.triggerType === 'CRON' ? '定时' : '手动' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="instanceTagType(row.status)">{{ instanceText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="95" />
        <el-table-column prop="rows" label="行数" width="80" />
        <el-table-column prop="bizDate" label="业务日期" width="115" />
        <el-table-column prop="errorMessage" label="异常说明" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <!-- 探数对话框 -->
    <el-dialog v-model="probeVisible" :title="`探数 - ${probeLabel}`" width="920px">
      <div v-if="probeLoading" class="probe-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在探数中，请稍候...</span>
      </div>
      <template v-else>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="连接状态">
            <el-tag :type="probeConnectOk ? 'success' : 'danger'">
              {{ probeConnectOk ? '连接成功' : '连接失败' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="表行数">{{ probeRowCount }}</el-descriptions-item>
        </el-descriptions>

        <div class="probe-section">
          <div class="section-title">表字段（{{ probeColumns.length }}）</div>
          <el-table :data="probeColumns" border size="small" max-height="240">
            <el-table-column prop="columnName" label="字段名" width="150" />
            <el-table-column prop="columnType" label="类型" width="120" />
            <el-table-column label="可空" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.nullable" size="small" type="info">是</el-tag>
                <el-tag v-else size="small">否</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.primaryKey" size="small" type="primary">是</el-tag>
                <el-tag v-else size="small">否</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="注释" />
          </el-table>
        </div>

        <div v-if="probeSample.rows && probeSample.rows.length" class="probe-section">
          <div class="section-title">样例数据（前 {{ probeSample.rows.length }} 行）</div>
          <el-table :data="probeSample.rows" border size="small" max-height="280">
            <el-table-column v-for="col in probeSample.columns" :key="col.columnName" :prop="col.columnName" :label="col.columnName" />
          </el-table>
        </div>
      </template>
      <template #footer>
        <el-button @click="probeVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  Coin,
  Connection,
  DataLine,
  Document,
  Files,
  Grid,
  Loading,
  MagicStick,
  Odometer,
  Plus,
  Refresh,
  Search,
  Setting,
  Timer,
} from '@element-plus/icons-vue'
import {
  createPipelineTask,
  deletePipelineTask,
  listPipelineEngines,
  listPipelineInstanceByTask,
  pagePipelineInstances,
  pagePipelineTasks,
  runPipelineTask,
  updatePipelineTask,
} from '@/api/pipeline'
import PipelinePreflightDialog from './PipelinePreflightDialog.vue'
import { executeSql, listColumnInfos, pageDatasources, tableCount, testDatasourceById } from '@/api/datasource'
import { listProjectGroups } from '@/api/permission'
import type { ColumnInfo, MetaDatasource, PipelineEngineInfo, PipelineInstance, PipelineTask, ProjectGroupDto } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const list = ref<PipelineTask[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, keyword: '', engine: undefined, status: undefined })

const wizardVisible = ref(false)
const isEdit = ref(false)
const wizardStep = ref(0)
const formRef = ref<FormInstance>()
const sourceType = ref<string>('MYSQL')
const targetType = ref<string>('MYSQL')
const wizardForm = reactive<PipelineTask>({ taskName: '', taskType: 'SYNC', engine: 'DB_SYNC', status: 1 })

const formRules: FormRules = {
  taskName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
}

const dbTypes = computed(() => [
  { label: 'MySQL', value: 'MYSQL', icon: Grid, color: '#f7941d' },
  { label: 'Hive', value: 'HIVE', icon: Coin, color: '#fbbf24' },
  { label: 'PostgreSQL', value: 'POSTGRESQL', icon: Connection, color: '#336791' },
  { label: 'Doris', value: 'DORIS', icon: Odometer, color: '#3b82f6' },
  { label: 'ClickHouse', value: 'CLICKHOUSE', icon: Files, color: '#f9c74f' },
  { label: 'Oracle', value: 'ORACLE', icon: Document, color: '#e11d48' },
  { label: 'DM8', value: 'DM8', icon: DataLine, color: '#7c3aed' },
  { label: 'Transwarp', value: 'TRANSWARP', icon: MagicStick, color: '#0ea5e9' },
])

const engineOptions = ref<PipelineEngineInfo[]>([])
const dsOptions = ref<MetaDatasource[]>([])

// 项目组名称映射（业务线-项目组隔离展示）
const groupMap = ref<Record<string, string>>({})

// 顶部「项目组」选择器：列表按项目组过滤 + 新建任务归属当前选中项目组
const groupOptions = ref<ProjectGroupDto[]>([])
const groupOptionsLoading = ref(false)
const filterGroupId = ref('')

/** 按选中项目组过滤后的任务列表（空 = 全部项目组） */
const visibleList = computed(() =>
  filterGroupId.value ? list.value.filter((t) => (t.projectGroupId || '') === filterGroupId.value) : list.value,
)

function groupName(id?: string) {
  return (id && groupMap.value[id]) || id || '-'
}

async function loadProjectGroupOptions() {
  if (groupOptionsLoading.value) return
  groupOptionsLoading.value = true
  try {
    const all = (await listProjectGroups()) || []
    groupOptions.value = all
    for (const g of all) {
      if (g.id) groupMap.value[g.id] = g.projectName || g.projectCode || g.id
    }
  } catch {
    groupOptions.value = []
  } finally {
    groupOptionsLoading.value = false
  }
}

function handleGroupChange() {
  load()
}

const instanceVisible = ref(false)
const instanceLoading = ref(false)
const instances = ref<PipelineInstance[]>([])
const currentTask = ref<PipelineTask>()

// 探数状态
const probeVisible = ref(false)
const probeLoading = ref(false)
const probeLabel = ref('')
const probeConnectOk = ref(false)
const probeRowCount = ref(0)
const probeColumns = ref<ColumnInfo[]>([])
const probeSample = ref<{ columns: ColumnInfo[]; rows: Record<string, any>[] }>({ columns: [], rows: [] })

const sourceDsOptions = computed(() => dsOptions.value.filter((d) => d.dsType === sourceType.value))
const targetDsOptions = computed(() => dsOptions.value.filter((d) => d.dsType === targetType.value))

function dbTypeText(t?: string) {
  return dbTypes.value.find((d) => d.value === t)?.label ?? t ?? ''
}

function taskTypeText(t?: string) {
  return { SYNC: '同步', ETL: '加工' }[t ?? ''] ?? t ?? '-'
}

function engineText(t?: string) {
  return engineOptions.value.find((e) => e.type === t)?.name ?? t ?? '-'
}

function engineTagType(t?: string): 'success' | 'warning' | 'info' {
  if (t === 'DB_SYNC') return 'success'
  if (t === 'DATAX' || t === 'SEATUNNEL') return 'warning'
  return 'info'
}

function engineIcon(t?: string) {
  if (t === 'DATAX') return Odometer
  if (t === 'SEATUNNEL') return Coin
  if (t === 'DB_SYNC') return Setting
  return DataLine
}

function engineColor(t?: string) {
  if (t === 'DATAX') return '#f7941d'
  if (t === 'SEATUNNEL') return '#0ea5e9'
  if (t === 'DB_SYNC') return '#10b981'
  return '#6b7280'
}

function engineDesc(t?: string) {
  if (t === 'DB_SYNC') return '内置 JDBC 直连，轻量兜底'
  if (t === 'DATAX') return '阿里开源，离线高吞吐'
  if (t === 'SEATUNNEL') return 'Apache 开源，流批一体'
  return ''
}

function instanceText(s?: number) {
  return { 0: '运行中', 1: '成功', 2: '失败', 3: '提交待核查' }[s ?? -1] ?? '-'
}

function instanceTagType(s?: number): 'success' | 'danger' | 'warning' | 'info' {
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  if (s === 3) return 'warning'
  return 'info'
}

const preflightTask = ref<PipelineTask>()

function canPreflight(task: PipelineTask) {
  if (task.engine !== 'DB_SYNC') return false
  const source = dsOptions.value.find(ds => ds.id === task.sourceDsId)
  const target = dsOptions.value.find(ds => ds.id === task.targetDsId)
  return source?.dsType === 'HIVE' && (target?.dsType === 'MYSQL' || target?.dsType === 'DM8')
}

function dsName(id?: string) {
  return dsOptions.value.find((d) => d.id === id)?.dsName ?? id ?? '-'
}

async function load() {
  loading.value = true
  try {
    const data = await pagePipelineTasks({ ...query })
    list.value = data.records
    total.value = Number(data.total)
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    engineOptions.value = await listPipelineEngines()
  } catch {
    engineOptions.value = []
  }
  try {
    const data = await pageDatasources({ current: 1, size: 200 })
    dsOptions.value = data.records
  } catch {
    dsOptions.value = []
  }
}

function handleSearch() {
  query.current = 1
  load()
}

function handleReset() {
  query.keyword = ''
  query.engine = undefined
  query.status = undefined
  filterGroupId.value = ''
  handleSearch()
}

function selectSourceType(t: string) {
  sourceType.value = t
  wizardForm.sourceDsId = ''
}
function selectTargetType(t: string) {
  targetType.value = t
  wizardForm.targetDsId = ''
}

function openCreate() {
  isEdit.value = false
  wizardStep.value = 0
  sourceType.value = 'MYSQL'
  targetType.value = 'MYSQL'
  Object.assign(wizardForm, { taskName: '', taskType: 'SYNC', engine: 'DB_SYNC', sourceDsId: '', sourceTable: '', targetDsId: '', targetTable: '', sourceQuery: '', fieldMapping: '', writeMode: 'APPEND', dateColumn: '', config: '', cronExpr: '', status: 1, projectGroupId: filterGroupId.value || '' })
  wizardVisible.value = true
}

function openEdit(row: PipelineTask) {
  isEdit.value = true
  wizardStep.value = 0
  Object.assign(wizardForm, { writeMode: 'APPEND', dateColumn: '', ...row })
  wizardForm.writeMode ||= 'APPEND'
  const src = dsOptions.value.find((d) => d.id === row.sourceDsId)
  const tgt = dsOptions.value.find((d) => d.id === row.targetDsId)
  sourceType.value = src?.dsType || 'MYSQL'
  targetType.value = tgt?.dsType || 'MYSQL'
  wizardVisible.value = true
}

function nextStep() {
  if (wizardStep.value === 0) {
    if (!wizardForm.sourceDsId) return ElMessage.warning('请选择源数据源')
    if (!wizardForm.sourceTable) return ElMessage.warning('请输入源表')
  } else if (wizardStep.value === 1) {
    if (!wizardForm.targetDsId) return ElMessage.warning('请选择目标数据源')
    if (!wizardForm.targetTable) return ElMessage.warning('请输入目标表')
  } else if (wizardStep.value === 2) {
    if (!wizardForm.engine) return ElMessage.warning('请选择同步工具')
  }
  wizardStep.value++
}

async function submit() {
  await formRef.value?.validate()
  if (wizardForm.engine === 'DB_SYNC' && wizardForm.writeMode === 'REPLACE_BY_BIZ_DATE' && !wizardForm.dateColumn?.trim()) {
    ElMessage.warning('请填写目标日期列')
    return
  }
  if (wizardForm.engine === 'DB_SYNC' && targetType.value === 'DM8' && sourceType.value !== 'HIVE') {
    ElMessage.warning('达梦目标的 DB-Sync 加载目前支持 Hive 源')
    return
  }
  submitting.value = true
  try {
    const payload = { ...wizardForm } as PipelineTask
    if (isEdit.value) {
      await updatePipelineTask(payload)
    } else {
      await createPipelineTask(payload)
    }
    ElMessage.success('保存成功')
    wizardVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: PipelineTask) {
  await ElMessageBox.confirm(`确认删除任务「${row.taskName}」吗？`, '提示', { type: 'warning' })
  await deletePipelineTask(row.id!)
  ElMessage.success('删除成功')
  load()
}

async function handleEnable(row: PipelineTask) {
  await updatePipelineTask({ id: row.id, status: 1 })
  ElMessage.success('任务已启用')
  load()
}

async function handleDisable(row: PipelineTask) {
  await updatePipelineTask({ id: row.id, status: 0 })
  ElMessage.success('任务已停用')
  load()
}

async function handleRun(row: PipelineTask) {
  try {
    let date: string | undefined
    if (row.writeMode === 'REPLACE_BY_BIZ_DATE' || row.sourceQuery?.includes('${bizDate}')) {
      const result = await ElMessageBox.prompt(`请输入任务「${row.taskName}」的业务日期`, '按业务日加载', {
        inputType: 'date',
        inputPattern: /^\d{4}-\d{2}-\d{2}$/,
        inputErrorMessage: '请选择业务日期',
        confirmButtonText: '提交运行',
      })
      date = (result as unknown as { value?: string }).value
      if (!date) return
    } else {
      await ElMessageBox.confirm(`确认立即运行任务「${row.taskName}」吗？`, '运行任务', { type: 'warning' })
    }
    const instance = await runPipelineTask(row.id!, date)
    ElMessage.success(`已触发运行，实例 ID ${instance.id}`)
  } catch {
    // Cancellation needs no toast; the HTTP client reports submission errors.
  }
}

async function openInstances(row: PipelineTask) {
  currentTask.value = row
  instanceVisible.value = true
  instanceLoading.value = true
  try {
    const data = await listPipelineInstanceByTask(row.id!)
    instances.value = Array.isArray(data) ? data : await pagePipelineInstances({ taskId: row.id, current: 1, size: 50 }).then((r) => r.records)
  } finally {
    instanceLoading.value = false
  }
}

/** 探数：测试连接 + 字段 + 行数 + 样例数据 */
async function openProbe(side: 'source' | 'target') {
  const dsId = side === 'source' ? wizardForm.sourceDsId : wizardForm.targetDsId
  const tableInput = (side === 'source' ? wizardForm.sourceTable : wizardForm.targetTable)?.trim()
  if (!dsId || !tableInput) return
  await startProbe(dsId, tableInput)
}

/** 列表行探数（探源表） */
async function openProbeRow(row: PipelineTask, side: 'source' | 'target') {
  const dsId = side === 'source' ? row.sourceDsId : row.targetDsId
  const tableInput = (side === 'source' ? row.sourceTable : row.targetTable)?.trim()
  if (!dsId || !tableInput) return
  await startProbe(dsId, tableInput)
}

async function startProbe(dsId: string, tableInput: string) {
  const ds = dsOptions.value.find((d) => d.id === dsId)
  if (!ds) return ElMessage.warning('请先选择数据源')
  // 支持「库.表」或纯表名（用数据源默认库）
  const db = ds.databaseName || 'default'
  let database = db
  let table = tableInput
  if (table.includes('.')) {
    const idx = table.lastIndexOf('.')
    database = table.slice(0, idx)
    table = table.slice(idx + 1)
  }
  if (!database) {
    ElMessage.warning('请在数据源配置模式，或使用 模式.表')
    return
  }
  probeLabel.value = `${ds.dsName}.${database}.${table}`
  probeVisible.value = true
  probeLoading.value = true
  probeConnectOk.value = false
  probeRowCount.value = 0
  probeColumns.value = []
  probeSample.value = { columns: [], rows: [] }
  try {
    probeConnectOk.value = !!await testDatasourceById(dsId)
    const cols = await listColumnInfos(dsId, database, table)
    probeColumns.value = cols || []
    if (probeConnectOk.value) {
      try {
        probeRowCount.value = Number(await tableCount(dsId, database, table)) || 0
      } catch {
        /* 行数统计失败不影响整体 */
      }
      try {
        const res = await executeSql(dsId, `SELECT * FROM ${database}.${table} LIMIT 20`)
        probeSample.value = { columns: res.columns || [], rows: res.rows || [] }
      } catch (e: any) {
        ElMessage.warning('样例数据获取失败：' + (e?.message || '未知错误'))
      }
    }
  } catch (e: any) {
    probeConnectOk.value = false
    ElMessage.error('探数失败：' + (e?.message || '未知错误'))
  } finally {
    probeLoading.value = false
  }
}

onMounted(() => {
  loadOptions()
  loadProjectGroupOptions()
  load()
})
</script>

<style scoped>
.pipeline-page {
  border-radius: 12px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.arrow {
  color: var(--el-text-color-primary);
}
.arrow-sep {
  margin: 0 6px;
  color: var(--el-text-color-secondary);
}
.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 10px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

/* ---------- 向导弹窗 ---------- */
.wizard-header {
  background: linear-gradient(135deg, #4f46e5 0%, #7c3aed 55%, #0ea5e9 100%);
  border-radius: 12px;
  padding: 18px 22px;
  margin-bottom: 16px;
  color: #fff;
}
.wh-title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.wh-sub {
  font-size: 12px;
  opacity: 0.85;
  margin-top: 4px;
}
.wizard-steps {
  margin: 6px 0 18px;
}
.wizard-panel {
  min-height: 240px;
}
.panel-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.panel-label::before {
  content: '';
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(180deg, #4f46e5, #0ea5e9);
}
.type-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}
.type-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: var(--el-bg-color);
}
.type-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(80, 70, 229, 0.12);
  border-color: #4f46e5;
}
.type-card.active {
  border-color: #4f46e5;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.08), rgba(14, 165, 233, 0.08));
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.15);
}
.tc-icon {
  font-size: 26px;
}
.tc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.panel-form {
  margin-top: 4px;
}
.table-input-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.table-input-row .el-input {
  flex: 1;
}
.probe-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 200px;
  color: var(--el-text-color-secondary);
}
.probe-section {
  margin-top: 12px;
}
.probe-section .section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 6px;
}

.engine-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 8px;
}
.engine-card {
  position: relative;
  padding: 18px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 14px;
  cursor: pointer;
  text-align: center;
  transition: all 0.2s ease;
  background: var(--el-bg-color);
}
.engine-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(80, 70, 229, 0.12);
  border-color: #4f46e5;
}
.engine-card.active {
  border-color: #4f46e5;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.08), rgba(14, 165, 233, 0.08));
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.15);
}
.ec-icon {
  font-size: 34px;
}
.ec-name {
  font-size: 15px;
  font-weight: 700;
  margin-top: 8px;
  color: var(--el-text-color-primary);
}
.ec-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.ec-tag {
  margin-top: 8px;
}
.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>