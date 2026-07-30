import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { httpClient } from '../../api/http-client'
import { auditApi } from './audit-api'

const originalAdapter = httpClient.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
})

describe('auditApi', () => {
  it('requests the project-scoped page with one-based pagination', async () => {
    let requestedUrl = ''
    let requestedParams: unknown
    httpClient.defaults.adapter = (async (config) => {
      requestedUrl = config.url ?? ''
      requestedParams = config.params
      return {
        data: {
          code: 'SUCCESS',
          message: '操作成功',
          data: { items: [], page: 2, size: 20, total: 0 },
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    const result = await auditApi.page('project-1', 2, 20)

    expect(requestedUrl).toBe('/projects/project-1/audit-logs')
    expect(requestedParams).toEqual({ page: 2, size: 20 })
    expect(result.data.page).toBe(2)
  })
})
