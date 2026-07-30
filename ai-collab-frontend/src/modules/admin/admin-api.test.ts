import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { httpClient } from '../../api/http-client'
import { adminApi } from './admin-api'

const originalAdapter = httpClient.defaults.adapter

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => { httpClient.defaults.adapter = originalAdapter })

describe('adminApi', () => {
  it('assigns one model configuration to a normalized purpose endpoint', async () => {
    let method = ''
    let url = ''
    let body = ''
    httpClient.defaults.adapter = (async (config) => {
      method = config.method ?? ''
      url = config.url ?? ''
      body = String(config.data)
      return {
        data: null,
        status: 204,
        statusText: 'No Content',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    await adminApi.assign('AGENT', 'model-1')

    expect(method).toBe('put')
    expect(url).toBe('/admin/models/assignments/AGENT')
    expect(JSON.parse(body)).toEqual({ configurationId: 'model-1' })
  })
})
