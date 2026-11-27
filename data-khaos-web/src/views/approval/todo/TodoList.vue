<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-button type="primary" :icon="Refresh" @click="load">刷新</el-button>
      <span class="tip">当前待我审批的申请</span>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="applyType" label="申请类型" width="110">
        <template #default="{ row }">
          <el-tag type="primary">{{ applyTypeText(row.applyType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetName" label="申请目标" min-width="180" />
      <el-table-column prop="reason" label="申请理由" min-width="220" show-overflow-tooltip />
      <el-table-column prop="applicantId" label="申请人ID" min-width="130" />
      <el-table-column prop="createTime" label="申请时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="success" @click="openApprove(row)">通过</el-button>
          <el-button link type="danger" @click="openReject(row)">驳回</el-button>
          <el-button link type="warning" @click="openTransfer(row)">转交</el-button>
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
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

    <!-- 通过 / 驳回 共用对话框 -->
    <el-dialog v-model="actionVisible" :title="actionMode === 'approve' ? '审批通过' : '审批驳回'" width="480px" destroy-on-close>
      <el-form ref="actionFormRef" :model="actionForm" label-width="90px">
        <el-form-item label="审批意见">
          <el-input v-model="actionForm.comment" type="textarea" :rows="3" placeholder="请输入审批意见（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button :type="actionMode === 'approve' ? 'success' : 'danger'" :loading="submitting" @click="submitAction">
          {{ actionMode === 'approve' ? '确认通过' : '确认驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 转交对话框 -->
    <el-dialog v-model="transferVisible" title="转交审批" width="480px" destroy-on-close>
      <el-form ref="transferFormRef" :model="transferForm" :rules="transferRules" label-width="90px">
        <el-form-item label="转交给" prop="toApproverId">
          <el-input v-model="transferForm.toApproverId" placeholder="请输入审批人ID" />
        </el-form-item>
        <el-form-item label="转交意见">
          <el-input v-model="transferForm.comment" type="textarea" :rows="2" placeholder="请输入转交意见（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitTransfer">确认转交</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" title="申请详情" width="680px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="申请类型">{{ applyTypeText(detail.apply?.applyType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag>{{ statusText(detail.apply?.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="目标名称">{{ detail.apply?.targetName }}</el-descriptions-item>
        <el-descriptions-item label="目标ID">{{ detail.apply?.targetId }}</el-descriptions-item>
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { applyDetail, approveApply, pagePendingApplies, rejectApply, transferApply } from '@/api/approval'
import type { AppApply, AppApprovalRecord, ApprovalActionRequest, TransferRequest } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const list = ref<AppApply[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 10 })

const actionVisible = ref(false)
const actionFormRef = ref<FormInstance>()
const actionMode = ref<'approve' | 'reject'>('approve')
const actionTarget = ref<AppApply | null>(null)
const actionForm = reactive<ApprovalActionRequest>({ comment: '' })

const transferVisible = ref(false)
const transferFormRef = ref<FormInstance>()
const transferTarget = ref<AppApply | null>(null)
const transferForm = reactive<TransferRequest>({ toApproverId: '', comment: '' })
const transferRules: FormRules = {
  toApproverId: [{ required: true, message: '请输入审批人ID', trigger: 'blur' }],
}

const detailVisible = ref(false)
const detail = ref<{ apply: AppApply; records: AppApprovalRecord[] }>({ apply: {}, records: [] })

function applyTypeText(t?: string) {
  return { TABLE: '表权限', REPORT: '报表', DATASOURCE: '数据源', MENU: '菜单',
    WAREHOUSE_API: '数仓 API 版本', FLINK_JOB: '实时作业版本' }[t ?? ''] ?? t ?? '-'
}

function statusText(s?: number) {
  return { 0: '待审批', 1: '已通过', 2: '已驳回', 3: '已撤销' }[s ?? -1] ?? '-'
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
    const data = await pagePendingApplies({ ...query })
    list.value = data.records
    total.value = Number(data.total)
  } finally {
    loading.value = false
  }
}

function openApprove(row: AppApply) {
  actionMode.value = 'approve'
  actionTarget.value = row
  actionForm.comment = ''
  actionVisible.value = true
}

function openReject(row: AppApply) {
  actionMode.value = 'reject'
  actionTarget.value = row
  actionForm.comment = ''
  actionVisible.value = true
}

async function submitAction() {
  submitting.value = true
  try {
    const payload: ApprovalActionRequest = { comment: actionForm.comment || undefined }
    if (actionMode.value === 'approve') {
      await approveApply(actionTarget.value!.id!, payload)
      ElMessage.success('已通过')
    } else {
      await rejectApply(actionTarget.value!.id!, payload)
      ElMessage.success('已驳回')
    }
    actionVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function openTransfer(row: AppApply) {
  transferTarget.value = row
  Object.assign(transferForm, { toApproverId: '', comment: '' })
  transferVisible.value = true
}

async function submitTransfer() {
  await transferFormRef.value?.validate()
  submitting.value = true
  try {
    await transferApply(transferTarget.value!.id!, { ...transferForm })
    ElMessage.success('转交成功')
    transferVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function showDetail(row: AppApply) {
  detail.value = await applyDetail(row.id!)
  detailVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.tip {
  color: #909399;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
