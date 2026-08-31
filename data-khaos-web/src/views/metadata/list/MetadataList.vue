<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="数据源">
          <el-select v-model="selectedDsId" placeholder="请选择数据源" style="width: 220px" filterable @change="loadList">
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsName" :value="ds.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="keyword" placeholder="表名 / 字段名 / 业务名 / 类型" style="width: 260px" clearable @input="applyFilter" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Refresh" :loading="loading" :disabled="!selectedDsId" @click="loadList">刷新</el-button>
          <el-button :icon="Refresh" :loading="syncing" :disabled="!selectedDsId" @click="doSync">同步元数据</el-button>
          <el-button :icon="Collection" @click="goTree">树状视图</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert title="元数据列表：点击库/表行可展开查看下级，点击字段可将行治理（业务名/说明/敏感级/字典关联/标准落标校验）" type="info" :closable="false" style="margin-bottom: 12px" />

    <el-table v-loading="loading" :data="filtered" row-key="id" border stripe default-expand-all :tree-props="{ children: 'children' }">
      <!-- 层级：库 / 表 / 字段 -->
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.nodeType === 'db'" type="primary">库</el-tag>
          <el-tag v-else-if="row.nodeType === 'table'" type="warning">表</el-tag>
          <el-tag v-else type="info">字段</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="名称" min-width="240">
        <template #default="{ row }">
          <div class="name-cell">
            <el-icon v-if="row.nodeType === 'db'"><FolderOpened /></el-icon>
            <el-icon v-else-if="row.nodeType === 'table'"><Grid /></el-icon>
            <el-icon v-else><Minus /></el-icon>
            <span>{{ row.name }}</span>
            <el-tag v-if="row.dictTypeName" size="small" class="chip">字典:{{ row.dictTypeName }}</el-tag>
            <el-tag v-if="row.bizName && row.nodeType === 'column'" size="small" type="success" class="chip">已治理</el-tag>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="类型 / 长度" width="130">
        <template #default="{ row }">
          <span v-if="row.nodeType === 'column'">{{ row.dataType || '-' }} ({{ row.dataLength || '-' }})</span>
          <span v-else-if="row.nodeType === 'table'">{{ row.tableType || '-' }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>

      <el-table-column label="业务名" min-width="120">
        <template #default="{ row }">{{ row.bizName || '-' }}</template>
      </el-table-column>

      <el-table-column label="敏感级" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.nodeType === 'column' && row.sensitiveLevel > 0"
                  :type="row.sensitiveLevel === 2 ? 'danger' : 'warning'" size="small">
            {{ row.sensitiveLevel === 2 ? '高度敏感' : '敏感' }}
          </el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>

      <el-table-column label="行数" width="90">
        <template #default="{ row }">{{ row.nodeType === 'table' ? row.rowCount : '-' }}</template>
      </el-table-column>

      <el-table-column label="描述" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description || row.bizComment || '-' }}</template>
      </el-table-column>

      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.nodeType === 'column'" type="primary" link @click.stop="openGovern(row)">治理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 字段治理对话框 -->
    <el-dialog v-model="dialogVisible" title="字段治理" width="560px" :close-on-click-modal="false">
      <el-descriptions v-if="current" :column="2" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="表">{{ currentTableName }}</el-descriptions-item>
        <el-descriptions-item label="字段">{{ current?.name }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ current?.dataType || '-' }} ({{ current?.dataLength || '-' }})</el-descriptions-item>
        <el-descriptions-item label="物理描述">{{ current?.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-form :model="form" label-width="100px">
        <el-form-item label="业务名称">
          <el-input v-model="form.bizName" placeholder="例如：订单金额" clearable />
        </el-form-item>
        <el-form-item label="业务说明">
          <el-input v-model="form.bizComment" type="textarea" :rows="2" placeholder="字段的业务含义说明" />
        </el-form-item>
        <el-form-item label="展示描述">
          <el-input v-model="form.description" placeholder="覆盖物理描述" />
        </el-form-item>
        <el-form-item label="敏感级别">
          <el-select v-model="form.sensitiveLevel" placeholder="请选择" style="width: 100%">
            <el-option label="普通" :value="0" />
            <el-option label="敏感" :value="1" />
            <el-option label="高度敏感" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联字典">
          <el-select v-model="form.dictTypeCode" placeholder="可选，绑定业务字典" clearable filterable style="width: 100%">
            <el-option v-for="dt in dictTypes" :key="dt.typeCode" :label="dt.typeName" :value="dt.typeCode!" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">标准落标校验</el-divider>
      <div class="std-check">
        <el-select v-model="stdCode" placeholder="选择数据标准" clearable filterable style="flex: 1">
          <el-option v-for="s in standards" :key="s.stdCode" :label="`${s.stdName} (${s.stdCode})`" :value="s.stdCode!" />
        </el-select>
        <el-button type="primary" :disabled="!stdCode || !current" :loading="checking" @click="doCheckStd">校验</el-button>
      </div>
      <el-alert v-if="stdResult" :type="stdResult.matched ? 'success' : 'error'" :closable="false" class="std-result"
                :title="`结果：${stdResult.matched ? '符合标准' : '不符合标准'}（类型${stdResult.typeMatch ? '√' : '×'} / 长度${stdResult.lengthMatch ? '√' : '×'} / 枚举${stdResult.enumMatch ? '√' : '×'}）`">
        <div v-if="stdResult.enumHint" class="std-hint">{{ stdResult.enumHint }}</div>
        <div v-if="!stdResult.typeMatch">字段类型 {{ current?.dataType }} 与标准要求不匹配</div>
        <div v-if="!stdResult.lengthMatch">字段长度超出标准限制</div>
      </el-alert>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveColumn">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Collection, FolderOpened, Grid, Minus, Refresh } from '@element-plus/icons-vue'
import { getStructure, syncMetadata, updateMetaColumn, checkColumnStandard } from '@/api/metadata'
import { pageDatasources } from '@/api/datasource'
import { listDictTypes, listStandards } from '@/api/governance'
import type { MetaDictType, MetaStandard } from '@/types'

interface RowNode {
  id: string
  nodeType: 'db' | 'table' | 'column'
  name: string
  dataType?: string
  dataLength?: number
  tableType?: string
  bizName?: string
  bizComment?: string
  sensitiveLevel?: number
  rowCount?: number
  description?: string
  dictTypeName?: string
  children?: RowNode[]
  column?: any
  table?: any
}

const router = useRouter()
const loading = ref(false)
const syncing = ref(false)
const datasources = ref<any[]>([])
const selectedDsId = ref('')
const keyword = ref('')
const rows = ref<RowNode[]>([])

const dialogVisible = ref(false)
const current = ref<RowNode | null>(null)
const currentTableName = ref('')
const saving = ref(false)
const checking = ref(false)
const dictTypes = ref<MetaDictType[]>([])
const standards = ref<MetaStandard[]>([])
const stdCode = ref('')
const stdResult = ref<Record<string, any> | null>(null)
const form = reactive({
  bizName: '',
  bizComment: '',
  description: '',
  sensitiveLevel: 0 as number,
  dictTypeCode: '' as string,
})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  const match = (n: RowNode): boolean =>
    [n.name, n.bizName, n.bizComment, n.description, n.dataType]
      .some(v => v != null && String(v).toLowerCase().includes(k)) ||
    (n.children?.some(match) ?? false)
  return rows.value.map(db => ({
    ...db,
    children: filterChildren(db.children ?? [], k),
  })).filter(db => match(db))
})

