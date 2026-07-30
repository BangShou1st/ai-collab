import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { httpClient } from '../../api/http-client'
import { dashboardApi } from './dashboard-api'

const originalAdapter = httpClient.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
})

describe('dashboardApi', () => {
  it('requests the project-scoped dashboard endpoint', async () => {
    let requestedUrl = ''
    httpClient.defaults.adapter = (async (config) => {
      requestedUrl = config.url ?? ''
      return {
        data: { code: 'SUCCESS', message: '操作成功', data: { project: {}, tasks: {} } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    const result = await dashboardApi.get('project-1')

    expect(requestedUrl).toBe('/projects/project-1/dashboard')
    expect(result.code).toBe('SUCCESS')
  })
})
