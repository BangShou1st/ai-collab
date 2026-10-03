<script setup lang="ts">
import { useKnowledgeView } from './use-knowledge-view'
import { formatTime, markdown, similarity } from './knowledge-render'
import { isFeedbackEligible } from './knowledge-feedback'

const {
  project, sessions, detail,
  selectedSessionId, selectedDocumentIds, question,
  loading, creating, submitting, deletingId,
  citationDrawer, selectedCitation,
  messageArea, editingSessionId, editingSessionTitle, streamingMessage,
  feedbackMap, feedbackSubmittingIds,
  readyDocuments, questionLength, canSubmit,
  selectSession, createSession, startEditSession, cancelEditSession, saveSessionTitle,
  removeSession, submitQuestion, cancelStream, onQuestionKeydown,
  openCitation, downloadCitation,
  removeFeedback, toggleFeedback,
} = useKnowledgeView()
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
  border: 1px solid var(--color-border);
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
}
.session-panel {
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-raised);
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-heading, .conversation-heading, .composer-footer {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.panel-heading, .conversation-heading { min-height: 52px; padding: 10px 14px; border-bottom: 1px solid var(--color-border); flex-shrink: 0; }
.panel-heading div, .conversation-heading div { display: grid; gap: 3px; }
.panel-heading small, .conversation-heading small, .message-meta span, .composer-footer span { color: var(--color-text-secondary); }
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
.session-item small { color: var(--color-text-secondary); font-size: 11px; }
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
.message.assistant { justify-self: start; background: var(--color-surface-raised); border: 1px solid var(--color-border); }
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
.citation-card span, .citation-card small { color: var(--color-text-secondary); }
.citation-card p { margin: 2px 0 0; display: -webkit-box; overflow: hidden; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.question-composer { border-top: 1px solid var(--color-border); padding: 12px 16px; display: grid; gap: 8px; flex-shrink: 0; }
.question-composer label { font-weight: 650; }
.composer-footer { font-size: 13px; }
.citation-detail { display: grid; grid-template-columns: 88px 1fr; gap: 12px; }
.citation-detail dt { color: var(--color-text-secondary); }
.citation-detail dd { margin: 0; overflow-wrap: anywhere; }
.citation-detail .quote { white-space: pre-wrap; line-height: 1.7; }
.feedback-bar { display: flex; align-items: center; gap: 6px; margin-top: 10px; }
.feedback-label { color: var(--color-text-secondary); font-size: 12px; }
@media (max-width: 900px) {
  .knowledge-page {
    height: auto;
    min-height: calc(100dvh - var(--project-shell-offset));
    overflow: visible;
  }
  .knowledge-layout { grid-template-columns: 1fr; height: auto; min-height: calc(100vh - 180px); }
  .session-panel { border-right: 0; border-bottom: 1px solid var(--color-border); }
  .session-list { flex-basis: 220px; height: 220px; }
  .document-selector { width: 100%; }
  .conversation-heading { align-items: stretch; flex-direction: column; }
}
</style>
