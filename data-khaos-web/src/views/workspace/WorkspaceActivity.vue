<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import DkStatus from '@/paas-ui/DkStatus.vue'
import WorkspaceBackfill from './WorkspaceBackfill.vue'
import WorkspaceRunOperations from './WorkspaceRunOperations.vue'
import { getWorkspaceRun, getWorkspaceTask, pageWorkspaceRuns, pageWorkspaceTasks, submitWorkspacePipelineRun } from '@/api/project-group'
import type {
  ProjectActivityKind, ProjectRunStatus, ProjectRunTrigger, ProjectWorkspaceRun, ProjectWorkspaceRunDetail, ProjectWorkspaceTask, ProjectWorkspaceTaskDetail, WorkspacePipelineRunReceipt,
} from '@/api/project-group'

import { createRequestId } from '@/utils/requestId'

const props = defineProps<{ projectId: string; canRunPipeline: boolean; canSchedule: boolean; fixedKind?: ProjectActivityKind }>()
const view = ref<'tasks' | 'runs' | 'alerts'>('tasks')
const unacknowledgedOnly = ref(true)
const kind = ref<ProjectActivityKind>()
const keyword = ref('')
const bizDate = ref('')
const status = ref<ProjectRunStatus>()
const triggerType = ref<ProjectRunTrigger>()
const taskId = ref('')
const detailType = ref<'tasks' | 'runs'>('tasks')
const backfillDate = ref('')
const current = ref(1)
const total = ref(0)
const pageSize = 10
const tasks = ref<ProjectWorkspaceTask[]>([])
const runs = ref<ProjectWorkspaceRun[]>([])
const loading = ref(false)
const error = ref(false)
let requestId = 0

const detailsOpen = ref(false)
const detailsLoading = ref(false)
const detailsError = ref(false)
const taskDetail = ref<ProjectWorkspaceTaskDetail>()
const runDetail = ref<ProjectWorkspaceRunDetail>()
let detailsRequestId = 0

const runDate = ref('')
const submitting = ref(false)
const receipt = ref<WorkspacePipelineRunReceipt>()
const pendingRequests = new Map<string, string>()

async function submitPipelineRun() {
  const task = taskDetail.value
  if (!task?.workspaceRunnable || !props.canRunPipeline || submitting.value || !runDate.value) return
  const date = runDate.value
  const key = task.id + ':' + date
  const request = pendingRequests.get(key) ?? createRequestId()
  pendingRequests.set(key, request)
  const selection = detailsRequestId
  submitting.value = true
  try {
    const result = await submitWorkspacePipelineRun(props.projectId, task.id, request, date)
    pendingRequests.delete(key)
    if (selection === detailsRequestId) receipt.value = result
  } catch {
    // Retain the request ID for retries when submission outcome is unknown.
  } finally {
    submitting.value = false
  }
}

function goToRun() {
  if (!receipt.value) return
  view.value = 'runs'
  kind.value = 'PIPELINE'
  status.value = undefined
  triggerType.value = undefined
  taskId.value = taskDetail.value?.id || ''
  bizDate.value = receipt.value.bizDate
  detailsOpen.value = false
  search()
}

async function openDetails(type: 'tasks' | 'runs', row: { kind: ProjectActivityKind; id: string }, date = '') {
  detailType.value = type
  backfillDate.value = date
  const id = ++detailsRequestId
  detailsOpen.value = true
  detailsLoading.value = true
  detailsError.value = false
  runDate.value = ''
  receipt.value = undefined
  taskDetail.value = undefined
  runDetail.value = undefined
  try {
    if (type === 'tasks') {
      const result = await getWorkspaceTask(props.projectId, row.kind, row.id)
      if (id === detailsRequestId) taskDetail.value = result
    } else {
      const result = await getWorkspaceRun(props.projectId, row.kind, row.id)
      if (id === detailsRequestId) runDetail.value = result
    }
  } catch {
    if (id === detailsRequestId) detailsError.value = true
  } finally {
    if (id === detailsRequestId) detailsLoading.value = false
  }
}

function closeDetails() {
  detailsRequestId++
  taskDetail.value = undefined
  runDetail.value = undefined
}

