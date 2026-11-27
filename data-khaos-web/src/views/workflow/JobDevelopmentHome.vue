<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ArrowRight, Collection, EditPen, Files, RefreshRight, Share } from '@element-plus/icons-vue'
import { RouterLink } from 'vue-router'
import { listFlinkAssets, type FlinkMode } from '@/api/flinkStudio'
import { pageWorkspaceTasks } from '@/api/project-group'
import DkButton from '@/paas-ui/DkButton.vue'
import { useModuleStore } from '@/stores/module'
import { projectLocation, useRealtimeProject } from '@/views/realtime/projectContext'

const { project } = useRealtimeProject()
const modules = useModuleStore()
const hasFeature = (key: string) => !modules.loaded || modules.categories.some(category => category.features.some(feature => feature.key === key))
const hasCompute = computed(() => hasFeature('dev_flink'))
const hasOffline = computed(() => hasFeature('dev_workflow'))
const hasOneSql = computed(() => hasFeature('dev_onesql'))
const inventory = [
  { kind: 'JOB', mode: 'STREAMING', title: '实时 SQL 作业', icon: EditPen, path: '/jobs/realtime/sql' },
  { kind: 'JAR_JOB', mode: 'STREAMING', title: '实时程序作业', icon: Files, path: '/jobs/realtime/jar' },
  { kind: 'JOB', mode: 'BATCH', title: '批处理 SQL 作业', icon: EditPen, path: '/jobs/offline/sql' },
  { kind: 'JAR_JOB', mode: 'BATCH', title: '批处理程序作业', icon: Files, path: '/jobs/offline/jar' },
  { kind: 'WORKFLOW', mode: 'BATCH', title: '调度工作流', icon: Share, path: '/jobs/offline' },
  { kind: 'FUNCTION', mode: undefined, title: '函数库', icon: Collection, path: '/jobs/functions' },
] as const
const availableInventory = computed(() => inventory.filter(item => item.kind === 'WORKFLOW' ? hasOffline.value : hasCompute.value))
const groups: { title: string; mode?: FlinkMode }[] = [
  { title: '实时作业', mode: 'STREAMING' },
  { title: '离线作业', mode: 'BATCH' },
  { title: '共享资产' },
]
const inventoryGroups = computed(() => groups.map(group => ({
  ...group, items: availableInventory.value.filter(item => item.mode === group.mode),
})).filter(group => group.items.length))
const stats = reactive<Partial<Record<typeof inventory[number]['path'], number | '统计中' | '读取失败'>>>({})
let generation = 0

function load() {
  const request = ++generation
  const id = project.value
  for (const { kind, mode, path } of availableInventory.value) {
    delete stats[path]
    if (!id) continue
    stats[path] = '统计中'
    const result = kind === 'WORKFLOW'
      ? pageWorkspaceTasks(id, { kind, current: 1, size: 1 })
      : listFlinkAssets(id, kind, 1, 1, undefined, mode)
    void result.then(page => {
      if (request !== generation) return
      const count = Number(page.total)
      stats[path] = page.total != null && Number.isInteger(count) && count >= 0 ? count : '读取失败'
    }).catch(() => {
      if (request === generation) stats[path] = '读取失败'
    })
  }
}

watch([project, availableInventory], load, { immediate: true })
</script>

<template>
  <div class="overview">
    <section class="overview-hero dk-panel" aria-labelledby="realtime-title">
      <div>
        <h1 id="realtime-title">作业开发</h1>
        <p>在同一项目内开发实时和离线作业，用 OneSQL 核对产出。</p>
        <div class="hero-actions">
          <RouterLink v-if="hasCompute" class="action action-primary" :to="projectLocation('/jobs/realtime/sql', project)">实时作业 <ArrowRight aria-hidden="true" /></RouterLink>
          <RouterLink v-if="hasCompute || hasOffline" class="action" :to="projectLocation(hasCompute ? '/jobs/offline/sql' : '/jobs/offline', project)"><Share aria-hidden="true" /> 离线作业</RouterLink>
        </div>
      </div>
      <div v-if="hasCompute" class="engine-flow" aria-hidden="true">
        <div class="flow-source"><span>流式输入</span><span>批量输入</span></div>
        <ArrowRight />
        <div class="flow-engine">作业开发</div>
        <ArrowRight />
        <div class="flow-result">数仓产出</div>
      </div>
    </section>

    <section v-if="availableInventory.length" aria-labelledby="inventory-title">
      <div class="section-toolbar">
        <h2 id="inventory-title">项目资产</h2>
        <DkButton v-if="project" variant="quiet" @click="load"><template #icon><RefreshRight /></template>刷新</DkButton>
      </div>
      <p v-if="!project" class="inventory-note">项目资产数量在选定项目后显示。</p>
      <div v-for="group in inventoryGroups" :key="group.title" class="inventory-group">
        <h3>{{ group.title }}</h3>
        <div class="inventory">
          <RouterLink v-for="item in group.items" :key="item.path" :to="projectLocation(item.path, project)" class="asset-card dk-panel">
            <span class="asset-icon"><component :is="item.icon" aria-hidden="true" /></span>
            <span>{{ item.title }}</span>
            <span v-if="project" class="asset-stat" aria-live="polite">
              <strong v-if="typeof stats[item.path] === 'number'" class="dk-numeric">{{ stats[item.path] }}</strong>
              <template v-else>{{ stats[item.path] }}</template>
            </span>
            <ArrowRight class="card-arrow" aria-hidden="true" />
          </RouterLink>
        </div>
      </div>
    </section>

    <section v-if="hasCompute || hasOneSql" aria-labelledby="delivery-title">
      <div class="section-toolbar"><h2 id="delivery-title">数仓交付</h2></div>
      <div class="delivery">
        <RouterLink v-if="hasCompute" :to="projectLocation('/realtime/operations', project)" class="delivery-card dk-panel">
          <strong>实时运维</strong><span>查看运行记录、检查点和保存点。</span>
          <ArrowRight aria-hidden="true" />
        </RouterLink>
        <RouterLink v-if="hasOneSql" :to="projectLocation('/jobs/onesql', project)" class="delivery-card dk-panel">
          <strong>OneSQL 取数</strong><span>查询已登记的数仓结果，检查字段与数据。</span>
          <ArrowRight aria-hidden="true" />
        </RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.overview { display: grid; gap: 23px; }
