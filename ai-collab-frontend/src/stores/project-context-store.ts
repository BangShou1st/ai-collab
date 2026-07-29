import { defineStore } from 'pinia'
import { projectApi } from '../modules/project/project-api'
import type { Project, ProjectRole } from '../modules/project/types'

export const useProjectContextStore = defineStore('projectContext', {
  state: () => ({
    currentProjectId: null as string | null,
    project: null as Project | null,
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
      if (this.currentProjectId === projectId && this.project) {
        return
      }
      this.loading = true
      try {
        this.project = (await projectApi.get(projectId)).data
        this.currentProjectId = projectId
      } finally {
        this.loading = false
      }
    },

    clear(): void {
      this.currentProjectId = null
      this.project = null
    },
  },
})
