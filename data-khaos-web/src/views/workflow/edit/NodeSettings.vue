<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Delete, Plus, VideoPlay } from '@element-plus/icons-vue'
import { getWorkflowSqlFile } from '@/api/workflow'
import type { WorkflowDef, WorkflowEdge, WorkflowNode, WorkflowSqlFile } from '@/types'
import { nodeMeta, parseConfig, writeConfig, RUN_STATUS_META } from './nodeCatalog'

const props = defineProps<{
  wfId?: string
  node: WorkflowNode
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  datasources: Array<{ id: string; dsName: string; dsType?: string }>
  sqlFiles: WorkflowSqlFile[]
  workflows: WorkflowDef[]
  pipelines: Array<{ id: string; name?: string }>
  qualityTasks: Array<{ id: string; name?: string }>
  lastStatus?: string
  lastStartTime?: string
  lastDurationMs?: number
  lastErrorMessage?: string
}>()

const emit = defineEmits<{
  (e: 'delete'): void
  (e: 'close'): void
  (e: 'choose-file'): void
  (e: 'save-as-file', content: string): void
  (e: 'debug'): void
  (e: 'run-downstream'): void
  (e: 'changed'): void
  (e: 'add-upstream', code: string): void
  (e: 'remove-upstream', code: string): void
  (e: 'open-workflow-deps'): void
}>()

const tab = ref<'basic' | 'task' | 'params' | 'deps'>('basic')
const meta = computed(() => nodeMeta(props.node.nodeType))
const statusMeta = computed(() => props.lastStatus ? RUN_STATUS_META[props.lastStatus] : undefined)

function cfg<T>(key: string, fallback: T): T {
  const value = parseConfig(props.node)[key]
  return (value === undefined || value === null ? fallback : value) as T
}
function setCfg(key: string, value: any) {
  const config = parseConfig(props.node)
  config[key] = value
  writeConfig(props.node, config)
  emit('changed')
}

const taskMode = computed({
  get: () => cfg('taskMode', 'CUSTOM'),
  set: (value: string) => setCfg('taskMode', value),
})
const datasourceId = computed({
  get: () => cfg('datasourceId', ''),
  set: (value: string) => setCfg('datasourceId', value),
})
const sqlText = computed({
  get: () => cfg('sql', ''),
  set: (value: string) => setCfg('sql', value),
})
const sqlSplitMode = computed({
  get: () => cfg('sqlSplitMode', 'NONE'),
  set: (value: string) => setCfg('sqlSplitMode', value),
})
const externalObject = computed({
  get: () => cfg('externalObject', 'DRAFT'),
  set: (value: string) => setCfg('externalObject', value),
})
const sqlFileId = computed({
  get: () => cfg('sqlFileId', ''),
  set: (value: string) => setCfg('sqlFileId', value),
})
const scriptText = computed({
  get: () => cfg('script', ''),
  set: (value: string) => setCfg('script', value),
})
const pipelineTaskId = computed({
  get: () => cfg('pipelineTaskId', ''),
  set: (value: string) => setCfg('pipelineTaskId', value),
})
const qualityTaskId = computed({
  get: () => cfg('qualityTaskId', ''),
  set: (value: string) => setCfg('qualityTaskId', value),
})
const gate = computed({
  get: () => cfg('gate', 'BLOCK'),
  set: (value: string) => setCfg('gate', value),
})
const nestedWorkflowId = computed({
  get: () => cfg('workflowId', ''),
  set: (value: string) => setCfg('workflowId', value),
})
const delaySeconds = computed({
  get: () => cfg('delaySeconds', 60),
  set: (value: number) => setCfg('delaySeconds', value),
})
const dsRemoteProjectCode = computed({
  get: () => String(cfg('remoteProjectCode', '')),
  set: (value: string) => setCfg('remoteProjectCode', value.trim()),
})
const dsRemoteDefinitionCode = computed({
  get: () => String(cfg('remoteDefinitionCode', '')),
  set: (value: string) => setCfg('remoteDefinitionCode', value.trim()),
})
const dsRemoteDefinitionVersion = computed({
  get: () => String(cfg('remoteDefinitionVersion', '')),
  set: (value: string) => setCfg('remoteDefinitionVersion', value.trim() === '' ? '' : Number(value)),
})

