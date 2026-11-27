<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FolderOpened } from '@element-plus/icons-vue'
import type { WorkflowSqlFile } from '@/types'

const props = defineProps<{ modelValue: boolean; files: WorkflowSqlFile[] }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'select', file: WorkflowSqlFile): void
}>()

interface FolderNode {
  path: string
  name: string
  children: FolderNode[]
}

const keyword = ref('')
const selectedFolder = ref('/')
const selectedId = ref('')

const tree = computed<FolderNode[]>(() => {
  const root: FolderNode = { path: '/', name: '全部文件', children: [] }
  const index = new Map<string, FolderNode>([['/', root]])
  for (const file of props.files) {
    const folder = file.folder || '/SQL文件'
    let path = ''
    for (const segment of folder.split('/').filter(Boolean)) {
      const parentPath = path || '/'
      path = `${path}/${segment}`
      if (!index.has(path)) {
        const node: FolderNode = { path, name: segment, children: [] }
        index.set(path, node)
        index.get(parentPath)!.children.push(node)
      }
    }
  }
  const sort = (node: FolderNode) => {
    node.children.sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
    node.children.forEach(sort)
  }
  sort(root)
  return [root]
})

const visibleFiles = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return props.files
    .filter(file => {
      const folder = file.folder || '/SQL文件'
      const inFolder = selectedFolder.value === '/' || folder === selectedFolder.value
        || folder.startsWith(selectedFolder.value + '/')
      const matches = !text || file.name.toLowerCase().includes(text)
        || folder.toLowerCase().includes(text) || (file.description || '').toLowerCase().includes(text)
      return inFolder && matches
    })
    .sort((a, b) => (a.folder || '').localeCompare(b.folder || '') || a.name.localeCompare(b.name, 'zh-Hans-CN'))
})

const selected = computed(() => props.files.find(file => file.id === selectedId.value))

watch(() => props.modelValue, (open) => {
  if (open) {
    keyword.value = ''
    selectedFolder.value = '/'
    selectedId.value = ''
  }
})

function confirm() {
  if (!selected.value) return
  emit('select', selected.value)
  emit('update:modelValue', false)
}

function onNodeClick(node: { path: string }) {
  selectedFolder.value = node.path
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="查找 SQL 文件" width="820px" class="file-picker"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <div class="picker-head">
      <el-input v-model="keyword" placeholder="输入文件名、目录或说明搜索" clearable />
    </div>
    <div class="picker-body">
      <div class="folder-pane">
        <el-tree :data="tree" node-key="path" :props="{ label: 'name', children: 'children' }"
          highlight-current default-expand-all :expand-on-click-node="false" @node-click="onNodeClick">
          <template #default="{ data }">
            <span class="folder-node">
              <el-icon><FolderOpened /></el-icon>
              <span>{{ data.name }}</span>
            </span>
          </template>
        </el-tree>
      </div>
      <div class="file-pane">
        <div v-if="visibleFiles.length" class="file-list">
          <button v-for="file in visibleFiles" :key="file.id" type="button" class="file-row"
            :class="{ active: file.id === selectedId }" @click="selectedId = file.id!">
            <span class="file-main">
              <strong>{{ file.name }}</strong>
              <span class="file-folder">{{ file.folder }}</span>
            </span>
            <span class="file-meta">
              <el-tag size="small" type="info">v{{ file.draftVersion ?? 1 }}</el-tag>
              <el-tag v-if="(file.publishedVersion ?? 0) > 0" size="small" type="success">
                v{{ file.publishedVersion }}
              </el-tag>
              <span class="file-time">{{ (file.updateTime || '').replace('T', ' ').slice(0, 16) }}</span>
            </span>
          </button>
        </div>
        <el-empty v-else description="该目录下没有匹配的 SQL 文件" :image-size="80" />
      </div>
    </div>
    <div class="picker-path">
      <span class="path-label">已选：</span>
      <span v-if="selected">{{ selected.folder }}/{{ selected.name }}</span>
      <span v-else class="muted">尚未选择文件</span>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :disabled="!selected" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.picker-head { margin-bottom: 12px; }
.picker-body { display: grid; grid-template-columns: 220px 1fr; gap: 12px; height: 380px; }
.folder-pane { border: 1px solid #e4e7ec; border-radius: 8px; padding: 6px; overflow: auto; }
.folder-node { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.folder-node .el-icon { color: #667085; }
.file-pane { border: 1px solid #e4e7ec; border-radius: 8px; overflow: auto; }
.file-list { display: flex; flex-direction: column; }
.file-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%;
  border: 0; border-bottom: 1px solid #f2f4f7; background: #fff; padding: 10px 12px; cursor: pointer;
  text-align: left; }
.file-row:hover { background: #f7f9fc; }
.file-row.active { background: var(--dk-primary-soft); }
.file-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.file-main strong { font-size: 13px; color: var(--dk-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-folder { font-size: 11.5px; color: #98a2b3; }
.file-meta { display: flex; align-items: center; gap: 6px; flex: none; }
.file-time { font-size: 11.5px; color: #98a2b3; }
.picker-path { margin-top: 10px; padding: 8px 10px; border-radius: 6px; background: #f7f9fc; font-size: 12.5px; }
.path-label { color: #667085; }
.muted { color: #98a2b3; }
</style>
