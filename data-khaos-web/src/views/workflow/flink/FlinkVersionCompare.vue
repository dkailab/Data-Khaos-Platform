<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getFlinkVersion, type FlinkVersionView } from '@/api/flinkStudio'

const props = defineProps<{ project: string; base: FlinkVersionView }>()
const emit = defineEmits<{ close: [] }>()
const target = ref<number | undefined>()
const compared = ref<FlinkVersionView>()
const loading = ref(false)
const error = ref('')
const retry = ref(0)

watch(() => props.base, base => {
  target.value = base.revision.version === base.currentVersion ? base.currentVersion - 1 : base.currentVersion
}, { immediate: true })

watch([() => props.project, () => props.base, target, retry], async ([project, base, version], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  compared.value = undefined
  loading.value = false
  error.value = ''
  if (!version || !Number.isInteger(version) || version < 1 || version > base.currentVersion
    || version === base.revision.version) return

  loading.value = true
  try {
    const result = await getFlinkVersion(project, base.assetId, version, request.signal)
    if (!request.signal.aborted) compared.value = result
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '无法加载对比版本'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

function fields(version: FlinkVersionView) {
  const definition = version.definition
  const shared = { 名称: version.revision.name }
  if (version.kind === 'JOB') {
    return {
      ...shared,
      运行模式: definition.mode === 'BATCH' ? '批处理' : '流式',
      并行度: String(definition.parallelism ?? ''),
      函数依赖: (definition.functions ?? []).map(dep => `${dep.assetId} · v${dep.version}`).join('\n'),
      SQL: definition.sql ?? '',
    }
  }
  return { ...shared, 语言: definition.language ?? '', JVM类名: definition.className ?? '', JAR地址: definition.jarUri ?? '' }
}

const changes = computed(() => {
  if (!compared.value) return []
  const right = new Map(Object.entries(fields(compared.value)))
  return Object.entries(fields(props.base)).flatMap(([label, before]) => {
    const after = right.get(label) ?? ''
    return before === after ? [] : [{ label, before, after }]
  })
})
</script>

<template>
  <el-dialog :model-value="true" title="Flink 版本对比" append-to-body
    width="min(960px, calc(100vw - 24px))" @update:model-value="$event === false && emit('close')">
    <label for="flink-compare-version">对比版本（v1 至 v{{ base.currentVersion }}）</label>
    <el-input-number id="flink-compare-version" v-model="target" :min="1" :max="base.currentVersion"
      :precision="0" aria-label="对比版本" />
    <p v-if="loading" role="status">正在加载对比版本。</p>
    <template v-else-if="error">
      <el-alert type="error" :title="error" :closable="false" show-icon />
      <el-button text type="primary" @click="retry++">重试</el-button>
    </template>
    <el-empty v-else-if="!compared" description="请选择与基准不同的版本" :image-size="60" />
    <el-empty v-else-if="!changes.length" description="两个版本的定义相同" :image-size="60" />
    <section v-for="change in changes" :key="change.label">
      <h3>{{ change.label }}</h3>
      <div class="compare-values">
        <div><strong>基准 v{{ base.revision.version }}</strong><pre>{{ change.before || '—' }}</pre></div>
        <div><strong>对比 v{{ compared?.revision.version }}</strong><pre>{{ change.after || '—' }}</pre></div>
      </div>
    </section>
  </el-dialog>
</template>

<style scoped>
.el-input-number { margin-inline-start: var(--dk-space-2); }
.compare-values { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr)); gap: var(--dk-space-3); }
.compare-values > div { min-width: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 320px; overflow: auto; padding: var(--dk-space-2); background: var(--dk-soft); }
</style>
