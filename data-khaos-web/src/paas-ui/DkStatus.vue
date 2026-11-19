<script setup lang="ts">
defineProps<{ status: 'success' | 'running' | 'failed' | 'queued' | 'blocked' }>()
const labels = {
  success: '成功',
  running: '运行中',
  failed: '失败',
  queued: '排队中',
  blocked: '已阻断',
}
</script>

<template>
  <span class="dk-status" :class="`dk-status--${status}`">
    <span class="dot" aria-hidden="true"></span>{{ labels[status] }}
  </span>
</template>

<style scoped>
.dk-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 6px;
  padding: 3px 8px;
  background: var(--dk-soft);
  color: var(--dk-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
}
.dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.dk-status--success { background: var(--dk-success-soft); color: var(--dk-success); }
.dk-status--running { background: var(--dk-primary-soft); color: var(--dk-primary); }
.dk-status--failed { background: var(--dk-danger-soft); color: var(--dk-danger); }
.dk-status--blocked { background: var(--dk-warning-soft); color: var(--dk-warning); }
</style>