/* 文件列表不携带正文，选中后按 id 拉取详情，避免资源树接口过大 */
const fileDetail = ref<WorkflowSqlFile>()
const fileDetailLoading = ref(false)
watch(() => sqlFileId.value, async (id) => {
  fileDetail.value = undefined
  fileDetailLoading.value = false
  if (!id) return
  fileDetailLoading.value = true
  try {
    const detail = await getWorkflowSqlFile(id)
    if (sqlFileId.value === id) fileDetail.value = detail
  } catch {
    // 文件可能已被删除，下方会展示未选择/为空的状态
  } finally {
    if (sqlFileId.value === id) fileDetailLoading.value = false
  }
}, { immediate: true })

const selectedFile = computed(() => {
  if (fileDetail.value && fileDetail.value.id === sqlFileId.value) return fileDetail.value
  return props.sqlFiles.find(file => file.id === sqlFileId.value)
})
const filePreview = computed(() => {
  const file = selectedFile.value
  if (!file) return ''
  const content = externalObject.value === 'LATEST' && (file.publishedVersion ?? 0) > 0
    ? file.publishedContent
    : file.draftContent
  return (content || '').slice(0, 300)
})

/* 超时/重试在界面上用分钟，存储仍保持秒，避免改变既有调度语义 */
const timeoutEnabled = computed({
  get: () => (props.node.timeout ?? 0) > 0,
  set: (value: boolean) => { props.node.timeout = value ? 600 : 0; emit('changed') },
})
const timeoutMinutes = computed({
  get: () => Math.max(1, Math.round((props.node.timeout ?? 0) / 60)),
  set: (value: number) => { props.node.timeout = Math.max(1, value) * 60; emit('changed') },
})
const retryEnabled = computed({
  get: () => (props.node.retryCount ?? 0) > 0,
  set: (value: boolean) => {
    props.node.retryCount = value ? 3 : 0
    props.node.retryInterval = value ? (props.node.retryInterval || 300) : 0
    emit('changed')
  },
})
const retryMinutes = computed({
  get: () => Math.max(1, Math.round((props.node.retryInterval ?? 0) / 60)),
  set: (value: number) => { props.node.retryInterval = Math.max(1, value) * 60; emit('changed') },
})
const skipped = computed({
  get: () => (props.node.skipFlag ?? 0) === 1,
  set: (value: boolean) => { props.node.skipFlag = value ? 1 : 0; emit('changed') },
})
const independent = computed({
  get: () => (props.node.independentSchedule ?? 0) === 1,
  set: (value: boolean) => { props.node.independentSchedule = value ? 1 : 0; emit('changed') },
})

/* 参数行：只在切换节点时从 JSON 载入，编辑过程不回灌，避免光标跳动 */
const paramRows = ref<Array<{ key: string; value: string }>>([])
watch(() => props.node.nodeCode, () => {
  paramRows.value = []
  try {
    const object = props.node.nodeParams ? JSON.parse(props.node.nodeParams) : {}
    paramRows.value = Object.keys(object).map(key => ({ key, value: String(object[key] ?? '') }))
  } catch {
    paramRows.value = []
  }
}, { immediate: true })

function writeParams() {
  const object: Record<string, string> = {}
  for (const row of paramRows.value) {
    if (row.key.trim()) object[row.key.trim()] = row.value
  }
  props.node.nodeParams = Object.keys(object).length ? JSON.stringify(object) : ''
  emit('changed')
}
function addParam() {
  paramRows.value.push({ key: '', value: '' })
}
function removeParam(index: number) {
  paramRows.value.splice(index, 1)
  writeParams()
}

const upstreamNodes = computed(() => props.edges
  .filter(edge => edge.toCode === props.node.nodeCode)
  .map(edge => props.nodes.find(node => node.nodeCode === edge.fromCode))
  .filter((node): node is WorkflowNode => !!node))
const downstreamNodes = computed(() => props.edges
  .filter(edge => edge.fromCode === props.node.nodeCode)
  .map(edge => props.nodes.find(node => node.nodeCode === edge.toCode))
  .filter((node): node is WorkflowNode => !!node))
const upstreamCandidate = ref('')
const candidates = computed(() => props.nodes.filter(node =>
  node.nodeCode !== props.node.nodeCode
  && !upstreamNodes.value.some(up => up.nodeCode === node.nodeCode)
  && !downstreamNodes.value.some(down => down.nodeCode === node.nodeCode)))

