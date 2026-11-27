<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listFlinkAssets, type FlinkAsset, type FlinkAssetKind } from '@/api/flinkStudio'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import FlinkRunsPanel from '@/views/workflow/flink/FlinkRunsPanel.vue'
import { projectLocation, useRealtimeProject } from './projectContext'

const { project, canRun } = useRealtimeProject()
const kind = ref<FlinkAssetKind>('JOB')
const jobs = ref<FlinkAsset[]>([])
const selection = ref<FlinkAsset>()
const keyword = ref('')
const current = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const size = 12
let generation = 0
const editor = computed(() => kind.value === 'JOB' ? '/jobs/flink/sql' : '/jobs/flink/jar')

async function load() {
  const request = ++generation
  jobs.value = []
  total.value = 0
  error.value = ''
  loading.value = !!project.value
  if (!loading.value) return
  try {
    const page = await listFlinkAssets(project.value, kind.value, current.value, size, keyword.value.trim())
    if (generation !== request) return
    jobs.value = page.records
    total.value = Number(page.total)
  } catch {
    if (generation === request) error.value = '无法加载作业，请重试。'
  } finally {
    if (generation === request) loading.value = false
  }
}

function search() {
  current.value = 1
  void load()
}

watch([project, kind], () => {
  selection.value = undefined
  keyword.value = ''
  search()
}, { immediate: true })
</script>

<template>
  <div class="operations">
    <DkPageHeader title="作业运维" description="选择作业查看运行记录、检查点、运行指标和告警。" />
    <section v-if="project" class="dk-panel jobs" aria-label="作业列表">
      <form class="filters" @submit.prevent="search">
        <el-select v-model="kind" aria-label="作业类型" class="kind">
          <el-option label="SQL 作业" value="JOB" /><el-option label="JAR 作业" value="JAR_JOB" />
        </el-select>
        <el-input v-model="keyword" clearable aria-label="作业名称或编码" placeholder="作业名称或编码" class="keyword" @clear="search" />
        <DkButton type="submit">搜索</DkButton>
        <RouterLink :to="projectLocation(editor, project)">进入作业开发</RouterLink>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="jobs" row-key="id" empty-text="暂无匹配的作业">
        <el-table-column prop="name" label="作业名称" min-width="150" />
        <el-table-column prop="code" label="编码" min-width="140" />
        <el-table-column label="运行模式" width="95">
          <template #default="{ row }">{{ row.currentMode === 'BATCH' ? '批处理' : '流式' }}</template>
        </el-table-column>
        <el-table-column label="当前版本" width="90">
          <template #default="{ row }">v{{ row.currentVersion }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }"><el-button link type="primary" @click="selection = row">查看运行</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
        layout="prev, pager, next" @current-change="load" />
    </section>
    <section v-if="selection" class="dk-panel jobs" aria-label="运行详情">
      <div class="run-header">
        <h2>{{ selection.name }} · 运行记录</h2>
        <DkButton variant="quiet" @click="selection = undefined">收起</DkButton>
      </div>
      <FlinkRunsPanel :key="selection.id" :project="project" :asset-id="selection.id" :version="selection.currentVersion"
        :can-run="canRun" :jar-job="selection.kind === 'JAR_JOB'" :streaming="selection.currentMode === 'STREAMING'" />
    </section>
  </div>
</template>

<style scoped>
.operations, .jobs { display: grid; gap: 14px; min-width: 0; }
.jobs { padding: 15px; }
.filters, .run-header { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }
.filters .kind { width: 120px; }
.filters .keyword { width: min(240px, 100%); }
.filters a { color: var(--dk-primary); margin-inline-start: auto; }
.run-header { justify-content: space-between; }
h2 { margin: 0; font-size: 17px; }
</style>
