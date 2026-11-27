<script setup lang="ts">
import { computed, onMounted, provide, ref, watch } from 'vue'
import { FolderOpened } from '@element-plus/icons-vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { listMyProjectWorkspaces, type ProjectWorkspace } from '@/api/project-group'
import DkButton from '@/paas-ui/DkButton.vue'
import { realtimeProjectKey } from './projectContext'

const route = useRoute()
const router = useRouter()
const projects = ref<ProjectWorkspace[]>([])
const requested = computed(() => typeof route.query.project === 'string' ? route.query.project : '')
const project = computed(() => {
  if (requested.value) return projects.value.find(row => row.projectGroupId === requested.value)?.projectGroupId ?? ''
  return (projects.value.find(row => row.primary) ?? projects.value[0])?.projectGroupId ?? ''
})
const invalidProject = computed(() => !!requested.value && !project.value && !loading.value && !failed.value)
const loading = ref(true)
const failed = ref(false)
const canRun = computed(() => projects.value.find(row => row.projectGroupId === project.value)
  ?.capabilities.includes('task:schedule') ?? false)
const needsProject = computed(() => route.name !== 'JobOneSql')
const projectLocked = computed(() => route.name === 'JobOfflineEdit')
provide(realtimeProjectKey, { project, canRun })

async function loadProjects() {
  loading.value = true
  failed.value = false
  try {
    projects.value = (await listMyProjectWorkspaces()).filter(row => row.capabilities.includes('task:develop'))
  } catch {
    failed.value = true
    projects.value = []
  } finally {
    loading.value = false
  }
}

function selectProject(id: string) {
  void router.push({ query: { ...route.query, project: id } })
}

watch([project, requested], ([id, explicit]) => {
  if (id && !explicit && (route.path.startsWith('/jobs') || route.path.startsWith('/realtime/'))) void router.replace({ query: { ...route.query, project: id } })
}, { flush: 'post' })

onMounted(loadProjects)
</script>

<template>
  <div class="realtime-workspace">
    <div v-if="needsProject" class="project-bar">
      <div class="project-label"><FolderOpened aria-hidden="true" /><span>项目空间</span></div>
      <el-select :model-value="project" aria-label="开发项目" placeholder="选择项目" :loading="loading"
        class="project-select" :disabled="loading || !projects.length || (projectLocked && !!project)" @change="selectProject">
        <el-option v-for="row in projects" :key="row.projectGroupId" :label="row.name" :value="row.projectGroupId" />
      </el-select>
    </div>
    <el-alert v-if="needsProject && failed" title="无法加载开发项目" type="error" :closable="false" show-icon>
      <DkButton variant="quiet" @click="loadProjects">重试</DkButton>
    </el-alert>
    <el-alert v-else-if="needsProject && !loading && !project" :title="invalidProject ? '当前项目不可访问，请选择有权限的项目' : '暂无具备数据开发权限的项目'"
      type="info" :closable="false" show-icon />
    <RouterView v-slot="{ Component }">
      <component v-if="route.name !== 'JobOfflineEdit' || project" :is="Component"
        :key="route.name === 'JobOfflineEdit' ? route.path + ':' + project : route.name" />
    </RouterView>
  </div>
</template>

<style scoped>
.realtime-workspace { display: grid; gap: 16px; min-width: 0; }
.project-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.project-label { display: inline-flex; align-items: center; gap: 7px; color: var(--dk-muted); font-weight: 600; }
.project-label svg { width: 17px; height: 17px; }
.project-select { width: min(250px, 100%); }
</style>