const stateNames: Record<string, string> = {
  ENABLED: '启用', DISABLED: '停用', PUBLISHED: '已发布', UNKNOWN: '未知', STOP: '已停止', SKIPPED: '已跳过',
  RECOVERY_REQUIRED: '待核查', MANUAL: '手动', SCHEDULE: '调度', CRON: '定时', WORKFLOW: '工作流触发', BACKFILL: '补数',
}
const taskKinds = { WORKFLOW: '工作流', PIPELINE: '管道' }
const runStyles = { PENDING: 'queued', RUNNING: 'running', SUCCESS: 'success', FAILED: 'failed' } as const
const runStatuses = { RECOVERY_REQUIRED: '待核查', FAILED: '失败', RUNNING: '运行中', SUCCESS: '成功', PENDING: '排队中', STOP: '已停止', UNKNOWN: '未知' }
const runTriggers = { BACKFILL: '补数', MANUAL: '手动', SCHEDULE: '调度', CRON: '定时', WORKFLOW: '工作流触发' }

function activityKey(row: ProjectWorkspaceTask | ProjectWorkspaceRun) {
  return row.kind + ':' + row.id
}

function formatTime(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}

async function load() {
  const id = ++requestId
  const params = { current: current.value, size: pageSize, kind: view.value === 'alerts' ? 'WORKFLOW' as const : props.fixedKind || kind.value || undefined }
  tasks.value = []
  runs.value = []
  total.value = 0
  loading.value = true
  error.value = false
  try {
    if (view.value === 'tasks') {
      const data = await pageWorkspaceTasks(props.projectId, { ...params, keyword: keyword.value.trim() || undefined })
      if (id !== requestId) return
      tasks.value = data.records
      total.value = Number(data.total)
    } else {
      const data = await pageWorkspaceRuns(props.projectId, {
        ...params, bizDate: bizDate.value || undefined, status: status.value || undefined,
        taskId: taskId.value.trim() || undefined, triggerType: triggerType.value || undefined,
        alertsOnly: view.value === 'alerts' || undefined,
        unacknowledgedOnly: (view.value === 'alerts' && unacknowledgedOnly.value) || undefined,
      })
      if (id !== requestId) return
      runs.value = data.records
      total.value = Number(data.total)
    }
  } catch {
    if (id === requestId) error.value = true
  } finally {
    if (id === requestId) loading.value = false
  }
}

function changeView() {
  status.value = undefined
  search()
}

function refreshRunDetail(id: string) {
  if (!detailsOpen.value || runDetail.value?.id !== id) return
  void openDetails('runs', { kind: 'WORKFLOW', id })
  void load()
}

function search() {
  current.value = 1
  void load()
}

onMounted(load)
onBeforeUnmount(() => { requestId++; closeDetails() })
</script>

