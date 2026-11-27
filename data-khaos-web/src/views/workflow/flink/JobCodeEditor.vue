<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Compartment, EditorState } from '@codemirror/state'
import { StreamLanguage, bracketMatching, defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { sql } from '@codemirror/lang-sql'
import { java, scala } from '@codemirror/legacy-modes/mode/clike'
import { python } from '@codemirror/legacy-modes/mode/python'
import { shell } from '@codemirror/legacy-modes/mode/shell'
import { javascript, json } from '@codemirror/legacy-modes/mode/javascript'
import { yaml } from '@codemirror/legacy-modes/mode/yaml'
import { minimalSetup } from 'codemirror'
import { EditorView, keymap, highlightActiveLine } from '@codemirror/view'
import { searchKeymap } from '@codemirror/search'

const parsers = { Python: python, Shell: shell, Java: java, Scala: scala, JavaScript: javascript, JSON: json, YAML: yaml }
const props = defineProps<{ language: 'SQL' | keyof typeof parsers | '纯文本'; label: string; readonly?: boolean }>()
const emit = defineEmits<{ selection: [text: string] }>()
const model = defineModel<string>({ required: true })
const host = ref<HTMLElement>()
const language = new Compartment()
const editable = new Compartment()
let view: EditorView | undefined

function syntax() {
  if (props.language === 'SQL') return sql()
  if (props.language === '纯文本') return []
  return StreamLanguage.define(parsers[props.language])
}

onMounted(() => {
  if (!host.value) return
  view = new EditorView({
    parent: host.value,
    doc: model.value,
    extensions: [
      minimalSetup, highlightActiveLine(), bracketMatching(), keymap.of(searchKeymap),
      syntaxHighlighting(defaultHighlightStyle), language.of(syntax()), editable.of(EditorState.readOnly.of(!!props.readonly)),
      EditorView.contentAttributes.of({ 'aria-label': props.label, spellcheck: 'false' }),
      EditorState.changeFilter.of(transaction => transaction.newDoc.length <= 131072),
      EditorView.updateListener.of(update => {
        if (update.docChanged) model.value = update.state.doc.toString()
        if (update.selectionSet || update.docChanged) {
          const { from, to } = update.state.selection.main
          emit('selection', update.state.sliceDoc(from, to))
        }
      }),
    ],
  })
})

watch(() => props.readonly, value => view?.dispatch({ effects: editable.reconfigure(EditorState.readOnly.of(!!value)) }))
watch(() => props.language, () => view?.dispatch({ effects: language.reconfigure(syntax()) }))
watch(model, value => {
  if (view && view.state.doc.toString() !== value) {
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } })
  }
})

onBeforeUnmount(() => view?.destroy())
</script>

<template>
  <div ref="host" class="job-code-editor dk-mono"></div>
</template>

<style scoped>
.job-code-editor { height: 100%; min-width: 0; min-height: 0; background: var(--dk-surface); }
.job-code-editor :deep(.cm-editor) { height: 100%; font-size: 13px; line-height: 1.7; }
.job-code-editor :deep(.cm-scroller) { overflow: auto; font-family: inherit; }
.job-code-editor :deep(.cm-content) { padding-block: 10px; }
.job-code-editor :deep(.cm-gutters) { background: var(--dk-sidebar); color: var(--dk-muted); border-color: var(--dk-border); }
.job-code-editor :deep(.cm-editor.cm-focused) { outline: 0; }
</style>
