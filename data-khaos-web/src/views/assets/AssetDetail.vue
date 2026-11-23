<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getAsset, pageAssetFields, type AssetDetail } from '@/api/metadata'
import type { MetaColumn } from '@/types'
import DkButton from '@/paas-ui/DkButton.vue'
import AssetPublication from './AssetPublication.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'

const route = useRoute()
const id = computed(() => String(route.params.tableId || ''))
const detail = ref<AssetDetail>()
const tab = ref('fields')
const fields = ref<MetaColumn[]>([])
const fieldTotal = ref(0)
const page = ref(1)
const loading = ref(false)
const fieldsLoading = ref(false)
const error = ref(false)
const fieldsError = ref(false)
const retry = ref(0)
const fieldRetry = ref(0)
const directions = [{ title: '直接上游', upstream: true }, { title: '直接下游', upstream: false }]

watch([id, retry], async ([assetId], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  detail.value = undefined
  page.value = 1
  tab.value = route.query.tab === 'publication' || route.query.runId ? 'publication' : 'fields'
  loading.value = true
  error.value = false
  try {
    const result = await getAsset(assetId, request.signal)
    if (!request.signal.aborted) detail.value = result
  } catch {
    if (!request.signal.aborted) error.value = true
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

watch([detail, page, fieldRetry], async ([item, current], _, onCleanup) => {
  fields.value = []
  fieldsError.value = false
  fieldsLoading.value = false
  if (!item) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  fieldsLoading.value = true
  try {
    const result = await pageAssetFields(item.table.id, current, request.signal)
    if (!request.signal.aborted) {
      fields.value = result.records
      fieldTotal.value = result.total
    }
  } catch {
    if (!request.signal.aborted) fieldsError.value = true
  } finally {
    if (!request.signal.aborted) fieldsLoading.value = false
  }
})
</script>

<template>
  <div class="asset-page">
    <RouterLink class="dk-link" :to="{ name: 'AssetCatalog', query: { q: route.query.q } }">← 返回数据地图</RouterLink>
    <DkPageHeader :title="detail?.table.tableName || '资产详情'"
      :description="detail ? `${detail.table.datasourceId} / ${detail.table.databaseName}` : undefined" />
    <section v-if="loading || error" v-loading="loading" class="dk-panel asset-panel" aria-live="polite">
      <p v-if="loading">正在加载资产详情</p>
      <div v-else role="alert">无法查看此资产。<DkButton @click="retry++">重试</DkButton></div>
    </section>
    <template v-else-if="detail">
      <section class="dk-panel asset-panel">
        <h2>资产概况</h2>
        <p>{{ detail.table.description || '暂无业务说明' }}</p>
        <dl><dt>类型</dt><dd>{{ detail.table.tableType || '—' }}</dd><dt>元数据同步时间</dt><dd class="dk-numeric">{{ detail.table.syncTime?.replace('T', ' ') || '—' }}</dd></dl>
      </section>
      <section class="dk-panel asset-panel">
        <el-tabs v-model="tab">
          <el-tab-pane label="字段" name="fields">
            <div v-if="fieldsError" role="alert">暂时无法加载字段。<DkButton @click="fieldRetry++">重试</DkButton></div>
            <template v-else>
              <el-table v-loading="fieldsLoading" :data="fields" :empty-text="fieldsLoading ? '加载中' : '暂无字段记录'">
                <el-table-column prop="columnName" label="字段" min-width="145" />
                <el-table-column prop="columnType" label="类型" min-width="105" />
                <el-table-column prop="bizName" label="业务名称" min-width="125" />
                <el-table-column label="说明" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.bizComment || row.description || '—' }}</template>
                </el-table-column>
              </el-table>
              <el-pagination v-if="fieldTotal > 20" v-model:current-page="page" :page-size="20"
                :total="fieldTotal" layout="total, prev, pager, next" />
            </template>
          </el-tab-pane>
          <el-tab-pane label="发布记录" name="publication">
            <AssetPublication v-if="tab === 'publication'" :asset-id="detail.table.id" />
          </el-tab-pane>
          <el-tab-pane label="血缘" name="lineage">
            <div class="relations">
              <section v-for="direction in directions" :key="direction.title">
                <h2>{{ direction.title }}</h2>
                <ul>
                  <li v-for="(relation, index) in detail.relations.filter(link => link.upstream === direction.upstream)" :key="index">
                    <RouterLink class="dk-link" :to="{ name: 'AssetDetail', params: { tableId: relation.table.id }, query: { q: route.query.q } }">
                      {{ relation.table.databaseName }}.{{ relation.table.tableName }}
                    </RouterLink>
                    <small>{{ relation.table.datasourceId }} · {{ relation.type || '关联' }}</small>
                  </li>
                  <li v-if="!detail.relations.some(link => link.upstream === direction.upstream)">暂无可见关系</li>
                </ul>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </template>
  </div>
</template>

<style scoped>
h2 { font-size: 16px; margin: 0 0 8px; }
dl { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 0; }
dt, small { color: var(--dk-muted); }
dd { margin: 0 16px 0 0; }
.relations { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
ul { list-style: none; margin: 0; padding: 0; }
li { border-top: 1px solid var(--dk-border); padding: 8px 0; overflow-wrap: anywhere; }
small { display: block; }
</style>
