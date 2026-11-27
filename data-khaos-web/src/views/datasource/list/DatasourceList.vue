<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ArrowDown, ArrowLeft, ArrowRight, Collection, Connection, CopyDocument, EditPen, MoreFilled,
  Plus, Refresh, Search } from '@element-plus/icons-vue'
import DkButton from '@/paas-ui/DkButton.vue'
import DkPageHeader from '@/paas-ui/DkPageHeader.vue'
import { createDatasource, deleteDatasource, getDatasource, pageDatasources, testDatasourceById,
  testDatasourceConfig, updateDatasource } from '@/api/datasource'
import { syncMetadata } from '@/api/metadata'
import type { MetaDatasource } from '@/types'

const engines = [
  { label: 'MySQL', value: 'MYSQL', port: 3306, mark: 'MY' },
  { label: '达梦 DM8', value: 'DM8', port: 5236, mark: 'DM' },
  { label: 'PostgreSQL', value: 'POSTGRESQL', port: 5432, mark: 'PG' },
  { label: 'Hive', value: 'HIVE', port: 10000, mark: 'HV' },
  { label: 'Doris', value: 'DORIS', port: 9030, mark: 'DO' },
  { label: 'ClickHouse', value: 'CLICKHOUSE', port: 8123, mark: 'CH' },
  { label: '星环 Transwarp', value: 'TRANSWARP', port: 10000, mark: 'TW' },
  { label: 'Oracle', value: 'ORACLE', port: 1521, mark: 'OR' },
]
function engine(type?: string) {
  return engines.find(item => item.value === type) ?? { label: type || '未知类型', mark: 'DB' }
}

const route = useRoute()
const router = useRouter()
const sourceId = computed(() => typeof route.query.source === 'string' ? route.query.source : '')
const expanded = ref(true)
const rows = ref<MetaDatasource[]>([])
const total = ref(0)
const loading = ref(false)
const listError = ref('')
const keyword = ref('')
const query = reactive({ current: 1, size: 10, keyword: '', dsType: '' })
const detail = ref<MetaDatasource>()
const detailError = ref('')
const detailLoading = ref(false)
const pending = reactive(new Set<string>())
const probes = reactive<Record<string, { ok: boolean; checkedAt: string }>>({})
let pageSequence = 0
let detailSequence = 0

