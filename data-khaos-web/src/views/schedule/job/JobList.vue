<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="任务名称/分组" clearable style="width: 180px" @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="query.jobType" clearable placeholder="全部" style="width: 130px" @change="handleSearch">
            <el-option v-for="t in jobTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px" @change="handleSearch">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增任务</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="jobName" label="任务名称" min-width="140" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-tag>{{ jobTypeText(row.jobType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="关联对象" min-width="170">
        <template #default="{ row }">
          <template v-if="row.jobType === 'WORKFLOW' || row.refType === 'WORKFLOW'">
            <el-link v-if="row.refId" type="primary" @click="router.push(`/workflow/edit/${row.refId}`)">
              {{ workflowName(row.refId) }}
            </el-link>
            <span v-else class="muted">未绑定工作流</span>
          </template>
          <span v-else-if="row.jobType === 'SYNC' || row.refType === 'PIPELINE'">
            {{ pipelineName(row.refId) || row.refId || '未绑定同步任务' }}
          </span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="jobGroup" label="分组" min-width="110" />
      <el-table-column prop="cronExpression" label="Cron 表达式" min-width="130" />
      <el-table-column prop="targetTable" label="目标表" min-width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170" />
      <el-table-column label="操作" width="360" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status !== 1" link type="success" @click="handleStart(row)">启用</el-button>
          <el-button v-else link type="warning" @click="handleStop(row)">停用</el-button>
          <el-button link type="info" @click="handleRun(row)">手动运行</el-button>
          <el-button link type="primary" @click="openDeps(row)">依赖</el-button>
          <el-button v-if="row.jobType === 'WORKFLOW' && row.refId" link type="primary"
            @click="router.push(`/workflow/edit/${row.refId}`)">编排</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="log-bar">
      <el-button type="primary" plain @click="logVisible = true">查看执行日志</el-button>
    </div>

    <el-pagination
      class="pager"
      v-model:current-page="query.current"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      @change="load"
    />

    <!-- 新增 / 编辑任务 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑任务' : '新增任务'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item label="任务名称" prop="jobName">
          <el-input v-model="form.jobName" placeholder="请输入任务名称" />
        </el-form-item>
        <el-form-item label="任务类型" prop="jobType">
          <el-select v-model="form.jobType" style="width: 100%">
            <el-option v-for="t in jobTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分组" prop="jobGroup">
          <el-input v-model="form.jobGroup" placeholder="请输入分组" />
        </el-form-item>
        <el-form-item label="Cron 表达式" prop="cronExpression">
          <el-input v-model="form.cronExpression" placeholder="如 0 0 2 * * ?" />
        </el-form-item>

        <el-form-item v-if="form.jobType === 'WORKFLOW'" label="关联工作流">
          <el-select v-model="form.refId" filterable placeholder="选择要调度的任务流" style="width: 100%">
            <el-option v-for="workflow in workflowOptions" :key="workflow.id"
              :label="workflow.name" :value="workflow.id!" />
          </el-select>
          <div class="field-hint">任务流内部 DAG 在「编排」中维护；此处只配置定时触发。</div>
        </el-form-item>

        <el-form-item v-else-if="form.jobType === 'SYNC'" label="关联同步任务">
          <el-select v-model="form.refId" filterable placeholder="选择数据同步任务" style="width: 100%">
            <el-option v-for="pipeline in pipelineOptions" :key="pipeline.id"
              :label="pipeline.name ? `${pipeline.name} (${pipeline.id})` : pipeline.id" :value="pipeline.id" />
          </el-select>
        </el-form-item>

        <template v-if="form.jobType === 'SQL'">
          <el-form-item label="数据源ID" prop="datasourceId">
            <el-input v-model="form.datasourceId" placeholder="请输入数据源ID" />
          </el-form-item>
          <el-form-item label="执行SQL" prop="targetSql">
            <el-input v-model="form.targetSql" type="textarea" :rows="4" placeholder="请输入执行 SQL" />
          </el-form-item>
          <el-form-item label="目标表" prop="targetTable">
            <el-input v-model="form.targetTable" placeholder="请输入目标表" />
          </el-form-item>
        </template>

        <el-form-item label="参数(JSON)" prop="params">
          <el-input v-model="form.params" type="textarea" :rows="2" :placeholder="paramsPlaceholder" />
        </el-form-item>
        <el-form-item label="失败重试次数" prop="retryCount">
          <el-input-number v-model="form.retryCount" :min="0" :max="10" />
        </el-form-item>
        <el-form-item label="重试间隔(秒)" prop="retryInterval">
          <el-input-number v-model="form.retryInterval" :min="0" :max="3600" />
        </el-form-item>
        <el-form-item label="超时(秒)" prop="timeout">
          <el-input-number v-model="form.timeout" :min="0" :max="86400" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
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

    <!-- 任务依赖 -->
    <el-dialog v-model="depVisible" :title="`任务依赖 · ${currentJob?.jobName || ''}`" width="640px">
      <div class="dep-add">
        <el-select v-model="depForm.depJobId" filterable placeholder="选择上游任务" style="flex: 1">
          <el-option v-for="job in allJobs.filter(item => item.id !== currentJob?.id)" :key="job.id"
            :label="job.jobName" :value="job.id!" />
        </el-select>
        <el-select v-model="depForm.depType" style="width: 120px">
          <el-option label="HARD 强依赖" value="HARD" />
          <el-option label="SOFT 弱依赖" value="SOFT" />
        </el-select>
        <el-button type="primary" :disabled="!depForm.depJobId" @click="addDep">添加</el-button>
      </div>
      <el-alert class="dep-hint" type="info" :closable="false" show-icon
        title="HARD：上游任务成功后才触发当前任务；SOFT：只记录依赖关系，不阻断运行。" />
      <el-table v-loading="depLoading" :data="depRows" size="small" border empty-text="暂无上游依赖">
        <el-table-column label="上游任务" min-width="180">
          <template #default="{ row }">{{ jobName(row.depJobId) }}</template>
        </el-table-column>
        <el-table-column label="依赖类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.depType === 'HARD' ? 'danger' : 'info'">{{ row.depType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" size="small" @click="removeDep(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="depVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 执行日志对话框 -->
    <el-dialog v-model="logVisible" title="任务执行日志" width="900px">
      <el-form inline>
        <el-form-item label="任务ID">
          <el-input v-model="logQuery.jobId" placeholder="按任务ID筛选" clearable style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="loadLogs">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="logLoading" :data="logs" size="small" border max-height="420">
        <el-table-column prop="jobId" label="任务ID" min-width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="logStatusTagType(row.status)">{{ logStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
        <el-table-column prop="resultRows" label="结果行数" width="90" />
        <el-table-column prop="errorMessage" label="错误信息" min-width="160" show-overflow-tooltip />
      </el-table>
      <el-pagination
        class="pager"
        v-model:current-page="logQuery.current"
        v-model:page-size="logQuery.size"
        :total="logTotal"
        layout="total, prev, pager, next"
        @change="loadLogs"
      />
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createJob, deleteJob, deleteJobDep, listJobDeps, pageJobLogs, pageJobs, runJob, saveJobDep,
  startJob, stopJob, updateJob,
} from '@/api/schedule'
import { pagePipelineTasks } from '@/api/pipeline'
import { listWorkflowOptions } from '@/api/workflow'
import type { ScheduleJob, ScheduleJobDep, ScheduleJobLog, WorkflowDef } from '@/types'

const route = useRoute()
const router = useRouter()

const paramsPlaceholder = computed(() => {
  if (form.jobType === 'QUALITY') return '质量任务ID，如 {"taskId": "质量任务ID"}'
  if (form.jobType === 'WORKFLOW') return '传给工作流的参数，如 {"bizDate": "2026-11-27"}'
  if (form.jobType === 'SYNC') return '传给同步任务的参数，如 {"batchSize": 1000}'
  return '如 {"batchSize": 1000}'
})

const jobTypes = [
  { label: '工作流 WORKFLOW', value: 'WORKFLOW' },
  { label: '同步 SYNC', value: 'SYNC' },
  { label: 'SQL', value: 'SQL' },
  { label: '质量稽核 QUALITY', value: 'QUALITY' },
]
const legacyTypeLabels: Record<string, string> = {
  REFRESH: '刷新 REFRESH（未接入）',
  PUSH: '推送 PUSH（未接入）',
}

function jobTypeText(t?: string) {
  return jobTypes.find((x) => x.value === t)?.label ?? legacyTypeLabels[t ?? ''] ?? t ?? '-'
}

const workflowOptions = ref<WorkflowDef[]>([])
const pipelineOptions = ref<Array<{ id: string; name?: string }>>([])
const allJobs = ref<ScheduleJob[]>([])
const depVisible = ref(false)
const depLoading = ref(false)
const depRows = ref<ScheduleJobDep[]>([])
const currentJob = ref<ScheduleJob>()
const depForm = reactive<{ depJobId: string; depType: 'HARD' | 'SOFT' }>({ depJobId: '', depType: 'HARD' })

async function loadOptions() {
  try { workflowOptions.value = await listWorkflowOptions() } catch { workflowOptions.value = [] }
  try {
    const page = await pagePipelineTasks({ current: 1, size: 200 })
    pipelineOptions.value = (page.records || []).map((row: any) => ({ id: row.id, name: row.taskName || row.name }))
  } catch { pipelineOptions.value = [] }
  try {
    const page = await pageJobs({ current: 1, size: 500 })
    allJobs.value = page.records
  } catch { allJobs.value = [] }
}

function workflowName(id?: string) {
  return workflowOptions.value.find(item => item.id === id)?.name || id || ''
}
function pipelineName(id?: string) {
  return pipelineOptions.value.find(item => item.id === id)?.name || ''
}

async function openDeps(row: ScheduleJob) {
  currentJob.value = row
  depForm.depJobId = ''
  depForm.depType = 'HARD'
  depVisible.value = true
  await loadOptions()
  await loadDeps()
}
async function loadDeps() {
  if (!currentJob.value?.id) return
  depLoading.value = true
  try {
    depRows.value = await listJobDeps(currentJob.value.id)
  } finally {
    depLoading.value = false
  }
}
function jobName(id?: string) {
  return allJobs.value.find(job => job.id === id)?.jobName || id || ''
}
async function addDep() {
  if (!currentJob.value?.id || !depForm.depJobId) return
  await saveJobDep(currentJob.value.id, { depJobId: depForm.depJobId, depType: depForm.depType })
  ElMessage.success('依赖已添加')
  depForm.depJobId = ''
  await loadDeps()
}
async function removeDep(dep: ScheduleJobDep) {
  if (!dep.id) return
  await deleteJobDep(dep.id)
  ElMessage.success('依赖已移除')
  await loadDeps()
}

const loading = ref(false)
const submitting = ref(false)
const list = ref<ScheduleJob[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, keyword: '', jobType: undefined, status: undefined })

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const isEdit = ref(false)
const form = reactive<ScheduleJob>({ status: 0, jobType: 'SYNC', retryCount: 0, retryInterval: 0, timeout: 60 })
const formRules: FormRules = {
  jobName: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  jobType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  cronExpression: [{ required: true, message: '请输入 Cron 表达式', trigger: 'blur' }],
}

const logVisible = ref(false)
const logLoading = ref(false)
const logs = ref<ScheduleJobLog[]>([])
const logTotal = ref(0)
const logQuery = reactive<Record<string, any>>({ current: 1, size: 10, jobId: '' })

function logStatusText(s?: number) {
  return { 0: '运行中', 1: '成功', 2: '失败' }[s ?? -1] ?? '-'
}

function logStatusTagType(s?: number): 'info' | 'success' | 'danger' {
  if (s === 0) return 'info'
  if (s === 1) return 'success'
  return 'danger'
}

async function load() {
  loading.value = true
  try {
    const data = await pageJobs({ ...query })
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
  query.jobType = undefined
  query.status = undefined
  handleSearch()
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, {
    jobName: '', jobType: 'WORKFLOW', jobGroup: '', cronExpression: '', datasourceId: '', targetSql: '',
    targetTable: '', params: '', refType: 'WORKFLOW', refId: '', status: 0, retryCount: 0,
    retryInterval: 0, timeout: 60,
  })
  dialogVisible.value = true
}

function openEdit(row: ScheduleJob) {
  isEdit.value = true
  Object.assign(form, { ...row })
  if (!form.refType) {
    form.refType = form.jobType === 'WORKFLOW' ? 'WORKFLOW' : form.jobType === 'SYNC' ? 'PIPELINE' : undefined
  }
  dialogVisible.value = true
}

async function submit() {
  await formRef.value?.validate()
  if (form.jobType === 'WORKFLOW' && !form.refId) {
    ElMessage.warning('请选择要调度的任务流')
    return
  }
  if (form.jobType === 'SYNC' && !form.refId) {
    ElMessage.warning('请选择要触发的同步任务')
    return
  }
  form.refType = form.jobType === 'WORKFLOW' ? 'WORKFLOW'
    : form.jobType === 'SYNC' ? 'PIPELINE' : undefined
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateJob({ ...form })
    } else {
      await createJob({ ...form })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: ScheduleJob) {
  await ElMessageBox.confirm(`确认删除任务「${row.jobName}」吗？将级联删除日志与依赖。`, '提示', { type: 'warning' })
  await deleteJob(row.id!)
  ElMessage.success('删除成功')
  load()
}

async function handleStart(row: ScheduleJob) {
  await startJob(row.id!)
  ElMessage.success('任务已启用')
  load()
}

async function handleStop(row: ScheduleJob) {
  await stopJob(row.id!)
  ElMessage.success('任务已停用')
  load()
}

async function handleRun(row: ScheduleJob) {
  await ElMessageBox.confirm(`确认手动运行任务「${row.jobName}」吗？`, '提示', { type: 'warning' })
  await runJob(row.id!)
  ElMessage.success('已触发运行')
}

async function loadLogs() {
  logLoading.value = true
  try {
    const data = await pageJobLogs({ ...logQuery })
    logs.value = data.records
    logTotal.value = Number(data.total)
  } finally {
    logLoading.value = false
  }
}

onMounted(() => {
  // 支持从质量任务页面跳转定位：携带 keyword 时自动按关键字过滤 QUALITY 任务
  const kw = route.query.keyword
  if (typeof kw === 'string' && kw) {
    query.keyword = kw
    query.jobType = 'QUALITY'
  }
  void loadOptions()
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
.log-bar {
  margin-top: 12px;
}
.field-hint { font-size: 12px; color: #98a2b3; line-height: 1.5; margin-top: 4px; }
.dep-add { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.dep-hint { margin-bottom: 10px; }
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
