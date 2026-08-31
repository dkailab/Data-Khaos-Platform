<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="数据源">
          <el-select v-model="selectedDsId" placeholder="请选择数据源" style="width: 240px" filterable @change="loadStructure">
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsName" :value="ds.id!" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="loading" :disabled="!selectedDsId" @click="loadStructure">
            加载结构
          </el-button>
          <el-button :icon="Refresh" :disabled="!selectedDsId" @click="syncAll">同步元数据</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert title="结构树：数据库 -> 表 -> 字段（点击字段可进行字段治理：业务名 / 业务说明 / 敏感级别 / 字典关联 / 标准落标校验）" type="info" :closable="false" style="margin-bottom: 12px" />

    <div v-if="datasources.length === 0" class="empty">
      <el-empty description="请先到「数据源管理」中新增并同步数据源" />
    </div>

    <el-tree v-else v-loading="loading" :data="treeData" node-key="id" default-expand-all :props="{ label: 'label', children: 'children' }">
      <template #default="{ data }">
        <span class="tree-node" @click.stop="onNodeClick(data)">
          <el-icon v-if="data.nodeType === 'db'"><FolderOpened /></el-icon>
          <el-icon v-else-if="data.nodeType === 'table'"><Grid /></el-icon>
          <el-icon v-else><Minus /></el-icon>
          <span>{{ data.label }}</span>
          <el-tag v-if="data.nodeType === 'table'" size="small" type="info" class="tag">{{ data.rowCount }} 行</el-tag>
          <el-tag v-if="data.nodeType === 'column' && data.isPrimaryKey === 1" size="small" type="warning" class="tag">主键</el-tag>
          <el-tag v-if="data.nodeType === 'column' && data.dictTypeName" size="small" class="tag">字典:{{ data.dictTypeName }}</el-tag>
        </span>
      </template>
    </el-tree>

    <!-- 字段治理对话框 -->
    <el-dialog v-model="dialogVisible" title="字段治理" width="560px" :close-on-click-modal="false">
      <el-descriptions v-if="current" :column="2" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="表">{{ currentTableName }}</el-descriptions-item>
        <el-descriptions-item label="字段">{{ current?.column?.columnName }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ current?.column?.columnType || '-' }} ({{ current?.column?.columnLength || '-' }})</el-descriptions-item>
        <el-descriptions-item label="物理描述">{{ current?.column?.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-form :model="form" label-width="100px">
        <el-form-item label="业务名称">
          <el-input v-model="form.bizName" placeholder="例如：订单金额" clearable />
        </el-form-item>
        <el-form-item label="业务说明">
          <el-input v-model="form.bizComment" type="textarea" :rows="2" placeholder="字段的业务含义说明" />
        </el-form-item>
        <el-form-item label="展示描述">
          <el-input v-model="form.description" placeholder="覆盖物理描述，用于展示用语义" />
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

      <!-- 标准落标校验 -->
      <el-divider content-position="left">标准落标校验</el-divider>
      <div class="std-check">
        <el-select v-model="stdCode" placeholder="选择数据标准" clearable filterable size="default" style="flex: 1">
          <el-option v-for="s in standards" :key="s.stdCode" :label="`${s.stdName} (${s.stdCode})`" :value="s.stdCode!" />
        </el-select>
        <el-button type="primary" :disabled="!stdCode || !current" :loading="checking" @click="doCheckStd">校验</el-button>
      </div>
      <el-alert v-if="stdResult" :type="stdResult.matched ? 'success' : 'error'" :closable="false" class="std-result"
                :title="`结果：${stdResult.matched ? '符合标准' : '不符合标准'}（类型${stdResult.typeMatch ? '√' : '×'} / 长度${stdResult.lengthMatch ? '√' : '×'} / 枚举${stdResult.enumMatch ? '√' : '×'}）`">
        <div v-if="stdResult.enumHint" class="std-hint">{{ stdResult.enumHint }}</div>
        <div v-if="!stdResult.typeMatch">字段类型 {{ current?.column?.columnType }} 与标准要求不匹配</div>
        <div v-if="!stdResult.lengthMatch">字段长度 {{ current?.column?.columnLength }} 超过标准长度限制</div>
      </el-alert>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveColumn">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getStructure, syncMetadata, updateMetaColumn, checkColumnStandard } from '@/api/metadata'
import { pageDatasources } from '@/api/datasource'
import { listDictTypes, listStandards } from '@/api/governance'
import type { MetaColumn, MetaDatabase, MetaDictType, MetaStandard, MetaTable } from '@/types'

interface StructTable {
  table: MetaTable
  columns: MetaColumn[]
}

interface StructDb {
  database: MetaDatabase
  tables: StructTable[]
}

interface TreeNode {
  id: string
  label: string
  nodeType: 'db' | 'table' | 'column'
  children?: TreeNode[]
  rowCount?: number
  isPrimaryKey?: number
  dictTypeName?: string
  column?: MetaColumn
  tableName?: string
}

const loading = ref(false)
const datasources = ref<any[]>([])
const selectedDsId = ref('')
const treeData = ref<TreeNode[]>([])

const dialogVisible = ref(false)
const current = ref<TreeNode | null>(null)
const currentTableName = ref('')
const saving = ref(false)
const dictTypes = ref<MetaDictType[]>([])
const standards = ref<MetaStandard[]>([])
const stdCode = ref('')
const stdResult = ref<Record<string, any> | null>(null)
const checking = ref(false)

const form = reactive({
  bizName: '',
  bizComment: '',
  description: '',
  sensitiveLevel: 0 as number,
  dictTypeCode: '' as string,
})

async function loadDatasources() {
  const data = await pageDatasources({ current: 1, size: 100 })
  datasources.value = data.records || []
  if (datasources.value.length > 0 && !selectedDsId.value) {
    selectedDsId.value = datasources.value[0].id
    loadStructure()
  }
}

async function loadStructure() {
  if (!selectedDsId.value) return
  loading.value = true
  try {
    const data = await getStructure(selectedDsId.value)
    treeData.value = (data || []).map((dbNode: StructDb) => ({
      id: `db-${dbNode.database.id}`,
      label: dbNode.database.databaseName ?? dbNode.database.id ?? '',
      nodeType: 'db',
      children: (dbNode.tables || []).map((t: StructTable) => ({
        id: `table-${t.table.id}`,
        label: t.table.tableName ?? t.table.id ?? '',
        nodeType: 'table',
        rowCount: t.table.rowCount,
        tableName: t.table.tableName,
        children: (t.columns || []).map((c: MetaColumn) => ({
          id: `column-${c.id}`,
          label: `${c.bizName || c.columnName} (${c.columnType || '-'})`,
          nodeType: 'column',
          isPrimaryKey: c.isPrimaryKey,
          dictTypeName: c.dictTypeName,
          tableName: t.table.tableName,
          column: c,
        })),
      })),
    }))
  } finally {
    loading.value = false
  }
}

async function syncAll() {
  await ElMessageBox.confirm('确认全量同步该数据源的元数据吗？', '提示', { type: 'warning' })
  await syncMetadata(selectedDsId.value)
  ElMessage.success('同步完成，正在刷新结构...')
  loadStructure()
}

function onNodeClick(node: TreeNode) {
  if (node.nodeType !== 'column') return
  if (!node.column) return
  const col = node.column
  current.value = node
  currentTableName.value = node.tableName ?? ''
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
    await loadStructure()
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

onMounted(loadDatasources)
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
.empty {
  padding: 40px 0;
}
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}
.tree-node .tag {
  margin-left: 6px;
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