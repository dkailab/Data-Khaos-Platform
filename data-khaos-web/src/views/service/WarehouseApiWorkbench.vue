<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import { listMyProjectWorkspaces } from '@/api/project-group'
import type { ProjectWorkspace } from '@/api/project-group'
import {
  createWarehouseApiDraft, editWarehouseApiDraft, invokeWarehouseApi, issueWarehouseApiCredential,
  listWarehouseApiCredentials, listWarehouseApis, offlineWarehouseApi, publishWarehouseApi,
  revokeWarehouseApiCredential, warehouseApiDataUrl,
} from '@/api/warehouseApi'
import type {
  IssuedWarehouseCredential, OrderRegionGmv, WarehouseApiCredential, WarehouseApiVersion,
} from '@/api/warehouseApi'

const route = useRoute()
const router = useRouter()

const projects = ref<ProjectWorkspace[]>([])
const rows = ref<WarehouseApiVersion[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const failure = ref('')
let requestVersion = 0

const projectId = computed(() => (typeof route.query.project === 'string' ? route.query.project : ''))
const currentProject = computed(() => projects.value.find(item => item.projectGroupId === projectId.value))
const canDevelop = computed(() => currentProject.value?.capabilities.includes('task:develop') ?? false)

const statusMeta: Record<string, { text: string; type: 'info' | 'success' | 'warning' }> = {
  DRAFT: { text: '草稿', type: 'info' },
  PUBLISHED: { text: '已发布', type: 'success' },
  OFFLINE: { text: '已下线', type: 'warning' },
}

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function selectProject(id: string) {
  if (id && id !== projectId.value) void router.push({ query: { ...route.query, project: id } })
}

/** 切换项目或重新加载前，清空所有与旧项目相关的页面状态 */
function resetState() {
  rows.value = []
  total.value = 0
  currentPage.value = 1
  failure.value = ''
  draftDialog.value = false
  detailDialog.value = false
  issueDialog.value = false
  selected.value = undefined
  credentials.value = []
  trialResult.value = undefined
  trialError.value = ''
  issued.value = undefined
}

async function loadProjects() {
  try {
    const list = await listMyProjectWorkspaces()
    projects.value = list
    if (!projectId.value) {
      const first = list.find(item => item.primary) ?? list[0]
      if (first) await router.replace({ query: { ...route.query, project: first.projectGroupId } })
      return
    }
    if (!list.some(item => item.projectGroupId === projectId.value)) {
      failure.value = '当前项目已停用或访问权限已收回，请重新选择项目。'
    }
  } catch {
    failure.value = '项目列表加载失败，请稍后重试。'
  }
}

async function loadRows() {
  const version = ++requestVersion
  rows.value = []
  total.value = 0
  if (!projectId.value) return
  loading.value = true
  try {
    const page = await listWarehouseApis(projectId.value, currentPage.value, pageSize.value)
    if (version !== requestVersion) return
    rows.value = page.records ?? []
    total.value = page.total ?? 0
  } catch {
    if (version === requestVersion) rows.value = []
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

watch([projectId, currentPage], () => { void loadRows() }, { immediate: true })

// ---------------------------------------------------------------- 草稿
const draftDialog = ref(false)
const draftMode = ref<'create' | 'edit'>('create')
const draftSaving = ref(false)
const draftForm = reactive({ apiCode: '', version: 1, name: '', maxRows: 100 })
const editing = ref<WarehouseApiVersion>()

function openCreate() {
  draftMode.value = 'create'
  editing.value = undefined
  draftForm.apiCode = ''
  draftForm.version = 1
  draftForm.name = ''
  draftForm.maxRows = 100
  draftDialog.value = true
}

function openEdit(row: WarehouseApiVersion) {
  if (row.status !== 'DRAFT') {
    ElMessage.warning('只有草稿可以编辑，请先下线已发布版本')
    return
  }
  draftMode.value = 'edit'
  editing.value = row
  draftForm.apiCode = row.apiCode
  draftForm.version = row.apiVersion
  draftForm.name = row.apiName
  draftForm.maxRows = row.maxRows
  draftDialog.value = true
}

async function submitDraft() {
  const name = draftForm.name.trim()
  if (!name) { ElMessage.warning('请填写 API 名称'); return }
  if (draftForm.maxRows < 1 || draftForm.maxRows > 100) { ElMessage.warning('最大返回行数需在 1 至 100 之间'); return }
  if (draftMode.value === 'create' && !/^[a-z][a-z0-9-]{0,47}$/.test(draftForm.apiCode.trim())) {
    ElMessage.warning('API 编码需以小写字母开头，仅含小写字母、数字和连字符')
    return
  }
  draftSaving.value = true
  try {
    if (draftMode.value === 'create') {
      await createWarehouseApiDraft(projectId.value, draftForm.apiCode.trim(), draftForm.version,
        { name, maxRows: draftForm.maxRows })
      ElMessage.success('草稿已创建')
    } else if (editing.value) {
      await editWarehouseApiDraft(projectId.value, editing.value.apiCode, editing.value.apiVersion,
        editing.value.revision, { name, maxRows: draftForm.maxRows })
      ElMessage.success('草稿已更新')
    }
    draftDialog.value = false
    await loadRows()
  } catch {
    // 修订号冲突等错误已由请求拦截器提示
  } finally {
    draftSaving.value = false
  }
}

// ---------------------------------------------------------------- 发布 / 下线
async function publish(row: WarehouseApiVersion) {
  try {
    await ElMessageBox.confirm(
      `发布 ${row.apiCode} v${row.apiVersion}（修订号 ${row.revision}）后将对外提供固定契约调用。`,
      '确认发布', { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
    )
  } catch { return }
  try {
    await publishWarehouseApi(projectId.value, row.apiCode, row.apiVersion, row.revision)
    ElMessage.success('已发布')
    await loadRows()
  } catch { /* 拦截器已提示 */ }
}

async function offline(row: WarehouseApiVersion) {
  try {
    await ElMessageBox.confirm(
      `下线 ${row.apiCode} v${row.apiVersion} 将立即停止对外服务，已签发的凭据不再可用。`,
      '确认下线', { type: 'warning', confirmButtonText: '下线', cancelButtonText: '取消' },
    )
  } catch { return }
  try {
    await offlineWarehouseApi(projectId.value, row.apiCode, row.apiVersion, row.revision)
    ElMessage.success('已下线')
    await loadRows()
  } catch { /* 拦截器已提示 */ }
}

// ---------------------------------------------------------------- 详情
const detailDialog = ref(false)
const selected = ref<WarehouseApiVersion>()
const credentials = ref<WarehouseApiCredential[]>([])
const credLoading = ref(false)
const trialBizDate = ref('')
const trialLoading = ref(false)
const trialResult = ref<OrderRegionGmv>()
const trialError = ref('')

async function openDetail(row: WarehouseApiVersion) {
  selected.value = row
  credentials.value = []
  trialResult.value = undefined
  trialError.value = ''
  trialBizDate.value = ''
  detailDialog.value = true
  await loadCredentials()
}

async function loadCredentials() {
  if (!selected.value) return
  credLoading.value = true
  try {
    const page = await listWarehouseApiCredentials(projectId.value, selected.value.apiCode,
      selected.value.apiVersion, 1, 20)
    credentials.value = page.records ?? []
  } catch {
    credentials.value = []
  } finally {
    credLoading.value = false
  }
}

const requestUrl = computed(() => {
  if (!selected.value || !trialBizDate.value) return ''
  return warehouseApiDataUrl(projectId.value, selected.value.apiCode, selected.value.apiVersion, trialBizDate.value)
})

const URL_PLACEHOLDER = '填写业务日期后显示运行地址'
const CURL_PLACEHOLDER = 'curl -H "Authorization: Bearer <凭据>" "<运行地址>"'

const curlExample = computed(() => {
  if (!requestUrl.value) return ''
  return `curl -H "Authorization: Bearer <凭据>" "${requestUrl.value}"`
})

async function runTrial() {
  if (!selected.value) return
  if (!/^\d{4}-\d{2}-\d{2}$/.test(trialBizDate.value)) {
    ElMessage.warning('请填写 YYYY-MM-DD 格式的业务日期')
    return
  }
  trialLoading.value = true
  trialError.value = ''
  trialResult.value = undefined
  try {
    trialResult.value = await invokeWarehouseApi(projectId.value, selected.value.apiCode,
      selected.value.apiVersion, trialBizDate.value)
  } catch (error) {
    trialError.value = error instanceof Error ? error.message : '试跑失败'
  } finally {
    trialLoading.value = false
  }
}

// ---------------------------------------------------------------- 凭据
const issueDialog = ref(false)
const issuing = ref(false)
const issueForm = reactive({ appName: '', ttlHours: 1 })
const issued = ref<IssuedWarehouseCredential>()

function openIssue() {
  issueForm.appName = ''
  issueForm.ttlHours = 1
  issued.value = undefined
  issueDialog.value = true
}

async function submitIssue() {
  if (!selected.value) return
  const appName = issueForm.appName.trim()
  if (!appName) { ElMessage.warning('请填写应用名称'); return }
  if (issueForm.ttlHours < 1 || issueForm.ttlHours > 168) { ElMessage.warning('有效期需在 1 至 168 小时之间'); return }
  issuing.value = true
  try {
    issued.value = await issueWarehouseApiCredential(projectId.value, selected.value.apiCode,
      selected.value.apiVersion, appName, issueForm.ttlHours)
    await loadCredentials()
  } catch {
    // 拦截器已提示
  } finally {
    issuing.value = false
  }
}

/** 关闭签发弹窗即释放明文，避免凭据留在页面状态中 */
function closeIssue() {
  issueDialog.value = false
  issued.value = undefined
  issueForm.appName = ''
  issueForm.ttlHours = 1
}

async function copyToken() {
  if (!issued.value) return
  try {
    await navigator.clipboard.writeText(issued.value.token)
    ElMessage.success('凭据已复制')
  } catch {
    ElMessage.warning('浏览器不允许自动复制，请手动选取')
  }
}

async function revoke(credential: WarehouseApiCredential) {
  if (!selected.value) return
  try {
    await ElMessageBox.confirm(`吊销「${credential.appName}」的凭据后，其后续调用会被立即拒绝。`,
      '确认吊销', { type: 'warning', confirmButtonText: '吊销', cancelButtonText: '取消' })
  } catch { return }
  try {
    await revokeWarehouseApiCredential(projectId.value, selected.value.apiCode,
      selected.value.apiVersion, credential.id)
    ElMessage.success('已吊销')
    await loadCredentials()
  } catch { /* 拦截器已提示 */ }
}

function reset() {
  resetState()
  void loadRows()
  void loadProjects()
}

void loadProjects()
</script>

<template>
  <section class="warehouse-api">
    <DkPageHeader
      title="数仓 API"
      description="发布数仓结果、固定调用契约，并为应用签发短期凭据。"
    >
      <template #actions>
        <DkButton variant="quiet" @click="reset">刷新</DkButton>
        <DkButton v-if="canDevelop" @click="openCreate">新建 API 草稿</DkButton>
      </template>
    </DkPageHeader>

    <el-alert v-if="failure" :title="failure" type="warning" show-icon :closable="false" />

    <div class="toolbar">
      <el-select
        :model-value="projectId || undefined"
        placeholder="选择项目"
        aria-label="选择项目"
        style="width: 260px"
        @change="selectProject"
      >
        <el-option
          v-for="item in projects"
          :key="item.projectGroupId"
          :label="item.name"
          :value="item.projectGroupId"
        />
      </el-select>
      <span class="hint">
        <template v-if="currentProject">
          {{ currentProject.name }}
          <template v-if="currentProject.roleCode">· {{ currentProject.roleCode }}</template>
          <template v-if="!canDevelop">· 当前角色无数据开发权限，仅可查看与试跑</template>
        </template>
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <template #empty>
        <el-empty description="当前项目还没有发布过数仓 API" />
      </template>
      <el-table-column prop="apiName" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="apiCode" label="编码" min-width="160" show-overflow-tooltip />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">v{{ row.apiVersion }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status]?.type ?? 'info'" size="small">
            {{ statusMeta[row.status]?.text ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最大行数" width="100">
        <template #default="{ row }">{{ row.maxRows }}</template>
      </el-table-column>
      <el-table-column label="修订号" width="90">
        <template #default="{ row }">{{ row.revision }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <template v-if="canDevelop">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publish(row)">发布</el-button>
            <el-button v-if="row.status === 'PUBLISHED'" link type="warning" @click="offline(row)">下线</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, total"
        background
      />
    </div>

    <!-- 草稿新建 / 编辑 -->
    <el-dialog v-model="draftDialog" :title="draftMode === 'create' ? '新建 API 草稿' : '编辑草稿'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="API 编码">
          <el-input v-model="draftForm.apiCode" :disabled="draftMode === 'edit'" placeholder="如 order-region-gmv" />
        </el-form-item>
        <el-form-item label="版本号">
          <el-input-number v-model="draftForm.version" :min="1" :disabled="draftMode === 'edit'" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="draftForm.name" maxlength="100" show-word-limit placeholder="对外展示的名称" />
        </el-form-item>
        <el-form-item label="最大返回行数">
          <el-input-number v-model="draftForm.maxRows" :min="1" :max="100" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="draftMode === 'edit'"
        title="提交时携带当前修订号，若已被他人修改会提示冲突。"
        type="info" :closable="false" show-icon
      />
      <template #footer>
        <el-button @click="draftDialog = false">取消</el-button>
        <el-button type="primary" :loading="draftSaving" @click="submitDraft">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailDialog" title="API 详情" width="860px">
      <template v-if="selected">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="名称">{{ selected.apiName }}</el-descriptions-item>
          <el-descriptions-item label="编码">{{ selected.apiCode }} v{{ selected.apiVersion }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMeta[selected.status]?.type ?? 'info'" size="small">
              {{ statusMeta[selected.status]?.text ?? selected.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="修订号">{{ selected.revision }}</el-descriptions-item>
          <el-descriptions-item label="最大行数">{{ selected.maxRows }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ formatTime(selected.publishedAt) }}</el-descriptions-item>
        </el-descriptions>

        <h4>试跑</h4>
        <div class="trial-row">
          <el-input v-model="trialBizDate" placeholder="业务日期 YYYY-MM-DD" style="width: 220px" />
          <el-button type="primary" :loading="trialLoading" @click="runTrial">试跑</el-button>
          <span class="hint">试跑以当前登录身份执行，只读取已发布且质量通过的受管物化结果。</span>
        </div>

        <el-alert v-if="trialError" :title="trialError" type="error" show-icon :closable="false" />

        <template v-if="trialResult">
          <el-descriptions :column="2" border size="small" class="trial-meta">
            <el-descriptions-item label="业务日期">{{ trialResult.bizDate }}</el-descriptions-item>
            <el-descriptions-item label="记录数">{{ trialResult.rows?.length ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="发布 ID">{{ trialResult.publicationId }}</el-descriptions-item>
            <el-descriptions-item label="质量快照 ID">{{ trialResult.qualitySnapshotId }}</el-descriptions-item>
            <el-descriptions-item label="发布时间">{{ formatTime(trialResult.publishedAt) }}</el-descriptions-item>
            <el-descriptions-item label="是否截断">{{ trialResult.truncated ? '是' : '否' }}</el-descriptions-item>
          </el-descriptions>
          <el-table v-if="trialResult.rows?.length" :data="trialResult.rows" border size="small">
            <el-table-column prop="region" label="地区" min-width="140" />
            <el-table-column prop="gmv" label="成交额" min-width="140" />
          </el-table>
          <el-empty v-else description="该业务日没有数据行，发布证据仍然有效" />
        </template>

        <h4>请求示例</h4>
        <el-input :model-value="requestUrl || URL_PLACEHOLDER" readonly />
        <el-input class="curl" :model-value="curlExample || CURL_PLACEHOLDER" readonly />

        <h4>
          调用凭据
          <el-button v-if="canDevelop && selected.status === 'PUBLISHED'" link type="primary" @click="openIssue">
            签发凭据
          </el-button>
        </h4>
        <el-table v-loading="credLoading" :data="credentials" border size="small">
          <template #empty><el-empty description="还没有签发过凭据" /></template>
          <el-table-column prop="appName" label="应用" min-width="120" show-overflow-tooltip />
          <el-table-column prop="issuedBy" label="签发人" width="110" />
          <el-table-column label="签发时间" width="170">
            <template #default="{ row }">{{ formatTime(row.issuedAt) }}</template>
          </el-table-column>
          <el-table-column label="过期时间" width="170">
            <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.revokedAt ? 'info' : 'success'" size="small">
                {{ row.revokedAt ? '已吊销' : '有效' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="canDevelop && !row.revokedAt" link type="danger" @click="revoke(row)">吊销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 签发凭据 -->
    <el-dialog
      v-model="issueDialog"
      title="签发调用凭据"
      width="560px"
      :close-on-click-modal="false"
      @closed="closeIssue"
    >
      <template v-if="!issued">
        <el-form label-width="100px">
          <el-form-item label="应用名称">
            <el-input v-model="issueForm.appName" maxlength="100" show-word-limit placeholder="如 订单看板" />
          </el-form-item>
          <el-form-item label="有效期">
            <el-input-number v-model="issueForm.ttlHours" :min="1" :max="168" />
            <span class="hint">小时，最长 168</span>
          </el-form-item>
        </el-form>
        <el-alert
          title="明文凭据只在签发成功时显示一次，关闭弹窗后无法再次查看。"
          type="warning" :closable="false" show-icon
        />
      </template>
      <template v-else>
        <el-alert
          title="请立即复制并妥善保存，关闭后无法再次查看。"
          type="success" :closable="false" show-icon
        />
        <div class="token-box">
          <code>{{ issued.token }}</code>
          <el-button size="small" type="primary" @click="copyToken">复制</el-button>
        </div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="应用">{{ issued.credential.appName }}</el-descriptions-item>
          <el-descriptions-item label="过期时间">{{ formatTime(issued.credential.expiresAt) }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <template v-if="!issued">
          <el-button @click="closeIssue">取消</el-button>
          <el-button type="primary" :loading="issuing" @click="submitIssue">签发</el-button>
        </template>
        <el-button v-else type="primary" @click="closeIssue">我已保存，关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.warehouse-api { display: flex; flex-direction: column; gap: var(--dk-space-4); }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--dk-space-3); }
.hint { color: var(--dk-muted); font-size: 13px; }
.pager { display: flex; justify-content: flex-end; }
h4 {
  display: flex; align-items: center; gap: var(--dk-space-2);
  margin: var(--dk-space-5) 0 var(--dk-space-3); font-size: 15px; font-weight: 600;
}
.trial-row { display: flex; flex-wrap: wrap; align-items: center; gap: var(--dk-space-2); margin-bottom: var(--dk-space-3); }
.trial-meta { margin-bottom: var(--dk-space-3); }
.curl { margin-top: var(--dk-space-2); }
.token-box {
  display: flex; align-items: center; gap: var(--dk-space-2);
  margin: var(--dk-space-3) 0; padding: var(--dk-space-3);
  background: var(--dk-soft); border-radius: 8px;
}
.token-box code { flex: 1; word-break: break-all; font-size: 12px; }
</style>
