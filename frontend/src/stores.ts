import { defineStore } from 'pinia'
import { api } from './api'

/** 全局登录与项目上下文；项目选择影响数据源、流程与看板的过滤。 */
export const useAppStore = defineStore('app', {
  state: () => ({
    user: JSON.parse(localStorage.getItem('tls_user') || 'null') as any,
    projects: [] as any[],
    projectId: localStorage.getItem('tls_project') || '',
    sources: [] as any[],
    variables: [] as any[],
    nodeDefinitions: [] as any[]
  }),
  getters: {
    currentProject: state => state.projects.find(p => p.id === state.projectId),
    flowsOfProject: state => (projectId: string) =>
      state.projects.find(p => p.id === projectId)?.flowCount || 0
  },
  actions: {
    async login(username: string, password: string) {
      const data: any = await api.login(username, password)
      localStorage.setItem('tls_token', data.token)
      localStorage.setItem('tls_user', JSON.stringify(data))
      this.user = data
    },
    async loadProjects() {
      this.projects = await api.projects() as any[]
      if (!this.projectId || !this.projects.some(p => p.id === this.projectId)) {
        this.projectId = this.projects[0]?.id || ''
        localStorage.setItem('tls_project', this.projectId)
      }
    },
    setProject(id: string) {
      this.projectId = id
      localStorage.setItem('tls_project', id)
    },
    async loadSources() {
      this.sources = await api.sources() as any[]
    },
    async loadVariables() {
      this.variables = await api.variables() as any[]
    },
    async loadNodeDefinitions() {
      this.nodeDefinitions = await api.nodeDefinitions() as any[]
    },
    logout() {
      localStorage.removeItem('tls_token')
      localStorage.removeItem('tls_user')
      this.user = null
    }
  }
})