function filterChildren(nodes: RowNode[], k: string): RowNode[] {
  return nodes
    .map(t => ({ ...t, children: filterChildren(t.children ?? [], k) }))
    .filter(n => [n.name, n.bizName, n.bizComment, n.description, n.dataType]
      .some(v => v != null && String(v).toLowerCase().includes(k)) ||
      (n.children?.length ?? 0) > 0)
}

function goTree() {
  router.push('/metadata/structure')
}

function openGovern(row: RowNode) {
  if (row.nodeType !== 'column' || !row.column) return
  const col = row.column
  current.value = row
  currentTableName.value = findTableName(row)
  form.bizName = col.bizName ?? ''
  form.bizComment = col.bizComment ?? ''
  form.description = col.description ?? ''
  form.sensitiveLevel = col.sensitiveLevel ?? 0
  form.dictTypeCode = col.dictTypeCode ?? ''
  stdCode.value = ''
  stdResult.value = null
  dialogVisible.value = true
  loadDictMeta()
}

function findTableName(row: RowNode): string {
  for (const db of rows.value) {
    for (const t of db.children ?? []) {
      if ((t.children ?? []).some(c => c.id === row.id)) {
        return t.name
      }
    }
  }
  return ''
}

async function loadDictMeta() {
  if (dictTypes.value.length === 0) {
    try {
      dictTypes.value = (await listDictTypes()) || []
    } catch {
      dictTypes.value = []
    }
  }
  if (standards.value.length === 0) {
    try {
      standards.value = (await listStandards()) || []
    } catch {
      standards.value = []
    }
  }
}

