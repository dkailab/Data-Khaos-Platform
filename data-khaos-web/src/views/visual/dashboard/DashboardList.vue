<template>
  <div class="dashboard-list-page">
    <!-- 头部 banner -->
    <div className="page-header">
      <div class="header-content">
        <h1 class="page-title">仪表板设计器</h1>
        <p class="page-subtitle">拖拉拽构建数据可视化看板 · 像素级精准排版</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" size="large" @click="openCreate">新建仪表板</el-button>
      </div>
    </div>

    <!-- 快速模板 -->
    <section class="quick-templates">
      <div class="section-title">快速开始</div>
      <div class="template-grid">
        <div
          v-for="tpl in templates"
          :key="tpl.code"
          class="template-card"
          @click="createFromTemplate(tpl)"
        >
          <div class="tpl-icon" :style="{ background: tpl.gradient }">
            <el-icon :size="28"><component :is="tpl.icon" /></el-icon>
          </div>
          <div class="tpl-info">
            <span class="tpl-name">{{ tpl.name }}</span>
          </div>
        </div>
        <div class="template-card blank-card" @click="openCreate">
          <div class="tpl-icon blank-icon">
            <el-icon :size="32"><Plus /></el-icon>
          </div>
          <div class="tpl-info">
            <span class="tpl-name">空白画布</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 仪表板列表 -->
    <section class="dashboard-section">
      <div class="section-header">
        <div class="section-title">我的仪表板</div>
        <div class="section-tools">
          <el-input
            v-model="query.keyword"
            placeholder="搜索仪表板..."
            clearable
            style="width: 240px"
            :prefix-icon="Search"
            @keyup.enter="load"
          />
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button value="grid"><el-icon><Grid /></el-icon></el-radio-button>
            <el-radio-button value="list"><el-icon><List /></el-icon></el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <!-- 网格视图 -->
      <div v-if="viewMode === 'grid'" v-loading="loading" class="dashboard-grid">
        <div
          v-for="item in list"
          :key="item.id"
          class="dashboard-card"
          @click="openEditor(item)"
        >
          <div class="card-thumbnail" :style="getCardStyle(item)">
            <div class="card-overlay">
              <el-button circle :icon="View" @click.stop="previewDashboard(item)" />
              <el-button circle :icon="Edit" @click.stop="openEditor(item)" />
            </div>
          </div>
          <div class="card-info">
            <div class="card-title">{{ item.name || '未命名仪表板' }}</div>
            <div class="card-meta">
              <el-tag v-if="item.status === 2" type="success" size="small">已上线</el-tag>
              <el-tag v-else type="warning" size="small">草稿</el-tag>
              <span class="card-time">{{ formatTime(item.updateTime || item.createTime) }}</span>
            </div>
          </div>
          <div class="card-actions">
            <el-dropdown trigger="click" @command="(cmd: any) => handleAction(cmd, item)">
              <el-button :icon="More" circle size="small" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit"><el-icon><Edit /></el-icon>编辑</el-dropdown-item>
                  <el-dropdown-item command="duplicate"><el-icon><CopyDocument /></el-icon>复制</el-dropdown-item>
                  <el-dropdown-item v-if="item.status !== 2" command="publish" divided><el-icon><Top /></el-icon>上线</el-dropdown-item>
                  <el-dropdown-item v-else command="unpublish"><el-icon><Bottom /></el-icon>下线</el-dropdown-item>
                  <el-dropdown-item command="delete" divided type="danger"><el-icon><Delete /></el-icon>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>

      <!-- 列表视图 -->
      <el-table v-else v-loading="loading" :data="list" border stripe style="width: 100%">
        <el-table-column prop="name" label="仪表板名称" min-width="200">
          <template #default="{ row }">
            <div class="list-name-cell">
              <div class="list-thumb" :style="getCardStyle(row)"></div>
              <span>{{ row.name || '未命名仪表板' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="250" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 2" type="success" size="small">已上线</el-tag>
            <el-tag v-else type="warning" size="small">草稿</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="createBy" label="创建人" width="120" />
        <el-table-column prop="updateTime" label="更新时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.updateTime || row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditor(row)">编辑</el-button>
            <el-button link @click="previewDashboard(row)">预览</el-button>
            <el-button v-if="row.status !== 2" link type="success" @click="handlePublish(row)">上线</el-button>
            <el-button v-else link type="warning" @click="handleUnpublish(row)">下线</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && !list.length" description="暂无仪表板，点击上方按钮创建" :image-size="100">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建仪表板</el-button>
      </el-empty>

      <el-pagination
        class="pager"
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[12, 24, 48]"
        layout="total, sizes, prev, pager, next, jumper"
        @change="load"
      />
    </section>

    <!-- 新建仪表板对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑仪表板' : '新建仪表板'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入仪表板名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="仪表板描述" />
        </el-form-item>
        <el-form-item label="画布尺寸">
          <el-select v-model="form.canvasPreset" style="width: 100%" @change="onPresetChange">
            <el-option v-for="p in canvasPresets" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="宽度">
              <el-input-number v-model="form.canvasWidth" :min="375" :max="7680" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="高度">
              <el-input-number v-model="form.canvasHeight" :min="300" :max="4320" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史对话框 -->
    <el-dialog v-model="versionDialog" title="版本历史" width="640px" destroy-on-close>
      <el-table :data="versions" border size="small">
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="remark" label="发布说明" min-width="160" />
        <el-table-column prop="createBy" label="发布人" width="110" />
        <el-table-column prop="createTime" label="发布时间" width="170" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  CopyDocument, Delete, Edit, Grid, List, More, Plus, Search, Top, Bottom, View,
  DataAnalysis, Monitor, PieChart,
} from '@element-plus/icons-vue'
import { createDashboard, dashboardVersions, deleteDashboard, pageDashboards, publishDashboard, saveItem, unpublishDashboard, updateDashboard } from '@/api/visual'
import { martModelDetail, pageMartMarket, queryMart } from '@/api/mart'
import type { VisualDashboard, VisualDashboardVersion } from '@/types'

const router = useRouter()

// ============ 状态 ============
const loading = ref(false)
const submitting = ref(false)
const list = ref<VisualDashboard[]>([])
const total = ref(0)
const query = reactive<Record<string, any>>({ current: 1, size: 12, keyword: '' })
const viewMode = ref<'grid' | 'list'>('grid')

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const isEdit = ref(false)
const form = reactive<any>({
  name: '',
  description: '',
  canvasPreset: '1920x1080',
  canvasWidth: 1920,
  canvasHeight: 1080,
})
const formRules: FormRules = {
  name: [{ required: true, message: '请输入仪表板名称', trigger: 'blur' }],
}

const canvasPresets = [
  { label: '数据大屏 1920×1080', value: '1920x1080', width: 1920, height: 1080 },
  { label: '指挥大屏 3840×2160', value: '3840x2160', width: 3840, height: 2160 },
  { label: 'PC报表 1440×900', value: '1440x900', width: 1440, height: 900 },
  { label: 'PC宽屏 1920×1200', value: '1920x1200', width: 1920, height: 1200 },
  { label: '移动端 375×667', value: '375x667', width: 375, height: 667 },
]

const templates = [
  { code: 'bigscreen', name: '数据大屏', icon: 'Monitor', gradient: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' },
  { code: 'report', name: '数据报表', icon: 'DataAnalysis', gradient: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)' },
  { code: 'mobile', name: '移动看板', icon: 'PieChart', gradient: 'linear-gradient(135deg, #43e97b 0%, #38f9d7 100%)' },
  // —— demo 样例：基于指标/维度语义模型，一键生成可下钻图表 ——
  { code: 'demo_trend', name: '指标趋势样例', icon: 'TrendCharts', gradient: 'linear-gradient(135deg, #f6d365 0%, #fda085 100%)', demo: true, chartType: 'LINE', dimTypePref: 'TIME', canvas: { width: 1440, height: 900 } },
  { code: 'demo_compare', name: '维度分布样例', icon: 'Histogram', gradient: 'linear-gradient(135deg, #84fab0 0%, #8fd3f4 100%)', demo: true, chartType: 'BAR', dimTypePref: 'COMMON', canvas: { width: 1440, height: 900 } },
]

const versionDialog = ref(false)
const versions = ref<VisualDashboardVersion[]>([])

// ============ 方法 ============
async function load() {
  loading.value = true
  try {
    const data = await pageDashboards({ ...query })
    list.value = data.records
    total.value = Number(data.total)
  } finally {
    loading.value = false
  }
}

function onPresetChange(val: string) {
  const preset = canvasPresets.find((p) => p.value === val)
  if (preset) {
    form.canvasWidth = preset.width
    form.canvasHeight = preset.height
  }
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, {
    id: undefined,
    name: '',
    description: '',
    canvasPreset: '1920x1080',
    canvasWidth: 1920,
    canvasHeight: 1080,
  })
  dialogVisible.value = true
}

function createFromTemplate(tpl: any) {
  if (tpl.demo) {
    createDemoDashboard(tpl)
    return
  }
  const preset = canvasPresets.find((p) => {
    if (tpl.code === 'bigscreen') return p.value === '1920x1080'
    if (tpl.code === 'report') return p.value === '1440x900'
    if (tpl.code === 'mobile') return p.value === '375x667'
    return p.value === '1920x1080'
  })
  Object.assign(form, {
    id: undefined,
    name: tpl.name + ' - ' + new Date().toLocaleDateString(),
    description: '',
    canvasPreset: preset?.value || '1920x1080',
    canvasWidth: preset?.width || 1920,
    canvasHeight: preset?.height || 1080,
  })
  dialogVisible.value = true
  isEdit.value = false
}

const demoCreating = ref(false)

/** 一键生成基于指标/维度语义模型的 demo 看板 */
async function createDemoDashboard(tpl: any) {
  if (demoCreating.value) return
  demoCreating.value = true
  try {
    // 1. 取第一个已发布且含指标的模型
    const page = await pageMartMarket({ current: 1, size: 20 })
    const model = (page.records || []).find((m) => (m.metricCount || 0) > 0)
    if (!model || !model.id) {
      ElMessage.warning('暂无已发布模型，请先在「数据集市」发布并关联指标')
      return
    }
    if (!model.datasourceId) {
      ElMessage.warning(`模型「${model.modelName}」未关联数据源，无法生成 demo`)
      return
    }

    // 2. 解析指标与维度
    const detail = await martModelDetail(model.id)
    const metrics = (detail.metrics || []).filter((x) => x.metricCode)
    const dimensions = (detail.dimensions || []).filter((x) => x.dimCode)
    if (!metrics.length) {
      ElMessage.warning(`模型「${model.modelName}」暂无指标`)
      return
    }
    const metric = metrics[0]
    const dim = dimensions.find((d) => d.dimType === tpl.dimTypePref) || dimensions[0]
    const grain = dim && dim.dimType === 'TIME' ? 'M' : undefined

    // 3. 生成语义 SQL
    const q = await queryMart({
      modelId: model.id,
      metrics: [{ metricCode: metric.metricCode }],
      dimensions: dim ? [{ dimCode: dim.dimCode, grain }] : [],
      limit: 1000,
    })

    // 4. 创建仪表板并保存组件
    const canvas = tpl.canvas || { width: 1440, height: 900 }
    const dashboardId = await createDashboard({
      name: tpl.name + ' - ' + new Date().toLocaleDateString(),
      layout: JSON.stringify({ canvasWidth: canvas.width, canvasHeight: canvas.height }),
      refreshInterval: 60,
      status: 1,
    })

    const dataConfig = {
      mode: 'MODEL',
      modelId: model.id,
      modelName: model.modelName || '',
      dimensions: dim
        ? [{ fieldCode: dim.dimCode, fieldName: dim.dimName || dim.dimCode, dimType: dim.dimType, grain: grain || '', sort: '' }]
        : [],
      metrics: [{ fieldCode: metric.metricCode, fieldName: metric.metricName || metric.metricCode, metricType: metric.metricType, unit: metric.unit }],
      filters: [],
      limit: 1000,
    }
    const styleConfig = JSON.stringify({
      title: { show: true, text: '', fontSize: 15, align: 'left' },
      legend: { show: true, position: 'top' },
      colorTheme: 'default',
      labelShow: false,
      decimalDigits: 2,
    })

    await saveItem({
      dashboardId,
      title: dim
        ? `${metric.metricName || metric.metricCode} × ${dim.dimName || dim.dimCode}`
        : metric.metricName || metric.metricCode,
      chartType: tpl.chartType,
      datasourceId: model.datasourceId,
      querySql: q.sql,
      dataConfig: JSON.stringify(dataConfig),
      styleConfig,
      posX: 60,
      posY: 60,
      width: canvas.width - 120,
      height: canvas.height - 120,
      zIndex: 1,
    })

    ElMessage.success('demo 看板已生成，可继续拖拽指标/维度搭建')
    router.push({ name: 'VisualDashboardEdit', params: { id: dashboardId } })
  } catch (e: any) {
    ElMessage.error(e?.message || '生成 demo 失败')
  } finally {
    demoCreating.value = false
  }
}

async function submit() {
  await formRef.value?.validate()
  submitting.value = true
  try {
    const payload = {
      ...form,
      layout: JSON.stringify({ canvasWidth: form.canvasWidth, canvasHeight: form.canvasHeight }),
      refreshInterval: 60,
      status: 1,
    }
    if (isEdit.value && form.id) {
      await updateDashboard(payload)
      ElMessage.success('保存成功')
    } else {
      const res = await createDashboard(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: VisualDashboard) {
  await ElMessageBox.confirm(`确认删除仪表板「${row.name}」吗？将级联删除其组件与版本。`, '提示', { type: 'warning' })
  await deleteDashboard(row.id!)
  ElMessage.success('删除成功')
  load()
}

async function handlePublish(row: VisualDashboard) {
  const { value } = await ElMessageBox.prompt('请输入发布说明（可选）', '上线仪表板', {
    confirmButtonText: '确认上线',
    cancelButtonText: '取消',
    inputType: 'textarea',
  }).catch(() => ({ value: '' }))
  submitting.value = true
  try {
    const v = await publishDashboard(row.id!, value || undefined)
    ElMessage.success(`已上线，版本号 v${v}`)
    load()
  } finally {
    submitting.value = false
  }
}

async function handleUnpublish(row: VisualDashboard) {
  await ElMessageBox.confirm(`确认下线仪表板「${row.name}」？`, '提示', { type: 'warning' })
  await unpublishDashboard(row.id!)
  ElMessage.success('已下线')
  load()
}

function openEditor(row: VisualDashboard) {
  router.push({ name: 'VisualDashboardEdit', params: { id: row.id } })
}

function previewDashboard(row: VisualDashboard) {
  window.open(`${location.origin}/visual/dashboard/view/${row.id}`, '_blank')
}

async function handleAction(cmd: string, row: VisualDashboard) {
  switch (cmd) {
    case 'edit': openEditor(row); break
    case 'duplicate': {
      const newDash = { ...row, name: row.name + ' 副本', status: 1 }
      await createDashboard(newDash)
      ElMessage.success('已复制')
      load()
      break
    }
    case 'publish': await handlePublish(row); break
    case 'unpublish': await handleUnpublish(row); break
    case 'delete': await handleDelete(row); break
  }
}

async function openVersions(row: VisualDashboard) {
  versions.value = await dashboardVersions(row.id!)
  versionDialog.value = true
}

function getCardStyle(row: VisualDashboard): Record<string, string> {
  let layout: any = {}
  try { layout = row.layout ? JSON.parse(row.layout) : {} } catch { /* ignore */ }
  return {
    background: `linear-gradient(135deg, #667eea22, #764ba222), #f8fafc`,
  }
}

function formatTime(t?: string): string {
  if (!t) return '-'
  const d = new Date(t)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
  if (diff < 604800000) return `${Math.floor(diff / 86400000)}天前`
  return d.toLocaleDateString()
}

onMounted(load)
</script>

<style scoped>
.dashboard-list-page {
  min-height: 100vh;
  background: #f8fafc;
  padding: 24px;
}

/* ============ 头部 ============ */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: #1a1d27;
  margin: 0 0 4px 0;
}

.page-subtitle {
  font-size: 14px;
  color: #6b7280;
  margin: 0;
}

/* ============ 快速模板 ============ */
.quick-templates {
  margin-bottom: 32px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: #1a1d27;
  margin-bottom: 16px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
}

.template-card {
  background: #fff;
  border: 2px solid #ebeef5;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.25s;
  text-align: center;
}

.template-card:hover {
  border-color: #4f9df9;
  box-shadow: 0 8px 24px rgba(79, 157, 249, 0.15);
  transform: translateY(-4px);
}

.blank-card { border-style: dashed; }
.blank-card:hover { border-color: #34d399; box-shadow: 0 8px 24px rgba(52, 211, 153, 0.15); }

.tpl-icon {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 12px;
  color: #fff;
}

.blank-icon {
  background: linear-gradient(135deg, #ebeef5, #dcdfe6);
  color: #909399;
}

.tpl-name { display: block; font-size: 15px; font-weight: 600; color: #1a1d27; }

/* ============ 仪表板列表 ============ */
.dashboard-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-tools { display: flex; align-items: center; gap: 12px; }

/* 网格视图 */
.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.dashboard-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.25s;
  position: relative;
}

.dashboard-card:hover {
  border-color: #4f9df9;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.card-thumbnail {
  height: 160px;
  position: relative;
  overflow: hidden;
}

.card-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}

.dashboard-card:hover .card-overlay { opacity: 1; }

.card-info { padding: 12px 16px; }
.card-title { font-size: 15px; font-weight: 600; color: #1a1d27; margin-bottom: 8px; }
.card-meta { display: flex; align-items: center; gap: 8px; }
.card-time { font-size: 12px; color: #909399; }

.card-actions {
  position: absolute;
  top: 8px;
  right: 8px;
}

/* 列表视图 */
.list-name-cell { display: flex; align-items: center; gap: 12px; }
.list-thumb { width: 48px; height: 32px; border-radius: 4px; background: #f0f2f5; }

/* 分页 */
.pager { margin-top: 20px; justify-content: flex-end; }

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .dashboard-list-page { padding: 16px; }
  .page-header { flex-direction: column; align-items: flex-start; gap: 16px; }
  .template-grid { grid-template-columns: repeat(2, 1fr); }
  .dashboard-grid { grid-template-columns: 1fr; }
}
</style>