.overview-hero { display: grid; gap: 32px; grid-template-columns: 1fr auto; align-items: center;
  padding: clamp(22px, 4vw, 40px); background: linear-gradient(115deg, var(--dk-surface) 15%, var(--dk-primary-soft)); }
h1 { margin: 0 0 10px; font-size: clamp(29px, 4vw, 43px); letter-spacing: -.03em; }
p { margin: 0; max-width: 52ch; color: var(--dk-muted); line-height: 1.75; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 22px; }
.action { display: inline-flex; align-items: center; gap: 8px; padding: 9px 13px; border: 1px solid var(--dk-border);
  border-radius: var(--dk-radius-control); background: var(--dk-surface); color: var(--dk-ink); font-weight: 600; text-decoration: none; }
.action-primary { color: white; border-color: var(--dk-primary); background: var(--dk-primary); }
.action-primary:hover { background: var(--dk-primary-hover); }
.action svg, .engine-flow svg, .delivery-card svg { width: 16px; height: 16px; flex: none; }
.engine-flow { display: flex; align-items: center; gap: 12px; color: var(--dk-primary); }
.flow-source { display: grid; gap: 7px; }
.flow-source span, .flow-result, .flow-engine { background: var(--dk-surface); border: 1px solid var(--dk-border);
  border-radius: var(--dk-radius-control); padding: 9px 11px; white-space: nowrap; text-align: center; }
.flow-engine { font-weight: 700; color: var(--dk-primary); }
.section-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; min-height: 36px; }
h2 { font-size: 18px; margin: 0; }
.inventory, .delivery { display: grid; grid-template-columns: repeat(auto-fit, minmax(155px, 1fr)); gap: 12px; }
.delivery { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.asset-card, .delivery-card { color: var(--dk-ink); text-decoration: none; align-items: center; padding: 14px; display: grid; gap: 7px; }
.asset-card { grid-template-columns: 32px 1fr auto; }
.inventory-note { margin-bottom: 10px; font-size: 13px; }
.inventory-group + .inventory-group { margin-top: 14px; }
.inventory-group h3 { margin: 0 0 8px; font-size: 14px; color: var(--dk-muted); }
.asset-stat { grid-column: 2; color: var(--dk-muted); font-size: 13px; }
.asset-stat strong { color: var(--dk-ink); font-size: 20px; }
.card-arrow { width: 15px; height: 15px; grid-column: 3; grid-row: 1 / 3; color: var(--dk-primary); }
.asset-icon { grid-row: 1 / 3; border-radius: 8px; display: grid; place-items: center; width: 32px; height: 32px;
  color: var(--dk-primary); background: var(--dk-primary-soft); }
.asset-icon svg { width: 17px; height: 17px; }
.delivery-card { grid-template-columns: 1fr auto; }
.delivery-card span { grid-column: 1; color: var(--dk-muted); font-size: 13px; line-height: 1.5; }
.delivery-card svg { grid-column: 2; grid-row: 1 / 3; color: var(--dk-primary); }
.asset-card:hover, .delivery-card:hover { border-color: var(--dk-primary); }
@media (max-width: 1040px) { .overview-hero { grid-template-columns: 1fr; } }
@media (max-width: 610px) {
  .inventory, .delivery { grid-template-columns: 1fr; }
  .engine-flow { flex-wrap: wrap; gap: 7px; }
}
</style>
