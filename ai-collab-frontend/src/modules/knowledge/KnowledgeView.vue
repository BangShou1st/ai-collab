<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiContractError, showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { knowledgeApi } from './knowledge-api'
import { isFeedbackEligible } from './knowledge-feedback'
import type {
  KnowledgeCitation,
  KnowledgeSession,
  KnowledgeSessionDetail,
} from './types'

interface StreamingMessage {
  role: 'ASSISTANT'
  content: string
  citations: KnowledgeCitation[]
  streaming: boolean
}

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
const feedbackMap = ref<Record<string, { myFeedback: boolean | null; helpfulCount: number; unhelpfulCount: number }>>({})
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

function markdown(content: string): string {
  const rendered = marked.parse(content, { async: false, breaks: true }) as string
  return DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['iframe', 'object', 'embed', 'form', 'img', 'a'],
    FORBID_ATTR: ['style'],
  })
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function similarity(value: number): string {
  return `${Math.max(0, Math.min(1, value)) * 100}`.replace(/(\.\d).*$/, '$1') + '%'
}

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
</script>

<template>
  <main class="workspace-page knowledge-page">
    <PageHeader
      eyebrow="项目资料检索"
      title="知识问答"
      :context="project?.name"
    />

    <section v-loading="loading" class="knowledge-layout">
      <aside class="session-panel" aria-label="我的问答会话">
        <div class="panel-heading">
          <div>
            <strong>我的会话</strong>
            <small>{{ sessions.length }} 个会话</small>
          </div>
          <el-button type="primary" size="small" :loading="creating" @click="createSession">
            新建会话
          </el-button>
        </div>
        <div v-if="sessions.length" class="session-list">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === selectedSessionId }"
            role="button"
            tabindex="0"
            @click="selectSession(session.id)"
            @keydown.enter="selectSession(session.id)"
            @keydown.space.prevent="selectSession(session.id)"
          >
            <span>
              <strong v-if="editingSessionId !== session.id">{{ session.title }}</strong>
              <el-input
                v-else
                v-model="editingSessionTitle"
                size="small"
                @click.stop
                @keyup.enter="saveSessionTitle(session)"
                @keyup.escape="cancelEditSession"
              />
              <small>{{ formatTime(session.updatedAt) }}</small>
            </span>
            <div class="session-actions">
              <el-button
                v-if="editingSessionId !== session.id"
                text
                type="primary"
                size="small"
                :aria-label="`重命名会话 ${session.title}`"
                @click.stop="startEditSession(session)"
              >
                重命名
              </el-button>
              <el-button
                text
                type="danger"
                :loading="deletingId === session.id"
                :aria-label="`删除会话 ${session.title}`"
                @click.stop="removeSession(session)"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="还没有问答会话">
          <el-button type="primary" :loading="creating" @click="createSession">创建会话</el-button>
        </el-empty>
      </aside>

      <section class="conversation-panel">
        <template v-if="detail">
          <header class="conversation-heading">
            <div>
              <strong>{{ detail.session.title }}</strong>
            </div>
            <el-select
              v-model="selectedDocumentIds"
              multiple
              clearable
              collapse-tags
              collapse-tags-tooltip
              :max-collapse-tags="2"
              placeholder="不选择则检索全部 READY 文档"
              aria-label="选择检索文档"
              class="document-selector"
            >
              <el-option
                v-for="document in readyDocuments"
                :key="document.id"
                :label="document.displayName"
                :value="document.id"
                :disabled="selectedDocumentIds.length >= 20
                  && !selectedDocumentIds.includes(document.id)"
              />
            </el-select>
          </header>

          <div ref="messageArea" class="message-area" aria-live="polite">
            <el-empty v-if="!detail.messages.length && !streamingMessage" description="输入问题开始基于项目资料问答" />
            <article
              v-for="message in detail.messages"
              :key="message.id"
              class="message"
              :class="message.role.toLowerCase()"
            >
              <div class="message-meta">
                <strong>{{ message.role === 'USER' ? '你' : '知识库助手' }}</strong>
                <span>{{ formatTime(message.createdAt) }}</span>
                <el-tag v-if="message.model" size="small" type="info">{{ message.model }}</el-tag>
              </div>
              <p v-if="message.role === 'USER'" class="user-content">{{ message.content }}</p>
              <div
                v-else
                class="markdown-content"
                v-html="markdown(message.content)"
              />
              <el-alert
                v-if="message.role === 'ASSISTANT' && message.insufficientEvidence"
                title="以上回答基于有限的项目资料，可能不够完整"
                type="info"
                :closable="false"
                show-icon
              />
              <div v-if="message.citations?.length" class="citation-list">
                <button
                  v-for="citation in message.citations"
                  :key="`${message.id}-${citation.rank}`"
                  class="citation-card"
                  type="button"
                  @click="openCitation(citation)"
                >
                  <strong>[S{{ citation.rank }}] {{ citation.filename }}</strong>
                  <span>{{ citation.heading || '未标注标题' }}</span>
                  <small v-if="citation.pageNumber">第 {{ citation.pageNumber }} 页 · </small>
                  <small>相似度 {{ similarity(citation.similarity) }}</small>
                  <p>{{ citation.quote }}</p>
                </button>
              </div>
              <div v-if="isFeedbackEligible(message)" class="feedback-bar">
                <el-button
                  size="small"
                  :type="feedbackMap[message.id]?.myFeedback === true ? 'success' : 'default'"
                  :loading="feedbackSubmittingIds.includes(message.id)"
                  :disabled="feedbackSubmittingIds.includes(message.id)"
                  aria-label="这个回答有用"
                  @click="toggleFeedback(message.id, true)"
                >
                  👍 有用 {{ feedbackMap[message.id]?.helpfulCount ? `(${feedbackMap[message.id].helpfulCount})` : '' }}
                </el-button>
                <el-button
                  size="small"
                  :type="feedbackMap[message.id]?.myFeedback === false ? 'danger' : 'default'"
                  :disabled="feedbackSubmittingIds.includes(message.id)"
                  aria-label="这个回答无用"
                  @click="toggleFeedback(message.id, false)"
                >
                  👎 无用 {{ feedbackMap[message.id]?.unhelpfulCount ? `(${feedbackMap[message.id].unhelpfulCount})` : '' }}
                </el-button>
                <el-button
                  v-if="feedbackMap[message.id]?.myFeedback !== null"
                  size="small"
                  text
                  @click="removeFeedback(message.id)"
                >
                  撤销
                </el-button>
              </div>
            </article>

            <!-- 流式消息 -->
            <article
              v-if="streamingMessage"
              class="message assistant"
            >
              <div class="message-meta">
                <strong>知识库助手</strong>
                <el-tag size="small" type="info">正在回答...</el-tag>
              </div>
              <div
                class="markdown-content"
                v-html="markdown(streamingMessage.content)"
              />
              <div v-if="streamingMessage.citations.length" class="citation-list">
                <button
                  v-for="citation in streamingMessage.citations"
                  :key="`streaming-${citation.rank}`"
                  class="citation-card"
                  type="button"
                  @click="openCitation(citation)"
                >
                  <strong>[S{{ citation.rank }}] {{ citation.filename }}</strong>
                  <span>{{ citation.heading || '未标注标题' }}</span>
                  <small v-if="citation.pageNumber">第 {{ citation.pageNumber }} 页 · </small>
                  <small>相似度 {{ similarity(citation.similarity) }}</small>
                  <p>{{ citation.quote }}</p>
                </button>
              </div>
            </article>
          </div>

          <form class="question-composer" @submit.prevent="submitQuestion">
            <label for="knowledge-question">向项目知识库提问</label>
            <el-input
              id="knowledge-question"
              v-model="question"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="例如：项目提交材料包括哪些内容？"
              @keydown="onQuestionKeydown"
            />
            <div class="composer-footer">
              <span>
                Ctrl / ⌘ + Enter 提交；{{ questionLength }}/2000 字；已选择
                {{ selectedDocumentIds.length }}/20 个文档
              </span>
              <el-button
                v-if="streamingMessage"
                type="danger"
                @click="cancelStream"
              >
                取消回答
              </el-button>
              <el-button
                v-else
                type="primary"
                native-type="submit"
                :loading="submitting"
                :disabled="!canSubmit"
              >
                提交问题
              </el-button>
            </div>
          </form>
        </template>
        <el-empty v-else description="请选择或创建一个问答会话" />
      </section>
    </section>

    <el-drawer v-model="citationDrawer" title="引用来源" size="480px">
      <template v-if="selectedCitation">
        <dl class="citation-detail">
          <dt>来源编号</dt><dd>[S{{ selectedCitation.rank }}]</dd>
          <dt>文件名</dt><dd>{{ selectedCitation.filename }}</dd>
          <dt>标题</dt><dd>{{ selectedCitation.heading || '未标注标题' }}</dd>
          <dt v-if="selectedCitation.pageNumber">页码</dt><dd v-if="selectedCitation.pageNumber">第 {{ selectedCitation.pageNumber }} 页</dd>
          <dt>相似度</dt><dd>{{ similarity(selectedCitation.similarity) }}</dd>
          <dt>引用内容</dt><dd class="quote">{{ selectedCitation.quote }}</dd>
        </dl>
        <el-button type="primary" @click="downloadCitation">下载原文</el-button>
      </template>
    </el-drawer>
  </main>
