import { defineStore } from 'pinia'
import { projectApi } from '../modules/project/project-api'
import { normalizeApiError } from '../api/api-result'
import type { Project, ProjectRole } from '../modules/project/types'

export const useProjectContextStore = defineStore('projectContext', {
  state: () => ({
    currentProjectId: null as string | null,
    project: null as Project | null,
    accessDeniedProjectId: null as string | null,
    loading: false,
  }),

  getters: {
    currentUserRole: (state): ProjectRole | null => state.project?.role ?? null,
    isAdminOrOwner: (state): boolean => {
      const role = state.project?.role
      return role === 'OWNER' || role === 'ADMIN'
    },
  },

  actions: {
    async loadProject(projectId: string): Promise<void> {
      if (this.currentProjectId === projectId && this.project
          || this.accessDeniedProjectId === projectId) {
        return
      }
      this.currentProjectId = null
      this.project = null
      this.accessDeniedProjectId = null
      this.loading = true
      try {
        this.project = (await projectApi.get(projectId)).data
        this.currentProjectId = projectId
      } catch (error) {
        if (isProjectNotFound(error)) {
          this.accessDeniedProjectId = projectId
          return
        }
        throw error
      } finally {
        this.loading = false
      }
    },

    async refreshProject(projectId: string): Promise<void> {
      this.loading = true
      try {
        this.project = (await projectApi.get(projectId)).data
        this.currentProjectId = projectId
        this.accessDeniedProjectId = null
      } catch (error) {
        if (isProjectNotFound(error)) {
          this.clear()
          this.accessDeniedProjectId = projectId
          return
        }
        throw error
      } finally {
        this.loading = false
      }
    },

    clear(): void {
      this.currentProjectId = null
      this.project = null
      this.accessDeniedProjectId = null
    },
  },
})

function isProjectNotFound(error: unknown): boolean {
  const normalized = normalizeApiError(error)
  return normalized.httpStatus === 404 || normalized.code === 'PROJECT_NOT_FOUND'
}
