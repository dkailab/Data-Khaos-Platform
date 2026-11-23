<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { getAssetEvidence, getRunEvidence, type AssetEvidence, type RunEvidence } from '@/api/metadata'
import DkButton from '@/paas-ui/DkButton.vue'
import DkStatus from '@/paas-ui/DkStatus.vue'

const props = defineProps<{ assetId: string }>()
const route = useRoute()
const router = useRouter()
const bizDate = computed(() => typeof route.query.bizDate === 'string' ? route.query.bizDate : '')
const runId = computed(() => typeof route.query.runId === 'string' ? route.query.runId : '')
const evidence = ref<AssetEvidence>()
const run = ref<RunEvidence>()
const publication = computed(() => evidence.value?.publishedInstance)
const busy = ref(false)
const runBusy = ref(false)
const error = ref(false)
const runError = ref(false)
const retry = ref(0)
const runRetry = ref(0)

watch([() => props.assetId, bizDate, retry], async ([assetId, date], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  evidence.value = undefined
  busy.value = true
  error.value = false
  try {
    const result = await getAssetEvidence(assetId, date, request.signal)
    if (!request.signal.aborted) evidence.value = result
  } catch {
    if (!request.signal.aborted) error.value = true
  } finally {
    if (!request.signal.aborted) busy.value = false
  }
}, { immediate: true })

watch([runId, runRetry], async ([id], _, onCleanup) => {
  run.value = undefined
  runError.value = false
  runBusy.value = false
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  runBusy.value = true
  try {
    const result = await getRunEvidence(id, request.signal)
    if (!request.signal.aborted) run.value = result
  } catch {
    if (!request.signal.aborted) runError.value = true
  } finally {
    if (!request.signal.aborted) runBusy.value = false
  }
}, { immediate: true })

function chooseDate(date?: string) {
  void router.replace({ query: { ...route.query, tab: 'publication', bizDate: date || undefined, runId: undefined } })
}

function runLink(id?: string) {
  return { query: { ...route.query, tab: 'publication', runId: id } }
}
</script>

<template>
  <div class="publication-controls">
    <label for="publication-date">业务日期</label>
    <el-date-picker id="publication-date" type="date" value-format="YYYY-MM-DD" placeholder="最近发布"
      :model-value="bizDate" @update:model-value="chooseDate" />
  </div>
  <section v-loading="busy" class="publication-section" aria-live="polite">
    <div v-if="error" role="alert">暂时无法加载发布记录。<DkButton @click="retry++">重试</DkButton></div>
    <p v-else-if="busy">正在查询发布记录</p>
    <template v-else-if="publication">
      <h2>当前发布</h2>
      <dl>
        <dt>业务日期</dt><dd>{{ publication.bizDate }}</dd>
        <dt>管道实例</dt><dd>{{ publication.id }}</dd>
        <dt>质量快照</dt><dd>{{ evidence?.qualityPass ? `${evidence.qualityPass.tableName} · ${evidence.qualityPass.id}` : '暂无关联快照' }}</dd>
      </dl>
      <RouterLink v-if="publication.materializationGeneration && evidence?.qualityPass && publication.bizDate"
        class="dk-link preview-link" :to="{ path: '/query/onesql', query: { materialization: assetId, bizDate: publication.bizDate } }">
        在 OneSQL 预览物化
      </RouterLink>
      <RouterLink v-if="publication.workflowRunId" class="dk-link" :to="runLink(publication.workflowRunId)">查看生产运行</RouterLink>
    </template>
    <p v-else>暂无可追溯的发布记录</p>
  </section>
  <section v-if="runId" v-loading="runBusy" class="publication-section">
    <div class="publication-controls">
      <h2>运行证据</h2>
      <RouterLink class="dk-link" :to="runLink()">关闭</RouterLink>
    </div>
    <div v-if="runError" role="alert">无法查看此运行。<DkButton @click="runRetry++">重试</DkButton></div>
    <template v-else-if="run">
      <p>
        {{ run.run.wfName || run.run.id }}
        <el-tag v-if="run.run.runStatus === 'RECOVERY_REQUIRED'" type="warning">待核查</el-tag>
        <DkStatus v-else :status="run.run.runStatus === 'SUCCESS' ? 'success' : run.run.runStatus === 'FAILED' ? 'failed' : run.run.runStatus === 'STOP' ? 'blocked' : run.run.runStatus === 'PENDING' ? 'queued' : 'running'" />
      </p>
      <el-table :data="run.nodes" empty-text="暂无节点证据">
        <el-table-column prop="nodeName" label="节点" min-width="115" />
        <el-table-column prop="status" label="状态" min-width="100" />
        <el-table-column prop="refId" label="管道实例或质量快照" min-width="200" />
        <el-table-column label="输入与输出" min-width="225">
          <template #default="{ row }">{{ row.inputAssets.join('、') || '—' }} → {{ row.outputAssets.join('、') || '—' }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="失败原因" min-width="220" show-overflow-tooltip />
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.publication-section { margin-top: 20px; min-height: 44px; }
.publication-controls, dl { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; }
.publication-controls h2 { margin: 0; }
h2 { font-size: 16px; }
.preview-link { margin-right: 16px; }
dt { color: var(--dk-muted); }
dd { margin: 0; overflow-wrap: anywhere; }
</style>
