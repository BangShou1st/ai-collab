import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { httpClient } from '../../api/http-client'
import { adminApi } from './admin-api'

const originalAdapter = httpClient.defaults.adapter

function mcpConnection(overrides: Record<string, unknown> = {}) {
  return {
    id: 'connection-1', code: 'github', name: 'GitHub', transport: 'STREAMABLE_HTTP',
    endpoint: 'https://mcp.example.com/mcp', authType: 'BEARER', credentialConfigured: true,
    timeoutMs: 10000, maxResultBytes: 65536, toolAllowlist: ['get_file'],
    resourceAllowlist: [], discoveredTools: [], discoveredResources: [], schemaHash: null,
    confirmedSchemaHash: null, schemaConfirmed: false, enabled: false,
    lastHealthStatus: null, lastHealthMessage: null, lastHealthAt: null, version: 0,
    createdAt: '2026-08-05T00:00:00Z', updatedAt: '2026-08-05T00:00:00Z',
    ...overrides,
  }
}

beforeEach(() => setActivePinia(createPinia()))
afterEach(() => { httpClient.defaults.adapter = originalAdapter })

describe('adminApi', () => {
  it('rejects an MCP connection response whose allowlists are not arrays', async () => {
    httpClient.defaults.adapter = (async (config) => ({
      data: {
        data: [mcpConnection({ toolAllowlist: { empty: false } })],
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })) as AxiosAdapter

    await expect(adminApi.mcpConnections()).rejects.toThrow(
      'MCP 连接响应契约错误：toolAllowlist 必须是字符串数组',
    )
  })

  it('creates an MCP connection through the Agent administration endpoint', async () => {
    let method = ''
    let url = ''
    let body = ''
    httpClient.defaults.adapter = (async (config) => {
      method = config.method ?? ''
      url = config.url ?? ''
      body = String(config.data)
      return {
        data: { data: mcpConnection() },
        status: 201,
        statusText: 'Created',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    await adminApi.createMcpConnection({
      code: 'github-readonly',
      name: 'GitHub readonly',
      transport: 'STREAMABLE_HTTP',
      endpoint: 'https://mcp.example.com/mcp',
      stdioCommand: null,
      authType: 'BEARER',
      credential: 'secret',
      timeoutMs: 10000,
      maxResultBytes: 65536,
      toolAllowlist: ['get_file_contents'],
      resourceAllowlist: [],
      version: 0,
    })

    expect(method).toBe('post')
    expect(url).toBe('/admin/agent/mcp-connections')
    expect(JSON.parse(body).credential).toBe('secret')
  })

  it('sends null credential when editing without rotating and sends a new value when rotating', async () => {
    const credentials: unknown[] = []
    httpClient.defaults.adapter = (async (config) => {
      credentials.push(JSON.parse(String(config.data)).credential)
      return {
        data: { data: mcpConnection({ version: 1 }) }, status: 200, statusText: 'OK', headers: {}, config,
      } as AxiosResponse
    }) as AxiosAdapter
    const input = {
      code: 'github', name: 'GitHub', transport: 'STREAMABLE_HTTP' as const,
      endpoint: 'https://mcp.example.com/mcp', stdioCommand: null, authType: 'BEARER' as const,
      credential: null, timeoutMs: 10000, maxResultBytes: 65536,
      toolAllowlist: ['get_file'], resourceAllowlist: [], version: 0,
    }

    await adminApi.updateMcpConnection('connection-1', input)
    await adminApi.updateMcpConnection('connection-1', { ...input, credential: 'rotated-secret' })

    expect(credentials).toEqual([null, 'rotated-secret'])
  })

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
