<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="工作流名称/编码" clearable style="width: 200px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px" @change="handleSearch">
            <el-option label="已发布" :value="2" />
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <div>
        <el-button type="primary" :icon="Plus" @click="router.push('/workflow/edit/create')">新建工作流</el-button>
        <el-button :icon="DataLine" @click="openRunDialog(null)">运行记录</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="name" label="工作流名称" min-width="160">
        <template #default="{ row }">
          <el-link type="primary" @click="router.push(`/workflow/edit/${row.id}`)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="code" label="编码" min-width="140" />
      <el-table-column prop="cronExpression" label="Cron 表达式" min-width="150">
        <template #default="{ row }">
          <span v-if="row.cronExpression">{{ row.cronExpression }}</span>
          <span v-else class="muted">仅手动</span>
        </template>
      </el-table-column>
      <el-table-column prop="owner" label="负责人" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 2 ? 'success' : row.status === 1 ? 'primary' : 'info'">
            {{ row.status === 2 ? '已发布' : row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170" />
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/workflow/edit/${row.id}`)">编排</el-button>
          <el-button v-if="row.status === 0" link type="success" @click="handleStart(row)">启用</el-button>
          <el-button v-else link type="warning" @click="handleStop(row)">停用</el-button>
          <el-button link type="info" @click="handleRun(row)">运行</el-button>
          <el-button link type="primary" @click="openRunDialog(row.id)">记录</el-button>
          <el-button link type="primary" @click="openRevisions(row)">版本</el-button>
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

    <WorkflowRevisionDialog v-if="revisionWorkflow" :workflow="revisionWorkflow"
      @close="revisionWorkflow = undefined" @open-draft="openDraft" />

    <!-- 运行记录对话框 -->
    <el-dialog v-model="runDialogVisible" title="运行记录" width="1000px">
      <el-table v-loading="runLoading" :data="runs" border stripe size="small">
        <el-table-column prop="wfName" label="工作流" min-width="120" />
        <el-table-column label="触发" width="90">
          <template #default="{ row }">
            <el-tag size="small">{{ row.triggerType === 'SCHEDULE' ? '定时' : '手动' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="runStatusTag(row.runStatus)">{{ runStatusText(row.runStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
        <el-table-column prop="errorMessage" label="错误" min-width="140" show-overflow-tooltip />
        <el-table-column label="节点" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRunDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        v-model:current-page="runQuery.current"
        v-model:page-size="runQuery.size"
        :total="runTotal"
        layout="total, prev, pager, next"
        @change="loadRuns"
      />
    </el-dialog>

    <!-- 节点执行明细对话框 -->
    <el-dialog v-model="nodeDialogVisible" title="节点执行明细" width="900px" destroy-on-close>
      <el-table v-loading="nodeLoading" :data="nodeRuns" border stripe size="small">
        <el-table-column prop="nodeName" label="节点" min-width="130" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ nodeTypeText(row.nodeType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="nodeStatusTag(row.status)">{{ nodeStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
        <el-table-column prop="resultRows" label="行数" width="70" />
        <el-table-column prop="errorMessage" label="错误" min-width="140" show-overflow-tooltip />
        <el-table-column label="日志" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="openLog(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 节点日志 -->
    <el-dialog v-model="logDialogVisible" title="执行日志" width="760px" destroy-on-close>
      <pre class="log-box">{{ currentLog }}</pre>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataLine, Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  deleteWorkflow,
  listWorkflowRunNodes,
  pageWorkflowRuns,
  pageWorkflows,
  triggerWorkflow,
  updateWorkflowStatus,
} from '@/api/workflow'
import type { WorkflowDef, WorkflowNodeRun, WorkflowRun } from '@/types'
import WorkflowRevisionDialog from './WorkflowRevisionDialog.vue'

const router = useRouter()
const revisionWorkflow = ref<WorkflowDef & { id: string }>()

function openRevisions(workflow: WorkflowDef) {
  if (workflow.id) revisionWorkflow.value = { ...workflow, id: workflow.id }
}

function openDraft(id: string) {
  revisionWorkflow.value = undefined
  void router.push(`/workflow/edit/${id}`)
}

const loading = ref(false)
const list = ref<WorkflowDef[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, keyword: '', status: undefined })

const runDialogVisible = ref(false)
const runLoading = ref(false)
const runs = ref<WorkflowRun[]>([])
const runTotal = ref(0)
const runQuery = reactive<Record<string, any>>({ current: 1, size: 10, wfId: '' })

const nodeDialogVisible = ref(false)
const nodeLoading = ref(false)
const nodeRuns = ref<WorkflowNodeRun[]>([])

const logDialogVisible = ref(false)
const currentLog = ref('')

function runStatusText(s?: string) {
  return { PENDING: '待运行', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', STOP: '停止', RECOVERY_REQUIRED: '待核查' }[s ?? ''] ?? s ?? '-'
}
function runStatusTag(s?: string): 'info' | 'warning' | 'success' | 'danger' {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING' || s === 'PENDING' || s === 'RECOVERY_REQUIRED') return 'warning'
  return 'info'
}
function nodeStatusText(s?: string) {
  return { PENDING: '待运行', RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败', SKIPPED: '跳过' }[s ?? ''] ?? s ?? '-'
}
function nodeStatusTag(s?: string): 'info' | 'warning' | 'success' | 'danger' {
  if (s === 'SUCCESS') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'SKIPPED') return 'info'
  return 'warning'
}
function nodeTypeText(t?: string) {
  return {
    SQL: 'SQL', SHELL: 'Shell', PYTHON: 'Python', DATA_OP: '数据算子',
    PIPELINE: '数据同步', QUALITY: '数据质量', DELAY: '延时器', NESTED: '子任务流',
  }[t ?? ''] ?? t ?? '-'
}

async function load() {
  loading.value = true
  try {
    const data = await pageWorkflows({ ...query })
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
  query.status = undefined
  handleSearch()
}

async function handleDelete(row: WorkflowDef) {
  await ElMessageBox.confirm(`确认删除工作流「${row.name}」吗？`, '提示', { type: 'warning' })
  await deleteWorkflow(row.id!)
  ElMessage.success('删除成功')
  load()
}
async function handleStart(row: WorkflowDef) {
  await updateWorkflowStatus(row.id!, 1)
  ElMessage.success('已启用')
  load()
}
async function handleStop(row: WorkflowDef) {
  await updateWorkflowStatus(row.id!, 0)
  ElMessage.success('已停用')
  load()
}
async function handleRun(row: WorkflowDef) {
  await ElMessageBox.confirm(`确认手动运行工作流「${row.name}」吗？`, '提示', { type: 'warning' })
  await triggerWorkflow(row.id!)
  ElMessage.success('已触发运行')
}

function openRunDialog(wfId: string | null) {
  runQuery.wfId = wfId || ''
  runQuery.current = 1
  runDialogVisible.value = true
  loadRuns()
}
async function loadRuns() {
  runLoading.value = true
  try {
    const data = await pageWorkflowRuns({ ...runQuery })
    runs.value = data
    runTotal.value = data.length
  } finally {
    runLoading.value = false
  }
}
async function openRunDetail(row: WorkflowRun) {
  nodeDialogVisible.value = true
  nodeLoading.value = true
  try {
    nodeRuns.value = await listWorkflowRunNodes(row.id!)
  } finally {
    nodeLoading.value = false
  }
}
function openLog(row: WorkflowNodeRun) {
  currentLog.value = row.logText || '(无日志)'
  logDialogVisible.value = true
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
.muted {
  color: #a9aeb8;
}
.log-box {
  max-height: 420px;
  overflow: auto;
  background: #151821;
  color: #9ece6a;
  padding: 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>