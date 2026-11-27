<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ArrowLeft, CircleCheck, Clock, Close, Document, Plus, Right, Setting } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import DkButton from '@/paas-ui/DkButton.vue'
import JobCodeEditor from './JobCodeEditor.vue'
import JobSqlDiff from './JobSqlDiff.vue'
import FlinkRunsPanel from './FlinkRunsPanel.vue'
import {
  createFlinkAsset, exportFlinkSql, getFlinkVersion, listFlinkAssets, listFlinkVersions, previewFlinkSelection,
  restoreFlinkVersion, saveFlinkAsset, submitFlinkRun, validateFlinkDraft,
} from '@/api/flinkStudio'
import type { FlinkAsset, FlinkDefinition, FlinkMode, FlinkRevision, FlinkVersionView, FunctionRef, SqlSelectionPreview } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assets: FlinkAsset[]; fixedMode?: FlinkMode; canRun: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const languages = ['SQL', 'Python', 'Shell', 'Java', 'Scala', 'JavaScript', 'JSON', 'YAML', '纯文本'] as const
type Language = typeof languages[number]
type Bottom = 'output' | 'runs' | 'history'

interface Draft {
  code: string
  name: string
  note: string
  sql: string
  mode: FlinkMode
  parallelism: number
  functions: FunctionRef[]
}

interface Tab {
  key: number
  scratch: boolean
  language: Language
  draft: Draft
  initial: string
  assetId?: string
  version?: number
  modeLocked: boolean
  busy: string
  selection: string
  settings: boolean
  bottom?: Bottom
  error: string
  message: string
  result?: SqlSelectionPreview
  versions: FlinkRevision[]
  historyPage: number
  historyTotal: number
  compared?: FlinkVersionView
  requestId?: string
  requestVersion?: number
}

const opened = ref(false)
const tabs = reactive<Tab[]>([])
const activeKey = ref<number>()
const active = computed(() => tabs.find(tab => tab.key === activeKey.value))
const functionOptions = ref<FlinkAsset[]>([])
const assetLoading = ref('')
const assetError = ref('')
const resourceKeyword = ref('')
const resourceAssets = ref<FlinkAsset[]>()
const resourceError = ref('')
let resourceRequest = 0
const pageSize = 10
let nextKey = 0
let generation = 0
let functionsRequest = 0

function draftFrom(version?: FlinkVersionView): Draft {
  return {
    code: version?.code ?? '', name: version?.revision.name ?? '', note: '', sql: version?.definition.sql ?? '',
    mode: version?.definition.mode ?? props.fixedMode ?? 'STREAMING',
    parallelism: version?.definition.parallelism ?? 1,
    functions: version?.definition.functions?.map(item => ({ ...item })) ?? [],
  }
}

function dirty(tab: Tab) {
  return JSON.stringify(tab.draft) !== tab.initial
}

function addTab(version?: FlinkVersionView, scratch = false) {
  const draft = draftFrom(version)
  const key = ++nextKey
  tabs.push({
    key, scratch, language: 'SQL', draft, initial: JSON.stringify(draft),
    assetId: version?.assetId, version: version?.revision.version, modeLocked: !!props.fixedMode,
    selection: '', settings: !scratch && !version, busy: '', error: '', message: '',
    versions: [], historyPage: 1, historyTotal: 0,
  })
  activeKey.value = key
  openWorkspace()
  return tabs[tabs.length - 1]!
}

async function findAssets() {
  const request = ++resourceRequest
  const project = props.project
  resourceError.value = ''
  try {
    const page = await listFlinkAssets(project, 'JOB', 1, 50, resourceKeyword.value.trim())
    if (request === resourceRequest && project === props.project) resourceAssets.value = page.records
  } catch {
    if (request === resourceRequest) resourceError.value = '无法加载项目作业'
  }
}

function openWorkspace() {
  if (!opened.value) void findAssets()
  opened.value = true
}

