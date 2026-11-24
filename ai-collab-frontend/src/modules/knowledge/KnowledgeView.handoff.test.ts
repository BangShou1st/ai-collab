// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeView from './KnowledgeView.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, unknown> },
  push: vi.fn(),
  projectGet: vi.fn(),
  listSessions: vi.fn(),
  getSession: vi.fn(),
  getFeedback: vi.fn(),
  listDocuments: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

vi.mock('../project/project-api', () => ({
  projectApi: { get: mocks.projectGet },
}))

vi.mock('../document/document-api', () => ({
  documentApi: { list: mocks.listDocuments },
}))

vi.mock('./knowledge-api', () => ({
  KnowledgeStreamError: class KnowledgeStreamError extends Error {},
  knowledgeApi: {
    listSessions: mocks.listSessions,
    getSession: mocks.getSession,
    getFeedback: mocks.getFeedback,
  },
}))

const doc = (id: string, displayName: string, status = 'READY') => ({
  id,
  projectId: 'project-1',
  displayName,
  originalFilename: `${displayName}.md`,
  mimeType: 'text/markdown',
  sizeBytes: 120,
  status,
  parserType: 'tika',
  chunkCount: 3,
  embeddingProvider: 'openai',
  embeddingModel: 'text-embedding-3-small',
  embeddingDimension: 1536,
  errorMessage: null,
  uploadedById: 'u1',
  uploadedByDisplayName: '张三',
  indexedAt: '2026-10-20T00:00:00Z',
  version: 1,
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-20T00:00:00Z',
})

const stubs = {
  PageHeader: { template: '<header />' },
  ElButton: {
    inheritAttrs: false,
    emits: ['click'],
    template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
  },
  ElSelect: {
    props: ['modelValue'],
    template: '<div data-test="doc-select" :data-selected="JSON.stringify(modelValue)"><slot /></div>',
  },
  ElOption: { props: ['label', 'value'], template: '<span :data-value="value">{{ label }}</span>' },
  ElInput: { template: '<textarea />' },
  ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
  ElEmpty: { template: '<div><slot /></div>' },
  ElDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
}

describe('KnowledgeView document handoff', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.route.query = {}
    mocks.projectGet.mockResolvedValue({ data: { id: 'project-1', name: '演示项目', role: 'OWNER' } })
    mocks.listSessions.mockResolvedValue({
      data: [{
        id: 'session-1',
        projectId: 'project-1',
        title: '默认问答',
        createdAt: '2026-10-20T00:00:00Z',
        updatedAt: '2026-10-20T00:00:00Z',
      }],
    })
    mocks.getSession.mockResolvedValue({
      data: {
        session: {
          id: 'session-1',
          projectId: 'project-1',
          title: '默认问答',
          createdAt: '2026-10-20T00:00:00Z',
          updatedAt: '2026-10-20T00:00:00Z',
        },
        messages: [{
          id: 'msg-1',
          role: 'ASSISTANT',
          content: '测试回答 [S1]',
          insufficientEvidence: false,
          model: 'gpt-4o',
          citations: [{
            documentId: 'doc-cited',
            filename: '架构说明.md',
            chunkId: 'chunk-1',
            heading: '总览',
            quote: '关键证据摘录',
            similarity: 0.91,
            rank: 1,
            pageNumber: 1,
          }],
          createdAt: '2026-10-20T00:01:00Z',
        }],
      },
    })
    mocks.getFeedback.mockResolvedValue({ data: { myFeedback: null, helpfulCount: 0, unhelpfulCount: 0 } })
    mocks.listDocuments.mockResolvedValue({
      data: [doc('doc-ready', '需求说明'), doc('doc-cited', '架构说明')],
    })
  })

  it('preselects ready query document and hands off citation document to Agent', async () => {
    mocks.route.query = { document: 'doc-ready' }
    const wrapper = mount(KnowledgeView, {
      global: { stubs, directives: { loading: () => undefined } },
    })
    await flushPromises()

    expect(wrapper.get('[data-test="doc-select"]').attributes('data-selected')).toBe('["doc-ready"]')
    await wrapper.get('[data-test="knowledge-handoff-agent"]').trigger('click')

    expect(mocks.push).toHaveBeenCalledWith({
      path: '/projects/project-1/agent',
      query: { document: 'doc-cited' },
    })
    wrapper.unmount()
  })
})
