import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { CATEGORIES, SYSTEM_CATEGORY, type CategoryDef } from '@/modules/registry'
import { getModuleConfigList, getVisibleModules, saveModuleConfig } from '@/api/moduleConfig'
import type { ModuleDisplayConfig } from '@/types'

/**
 * 门户可插拔模块 Store
 *
 * 职责：
 * - 拉取后端 module_display_config（管理员可配置的全局展示开关）。
 * - 将后端配置与前端注册表(CATEGORIES/SYSTEM_CATEGORY)合并，得到「当前应渲染」的模块结构。
 * - 提供管理员配置页所需的全部配置列表与保存能力。
 */
export const useModuleStore = defineStore('module', () => {
  /** 后端全部模块配置（空 = 尚未加载） */
  const configList = ref<ModuleDisplayConfig[]>([])
  /** 是否已加载 */
  const loaded = ref(false)

  /** 当前用户是否具备模块配置能力（管理员） */
  const canConfig = ref(false)

  /** 后端配置 key -> 可见性 */
  const visibleMap = computed<Record<string, boolean>>(() => {
    const m: Record<string, boolean> = {}
    for (const c of configList.value) {
      m[c.moduleKey] = c.visible !== 0
    }
    return m
  })

  /** 后端配置 key -> 配置项 */
  const configMap = computed<Record<string, ModuleDisplayConfig>>(() => {
    const m: Record<string, ModuleDisplayConfig> = {}
    for (const c of configList.value) m[c.moduleKey] = c
    return m
  })

  /** 合并注册表与配置：六大业务分类（返回新的不可变结构） */
  const categories = computed<CategoryDef[]>(() =>
    CATEGORIES.map((cat) => ({
      ...cat,
      // 仅保留后端允许展示(visible=1)的功能；未在配置表中则回退为可见
      features: cat.features.filter((f) => {
        const cfg = configMap.value[f.key]
        return cfg ? cfg.visible !== 0 : visibleMap.value[f.key] !== false
      }),
    })).filter((cat) => cat.features.length > 0),
  )

  /** 合并注册表与配置：系统管理分类 */
  const systemCategory = computed<CategoryDef>(() => ({
    ...SYSTEM_CATEGORY,
    features: SYSTEM_CATEGORY.features.filter((f) => {
      const cfg = configMap.value[f.key]
      return cfg ? cfg.visible !== 0 : visibleMap.value[f.key] !== false
    }),
  }))

  /** 加载完整配置，以便区分隐藏功能与尚未配置的新功能。 */
  async function loadConfig(capabilityFlags?: string[]) {
    const list = await getModuleConfigList()
    configList.value = list || []
    loaded.value = true
    if (capabilityFlags) canConfig.value = capabilityFlags.includes('module:config')
    return list
  }

  /** 加载仅展示模块（普通用户门户渲染用） */
  async function loadVisible() {
    const list = await getVisibleModules()
    configList.value = list || []
    loaded.value = true
    return list
  }

  /** 管理员批量保存可见性 */
  async function save(updates: Partial<ModuleDisplayConfig>[]) {
    await saveModuleConfig(updates)
    // 保存后刷新本地配置
    configList.value = await getModuleConfigList()
  }

  return {
    configList,
    loaded,
    canConfig,
    visibleMap,
    configMap,
    categories,
    systemCategory,
    loadConfig,
    loadVisible,
    save,
  }
})