<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import { acknowledgePlatformAlert, listPlatformAlerts, listServiceStatuses } from '@/api/platformMonitor'
import type { PlatformAlert, PlatformServiceStatus } from '@/api/platformMonitor'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const canView = computed(() => String(user.userInfo?.id) === '1')
const services = ref<PlatformServiceStatus[]>([])
const alerts = ref<PlatformAlert[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const confirming = ref<string>()
const openOnly = ref(true)
const current = ref(1)
const size = 10
const total = ref(0)
let interval: ReturnType<typeof setInterval>
let sequence = 0

const reachable = computed(() => services.value.filter(service => service.status === 'UP').length)
const abnormal = computed(() => services.value.filter(service => service.status === 'DEGRADED' || service.status === 'DOWN').length)
const unknown = computed(() => services.value.filter(service => service.status === 'UNKNOWN').length)

const reasonText: Record<string, string> = {
  UNREGISTERED: '未发现注册实例',
  INSTANCE_UNREACHABLE: '部分或全部实例不可达',
  DISCOVERY_ERROR: '注册中心暂不可用',
}

function displayTime(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}

async function load(quiet = false) {
  if (!canView.value) return
  const request = ++sequence
  if (!quiet) loading.value = true
  try {
    const [statuses, page] = await Promise.all([
      listServiceStatuses(),
      listPlatformAlerts({ current: current.value, size, openOnly: openOnly.value }),
    ])
    if (request !== sequence) return
    services.value = statuses
    alerts.value = page.records
    total.value = Number(page.total)
    loadFailed.value = false
  } catch {
    if (request === sequence) loadFailed.value = true
  } finally {
    if (request === sequence) loading.value = false
  }
}

function changeFilter() {
  current.value = 1
  void load()
}

async function acknowledge(id: string) {
  confirming.value = id
  try {
    await acknowledgePlatformAlert(id)
    await load()
  } catch {
    // 请求拦截器显示后端错误。
  } finally {
    confirming.value = undefined
  }
}

onMounted(() => {
  void load()
  interval = setInterval(() => { if (!loading.value && !loadFailed.value) void load(true) }, 30_000)
})

onBeforeUnmount(() => {
  sequence++
  clearInterval(interval)
})
</script>

<template>
  <section class="platform-monitor">
    <DkPageHeader title="平台监控告警" description="查看平台注册实例的连通状态与可用性告警。">
      <template #actions>
        <DkButton v-if="canView" :loading="loading" @click="load()">刷新</DkButton>
      </template>
    </DkPageHeader>

    <el-alert v-if="!canView" title="此页面仅供平台管理员使用" type="warning" show-icon :closable="false" />

    <template v-else>
      <el-alert v-if="loadFailed" title="监控数据加载失败，请刷新重试" type="error" show-icon :closable="false" />
      <div class="service-summary" aria-label="服务状态汇总">
        <div><span>完全可达</span><strong>{{ reachable }}</strong></div>
        <div><span>服务异常</span><strong>{{ abnormal }}</strong></div>
        <div><span>状态未知</span><strong>{{ unknown }}</strong></div>
      </div>

      <el-card shadow="never">
        <template #header><h2>服务状态</h2></template>
        <el-table v-loading="loading" :data="services" row-key="serviceId" empty-text="暂无监控服务">
          <el-table-column prop="serviceName" label="服务" min-width="125" />
          <el-table-column label="最近探测" min-width="105">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'UP'" type="success" size="small">可达</el-tag>
              <el-tag v-else-if="row.status === 'DEGRADED'" type="warning" size="small">部分不可达</el-tag>
              <el-tag v-else-if="row.status === 'DOWN'" type="danger" size="small">不可用</el-tag>
              <el-tag v-else type="info" size="small">未知</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="可达 / 注册" width="115">
            <template #default="{ row }">{{ row.reachableCount }} / {{ row.registeredCount }}</template>
          </el-table-column>
          <el-table-column label="采样时间" min-width="170">
            <template #default="{ row }">{{ displayTime(row.checkedAt) }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="155">
            <template #default="{ row }">{{ reasonText[row.reason] || '—' }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <div class="alert-header">
            <h2>可用性告警</h2>
            <el-checkbox v-model="openOnly" @change="changeFilter">仅显示未恢复</el-checkbox>
          </div>
        </template>
        <el-table v-loading="loading" :data="alerts" row-key="id" empty-text="暂无匹配告警">
          <el-table-column prop="serviceName" label="服务" min-width="125" />
          <el-table-column label="告警原因" min-width="165">
            <template #default="{ row }">{{ reasonText[row.reason] || '服务不可达' }}</template>
          </el-table-column>
          <el-table-column label="触发时间" min-width="170">
            <template #default="{ row }">{{ displayTime(row.openedAt) }}</template>
          </el-table-column>
          <el-table-column label="恢复时间" min-width="170">
            <template #default="{ row }">{{ displayTime(row.recoveredAt) }}</template>
          </el-table-column>
          <el-table-column label="处理" width="110">
            <template #default="{ row }">
              <span v-if="row.acknowledgedAt" :title="displayTime(row.acknowledgedAt)">已确认</span>
              <el-button v-else-if="!row.recoveredAt" link type="primary"
                :disabled="!!confirming" @click="acknowledge(row.id)">确认告警</el-button>
              <span v-else>已恢复</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="monitor-pagination" small v-model:current-page="current"
          :page-size="size" :total="total" layout="prev, pager, next" @current-change="load()" />
      </el-card>
    </template>
  </section>
</template>

<style scoped>
.platform-monitor { display: grid; min-width: 0; gap: var(--dk-space-4); }
.platform-monitor > * { min-width: 0; }
.service-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--dk-space-3); }
.service-summary > div {
  display: grid;
  gap: var(--dk-space-2);
  padding: var(--dk-space-3);
  color: var(--dk-secondary);
  border: 1px solid var(--dk-border);
  border-radius: var(--dk-radius-control);
  background: var(--dk-surface);
}
.service-summary strong { color: var(--dk-ink); font-size: clamp(20px, 3vw, 32px); }
.alert-header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; }
h2 { margin: 0; font-size: 17px; }
.monitor-pagination { margin-top: var(--dk-space-3); justify-content: flex-end; }
</style>
