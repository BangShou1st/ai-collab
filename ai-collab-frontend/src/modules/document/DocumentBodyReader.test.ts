// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import DocumentBodyReader from './DocumentBodyReader.vue'
import { documentApi } from './document-api'
vi.mock('./document-api', () => ({ documentApi: { readingStatus: vi.fn(), readBody: vi.fn() } }))
vi.mock('../../api/api-result', () => ({ showApiError: vi.fn() }))
const state = { bodyReadable: true, searchAvailable: false, failureStage: '检索索引', snapshotId: 'snapshot', originalContentHash: 'hash', parseVersion: 'v2' }
describe('document body reader', () => {
  beforeEach(() => vi.clearAllMocks())
  it('shows body availability separately and continues the exact persisted range', async () => {
    vi.mocked(documentApi.readingStatus).mockResolvedValue({ data: state } as never)
    vi.mocked(documentApi.readBody).mockResolvedValueOnce({ data: { ...state, items: [{ chunkId: 'c', chunkNo: 0, content: '来源内容', fromOffset: 0, throughOffset: 100 }], hasMore: true, continuation: { snapshotId: 'snapshot', fromChunk: 0, fromOffset: 100 } } } as never)
      .mockResolvedValueOnce({ data: { ...state, items: [], hasMore: false, continuation: null } } as never)
    const wrapper = mount(DocumentBodyReader, { props: { projectId: 'p', documentId: 'd' }, global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('正文可读 · 检索尚不可用')
    expect(wrapper.text()).toContain('失败阶段：检索索引')
    await wrapper.find('button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('本次仅阅读下列范围')
    await wrapper.find('button').trigger('click'); await flushPromises()
    expect(documentApi.readBody).toHaveBeenLastCalledWith('p', 'd', expect.objectContaining({ snapshotId: 'snapshot', fromChunk: 0, fromOffset: 100 }))
  })
  it('opens the immutable citation id rather than substituting another chunk', async () => {
    vi.mocked(documentApi.readingStatus).mockResolvedValue({ data: state } as never)
    vi.mocked(documentApi.readBody).mockRejectedValueOnce(new Error('引用来源已更新或已删除'))
    mount(DocumentBodyReader, { props: { projectId: 'p', documentId: 'd', chunkId: 'old-chunk' } })
    await flushPromises()
    expect(documentApi.readBody).toHaveBeenCalledWith('p', 'd', expect.objectContaining({ chunkId: 'old-chunk' }))
  })
})
