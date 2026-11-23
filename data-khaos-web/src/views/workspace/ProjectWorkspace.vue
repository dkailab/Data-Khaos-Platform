<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import WorkspaceActivity from './WorkspaceActivity.vue'
import { useUserStore } from '@/stores/user'
import {
  getProjectWorkspace, listAssignableRoles, listMyProjectWorkspaces, listWorkspaceResources,
  listWorkspaceMembers, removeWorkspaceMember, setWorkspaceMember,
} from '@/api/project-group'
import type { ProjectMember, ProjectWorkspace, ProjectWorkspaceResource, SgProjectRole } from '@/api/project-group'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const projects = ref<ProjectWorkspace[]>([])
const current = ref<ProjectWorkspace>()
const resources = ref<ProjectWorkspaceResource[]>([])
const members = ref<ProjectMember[]>([])
const roles = ref<SgProjectRole[]>([])
const loading = ref(false)
const failure = ref('')
const memberDialog = ref(false)
const mutation = ref(false)
const editing = ref(false)
const form = reactive({ userId: '', roleId: '' })
let requestVersion = 0

const projectId = computed(() => typeof route.query.project === 'string' ? route.query.project : '')
const canManage = computed(() => current.value?.capabilities.includes('pg:manage') ||
  (current.value && (String(user.userInfo?.id) === '1' || user.roles.includes('SUPER_ADMIN'))))
const roleNames: Record<string, string> = { PG_LEADER: '组长', PG_DEV: '开发者', PG_USER: '使用者' }
const resourceNames: Record<string, string> = { DATASOURCE: '数据源', TASK: '任务', TABLE: '数据表', REPORT: '报表' }

function selectProject(id: string) {
  if (id && id !== projectId.value) void router.push({ query: { ...route.query, project: id } })
}

