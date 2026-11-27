<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import FlinkCheckpointDialog from './FlinkCheckpointDialog.vue'
import FlinkMetricsDialog from './FlinkMetricsDialog.vue'
import { cancelFlinkRun, listFlinkRuns, refreshFlinkRun, resumeFlinkRun, stopFlinkRun, submitFlinkRun, validateFlinkSql } from '@/api/flinkStudio'
import type { FlinkRun, FlinkRunState } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assetId: string; version: number; canRun: boolean; jarJob?: boolean; streaming: boolean; hideLaunchActions?: boolean }>()
const runs = ref<FlinkRun[]>([])
const enabled = ref(false)
const total = ref(0)
const current = ref(1)
const size = 10
const loading = ref(false)
const working = ref(false)
const error = ref('')
const validation = ref('')
const validating = ref(false)
let validationSequence = 0
const checkpointRun = ref<string>()
const metricsRun = ref<string>()
let sequence = 0
let requestId = ''
const recoveryRequests = new Map<string, string>()
let closed = false
const active = computed(() => runs.value.some(row => !terminal(row.state)))

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
    total.value = Number(response.page.total)
  } catch (e) {
    if (token === sequence) {
      enabled.value = false
      runs.value = []
      error.value = e instanceof Error ? e.message : '无法加载运行记录'
    }
  } finally {
    if (token === sequence) loading.value = false
  }
}

async function validateSql() {
  if (props.jarJob || !enabled.value || validating.value || working.value) return
  const attempt = ++validationSequence
  validating.value = true
  validation.value = ''
  error.value = ''
  try {
    const result = await validateFlinkSql(props.project, props.assetId, props.version)
    if (attempt === validationSequence) {
      validation.value = `v${result.version} 通过 Flink 校验 · ${result.statementCount} 条语句`
    }
  } catch (e) {
    if (attempt === validationSequence) error.value = e instanceof Error ? e.message : 'Flink SQL 校验失败'
  } finally {
    if (attempt === validationSequence) validating.value = false
  }
}

async function submit() {
  if (working.value || validating.value || !enabled.value || !props.canRun) return
  const version = props.version
  try {
    await ElMessageBox.confirm(`提交 v${version} 到绑定的 Flink 集群。`, '提交 Flink 作业', {
      confirmButtonText: '提交', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return
  }
  if (closed || version !== props.version) return
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
    if (!closed && saved.state === 'FAILED' && saved.message) error.value = saved.message
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '提交未确认，请重试同一请求'
  } finally {
    working.value = false
  }
}

async function recover(row: FlinkRun) {
  if (working.value || active.value || !row.recoverable) return
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
  if (working.value) return
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
  if (closed) return
  working.value = true
  error.value = ''
  try {
    const api = { refresh: refreshFlinkRun, cancel: cancelFlinkRun, stop: stopFlinkRun }[action]
    const updated = await api(props.project, props.assetId, row.id)
    if (!closed) runs.value = runs.value.map(item => item.id === updated.id ? updated : item)
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '运行操作失败'
  } finally {
    working.value = false
  }
}

watch([() => props.project, () => props.assetId, () => props.version], () => {
  requestId = ''
  validationSequence++
  validating.value = false
  validation.value = ''
})
watch([() => props.project, () => props.assetId], () => {
  checkpointRun.value = undefined
  metricsRun.value = undefined
  void load()
}, { immediate: true })
onBeforeUnmount(() => { sequence++; validationSequence++; closed = true })
</script>

<template>
  <section class="runs-panel" aria-label="Flink 运行记录">
    <header class="runs-actions">
      <h3>运行记录</h3>
      <DkButton :loading="loading" :disabled="working" @click="load">刷新列表</DkButton>
      <DkButton v-if="!hideLaunchActions && !jarJob" :disabled="!enabled || loading || working" :loading="validating" @click="validateSql">
        校验 SQL
      </DkButton>
      <DkButton v-if="!hideLaunchActions" variant="primary" :disabled="!enabled || !canRun || active || loading || validating" :loading="working" @click="submit">
        提交 v{{ version }}
      </DkButton>
    </header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-alert v-else-if="validation" type="success" :title="validation" :closable="false" show-icon />
    <p v-else-if="!loading && !enabled" class="runs-hint">{{ jarJob ? '当前项目尚未启用 JAR 提交，请核对集群绑定和已授权的 JAR ID。' : '当前项目未绑定 Flink 运行集群。' }}</p>
    <p v-else-if="!canRun" class="runs-hint">提交及取消需要项目调度权限。</p>
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
          <el-button v-if="canRun && streaming && row.version === version && (row.state === 'STOP_REQUESTED' || row.state === 'SUBMITTED' && row.engineState === 'RUNNING') && row.engineJobId"
            link type="primary" :disabled="working" @click="update(row, 'stop')">保存停机</el-button>
          <el-button v-if="canRun && enabled && row.recoverable" link type="primary" :disabled="working || active"
            @click="recover(row)">从断点恢复</el-button>
        </template>
      </el-table-column>
    </el-table>
    <FlinkCheckpointDialog v-if="checkpointRun" :project="project" :asset-id="assetId"
      :run-id="checkpointRun" @close="checkpointRun = undefined" />
    <FlinkMetricsDialog v-if="metricsRun" :key="metricsRun" :project="project" :asset-id="assetId"
      :run-id="metricsRun" :can-configure="canRun" @close="metricsRun = undefined" />
    <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
      layout="prev, pager, next" @current-change="load" />
  </section>
</template>

<style scoped>
.runs-panel { margin-top: var(--dk-space-4); min-width: 0; }
.runs-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--dk-space-2); }
.runs-actions h3 { margin: 0 auto 0 0; }
.runs-hint { color: var(--dk-muted); }
</style>