function addUpstream() {
  if (!upstreamCandidate.value) return
  emit('add-upstream', upstreamCandidate.value)
  upstreamCandidate.value = ''
}

async function copyUuid() {
  const text = props.node.id || props.node.nodeCode
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制节点 UUID')
  } catch {
    ElMessage.warning(text)
  }
}

function saveAsFile() {
  if (!sqlText.value.trim()) {
    ElMessage.warning('先填写 SQL 再保存为文件')
    return
  }
  emit('save-as-file', sqlText.value)
}
</script>

<template>
  <section class="settings" aria-label="节点设置">
    <header class="settings-head">
      <span class="type-chip" :style="{ color: meta.color, background: meta.soft }">
        <el-icon><component :is="meta.icon" /></el-icon>
        {{ meta.label }}
      </span>
      <button class="icon-btn" title="关闭设置" @click="emit('close')">×</button>
    </header>

    <el-input v-model="node.nodeName" class="name-input" maxlength="200" placeholder="节点名称" @input="emit('changed')" />
    <div class="head-actions">
      <el-button size="small" :icon="VideoPlay" @click="emit('debug')">调试</el-button>
      <el-button size="small" @click="emit('run-downstream')">运行及下游</el-button>
      <el-button size="small" type="danger" plain :icon="Delete" @click="emit('delete')">删除</el-button>
    </div>

    <div v-if="lastStatus" class="last-run">
      <span class="run-dot" :style="{ background: statusMeta?.color || '#98a2b3' }" />
      <span>上次运行：{{ statusMeta?.text || lastStatus }}</span>
      <span v-if="lastDurationMs != null" class="muted">{{ Math.round(lastDurationMs / 1000) }}s</span>
      <span v-if="lastStartTime" class="muted">{{ lastStartTime.replace('T', ' ').slice(5, 19) }}</span>
      <div v-if="lastErrorMessage" class="run-error">{{ lastErrorMessage }}</div>
    </div>

    <el-tabs v-model="tab" class="settings-tabs">
      <el-tab-pane label="基本信息" name="basic">
        <el-form label-position="top" size="small" class="form">
          <el-form-item label="UUID">
            <div class="uuid-row">
              <el-input :model-value="node.id || node.nodeCode" readonly />
              <el-button :icon="CopyDocument" @click="copyUuid" />
            </div>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="node.description" type="textarea" :rows="2" maxlength="500" placeholder="节点用途、口径说明" @input="emit('changed')" />
          </el-form-item>

          <el-form-item label="超时设置">
            <div class="inline">
              <el-switch v-model="timeoutEnabled" active-text="超时失败" />
              <template v-if="timeoutEnabled">
                <el-input-number v-model="timeoutMinutes" :min="1" :max="1440" controls-position="right" />
                <span class="unit">分钟</span>
              </template>
            </div>
            <div class="hint">数据同步、子任务流、质量节点按此超时中断等待；SQL 节点受数据源请求超时约束。</div>
          </el-form-item>

          <el-form-item label="错误重试">
            <div class="inline">
              <el-switch v-model="retryEnabled" active-text="开启" />
              <template v-if="retryEnabled">
                <span class="unit">次数</span>
                <el-input-number v-model="node.retryCount" :min="1" :max="10" controls-position="right" @change="emit('changed')" />
                <span class="unit">间隔</span>
                <el-input-number v-model="retryMinutes" :min="1" :max="60" controls-position="right" />
                <span class="unit">分钟</span>
              </template>
            </div>
          </el-form-item>

          <el-form-item label="是否跳过">
            <el-switch v-model="skipped" active-text="跳过执行" />
            <div class="hint">跳过仍会释放下游依赖，适合临时停用某个环节。</div>
          </el-form-item>

          <el-form-item label="执行器组">
            <el-select v-model="node.executorGroup" filterable allow-create default-first-option style="width: 100%"
              placeholder="default group" @change="emit('changed')">
              <el-option label="default group" value="default group" />
              <el-option label="high-memory" value="high-memory" />
              <el-option label="io-heavy" value="io-heavy" />
            </el-select>
            <div class="hint">用于节点分组标识；当前平台按默认执行器组运行。</div>
          </el-form-item>

          <el-form-item label="独立调度">
            <el-switch v-model="independent" active-text="允许单独调试" />
            <div class="hint">标记该节点允许独立调度，供调度器识别；工作台调试不受此开关限制。</div>
          </el-form-item>

          <el-form-item label="当前任务执行前置条件">
            <el-input v-model="node.precondition" type="textarea" :rows="3" maxlength="1000"
              placeholder="if [ ${bizDate} >= '2026-01-01' ] then exit 0; else exit 1;" @input="emit('changed')" />
            <div class="hint">支持 ${参数}、== != &gt; &gt;= &lt; &lt;= 与 &amp;&amp; ||；不满足时节点跳过并释放下游。</div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane :label="meta.contentKind === 'sql' ? 'SQL任务' : '任务配置'" name="task">
        <el-form label-position="top" size="small" class="form">
          <template v-if="meta.contentKind === 'sql'">
            <el-form-item label="任务模式">
              <el-radio-group v-model="taskMode">
                <el-radio value="CUSTOM">自定义任务</el-radio>
                <el-radio value="EXTERNAL">外部任务</el-radio>
              </el-radio-group>
            </el-form-item>

            <el-form-item label="数据源">
              <el-select v-model="datasourceId" filterable clearable placeholder="选择数据源" style="width: 100%">
                <el-option v-for="ds in datasources" :key="ds.id"
                  :label="ds.dsType ? `${ds.dsName} (${ds.dsType})` : ds.dsName" :value="ds.id" />
              </el-select>
              <div v-if="taskMode === 'EXTERNAL'" class="hint">留空时继承 SQL 文件的数据源。</div>
            </el-form-item>

            <template v-if="taskMode === 'CUSTOM'">
              <el-form-item label="SQL">
                <el-input v-model="sqlText" type="textarea" :rows="14" class="code-area"
                  placeholder="输入 SQL，支持 ${参数} 替换" />
              </el-form-item>
              <div class="toolbar-row">
                <el-button size="small" @click="saveAsFile">保存为 SQL 文件</el-button>
                <span class="hint">保存后可在其他任务里通过「外部任务」复用。</span>
              </div>
            </template>

            <template v-else>
              <el-form-item label="外部任务执行对象">
                <el-radio-group v-model="externalObject">
                  <el-radio value="DRAFT">草稿内容</el-radio>
                  <el-radio value="LATEST">最新版本</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="SQL 文件">
                <div class="uuid-row">
                  <el-input :model-value="selectedFile ? `${selectedFile.folder}/${selectedFile.name}` : ''"
                    readonly placeholder="请选择已保存的 SQL 文件" />
                  <el-button type="primary" plain @click="emit('choose-file')">选择</el-button>
                </div>
              </el-form-item>
              <div v-if="selectedFile" class="file-card">
                <div class="file-head">
                  <strong>{{ selectedFile.name }}</strong>
                  <el-tag size="small" type="info">草稿 v{{ selectedFile.draftVersion ?? 1 }}</el-tag>
                  <el-tag v-if="(selectedFile.publishedVersion ?? 0) > 0" size="small" type="success">
                    已发布 v{{ selectedFile.publishedVersion }}
                  </el-tag>
                </div>
                <p class="file-desc">{{ selectedFile.description || '暂无说明' }}</p>
                <pre class="file-preview">{{ fileDetailLoading ? '正在读取文件内容…' : (filePreview || '（当前执行对象内容为空）') }}</pre>
                <div class="hint">
                  本次将读取{{ externalObject === 'LATEST' && (selectedFile.publishedVersion ?? 0) > 0 ? '已发布快照' : '草稿内容' }}。
                </div>
              </div>
              <el-alert v-else type="warning" :closable="false" show-icon
                title="未选择 SQL 文件，任务运行时将直接报错。" />
            </template>

            <el-form-item label="SQL 执行模式">
              <el-radio-group v-model="sqlSplitMode">
                <el-radio value="NONE">不拆分执行</el-radio>
                <el-radio value="SPLIT">拆分执行</el-radio>
              </el-radio-group>
              <div class="hint">拆分执行按分号切成多条语句顺序提交，任一失败立即中止。</div>
            </el-form-item>
          </template>

          <template v-else-if="meta.contentKind === 'script'">
            <el-form-item :label="node.nodeType === 'PYTHON' ? 'Python 脚本' : 'Shell 脚本'">
              <el-input v-model="scriptText" type="textarea" :rows="16" class="code-area"
                placeholder="输入脚本内容，支持 ${参数} 替换" />
            </el-form-item>
          </template>

          <template v-else-if="meta.contentKind === 'pipeline'">
            <el-form-item label="数据同步任务">
              <el-select v-model="pipelineTaskId" filterable clearable placeholder="选择同步任务" style="width: 100%">
                <el-option v-for="task in pipelines" :key="task.id"
                  :label="task.name ? `${task.name} (${task.id})` : task.id" :value="task.id" />
              </el-select>
              <div class="hint">节点会触发该同步任务并等待实例到达终态，超时按节点超时设置。</div>
            </el-form-item>
          </template>

          <template v-else-if="meta.contentKind === 'quality'">
            <el-form-item label="质量稽核任务">
              <el-select v-model="qualityTaskId" filterable clearable placeholder="选择质量任务" style="width: 100%">
                <el-option v-for="task in qualityTasks" :key="task.id"
                  :label="task.name ? `${task.name} (${task.id})` : task.id" :value="task.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="门禁动作">
              <el-radio-group v-model="gate">
                <el-radio value="BLOCK">阻断下游</el-radio>
                <el-radio value="WARN">仅告警</el-radio>
              </el-radio-group>
            </el-form-item>
          </template>

          <template v-else-if="meta.contentKind === 'dolphinscheduler'">
            <el-form-item label="DolphinScheduler 项目编码">
              <el-input v-model="dsRemoteProjectCode" placeholder="远端项目编码" />
            </el-form-item>
            <el-form-item label="工作流定义编码">
              <el-input v-model="dsRemoteDefinitionCode" placeholder="远端已上线的工作流定义编码" />
            </el-form-item>
            <el-form-item label="定义版本">
              <el-input v-model="dsRemoteDefinitionVersion" placeholder="留空表示接受远端当前上线版本" />
              <div class="hint">
                按业务日期提交远端 DAG。填写版本后，远端上线版本不一致时拒绝提交；发布后的草稿修改不影响已受理运行。
              </div>
            </el-form-item>
          </template>
          <template v-else-if="meta.contentKind === 'delay'">
            <el-form-item label="延时秒数">
              <el-input-number v-model="delaySeconds" :min="0" :max="86400" controls-position="right" />
              <div class="hint">延时上限 24 小时；等待过程可被任务取消中断。</div>
            </el-form-item>
            <div class="toolbar-row">
              <el-button v-for="value in [30, 60, 300, 900]" :key="value" size="small" @click="delaySeconds = value">
                {{ value }} 秒
              </el-button>
            </div>
          </template>

          <template v-else-if="meta.contentKind === 'nested'">
            <el-form-item label="子任务流">
              <el-select v-model="nestedWorkflowId" filterable clearable placeholder="选择任务流" style="width: 100%">
                <el-option v-for="workflow in workflows.filter(item => item.id !== wfId)" :key="workflow.id"
                  :label="workflow.name" :value="workflow.id!" />
              </el-select>
              <div class="hint">继承当前运行参数与业务日期；嵌套层数最多 5 层。</div>
            </el-form-item>
          </template>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="任务参数" name="params">
        <p class="section-hint">节点参数覆盖工作流参数；业务日期由运行上下文决定，不可覆盖。</p>
        <div class="param-table">
          <div v-for="(row, index) in paramRows" :key="index" class="param-row">
            <el-input v-model="row.key" placeholder="参数名" @input="writeParams" />
            <el-input v-model="row.value" placeholder="参数值" @input="writeParams" />
            <el-button :icon="Delete" @click="removeParam(index)" />
          </div>
          <el-button :icon="Plus" size="small" @click="addParam">添加参数</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="依赖" name="deps">
        <div class="dep-section">
          <h4>上游节点</h4>
          <div v-if="upstreamNodes.length" class="dep-list">
            <div v-for="up in upstreamNodes" :key="up.nodeCode" class="dep-item">
              <span class="dep-dot" :style="{ background: nodeMeta(up.nodeType).color }" />
              <span class="dep-name">{{ up.nodeName }}</span>
              <el-button link type="danger" size="small" @click="emit('remove-upstream', up.nodeCode!)">移除</el-button>
            </div>
          </div>
          <p v-else class="section-hint">当前节点为起始节点。</p>
          <div class="dep-add">
            <el-select v-model="upstreamCandidate" filterable placeholder="选择上游节点" size="small">
              <el-option v-for="candidate in candidates" :key="candidate.nodeCode"
                :label="candidate.nodeName" :value="candidate.nodeCode" />
            </el-select>
            <el-button size="small" type="primary" plain @click="addUpstream">添加</el-button>
          </div>
        </div>

        <div class="dep-section">
          <h4>下游节点</h4>
          <div v-if="downstreamNodes.length" class="dep-list">
            <div v-for="down in downstreamNodes" :key="down.nodeCode" class="dep-item">
              <span class="dep-dot" :style="{ background: nodeMeta(down.nodeType).color }" />
              <span class="dep-name">{{ down.nodeName }}</span>
            </div>
          </div>
          <p v-else class="section-hint">没有下游节点。</p>
        </div>

        <div class="dep-section">
          <h4>跨任务流依赖</h4>
          <p class="section-hint">任务流级别的上游依赖与参数传递在「任务流配置」中维护。</p>
          <el-button size="small" @click="emit('open-workflow-deps')">打开任务流依赖</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.settings { display: flex; flex-direction: column; height: 100%; min-height: 0; background: #fff; }
.settings-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px 6px; }
.type-chip { display: inline-flex; align-items: center; gap: 6px; padding: 3px 8px; border-radius: 6px;
  font-size: 12px; font-weight: 600; }
