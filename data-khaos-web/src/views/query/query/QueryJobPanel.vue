<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { cancelQueryJob, listQueryJobs, readQueryJobResult } from '@/api/query'
import DkButton from '@/paas-ui/DkButton.vue'
import DkStatus from '@/paas-ui/DkStatus.vue'
import QueryUsagePanel from './QueryUsagePanel.vue'
import type { QueryJob, QueryJobStatus, QueryResult } from '@/types'

const emit = defineEmits<{
  showResult: [result: QueryResult]
  restoreSql: [job: QueryJob]
}>()

const records = ref<QueryJob[]>([])
const current = ref(1)
const size = 10
const total = ref(0)
const status = ref<QueryJobStatus>()
const submittedOn = ref<string>()
const usageOpen = ref(false)
const loading = ref(false)
const failed = ref(false)
const busyJob = ref<string>()
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
let closed = false

const labels: Record<QueryJobStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  CANCEL_REQUESTED: '正在停止接收结果',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}
const sharedStatus = { QUEUED: 'queued', RUNNING: 'running', SUCCESS: 'success', FAILED: 'failed' } as const

function statusStyle(value: QueryJobStatus) {
  return value in sharedStatus ? sharedStatus[value as keyof typeof sharedStatus] : undefined
}

async function load(quiet = false) {
  if (timer) clearTimeout(timer)
  const sequence = ++requestSequence
  if (!quiet) loading.value = true
  try {
    const page = await listQueryJobs({
      current: current.value, size, status: status.value || undefined, submittedOn: submittedOn.value,
    })
    if (sequence !== requestSequence || closed) return
    records.value = page.records
    total.value = Number(page.total)
    failed.value = false
    if (records.value.some(job => ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(job.status))) {
      timer = setTimeout(() => void load(true), 2500)
    }
  } catch {
    if (sequence === requestSequence && !closed) failed.value = true
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function showLatest() {
  status.value = undefined
  submittedOn.value = undefined
  current.value = 1
  await load()
}

function selectDay(day: string) {
  status.value = undefined
  submittedOn.value = day
  void filterChanged()
}

function clearDay() {
  submittedOn.value = undefined
  void filterChanged()
}

async function filterChanged() {
  current.value = 1
  await load()
}

async function viewResult(job: QueryJob) {
  busyJob.value = job.id
  try {
    emit('showResult', await readQueryJobResult(job.id))
  } catch {
    await load(true)
  } finally {
    busyJob.value = undefined
  }
}

async function cancel(job: QueryJob) {
  busyJob.value = job.id
  try {
    await cancelQueryJob(job.id)
    await load()
  } catch {
    await load(true)
  } finally {
    busyJob.value = undefined
  }
}

function time(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(() => void load())
onBeforeUnmount(() => {
  closed = true
  if (timer) clearTimeout(timer)
})
defineExpose({ showLatest })
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="jobs-header">
        <strong>查询任务</strong>
        <div class="jobs-actions">
          <el-select v-model="status" clearable placeholder="全部状态" aria-label="任务状态" @change="filterChanged">
            <el-option v-for="(label, value) in labels" :key="value" :label="label" :value="value" />
          </el-select>
          <DkButton :aria-expanded="usageOpen" @click="usageOpen = !usageOpen">
            {{ usageOpen ? '收起查询用量' : '查询用量' }}
          </DkButton>
          <DkButton :loading="loading" @click="load()">刷新</DkButton>
        </div>
      </div>
    </template>
    <QueryUsagePanel v-if="usageOpen" @select-day="selectDay" />
    <div v-if="submittedOn" class="jobs-day-filter">
      <el-tag closable @close="clearDay" aria-label="清除提交日期">提交日期 {{ submittedOn }}</el-tag>
    </div>
    <el-alert v-if="failed" type="error" title="查询任务加载失败，请刷新重试" :closable="false" show-icon />
    <el-table v-loading="loading" :data="records" empty-text="暂无查询任务" row-key="id">
      <el-table-column label="SQL" min-width="215" show-overflow-tooltip>
        <template #default="{ row }">
          <el-button link type="primary" @click="emit('restoreSql', row)">{{ row.sqlText }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="155">
        <template #default="{ row }">
          <DkStatus v-if="statusStyle(row.status)" :status="statusStyle(row.status)!" />
          <el-tag v-else type="info" size="small">{{ labels[row.status as QueryJobStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="170">
        <template #default="{ row }">{{ time(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="结果" min-width="165" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.errorMessage">{{ row.errorMessage }}</span>
          <span v-else-if="row.resultAvailable">{{ row.rowCount ?? 0 }} 行</span>
          <span v-else-if="row.status === 'SUCCESS'">结果已过期</span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="185">
        <template #default="{ row }">
          <el-button v-if="row.resultAvailable" link type="primary" :disabled="!!busyJob" @click="viewResult(row)">
            查看结果
          </el-button>
          <el-button v-if="row.status === 'QUEUED' || row.status === 'RUNNING'" link :disabled="!!busyJob" @click="cancel(row)">
            {{ row.status === 'RUNNING' ? '停止接收结果' : '取消排队' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="jobs-pagination" small v-model:current-page="current" :page-size="size" :total="total"
      layout="prev, pager, next" @current-change="load()" />
  </el-card>
</template>

<style scoped>
.jobs-header, .jobs-actions {
  display: flex;
  align-items: center;
  gap: var(--dk-space-3);
  flex-wrap: wrap;
}
.jobs-header { justify-content: space-between; }
.jobs-actions :deep(.el-select) { width: 170px; }
.jobs-day-filter { margin-bottom: var(--dk-space-3); }
.jobs-pagination { margin-top: var(--dk-space-3); justify-content: flex-end; }
</style>
