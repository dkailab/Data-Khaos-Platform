<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { MergeView } from '@codemirror/merge'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers } from '@codemirror/view'
import { sql } from '@codemirror/lang-sql'
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'

const props = defineProps<{ original: string; draft: string; version: number }>()
const host = ref<HTMLElement>()
let merge: MergeView | undefined

function render() {
  merge?.destroy()
  if (!host.value) return
  const extensions = [
    lineNumbers(), sql(), syntaxHighlighting(defaultHighlightStyle),
    EditorState.readOnly.of(true), EditorView.editable.of(false),
  ]
  merge = new MergeView({
    parent: host.value,
    a: { doc: props.original, extensions },
    b: { doc: props.draft, extensions },
    collapseUnchanged: { margin: 3, minSize: 8 },
  })
}

onMounted(render)
watch(() => [props.original, props.draft], render)
onBeforeUnmount(() => merge?.destroy())
</script>

<template>
  <div class="diff">
    <div class="diff-labels"><span>历史版本 v{{ version }}</span><span>当前工作稿</span></div>
    <div v-if="original === draft" class="diff-empty">两边的 SQL 内容相同。</div>
    <div ref="host" class="diff-code dk-mono" aria-label="SQL 差异对比"></div>
  </div>
</template>

<style scoped>
.diff { display: flex; min-height: 0; height: 100%; flex-direction: column; }
.diff-labels { display: grid; grid-template-columns: 1fr 1fr; background: var(--dk-sidebar); border-bottom: 1px solid var(--dk-border); }
.diff-labels span, .diff-empty { padding: 8px 12px; font-size: 12px; color: var(--dk-muted); }
.diff-labels span + span { border-left: 1px solid var(--dk-border); }
.diff-code { flex: 1; min-height: 0; overflow: auto; }
.diff-code :deep(.cm-mergeView) { min-height: 100%; font-size: 13px; line-height: 1.65; }
</style>