async function saveColumn() {
  if (!current.value?.column?.id) return
  saving.value = true
  try {
    await updateMetaColumn(current.value.column.id, {
      id: current.value.column.id,
      bizName: form.bizName,
      bizComment: form.bizComment,
      description: form.description,
      sensitiveLevel: form.sensitiveLevel,
      dictTypeCode: form.dictTypeCode || undefined,
    })
    ElMessage.success('字段治理已保存')
    dialogVisible.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

async function doCheckStd() {
  if (!current.value?.column?.id || !stdCode.value) return
  checking.value = true
  try {
    stdResult.value = await checkColumnStandard(current.value.column.id, stdCode.value)
  } catch (e: any) {
    ElMessage.error(e?.message || '校验失败')
  } finally {
    checking.value = false
  }
}

async function loadDatasources() {
  const data = await pageDatasources({ current: 1, size: 100 })
  datasources.value = data.records || []
  if (datasources.value.length > 0 && !selectedDsId.value) {
    selectedDsId.value = datasources.value[0].id
    await loadList()
  }
}

async function loadList() {
  if (!selectedDsId.value) return
  loading.value = true
  try {
    const data = await getStructure(selectedDsId.value)
    rows.value = (data || []).map((dbNode: any) => ({
      id: `db-${dbNode.database.id}`,
      nodeType: 'db',
      name: dbNode.database.databaseName ?? dbNode.database.id ?? '',
      children: (dbNode.tables || []).map((tNode: any) => ({
        id: `table-${tNode.table.id}`,
        nodeType: 'table',
        name: tNode.table.tableName ?? '',
        tableType: tNode.table.tableType,
        rowCount: tNode.table.rowCount,
        description: tNode.table.description,
        children: (tNode.columns || []).map((c: any) => ({
          id: `column-${c.id}`,
          nodeType: 'column',
          name: c.columnName ?? '',
          dataType: c.columnType,
          dataLength: c.columnLength,
          bizName: c.bizName,
          bizComment: c.bizComment,
          sensitiveLevel: c.sensitiveLevel,
          description: c.description,
          dictTypeName: c.dictTypeName,
          column: c,
        })),
      })),
    }))
  } finally {
    loading.value = false
  }
}

async function doSync() {
  await syncMetadata(selectedDsId.value)
  ElMessage.success('同步完成，正在刷新...')
  await loadList()
}

function applyFilter() {
  /* computed 自动过滤 */
}

onMounted(loadDatasources)
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chip {
  margin-left: 2px;
}
.std-check {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.std-result {
  margin-top: 4px;
}
.std-hint {
  margin-top: 4px;
}
</style>