<template>
  <section class="dk-panel activity" aria-label="项目任务与运行">
    <el-tabs v-model="view" class="activity-tabs" @tab-change="changeView">
      <el-tab-pane name="tasks" label="任务列表" />
      <el-tab-pane name="runs" label="运行记录" />
      <el-tab-pane name="alerts" label="任务告警" />
    </el-tabs>
    <form class="filters" @submit.prevent="search">
      <el-select v-if="view !== 'alerts' && !fixedKind" v-model="kind" clearable placeholder="全部类型" aria-label="任务类型" class="kind" @change="search">
        <el-option label="工作流" value="WORKFLOW" />
        <el-option label="管道" value="PIPELINE" />
      </el-select>
      <el-input v-if="view === 'tasks'" v-model="keyword" clearable maxlength="100" class="keyword"
        placeholder="搜索任务名称" aria-label="任务名称" @clear="search" />
      <template v-else>
        <el-select v-model="status" clearable placeholder="全部状态" aria-label="运行状态" class="kind" @change="search">
          <el-option v-for="(label, value) in runStatuses" :key="value" :label="label" :value="value" />
        </el-select>
        <el-select v-model="triggerType" clearable placeholder="全部触发" aria-label="触发类型" class="kind" @change="search">
          <el-option v-for="(label, value) in runTriggers" :key="value" :label="label" :value="value" />
        </el-select>
        <el-date-picker v-model="bizDate" type="date" value-format="YYYY-MM-DD" placeholder="业务日期"
          aria-label="业务日期" @change="search" />
        <el-input v-model="taskId" clearable maxlength="32" class="keyword" placeholder="任务 ID"
          aria-label="任务 ID" @clear="search" />
      </template>
      <el-checkbox v-if="view === 'alerts'" v-model="unacknowledgedOnly" @change="search">仅待确认</el-checkbox>
      <DkButton type="submit">查询</DkButton>
    </form>
    <el-alert v-if="error" class="activity-error" title="加载失败或项目权限已变更，请重新查询" type="warning"
      show-icon :closable="false" />
    <el-table v-if="view === 'tasks'" v-loading="loading" :data="tasks" :row-key="activityKey"
      empty-text="当前项目暂无匹配任务">
      <el-table-column label="任务" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.name }}<div class="dk-mono activity-id">{{ row.id }}</div></template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ taskKinds[row.kind as ProjectActivityKind] }}</template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{ row }">{{ stateNames[row.status] || row.status }}</template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="165">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="$slots['task-action'] ? 150 : 85">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetails('tasks', row)">详情</el-button>
          <slot name="task-action" :task="row" />
        </template>
      </el-table-column>
    </el-table>
    <el-table v-else v-loading="loading" :data="runs" :row-key="activityKey"
      empty-text="当前项目暂无匹配的运行记录">
      <el-table-column label="任务 / 运行 ID" min-width="155" show-overflow-tooltip>
        <template #default="{ row }">{{ row.taskName || row.taskId }}<div class="dk-mono activity-id">{{ row.id }}</div></template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ taskKinds[row.kind as ProjectActivityKind] }}</template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{ row }">
          <DkStatus v-if="row.status in runStyles" :status="runStyles[row.status as keyof typeof runStyles]" />
          <span v-else>{{ stateNames[row.status] || row.status || '未知' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" min-width="110">
        <template #default="{ row }">{{ row.bizDate || '—' }}</template>
      </el-table-column>
      <el-table-column label="开始时间" min-width="165">
        <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="触发" min-width="100">
        <template #default="{ row }">{{ stateNames[row.triggerType] || row.triggerType || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="view === 'alerts'" label="告警确认" width="105">
        <template #default="{ row }">{{ row.alertAcknowledgedAt ? '已确认' : '待确认' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="85">
        <template #default="{ row }"><el-button link type="primary" @click="openDetails('runs', row)">详情</el-button></template>
      </el-table-column>
    </el-table>
    <div class="activity-footer">
      <span v-if="view === 'runs'">仅显示记录了项目归属的运行</span>
      <el-pagination v-if="total > pageSize" v-model:current-page="current" layout="prev, pager, next"
        size="small" :total="total" :page-size="pageSize" @current-change="load" />
    </div>

    <el-drawer v-model="detailsOpen" :title="detailType === 'tasks' ? '任务详情' : '运行详情'" size="min(530px, 100vw)"
      destroy-on-close :close-on-click-modal="!submitting" :show-close="!submitting" @close="closeDetails">
      <p v-if="detailsLoading" role="status">正在加载详情…</p>
      <el-alert v-else-if="detailsError" type="warning" show-icon :closable="false" title="详情不可用或访问权限已变更" />
      <template v-else-if="taskDetail">
        <h2 class="detail-heading">{{ taskDetail.name }}</h2>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="任务 ID">{{ taskDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ taskKinds[taskDetail.kind] }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ stateNames[taskDetail.status] || taskDetail.status }}</el-descriptions-item>
          <el-descriptions-item label="更新于">{{ formatTime(taskDetail.updatedAt) }}</el-descriptions-item>
          <template v-if="taskDetail.kind === 'PIPELINE'">
            <el-descriptions-item label="引擎">{{ taskDetail.engine || '—' }}</el-descriptions-item>
            <el-descriptions-item label="源数据源">{{ taskDetail.sourceName || '暂无绑定信息' }}</el-descriptions-item>
            <el-descriptions-item label="目标数据源">{{ taskDetail.targetName || '暂无绑定信息' }}</el-descriptions-item>
          </template>
        </el-descriptions>
        <div v-if="taskDetail.kind === 'PIPELINE' && props.canRunPipeline" class="pipeline-run">
          <template v-if="taskDetail.workspaceRunnable">
            <h3 class="nodes-heading">按业务日期运行</h3>
            <form class="run-controls" @submit.prevent="submitPipelineRun">
              <input v-model="runDate" type="date" required aria-label="提交业务日期" class="run-date" :disabled="submitting" />
              <DkButton variant="primary" type="submit" :disabled="!runDate || !!receipt" :loading="submitting">
                提交运行
              </DkButton>
            </form>
            <div v-if="receipt" class="run-feedback" role="status">
              <span>{{ receipt.state === 'REJECTED' ? '队列暂满' : receipt.state === 'DUPLICATE' ? '已找到原运行' : '已受理' }}</span>
              <code class="dk-mono">{{ receipt.runId }}</code>
              <DkButton variant="quiet" @click="goToRun">查看运行记录</DkButton>
              <DkButton v-if="receipt.state === 'REJECTED'" variant="quiet" @click="receipt = undefined">重新提交</DkButton>
            </div>
          </template>
          <p v-else class="activity-id">项目空间支持绑定完整的 MySQL 按日覆盖任务。</p>
        </div>
        <WorkspaceBackfill v-if="taskDetail.kind === 'WORKFLOW' && props.canSchedule && taskDetail.status !== 'DISABLED'"
          :project-id="projectId" :task-id="taskDetail.id" :initial-date="backfillDate"
          @open-run="id => openDetails('runs', { kind: 'WORKFLOW', id })" />
        <template v-if="taskDetail.kind === 'WORKFLOW'">
          <h3 class="nodes-heading">工作流节点</h3>
          <el-table :data="taskDetail.nodes" row-key="code" empty-text="暂无节点">
            <el-table-column label="节点" min-width="135">
              <template #default="{ row }">{{ row.name || row.code }}<div class="dk-mono activity-id">{{ row.code }}</div></template>
            </el-table-column>
            <el-table-column prop="type" label="类型" width="100" />
          </el-table>
        </template>
      </template>
      <template v-else-if="runDetail">
        <h2 class="detail-heading">{{ runDetail.taskName || runDetail.taskId }}</h2>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="运行 ID">{{ runDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="任务类型">{{ taskKinds[runDetail.kind] }}</el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ runDetail.bizDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ formatTime(runDetail.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatTime(runDetail.endedAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ runDetail.durationMs == null ? '—' : runDetail.durationMs + ' ms' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <DkStatus v-if="runDetail.status && runDetail.status in runStyles"
              :status="runStyles[runDetail.status as keyof typeof runStyles]" />
            <span v-else>{{ stateNames[runDetail.status || 'UNKNOWN'] || runDetail.status || '未知' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="runDetail.failureReason" type="warning" show-icon :closable="false"
          class="run-feedback" :title="runDetail.failureReason" />
        <template v-if="runDetail.kind === 'WORKFLOW'">
          <WorkspaceRunOperations :key="runDetail.id" :project-id="projectId" :run="runDetail" :can-schedule="canSchedule"
            @refresh="refreshRunDetail(runDetail.id)" />
          <DkButton v-if="props.canSchedule && runDetail.status === 'FAILED' && runDetail.bizDate"
            class="run-feedback" @click="openDetails('tasks', { kind: 'WORKFLOW', id: runDetail.taskId }, runDetail.bizDate)">
            补跑该业务日
          </DkButton>
          <h3 class="nodes-heading">节点执行</h3>
          <el-table :data="runDetail.nodes" row-key="code" empty-text="暂无节点记录">
            <el-table-column label="节点" min-width="135">
              <template #default="{ row }">{{ row.name || row.code }}<div class="dk-mono activity-id">{{ row.code }}</div></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <DkStatus v-if="row.status in runStyles" :status="runStyles[row.status as keyof typeof runStyles]" />
                <span v-else>{{ stateNames[row.status] || row.status || '未知' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="关联记录" min-width="145">
              <template #default="{ row }"><span class="dk-mono">{{ row.refId || '—' }}</span></template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.activity { min-width:0; }
.pipeline-run { margin-top:var(--dk-space-4); }
.run-controls,.run-feedback { display:flex; align-items:center; flex-wrap:wrap; gap:var(--dk-space-2); }
.run-feedback { margin-top:var(--dk-space-3); }
.run-date { min-height:36px; padding:0 var(--dk-space-2); border:1px solid var(--dk-border);
  border-radius:var(--dk-radius-control); background:var(--dk-surface); color:var(--dk-ink); font:inherit; }
.detail-heading { margin:0 0 var(--dk-space-3); font-size:18px; }
.nodes-heading { margin:var(--dk-space-5) 0 var(--dk-space-2); font-size:15px; }
.activity-tabs,.filters,.activity-error,.activity-footer { padding:0 var(--dk-space-4); }
.filters { display:flex; align-items:center; flex-wrap:wrap; gap:var(--dk-space-3); margin-bottom:var(--dk-space-3); }
.kind { width:130px; }
.keyword { width:min(215px, 100%); }
.activity-id { color:var(--dk-muted); font-size:12px; }
.activity-footer { display:flex; min-height:12px; align-items:center; flex-wrap:wrap; gap:var(--dk-space-2); color:var(--dk-muted); font-size:12px; }
.activity-footer > span,.activity-footer > .el-pagination { margin:var(--dk-space-3) 0; }
.activity-footer > .el-pagination { margin-left:auto; }
</style>
