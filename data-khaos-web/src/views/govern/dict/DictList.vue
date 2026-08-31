<template>
  <el-card shadow="never" class="dict-page">
    <el-row :gutter="12" class="dict-body">
      <!-- 左：字典类型 -->
      <el-col :md="7" :xs="24">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">字典类型</span>
            <el-button link type="primary" :icon="Plus" @click="openTypeCreate">新增</el-button>
          </div>
          <el-table
            :data="types"
            highlight-current-row
            border
            size="small"
            v-loading="typeLoading"
            height="calc(100vh - 220px)"
            @current-change="onTypeSelect"
          >
            <el-table-column prop="typeName" label="类型名称" min-width="90" show-overflow-tooltip />
            <el-table-column prop="typeCode" label="编码" min-width="70" show-overflow-tooltip />
            <el-table-column label="操作" width="66" align="center">
              <template #default="{ row, $index }">
                <el-icon class="row-op" @click.stop="openTypeEdit(row, $index)"><Edit /></el-icon>
                <el-icon class="row-op danger" @click.stop="handleTypeDelete(row)"><Delete /></el-icon>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>

      <!-- 右：字典项 -->
      <el-col :md="17" :xs="24">
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">{{ currentType ? `「${currentType.typeName}」字典项` : '字典项' }}</span>
            <el-button link type="primary" :icon="Plus" :disabled="!currentType" @click="openItemCreate">新增字典项</el-button>
          </div>

          <div v-if="!currentType" class="empty-tip">请在左侧选择一个字典类型</div>
          <template v-else>
            <div class="panel-toolbar">
              <el-input
                v-model="itemQuery.keyword"
                placeholder="字典项名称/编码"
                clearable
                style="width: 220px"
                :prefix-icon="Search"
                @keyup.enter="loadItems"
              />
              <el-button :icon="Search" @click="loadItems">查询</el-button>
            </div>
            <el-table v-loading="itemLoading" :data="items" border size="small">
              <el-table-column prop="itemName" label="名称" min-width="120" show-overflow-tooltip />
              <el-table-column prop="itemCode" label="编码" min-width="90" show-overflow-tooltip />
              <el-table-column prop="itemValue" label="值" min-width="90" show-overflow-tooltip />
              <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
              <el-table-column label="状态" width="80" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="120" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openItemEdit(row)">编辑</el-button>
                  <el-button link type="danger" @click="handleItemDelete(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              class="pager"
              v-model:current-page="itemQuery.current"
              v-model:page-size="itemQuery.size"
              :total="itemTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @change="loadItems"
            />
          </template>
        </div>
      </el-col>
    </el-row>

    <!-- 字典类型弹窗 -->
    <el-dialog v-model="typeDialogVisible" :title="typeEditTarget ? '编辑字典类型' : '新增字典类型'" width="480px" destroy-on-close>
      <el-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-width="90px">
        <el-form-item label="类型名称" prop="typeName">
          <el-input v-model="typeForm.typeName" placeholder="如：性别、数据状态" />
        </el-form-item>
        <el-form-item label="类型编码" prop="typeCode">
          <el-input v-model="typeForm.typeCode" placeholder="如：GENDER" :disabled="!!typeEditTarget" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="typeForm.description" type="textarea" :rows="2" placeholder="类型说明" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="typeForm.sortOrder" :min="0" style="width: 160px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitType">保存</el-button>
      </template>
    </el-dialog>

    <!-- 字典项弹窗 -->
    <el-dialog v-model="itemDialogVisible" :title="itemEditTarget ? '编辑字典项' : '新增字典项'" width="520px" destroy-on-close>
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemRules" label-width="90px">
        <el-form-item label="项名称" prop="itemName">
          <el-input v-model="itemForm.itemName" placeholder="如：男" />
        </el-form-item>
        <el-form-item label="项编码" prop="itemCode">
          <el-input v-model="itemForm.itemCode" placeholder="如：M" :disabled="!!itemEditTarget" />
        </el-form-item>
        <el-form-item label="项值" prop="itemValue">
          <el-input v-model="itemForm.itemValue" placeholder="存储值，如：1" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sortOrder" :min="0" style="width: 160px" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="itemForm.description" type="textarea" :rows="2" placeholder="项说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitItem">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import {
  createDictItem, createDictType, deleteDictItem, deleteDictType,
  pageDictItems, listDictTypes, updateDictItem, updateDictType,
} from '@/api/governance'
import type { MetaDictItem, MetaDictType } from '@/types'

const types = ref<MetaDictType[]>([])
const typeLoading = ref(false)
const currentType = ref<MetaDictType | null>(null)
const currentTypeIndex = ref(0)

const items = ref<MetaDictItem[]>([])
const itemLoading = ref(false)
const itemTotal = ref(0)
const itemQuery = ref({ current: 1, size: 20, keyword: '' })

