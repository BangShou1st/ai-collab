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
    requestSequence: 0,
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
      if ((this.currentProjectId === projectId && this.project)
          || this.accessDeniedProjectId === projectId) {
        return
      }
      const requestSequence = ++this.requestSequence
      this.currentProjectId = null
      this.project = null
      this.accessDeniedProjectId = null
      this.loading = true
      try {
        const response = await projectApi.get(projectId)
        if (this.requestSequence !== requestSequence) return
        this.project = response.data
        this.currentProjectId = projectId
      } catch (error) {
        if (this.requestSequence !== requestSequence) return
        if (isProjectNotFound(error)) {
          this.accessDeniedProjectId = projectId
          return
        }
        throw error
      } finally {
        if (this.requestSequence === requestSequence) this.loading = false
      }
    },

    async refreshProject(projectId: string): Promise<void> {
      const requestSequence = ++this.requestSequence
      this.loading = true
      try {
        const response = await projectApi.get(projectId)
        if (this.requestSequence !== requestSequence) return
        this.project = response.data
        this.currentProjectId = projectId
        this.accessDeniedProjectId = null
      } catch (error) {
        if (this.requestSequence !== requestSequence) return
        if (isProjectNotFound(error)) {
          this.currentProjectId = null
          this.project = null
          this.accessDeniedProjectId = projectId
          return
        }
        throw error
      } finally {
        if (this.requestSequence === requestSequence) this.loading = false
      }
    },

    clear(): void {
      this.requestSequence += 1
      this.currentProjectId = null
      this.project = null
      this.accessDeniedProjectId = null
      this.loading = false
    },
  },
})

function isProjectNotFound(error: unknown): boolean {
  const normalized = normalizeApiError(error)
  return normalized.httpStatus === 404 || normalized.code === 'PROJECT_NOT_FOUND'
}
