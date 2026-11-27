<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ArrowDown, Fold, Expand, Menu, Grid, Bell } from '@element-plus/icons-vue'
import { buildWorkspaces, findWorkspace } from '@/modules/workspaces'
import { CATEGORIES } from '@/modules/registry'
import { projectLocation } from '@/views/realtime/projectContext'
import { getUserPermission } from '@/api/permission'
import { useModuleStore } from '@/stores/module'
import { useUserStore } from '@/stores/user'
import ProductDirectory from './ProductDirectory.vue'
import NotificationInbox from '@/views/notification/NotificationInbox.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const modules = useModuleStore()
const menuOpen = ref(false)
const sidebarCollapsed = ref(false)
const foldedSections = ref(new Set<string>())
const directoryOpen = ref(false)
const inboxOpen = ref(false)

const workspaces = computed(() => buildWorkspaces(
  modules.loaded ? modules.categories : [],
  modules.loaded ? modules.systemCategory : { key: 'system', title: '系统管理', icon: 'Setting', features: [] },
  modules.canConfig,
))
const workspace = computed(() => findWorkspace(workspaces.value, route.path,
  !modules.loaded ? CATEGORIES.find(category => category.key === 'dev')?.features : undefined))
const returnToPortal = computed(() => workspace.value.key === 'realtime' || route.path.startsWith('/jobs'))
function linkLocation(path: string) {
  const project = route.query.project
  return typeof project === 'string' && (path === '/jobs' || path.startsWith('/jobs/')
    || path.startsWith('/realtime/') || path === '/project-workspace') ? projectLocation(path, project) : path
}
const portalHome = computed(() => route.name === 'Dashboard')
const sidebarLabel = computed(() => sidebarCollapsed.value ? '展开侧栏' : '收起侧栏')

function isFolded(title: string) {
  return foldedSections.value.has(`${workspace.value.key}:${title}`)
}

function toggleSection(title: string) {
  const key = `${workspace.value.key}:${title}`
  if (foldedSections.value.has(key)) foldedSections.value.delete(key)
  else foldedSections.value.add(key)
}

function isActive(path: string, match?: string) {
  if (path.includes('?') || route.path === '/metadata/lineage') {
    const target = router.resolve(path)
    return target.path === route.path && target.query.mode === route.query.mode
  }
  if (path === '/jobs/offline' && (route.path === '/workflow/list' || route.path.startsWith('/workflow/edit/'))) return true
  return path === route.path || Boolean(match && route.path.startsWith(match))
}

watch(() => route.fullPath, () => {
  menuOpen.value = false
  directoryOpen.value = false
})
watch(() => user.userInfo?.id, async id => {
  modules.canConfig = false
  try {
    await modules.loadConfig()
  } catch {
    // The shared request handler reports the error. The core links remain usable.
  }
  if (!id) return
  try {
    const permission = await getUserPermission(id)
    if (user.userInfo?.id === id) {
      modules.canConfig = permission?.capabilityFlags?.includes('module:config') ?? false
    }
  } catch {
    // Keep privileged actions unavailable if capability lookup fails.
  }
}, { immediate: true })

