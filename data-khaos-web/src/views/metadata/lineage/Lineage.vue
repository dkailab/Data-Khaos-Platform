<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="表名关键字">
          <el-input v-model="keyword" placeholder="输入表名搜索" clearable style="width: 200px" @keyup.enter="searchTables" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="searching" @click="searchTables">搜索表</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="success" :icon="MagicStick" @click="sqlVisible = true">SQL 自动分析</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Plus" @click="lineageVisible = true">手工录入</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never" header="搜索结果">
          <el-table v-loading="searching" :data="tables" size="small" border highlight-current-row @current-change="selectTable">
            <el-table-column prop="tableName" label="表名" min-width="140" />
            <el-table-column prop="tableType" label="类型" width="80" />
            <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>血缘关系：{{ currentTable?.tableName || '请先选择表' }}</template>
          <el-table v-loading="lineageLoading" :data="lineageList" size="small" border>
            <el-table-column prop="sourceTableId" label="源表ID" min-width="130" />
            <el-table-column prop="sourceColumn" label="源字段" min-width="100" />
            <el-table-column prop="targetTableId" label="目标表ID" min-width="130" />
            <el-table-column prop="targetColumn" label="目标字段" min-width="100" />
            <el-table-column label="关系类型" width="100">
              <template #default="{ row }">
                <el-tag>{{ row.relationType }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!lineageLoading && lineageList.length === 0" description="暂无血缘关系" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 记录血缘对话框 -->
    <el-dialog v-model="lineageVisible" title="记录血缘关系" width="520px" destroy-on-close>
      <el-form ref="lineageFormRef" :model="lineageForm" :rules="lineageRules" label-width="100px">
        <el-form-item label="源表ID" prop="sourceTableId">
          <el-input v-model="lineageForm.sourceTableId" placeholder="请输入源表记录ID" />
        </el-form-item>
        <el-form-item label="目标表ID" prop="targetTableId">
          <el-input v-model="lineageForm.targetTableId" placeholder="请输入目标表记录ID" />
        </el-form-item>
        <el-form-item label="源字段">
          <el-input v-model="lineageForm.sourceColumn" placeholder="请输入源字段（可选）" />
        </el-form-item>
        <el-form-item label="目标字段">
          <el-input v-model="lineageForm.targetColumn" placeholder="请输入目标字段（可选）" />
        </el-form-item>
        <el-form-item label="关系类型">
          <el-select v-model="lineageForm.relationType" style="width: 100%">
            <el-option label="ETL" value="ETL" />
            <el-option label="手动" value="MANUAL" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lineageVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitLineage">保存</el-button>
      </template>
    </el-dialog>
    <!-- SQL 血缘自动分析对话框 -->
    <el-dialog v-model="sqlVisible" title="SQL 血缘自动分析" width="620px" destroy-on-close>
      <el-form ref="sqlFormRef" :model="sqlForm" label-width="100px">
        <el-form-item label="数据源" prop="datasourceId">
          <el-select v-model="sqlForm.datasourceId" placeholder="请选择数据源" filterable style="width: 100%" @change="onDsChange">
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsName" :value="ds.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库" prop="database">
          <el-select v-model="sqlForm.database" placeholder="请选择数据库" clearable filterable style="width: 100%">
            <el-option v-for="db in databases" :key="db" :label="db" :value="db" />
          </el-select>
        </el-form-item>
        <el-form-item label="SQL 语句" prop="sql">
          <el-input v-model="sqlForm.sql" type="textarea" :rows="6"
                    placeholder="请输入 INSERT INTO ... SELECT ... JOIN ...（点击分析解析出目标表与源表并自动写入血缘）" />
        </el-form-item>
      </el-form>
      <el-alert v-if="analyzeResult" :type="analyzeCount > 0 ? 'success' : 'warning'" :closable="false"
                :title="`解析完成：发现 ${analyzeCount} 条血缘关系（目标表 → 源表）`" />
      <template #footer>
        <el-button @click="sqlVisible = false">取消</el-button>
        <el-button type="primary" :loading="analyzing" :disabled="!sqlForm.datasourceId || !sqlForm.sql" @click="doAnalyzeSql">分析并写入</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { MagicStick, Plus, Search } from '@element-plus/icons-vue'
import { analyzeSqlLineage, getLineage, listMetaDatabases, pageMetaTables, saveLineage } from '@/api/metadata'
import { pageDatasources } from '@/api/datasource'
import type { MetaTable, MetaTableLineage } from '@/types'

const keyword = ref('')
const searching = ref(false)
const lineageLoading = ref(false)
const tables = ref<MetaTable[]>([])
const currentTable = ref<MetaTable | null>(null)
const lineageList = ref<MetaTableLineage[]>([])

const lineageVisible = ref(false)
const lineageFormRef = ref<FormInstance>()
const submitting = ref(false)
const lineageForm = reactive<MetaTableLineage>({ relationType: 'ETL' })
const lineageRules: FormRules = {
  sourceTableId: [{ required: true, message: '请输入源表ID', trigger: 'blur' }],
  targetTableId: [{ required: true, message: '请输入目标表ID', trigger: 'blur' }],
}

// SQL 自动分析
const sqlVisible = ref(false)
const analyzing = ref(false)
const datasources = ref<any[]>([])
const databases = ref<string[]>([])
const analyzeCount = ref(0)
const analyzeResult = ref(false)
const sqlFormRef = ref<FormInstance>()
const sqlForm = reactive({
  datasourceId: '',
  database: '',
  sql: '',
})

async function loadDatasources() {
  const data = await pageDatasources({ current: 1, size: 100 })
  datasources.value = data.records || []
}

async function onDsChange(dsId: string) {
  sqlForm.database = ''
  databases.value = []
  if (!dsId) return
  try {
    const dbs = (await listMetaDatabases(dsId)) || []
    databases.value = dbs.map((d: any) => d.databaseName ?? d.id).filter(Boolean)
  } catch {
    databases.value = []
  }
}

async function doAnalyzeSql() {
  if (!sqlForm.datasourceId || !sqlForm.database || !sqlForm.sql.trim()) {
    ElMessage.warning('请完整填写数据源、数据库与 SQL')
    return
  }
  analyzing.value = true
  analyzeResult.value = false
  try {
    const created = (await analyzeSqlLineage(sqlForm.datasourceId, sqlForm.database, sqlForm.sql.trim())) || []
    analyzeCount.value = created.length
    analyzeResult.value = true
    ElMessage.success(`SQL 解析完成，写入 ${created.length} 条血缘`)
    if (currentTable.value) {
      selectTable(currentTable.value)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '分析失败')
  } finally {
    analyzing.value = false
  }
}

async function searchTables() {
  searching.value = true
  try {
    const data = await pageMetaTables({ current: 1, size: 20, keyword: keyword.value })
    tables.value = data.records
    currentTable.value = null
    lineageList.value = []
  } finally {
    searching.value = false
  }
}

async function selectTable(row: MetaTable) {
  currentTable.value = row
  lineageLoading.value = true
  try {
    lineageList.value = (await getLineage(row.id!)) || []
  } finally {
    lineageLoading.value = false
  }
}

async function submitLineage() {
  await lineageFormRef.value?.validate()
  submitting.value = true
  try {
    await saveLineage({ ...lineageForm })
    ElMessage.success('血缘关系已保存')
    lineageVisible.value = false
    if (currentTable.value) {
      selectTable(currentTable.value)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadDatasources()
  searchTables()
})
</script>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}
</style>
