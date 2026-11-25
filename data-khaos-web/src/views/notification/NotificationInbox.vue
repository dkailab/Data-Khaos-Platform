<script setup lang="ts">
import { ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { pageInbox, type InboxMessage } from '@/api/notification'

const emit = defineEmits<{ close: [] }>()
const current = ref(1)
const size = 10
const messages = ref<InboxMessage[]>([])
const total = ref(0)
const revision = ref(0)
const loading = ref(false)
const error = ref('')
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })

watch([current, revision], async ([page], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''
  messages.value = []
  try {
    const result = await pageInbox(page, size, request.signal)
    if (!request.signal.aborted) {
      messages.value = result.records
      total.value = Number(result.total)
    }
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '无法读取站内消息'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

function formattedTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : formatter.format(date)
}
</script>

<template>
  <el-dialog :model-value="true" title="站内消息" append-to-body width="min(560px, calc(100vw - 24px))"
    @update:model-value="$event === false && emit('close')">
    <p v-if="loading" role="status">正在读取站内消息。</p>
    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-empty v-else-if="!messages.length" description="暂无站内消息" :image-size="60" />
    <ul v-else class="message-list">
      <li v-for="message in messages" :key="message.id">
        <div class="message-header">
          <strong>{{ message.title }}</strong>
          <time v-if="message.sendTime" :datetime="message.sendTime">{{ formattedTime(message.sendTime) }}</time>
        </div>
        <p>{{ message.content }}</p>
      </li>
    </ul>
    <template #footer>
      <div class="message-actions">
        <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
          layout="prev, pager, next" :pager-count="5" size="small" />
        <DkButton :loading="loading" @click="revision++">刷新</DkButton>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.message-list { max-height: min(56vh, 560px); overflow: auto; margin: 0; padding: 0; list-style: none; }
.message-list li { padding: 12px 0; border-bottom: 1px solid var(--dk-border); }
.message-header, .message-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.message-header time { color: var(--dk-muted); font-size: 12px; }
.message-list p { margin: 4px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-actions :last-child { margin-left: auto; }
</style>
