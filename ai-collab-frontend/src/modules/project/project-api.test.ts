import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { httpClient } from '../../api/http-client'
import { projectApi } from './project-api'

const originalAdapter = httpClient.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
})

describe('projectApi', () => {
  it('updates a project with its optimistic-lock version', async () => {
    let requestedMethod = ''
    let requestedUrl = ''
    let requestedBody = ''
    httpClient.defaults.adapter = (async (config) => {
      requestedMethod = config.method ?? ''
      requestedUrl = config.url ?? ''
      requestedBody = String(config.data)
      return {
        data: { code: 'SUCCESS', message: '操作成功', data: { id: 'project-1', version: 4 } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    await projectApi.update('project-1', {
      name: '项目一',
      description: '说明',
      type: 'OTHER',
      startDate: '2026-08-01',
      dueDate: null,
      status: 'ARCHIVED',
      version: 3,
    })

    expect(requestedMethod).toBe('patch')
    expect(requestedUrl).toBe('/projects/project-1')
    expect(JSON.parse(requestedBody)).toMatchObject({ status: 'ARCHIVED', version: 3 })
  })

  it('deletes the selected project', async () => {
    let requestedMethod = ''
    let requestedUrl = ''
    httpClient.defaults.adapter = (async (config) => {
      requestedMethod = config.method ?? ''
      requestedUrl = config.url ?? ''
      return {
        data: null,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    await projectApi.remove('project-1')

    expect(requestedMethod).toBe('delete')
    expect(requestedUrl).toBe('/projects/project-1')
  })
})