async function load() {
  const version = ++requestVersion
  const wanted = projectId.value
  projects.value = []
  current.value = undefined
  resources.value = []
  members.value = []
  roles.value = []
  failure.value = ''
  memberDialog.value = false
  loading.value = true

  try {
    const list = await listMyProjectWorkspaces()
    if (version !== requestVersion) return
    projects.value = list
    if (!wanted) {
      const first = list.find(project => project.primary) ?? list[0]
      if (first) await router.replace({ query: { ...route.query, project: first.projectGroupId } })
      return
    }
    if (!list.some(project => project.projectGroupId === wanted)) {
      failure.value = '当前项目已停用或访问权限已收回，请重新选择项目。'
      return
    }

    const [workspace, bindings] = await Promise.all([getProjectWorkspace(wanted), listWorkspaceResources(wanted)])
    if (version !== requestVersion) return
    current.value = workspace
    resources.value = bindings
    if (canManage.value) {
      const [rows, choices] = await Promise.all([listWorkspaceMembers(wanted), listAssignableRoles(wanted)])
      if (version !== requestVersion) return
      members.value = rows
      roles.value = choices
    }
  } catch {
    if (version === requestVersion) {
      current.value = undefined
      resources.value = []
      members.value = []
      roles.value = []
      failure.value = '无法加载项目或项目权限已变更，请刷新重试。'
    }
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

function editMember(row?: ProjectMember) {
  editing.value = !!row
  Object.assign(form, { userId: row?.userId ?? '', roleId: row?.projectRoleId ?? '' })
  memberDialog.value = true
}

async function saveMember() {
  const id = current.value?.projectGroupId
  if (!id || mutation.value || !form.userId.trim() || !form.roleId) return
  mutation.value = true
  try {
    await setWorkspaceMember(id, form.userId.trim(), form.roleId)
    if (projectId.value === id) await load()
    ElMessage.success('成员已保存')
  } catch {
    // 共用请求层显示服务端错误，保留表单。
  } finally {
    mutation.value = false
  }
}

async function removeMember(row: ProjectMember) {
  const id = current.value?.projectGroupId
  if (!id || mutation.value) return
  try {
    await ElMessageBox.confirm(`移除用户「${row.userId}」的当前项目访问权限？`, '移除成员', { type: 'warning' })
    if (projectId.value !== id || mutation.value) return
    mutation.value = true
    try {
      await removeWorkspaceMember(id, row.userId)
      if (projectId.value === id) await load()
    } finally {
      mutation.value = false
    }
  } catch {
    // 用户取消或请求层已展示错误。
  }
}

watch([projectId, () => user.userInfo?.id], () => { void load() }, { immediate: true })
onBeforeUnmount(() => { requestVersion++ })
</script>

<template>
  <div class="project-space">
    <DkPageHeader title="项目空间" description="切换项目，查看任务、运行记录与成员权限。">
      <template #actions>
        <el-select :model-value="projectId || undefined" aria-label="选择项目" placeholder="选择项目"
          class="project-select" filterable :disabled="mutation" @change="selectProject">
          <el-option v-for="project in projects" :key="project.projectGroupId"
            :label="project.primary ? project.name + ' · 主项目' : project.name" :value="project.projectGroupId" />
        </el-select>
        <DkButton :loading="loading" :disabled="mutation" @click="load">刷新</DkButton>
      </template>
    </DkPageHeader>

    <el-alert v-if="failure" :title="failure" type="warning" show-icon :closable="false" />
    <div v-else-if="loading" class="dk-panel state-panel" role="status">正在加载项目空间…</div>
    <div v-else-if="!current" class="dk-panel"><el-empty description="没有可访问的项目，请联系项目管理员" /></div>
    <template v-else>
      <section class="dk-panel project-card" aria-label="当前项目">
        <div><h2>{{ current.name }}</h2><span class="dk-mono muted">{{ current.projectGroupId }}</span></div>
        <el-tag v-if="current.primary" type="info">主项目</el-tag>
        <el-tag>{{ roleNames[current.roleCode || ''] || current.roleCode || '尚未分配角色' }}</el-tag>
      </section>

      <WorkspaceActivity :key="current.projectGroupId" :project-id="current.projectGroupId"
        :can-schedule="current.capabilities.includes('task:schedule')"
        :can-run-pipeline="current.capabilities.includes('task:develop') || current.capabilities.includes('pipeline:run')" />

      <section class="dk-panel">
        <header class="section-head"><h2>已绑定资源</h2></header>
        <el-table :data="resources" row-key="id" empty-text="当前项目暂无资源绑定">
          <el-table-column label="资源类型" min-width="120">
            <template #default="{ row }">{{ resourceNames[row.resourceType] || row.resourceType }}</template>
          </el-table-column>
          <el-table-column label="资源" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ row.name || row.resourceId }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="105">
            <template #default="{ row }">
              <el-tag v-if="row.available === true" type="info">可用</el-tag>
              <el-tag v-else-if="row.available === false" type="warning">不可用</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="canManage" class="dk-panel">
        <header class="section-head"><h2>项目成员</h2>
          <DkButton variant="primary" :disabled="!roles.length || mutation" @click="editMember()">添加成员</DkButton>
        </header>
        <el-table :data="members" row-key="memberId" empty-text="暂无成员">
          <el-table-column prop="userId" label="用户 ID" min-width="125" show-overflow-tooltip />
          <el-table-column prop="roleName" label="项目角色" min-width="120" />
          <el-table-column label="主项目" min-width="90">
            <template #default="{ row }">{{ row.primary ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="140">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="mutation" @click="editMember(row)">修改角色</el-button>
              <el-button link type="danger" :disabled="mutation || row.userId === String(user.userInfo?.id)"
                @click="removeMember(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>

    <el-dialog v-model="memberDialog" :title="editing ? '修改项目成员' : '添加项目成员'" width="min(440px, 92vw)"
      :close-on-click-modal="!mutation" :show-close="!mutation">
      <el-form label-position="top" @submit.prevent="saveMember">
        <el-form-item label="用户 ID"><el-input v-model="form.userId" maxlength="32" :disabled="editing || mutation" /></el-form-item>
        <el-form-item label="项目角色">
          <el-select v-model="form.roleId" aria-label="项目角色" :disabled="mutation" style="width:100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.roleName" :value="role.id!" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <DkButton :disabled="mutation" @click="memberDialog = false">取消</DkButton>
        <DkButton variant="primary" :loading="mutation" :disabled="!form.userId.trim() || !form.roleId" @click="saveMember">保存</DkButton>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.project-space { display:grid; min-width:0; gap:var(--dk-space-4); }
.project-space > * { min-width:0; }
.project-select { width:min(225px, 55vw); }
.project-card, .section-head { display:flex; align-items:center; flex-wrap:wrap; gap:var(--dk-space-3); padding:var(--dk-space-4); }
.section-head { justify-content:space-between; }
.project-card > div { margin-right:auto; }
h2 { margin:0; font-size:17px; }
.muted { color:var(--dk-muted); }
.state-panel { padding:var(--dk-space-4); }
</style>