async function logout() {
  await user.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="dk-paas shell" :class="{ 'sidebar-collapsed': sidebarCollapsed || portalHome }">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside v-if="!portalHome" id="platform-navigation" class="sidebar" :class="{ 'is-open': menuOpen }">
      <nav :aria-label="`${workspace.title}菜单`">
        <section v-for="(section, index) in workspace.sections" :key="`${workspace.key}:${section.title}`" class="nav-group">
          <h2>
            <button type="button" class="section-trigger" :aria-expanded="!isFolded(section.title)"
              :aria-controls="`workspace-menu-${index}`" @click="toggleSection(section.title)">
              <span>{{ section.title }}</span><ArrowDown aria-hidden="true" />
            </button>
          </h2>
          <div :id="`workspace-menu-${index}`" v-show="!isFolded(section.title)">
            <RouterLink v-for="link in section.links" :key="link.path" :to="linkLocation(link.path)"
              :class="{ current: isActive(link.path, link.match) }" @click="menuOpen = false">
              <component :is="link.icon" class="nav-icon" aria-hidden="true" />{{ link.title }}
            </RouterLink>
          </div>
        </section>
      </nav>
      <footer>© 2026 dkailab</footer>
    </aside>
    <header class="topbar">
      <div class="context">
        <button type="button" class="portal-trigger" aria-label="产品服务" :aria-expanded="directoryOpen"
          aria-haspopup="dialog" @click="directoryOpen = true">
          <span class="brand-icon"><Grid aria-hidden="true" /></span>
          <span>产品服务</span><ArrowDown class="portal-chevron" aria-hidden="true" />
        </button>
        <RouterLink to="/dashboard" class="portal-brand" :aria-label="returnToPortal ? '返回数据门户' : '数据门户'">
          {{ returnToPortal ? '返回门户' : 'Data Khaos' }}
        </RouterLink>
        <button v-if="!portalHome" class="sidebar-toggle" type="button" :aria-label="sidebarLabel" :title="sidebarLabel"
          aria-controls="platform-navigation" :aria-expanded="!sidebarCollapsed" @click="sidebarCollapsed = !sidebarCollapsed">
          <component :is="sidebarCollapsed ? Expand : Fold" aria-hidden="true" />
        </button>
        <button v-if="!portalHome" class="menu-toggle" type="button" aria-label="切换平台导航" aria-controls="platform-navigation"
          :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Menu aria-hidden="true" /></button>
        <strong v-if="!portalHome" class="page-context">{{ workspace.title }}</strong>
      </div>
      <div class="account-actions">
        <button type="button" class="inbox-trigger" aria-label="站内消息" title="站内消息" aria-haspopup="dialog"
          :aria-expanded="inboxOpen" @click="inboxOpen = true"><Bell aria-hidden="true" /></button>
        <el-dropdown trigger="click" @command="logout">
          <button type="button" class="account">{{ user.userInfo?.realName || user.userInfo?.username || '账户' }}</button>
          <template #dropdown>
            <el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <NotificationInbox v-if="inboxOpen" @close="inboxOpen = false" />
    <ProductDirectory v-model="directoryOpen" :workspaces="workspaces" :current="workspace.key" />
    <div class="workspace">
      <main id="main-content" class="workspace-content" :class="{ workbench: route.path === '/query/onesql' || route.name === 'JobOneSql' || portalHome }" tabindex="-1"><RouterView /></main>
    </div>
  </div>
</template>

<style scoped>
.shell { display: grid; grid-template-columns: 214px minmax(0, 1fr); grid-template-rows: 57px 1fr; min-height: 100dvh; background: var(--dk-canvas); }
.shell.sidebar-collapsed { grid-template-columns: 0 minmax(0, 1fr); }
.sidebar-collapsed .sidebar { display: none; }
.workspace { grid-column: 2; grid-row: 2; min-width: 0; }
.sidebar { grid-row: 2; grid-column: 1; position: sticky; top: 57px; display: flex; flex-direction: column; height: calc(100dvh - 57px); box-sizing: border-box; gap: 18px; border-right: 1px solid var(--dk-border); background: var(--dk-sidebar); padding: 18px 14px 10px; }
.sidebar nav { overflow: auto; min-height: 0; padding: 3px; }
.brand-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: var(--dk-radius-control); background: var(--dk-primary); color: white; }
.brand-icon svg { width: 18px; height: 18px; }
.portal-trigger { display: flex; gap: 8px; align-items: center; white-space: nowrap; padding: 3px 7px 3px 3px; border: 1px solid transparent; border-radius: var(--dk-radius-control); background: transparent; color: var(--dk-ink); cursor: pointer; font: inherit; font-weight: 650; }
.portal-trigger:hover, .portal-trigger[aria-expanded="true"] { background: var(--dk-primary-soft); color: var(--dk-primary); }
.portal-chevron { width: 14px; height: 14px; }
.nav-icon { width: 16px; height: 16px; flex: none; }
.portal-brand { margin-left: 4px; padding-left: 12px; border-left: 1px solid var(--dk-border); color: var(--dk-ink); text-decoration: none; font-size: 17px; font-weight: 700; white-space: nowrap; }
.sidebar nav a { display: flex; gap: 8px; align-items: center; min-height: 32px; box-sizing: border-box; padding: 5px 8px; border-radius: var(--dk-radius-control); text-decoration: none; color: var(--dk-sidebar-text); font-size: 13px; }
.sidebar nav a:hover, .sidebar nav a.current { color: var(--dk-primary); background: var(--dk-sidebar-selected); }
.sidebar-toggle svg, .menu-toggle svg { width: 16px; height: 16px; }
.nav-group + .nav-group { margin-top: 12px; }
.nav-group h2 { margin: 0; }
.section-trigger { display: flex; justify-content: space-between; align-items: center; gap: 8px; cursor: pointer; width: 100%; padding: 5px 8px; border: 0; background: transparent; color: var(--dk-muted); font: 650 12px/1.5 var(--dk-font); }
.section-trigger svg { width: 12px; height: 12px; }
.section-trigger[aria-expanded="false"] svg { transform: rotate(-90deg); }
.sidebar footer { color: var(--dk-muted); border-top: 1px solid var(--dk-border); padding: 10px 5px 0; margin-top: auto; font-size: 11px; }
.topbar { grid-column: 1 / -1; grid-row: 1; position: sticky; top: 0; z-index: 11; display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 56px; padding-inline: clamp(10px, 2vw, 24px); border-bottom: 1px solid var(--dk-border); background: var(--dk-surface); }
.account-actions { display: flex; align-items: center; gap: var(--dk-space-2); min-width: 0; }
.inbox-trigger { display: grid; flex: none; place-items: center; width: 32px; height: 32px; border: 0; border-radius: var(--dk-radius-control); background: transparent; color: var(--dk-ink); cursor: pointer; }
.inbox-trigger svg { width: 18px; height: 18px; }
.inbox-trigger:hover { color: var(--dk-primary); background: var(--dk-primary-soft); }
.account { max-width: min(120px, 24vw); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.context { display: flex; align-items: center; gap: 9px; }
.page-context { margin-left: 4px; padding-left: 12px; border-left: 1px solid var(--dk-border); font-weight: 550; color: var(--dk-muted); }
.account, .menu-toggle, .sidebar-toggle { font: inherit; background: var(--dk-surface); color: var(--dk-ink); border: 1px solid var(--dk-border); border-radius: var(--dk-radius-control); padding: 5px 10px; cursor: pointer; }
.menu-toggle { display: none; }
.sidebar-toggle { display: inline-grid; place-items: center; }
.workspace-content { margin: auto; padding: clamp(12px, 2.5vw, 28px); min-width: 0; }
.workspace-content.workbench { padding: 0; }
.skip-link { position: fixed; top: 8px; left: 8px; z-index: 30; padding: 8px; background: white; transform: translateY(-150%); }
.skip-link:focus { transform: none; }
@media (max-width: 760px) {
  .shell { display: block; }
  .topbar { position: sticky; top: 0; z-index: 11; }
  .sidebar { display: none; }
  .sidebar.is-open, .sidebar-collapsed .sidebar.is-open { display: flex; position: fixed; top: 57px; bottom: 0; height: auto; width: min(214px, 84vw); z-index: 10; box-shadow: 4px 3px 20px #1f353528; }
  .sidebar-toggle { display: none; }
  .menu-toggle { display: inline-grid; place-items: center; }
  .page-context { display: none; }
  .portal-brand { font-size: 14px; padding-left: 7px; margin-left: 0; }
  .context { gap: 6px; }
}
@media (max-width: 420px) {
  .portal-trigger > span:not(.brand-icon) { display: none; }
  .account-actions { gap: 4px; }
}
</style>