</template>

<style scoped>
.knowledge-page {
  display: flex;
  flex-direction: column;
  gap: 0;
  height: calc(100dvh - var(--project-shell-offset));
  min-height: 0;
  overflow: hidden;
  padding: 16px 24px;
}
.knowledge-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
}
.session-panel {
  border-right: 1px solid #e4e7ed;
  background: #f8fafc;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-heading, .conversation-heading, .composer-footer {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.panel-heading, .conversation-heading { min-height: 52px; padding: 10px 14px; border-bottom: 1px solid #e4e7ed; flex-shrink: 0; }
.panel-heading div, .conversation-heading div { display: grid; gap: 3px; }
.panel-heading small, .conversation-heading small, .message-meta span, .composer-footer span { color: #64748b; }
.session-list {
  display: grid;
  flex: 0 0 320px;
  align-content: start;
  gap: 4px;
  height: 320px;
  min-height: 0;
  padding: 8px;
  overflow-y: auto;
}
.session-item {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  border: 1px solid transparent; border-radius: 10px; padding: 8px 8px 8px 12px;
  background: transparent; color: inherit; text-align: left; cursor: pointer;
  box-sizing: border-box;
}
.session-item:hover, .session-item.active { background: #fff; border-color: #c7d2fe; }
.session-item > span { min-width: 0; display: grid; gap: 2px; flex: 1; overflow: hidden; }
.session-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.session-item small { color: #64748b; font-size: 11px; }
.session-actions { display: flex; gap: 4px; opacity: 0; transition: opacity 0.2s; flex-shrink: 0; }
.session-item:hover .session-actions { opacity: 1; }
.conversation-panel {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.document-selector { width: min(420px, 48vw); }
.message-area {
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  display: grid;
  align-content: start;
  gap: 14px;
  flex: 1;
}
.message { max-width: min(780px, 92%); border-radius: 14px; padding: 12px 14px; }
.message.user { justify-self: end; background: #eef2ff; }
.message.assistant { justify-self: start; background: #f8fafc; border: 1px solid #e2e8f0; }
.message-meta { display: flex; align-items: center; gap: 9px; margin-bottom: 6px; font-size: 13px; }
.user-content { white-space: pre-wrap; margin: 0; line-height: 1.7; }
.markdown-content { line-height: 1.72; overflow-wrap: anywhere; }
.markdown-content :deep(pre) { overflow-x: auto; padding: 12px; border-radius: 8px; background: #111827; color: #e5e7eb; }
.markdown-content :deep(code) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.citation-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 9px; margin-top: 12px; }
.citation-card {
  display: grid; gap: 5px; border: 1px solid #dbeafe; border-radius: 9px;
  padding: 10px; background: #fff; color: inherit; text-align: left; cursor: pointer;
}
.citation-card:hover { border-color: #6366f1; }
.citation-card span, .citation-card small { color: #64748b; }
.citation-card p { margin: 2px 0 0; display: -webkit-box; overflow: hidden; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.question-composer { border-top: 1px solid #e4e7ed; padding: 12px 16px; display: grid; gap: 8px; flex-shrink: 0; }
.question-composer label { font-weight: 650; }
.composer-footer { font-size: 13px; }
.citation-detail { display: grid; grid-template-columns: 88px 1fr; gap: 12px; }
.citation-detail dt { color: #64748b; }
.citation-detail dd { margin: 0; overflow-wrap: anywhere; }
.citation-detail .quote { white-space: pre-wrap; line-height: 1.7; }
.feedback-bar { display: flex; align-items: center; gap: 6px; margin-top: 10px; }
.feedback-label { color: #64748b; font-size: 12px; }
@media (max-width: 900px) {
  .knowledge-page {
    height: auto;
    min-height: calc(100dvh - var(--project-shell-offset));
    overflow: visible;
  }
  .knowledge-layout { grid-template-columns: 1fr; height: auto; min-height: calc(100vh - 180px); }
  .session-panel { border-right: 0; border-bottom: 1px solid #e4e7ed; }
  .session-list { flex-basis: 220px; height: 220px; }
  .document-selector { width: 100%; }
  .conversation-heading { align-items: stretch; flex-direction: column; }
}
</style>