async function openAsset(row?: FlinkAsset, view?: Bottom) {
  if (!row) {
    addTab()
    return
  }
  const existing = tabs.find(tab => tab.assetId === row.id)
  if (existing) {
    activeKey.value = existing.key
    openWorkspace()
    if (view) showBottom(existing, view)
    return
  }
  if (assetLoading.value) return
  const project = props.project
  const request = ++generation
  assetLoading.value = row.id
  assetError.value = ''
  openWorkspace()
  try {
    const version = await getFlinkVersion(project, row.id, row.currentVersion)
    if (request !== generation || project !== props.project) return
    const tab = addTab(version)
    if (view) showBottom(tab, view)
  } catch (e) {
    if (request === generation) assetError.value = errorText(e, '加载作业失败')
  } finally {
    if (request === generation) assetLoading.value = ''
  }
}

defineExpose({ openAsset })

async function confirmDiscard(message: string) {
  try {
    await ElMessageBox.confirm(message, '未保存的更改', {
      confirmButtonText: '放弃更改', cancelButtonText: '继续编辑', type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function closeTab(tab: Tab) {
  if (tab.busy || (dirty(tab) && !await confirmDiscard('关闭标签会丢失本标签的未保存内容。'))) return
  const index = tabs.indexOf(tab)
  if (index < 0) return
  tabs.splice(index, 1)
  if (tab.key === activeKey.value) activeKey.value = tabs[Math.max(index - 1, 0)]?.key
}

async function confirmExit() {
  if (tabs.some(tab => tab.busy)) return false
  return !tabs.some(dirty) || await confirmDiscard('离开工作台会丢失所有未保存的工作稿和临时脚本。')
}

function clearWorkspace() {
  ++generation
  ++functionsRequest
  ++resourceRequest
  resourceAssets.value = undefined
  resourceKeyword.value = ''
  resourceError.value = ''
  functionOptions.value = []
  assetLoading.value = ''
  assetError.value = ''
  tabs.splice(0)
  activeKey.value = undefined
  opened.value = false
}

async function closeWorkspace() {
  if (await confirmExit()) clearWorkspace()
}

onBeforeRouteLeave(confirmExit)
onBeforeRouteUpdate((to, from) => to.query.project !== from.query.project ? confirmExit() : true)
watch(() => props.project, clearWorkspace)

function beforeUnload(event: BeforeUnloadEvent) {
  if (!tabs.some(dirty)) return
  event.preventDefault()
  event.returnValue = ''
}

onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

function definition(tab: Tab): FlinkDefinition {
  return {
    mode: tab.draft.mode, parallelism: tab.draft.parallelism, sql: tab.draft.sql,
    functions: tab.draft.functions.map(item => ({ ...item })),
  }
}

function errorText(e: unknown, fallback: string) {
  return e instanceof Error ? e.message : fallback
}

function accept(tab: Tab, saved: FlinkVersionView) {
  tab.draft = draftFrom(saved)
  tab.initial = JSON.stringify(tab.draft)
  tab.assetId = saved.assetId
  tab.version = saved.revision.version
  tab.compared = undefined
  tab.versions = []
  tab.historyPage = 1
  tab.historyTotal = 0
  emit('saved')
  void findAssets()
}

async function save(tab: Tab) {
  if (tab.busy || tab.scratch) return
  const { code, name, note, sql, functions, parallelism } = tab.draft
  if (!name.trim() || !code.trim() || !note.trim() || !sql.trim() || !parallelism || functions.some(item => !item.assetId || !item.version)) {
    tab.settings = true
    tab.error = '请填写编码、名称、版本说明、SQL 和完整的运行配置。'
    return
  }
  tab.busy = 'save'
  tab.error = ''
  try {
    const input = { name, note, definition: definition(tab) }
    const saved = tab.assetId && tab.version
      ? await saveFlinkAsset(props.project, tab.assetId, { ...input, expectedVersion: tab.version })
      : await createFlinkAsset(props.project, { ...input, kind: 'JOB', code })
    accept(tab, saved)
    tab.message = `已保存 v${saved.revision.version}`
    if (tab.bottom === 'history') await loadHistory(tab)
  } catch (e) {
    tab.error = errorText(e, '保存版本失败')
  } finally {
    tab.busy = ''
  }
}

function showBottom(tab: Tab, panel: Bottom) {
  tab.bottom = panel
  if (panel !== 'history' || !tab.assetId || tab.versions.length || tab.busy) return
  tab.historyPage = 1
  void changeHistoryPage(tab)
}

async function loadHistory(tab: Tab) {
  if (!tab.assetId) return
  const page = await listFlinkVersions(props.project, tab.assetId, tab.historyPage, pageSize)
  tab.versions = page.records
  tab.historyTotal = Number(page.total)
}

async function changeHistoryPage(tab: Tab) {
  if (tab.busy) return
  tab.busy = 'history'
  tab.error = ''
  try {
    await loadHistory(tab)
  } catch (e) {
    tab.error = errorText(e, '版本历史加载失败')
  } finally {
    tab.busy = ''
  }
}

async function compare(tab: Tab, revision: number) {
  if (!tab.assetId || tab.busy) return
  tab.busy = 'diff'
  tab.error = ''
  try {
    tab.compared = await getFlinkVersion(props.project, tab.assetId, revision)
  } catch (e) {
    tab.error = errorText(e, '版本加载失败')
  } finally {
    tab.busy = ''
  }
}

async function download(tab: Tab) {
  const version = tab.compared
  if (!version || tab.busy) return
  tab.busy = 'export'
  tab.error = ''
  try {
    const sql = await exportFlinkSql(props.project, version.assetId, version.revision.version)
    const url = URL.createObjectURL(new Blob([sql], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `${version.code}-v${version.revision.version}.sql`
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    tab.error = errorText(e, '导出失败')
  } finally {
    tab.busy = ''
  }
}

async function revert(tab: Tab) {
  const selected = tab.compared
  if (!selected || tab.busy || !tab.version || selected.revision.version === selected.currentVersion) return
  const project = props.project
  const baseVersion = tab.version
  tab.busy = 'restore'
  try {
    const prompt = await ElMessageBox.prompt(
      `回滚到 v${selected.revision.version} 会创建一个新版本。${dirty(tab) ? '本标签未保存的修改将被替换。' : ''}`,
      '回滚作业版本',
      { confirmButtonText: '回滚', cancelButtonText: '取消', inputPlaceholder: '填写回滚原因',
        inputValidator: value => (!!value?.trim() && value.trim().length <= 500) || '请输入 1 至 500 字' },
    ).catch(() => undefined)
    if (!prompt || project !== props.project || !tabs.includes(tab)) return
    const restored = await restoreFlinkVersion(project, selected.assetId, selected.revision.version, baseVersion, prompt.value.trim())
    accept(tab, restored)
    tab.message = `已从 v${selected.revision.version} 回滚并保存为 v${tab.version}`
    await loadHistory(tab)
  } catch (e) {
    tab.error = errorText(e, '回滚失败')
  } finally {
    tab.busy = ''
  }
}

async function debug(tab: Tab, selection: boolean) {
  const sql = tab.selection.trim()
  if (tab.busy || (selection ? !sql || !props.canRun : !tab.draft.sql.trim())) return
  tab.busy = selection ? 'preview' : 'validate'
  tab.bottom = 'output'
  tab.error = ''
  tab.message = ''
  tab.result = undefined
  try {
    if (selection) {
      tab.result = await previewFlinkSelection(props.project, sql)
      tab.message = `返回 ${tab.result.rows.length} 行${tab.result.truncated ? '，已截断' : ''}`
    } else {
      const result = await validateFlinkDraft(props.project, definition(tab))
      tab.message = `草稿校验通过，已验证 ${result.statementCount} 条语句及执行计划。`
    }
  } catch (e) {
    tab.error = errorText(e, '调试失败')
  } finally {
    tab.busy = ''
  }
}

async function execute(tab: Tab) {
  if (!props.canRun || !tab.assetId || !tab.version || tab.busy || dirty(tab)) return
  const project = props.project
  const version = tab.version
  const asset = tab.assetId
  tab.busy = 'execute'
  try {
    const confirmed = await ElMessageBox.confirm(
      `提交「${tab.draft.name}」v${version}，将按该版本配置启动作业。`, '执行全部',
      { confirmButtonText: '提交运行', cancelButtonText: '取消', type: 'warning' },
    ).then(() => true).catch(() => false)
    if (!confirmed || project !== props.project || !tabs.includes(tab)) return
    tab.error = ''
    tab.bottom = 'runs'
    if (!tab.requestId || tab.requestVersion !== version) {
      tab.requestId = crypto.randomUUID()
      tab.requestVersion = version
    }
    const run = await submitFlinkRun(project, asset, version, tab.requestId)
    tab.requestId = undefined
    tab.message = run.message || `已提交 v${version}，请在运行记录查看状态。`
  } catch (e) {
    tab.error = errorText(e, '提交失败')
  } finally {
    tab.busy = ''
  }
}

async function loadFunctions(keyword = '') {
  if (!props.project) return
  const sequence = ++functionsRequest
  const project = props.project
  try {
    const page = await listFlinkAssets(project, 'FUNCTION', 1, 50, keyword)
    if (sequence !== functionsRequest || project !== props.project) return
    const bound = new Set(tabs.flatMap(tab => tab.draft.functions.map(item => item.assetId)))
    const retained = functionOptions.value.filter(row => bound.has(row.id))
    functionOptions.value = [...new Map([...retained, ...page.records].map(row => [row.id, row])).values()]
  } catch {
    // The request layer renders the error, and existing function references stay editable.
  }
}

function toggleSettings(tab: Tab) {
  tab.settings = !tab.settings
  if (tab.settings && !functionOptions.value.length) void loadFunctions()
}

function changeFunction(dep: FunctionRef) {
  dep.version = functionOptions.value.find(row => row.id === dep.assetId)?.currentVersion ?? 1
}

function label(tab: Tab) {
  return tab.scratch ? `临时脚本 ${tab.key}` : tab.draft.name || '未命名 SQL 作业'
}

function field(value: unknown) {
  return value == null ? 'NULL' : String(value)
}
</script>

<template>
  <el-dialog :model-value="opened" fullscreen append-to-body :show-close="false"
    :close-on-click-modal="false" :close-on-press-escape="false"
    class="dk-paas job-ide-dialog" title="SQL 作业编辑工作台">
    <template #header>
      <div class="ide-header">
        <DkButton variant="quiet" @click="closeWorkspace"><template #icon><ArrowLeft /></template>返回列表</DkButton>
        <h2>作业编辑工作台</h2>
        <span v-if="active?.version" class="version-badge">v{{ active.version }}</span>
      </div>
    </template>
    <div class="ide">
      <aside class="ide-assets" aria-label="作业资源">
        <div class="asset-heading">
          <strong>作业资源</strong>
          <button type="button" title="新建 SQL 作业" aria-label="新建 SQL 作业" @click="openAsset()"><Plus /></button>
        </div>
        <button type="button" class="asset-link scratch-link" @click="addTab(undefined, true)"><Plus />临时脚本</button>
        <form class="asset-filter" @submit.prevent="findAssets">
          <el-input v-model="resourceKeyword" placeholder="搜索项目作业" aria-label="搜索项目作业" clearable @clear="findAssets" />
        </form>
        <p v-if="resourceError" class="output-message">{{ resourceError }} <DkButton variant="quiet" @click="findAssets">重试</DkButton></p>
        <button v-for="row in resourceAssets ?? assets" :key="row.id" type="button" class="asset-link"
          :class="{ selected: active?.assetId === row.id }" :title="row.name" @click="openAsset(row)">
          <Document /><span>{{ row.name }}</span>
        </button>
      </aside>

      <div class="ide-content">
        <div class="ide-tabs" role="group" aria-label="打开的编辑标签">
          <div v-for="tab in tabs" :key="tab.key" class="ide-tab" :class="{ 'is-active': activeKey === tab.key }">
            <button type="button" :aria-pressed="activeKey === tab.key" @click="activeKey = tab.key">
              <Document /><span>{{ label(tab) }}</span><span v-if="dirty(tab)" class="unsaved" aria-label="未保存"></span>
            </button>
            <button type="button" class="close-tab" :aria-label="'关闭 ' + label(tab)" :disabled="!!tab.busy" @click="closeTab(tab)"><Close /></button>
          </div>
          <button type="button" title="打开临时脚本" aria-label="打开临时脚本" @click="addTab(undefined, true)"><Plus /></button>
        </div>

        <el-alert v-if="assetError" :title="assetError" type="error" :closable="false" />
        <div v-if="!active" class="ide-empty">{{ assetLoading ? '正在加载作业…' : '从左侧打开作业，或新建临时脚本。' }}</div>
        <template v-for="tab in tabs" :key="tab.key">
          <section v-show="tab.key === activeKey" class="ide-pane">
            <div class="editor-toolbar">
              <el-select v-if="tab.scratch" v-model="tab.language" class="language-select" aria-label="脚本语言" size="small">
                <el-option v-for="language in languages" :key="language" :label="language" :value="language" />
              </el-select>
              <span v-else class="editor-mode">SQL · {{ tab.draft.mode === 'BATCH' ? '批处理' : '流式' }}</span>
              <DkButton v-if="!tab.scratch" :disabled="!!tab.busy || !tab.draft.sql.trim()"
                :loading="tab.busy === 'validate'" @click="debug(tab, false)">
                <template #icon><CircleCheck /></template>调试校验
              </DkButton>
              <DkButton v-if="tab.language === 'SQL'" :disabled="!!tab.busy || !canRun || !tab.selection.trim()"
                :loading="tab.busy === 'preview'" title="选中一个有界 SELECT，可一并选中它依赖的临时表或视图。"
                @click="debug(tab, true)"><template #icon><Right /></template>执行选中</DkButton>
              <template v-if="!tab.scratch">
                <DkButton :disabled="!!tab.busy || !tab.version || !canRun || dirty(tab)" :loading="tab.busy === 'execute'"
                  title="需要运行权限和已保存的作业版本。" @click="execute(tab)">执行全部</DkButton>
                <div class="toolbar-spacer"></div>
                <DkButton variant="quiet" :aria-expanded="tab.settings" @click="toggleSettings(tab)">
                  <template #icon><Setting /></template>设置
                </DkButton>
                <DkButton variant="primary" :disabled="!!tab.busy" :loading="tab.busy === 'save'" @click="save(tab)">保存版本</DkButton>
              </template>
            </div>

            <p v-if="tab.error" class="ide-error" role="alert">{{ tab.error }}</p>
            <div class="ide-edit" :class="{ 'with-settings': tab.settings }">
              <JobCodeEditor v-model="tab.draft.sql" :language="tab.language" :label="label(tab) + ' 代码编辑器'"
                :readonly="!!tab.busy && ['save', 'restore', 'execute'].includes(tab.busy)" @selection="tab.selection = $event" />
              <aside v-if="tab.settings" class="job-settings" aria-label="作业设置">
                <el-form label-position="top" :disabled="['save', 'restore', 'execute'].includes(tab.busy)">
                  <el-form-item label="作业名称"><el-input v-model="tab.draft.name" aria-label="作业名称" maxlength="200" /></el-form-item>
                  <el-form-item label="作业编码"><el-input v-model="tab.draft.code" aria-label="作业编码" maxlength="64" :disabled="!!tab.assetId" /></el-form-item>
                  <el-form-item label="版本说明"><el-input v-model="tab.draft.note" aria-label="版本说明" type="textarea" :rows="2" maxlength="500" /></el-form-item>
                  <el-form-item label="运行模式">
                    <el-select v-model="tab.draft.mode" aria-label="运行模式" :disabled="tab.modeLocked">
                      <el-option value="STREAMING" label="流式" /><el-option value="BATCH" label="批处理" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="并行度"><el-input-number v-model="tab.draft.parallelism" aria-label="并行度" :min="1" :max="1024" /></el-form-item>
                  <el-form-item label="函数依赖">
                    <div v-for="(dep, index) in tab.draft.functions" :key="index" class="job-dependency">
                      <el-select v-model="dep.assetId" aria-label="依赖函数" placeholder="搜索函数" filterable remote
                        :remote-method="loadFunctions" @visible-change="$event && !functionOptions.length && loadFunctions()" @change="changeFunction(dep)">
                        <el-option v-for="row in functionOptions" :key="row.id" :value="row.id" :label="`${row.name} · v${row.currentVersion}`" />
                      </el-select>
                      <el-input-number v-model="dep.version" aria-label="依赖版本" :min="1" />
                      <DkButton variant="quiet" @click="tab.draft.functions.splice(index, 1)">移除</DkButton>
                    </div>
                    <DkButton :disabled="tab.draft.functions.length >= 20" @click="tab.draft.functions.push({ assetId: '', version: 1 })">添加函数</DkButton>
                  </el-form-item>
                </el-form>
              </aside>
            </div>

            <div class="bottom-toolbar" role="group" aria-label="结果与历史">
              <button type="button" :aria-pressed="tab.bottom === 'output'" @click="tab.bottom === 'output' ? tab.bottom = undefined : showBottom(tab, 'output')">调试输出</button>
              <button v-if="!tab.scratch && tab.assetId" type="button" :aria-pressed="tab.bottom === 'runs'"
                @click="tab.bottom === 'runs' ? tab.bottom = undefined : showBottom(tab, 'runs')">运行记录</button>
              <button v-if="!tab.scratch && tab.assetId" type="button" :aria-pressed="tab.bottom === 'history'"
                @click="tab.bottom === 'history' ? tab.bottom = undefined : showBottom(tab, 'history')"><Clock />历史版本</button>
              <span v-if="tab.message && !tab.bottom" class="action-message">{{ tab.message }}</span>
            </div>

            <div v-if="tab.bottom === 'output'" class="ide-output">
              <p v-if="tab.message" class="output-message" role="status">{{ tab.message }}</p>
              <p v-else-if="tab.busy" class="output-message" role="status">执行中…</p>
              <p v-else-if="!tab.error" class="output-message">校验 SQL，或选中有界 SELECT 查看样本结果。</p>
              <el-table v-if="tab.result" :data="tab.result.rows" size="small" stripe empty-text="查询完成，返回 0 行">
                <el-table-column v-for="(column, index) in tab.result.columns" :key="index" :label="column" min-width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ field(row[index]) }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div v-if="tab.bottom === 'runs' && tab.assetId && tab.version && tab.key === activeKey && tab.busy !== 'execute'" class="ide-output">
              <FlinkRunsPanel :key="`${tab.assetId}:${tab.version}`" :project="project" :asset-id="tab.assetId"
                :version="tab.version" :can-run="canRun" :streaming="tab.draft.mode === 'STREAMING'" hide-launch-actions />
            </div>
            <div v-if="tab.bottom === 'history'" class="ide-history">
              <aside class="history-list" aria-label="历史版本列表">
                <p v-if="!tab.versions.length && !tab.busy" class="output-message">暂无历史版本</p>
                <button v-for="revision in tab.versions" :key="revision.version" type="button" :disabled="!!tab.busy"
                  :title="revision.note" :aria-pressed="tab.compared?.revision.version === revision.version" @click="compare(tab, revision.version)">
                  <strong>v{{ revision.version }}</strong><span>{{ revision.note }}</span>
                  <small>{{ revision.createTime?.replace('T', ' ').slice(0, 19) }}</small>
                </button>
                <el-pagination v-if="tab.historyTotal > pageSize" v-model:current-page="tab.historyPage" :page-size="pageSize"
                  layout="prev, pager, next" :pager-count="5" :disabled="!!tab.busy" :total="tab.historyTotal" small @current-change="changeHistoryPage(tab)" />
              </aside>
              <div class="diff-panel">
                <template v-if="tab.compared">
                  <div class="diff-toolbar">
                    <span>{{ tab.compared.revision.name }} · {{ tab.compared.revision.note }}</span>
                    <DkButton variant="quiet" :disabled="!!tab.busy" @click="download(tab)">下载 SQL</DkButton>
                    <DkButton :disabled="!!tab.busy || tab.compared.revision.version === tab.compared.currentVersion" @click="revert(tab)">回滚为新版本</DkButton>
                  </div>
                  <JobSqlDiff v-if="tab.key === activeKey" :version="tab.compared.revision.version" :original="tab.compared.definition.sql ?? ''" :draft="tab.draft.sql" />
                </template>
                <p v-else class="output-message">{{ tab.busy ? '正在加载版本…' : '选择历史版本，和当前工作稿比较。' }}</p>
              </div>
            </div>
          </section>
        </template>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
:global(.job-ide-dialog.el-dialog) { display: flex; flex-direction: column; padding: 0; height: 100dvh; overflow: hidden; font-family: var(--dk-font); }
:global(.job-ide-dialog > .el-dialog__header) { margin: 0; padding: 0; }
:global(.job-ide-dialog > .el-dialog__body) { display: flex; flex: 1; min-height: 0; padding: 0; color: var(--dk-ink); }
.ide-header { display: flex; min-height: 54px; align-items: center; gap: 14px; padding: 0 12px; border-bottom: 1px solid var(--dk-border); }
.ide-header h2 { font-size: 17px; margin: 0; color: var(--dk-ink); }
.version-badge { border-radius: 5px; background: var(--dk-primary-soft); color: var(--dk-primary); padding: 3px 7px; font-size: 12px; }
.ide { flex: 1; display: grid; grid-template-columns: 215px minmax(0, 1fr); min-width: 0; min-height: 0; }
.ide-assets { overflow: auto; padding: 8px; background: var(--dk-sidebar); border-right: 1px solid var(--dk-border); }
.asset-heading, .asset-link { display: flex; width: 100%; align-items: center; gap: 8px; padding: 7px 8px; min-height: 33px; }
.asset-heading { justify-content: space-between; font-size: 13px; }
.asset-heading button, .asset-link, .ide-tabs button, .bottom-toolbar button, .history-list > button { font-family: inherit; }
.asset-heading button, .asset-link, .ide-tabs button, .bottom-toolbar button, .history-list > button { background: none; border: 0; color: var(--dk-ink); cursor: pointer; }
.asset-link { text-align: left; font-size: 13px; border-radius: 6px; }
.asset-link span, .ide-tab span, .diff-toolbar > span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.asset-link:hover, .asset-link.selected, .history-list > button[aria-pressed='true'] { background: var(--dk-primary-soft); color: var(--dk-primary); }
.asset-filter { margin: 4px 0; }
.scratch-link { color: var(--dk-primary); }
.ide svg { width: 15px; height: 15px; flex: none; }
.ide-content, .ide-pane { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.ide-pane { flex: 1; }
.ide-tabs { display: flex; min-height: 38px; overflow: auto; flex: none; background: var(--dk-sidebar); border-bottom: 1px solid var(--dk-border); }
.ide-tabs button { display: flex; align-items: center; gap: 6px; padding: 0 8px; min-height: 36px; }
.ide-tab { display: flex; max-width: 230px; min-width: 115px; flex: 0 0 auto; border-right: 1px solid var(--dk-border); border-top: 2px solid transparent; }
.ide-tab.is-active { border-top-color: var(--dk-primary); background: var(--dk-surface); }
.ide-tab > button:first-child { min-width: 0; flex: 1; }
.ide-tabs .close-tab:disabled { opacity: .45; cursor: not-allowed; }
.unsaved { width: 6px; height: 6px; border-radius: 50%; background: var(--dk-primary); flex: none; }
.ide-empty { margin: auto; color: var(--dk-muted); }
.editor-toolbar { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; padding: 6px 10px; border-bottom: 1px solid var(--dk-border); }
.editor-toolbar .dk-button { min-height: 30px; font-size: 13px; }
.editor-mode { font-size: 12px; color: var(--dk-muted); padding-right: 5px; }
.language-select { width: 124px; }
.toolbar-spacer { flex: 1; }
.ide-edit { display: grid; grid-template-columns: minmax(0, 1fr); flex: 1; min-width: 0; min-height: 130px; }
.ide-edit.with-settings { grid-template-columns: minmax(0, 1fr) minmax(170px, 285px); }
.job-settings { padding: 12px; overflow: auto; border-left: 1px solid var(--dk-border); background: var(--dk-sidebar); }
.job-settings :deep(.el-form-item) { margin-bottom: 12px; }
.job-dependency { display: grid; width: 100%; gap: 6px; padding-bottom: 8px; }
.ide-error { margin: 0; padding: 8px 12px; background: var(--dk-danger-soft); color: var(--dk-danger); overflow-wrap: anywhere; }
.bottom-toolbar { display: flex; align-items: center; min-height: 34px; flex: none; border-top: 1px solid var(--dk-border); background: var(--dk-sidebar); }
.bottom-toolbar button { display: inline-flex; align-items: center; gap: 5px; padding: 7px 10px; border-bottom: 2px solid transparent; font-size: 12px; white-space: nowrap; }
.bottom-toolbar button[aria-pressed='true'] { border-bottom-color: var(--dk-primary); color: var(--dk-primary); }
.action-message, .output-message { margin: 0; padding: 8px 12px; color: var(--dk-muted); font-size: 12px; }
.ide-output, .ide-history { height: min(43dvh, 420px); min-height: 120px; overflow: auto; border-top: 1px solid var(--dk-border); }
.ide-history { display: grid; grid-template-columns: minmax(110px, 210px) minmax(0, 1fr); overflow: hidden; }
.history-list { border-right: 1px solid var(--dk-border); overflow: auto; }
.history-list > button { padding: 7px 10px; display: grid; gap: 4px; grid-template-columns: auto minmax(0, 1fr); width: 100%; text-align: left; border-bottom: 1px solid var(--dk-border); }
.history-list button span { text-overflow: ellipsis; overflow: hidden; white-space: nowrap; }
.history-list button small { grid-column: 1 / -1; color: var(--dk-muted); }
.diff-panel { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; }
.diff-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 4px 10px; border-bottom: 1px solid var(--dk-border); font-size: 12px; }
@media (max-width: 760px) {
  .ide { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
  .ide-assets { display: flex; align-items: center; overflow: auto; padding: 2px; border-right: 0; border-bottom: 1px solid var(--dk-border); }
  .asset-heading, .asset-link { width: auto; flex: 0 0 auto; }
  .asset-link span { max-width: 120px; }
  .asset-filter { flex: 0 0 130px; margin: 0 4px; }
  .editor-toolbar { gap: 4px; }
  .ide-edit.with-settings { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) minmax(0, 45%); }
  .job-settings { border: 0; border-top: 1px solid var(--dk-border); }
  .ide-history { grid-template-columns: minmax(85px, 108px) minmax(0,1fr); }
}
</style>
