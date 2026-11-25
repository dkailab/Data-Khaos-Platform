<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import FlinkRunsPanel from './FlinkRunsPanel.vue'
import { listMyProjectWorkspaces, type ProjectWorkspace } from '@/api/project-group'
import {
  createFlinkAsset, exportFlinkSql, getFlinkVersion, listFlinkAssets, listFlinkVersions,
  restoreFlinkVersion, saveFlinkAsset,
} from '@/api/flinkStudio'
import type {
  FlinkAsset, FlinkAssetKind, FlinkDefinition, FlinkFunctionType, FlinkLanguage, FlinkMode, FlinkRevision, FlinkVersionView, FunctionRef,
} from '@/api/flinkStudio'

const projects = ref<ProjectWorkspace[]>([])
const project = ref('')
const kind = ref<FlinkAssetKind>('JOB')
const kindTitle: Record<FlinkAssetKind, string> = { JOB: 'SQL 作业', JAR_JOB: 'JAR 作业', FUNCTION: '函数库' }
const functionTypes = [
  { value: 'UDF', label: 'UDF · 标量函数' },
  { value: 'UDTF', label: 'UDTF · 表值函数' },
  { value: 'UDAF', label: 'UDAF · 聚合函数' },
]
const assets = ref<FlinkAsset[]>([])
const keyword = ref('')
const nextKeyword = ref('')
const current = ref(1)
const total = ref(0)
const size = 15
const projectLoading = ref(false)
const loading = ref(false)
const failed = ref('')
let listSequence = 0

const editor = ref(false)
const editing = ref<FlinkVersionView>()
const saving = ref(false)
const formError = ref('')
const form = reactive({
  code: '', name: '', note: '', mode: 'STREAMING' as FlinkMode, parallelism: 1,
  sql: '', functions: [] as FunctionRef[], language: 'JAVA' as FlinkLanguage, className: '', jarUri: '', functionType: '' as FlinkFunctionType | '',
  jarId: '', entryClass: '', arguments: [] as string[],
})
const functionOptions = ref<FlinkAsset[]>([])
let functionSequence = 0

const viewedAsset = ref<FlinkAsset>()
const history = ref<FlinkRevision[]>([])
const historyPage = ref(1)
const historyTotal = ref(0)
const historyLoading = ref(false)
const selected = ref<FlinkVersionView>()
const versionWorking = ref(false)
const historyError = ref('')
const exporting = ref(false)
const runsOpen = ref(false)
const canRun = computed(() => projects.value.find(row => row.projectGroupId === project.value)
  ?.capabilities.includes('task:schedule') ?? false)
let historySequence = 0
let selectionSequence = 0

const canSave = computed(() => !!form.name.trim() && !!form.note.trim() && (!!editing.value || !!form.code.trim())
  && (kind.value === 'JOB' ? !!form.sql.trim() && !!form.parallelism && form.functions.every(item => item.assetId && item.version)
    : kind.value === 'JAR_JOB' ? !!form.jarId.trim() && !!form.entryClass.trim() && !!form.parallelism
      : !!form.className.trim()))

async function loadProjects() {
  projectLoading.value = true
  failed.value = ''
  try {
    projects.value = (await listMyProjectWorkspaces()).filter(row => row.capabilities.includes('task:develop'))
    project.value = projects.value.find(row => row.primary)?.projectGroupId ?? projects.value[0]?.projectGroupId ?? ''
  } catch {
    failed.value = '无法加载项目，请刷新重试。'
  } finally {
    projectLoading.value = false
  }
}

async function loadAssets() {
  const sequence = ++listSequence
  if (!project.value) return
  loading.value = true
  failed.value = ''
  try {
    const page = await listFlinkAssets(project.value, kind.value, current.value, size, keyword.value)
    if (sequence !== listSequence) return
    assets.value = page.records
    total.value = Number(page.total)
  } catch (e) {
    if (sequence !== listSequence) return
    assets.value = []
    total.value = 0
    failed.value = e instanceof Error ? e.message : '资产加载失败'
  } finally {
    if (sequence === listSequence) loading.value = false
  }
}

watch([project, kind], () => {
  assets.value = []
  total.value = 0
  current.value = 1
  keyword.value = ''
  nextKeyword.value = ''
  editor.value = false
  closeHistory()
  functionSequence++
  void loadAssets()
})

function search() {
  current.value = 1
  keyword.value = nextKeyword.value.trim()
  void loadAssets()
}

