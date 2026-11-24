<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import FlinkCheckpointDialog from './FlinkCheckpointDialog.vue'
import FlinkMetricsDialog from './FlinkMetricsDialog.vue'
import { cancelFlinkRun, listFlinkRuns, refreshFlinkRun, resumeFlinkRun, stopFlinkRun, submitFlinkRun } from '@/api/flinkStudio'
import type { FlinkRun, FlinkRunState } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assetId: string; version: number; canRun: boolean; streaming: boolean }>()
const runs = ref<FlinkRun[]>([])
const activeRun = ref<FlinkRun | null>(null)
const enabled = ref(false)
const total = ref(0)
const current = ref(1)
const size = 10
const loading = ref(false)
const working = ref(false)
const error = ref('')
const checkpointRun = ref<string>()
const metricsRun = ref<string>()
let sequence = 0
let requestId = ''
const recoveryRequests = new Map<string, string>()
let closed = false
const canStart = computed(() => enabled.value && props.canRun && !loading.value && !working.value && !activeRun.value)
const activeOutsidePage = computed(() => {
  const row = activeRun.value
  return row && !runs.value.some(item => item.id === row.id) ? row : null
})

const labels: Record<FlinkRunState, string> = {
  PREPARING: '准备中', SUBMITTING: '提交中', RECONCILING: '待核查', SUBMITTED: '引擎已受理',
  CANCEL_REQUESTED: '取消中', STOP_REQUESTED: '保存停机中', STOPPED: '已保存停机', FINISHED: '已完成', FAILED: '失败', CANCELED: '已取消',
}

function terminal(state: FlinkRunState) {
  return ['STOPPED', 'FINISHED', 'FAILED', 'CANCELED'].includes(state)
}

function newRequestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 15) | 64
  bytes[8] = (bytes[8]! & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

async function load() {
  const token = ++sequence
  loading.value = true
  error.value = ''
  try {
    const response = await listFlinkRuns(props.project, props.assetId, current.value, size)
    if (token !== sequence) return
    enabled.value = response.enabled
    runs.value = response.page.records
    activeRun.value = response.activeRun === undefined
      ? runs.value.find(row => !terminal(row.state)) ?? null
      : response.activeRun
    total.value = Number(response.page.total)
  } catch (e) {
    if (token === sequence) {
      enabled.value = false
      runs.value = []
      activeRun.value = null
      total.value = 0
      error.value = e instanceof Error ? e.message : '无法加载运行记录'
    }
  } finally {
    if (token === sequence) loading.value = false
  }
}

async function submit() {
  if (!canStart.value) return
  const version = props.version
  try {
    await ElMessageBox.confirm(`提交 v${version} 到绑定的 Flink 集群。`, '提交 Flink 作业', {
      confirmButtonText: '提交', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return
  }
  if (closed || version !== props.version || !canStart.value) return
  const key = requestId || (requestId = newRequestId())
  working.value = true
  error.value = ''
  try {
    const saved = await submitFlinkRun(props.project, props.assetId, version, key)
    if (closed) return
    requestId = ''
    ElMessage.info(labels[saved.state])
    current.value = 1
    await load()
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '提交未确认，请重试同一请求'
  } finally {
    working.value = false
  }
}

async function recover(row: FlinkRun) {
  if (!canStart.value || !row.recoverable) return
  const key = recoveryRequests.get(row.id) ?? newRequestId()
  recoveryRequests.set(row.id, key)
  working.value = true
  error.value = ''
  try {
    const result = await resumeFlinkRun(props.project, props.assetId, row.id, key)
    if (!closed) {
      recoveryRequests.delete(row.id)
      ElMessage.info(`恢复 v${result.version} · ${labels[result.state]}`)
      current.value = 1
      await load()
    }
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '恢复请求失败'
  } finally {
    working.value = false
  }
}

async function update(row: FlinkRun, action: 'refresh' | 'cancel' | 'stop') {
  if (working.value || loading.value) return
  if (action !== 'refresh') {
    const savepoint = action === 'stop'
    try {
      await ElMessageBox.confirm(savepoint ? '保存作业断点并停机，可从该保存点恢复。' : `取消引擎作业 ${row.engineJobId}？`,
        savepoint ? '保存停机' : '取消运行', {
          confirmButtonText: savepoint ? '保存停机' : '确认取消', cancelButtonText: '返回', type: 'warning',
        })
    } catch {
      return
    }
  }
  if (closed || working.value || loading.value) return
  working.value = true
  error.value = ''
  try {
    const api = { refresh: refreshFlinkRun, cancel: cancelFlinkRun, stop: stopFlinkRun }[action]
    await api(props.project, props.assetId, row.id)
    if (!closed) await load()
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '运行操作失败'
  } finally {
    working.value = false
  }
}

const pendingSubmission = computed(() => {
  const row = activeRun.value
  return row && ['SUBMITTING', 'RECONCILING'].includes(row.state) ? row : null
})

watch([pendingSubmission, enabled, loading, working, error], ([row, bound, busy, changing, failed], _, onCleanup) => {
  if (!row || !bound || busy || changing || failed) return
  const timer = window.setTimeout(() => { void update(row, 'refresh') }, 10_000)
  onCleanup(() => window.clearTimeout(timer))
}, { immediate: true })

watch(() => props.version, () => { requestId = '' })
watch([() => props.project, () => props.assetId], () => {
  current.value = 1
  checkpointRun.value = undefined
  metricsRun.value = undefined
  void load()
}, { immediate: true })
onBeforeUnmount(() => { sequence++; closed = true })
</script>

<template>
  <section class="runs-panel" aria-label="Flink 运行记录">
    <header class="runs-actions">
      <h3>运行记录</h3>
      <DkButton :loading="loading" :disabled="working" @click="load">刷新列表</DkButton>
      <DkButton variant="primary" :disabled="!canStart" :loading="working" @click="submit">
        提交 v{{ version }}
      </DkButton>
    </header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <p v-else-if="!loading && !enabled" class="runs-hint">当前项目未绑定 Flink 运行集群。</p>
    <p v-else-if="!canRun" class="runs-hint">提交及取消需要项目调度权限。</p>
    <p v-if="pendingSubmission && enabled && !error" class="runs-hint">
      正在核查版本 v{{ pendingSubmission.version }} 的提交结果。
    </p>
    <div v-if="activeOutsidePage" class="runs-active">
      <span>活动运行 v{{ activeOutsidePage.version }} · {{ labels[activeOutsidePage.state] }}</span>
      <el-button link type="primary" :disabled="working || loading || !enabled"
        @click="update(activeOutsidePage, 'refresh')">刷新活动运行</el-button>
    </div>
    <el-table v-loading="loading" :data="runs" row-key="id" empty-text="暂无作业运行">
      <el-table-column label="版本" width="72"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
      <el-table-column label="状态" min-width="108">
        <template #default="{ row }">{{ labels[row.state as FlinkRunState] }}<br v-if="row.engineState" /><small>{{ row.engineState }}</small></template>
      </el-table-column>
      <el-table-column prop="engineJobId" label="引擎 Job ID" min-width="145" show-overflow-tooltip />
      <el-table-column label="说明" min-width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.restoredFromRunId ? '恢复自 ' + row.restoredFromRunId : row.message }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="165">
        <template #default="{ row }">{{ row.createTime?.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="282">
        <template #default="{ row }">
          <el-button v-if="row.engineJobId" link type="primary" @click="metricsRun = row.id">运行指标</el-button>
          <el-button v-if="row.engineJobId" link type="primary" @click="checkpointRun = row.id">检查点</el-button>
          <el-button v-if="!terminal(row.state)" link type="primary" :disabled="working" @click="update(row, 'refresh')">刷新</el-button>
          <el-button v-if="canRun && ['SUBMITTED', 'CANCEL_REQUESTED'].includes(row.state) && row.engineJobId"
            link type="danger" :disabled="working" @click="update(row, 'cancel')">取消</el-button>
          <el-button v-if="canRun && streaming && row.version === version && ['SUBMITTED', 'STOP_REQUESTED'].includes(row.state) && row.engineJobId"
            link type="primary" :disabled="working" @click="update(row, 'stop')">保存停机</el-button>
          <el-button v-if="canRun && enabled && row.recoverable" link type="primary" :disabled="!canStart"
            @click="recover(row)">从断点恢复</el-button>
        </template>
      </el-table-column>
    </el-table>
    <FlinkCheckpointDialog v-if="checkpointRun" :project="project" :asset-id="assetId"
      :run-id="checkpointRun" @close="checkpointRun = undefined" />
    <FlinkMetricsDialog v-if="metricsRun" :key="metricsRun" :project="project" :asset-id="assetId"
      :run-id="metricsRun" @close="metricsRun = undefined" />
    <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
      :disabled="working" layout="prev, pager, next" @current-change="load" />
  </section>
</template>

<style scoped>
.runs-panel { margin-top: var(--dk-space-4); min-width: 0; }
.runs-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--dk-space-2); }
.runs-actions h3 { margin: 0 auto 0 0; }
.runs-active { display: flex; align-items: center; flex-wrap: wrap; gap: var(--dk-space-2); }
.runs-hint { color: var(--dk-muted); }
</style>
