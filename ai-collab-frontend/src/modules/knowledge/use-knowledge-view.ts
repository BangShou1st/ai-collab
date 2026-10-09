import { computed, nextTick, onUnmounted, ref, watch, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiContractError, showApiError } from '../../api/api-result'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { knowledgeApi } from './knowledge-api'
import type {
  KnowledgeCitation,
  KnowledgeSession,
  KnowledgeSessionDetail,
} from './types'

export interface StreamingMessage {
  role: 'ASSISTANT'
  content: string
  citations: KnowledgeCitation[]
  streaming: boolean
}

export type KnowledgeFeedbackMap = Record<
  string,
  { myFeedback: boolean | null; helpfulCount: number; unhelpfulCount: number }
>

/**
 * 知识问答视图的状态与业务逻辑。
 * 从 KnowledgeView.vue 下沉：会话生命周期、流式问答、引用与反馈都在这里，
 * 视图层只负责渲染与编排。
 */
export function useKnowledgeView() {
  const route = useRoute()
  const projectId = computed(() =>
    typeof route.params.projectId === 'string' ? route.params.projectId : '',
  )
  const project = ref<Project | null>(null)
  const sessions = ref<KnowledgeSession[]>([])
  const detail = ref<KnowledgeSessionDetail | null>(null)
  const documents = ref<ProjectDocument[]>([])
  const selectedSessionId = ref('')
  const selectedDocumentIds = ref<string[]>([])
  const question = ref('')
  const loading = ref(true)
  const creating = ref(false)
  const submitting = ref(false)
  const deletingId = ref('')
  const citationDrawer = ref(false)
  const selectedCitation = ref<KnowledgeCitation | null>(null)
  const messageArea = ref<HTMLElement | null>(null)
  const editingSessionId = ref('')
  const editingSessionTitle = ref('')
  const streamingMessage = ref<StreamingMessage | null>(null)
  const feedbackMap = ref<KnowledgeFeedbackMap>({})
  const feedbackSubmittingIds = ref<string[]>([])
  let active = true
  let projectGeneration = 0
  let currentAbortController: AbortController | null = null

  const readyDocuments = computed(() => documents.value.filter(item => item.status === 'READY'))
  const questionLength = computed(() => Array.from(question.value).length)
  const canSubmit = computed(() =>
    Boolean(selectedSessionId.value && question.value.trim())
    && !submitting.value
    && !streamingMessage.value
    && questionLength.value <= 2000,
  )

  watch(question, (value) => {
    const codePoints = Array.from(value)
    if (codePoints.length > 2000) question.value = codePoints.slice(0, 2000).join('')
  })

  function isCurrentProject(targetProjectId: string, generation: number): boolean {
    return active
      && targetProjectId.length > 0
      && projectId.value === targetProjectId
      && projectGeneration === generation
  }

  function resetProjectState(): void {
    project.value = null
    sessions.value = []
    detail.value = null
    documents.value = []
    selectedSessionId.value = ''
    selectedDocumentIds.value = []
    question.value = ''
    creating.value = false
    submitting.value = false
    deletingId.value = ''
    citationDrawer.value = false
    selectedCitation.value = null
    streamingMessage.value = null
    feedbackMap.value = {}
    if (currentAbortController) {
      currentAbortController.abort()
      currentAbortController = null
    }
  }

  async function load(targetProjectId: string, generation: number): Promise<void> {
    loading.value = true
    try {
      const [projectResult, sessionResult, documentResult] = await Promise.allSettled([
        projectApi.get(targetProjectId),
        knowledgeApi.listSessions(targetProjectId),
        documentApi.list(targetProjectId),
      ])
      if (!isCurrentProject(targetProjectId, generation)) return
      if (projectResult.status === 'fulfilled') {
        project.value = projectResult.value.data
      } else {
        showApiError(projectResult.reason, '项目信息加载')
      }
      if (sessionResult.status === 'fulfilled') {
        sessions.value = sessionResult.value.data
      } else {
        sessions.value = []
        showApiError(sessionResult.reason, '问答会话列表加载')
      }
      if (documentResult.status === 'fulfilled') {
        documents.value = documentResult.value.data
      } else {
        documents.value = []
        showApiError(documentResult.reason, '参考文档加载')
      }
      selectedDocumentIds.value = selectedDocumentIds.value
        .filter(id => readyDocuments.value.some(document => document.id === id))
      if (!selectedSessionId.value && sessions.value.length) {
        selectedSessionId.value = sessions.value[0].id
        await loadDetail(sessions.value[0].id, targetProjectId, generation)
      } else if (selectedSessionId.value) {
        const exists = sessions.value.some(session => session.id === selectedSessionId.value)
        if (exists) await loadDetail(selectedSessionId.value, targetProjectId, generation)
        else {
          selectedSessionId.value = ''
          detail.value = null
        }
      }
    } finally {
      if (isCurrentProject(targetProjectId, generation)) loading.value = false
    }
  }

  async function loadSessions(
    targetProjectId = projectId.value,
    generation = projectGeneration,
  ): Promise<KnowledgeSession[] | null> {
    const result = await knowledgeApi.listSessions(targetProjectId)
    if (!isCurrentProject(targetProjectId, generation)) return null
    sessions.value = result.data
    return result.data
  }

  async function loadDetail(
    sessionId: string,
    targetProjectId = projectId.value,
    generation = projectGeneration,
  ): Promise<void> {
    const result = await knowledgeApi.getSession(targetProjectId, sessionId)
    if (!isCurrentProject(targetProjectId, generation)
        || selectedSessionId.value !== sessionId) return
    detail.value = result.data
    // 加载反馈状态
    await loadFeedbackForMessages(result.data.messages.filter(m => m.role === 'ASSISTANT'))
    await nextTick()
    if (isCurrentProject(targetProjectId, generation)
        && selectedSessionId.value === sessionId) {
      messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight })
    }
  }

  async function selectSession(sessionId: string): Promise<void> {
    if (submitting.value || streamingMessage.value || (sessionId === selectedSessionId.value && detail.value)) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    selectedSessionId.value = sessionId
    detail.value = null
    try {
      await loadDetail(sessionId, targetProjectId, generation)
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答会话详情加载')
      }
    }
  }

  async function createSession(): Promise<void> {
    if (creating.value || !projectId.value) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    creating.value = true
    try {
      const result = await knowledgeApi.createSession(targetProjectId)
      if (!isCurrentProject(targetProjectId, generation)) return
      const refreshed = await loadSessions(targetProjectId, generation)
      if (!refreshed || !isCurrentProject(targetProjectId, generation)) return
      selectedSessionId.value = result.data.id
      await loadDetail(result.data.id, targetProjectId, generation)
      if (isCurrentProject(targetProjectId, generation)) ElMessage.success('新会话已创建')
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答会话创建')
      }
    } finally {
      if (isCurrentProject(targetProjectId, generation)) creating.value = false
    }
  }

  async function renameSession(session: KnowledgeSession, newTitle: string): Promise<void> {
    if (!projectId.value || !newTitle.trim()) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    try {
      const result = await knowledgeApi.renameSession(targetProjectId, session.id, newTitle.trim())
      if (!isCurrentProject(targetProjectId, generation)) return
      // 更新本地会话列表
      const index = sessions.value.findIndex(s => s.id === session.id)
      if (index !== -1) {
        sessions.value[index] = { ...sessions.value[index], title: newTitle.trim() }
      }
      // 如果是当前选中的会话，也更新详情
      if (detail.value && detail.value.session.id === session.id) {
        detail.value = { ...detail.value, session: { ...detail.value.session, title: newTitle.trim() } }
      }
      ElMessage.success('会话已重命名')
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答会话重命名')
      }
    }
  }

  function startEditSession(session: KnowledgeSession): void {
    editingSessionId.value = session.id
    editingSessionTitle.value = session.title
  }

  function cancelEditSession(): void {
    editingSessionId.value = ''
    editingSessionTitle.value = ''
  }

  async function saveSessionTitle(session: KnowledgeSession): Promise<void> {
    if (editingSessionTitle.value.trim()) {
      await renameSession(session, editingSessionTitle.value)
    }
    cancelEditSession()
  }

  async function removeSession(session: KnowledgeSession): Promise<void> {
    if (deletingId.value || submitting.value || !projectId.value) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    const deletingCurrent = session.id === selectedSessionId.value
    const previousSelectedId = selectedSessionId.value
    try {
      await ElMessageBox.confirm(
        `删除会话“${session.title}”及其全部消息？此操作无法恢复。`,
        '删除问答会话',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
      if (!isCurrentProject(targetProjectId, generation)) return
      deletingId.value = session.id
      await knowledgeApi.deleteSession(targetProjectId, session.id)
      if (!isCurrentProject(targetProjectId, generation)) return
      const refreshed = await loadSessions(targetProjectId, generation)
      if (!refreshed || !isCurrentProject(targetProjectId, generation)) return

      if (deletingCurrent) {
        const nextSession = refreshed[0]
        selectedSessionId.value = nextSession?.id ?? ''
        detail.value = null
        if (nextSession) await loadDetail(nextSession.id, targetProjectId, generation)
      } else if (refreshed.some(item => item.id === previousSelectedId)) {
        selectedSessionId.value = previousSelectedId
      } else {
        const nextSession = refreshed[0]
        selectedSessionId.value = nextSession?.id ?? ''
        detail.value = null
        if (nextSession) await loadDetail(nextSession.id, targetProjectId, generation)
      }
      if (isCurrentProject(targetProjectId, generation)) ElMessage.success('会话已删除')
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答会话删除')
      }
    } finally {
      if (isCurrentProject(targetProjectId, generation)) deletingId.value = ''
    }
  }

  async function submitQuestion(): Promise<void> {
    const normalized = question.value.trim()
    if (!canSubmit.value || !normalized || !projectId.value) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    const targetSessionId = selectedSessionId.value
    submitting.value = true

    // 初始化流式消息
    streamingMessage.value = {
      role: 'ASSISTANT',
      content: '',
      citations: [],
      streaming: true,
    }

    const abortController = new AbortController()
    currentAbortController = abortController

    try {
      await knowledgeApi.askStream(
        targetProjectId, targetSessionId, normalized, selectedDocumentIds.value,
        {
          onToken: (text) => {
            if (!isCurrentProject(targetProjectId, generation)) return
            if (streamingMessage.value) {
              streamingMessage.value.content += text
            }
          },
          onCitations: (citations) => {
            if (!isCurrentProject(targetProjectId, generation)) return
            if (streamingMessage.value) {
              streamingMessage.value.citations = citations
            }
          },
          onDone: (_messageId) => {
            // 流式完成，清除流式状态
            streamingMessage.value = null
          },
          onError: (code, message) => {
            if (isCurrentProject(targetProjectId, generation)) {
              showApiError(new ApiContractError(message), '知识问答')
              streamingMessage.value = null
            }
          },
        },
        abortController.signal,
      )

      if (!isCurrentProject(targetProjectId, generation)
          || selectedSessionId.value !== targetSessionId) return
      question.value = ''
      await Promise.all([
        loadDetail(targetSessionId, targetProjectId, generation),
        loadSessions(targetProjectId, generation),
      ])

      // 如果是第一次提问，根据问题内容自动更新会话标题
      const currentSession = sessions.value.find(s => s.id === targetSessionId)
      if (currentSession && currentSession.title === '新会话') {
        const newTitle = normalized.length > 20 ? normalized.substring(0, 20) + '...' : normalized
        await renameSession(currentSession, newTitle)
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        // 用户取消，不显示错误
        streamingMessage.value = null
      } else if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '知识问答')
        streamingMessage.value = null
      }
    } finally {
      currentAbortController = null
      if (isCurrentProject(targetProjectId, generation)) submitting.value = false
    }
  }

  function cancelStream(): void {
    if (currentAbortController) {
      currentAbortController.abort()
      currentAbortController = null
    }
  }

  function onQuestionKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault()
      void submitQuestion()
    }
  }

  function openCitation(citation: KnowledgeCitation): void {
    selectedCitation.value = citation
    citationDrawer.value = true
  }

  async function downloadCitation(): Promise<void> {
    if (!selectedCitation.value || !projectId.value) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    const citation = selectedCitation.value
    try {
      const result = await documentApi.downloadUrl(targetProjectId, citation.documentId)
      if (isCurrentProject(targetProjectId, generation)) window.location.assign(result.data.url)
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '引用文档下载')
      }
    }
  }

  async function submitFeedback(messageId: string, helpful: boolean): Promise<void> {
    if (!projectId.value || feedbackSubmittingIds.value.includes(messageId)) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    feedbackSubmittingIds.value = [...feedbackSubmittingIds.value, messageId]
    try {
      const result = await knowledgeApi.submitFeedback(targetProjectId, messageId, helpful)
      if (!isCurrentProject(targetProjectId, generation)) return
      feedbackMap.value[messageId] = result.data
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答反馈提交')
      }
    } finally {
      feedbackSubmittingIds.value = feedbackSubmittingIds.value.filter(id => id !== messageId)
    }
  }

  async function removeFeedback(messageId: string): Promise<void> {
    if (!projectId.value || feedbackSubmittingIds.value.includes(messageId)) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    feedbackSubmittingIds.value = [...feedbackSubmittingIds.value, messageId]
    try {
      const result = await knowledgeApi.removeFeedback(targetProjectId, messageId)
      if (!isCurrentProject(targetProjectId, generation)) return
      feedbackMap.value[messageId] = result.data
    } catch (error) {
      if (isCurrentProject(targetProjectId, generation)) {
        showApiError(error, '问答反馈撤销')
      }
    } finally {
      feedbackSubmittingIds.value = feedbackSubmittingIds.value.filter(id => id !== messageId)
    }
  }

  async function toggleFeedback(messageId: string, helpful: boolean): Promise<void> {
    if (feedbackMap.value[messageId]?.myFeedback === helpful) {
      await removeFeedback(messageId)
      return
    }
    await submitFeedback(messageId, helpful)
  }

  async function loadFeedbackForMessages(messages: Array<{ id: string }>): Promise<void> {
    if (!projectId.value || !messages.length) return
    const targetProjectId = projectId.value
    const generation = projectGeneration
    let firstError: unknown
    for (const msg of messages) {
      if (feedbackMap.value[msg.id]) continue
      try {
        const result = await knowledgeApi.getFeedback(targetProjectId, msg.id)
        if (!isCurrentProject(targetProjectId, generation)) return
        feedbackMap.value[msg.id] = result.data
      } catch (error) {
        firstError ??= error
      }
    }
    if (firstError && isCurrentProject(targetProjectId, generation)) {
      showApiError(firstError, '问答反馈加载')
    }
  }

  watch(projectId, (nextProjectId) => {
    const generation = ++projectGeneration
    resetProjectState()
    if (!nextProjectId) {
      loading.value = false
      return
    }
    void load(nextProjectId, generation)
  }, { immediate: true })

  onUnmounted(() => {
    active = false
    projectGeneration++
    if (currentAbortController) {
      currentAbortController.abort()
      currentAbortController = null
    }
  })

  return {
    projectId, project, sessions, detail, documents,
    selectedSessionId, selectedDocumentIds, question,
    loading, creating, submitting, deletingId,
    citationDrawer, selectedCitation,
    messageArea: messageArea as Ref<HTMLElement | null>,
    editingSessionId, editingSessionTitle, streamingMessage,
    feedbackMap, feedbackSubmittingIds,
    readyDocuments, questionLength, canSubmit,
    selectSession, createSession, startEditSession, cancelEditSession, saveSessionTitle,
    removeSession, submitQuestion, cancelStream, onQuestionKeydown,
    openCitation, downloadCitation,
    submitFeedback, removeFeedback, toggleFeedback,
  }
}