const drawer = ref(false)
const formRef = ref<FormInstance>()
const original = ref<MetaDatasource>()
const form = ref<MetaDatasource>({})
const submitting = ref(false)
const testing = ref(false)
const formError = ref('')
const testResult = ref<boolean>()
const rules: FormRules = {
  dsName: [{ required: true, whitespace: true, message: '请输入数据源名称', trigger: 'blur' }],
  dsType: [{ required: true, message: '请选择引擎类型', trigger: 'change' }],
  host: [{ required: true, whitespace: true, message: '请输入主机地址', trigger: 'blur' }],
  username: [{ required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' }],
}
const connectionFields = ['dsType', 'host', 'port', 'databaseName', 'username', 'properties'] as const
const savedConnection = computed(() => !!original.value && !form.value.password
  && connectionFields.every(field => (form.value[field] ?? '') === (original.value?.[field] ?? '')))
const hiveAuth = computed({
  get: () => extra(form.value.properties).hiveAuth === 'NOSASL' ? 'NOSASL' : 'NONE',
  set: value => { form.value.properties = JSON.stringify({ ...extra(form.value.properties), hiveAuth: value }) },
})
watch(() => [...connectionFields.map(field => form.value[field]), form.value.password], () => {
  testResult.value = undefined
})

function extra(properties?: string): Record<string, unknown> {
  try {
    const value = JSON.parse(properties || '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch {
    return {}
  }
}
function formatTime(value?: string) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}
function probeText(id?: string) {
  const result = id ? probes[id] : undefined
  return result == null ? '未测试' : result.ok ? '可连接' : '连接失败'
}
function probeClass(id?: string) {
  const result = id ? probes[id] : undefined
  return result == null ? '' : result.ok ? 'is-good' : 'is-bad'
}
function message(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}
function go(id?: string) {
  const next = { ...route.query }
  if (id) next.source = id
  else delete next.source
  void router.push({ query: next })
}

async function loadPage() {
  const sequence = ++pageSequence
  loading.value = true
  listError.value = ''
  try {
    const result = await pageDatasources({ ...query, dsType: query.dsType || undefined })
    if (sequence !== pageSequence) return
    rows.value = result.records
    total.value = Number(result.total)
    const last = Math.max(1, Math.ceil(total.value / query.size))
    if (query.current > last) {
      query.current = last
      await loadPage()
    }
  } catch (e) {
    if (sequence === pageSequence) {
      rows.value = []
      total.value = 0
      listError.value = message(e, '无法加载数据源')
    }
  } finally {
    if (sequence === pageSequence) loading.value = false
  }
}

async function loadDetail() {
  const sequence = ++detailSequence
  const id = sourceId.value
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = !!id
  if (!id) return
  try {
    const result = await getDatasource(id)
    if (sequence === detailSequence) detail.value = result
  } catch (e) {
    if (sequence === detailSequence) detailError.value = message(e, '无法加载数据源详情')
  } finally {
    if (sequence === detailSequence) detailLoading.value = false
  }
}

function search() {
  query.current = 1
  query.keyword = keyword.value.trim()
  void loadPage()
}
async function refresh() {
  await Promise.all([loadPage(), loadDetail()])
}
watch(sourceId, loadDetail, { immediate: true })
onMounted(loadPage)

function openForm(row?: MetaDatasource) {
  original.value = row
  form.value = {
    id: row?.id, dsName: row?.dsName ?? '', dsType: row?.dsType ?? 'MYSQL', host: row?.host ?? '',
    port: row ? row.port : 3306, databaseName: row?.databaseName, username: row?.username ?? '',
    password: '', properties: row?.properties, status: row?.status ?? 1,
  }
  formError.value = ''
  testResult.value = undefined
  drawer.value = true
}

function changeEngine(value: string) {
  form.value.port = engines.find(item => item.value === value)?.port
}

function closeForm() {
  drawer.value = false
  form.value.password = ''
}

async function validateForm() {
  return await formRef.value?.validate().catch(() => false) ?? false
}

async function save() {
  if (submitting.value || testing.value || !await validateForm()) return
  submitting.value = true
  formError.value = ''
  const id = form.value.id
  const payload = { ...form.value, dsName: form.value.dsName?.trim(), host: form.value.host?.trim() }
  try {
    if (id) {
      await updateDatasource(payload)
      delete probes[id]
    } else {
      await createDatasource(payload)
      query.current = 1
    }
    closeForm()
    ElMessage.success('数据源已保存')
    await refresh()
  } catch (e) {
    formError.value = message(e, '保存失败')
  } finally {
    submitting.value = false
  }
}

async function runProbe(id: string) {
  const ok = await testDatasourceById(id)
  probes[id] = { ok, checkedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
  return ok
}

async function testForm() {
  if (submitting.value || testing.value || !await validateForm()) return
  testing.value = true
  testResult.value = undefined
  formError.value = ''
  try {
    const { dsType, host, port, databaseName, username, password, properties } = form.value
    testResult.value = savedConnection.value && form.value.id
      ? await runProbe(form.value.id)
      : await testDatasourceConfig({ dsType, host: host?.trim(), port, databaseName, username, password, properties })
  } catch (e) {
    formError.value = message(e, '无法完成连接测试')
  } finally {
    testing.value = false
  }
}

type SourceAction = 'test' | 'sync' | 'delete'

async function act(action: SourceAction, row: MetaDatasource) {
  const id = row.id
  if (!id || pending.has(id)) return
  pending.add(id)
  try {
    if (action === 'test') {
      const ok = await runProbe(id)
      if (ok) ElMessage.success('连接成功')
      else ElMessage.error('连接失败，请检查地址和认证配置')
    } else {
      const operation = action === 'sync' ? '同步元数据' : '删除数据源'
      const confirmed = await ElMessageBox.confirm(`确认${operation}「${row.dsName}」？`, operation,
        { type: action === 'delete' ? 'warning' : 'info' }).then(() => true, () => false)
      if (!confirmed) return
      if (action === 'delete') {
        await deleteDatasource(id)
        delete probes[id]
        if (sourceId.value === id) go()
        await loadPage()
      } else {
        await syncMetadata(id)
      }
      ElMessage.success(`${operation}完成`)
    }
  } catch {
    // The shared request handler displays server or transport errors.
  } finally {
    pending.delete(id)
  }
}

async function copyId(id?: string) {
  if (!id) return
  try {
    await navigator.clipboard.writeText(id)
    ElMessage.success('已复制数据源 ID')
  } catch {
    ElMessage.error('复制失败，可在详情中选中 ID 手动复制')
  }
}
</script>

<template>
  <section class="source-page">
    <DkPageHeader title="数据源管理" description="配置数据库连接、测试连通性并同步元数据。">
      <template #actions>
        <DkButton :loading="loading || detailLoading" @click="refresh"><template #icon><Refresh /></template>刷新</DkButton>
        <DkButton variant="primary" @click="openForm()"><template #icon><Plus /></template>新建数据源</DkButton>
      </template>
    </DkPageHeader>

    <div class="source-workspace" :class="{ 'is-collapsed': !expanded }">
      <aside class="dk-panel source-directory" aria-label="数据源目录">
        <button type="button" class="directory-title" :aria-label="expanded ? '收起数据源目录' : '展开数据源目录'" :aria-expanded="expanded" aria-controls="source-directory-items" @click="expanded = !expanded">
          <Collection aria-hidden="true" /><span>数据源目录</span><component :is="expanded ? ArrowDown : ArrowRight" aria-hidden="true" />
        </button>
        <div v-show="expanded" id="source-directory-items">
          <button type="button" class="directory-entry" :class="{ selected: !sourceId }" @click="go()">全部数据源</button>
          <div v-if="rows.length" class="directory-caption">当前页</div>
          <button v-for="row in rows" :key="row.id" type="button" class="directory-entry" :class="{ selected: row.id === sourceId }"
            :title="row.dsName" :aria-current="row.id === sourceId ? 'true' : undefined" @click="go(row.id)">
            <span class="engine-mark" aria-hidden="true">{{ engine(row.dsType).mark }}</span><span class="truncate">{{ row.dsName }}</span>
          </button>
        </div>
      </aside>

      <section v-if="!sourceId" class="dk-panel source-content" aria-label="全部数据源">
        <form class="filter-bar" @submit.prevent="search">
          <el-input v-model="keyword" :prefix-icon="Search" aria-label="搜索数据源" placeholder="按名称或主机搜索"
            clearable class="search-input" @clear="search" />
          <el-select v-model="query.dsType" aria-label="筛选引擎类型" placeholder="全部类型" clearable class="type-filter" @change="search">
            <el-option v-for="item in engines" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <DkButton type="submit">搜索</DkButton>
        </form>
        <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon>
          <DkButton variant="quiet" @click="loadPage">重试</DkButton>
        </el-alert>
        <el-table v-else v-loading="loading" :data="rows" row-key="id" :empty-text="query.keyword || query.dsType ? '没有匹配的数据源' : '暂无数据源'">
          <el-table-column label="名称" min-width="185">
            <template #default="{ row }">
              <button type="button" class="name-link" @click="go(row.id)">
                <span class="engine-mark" aria-hidden="true">{{ engine(row.dsType).mark }}</span>{{ row.dsName }}
              </button>
            </template>
          </el-table-column>
          <el-table-column label="类型" min-width="104"><template #default="{ row }">{{ engine(row.dsType).label }}</template></el-table-column>
          <el-table-column label="主机 / 端口" min-width="135" show-overflow-tooltip>
            <template #default="{ row }">{{ row.host || '—' }}<template v-if="row.port"> · {{ row.port }}</template></template>
          </el-table-column>
          <el-table-column prop="databaseName" label="默认库 / 模式" min-width="105" show-overflow-tooltip />
          <el-table-column label="配置状态" width="86"><template #default="{ row }">{{ row.status === 1 ? '启用' : '禁用' }}</template></el-table-column>
          <el-table-column label="连接测试" width="105">
            <template #default="{ row }"><span class="probe" :class="probeClass(row.id)">{{ probeText(row.id) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="125">
            <template #default="{ row }">
              <el-button link type="primary" @click="openForm(row)">编辑</el-button>
              <el-dropdown trigger="click" @command="(action: SourceAction) => act(action, row)">
                <button type="button" class="icon-action" :aria-label="`${row.dsName} 的更多操作`" :disabled="pending.has(row.id)"><MoreFilled /></button>
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="test">测试连接</el-dropdown-item>
                  <el-dropdown-item command="sync">同步元数据</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除数据源</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="total" class="pager" v-model:current-page="query.current" v-model:page-size="query.size" :total="total"
          :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" :pager-count="5" @change="loadPage" />
      </section>

      <section v-else v-loading="detailLoading" class="dk-panel source-content" aria-label="数据源详情">
        <DkButton variant="quiet" @click="go()"><template #icon><ArrowLeft /></template>全部数据源</DkButton>
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon>
          <DkButton variant="quiet" @click="loadDetail">重试</DkButton>
        </el-alert>
        <template v-if="detail">
          <header class="detail-header">
            <div class="detail-title">
              <span class="engine-mark large" aria-hidden="true">{{ engine(detail.dsType).mark }}</span>
              <div><h2>{{ detail.dsName }}</h2><span>{{ engine(detail.dsType).label }} · {{ detail.status === 1 ? '启用' : '禁用' }}</span></div>
            </div>
            <div class="actions">
              <DkButton :loading="pending.has(sourceId)" @click="act('test', detail)"><template #icon><Connection /></template>测试连接</DkButton>
              <DkButton variant="primary" @click="openForm(detail)"><template #icon><EditPen /></template>编辑数据源</DkButton>
              <el-dropdown trigger="click" @command="(action: SourceAction) => act(action, detail!)">
                <button type="button" class="icon-action" aria-label="更多数据源操作" :disabled="pending.has(sourceId)"><MoreFilled /></button>
                <template #dropdown><el-dropdown-menu>
                  <el-dropdown-item command="sync">同步元数据</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除数据源</el-dropdown-item>
                </el-dropdown-menu></template>
              </el-dropdown>
            </div>
          </header>
          <section class="detail-section" aria-label="基本信息">
            <h3>基本信息</h3>
            <dl class="facts">
              <div class="full"><dt>数据源 ID</dt><dd class="id-value"><code>{{ detail.id }}</code>
                <button class="icon-action" type="button" aria-label="复制数据源 ID" @click="copyId(detail.id)"><CopyDocument /></button>
              </dd></div>
              <div><dt>创建时间</dt><dd>{{ formatTime(detail.createTime) }}</dd></div>
              <div><dt>更新时间</dt><dd>{{ formatTime(detail.updateTime) }}</dd></div>
            </dl>
          </section>
          <section class="detail-section" aria-label="连接信息">
            <h3>连接信息</h3>
            <dl class="facts">
              <div><dt>主机</dt><dd>{{ detail.host || '—' }}</dd></div>
              <div><dt>端口</dt><dd>{{ detail.port ?? '—' }}</dd></div>
              <div><dt>{{ detail.dsType === 'DM8' ? '目标模式' : '默认数据库' }}</dt><dd>{{ detail.databaseName || '—' }}</dd></div>
              <div><dt>连接用户名</dt><dd>{{ detail.username || '—' }}</dd></div>
              <div v-if="detail.dsType === 'HIVE'"><dt>Hive 认证</dt><dd>{{ extra(detail.properties).hiveAuth === 'NOSASL' ? 'NOSASL' : '默认（NONE）' }}</dd></div>
              <div><dt>连接测试</dt><dd><span class="probe" :class="probeClass(detail.id)">{{ probeText(detail.id) }}</span></dd></div>
              <div v-if="probes[sourceId]"><dt>本次页面最近测试</dt><dd>{{ probes[sourceId]?.checkedAt }}</dd></div>
            </dl>
          </section>
        </template>
      </section>
    </div>

    <el-drawer v-model="drawer" :title="original ? '编辑数据源' : '新建数据源'" size="min(620px, 100vw)" destroy-on-close
      :close-on-click-modal="false" :show-close="!testing && !submitting" :close-on-press-escape="!testing && !submitting"
      @closed="form.password = ''">
      <el-alert v-if="formError" :title="formError" type="error" show-icon :closable="false" />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="testing || submitting"
        class="source-form" @submit.prevent="save">
        <section class="form-section" aria-label="基本信息">
          <h3>基本信息</h3>
          <div class="form-grid">
            <el-form-item label="数据源名称" prop="dsName"><el-input v-model="form.dsName" aria-label="数据源名称" maxlength="128" /></el-form-item>
            <el-form-item label="引擎类型" prop="dsType">
              <el-select v-model="form.dsType" aria-label="引擎类型" @change="changeEngine">
                <el-option v-for="item in engines" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="配置状态">
            <el-radio-group v-model="form.status"><el-radio :value="1">启用</el-radio><el-radio :value="0">禁用</el-radio></el-radio-group>
          </el-form-item>
        </section>
        <section class="form-section" aria-label="连接参数">
          <h3>连接参数</h3>
          <div class="form-grid">
            <el-form-item label="主机" prop="host"><el-input v-model="form.host" aria-label="主机" /></el-form-item>
            <el-form-item label="端口" prop="port"><el-input-number v-model="form.port" aria-label="端口" :min="1" :max="65535" :controls="false" placeholder="使用默认端口" /></el-form-item>
          </div>
          <el-form-item :label="form.dsType === 'DM8' ? '目标模式' : '默认数据库'">
            <el-input v-model="form.databaseName" :placeholder="form.dsType === 'DM8' ? '留空使用登录用户的默认模式' : '填写默认数据库（可选）'" />
          </el-form-item>
          <el-form-item v-if="form.dsType === 'HIVE'" label="Hive 认证">
            <el-select v-model="hiveAuth"><el-option label="默认（NONE）" value="NONE" /><el-option label="NOSASL" value="NOSASL" /></el-select>
          </el-form-item>
        </section>
        <section class="form-section" aria-label="认证与测试">
          <h3>认证与测试</h3>
          <el-form-item label="用户名" prop="username"><el-input v-model="form.username" aria-label="用户名" autocomplete="off" /></el-form-item>
          <el-form-item :label="original ? '新密码' : '密码'">
            <el-input v-model="form.password" :aria-label="original ? '新密码' : '密码'" type="password" autocomplete="new-password" show-password
              :placeholder="original ? '留空保留原密码' : '填写连接密码'" />
          </el-form-item>
          <div class="actions">
            <DkButton :loading="testing" :disabled="submitting" @click="testForm"><template #icon><Connection /></template>测试连接</DkButton>
            <span v-if="testResult != null" class="probe" :class="testResult ? 'is-good' : 'is-bad'" aria-live="polite">
              {{ testResult ? '可连接' : '连接失败' }}
            </span>
          </div>
          <p v-if="original && !savedConnection && !form.password" class="form-note">如需用密码测试修改后的连接，请填写密码。保存时留空会保留原密码。</p>
        </section>
      </el-form>
      <template #footer>
        <DkButton :disabled="testing || submitting" @click="closeForm">取消</DkButton>
        <DkButton variant="primary" :loading="submitting" :disabled="testing" @click="save">保存数据源</DkButton>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.source-page { display: grid; gap: 14px; min-width: 0; }
.source-workspace { display: grid; grid-template-columns: minmax(154px, 205px) minmax(0, 1fr); gap: 14px; align-items: start; }
.source-workspace.is-collapsed { grid-template-columns: 49px minmax(0, 1fr); }
.source-directory { padding: 7px; min-width: 0; }
.directory-title, .directory-entry, .name-link, .icon-action { border: 0; background: transparent; color: var(--dk-ink); cursor: pointer; font: inherit; }
.directory-title, .directory-entry { display: flex; align-items: center; gap: 7px; width: 100%; min-height: 34px; padding: 5px 7px; text-align: left; border-radius: var(--dk-radius-control); }
.directory-title { font-weight: 650; }
.directory-title span { flex: 1; }
.directory-title svg, .icon-action svg { width: 16px; height: 16px; flex: none; }
.is-collapsed .directory-title span, .is-collapsed .directory-title svg:last-child { display: none; }
.directory-entry:hover, .directory-entry.selected { background: var(--dk-primary-soft); color: var(--dk-primary); }
.directory-caption { padding: 8px 7px 2px; color: var(--dk-muted); font-size: 12px; }
.truncate { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.engine-mark { display: inline-grid; place-items: center; min-width: 26px; height: 24px; padding-inline: 3px; box-sizing: border-box; border-radius: 6px; background: var(--dk-primary-soft); color: var(--dk-primary); font-size: 10px; font-weight: 750; flex: none; }
.engine-mark.large { width: 44px; height: 44px; font-size: 16px; }
.source-content { padding: clamp(10px, 1.6vw, 20px); min-width: 0; }
.filter-bar, .actions, .name-link, .detail-title, .id-value { display: flex; align-items: center; gap: 8px; }
.filter-bar, .actions { flex-wrap: wrap; }
.filter-bar { margin-bottom: 12px; }
.search-input { width: min(260px, 100%); }
.type-filter { width: 130px; }
.name-link { padding: 2px 0; text-align: left; color: var(--dk-primary); font-weight: 650; }
.name-link:hover { text-decoration: underline; }
.icon-action { display: inline-grid; place-items: center; min-width: 28px; min-height: 28px; border-radius: 6px; color: var(--dk-primary); }
.icon-action:hover { background: var(--dk-primary-soft); }
.icon-action:disabled { opacity: .5; cursor: not-allowed; }
.probe { color: var(--dk-muted); white-space: nowrap; }
.probe::before { content: ''; display: inline-block; vertical-align: 2px; margin-right: 5px; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.probe.is-good { color: var(--dk-success); }
.probe.is-bad { color: var(--dk-danger); }
.pager { margin-top: 14px; display: flex; flex-wrap: wrap; justify-content: flex-end; }
.detail-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 0; }
.detail-title { min-width: 0; }
.detail-title h2 { margin: 0 0 3px; overflow-wrap: anywhere; }
.detail-title div > span, .form-note { color: var(--dk-muted); font-size: 13px; }
.detail-section { margin-top: 14px; padding: 14px; border: 1px solid var(--dk-border); border-radius: var(--dk-radius-panel); }
h3 { font-size: 15px; margin: 0 0 11px; }
.facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 0; }
.facts .full { grid-column: 1 / -1; }
.facts dt { color: var(--dk-muted); font-size: 12px; }
.facts dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.form-section { margin-top: 10px; padding: 14px; border: 1px solid var(--dk-border, #d5deed); border-radius: 8px; }
.form-section :deep(.el-form-item:last-child) { margin-bottom: 0; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.form-grid :deep(.el-form-item:last-child) { margin-bottom: 18px; }
.form-section :deep(.el-select), .form-section :deep(.el-input-number) { width: 100%; }
.form-note { margin: 8px 0 0; line-height: 1.5; }
@media (max-width: 1000px) {
  .source-workspace, .source-workspace.is-collapsed { grid-template-columns: minmax(0, 1fr); }
  #source-directory-items { display: flex; flex-wrap: wrap; gap: 3px; }
  .directory-entry { width: auto; max-width: 100%; }
  .directory-caption, .is-collapsed .directory-title svg:last-child { display: none; }
  .is-collapsed .directory-title span { display: inline; }
}
@media (max-width: 560px) {
  .facts, .form-grid { grid-template-columns: minmax(0, 1fr); }
  .form-grid { gap: 0; }
  .pager :deep(.el-pagination__sizes) { display: none; }
}
</style>
