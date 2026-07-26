<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
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
const errorMessage = ref('')
const citationDrawer = ref(false)
const selectedCitation = ref<KnowledgeCitation | null>(null)
const messageArea = ref<HTMLElement | null>(null)
let active = true
let projectGeneration = 0

const readyDocuments = computed(() => documents.value.filter(item => item.status === 'READY'))
const questionLength = computed(() => Array.from(question.value).length)
const canSubmit = computed(() =>
  Boolean(selectedSessionId.value && question.value.trim())
  && !submitting.value
  && questionLength.value <= 1000,
)

watch(question, (value) => {
  const codePoints = Array.from(value)
  if (codePoints.length > 1000) question.value = codePoints.slice(0, 1000).join('')
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
  errorMessage.value = ''
  citationDrawer.value = false
  selectedCitation.value = null
}

async function load(targetProjectId: string, generation: number): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectResult, sessionResult, documentResult] = await Promise.all([
      projectApi.get(targetProjectId),
      knowledgeApi.listSessions(targetProjectId),
      documentApi.list(targetProjectId),
    ])
    if (!isCurrentProject(targetProjectId, generation)) return
    project.value = projectResult.data
    sessions.value = sessionResult.data
    documents.value = documentResult.data
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
  } catch (error) {
    if (isCurrentProject(targetProjectId, generation)) {
      errorMessage.value = normalizeApiError(error).message
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
  await nextTick()
  if (isCurrentProject(targetProjectId, generation)
      && selectedSessionId.value === sessionId) {
    messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight })
  }
}

async function selectSession(sessionId: string): Promise<void> {
  if (submitting.value || sessionId === selectedSessionId.value && detail.value) return
  const targetProjectId = projectId.value
  const generation = projectGeneration
  selectedSessionId.value = sessionId
  detail.value = null
  errorMessage.value = ''
  try {
    await loadDetail(sessionId, targetProjectId, generation)
  } catch (error) {
    if (isCurrentProject(targetProjectId, generation)) {
      errorMessage.value = normalizeApiError(error).message
    }
  }
}

async function createSession(): Promise<void> {
  if (creating.value || !projectId.value) return
  const targetProjectId = projectId.value
  const generation = projectGeneration
  creating.value = true
  errorMessage.value = ''
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
      errorMessage.value = normalizeApiError(error).message
    }
  } finally {
    if (isCurrentProject(targetProjectId, generation)) creating.value = false
  }
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
      errorMessage.value = normalizeApiError(error).message
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
  errorMessage.value = ''
  try {
    await knowledgeApi.ask(
      targetProjectId, targetSessionId, normalized, selectedDocumentIds.value,
    )
    if (!isCurrentProject(targetProjectId, generation)
        || selectedSessionId.value !== targetSessionId) return
    question.value = ''
    await Promise.all([
      loadDetail(targetSessionId, targetProjectId, generation),
      loadSessions(targetProjectId, generation),
    ])
  } catch (error) {
    if (isCurrentProject(targetProjectId, generation)) {
      errorMessage.value = normalizeApiError(error).message
    }
  } finally {
    if (isCurrentProject(targetProjectId, generation)) submitting.value = false
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
      errorMessage.value = normalizeApiError(error).message
    }
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
})
</script>

<template>
  <main class="workspace-page knowledge-page">
    <PageHeader
      eyebrow="项目资料检索"
      title="知识问答"
      description="回答仅基于当前项目已完成索引的文档；资料不足时会明确拒答。"
      :context="project?.name"
    />

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />

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
              <strong>{{ session.title }}</strong>
              <small>{{ formatTime(session.updatedAt) }}</small>
            </span>
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
        <el-empty v-else description="还没有问答会话">
          <el-button type="primary" :loading="creating" @click="createSession">创建会话</el-button>
        </el-empty>
      </aside>

      <section class="conversation-panel">
        <template v-if="detail">
          <header class="conversation-heading">
            <div>
              <strong>{{ detail.session.title }}</strong>
              <small>消息按服务器保存顺序展示</small>
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
            <el-empty v-if="!detail.messages.length" description="输入问题开始基于项目资料问答" />
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
                title="证据不足：当前项目资料无法支持可靠回答"
                type="warning"
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
                Ctrl / ⌘ + Enter 提交；{{ questionLength }}/1000 字；已选择
                {{ selectedDocumentIds.length }}/20 个文档
              </span>
              <el-button
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
          <dt>相似度</dt><dd>{{ similarity(selectedCitation.similarity) }}</dd>
          <dt>引用内容</dt><dd class="quote">{{ selectedCitation.quote }}</dd>
        </dl>
        <el-button type="primary" @click="downloadCitation">下载原文</el-button>
      </template>
    </el-drawer>
  </main>
</template>

<style scoped>
.knowledge-page { display: grid; gap: 16px; }
.knowledge-layout {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  min-height: calc(100vh - 250px);
  border: 1px solid #e4e7ed;
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
}
.session-panel { border-right: 1px solid #e4e7ed; background: #f8fafc; min-width: 0; }
.panel-heading, .conversation-heading, .composer-footer {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.panel-heading, .conversation-heading { min-height: 68px; padding: 14px 16px; border-bottom: 1px solid #e4e7ed; }
.panel-heading div, .conversation-heading div { display: grid; gap: 3px; }
.panel-heading small, .conversation-heading small, .message-meta span, .composer-footer span { color: #64748b; }
.session-list { padding: 10px; display: grid; gap: 6px; }
.session-item {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  border: 1px solid transparent; border-radius: 10px; padding: 10px 8px 10px 12px;
  background: transparent; color: inherit; text-align: left; cursor: pointer;
}
.session-item:hover, .session-item.active { background: #fff; border-color: #c7d2fe; }
.session-item > span { min-width: 0; display: grid; gap: 4px; }
.session-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-item small { color: #64748b; }
.conversation-panel { min-width: 0; display: grid; grid-template-rows: auto minmax(320px, 1fr) auto; }
.document-selector { width: min(420px, 48vw); }
.message-area { overflow-y: auto; padding: 20px; display: grid; align-content: start; gap: 18px; }
.message { max-width: min(780px, 92%); border-radius: 14px; padding: 14px 16px; }
.message.user { justify-self: end; background: #eef2ff; }
.message.assistant { justify-self: start; background: #f8fafc; border: 1px solid #e2e8f0; }
.message-meta { display: flex; align-items: center; gap: 9px; margin-bottom: 9px; font-size: 13px; }
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
.question-composer { border-top: 1px solid #e4e7ed; padding: 14px 18px; display: grid; gap: 9px; }
.question-composer label { font-weight: 650; }
.composer-footer { font-size: 13px; }
.citation-detail { display: grid; grid-template-columns: 88px 1fr; gap: 12px; }
.citation-detail dt { color: #64748b; }
.citation-detail dd { margin: 0; overflow-wrap: anywhere; }
.citation-detail .quote { white-space: pre-wrap; line-height: 1.7; }
@media (max-width: 900px) {
  .knowledge-layout { grid-template-columns: 1fr; }
  .session-panel { border-right: 0; border-bottom: 1px solid #e4e7ed; max-height: 260px; overflow-y: auto; }
  .document-selector { width: 100%; }
  .conversation-heading { align-items: stretch; flex-direction: column; }
}
</style>
