<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { preflightPipelineTask, type PipelinePreflight } from '@/api/pipeline'
import type { PipelineTask } from '@/types'
import DkButton from '@/paas-ui/DkButton.vue'

const props = defineProps<{ task: PipelineTask }>()
const emit = defineEmits<{ close: [] }>()
const open = ref(true)
const bizDate = ref('')
const result = ref<PipelinePreflight>()
const loading = ref(false)
const error = ref('')
let request: AbortController | undefined
const needsDate = computed(() => props.task.writeMode === 'REPLACE_BY_BIZ_DATE'
  || props.task.sourceQuery?.includes('${bizDate}'))

watch(bizDate, () => {
  result.value = undefined
  error.value = ''
})

async function submit() {
  if (!props.task.id || loading.value || (needsDate.value && !bizDate.value)) return
  const active = new AbortController()
  request = active
  loading.value = true
  result.value = undefined
  error.value = ''
  try {
    result.value = await preflightPipelineTask(props.task.id, bizDate.value || undefined, active.signal)
  } catch (e) {
    if (!active.signal.aborted) error.value = e instanceof Error ? e.message : '预检暂时不可用'
  } finally {
    if (!active.signal.aborted) loading.value = false
  }
}

onBeforeUnmount(() => request?.abort())
</script>

<template>
  <el-dialog v-model="open" :title="`管道预检 · ${task.taskName || 'Hive 加载'}`"
    width="min(680px, 95vw)" @closed="emit('close')">
    <form class="preflight-controls" @submit.prevent="submit">
      <label v-if="needsDate" class="preflight-date">
        业务日期
        <input v-model="bizDate" :disabled="loading" type="date" required />
      </label>
      <DkButton type="submit" variant="primary" :loading="loading" :disabled="needsDate && !bizDate">开始预检</DkButton>
    </form>
    <p v-if="!result && !loading && !error" class="preflight-hint">读取源查询与目标结构，校验字段映射和业务日期。</p>
    <p v-if="loading" role="status">正在读取数据库结构…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="result">
      <el-alert title="结构预检通过" type="success" :closable="false" show-icon />
      <dl>
        <dt>目标</dt><dd>{{ result.targetDatabaseName }}.{{ result.targetTable }}</dd>
        <dt>业务日期</dt><dd>{{ result.bizDate || '—' }}</dd>
        <dt>批大小</dt><dd>{{ result.batchSize }}</dd>
        <dt>源样例</dt><dd>{{ result.sampleFound ? '已读到样例' : '未读到样例' }}</dd>
      </dl>
      <el-table :data="result.mappings" size="small" max-height="260" row-key="sourceColumn">
        <el-table-column label="Hive 字段" min-width="140">
          <template #default="{ row }">{{ row.sourceColumn }}<small>{{ row.sourceType }}</small></template>
        </el-table-column>
        <el-table-column label="目标字段" min-width="145">
          <template #default="{ row }">
            {{ row.targetColumn }} <el-tag v-if="row.partitionColumn" size="small">日期列</el-tag>
            <small>{{ row.targetType }}</small>
          </template>
        </el-table-column>
      </el-table>
      <ul class="preflight-warnings">
        <li v-for="warning in result.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </template>
  </el-dialog>
</template>

<style scoped>
.preflight-controls, dl { display:flex; flex-wrap:wrap; gap:var(--dk-space-2); }
.preflight-controls { align-items:end; margin-bottom:var(--dk-space-3); }
.preflight-date { display:grid; gap:4px; color:var(--dk-secondary); font-size:12px; }
input { min-height:36px; border:1px solid var(--dk-border); border-radius:var(--dk-radius-control);
  padding:0 var(--dk-space-2); background:var(--dk-surface); color:var(--dk-ink); font:inherit; }
.preflight-hint, dt, small { color:var(--dk-muted); }
dd { margin:0 var(--dk-space-3) 0 0; overflow-wrap:anywhere; }
small { display:block; }
.preflight-warnings { margin-bottom:0; padding-left:var(--dk-space-4); }
</style>
