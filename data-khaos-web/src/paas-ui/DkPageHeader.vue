<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <header class="dk-page-header">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.dk-page-header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end; gap: var(--dk-space-4); }
h1 { margin: 0; font-size: clamp(27px, 3.5vw, 36px); font-weight: 680; line-height: 1.24; letter-spacing: -.035em; }
p { margin: var(--dk-space-2) 0 0; color: var(--dk-muted); }
.actions { display: flex; flex-wrap: wrap; gap: var(--dk-space-2); }
</style>
