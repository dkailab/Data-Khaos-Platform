<script setup lang="ts">
import { ref, watch } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { RouterLink, useRouter } from 'vue-router'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import WorkflowRevisionDialog from './list/WorkflowRevisionDialog.vue'
import type { ProjectWorkspaceTask } from '@/api/project-group'
import WorkspaceActivity from '@/views/workspace/WorkspaceActivity.vue'
import { projectLocation, useRealtimeProject } from '@/views/realtime/projectContext'

const { project, canRun } = useRealtimeProject()
const router = useRouter()
const versionTask = ref<ProjectWorkspaceTask>()
watch(project, () => { versionTask.value = undefined })

function editLocation(id: string) {
  return projectLocation('/jobs/offline/edit/' + encodeURIComponent(id), project.value)
}
</script>

<template>
  <section class="offline-development">
    <DkPageHeader title="离线工作流" description="编排批次任务，查看运行实例，并按业务日期补数。">
      <template #actions>
        <RouterLink v-if="project" class="create-link" :to="editLocation('create')"><Plus aria-hidden="true" /> 新建工作流</RouterLink>
      </template>
    </DkPageHeader>
    <WorkspaceActivity v-if="project" :key="project" :project-id="project" fixed-kind="WORKFLOW"
      :can-run-pipeline="false" :can-schedule="canRun">
      <template #task-action="{ task }">
        <RouterLink class="edit-link" :to="editLocation(task.id)">编排</RouterLink>
        <el-button link type="primary" class="versions" @click="versionTask = task">版本</el-button>
      </template>
    </WorkspaceActivity>
    <WorkflowRevisionDialog v-if="versionTask" :workflow="{ id: versionTask.id, name: versionTask.name }"
      @close="versionTask = undefined" @open-draft="id => router.push(editLocation(id))" />
  </section>
</template>

<style scoped>
.offline-development { display: grid; gap: 14px; min-width: 0; }
.create-link { display: inline-flex; align-items: center; gap: 6px; padding: 8px 11px; border-radius: var(--dk-radius-control);
  background: var(--dk-primary); color: white; font-weight: 600; text-decoration: none; }
.create-link:hover { background: var(--dk-primary-hover); }
.create-link svg { width: 16px; height: 16px; }
.versions { margin-inline-start: 8px; }
.edit-link { margin-inline-start: 8px; color: var(--dk-primary); text-decoration: none; font-size: 14px; }
.edit-link:hover { text-decoration: underline; }
</style>