async function loadFunctions(phrase = '') {
  const sequence = ++functionSequence
  try {
    const page = await listFlinkAssets(project.value, 'FUNCTION', 1, 50, phrase)
    if (sequence !== functionSequence) return
    const selectedIds = new Set(form.functions.map(item => item.assetId))
    const keep = functionOptions.value.filter(row => selectedIds.has(row.id))
    const byId = new Map([...keep, ...page.records].map(row => [row.id, row]))
    functionOptions.value = [...byId.values()]
  } catch {
    if (sequence === functionSequence) formError.value = '无法加载函数列表，可重试搜索。'
  }
}

function functionChanged(dependency: FunctionRef) {
  dependency.version = functionOptions.value.find(row => row.id === dependency.assetId)?.currentVersion ?? 1
}

async function openEditor(row?: FlinkAsset) {
  const activeProject = project.value
  const activeKind = kind.value
  if (!activeProject) return
  try {
    const version = row ? await getFlinkVersion(activeProject, row.id, row.currentVersion) : undefined
    if (activeProject !== project.value || activeKind !== kind.value) return
    editing.value = version
    const def = version?.definition
    Object.assign(form, {
      code: version?.code ?? '', name: version?.revision.name ?? '', note: '', mode: def?.mode ?? 'STREAMING',
      parallelism: def?.parallelism ?? 1, sql: def?.sql ?? '', functions: def?.functions?.map(item => ({ ...item })) ?? [],
      language: def?.language ?? 'JAVA', className: def?.className ?? '', jarUri: def?.jarUri ?? '',
      functionType: def?.functionType ?? (version ? '' : 'UDF'),
      jarId: def?.jarJob?.jarId ?? '', entryClass: def?.jarJob?.entryClass ?? '', arguments: [...(def?.jarJob?.arguments ?? [])],
    })
    formError.value = ''
    functionOptions.value = []
    editor.value = true
    if (kind.value === 'JOB') void loadFunctions()
  } catch {
    // The request layer displays the server error.
  }
}

