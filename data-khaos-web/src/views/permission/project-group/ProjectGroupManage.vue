<template>
  <el-card shadow="never">
    <!-- 顶部：项目组切换 / 选择 -->
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="当前项目组">
          <el-select v-model="currentGroupId" placeholder="请选择项目组" style="width: 260px" @change="onGroupChange">
            <el-option v-for="g in groupList" :key="g.id" :label="g.projectName" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Plus" @click="openCreateGroup">新增项目组</el-button>
          <el-button :icon="Refresh" @click="loadGroups">刷新</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-tabs v-model="activeTab" class="pg-tabs" @tab-change="loadTabData">
      <!-- ========== 成员管理 ========== -->
      <el-tab-pane label="成员管理" name="members">
        <el-table v-loading="membersLoading" :data="members" border stripe>
          <el-table-column prop="userId" label="用户ID" min-width="140" />
          <el-table-column prop="realName" label="姓名" min-width="120" />
          <el-table-column prop="roleName" label="组内角色" min-width="140" />
          <el-table-column label="能力位" min-width="280">
            <template #default="{ row }">
              <el-tag v-for="cap in (row.capabilityFlags || [])" :key="cap" size="small" class="cap-tag">{{ cap }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="joinTime" label="加入时间" width="170" />
        </el-table>
        <el-button class="mt-12" type="primary" :icon="Plus" :disabled="!currentGroupId" @click="openAddMember">添加成员</el-button>
      </el-tab-pane>

      <!-- ========== 角色 / 能力位 ========== -->
      <el-tab-pane label="角色与能力位" name="roles">
        <div class="sub-toolbar">
          <el-button type="primary" :icon="Plus" :disabled="!currentGroupId" @click="openCreateRole">新增角色</el-button>
        </div>
        <el-table v-loading="rolesLoading" :data="roles" border stripe>
          <el-table-column prop="roleName" label="角色名称" min-width="140" />
          <el-table-column prop="roleCode" label="角色编码" min-width="140" />
          <el-table-column label="能力位" min-width="320">
            <template #default="{ row }">
              <el-tag v-for="cap in parseCapabilities(row.capabilityFlags)" :key="cap" size="small" class="cap-tag">{{ cap }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditRole(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteRole(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="pager"
          v-model:current-page="roleQuery.current"
          v-model:page-size="roleQuery.size"
          :total="roleTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="loadRoles"
        />
      </el-tab-pane>

      <!-- ========== 资源绑定 ========== -->
      <el-tab-pane label="资源绑定" name="resources">
        <div class="sub-toolbar">
          <el-button type="primary" :icon="Plus" :disabled="!currentGroupId" @click="openBindResource">绑定资源</el-button>
        </div>
        <el-table v-loading="resourcesLoading" :data="resources" border stripe>
          <el-table-column prop="resourceType" label="资源类型" width="140">
            <template #default="{ row }">
              <el-tag :type="resourceTypeTag(row.resourceType)">{{ row.resourceType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resourceId" label="资源ID" min-width="200" />
          <el-table-column prop="createTime" label="绑定时间" width="170" />
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="handleDeleteResource(row)">解绑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ========== 权限总览 ========== -->
      <el-tab-pane label="权限总览" name="overview">
        <el-form inline class="overview-form">
          <el-form-item label="查询用户">
            <el-input v-model="overviewUserId" placeholder="输入用户ID" style="width: 220px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadOverview">查询</el-button>
          </el-form-item>
        </el-form>
        <div v-if="overviewData" class="overview-block">
          <h4>加入的项目组</h4>
          <el-table :data="overviewData.groups" border stripe>
            <el-table-column prop="projectName" label="项目组" min-width="160" />
            <el-table-column prop="projectCode" label="编码" min-width="140" />
            <el-table-column label="角色" min-width="200">
              <template #default="{ row }">
                <el-tag v-for="r in (row.roles || [])" :key="r" size="small" class="cap-tag">{{ r }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <h4 class="mt-16">能力位集合</h4>
          <div>
            <el-tag v-for="cap in (overviewData.capabilities || [])" :key="cap" class="cap-tag">{{ cap }}</el-tag>
            <span v-if="!overviewData.capabilities?.length" class="muted">无能力位</span>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ========== 项目组 新增/编辑 弹窗 ========== -->
    <el-dialog v-model="groupDialog.visible" :title="groupDialog.isEdit ? '编辑项目组' : '新增项目组'" width="520px" destroy-on-close>
      <el-form ref="groupFormRef" :model="groupDialog.form" :rules="groupRules" label-width="100px">
        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="groupDialog.form.projectName" placeholder="请输入项目组名称" />
        </el-form-item>
        <el-form-item label="项目编码" prop="projectCode">
          <el-input v-model="groupDialog.form.projectCode" placeholder="唯一编码" />
        </el-form-item>
        <el-form-item label="组长">
          <el-input v-model="groupDialog.form.leaderId" placeholder="用户ID" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="groupDialog.form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveGroup">保存</el-button>
      </template>
    </el-dialog>

    <!-- ========== 角色 新增/编辑 弹窗 ========== -->
    <el-dialog v-model="roleDialog.visible" :title="roleDialog.isEdit ? '编辑角色' : '新增角色'" width="520px" destroy-on-close>
      <el-form ref="roleFormRef" :model="roleDialog.form" :rules="roleRules" label-width="100px">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="roleDialog.form.roleName" placeholder="如 数据分析师" />
        </el-form-item>
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="roleDialog.form.roleCode" placeholder="如 data_analyst" />
        </el-form-item>
        <el-form-item label="能力位">
          <el-select v-model="roleDialog.capabilityList" multiple placeholder="选择能力位" style="width: 100%">
            <el-option v-for="c in ALL_CAPABILITIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleDialog.form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- ========== 绑定资源弹窗 ========== -->
    <el-dialog v-model="resourceDialog.visible" title="绑定资源" width="520px" destroy-on-close>
      <el-form :model="resourceDialog.form" label-width="100px">
        <el-form-item label="资源类型" required>
          <el-select v-model="resourceDialog.form.resourceType" style="width: 100%">
            <el-option label="开发任务 (TASK)" value="TASK" />
            <el-option label="报表 (REPORT)" value="REPORT" />
            <el-option label="数据表 (TABLE)" value="TABLE" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源ID" required>
          <el-input v-model="resourceDialog.form.resourceId" placeholder="资源唯一标识" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resourceDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="handleBindResource">绑定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import type { SgProjectGroup } from '@/types'
import {
  pageProjectGroups,
  createProjectGroup,
  updateProjectGroup,
  deleteProjectGroup,
  listMembers,
  pageRoles,
  createRole,
  updateRole,
  deleteRole,
  listResources,
  bindResources,
  deleteResource,
  getUserProjectGroups,
  getUserCapabilities,
} from '@/api/project-group'
import type { SgProjectRole, SgProjectGroupResource } from '@/api/project-group'

const ALL_CAPABILITIES = [
  'module:develop', 'module:config',
  'report:develop', 'report:config',
  'quality:manage', 'quality:config',
  'datasource:manage', 'permission:manage',
  'dataset:manage', 'pipeline:config',
]

const activeTab = ref('members')
const currentGroupId = ref('')
const groupList = ref<SgProjectGroup[]>([])

// === 成员 ===
const membersLoading = ref(false)
const members = ref<Array<Record<string, any>>>([])

// === 角色 ===
const rolesLoading = ref(false)
const roles = ref<SgProjectRole[]>([])
const roleTotal = ref(0)
const roleQuery = reactive({ current: 1, size: 10 })

// === 资源 ===
const resourcesLoading = ref(false)
const resources = ref<SgProjectGroupResource[]>([])

// === 总览 ===
const overviewUserId = ref('')
const overviewData = ref<{ groups: any[]; capabilities: string[] } | null>(null)

// === 项目组 弹窗 ===
const groupFormRef = ref<FormInstance>()
const groupDialog = reactive<{
  visible: boolean
  isEdit: boolean
  form: Partial<SgProjectGroup> & { projectName: string; projectCode: string; status: number }
}>({
  visible: false,
  isEdit: false,
  form: { projectName: '', projectCode: '', status: 1 },
})
const groupRules: FormRules = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  projectCode: [{ required: true, message: '请输入项目编码', trigger: 'blur' }],
}

// === 角色 弹窗 ===
const roleFormRef = ref<FormInstance>()
const roleDialog = reactive<{
  visible: boolean
  isEdit: boolean
  capabilityList: string[]
  form: Partial<SgProjectRole> & { roleName: string; roleCode: string; status: number }
}>({
  visible: false,
  isEdit: false,
  capabilityList: [],
  form: { roleName: '', roleCode: '', status: 1 },
})
const roleRules: FormRules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  roleCode: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
}

// === 资源 弹窗 ===
const resourceDialog = reactive<{
  visible: boolean
  form: { resourceType: string; resourceId: string }
}>({
  visible: false,
  form: { resourceType: 'TABLE', resourceId: '' },
})

onMounted(async () => {
  await loadGroups()
})

async function loadGroups() {
  const res = await pageProjectGroups({ current: 1, size: 100 })
  groupList.value = res?.records || []
  if (groupList.value.length && !currentGroupId.value) {
    currentGroupId.value = groupList.value[0].id || ''
    await loadTabData()
  }
}

async function onGroupChange() {
  await loadTabData()
}

async function loadTabData() {
  if (!currentGroupId.value) return
  if (activeTab.value === 'members') await loadMembers()
  else if (activeTab.value === 'roles') await loadRoles()
  else if (activeTab.value === 'resources') await loadResources()
}

async function loadMembers() {
  if (!currentGroupId.value) return
  membersLoading.value = true
  try {
    const res = await listMembers(currentGroupId.value)
    members.value = res || []
  } finally {
    membersLoading.value = false
  }
}

async function loadRoles() {
  if (!currentGroupId.value) return
  rolesLoading.value = true
  try {
    const res = await pageRoles({ id: currentGroupId.value, current: roleQuery.current, size: roleQuery.size })
    roles.value = res?.records || []
    roleTotal.value = res?.total || 0
  } finally {
    rolesLoading.value = false
  }
}

async function loadResources() {
  if (!currentGroupId.value) return
  resourcesLoading.value = true
  try {
    const res = await listResources(currentGroupId.value)
    resources.value = res || []
  } finally {
    resourcesLoading.value = false
  }
}

function openCreateGroup() {
  groupDialog.isEdit = false
  groupDialog.form = { projectName: '', projectCode: '', status: 1 }
  groupDialog.visible = true
}

function openEditGroup(row: SgProjectGroup) {
  groupDialog.isEdit = true
  groupDialog.form = {
    id: row.id,
    projectName: row.projectName || '',
    projectCode: row.projectCode || '',
    leaderId: row.leaderId,
    status: row.status ?? 1,
  }
  groupDialog.visible = true
}

async function handleSaveGroup() {
  if (!groupFormRef.value) return
  await groupFormRef.value.validate()
  if (groupDialog.isEdit && groupDialog.form.id) {
    await updateProjectGroup(groupDialog.form.id, groupDialog.form)
    ElMessage.success('更新成功')
  } else {
    await createProjectGroup(groupDialog.form)
    ElMessage.success('创建成功')
  }
  groupDialog.visible = false
  await loadGroups()
}

async function handleDeleteGroup(row: SgProjectGroup) {
  await ElMessageBox.confirm(`确定删除项目组「${row.projectName}」？`, '提示', { type: 'warning' })
  await deleteProjectGroup(row.id!)
  ElMessage.success('删除成功')
  await loadGroups()
}

function openAddMember() {
  ElMessage.info('请通过用户管理模块设置项目组成员')
}

function openCreateRole() {
  roleDialog.isEdit = false
  roleDialog.capabilityList = []
  roleDialog.form = { roleName: '', roleCode: '', status: 1, projectGroupId: currentGroupId.value }
  roleDialog.visible = true
}

function openEditRole(row: SgProjectRole) {
  roleDialog.isEdit = true
  roleDialog.capabilityList = parseCapabilities(row.capabilityFlags)
  roleDialog.form = {
    id: row.id,
    roleName: row.roleName || '',
    roleCode: row.roleCode || '',
    status: row.status ?? 1,
    projectGroupId: row.projectGroupId,
  }
  roleDialog.visible = true
}

async function handleSaveRole() {
  if (!roleFormRef.value) return
  await roleFormRef.value.validate()
  const data = { ...roleDialog.form, capabilityFlags: JSON.stringify(roleDialog.capabilityList) }
  if (roleDialog.isEdit && roleDialog.form.id) {
    await updateRole(roleDialog.form.id, data)
    ElMessage.success('更新成功')
  } else {
    await createRole(data)
    ElMessage.success('创建成功')
  }
  roleDialog.visible = false
  await loadRoles()
}

async function handleDeleteRole(row: SgProjectRole) {
  await ElMessageBox.confirm(`确定删除角色「${row.roleName}」？`, '提示', { type: 'warning' })
  await deleteRole(row.id!)
  ElMessage.success('删除成功')
  await loadRoles()
}

function openBindResource() {
  resourceDialog.form = { resourceType: 'TABLE', resourceId: '' }
  resourceDialog.visible = true
}

async function handleBindResource() {
  if (!resourceDialog.form.resourceId) {
    ElMessage.warning('请输入资源ID')
    return
  }
  await bindResources(currentGroupId.value, [{ ...resourceDialog.form, projectGroupId: currentGroupId.value }] as any)
  ElMessage.success('绑定成功')
  resourceDialog.visible = false
  await loadResources()
}

async function handleDeleteResource(row: SgProjectGroupResource) {
  await ElMessageBox.confirm('确定解绑该资源？', '提示', { type: 'warning' })
  await deleteResource(row.id!)
  ElMessage.success('已解绑')
  await loadResources()
}

async function loadOverview() {
  if (!overviewUserId.value) {
    ElMessage.warning('请输入用户ID')
    return
  }
  const [groupsRes, capsRes] = await Promise.all([
    getUserProjectGroups(overviewUserId.value),
    getUserCapabilities(overviewUserId.value, currentGroupId.value || undefined),
  ])
  overviewData.value = { groups: groupsRes || [], capabilities: capsRes || [] }
}

function parseCapabilities(json?: string): string[] {
  if (!json) return []
  try { return JSON.parse(json) } catch { return [] }
}

function resourceTypeTag(t?: string) {
  return ({ TASK: 'primary', REPORT: 'success', TABLE: 'warning' } as any)[t || ''] || 'info'
}
</script>

<style scoped>
.toolbar { margin-bottom: 16px; }
.sub-toolbar { margin-bottom: 12px; }
.pg-tabs { margin-top: 8px; }
.cap-tag { margin-right: 4px; margin-bottom: 2px; }
.mt-12 { margin-top: 12px; }
.mt-16 { margin-top: 16px; }
.pager { margin-top: 16px; justify-content: flex-end; }
.overview-form { margin-bottom: 16px; }
.overview-block h4 { margin: 12px 0 8px; font-size: 15px; color: #303133; }
.muted { color: #909399; font-size: 13px; }
</style>
