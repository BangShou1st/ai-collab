// @vitest-environment jsdom
import { computed, inject, provide, type ComputedRef, type PropType } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DocumentView from './DocumentView.vue'
import { documentApi } from './document-api'
import { projectApi } from '../project/project-api'
import { showApiError } from '../../api/api-result'
import type { ProjectDocument } from './types'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { projectId: 'p' } }) }))
vi.mock('./document-api', () => ({ documentApi: { list: vi.fn(), get: vi.fn(), retry: vi.fn() } }))
vi.mock('../project/project-api', () => ({ projectApi: { get: vi.fn() } }))
vi.mock('../../api/api-result', async original => ({ ...await original<typeof import('../../api/api-result')>(), showApiError: vi.fn() }))

const document = (over: Partial<ProjectDocument> = {}): ProjectDocument => ({
  id: 'd', projectId: 'p', displayName: '失败资料', originalFilename: 'sample.txt', mimeType: 'text/plain',
  sizeBytes: 12, status: 'FAILED', errorMessage: '文档向量化失败，请检查模型配置后重试',
  parserType: null, chunkCount: 0, embeddingProvider: null, embeddingModel: null, embeddingDimension: null,
  uploadedById: 'u', uploadedByDisplayName: '测试上传者', indexedAt: null, version: 1,
  createdAt: '2026-10-20T00:00:00Z', updatedAt: '2026-10-20T00:00:00Z', ...over,
})
const response = <T,>(data: T) => ({ data }) as never
const project = (role = 'OWNER', status = 'ACTIVE') => response({ id: 'p', name: '合成项目', role, status })
const stubs = {
  PageHeader: { template: '<header><slot name="actions" /></header>' },
  DocumentBodyReader: true,
  RouterLink: { template: '<a><slot /></a>' },
  ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading" :aria-busy="Boolean(loading)"><slot /></button>' },
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  ElCard: { template: '<div><slot name="header" /><slot /></div>' },
  ElTag: { template: '<span><slot /></span>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: true,
  ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
  ElDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  ElTable: {
    props: { data: { type: Array as PropType<ProjectDocument[]>, required: true } }, emits: ['row-click'],
    setup(props: { data?: ProjectDocument[] }) { provide('rows', computed(() => props.data ?? [])); return {} },
    template: '<div><button data-open @click="$emit(\'row-click\', data[0])">查看资料</button><slot /></div>',
  },
  ElTableColumn: {
    setup() { return { rows: inject<ComputedRef<ProjectDocument[]>>('rows') } },
    template: '<div><slot v-for="row in rows" :row="row" /></div>',
  },
}
async function render() {
  const wrapper = mount(DocumentView, { global: { stubs, directives: { loading: () => {} } } })
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(projectApi.get).mockResolvedValue(project())
  vi.mocked(documentApi.list).mockResolvedValue(response([document()]))
  vi.mocked(documentApi.get).mockResolvedValue(response(document()))
})

