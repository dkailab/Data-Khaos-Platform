<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 140px" @change="handleSearch">
            <el-option label="待审批" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="已撤销" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openApply">发起申请</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="applyType" label="申请类型" width="110">
        <template #default="{ row }">
          <el-tag type="primary">{{ applyTypeText(row.applyType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetName" label="申请目标" min-width="180" />
      <el-table-column prop="reason" label="申请理由" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="currentApprover" label="当前审批人ID" min-width="130" />
      <el-table-column prop="createTime" label="申请时间" width="170" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          <el-button v-if="row.status === 0" link type="danger" @click="handleCancel(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      v-model:current-page="query.current"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      @change="load"
    />

    <!-- 发起申请对话框 -->
    <el-dialog v-model="applyVisible" title="发起权限申请" width="560px" destroy-on-close>
      <el-form ref="applyFormRef" :model="applyForm" :rules="applyRules" label-width="100px">
        <el-form-item label="申请类型" prop="applyType">
          <el-select v-model="applyForm.applyType" style="width: 100%">
            <el-option label="表权限" value="TABLE" />
            <el-option label="报表" value="REPORT" />
            <el-option label="数据源" value="DATASOURCE" />
            <el-option label="菜单" value="MENU" />
            <el-option label="模型订阅" value="MART" />
            <el-option label="数仓 API 版本" value="WAREHOUSE_API" />
            <el-option label="实时作业版本" value="FLINK_JOB" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标ID" prop="targetId">
          <el-input v-model="applyForm.targetId" placeholder="目标ID（TABLE 时为数据源ID）" />
        </el-form-item>
        <el-form-item v-if="needsRevision" label="目标版本">
          <el-input-number v-model="applyForm.targetRevision" :min="1" />
          <span class="hint">审批只对这一版有效，改稿后需重新申请</span>
        </el-form-item>
        <el-form-item label="目标名称" prop="targetName">
          <el-input v-model="applyForm.targetName" placeholder="目标名称（TABLE 时为 database.table）" />
        </el-form-item>
        <el-form-item label="申请理由" prop="reason">
          <el-input v-model="applyForm.reason" type="textarea" :rows="3" placeholder="请输入申请理由" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitApply">提交</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" title="申请详情" width="680px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="申请类型">{{ applyTypeText(detail.apply?.applyType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.apply?.status)">{{ statusText(detail.apply?.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="目标名称">{{ detail.apply?.targetName }}</el-descriptions-item>
        <el-descriptions-item label="目标ID">{{ detail.apply?.targetId }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.apply?.targetRevision" label="目标版本" :span="2">
          v{{ detail.apply?.targetRevision }}
        </el-descriptions-item>
        <el-descriptions-item label="申请理由" :span="2">{{ detail.apply?.reason }}</el-descriptions-item>
        <el-descriptions-item label="申请时间" :span="2">{{ detail.apply?.createTime }}</el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">审批记录</el-divider>
      <el-table :data="detail.records || []" size="small" border>
        <el-table-column prop="approverId" label="审批人ID" min-width="120" />
        <el-table-column label="动作" width="90">
          <template #default="{ row }">
            <el-tag :type="recordActionTagType(row.action)">{{ recordActionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="comment" label="意见" min-width="180" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { applyDetail, cancelApply, pageMyApplies, submitApply as apiSubmitApply } from '@/api/approval'
import type { AppApply, AppApprovalRecord, ApplyRequest } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const list = ref<AppApply[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10, status: undefined })

const applyVisible = ref(false)
const applyFormRef = ref<FormInstance>()
const applyForm = reactive<ApplyRequest>({ applyType: 'TABLE' })
const needsRevision = computed(() => ['WAREHOUSE_API', 'FLINK_JOB'].includes(applyForm.applyType))
const applyRules: FormRules = {
  applyType: [{ required: true, message: '请选择申请类型', trigger: 'change' }],
  targetName: [{ required: true, message: '请输入目标名称', trigger: 'blur' }],
}

const detailVisible = ref(false)
const detail = ref<{ apply: AppApply; records: AppApprovalRecord[] }>({ apply: {}, records: [] })

function applyTypeText(t?: string) {
  return { TABLE: '表权限', REPORT: '报表', DATASOURCE: '数据源', MENU: '菜单', MART: '模型订阅',
    WAREHOUSE_API: '数仓 API 版本', FLINK_JOB: '实时作业版本' }[t ?? ''] ?? t ?? '-'
}

function statusText(s?: number) {
  return { 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已撤销' }[s ?? -1] ?? '-'
}

function statusTagType(s?: number): 'warning' | 'success' | 'danger' | 'info' {
  if (s === 0) return 'warning'
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  return 'info'
}

function recordActionText(a?: number) {
  return { 1: '通过', 2: '驳回', 3: '转交' }[a ?? -1] ?? '-'
}

function recordActionTagType(a?: number): 'success' | 'danger' | 'warning' {
  if (a === 1) return 'success'
  if (a === 2) return 'danger'
  return 'warning'
}

async function load() {
  loading.value = true
  try {
    const data = await pageMyApplies({ ...query })
    list.value = data.records
    total.value = Number(data.total)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.current = 1
  load()
}

function openApply() {
  Object.assign(applyForm, { applyType: 'TABLE', targetId: '', targetRevision: undefined, targetName: '', reason: '' })
  applyVisible.value = true
}

async function handleSubmitApply() {
  await applyFormRef.value?.validate()
  submitting.value = true
  try {
    await apiSubmitApply({ ...applyForm })
    ElMessage.success('申请提交成功')
    applyVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleCancel(row: AppApply) {
  await ElMessageBox.confirm('确认撤销该申请吗？', '提示', { type: 'warning' })
  await cancelApply(row.id!)
  ElMessage.success('已撤销')
  load()
}

async function showDetail(row: AppApply) {
  detail.value = await applyDetail(row.id!)
  detailVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.hint { margin-left: 8px; color: var(--dk-muted); font-size: 12px; }
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
