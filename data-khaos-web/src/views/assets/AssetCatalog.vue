<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { searchAssets, type AssetTable } from '@/api/metadata'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const rows = ref<AssetTable[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const retry = ref(0)
const query = computed(() => typeof route.query.q === 'string' ? route.query.q.slice(0, 100).trim() : '')
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isSafeInteger(value) && value > 0 ? value : 1
})
const linkQuery = computed(() => ({
  q: query.value || undefined,
  page: page.value > 1 ? String(page.value) : undefined,
}))

function navigate(q: string, current = 1) {
  const search = q.slice(0, 100).trim()
  if (search === query.value && current === page.value) {
    retry.value++
    return
  }
  router.push({ name: 'AssetCatalog', query: {
    q: search || undefined, page: current > 1 ? String(current) : undefined,
  } })
}

watch([query, page, retry], async ([q, current], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  keyword.value = q
  loading.value = true
  error.value = ''
  rows.value = []
  try {
    const result = await searchAssets(q, current, request.signal)
    if (!request.signal.aborted) {
      rows.value = result.records
      total.value = result.total
      const lastPage = Math.max(1, Math.ceil(result.total / 20))
      if (current > lastPage) navigate(q, lastPage)
    }
  } catch {
    if (!request.signal.aborted) error.value = '资产目录暂时无法加载。'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <div class="asset-page">
    <DkPageHeader title="数据地图" description="查看当前账号可访问的数据表、字段与直接血缘。" />
    <section class="dk-panel asset-panel" aria-label="资产目录">
      <form class="filter-row" @submit.prevent="navigate(keyword)">
        <el-input v-model="keyword" clearable :prefix-icon="Search" maxlength="100" aria-label="资产关键词" placeholder="表名、注释或字段" />
        <DkButton variant="primary" type="submit">搜索</DkButton>
      </form>
      <div v-if="error" class="fallback" role="alert">
        {{ error }} <DkButton @click="retry++">重试</DkButton>
      </div>
      <template v-else>
        <el-table v-loading="loading" :data="rows" :empty-text="loading ? '加载中' : '没有可见的匹配资产'">
          <el-table-column label="表名" min-width="165">
            <template #default="{ row }">
              <RouterLink class="dk-link dk-mono" :to="{ name: 'AssetDetail', params: { tableId: row.id }, query: linkQuery }">
                {{ row.tableName }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="datasourceId" label="数据源 ID" min-width="125" show-overflow-tooltip />
          <el-table-column prop="databaseName" label="数据库" min-width="125" show-overflow-tooltip />
          <el-table-column prop="tableType" label="类型" width="84" />
          <el-table-column prop="description" label="业务说明" min-width="170" show-overflow-tooltip />
        </el-table>
        <el-pagination v-if="total > 20" :current-page="page" :page-size="20" :total="total"
          layout="total, prev, pager, next" @current-change="navigate(query, $event)" />
      </template>
    </section>
  </div>
</template>

<style scoped>
.filter-row { display: flex; gap: 8px; margin-bottom: 14px; }
.filter-row .el-input { max-width: 360px; }
.fallback { display: flex; align-items: center; gap: 10px; color: var(--dk-danger); }
</style>
