<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { configureFlinkKafkaAlerts, getFlinkKafkaAlerts, type FlinkKafkaAlerts } from '@/api/flinkStudio'

const props = defineProps<{
  project: string; assetId: string; runId: string; refresh: number; canConfigure: boolean
  vertex?: { id: string; name: string; status: string }
}>()
const history = ref<FlinkKafkaAlerts>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
let fetchSequence = 0
const high = ref<number | undefined>(10000)
const low = ref<number | undefined>(2000)
const labels = { OPEN: '告警中', RECOVERED: '已恢复', ENDED: '作业已结束', DISABLED: '规则已停用' } as const
const number = new Intl.NumberFormat('zh-CN')
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
const validThresholds = computed(() => high.value != null && low.value != null && high.value > low.value)

watch(() => props.refresh, async (_, __, onCleanup) => {
  if (saving.value) return
  const sequence = ++fetchSequence
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''
  try {
    const result = await getFlinkKafkaAlerts(props.project, props.assetId, props.runId, request.signal)
    if (!request.signal.aborted && sequence === fetchSequence) history.value = result
  } catch (e) {
    if (!request.signal.aborted && sequence === fetchSequence) error.value = e instanceof Error ? e.message : 'Kafka 告警读取失败'
  } finally {
    if (!request.signal.aborted && sequence === fetchSequence) loading.value = false
  }
}, { immediate: true })

async function configure(enable: boolean) {
  if (saving.value || loading.value || !props.canConfigure) return
  let rule: Parameters<typeof configureFlinkKafkaAlerts>[3] = { enabled: false }
  if (enable) {
    const vertex = props.vertex
    const upper = high.value
    const lower = low.value
    if (!vertex || vertex.status !== 'RUNNING' || upper == null || lower == null || upper <= lower) return
    rule = { enabled: true, vertexId: vertex.id, highThreshold: upper, lowThreshold: lower }
  }
  saving.value = true
  fetchSequence++
  error.value = ''
  try {
    history.value = await configureFlinkKafkaAlerts(props.project, props.assetId, props.runId, rule)
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Kafka 告警规则保存失败'
  } finally {
    saving.value = false
  }
}

function time(value: string | number | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : formatter.format(date)
}
</script>

<template>
  <section class="kafka-alerts" aria-labelledby="flink-kafka-alert-heading">
    <h3 id="flink-kafka-alert-heading">Kafka 积压告警</h3>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <p v-else-if="loading" role="status">正在读取 Kafka 告警规则。</p>
    <p v-else-if="history && !history.available" class="muted">未启用 Kafka 积压巡检。</p>
    <template v-if="history?.available">
      <div v-if="history.watch?.enabled" class="rule">
        <div>
          <strong>{{ history.watch.vertexName }}</strong>
          <p class="muted">告警 ≥ {{ number.format(history.watch.highThreshold) }} 条 · 恢复 ≤ {{ number.format(history.watch.lowThreshold) }} 条</p>
          <p class="muted">最近采样 {{ history.watch.lastLag == null ? '暂无有效值' : number.format(history.watch.lastLag) + ' 条' }}
            · {{ time(history.watch.sampledAtMs) }}</p>
        </div>
        <DkButton v-if="canConfigure" :loading="saving" :disabled="loading" @click="configure(false)">停用规则</DkButton>
      </div>
      <form v-else-if="canConfigure && vertex?.status === 'RUNNING'" class="rule" @submit.prevent="configure(true)">
        <div>
          <p>监控当前节点 · {{ vertex.name }}</p>
          <div class="thresholds">
            <label>告警阈值 <el-input-number v-model="high" aria-label="告警阈值" :min="1" :max="1000000000000" :precision="0" /></label>
            <label>恢复阈值 <el-input-number v-model="low" aria-label="恢复阈值" :min="0" :max="1000000000000" :precision="0" /></label>
          </div>
        </div>
        <DkButton type="submit" variant="primary" :loading="saving" :disabled="loading || !validThresholds">启用告警</DkButton>
      </form>
      <p v-else class="muted">当前运行未配置 Kafka 告警规则。</p>
      <el-table :data="history.alerts" row-key="id" size="small" empty-text="暂无 Kafka 积压告警">
        <el-table-column label="状态" min-width="104">
          <template #default="{ row }">{{ labels[row.status as keyof typeof labels] }}</template>
        </el-table-column>
        <el-table-column prop="vertexName" label="执行节点" min-width="148" show-overflow-tooltip />
        <el-table-column label="峰值积压" min-width="110">
          <template #default="{ row }">{{ number.format(row.peakLag) }} 条</template>
        </el-table-column>
        <el-table-column label="触发时间" min-width="140"><template #default="{ row }">{{ time(row.openedAt) }}</template></el-table-column>
        <el-table-column label="关闭时间" min-width="140"><template #default="{ row }">{{ time(row.closedAt) }}</template></el-table-column>
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.kafka-alerts { margin-top: var(--dk-space-4); min-width: 0; }
.kafka-alerts h3 { font-size: 15px; margin: 0 0 var(--dk-space-2); }
.rule, .thresholds { display: flex; flex-wrap: wrap; align-items: center; gap: var(--dk-space-2); }
.rule { justify-content: space-between; margin-bottom: var(--dk-space-2); }
.rule p { margin: var(--dk-space-1) 0; overflow-wrap: anywhere; }
.thresholds label { display: flex; align-items: center; gap: var(--dk-space-2); }
.muted { color: var(--dk-muted); }
</style>