.type-chip .el-icon { font-size: 13px; }
.icon-btn { border: 0; background: transparent; font-size: 20px; line-height: 1; color: var(--dk-muted);
  cursor: pointer; padding: 0 4px; }
.icon-btn:hover { color: var(--dk-ink); }
.name-input { margin: 4px 14px 8px; width: calc(100% - 28px); }
.name-input :deep(.el-input__inner) { font-weight: 600; }
.head-actions { display: flex; gap: 6px; padding: 0 14px 10px; flex-wrap: wrap; }
.last-run { margin: 0 14px 8px; padding: 8px 10px; border-radius: 8px; background: #f7f9fc;
  font-size: 12px; color: var(--dk-muted); display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.run-dot { width: 8px; height: 8px; border-radius: 50%; }
.run-error { flex-basis: 100%; color: #d92d20; word-break: break-all; }
.muted { color: #98a2b3; }
.settings-tabs { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 0 14px; }
.settings-tabs :deep(.el-tabs__header) { margin-bottom: 10px; }
.settings-tabs :deep(.el-tabs__content) { flex: 1; min-height: 0; overflow-y: auto; padding-bottom: 20px; }
.form :deep(.el-form-item) { margin-bottom: 13px; }
.form :deep(.el-form-item__label) { padding-bottom: 3px; font-size: 12px; color: #475467; }
.inline { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.inline :deep(.el-input-number) { width: 92px; }
.unit { font-size: 12px; color: #667085; }
.hint { font-size: 12px; color: #98a2b3; line-height: 1.5; margin-top: 4px; }
.uuid-row { display: flex; gap: 6px; width: 100%; }
.uuid-row :deep(.el-input) { flex: 1; }
.code-area :deep(textarea) { font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px; line-height: 1.6; }
.toolbar-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.file-card { border: 1px solid #e4e7ec; border-radius: 8px; padding: 10px; margin-bottom: 10px; background: #fcfcfd; }
.file-head { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; flex-wrap: wrap; }
.file-desc { margin: 0 0 6px; font-size: 12px; color: #667085; }
.file-preview { margin: 0 0 6px; padding: 8px; background: #f2f4f7; border-radius: 6px; font-size: 11.5px;
  color: #475467; max-height: 110px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.section-hint { margin: 0 0 10px; font-size: 12px; color: #98a2b3; line-height: 1.6; }
.param-table { display: grid; gap: 8px; }
.param-row { display: grid; grid-template-columns: 1fr 1fr 34px; gap: 6px; }
.dep-section { margin-bottom: 18px; }
.dep-section h4 { margin: 0 0 8px; font-size: 13px; color: #344054; }
.dep-list { display: grid; gap: 6px; margin-bottom: 8px; }
.dep-item { display: flex; align-items: center; gap: 7px; padding: 6px 8px; border: 1px solid #eaecf0;
  border-radius: 6px; background: #fcfcfd; font-size: 12.5px; }
.dep-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.dep-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dep-add { display: flex; gap: 6px; }
.dep-add :deep(.el-select) { flex: 1; }
</style>
