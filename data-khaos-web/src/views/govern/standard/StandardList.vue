<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-form inline>
        <el-form-item label="分类">
          <el-select v-model="query.category" clearable placeholder="全部分类" style="width: 150px" @change="handleSearch">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 120px" @change="handleSearch">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="标准名称/编码" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增标准</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border stripe>
      <el-table-column prop="stdName" label="标准名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="stdCode" label="标准编码" min-width="130" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.category">{{ row.category }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="类型/长度" min-width="130">
        <template #default="{ row }">
          {{ row.dataType || '-' }}<template v-if="row.dataLength">({{ row.dataLength }})</template>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="70">
        <template #default="{ row }"><span>{{ row.unit || '-' }}</span></template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="80" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑数据标准' : '新增数据标准'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="标准名称" prop="stdName">
          <el-input v-model="form.stdName" placeholder="如：统一客户编号" />
        </el-form-item>
        <el-form-item label="标准编码" prop="stdCode">
          <el-input v-model="form.stdCode" placeholder="如：STD_CUST_NO" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="数据类型" prop="dataType">
              <el-select v-model="form.dataType" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="t in dataTypes" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="长度">
              <el-input-number v-model="form.dataLength" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="小数位">
              <el-input-number v-model="form.dataScale" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="枚举范围">
          <el-input v-model="form.enumRange" placeholder="如：0,1,2 或 JSON 数组；留空表示不限制" />
        </el-form-item>
        <el-form-item label="格式规则">
          <el-input v-model="form.formatRule" placeholder="如：^[A-Z]{2}\d{6}$" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { createStandard, deleteStandard, pageStandards, updateStandard } from '@/api/governance'
import type { MetaStandard } from '@/types'

const categoryOptions = ['元数据类', '编码类', '格式类', '值域类']
const dataTypes = ['VARCHAR', 'BIGINT', 'INT', 'DECIMAL', 'DATE', 'DATETIME', 'TIMESTAMP', 'BOOLEAN']

const loading = ref(false)
const list = ref<MetaStandard[]>([])
const total = ref(0)
const query = reactive({ current: 1, size: 10, keyword: '', category: undefined as string | undefined, status: undefined as number | undefined })

const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref('')
const submitting = ref(false)
const formRef = ref<FormInstance>()
const form = ref<Partial<MetaStandard>>({})
const formRules: FormRules = {
  stdName: [{ required: true, message: '请输入标准名称', trigger: 'blur' }],
  stdCode: [{ required: true, message: '请输入标准编码', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

async function load() {
  loading.value = true
  try {
    const page = await pageStandards(query)
    list.value = page?.records || []
    total.value = page?.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.current = 1
  load()
}

function handleReset() {
  query.keyword = ''
  query.category = undefined
  query.status = undefined
  query.current = 1
  load()
}

function openCreate() {
  isEdit.value = false
  editingId.value = ''
  form.value = { stdName: '', stdCode: '', category: '', dataType: 'VARCHAR', dataLength: 0, dataScale: 0, enumRange: '', formatRule: '', description: '', status: 1, sortOrder: 0 }
  dialogVisible.value = true
}

function openEdit(row: MetaStandard) {
  isEdit.value = true
  editingId.value = row.id!
  form.value = { ...row }
  dialogVisible.value = true
}

async function submit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  submitting.value = true
  try {
    const payload: MetaStandard = { ...form.value }
    if (isEdit.value) {
      await updateStandard(editingId.value, payload)
    } else {
      await createStandard(payload)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: MetaStandard) {
  await ElMessageBox.confirm(`确认删除数据标准「${row.stdName}」？`, '提示', { type: 'warning' })
  await deleteStandard(row.id!)
  ElMessage.success('删除成功')
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>