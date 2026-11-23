<script setup lang="ts">
import { computed, ref } from 'vue'
import { acknowledgeWorkspaceRun, resolveWorkspaceRun, type ProjectWorkspaceRunDetail } from '@/api/project-group'
import DkButton from '@/paas-ui/DkButton.vue'

const props = defineProps<{ projectId: string; run: ProjectWorkspaceRunDetail; canSchedule: boolean }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()
const busy = ref(false)
const checked = ref(false)
const note = ref('')
const error = ref('')
const needsRecovery = computed(() => props.run.status === 'RECOVERY_REQUIRED')
const reasons: Record<string, string> = {
  QUEUE_REJECTED: '队列未受理，节点未执行',
  QUEUE_INTERRUPTED: '排队期间中断，节点未执行',
  HEARTBEAT_EXPIRED: '执行心跳已过期',
  SUBMISSION_UNCONFIRMED: '外部任务执行结果未知',
}

async function submit(action: 'ack' | 'resolve') {
  if (!props.canSchedule || busy.value) return
  if (action === 'resolve' && (!checked.value || !note.value.trim())) return
  busy.value = true
  error.value = ''
  try {
    if (action === 'ack') await acknowledgeWorkspaceRun(props.projectId, props.run.id)
    else await resolveWorkspaceRun(props.projectId, props.run.id, note.value.trim())
    emit('refresh')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作未完成，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section v-if="needsRecovery || run.status === 'FAILED'" class="run-operations" aria-label="任务告警">
    <div class="alert-heading">
      <h3>任务告警</h3>
      <span v-if="run.alertAcknowledgedAt">已确认</span>
      <DkButton v-else-if="canSchedule" :disabled="busy" @click="submit('ack')">确认告警</DkButton>
      <span v-else>待确认</span>
    </div>
    <p v-if="run.recoveryCode">{{ reasons[run.recoveryCode] || run.recoveryCode }}</p>
    <p v-if="run.recoveryResolvedAt" class="audit">核查完成 · {{ run.recoveryResolvedBy }}<br>{{ run.recoveryNote }}</p>
    <template v-if="needsRecovery">
      <p v-if="run.bizDate">业务日锁保留，等待核查。</p>
      <p v-else>运行等待核查。</p>
      <form v-if="canSchedule" class="recovery-form" @submit.prevent="submit('resolve')">
        <el-checkbox v-model="checked">已核实原执行进程和外部写入均已停止</el-checkbox>
        <el-input v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit
          aria-label="核查说明" placeholder="记录管道实例、目标分区校验结果或工单编号" />
        <DkButton type="submit" variant="primary" :disabled="busy || !checked || !note.trim()">
          {{ run.bizDate ? '完成核查并释放业务日锁' : '完成核查' }}
        </DkButton>
      </form>
    </template>
    <el-alert v-if="error" :title="error" type="warning" show-icon :closable="false" />
  </section>
</template>

<style scoped>
.run-operations { margin-top:var(--dk-space-4); padding:var(--dk-space-3); border:1px solid var(--dk-border); border-radius:var(--dk-radius-control); }
.alert-heading { display:flex; flex-wrap:wrap; align-items:center; gap:var(--dk-space-2); }
h3 { margin:0; font-size:15px; }
p { margin:var(--dk-space-2) 0; }
.alert-heading span,.audit { color:var(--dk-muted); font-size:12px; overflow-wrap:anywhere; }
.recovery-form { display:grid; justify-items:start; gap:var(--dk-space-2); }
.recovery-form :deep(.el-checkbox) { white-space:normal; height:auto; }
</style>