const submitting = ref(false)

// ---- 类型弹窗 ----
const typeDialogVisible = ref(false)
const typeEditTarget = ref<MetaDictType | null>(null)
const typeFormRef = ref<FormInstance>()
const typeForm = ref<Partial<MetaDictType>>({})
const typeRules: FormRules = {
  typeName: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
  typeCode: [{ required: true, message: '请输入类型编码', trigger: 'blur' }],
}

// ---- 字典项弹窗 ----
const itemDialogVisible = ref(false)
const itemEditTarget = ref<MetaDictItem | null>(null)
const itemFormRef = ref<FormInstance>()
const itemForm = ref<Partial<MetaDictItem>>({})
const itemRules: FormRules = {
  itemName: [{ required: true, message: '请输入项名称', trigger: 'blur' }],
  itemCode: [{ required: true, message: '请输入项编码', trigger: 'blur' }],
  itemValue: [{ required: true, message: '请输入项值', trigger: 'blur' }],
}

async function loadTypes() {
  typeLoading.value = true
  try {
    types.value = (await listDictTypes()) || []
    if (types.value.length) {
      currentType.value = types.value[currentTypeIndex.value] || types.value[0]
      loadItems()
    } else {
      currentType.value = null
      items.value = []
    }
  } finally {
    typeLoading.value = false
  }
}

function onTypeSelect(row: MetaDictType) {
  currentType.value = row
  itemQuery.value.current = 1
  itemQuery.value.keyword = ''
  loadItems()
}

async function loadItems() {
  if (!currentType.value?.id) return
  itemLoading.value = true
  try {
    const page = await pageDictItems({
      current: itemQuery.value.current,
      size: itemQuery.value.size,
      typeId: currentType.value.id,
      keyword: itemQuery.value.keyword,
    })
    items.value = page?.records || []
    itemTotal.value = page?.total || 0
  } finally {
    itemLoading.value = false
  }
}

function openTypeCreate() {
  typeEditTarget.value = null
  typeForm.value = { typeName: '', typeCode: '', description: '', sortOrder: 0 }
  typeDialogVisible.value = true
}

function openTypeEdit(row: MetaDictType, index: number) {
  typeEditTarget.value = row
  currentTypeIndex.value = index
  typeForm.value = { ...row }
  typeDialogVisible.value = true
}

async function submitType() {
  const ok = await typeFormRef.value?.validate().catch(() => false)
  if (!ok) return
  submitting.value = true
  try {
    if (typeEditTarget.value?.id) {
      await updateDictType(typeEditTarget.value.id, typeForm.value)
    } else {
      await createDictType(typeForm.value)
    }
    ElMessage.success('保存成功')
    typeDialogVisible.value = false
    await loadTypes()
  } finally {
    submitting.value = false
  }
}

async function handleTypeDelete(row: MetaDictType) {
  await ElMessageBox.confirm(`删除字典类型「${row.typeName}」将连带删除其全部字典项，确认删除？`, '提示', { type: 'warning' })
  await deleteDictType(row.id!)
  ElMessage.success('删除成功')
  await loadTypes()
}

function openItemCreate() {
  if (!currentType.value?.id) return
  itemEditTarget.value = null
  itemForm.value = { itemName: '', itemCode: '', itemValue: '', sortOrder: 0 }
  itemDialogVisible.value = true
}

function openItemEdit(row: MetaDictItem) {
  itemEditTarget.value = row
  itemForm.value = { ...row }
  itemDialogVisible.value = true
}

async function submitItem() {
  const ok = await itemFormRef.value?.validate().catch(() => false)
  if (!ok) return
  submitting.value = true
  try {
    const payload = { ...itemForm.value, typeId: currentType.value?.id }
    if (itemEditTarget.value?.id) {
      await updateDictItem(itemEditTarget.value.id, payload)
    } else {
      await createDictItem(payload)
    }
    ElMessage.success('保存成功')
    itemDialogVisible.value = false
    await loadItems()
  } finally {
    submitting.value = false
  }
}

async function handleItemDelete(row: MetaDictItem) {
  await ElMessageBox.confirm(`确认删除字典项「${row.itemName}」？`, '提示', { type: 'warning' })
  await deleteDictItem(row.id!)
  ElMessage.success('删除成功')
  await loadItems()
}

onMounted(loadTypes)
</script>

<style scoped>
.dict-body {
  min-height: calc(100vh - 160px);
}
.panel {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  height: 100%;
  overflow: hidden;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.panel-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.row-op {
  cursor: pointer;
  color: var(--el-color-primary);
  margin: 0 4px;
}
.row-op.danger {
  color: var(--el-color-danger);
}
.empty-tip {
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 60px 0 40px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>