describe('failed document recovery', () => {
  it('shows known diagnosis in the list and responsibility in details; never retries automatically', async () => {
    const wrapper = await render()
    expect(wrapper.text()).toContain('文档向量化失败')
    await wrapper.get('[data-open]').trigger('click'); await flushPromises()
    expect(wrapper.get('aside').text()).toContain('请系统管理员检查系统 Embedding')
    expect(wrapper.text()).toContain('提交成功不代表处理完成')
    expect(documentApi.retry).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('never renders arbitrary persisted failure text, even in details', async () => {
    vi.mocked(documentApi.get).mockResolvedValue(response(document({ errorMessage: 'private-key sk-secret' })))
    const wrapper = await render()
    await wrapper.get('[data-open]').trigger('click'); await flushPromises()
    expect(wrapper.get('aside').text()).toContain('原因暂不明确')
    expect(wrapper.html()).not.toContain('sk-secret')
    wrapper.unmount()
  })
  it.each(['MEMBER', 'ADMIN'])('respects %s capabilities', async role => {
    vi.mocked(projectApi.get).mockResolvedValue(project(role))
    const wrapper = await render()
    await wrapper.get('[data-open]').trigger('click'); await flushPromises()
    const buttons = wrapper.findAll('button').filter(b => b.text() === '重试处理')
    expect(buttons.length).toBe(role === 'MEMBER' ? 0 : 2)
    if (role === 'MEMBER') expect(wrapper.get('aside').text()).toContain('请联系项目所有者或管理员处理')
    wrapper.unmount()
  })
  it.each(['COMPLETED', 'ARCHIVED'])('disables retry in read-only %s projects', async status => {
    vi.mocked(projectApi.get).mockResolvedValue(project('OWNER', status))
    const wrapper = await render()
    const button = wrapper.get('button[aria-label="重试文档处理"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(documentApi.retry).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前项目只读')
    wrapper.unmount()
  })
  it('submits once, exposes pending feedback, then trusts persisted state on refresh', async () => {
    let resolve!: (data: never) => void
    vi.mocked(documentApi.retry).mockReturnValue(new Promise(r => { resolve = r }))
    const wrapper = await render()
    const button = wrapper.get('button[aria-label="重试文档处理"]')
    await button.trigger('click'); await button.trigger('click')
    expect(documentApi.retry).toHaveBeenCalledExactlyOnceWith('p', 'd')
    expect(wrapper.text()).toContain('正在提交')
    expect(button.attributes('disabled')).toBeDefined()
    vi.mocked(documentApi.list).mockResolvedValue(response([document({ status: 'INDEXING', errorMessage: null })]))
    resolve(response(document({ status: 'UPLOADED', errorMessage: null }))); await flushPromises()
    expect(wrapper.text()).toContain('已提交处理')
    expect(wrapper.text()).toContain('向量化中')
    expect(wrapper.text()).not.toContain('处理成功')
    vi.mocked(documentApi.list).mockResolvedValue(response([document({ status: 'READY', errorMessage: null })]))
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('可检索')
    wrapper.unmount()
    const fresh = await render()
    expect(fresh.text()).toContain('可检索')
    expect(fresh.text()).not.toContain('已提交处理')
    fresh.unmount()
  })
  it('refreshes permissions after a rejected retry and does not claim acceptance', async () => {
    const wrapper = await render()
    vi.mocked(documentApi.retry).mockRejectedValue(new Error('denied'))
    vi.mocked(projectApi.get).mockResolvedValue(project('MEMBER'))
    await wrapper.get('button[aria-label="重试文档处理"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('未确认成功')
    expect(wrapper.text()).not.toContain('已提交处理')
    expect(wrapper.find('button[aria-label="重试文档处理"]').exists()).toBe(false)
    expect(showApiError).toHaveBeenCalled()
    wrapper.unmount()
  })
  it('marks a failed status refresh unknown and blocks retries until refreshed', async () => {
    const wrapper = await render()
    await wrapper.get('[data-open]').trigger('click'); await flushPromises()
    vi.mocked(documentApi.retry).mockResolvedValue(response(document({ status: 'UPLOADED' })))
    vi.mocked(documentApi.list).mockRejectedValue(new Error('network'))
    await wrapper.get('button[aria-label="重试文档处理"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('当前状态未知')
    expect(wrapper.get('aside').text()).toContain('下方为上次状态')
    expect(wrapper.get('aside').text()).toContain('上次状态（可能过时）')
    expect(wrapper.get('aside').text()).not.toContain('文档正在后台处理')
    expect(wrapper.text()).not.toContain('处理成功')
    vi.mocked(documentApi.list).mockResolvedValue(response([document()]))
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('当前状态未知')
    wrapper.unmount()
  })
  it('keeps stale failed data labelled unknown and blocks retry after a list outage', async () => {
    const wrapper = await render()
    vi.mocked(documentApi.list).mockRejectedValue(new Error('network'))
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已有内容可能已过时')
    expect(wrapper.get('button[aria-label="重试文档处理"]').attributes('disabled')).toBeDefined()
    expect(documentApi.retry).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('clears old document details after access is revoked', async () => {
    const wrapper = await render()
    await wrapper.get('[data-open]').trigger('click'); await flushPromises()
    const denied = { isAxiosError: true, response: { status: 404, data: { code: 'PROJECT_NOT_FOUND' } } }
    vi.mocked(projectApi.get).mockRejectedValue(denied)
    vi.mocked(documentApi.list).mockRejectedValue(denied)
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    expect(wrapper.find('aside').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('失败资料')
    expect(wrapper.find('button[aria-label="重试文档处理"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('hides retry if the project role cannot be refreshed', async () => {
    const wrapper = await render()
    vi.mocked(projectApi.get).mockRejectedValue(new Error('network'))
    await wrapper.findAll('button').find(b => b.text() === '刷新状态')!.trigger('click'); await flushPromises()
    expect(wrapper.find('button[aria-label="重试文档处理"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
