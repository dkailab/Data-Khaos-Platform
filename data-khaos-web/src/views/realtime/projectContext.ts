import { inject, type ComputedRef, type InjectionKey, type Ref } from 'vue'

export interface RealtimeProjectContext {
  project: Ref<string>
  canRun: ComputedRef<boolean>
}

export const realtimeProjectKey: InjectionKey<RealtimeProjectContext> = Symbol('realtimeProject')

export function useRealtimeProject() {
  const context = inject(realtimeProjectKey)
  if (!context) throw new Error('作业工作区项目上下文不可用')
  return context
}

export function projectLocation(path: string, project: string) {
  return { path, query: project ? { project } : {} }
}
