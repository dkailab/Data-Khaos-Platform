<script setup lang="ts">
import { ref, watch } from 'vue'
import { RefreshRight } from '@element-plus/icons-vue'
import { listWorkflowTasks } from '@/api/workflow'
import type { WorkflowTaskView } from '@/types'
import { nodeMeta, RUN_STATUS_META } from './nodeCatalog'

const props = defineProps<{ modelValue: boolean; wfId?: string; workflowName?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const rows = ref<WorkflowTaskView[]>([])
const loading = ref(false)

watch(() => props.modelValue, (open) => {
  if (open) void load()
})

async function load() {
  if (!props.wfId) return
  loading.value = true
  try {
    rows.value = await listWorkflowTasks(props.wfId)
  } finally {
    loading.value = false
  }
}

function statusText(status?: string) {
  return status ? (RUN_STATUS_META[status]?.text || status) : '未运行'
}
function statusType(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'primary'
  if (status === 'PENDING') return 'warning'
  return 'info'
}
</script>

<template>
  <el-dialog :model-value="modelValue" width="960px" :title="`任务列表 · ${workflowName || ''}`"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <div class="toolbar">
      <span class="hint">展示节点配置与最近一次运行的节点状态，状态来自最近一条运行实例。</span>
      <el-button size="small" :icon="RefreshRight" :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" size="small" border empty-text="暂无节点">
      <el-table-column label="任务名称" min-width="180">
        <template #default="{ row }">
          <div class="task-name">
            <span class="dot" :style="{ background: nodeMeta(row.nodeType).color }" />
            <span>{{ row.nodeName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ nodeMeta(row.nodeType).label }}</template>
      </el-table-column>
      <el-table-column label="最近状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.lastStatus)">{{ statusText(row.lastStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">
          {{ row.lastDurationMs != null ? Math.round(row.lastDurationMs / 1000) + 's' : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="超时/重试" width="110">
        <template #default="{ row }">
          {{ row.timeoutSeconds ? Math.round(row.timeoutSeconds / 60) + '分' : '—' }}
          / {{ row.retryCount || 0 }} 次
        </template>
      </el-table-column>
      <el-table-column label="跳过" width="70">
        <template #default="{ row }">{{ row.skipped ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="独立调度" width="90">
        <template #default="{ row }">{{ row.independent ? '允许' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="executorGroup" label="执行器组" width="130" show-overflow-tooltip />
      <el-table-column prop="lastErrorMessage" label="最近错误" min-width="160" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-button type="primary" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.hint { font-size: 12px; color: #98a2b3; }
.task-name { display: flex; align-items: center; gap: 7px; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
</style>
