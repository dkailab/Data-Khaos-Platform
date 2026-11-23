<script setup lang="ts">
import { ref, watch } from 'vue'
import { previewMaterialization, type MaterializationPreview } from '@/api/query'
import DkButton from '@/paas-ui/DkButton.vue'

const props = defineProps<{ assetId: string; bizDate: string }>()
const preview = ref<MaterializationPreview>()
const pending = ref(false)
const error = ref('')
const revision = ref(0)

watch([() => props.assetId, () => props.bizDate, revision], async ([id, date, attempt], old, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  pending.value = true
  preview.value = undefined
  error.value = ''
  try {
    const refresh = old?.length === 3 && attempt !== old[2]
    const response = await previewMaterialization(id, date, refresh, request.signal)
    if (!request.signal.aborted) preview.value = response
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '物化预览加载失败'
  } finally {
    if (!request.signal.aborted) pending.value = false
  }
}, { immediate: true })
</script>

<template>
  <section class="materialization" aria-label="物化结果预览">
    <header class="preview-header">
      <div>
        <h2>物化结果预览</h2>
        <p>{{ preview?.tableName }} <span v-if="preview">·</span> {{ bizDate }}</p>
      </div>
      <div class="preview-actions">
        <RouterLink class="dk-link"
          :to="{ name: 'AssetDetail', params: { tableId: assetId }, query: { tab: 'publication', bizDate } }">
          查看发布证据
        </RouterLink>
        <DkButton :loading="pending" @click="revision++">刷新结果</DkButton>
      </div>
    </header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <p v-else-if="pending" role="status">正在核对发布版本…</p>
    <template v-else-if="preview">
      <div class="preview-meta">
        <span>{{ preview.cacheHit ? '个人缓存命中' : '从目标库读取' }}</span>
        <span>发布 {{ preview.publishedAt.replace('T', ' ') }} UTC</span>
        <span>发布实例 {{ preview.publicationId }}</span>
        <span>最多展示 100 行</span>
      </div>
      <el-table :data="preview.result.rows" size="small" border max-height="300" empty-text="该物化分区为空">
        <el-table-column v-for="column in preview.result.columns" :key="column.columnName"
          :prop="column.columnName" :label="column.columnName" min-width="100" show-overflow-tooltip />
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.materialization {
  padding: var(--dk-space-4);
  border-bottom: 1px solid var(--dk-border);
  background: var(--onesql-bg);
  overflow-wrap: anywhere;
}
.preview-header, .preview-actions, .preview-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dk-space-2) var(--dk-space-4);
}
.preview-header { justify-content: space-between; margin-bottom: var(--dk-space-3); }
.preview-header h2 { font-size: 16px; margin: 0; }
.preview-header p { margin: var(--dk-space-1) 0 0; }
.preview-meta { margin-bottom: var(--dk-space-3); color: var(--onesql-fg-secondary, var(--dk-muted)); font-size: 13px; }
</style>
