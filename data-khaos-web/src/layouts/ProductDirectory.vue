<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, Close, Grid, House, Search } from '@element-plus/icons-vue'
import { groupProducts, workspaceHome, type Workspace } from '@/modules/workspaces'

const props = defineProps<{ workspaces: Workspace[]; current: string }>()
const open = defineModel<boolean>({ required: true })
const dialog = ref<HTMLDialogElement>()
const searchInput = ref<HTMLInputElement>()
const results = ref<HTMLElement>()
const query = ref('')
const active = ref('')
const groups = computed(() => groupProducts(props.workspaces))
const filtered = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase()
  return groups.value
    .filter(group => !active.value || group.key === active.value)
    .map(group => ({
      ...group,
      workspaces: group.workspaces.flatMap(workspace => {
        const matchesWorkspace = !keyword || [group.title, workspace.title].some(label => label.toLocaleLowerCase().includes(keyword))
        const links = workspace.sections.flatMap(section => section.links)
          .filter(link => matchesWorkspace || link.title.toLocaleLowerCase().includes(keyword))
        return links.length ? [{ workspace, links }] : []
      }),
    })).filter(group => group.workspaces.length)
})

watch(open, async visible => {
  if (!visible) {
    dialog.value?.close()
    return
  }
  query.value = ''
  active.value = ''
  await nextTick()
  if (!open.value) return
  dialog.value?.showModal()
  searchInput.value?.focus()
})

function selectGroup(key: string) {
  active.value = key
  if (results.value) results.value.scrollTop = 0
}
</script>