async function save() {
  if (saving.value || !canSave.value) return
  const definition: FlinkDefinition = kind.value === 'JOB'
    ? { sql: form.sql, mode: form.mode, parallelism: form.parallelism, functions: form.functions }
    : kind.value === 'JAR_JOB'
      ? { mode: form.mode, parallelism: form.parallelism,
          jarJob: { jarId: form.jarId, entryClass: form.entryClass, arguments: [...form.arguments] } }
      : { language: form.language, className: form.className, jarUri: form.jarUri || undefined,
          functionType: form.functionType || undefined }
  const input = { name: form.name, note: form.note, definition }
  saving.value = true
  formError.value = ''
  try {
    const result = editing.value
      ? await saveFlinkAsset(project.value, editing.value.assetId, { ...input, expectedVersion: editing.value.revision.version })
      : await createFlinkAsset(project.value, { ...input, kind: kind.value, code: form.code })
    ElMessage.success(`已保存 v${result.revision.version}`)
    editor.value = false
    await loadAssets()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function openHistory(row: FlinkAsset) {
  viewedAsset.value = row
  selected.value = undefined
  runsOpen.value = false
  history.value = []
  historyPage.value = 1
  historyTotal.value = 0
  await loadHistory()
}

async function loadHistory() {
  const row = viewedAsset.value
  if (!row) return
  const sequence = ++historySequence
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await listFlinkVersions(project.value, row.id, historyPage.value, size)
    if (sequence !== historySequence || viewedAsset.value?.id !== row.id) return
    history.value = page.records
    historyTotal.value = Number(page.total)
  } catch (e) {
    if (sequence === historySequence) historyError.value = e instanceof Error ? e.message : '版本历史加载失败'
  } finally {
    if (sequence === historySequence) historyLoading.value = false
  }
}

async function viewVersion(version: number) {
  const row = viewedAsset.value
  if (!row) return
  const sequence = ++selectionSequence
  selected.value = undefined
  versionWorking.value = true
  historyError.value = ''
  try {
    const result = await getFlinkVersion(project.value, row.id, version)
    if (sequence === selectionSequence && viewedAsset.value?.id === row.id) selected.value = result
  } catch (e) {
    if (sequence === selectionSequence) historyError.value = e instanceof Error ? e.message : '版本加载失败'
  } finally {
    if (sequence === selectionSequence) versionWorking.value = false
  }
}

function closeHistory() {
  historySequence++
  selectionSequence++
  viewedAsset.value = undefined
  selected.value = undefined
  runsOpen.value = false
  versionWorking.value = false
}

async function restore() {
  const version = selected.value
  if (!version || versionWorking.value) return
  let note: string
  try {
    const answer = await ElMessageBox.prompt(`将 v${version.revision.version} 的内容保存为新版本。`, '恢复版本', {
      inputPlaceholder: '填写恢复原因', inputValidator: value => !!value?.trim() && value.trim().length <= 500 || '请输入 1 至 500 字',
      confirmButtonText: '恢复', cancelButtonText: '取消',
    })
    note = answer.value
  } catch {
    return
  }
  versionWorking.value = true
  historyError.value = ''
  try {
    selected.value = await restoreFlinkVersion(project.value, version.assetId, version.revision.version, version.currentVersion, note)
    ElMessage.success(`已恢复为 v${selected.value.revision.version}`)
    historyPage.value = 1
    await Promise.all([loadHistory(), loadAssets()])
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : '恢复失败'
  } finally {
    versionWorking.value = false
  }
}

async function download() {
  const version = selected.value
  if (!version) return
  exporting.value = true
  try {
    const source = await exportFlinkSql(project.value, version.assetId, version.revision.version)
    const url = URL.createObjectURL(new Blob([source], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${version.code}-v${version.revision.version}.sql`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : '导出失败'
  } finally {
    exporting.value = false
  }
}

function time(value: string) {
  return value?.replace('T', ' ').slice(0, 19)
}

onMounted(() => void loadProjects())
</script>

<template>
  <div class="flink-studio">
    <DkPageHeader title="Flink 开发" description="管理 SQL 作业、JAR 作业和函数版本。">
      <template #actions>
        <el-select v-model="project" aria-label="开发项目" placeholder="选择开发项目" :loading="projectLoading" class="project-select">
          <el-option v-for="row in projects" :key="row.projectGroupId" :label="row.name" :value="row.projectGroupId" />
        </el-select>
      </template>
    </DkPageHeader>

    <el-alert v-if="failed" type="error" :title="failed" :closable="false" show-icon />
    <el-empty v-if="!project && !projectLoading" description="暂无具备数据开发权限的项目">
      <DkButton @click="loadProjects">刷新项目</DkButton>
    </el-empty>
    <el-card v-if="project" shadow="never">
      <el-tabs v-model="kind">
        <el-tab-pane name="JOB" label="SQL 作业" />
        <el-tab-pane name="JAR_JOB" label="JAR 作业" />
        <el-tab-pane name="FUNCTION" label="函数库" />
      </el-tabs>
      <div class="asset-tools">
        <form class="asset-search" @submit.prevent="search">
          <el-input v-model="nextKeyword" aria-label="搜索资产" placeholder="名称或编码" clearable @clear="search" />
          <DkButton type="submit">搜索</DkButton>
        </form>
        <DkButton variant="primary" @click="openEditor()">{{ kind === 'FUNCTION' ? '登记函数' : '新建 ' + kindTitle[kind] }}</DkButton>
      </div>
      <el-table v-loading="loading" :data="assets" row-key="id" :empty-text="'暂无' + kindTitle[kind]">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="code" label="编码" min-width="140" />
        <el-table-column v-if="kind !== 'FUNCTION'" label="运行模式" width="85">
          <template #default="{ row }">{{ row.currentMode === 'BATCH' ? '批处理' : '流式' }}</template>
        </el-table-column>
        <el-table-column v-if="kind === 'FUNCTION'" label="函数类型" width="110">
          <template #default="{ row }">{{ row.functionType || '待分类' }}</template>
        </el-table-column>
        <el-table-column label="当前版本" width="90"><template #default="{ row }">v{{ row.currentVersion }}</template></el-table-column>
        <el-table-column label="更新时间" width="165"><template #default="{ row }">{{ time(row.updateTime) }}</template></el-table-column>
        <el-table-column label="操作" width="135">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditor(row)">编辑</el-button>
            <el-button link type="primary" @click="openHistory(row)">历史版本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
        layout="prev, pager, next" @current-change="loadAssets" />
    </el-card>

    <el-dialog v-model="editor" :title="editing ? '编辑定义' : kind === 'FUNCTION' ? '登记 Flink 函数' : '新建 Flink ' + kindTitle[kind]"
      width="min(850px, calc(100vw - 28px))" :show-close="!saving" :close-on-click-modal="false" :close-on-press-escape="!saving">
      <el-alert v-if="formError" type="error" :title="formError" :closable="false" show-icon />
      <el-form label-position="top" class="studio-form" @submit.prevent="save">
        <div class="form-grid">
          <el-form-item label="编码">
            <el-input v-model="form.code" aria-label="编码" maxlength="64" :disabled="!!editing" placeholder="例如 orders_gmv" />
          </el-form-item>
          <el-form-item label="名称"><el-input v-model="form.name" aria-label="名称" maxlength="200" /></el-form-item>
        </div>
        <template v-if="kind !== 'FUNCTION'">
          <div class="form-grid">
            <el-form-item label="运行模式">
              <el-select v-model="form.mode" aria-label="运行模式">
                <el-option value="STREAMING" label="流式" /><el-option value="BATCH" label="批处理" />
              </el-select>
            </el-form-item>
            <el-form-item label="并行度"><el-input-number v-model="form.parallelism" aria-label="并行度" :min="1" :max="1024" /></el-form-item>
          </div>
        </template>
        <template v-if="kind === 'JOB'">
          <el-form-item label="Flink SQL">
            <el-input v-model="form.sql" aria-label="Flink SQL" type="textarea" :rows="9" maxlength="131072" class="sql-input" />
          </el-form-item>
          <el-form-item label="函数版本">
            <div v-for="(dep, i) in form.functions" :key="i" class="dependency">
              <el-select v-model="dep.assetId" aria-label="依赖函数" placeholder="按名称或编码搜索" filterable remote
                :remote-method="loadFunctions" @change="functionChanged(dep)">
                <el-option v-for="row in functionOptions" :key="row.id" :value="row.id"
                  :label="`${row.name} · ${row.code}（最新 v${row.currentVersion}）`" />
              </el-select>
              <el-input-number v-model="dep.version" aria-label="依赖版本号" :min="1" />
              <el-button link type="danger" @click="form.functions.splice(i, 1)">移除</el-button>
            </div>
            <DkButton :disabled="form.functions.length >= 20" @click="form.functions.push({ assetId: '', version: 1 })">添加函数</DkButton>
          </el-form-item>
        </template>
        <template v-else-if="kind === 'JAR_JOB'">
          <el-form-item label="集群 JAR ID">
            <el-input v-model="form.jarId" aria-label="集群 JAR ID" maxlength="255"
              placeholder="从已绑定 Flink 集群的 JAR 列表获取" />
          </el-form-item>
          <el-form-item label="入口类">
            <el-input v-model="form.entryClass" aria-label="入口类" maxlength="255" placeholder="com.example.OrdersJob" />
          </el-form-item>
          <el-form-item label="启动参数">
            <div v-for="(_, i) in form.arguments" :key="i" class="dependency">
              <el-input v-model="form.arguments[i]" :aria-label="'启动参数 ' + (i + 1)" maxlength="2048" />
              <el-button link type="danger" @click="form.arguments.splice(i, 1)">移除</el-button>
            </div>
            <DkButton :disabled="form.arguments.length >= 64" @click="form.arguments.push('')">添加参数</DkButton>
          </el-form-item>
        </template>
        <template v-else>
          <div class="form-grid">
            <el-form-item label="函数类型">
              <el-select v-model="form.functionType" aria-label="函数类型" placeholder="待分类">
                <el-option v-for="option in functionTypes" :key="option.value" :value="option.value" :label="option.label" />
              </el-select>
            </el-form-item>
            <el-form-item label="语言">
              <el-select v-model="form.language" aria-label="语言">
                <el-option value="JAVA" label="Java" /><el-option value="SCALA" label="Scala" />
              </el-select>
            </el-form-item>
            <el-form-item label="JVM 类名"><el-input v-model="form.className" aria-label="JVM 类名" placeholder="com.example.RevenueUdf" /></el-form-item>
          </div>
          <el-form-item label="JAR 地址"><el-input v-model="form.jarUri" aria-label="JAR 地址" placeholder="可选，使用带版本的制品地址" /></el-form-item>
        </template>
        <el-form-item label="版本说明"><el-input v-model="form.note" aria-label="版本说明" maxlength="500" /></el-form-item>
        <DkButton type="submit" variant="primary" :disabled="!canSave" :loading="saving">保存版本</DkButton>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="!!viewedAsset" :title="`历史版本 · ${viewedAsset?.name ?? ''}`" width="min(850px, calc(100vw - 28px))"
      :close-on-click-modal="false" @update:model-value="$event === false && closeHistory()">
      <el-alert v-if="historyError" type="error" :title="historyError" :closable="false" show-icon />
      <el-table v-loading="historyLoading" :data="history" row-key="version" empty-text="暂无历史版本">
        <el-table-column label="版本" width="75"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
        <el-table-column prop="note" label="版本说明" min-width="150" show-overflow-tooltip />
        <el-table-column prop="createdBy" label="作者 ID" min-width="100" />
        <el-table-column label="保存时间" width="165"><template #default="{ row }">{{ time(row.createTime) }}</template></el-table-column>
        <el-table-column label="操作" width="75">
          <template #default="{ row }"><el-button link type="primary" :disabled="versionWorking" @click="viewVersion(row.version)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="historyTotal > size" v-model:current-page="historyPage" :page-size="size" :total="historyTotal"
        layout="prev, pager, next" @current-change="loadHistory" />
      <section v-if="selected" class="version-content">
        <div class="asset-tools">
          <h3>v{{ selected.revision.version }} · {{ selected.revision.name }}</h3>
          <div class="asset-actions">
            <DkButton v-if="selected.kind !== 'FUNCTION'" :aria-expanded="runsOpen" @click="runsOpen = !runsOpen">
              {{ runsOpen ? '收起运行记录' : '运行与记录' }}
            </DkButton>
            <DkButton v-if="selected.kind === 'JOB'" :loading="exporting" @click="download">下载 SQL</DkButton>
            <DkButton :loading="versionWorking" :disabled="selected.revision.version === selected.currentVersion" @click="restore">恢复为新版本</DkButton>
          </div>
        </div>
        <p v-if="selected.revision.restoredFrom">恢复自 v{{ selected.revision.restoredFrom }}</p>
        <template v-if="selected.kind !== 'FUNCTION'">
          <p>{{ selected.definition.mode === 'BATCH' ? '批处理' : '流式' }} · 并行度 {{ selected.definition.parallelism }}</p>
          <ul v-if="selected.definition.functions?.length" aria-label="固定函数版本">
            <li v-for="dep in selected.definition.functions" :key="dep.assetId">{{ dep.assetId }} · v{{ dep.version }}</li>
          </ul>
          <pre v-if="selected.kind === 'JOB'">{{ selected.definition.sql }}</pre>
          <dl v-else-if="selected.definition.jarJob">
            <dt>集群 JAR ID</dt><dd>{{ selected.definition.jarJob.jarId }}</dd>
            <dt>入口类</dt><dd>{{ selected.definition.jarJob.entryClass }}</dd>
            <dt>启动参数</dt><dd>{{ JSON.stringify(selected.definition.jarJob.arguments) }}</dd>
          </dl>
          <FlinkRunsPanel v-if="runsOpen" :project="project" :asset-id="selected.assetId"
            :version="selected.revision.version" :can-run="canRun" :jar-job="selected.kind === 'JAR_JOB'" :streaming="selected.definition.mode === 'STREAMING'" />
        </template>
        <dl v-else>
          <dt>函数类型</dt><dd>{{ selected.definition.functionType || '待分类' }}</dd>
          <dt>语言</dt><dd>{{ selected.definition.language }}</dd>
          <dt>JVM 类名</dt><dd>{{ selected.definition.className }}</dd>
          <dt>JAR 地址</dt><dd>{{ selected.definition.jarUri || '使用运行环境类路径' }}</dd>
        </dl>
      </section>
    </el-dialog>
  </div>
</template>

<style scoped>
.flink-studio { display: grid; gap: var(--dk-space-4); min-width: 0; }
.project-select { width: min(240px, 68vw); }
.asset-tools, .asset-actions, .asset-search, .dependency {
  display: flex; align-items: center; flex-wrap: wrap; gap: var(--dk-space-2);
}
.asset-tools { justify-content: space-between; margin-bottom: var(--dk-space-3); }
.asset-search .el-input { width: min(210px, 58vw); }
.studio-form { margin-top: var(--dk-space-3); }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr)); column-gap: var(--dk-space-4); }
.sql-input :deep(textarea), pre { font-family: var(--dk-mono, monospace); }
.dependency { width: 100%; margin-bottom: var(--dk-space-2); }
.dependency .el-select { flex: 1 1 180px; }
.version-content { margin-top: var(--dk-space-4); overflow-wrap: anywhere; }
.version-content h3 { margin: 0; }
.version-content pre { padding: var(--dk-space-3); max-height: 300px; overflow: auto; background: var(--dk-soft); }
.version-content dd { margin-left: 0; margin-bottom: var(--dk-space-2); }
</style>
