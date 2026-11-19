<script setup lang="ts">
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'quiet'
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  loading?: boolean
}>(), { variant: 'secondary', type: 'button' })
</script>

<template>
  <button
    class="dk-button"
    :class="`dk-button--${variant}`"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="dk-spinner" aria-hidden="true"></span>
    <slot v-else name="icon" />
    <slot />
  </button>
</template>

<style scoped>
.dk-button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  gap: var(--dk-space-2);
  padding: 0 var(--dk-space-3);
  border: 1px solid var(--dk-border);
  border-radius: var(--dk-radius-control);
  color: var(--dk-ink);
  background: var(--dk-surface);
  font: inherit;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}
.dk-button:hover { background: var(--dk-soft); }
.dk-button--primary { background: var(--dk-primary); border-color: var(--dk-primary); color: white; }
.dk-button--primary:hover { background: var(--dk-primary-hover); border-color: var(--dk-primary-hover); }
.dk-button--quiet { background: transparent; border-color: transparent; color: var(--dk-primary); }
.dk-button:disabled { opacity: .55; cursor: not-allowed; }
.dk-button :deep(svg), .dk-spinner { width: 16px; height: 16px; flex: none; }
.dk-spinner {
  box-sizing: border-box;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
}
@media (prefers-reduced-motion: no-preference) {
  .dk-spinner { animation: spin .75s linear infinite; }
}
@keyframes spin { to { transform: rotate(1turn); } }
</style>