<template>
  <dialog ref="dialog" class="product-directory" aria-labelledby="product-directory-title" @close="open = false" @click.self="open = false">
    <div class="directory-frame">
      <header class="directory-header">
        <div><span class="directory-brand">Data Khaos</span><h2 id="product-directory-title">产品目录</h2></div>
        <label class="directory-search">
          <Search aria-hidden="true" />
          <input ref="searchInput" v-model="query" type="search" placeholder="搜索产品或功能" aria-label="搜索产品或功能" maxlength="100" />
        </label>
        <button type="button" class="close-directory" aria-label="关闭产品目录" @click="open = false"><Close /></button>
      </header>
      <div class="directory-body">
        <nav class="directory-rail" aria-label="产品分类">
          <RouterLink to="/dashboard" class="rail-home" @click="open = false"><House aria-hidden="true" />门户首页</RouterLink>
          <button type="button" :aria-pressed="!active" @click="selectGroup('')"><Grid aria-hidden="true" />全部产品</button>
          <button v-for="group in groups" :key="group.key" type="button" :aria-pressed="active === group.key"
            @click="selectGroup(group.key)">{{ group.title }}</button>
        </nav>
        <div ref="results" class="directory-results" aria-label="产品及功能" role="region">
          <div v-if="filtered.length" class="directory-columns">
            <section v-for="group in filtered" :key="group.key">
              <h3>{{ group.title }}</h3>
              <div v-for="{ workspace, links } in group.workspaces" :key="workspace.key" class="directory-product">
                <RouterLink :to="workspaceHome(workspace)" class="product-title" :class="{ current: workspace.key === current }"
                  @click="open = false">
                  <component :is="workspace.icon" aria-hidden="true" />{{ workspace.title }}<ArrowRight class="product-arrow" aria-hidden="true" />
                </RouterLink>
                <RouterLink v-for="link in links" :key="link.path" :to="link.path" class="product-link"
                  @click="open = false">{{ link.title }}</RouterLink>
              </div>
            </section>
          </div>
          <p v-else class="directory-empty">当前分类没有匹配的产品或功能。</p>
        </div>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.product-directory { position: fixed; inset: 57px 0 auto; width: 100%; max-width: none; max-height: calc(100dvh - 57px); margin: 0; padding: 0; border: 0; background: var(--dk-surface); color: var(--dk-ink); box-shadow: 0 14px 48px #203b6833; }
.product-directory::backdrop { background: rgb(21 37 63 / 23%); }
.directory-frame { display: flex; flex-direction: column; max-height: calc(100dvh - 57px); }
.directory-header { flex: none; display: grid; grid-template-columns: 175px minmax(150px, 420px) 1fr; align-items: center; gap: 18px; padding: 15px clamp(16px, 3vw, 32px); border-bottom: 1px solid var(--dk-border); }
.directory-brand { color: var(--dk-muted); font-size: 12px; }
.directory-header h2 { margin: 0; font-size: 19px; }
.directory-search { display: flex; align-items: center; gap: 8px; padding: 0 10px; min-height: 38px; border: 1px solid var(--dk-border); border-radius: var(--dk-radius-control); background: var(--dk-sidebar); color: var(--dk-muted); }
.directory-search:focus-within { outline: 2px solid var(--dk-primary); outline-offset: 2px; }
.directory-search input { width: 100%; padding: 0; border: 0; outline: 0; background: transparent; color: var(--dk-ink); font: inherit; }
.directory-search input:focus-visible { outline: 0; }
.directory-search svg, .close-directory svg, .directory-rail svg, .product-title svg { width: 16px; height: 16px; flex: none; }
.close-directory { justify-self: end; display: grid; place-items: center; width: 32px; height: 32px; padding: 0; cursor: pointer; border: 1px solid var(--dk-border); border-radius: var(--dk-radius-control); background: var(--dk-surface); color: var(--dk-ink); }
.directory-body { display: grid; grid-template-columns: 210px minmax(0, 1fr); min-height: 0; }
.directory-rail { overflow: auto; padding: 16px; background: var(--dk-sidebar); border-right: 1px solid var(--dk-border); }
.directory-rail button, .rail-home { width: 100%; box-sizing: border-box; display: flex; align-items: center; gap: 8px; padding: 8px 10px; margin: 0 0 4px; border: 0; border-radius: var(--dk-radius-control); background: transparent; color: var(--dk-ink); font: inherit; text-align: left; cursor: pointer; text-decoration: none; white-space: nowrap; }
.directory-rail button[aria-pressed="true"], .directory-rail button:hover, .rail-home:hover { color: var(--dk-primary); background: var(--dk-primary-soft); }
.directory-results { min-width: 0; overflow: auto; padding: 22px clamp(16px, 3vw, 36px) 30px; }
.directory-columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px 32px; }
.directory-columns h3 { color: var(--dk-primary); font-size: 16px; margin: 0 0 14px; }
.directory-product + .directory-product { margin-top: 14px; }
.product-title, .product-link { text-decoration: none; border-radius: 6px; }
.product-title { display: flex; align-items: center; gap: 8px; padding: 6px; color: var(--dk-ink); font-weight: 650; }
.product-title svg { color: var(--dk-primary); }
.product-arrow { margin-left: auto; }
.product-link { display: block; padding: 4px 8px 4px 30px; color: var(--dk-secondary); font-size: 13px; }
.product-title.current, .product-title:hover, .product-link:hover { background: var(--dk-primary-soft); color: var(--dk-primary); }
.directory-empty { margin: 0; color: var(--dk-muted); }
@media (max-width: 980px) { .directory-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 650px) {
  .directory-header { grid-template-columns: minmax(0,1fr) 32px; gap: 10px; }
  .directory-search { grid-column: 1 / -1; grid-row: 2; }
  .close-directory { grid-column: 2; grid-row: 1; }
  .directory-columns { grid-template-columns: 1fr; }
  .directory-body { display: flex; flex-direction: column; }
  .directory-rail { flex: none; display: flex; overflow: auto; border-right: 0; border-bottom: 1px solid var(--dk-border); padding: 6px 12px; }
  .directory-rail button, .rail-home { width: auto; margin: 0; }
}
</style>
