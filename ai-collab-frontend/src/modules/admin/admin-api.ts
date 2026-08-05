import { httpClient } from '../../api/http-client'
import { ApiContractError, apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { AxiosResponse } from 'axios'
import type {
  AdminUser,
  ModelAssignment,
  ModelConfiguration,
  ModelConfigurationInput,
  ModelPurpose,
  McpConnection,
  McpConnectionInput,
} from './types'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string') throw contractError(`${field} 必须是字符串`)
  return value
}

function nullableString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  if (typeof value !== 'string') throw contractError(`${field} 必须是字符串或 null`)
  return value
}

function stringArray(record: Record<string, unknown>, field: string): string[] {
  const value = record[field]
  if (!Array.isArray(value) || !value.every(item => typeof item === 'string')) {
    throw contractError(`${field} 必须是字符串数组`)
  }
  return [...value]
}

function objectArray(record: Record<string, unknown>, field: string): Record<string, unknown>[] {
  const value = record[field]
  if (!Array.isArray(value) || !value.every(isRecord)) {
    throw contractError(`${field} 必须是对象数组`)
  }
  return value
}

function requiredBoolean(record: Record<string, unknown>, field: string): boolean {
  const value = record[field]
  if (typeof value !== 'boolean') throw contractError(`${field} 必须是布尔值`)
  return value
}

function requiredNumber(record: Record<string, unknown>, field: string): number {
  const value = record[field]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw contractError(`${field} 必须是数字`)
  return value
}

function contractError(reason: string): Error {
  return new ApiContractError(`MCP 连接响应契约错误：${reason}`)
}

function parseMcpConnection(value: unknown): McpConnection {
  if (!isRecord(value)) throw contractError('连接项必须是对象')
  const transport = requiredString(value, 'transport')
  const authType = requiredString(value, 'authType')
  if (!['STDIO', 'SSE', 'STREAMABLE_HTTP'].includes(transport)) throw contractError('transport 无效')
  if (!['NONE', 'BEARER', 'OAUTH21'].includes(authType)) throw contractError('authType 无效')
  const discoveredTools = objectArray(value, 'discoveredTools').map(tool => ({
    name: requiredString(tool, 'name'),
    description: nullableString(tool, 'description'),
    inputSchema: tool.inputSchema,
    outputSchema: tool.outputSchema,
    annotations: tool.annotations,
  }))
  const discoveredResources = objectArray(value, 'discoveredResources').map(resource => ({
    uri: requiredString(resource, 'uri'),
    name: nullableString(resource, 'name'),
    description: nullableString(resource, 'description'),
    mimeType: nullableString(resource, 'mimeType'),
  }))
  return {
    id: requiredString(value, 'id'), code: requiredString(value, 'code'),
    name: requiredString(value, 'name'), transport: transport as McpConnection['transport'],
    endpoint: nullableString(value, 'endpoint'), authType: authType as McpConnection['authType'],
    credentialConfigured: requiredBoolean(value, 'credentialConfigured'),
    timeoutMs: requiredNumber(value, 'timeoutMs'), maxResultBytes: requiredNumber(value, 'maxResultBytes'),
    toolAllowlist: stringArray(value, 'toolAllowlist'),
    resourceAllowlist: stringArray(value, 'resourceAllowlist'), discoveredTools, discoveredResources,
    schemaHash: nullableString(value, 'schemaHash'),
    confirmedSchemaHash: nullableString(value, 'confirmedSchemaHash'),
    schemaConfirmed: requiredBoolean(value, 'schemaConfirmed'), enabled: requiredBoolean(value, 'enabled'),
    lastHealthStatus: nullableString(value, 'lastHealthStatus'),
    lastHealthMessage: nullableString(value, 'lastHealthMessage'),
    lastHealthAt: nullableString(value, 'lastHealthAt'), version: requiredNumber(value, 'version'),
    createdAt: requiredString(value, 'createdAt'), updatedAt: requiredString(value, 'updatedAt'),
  }
}

function mcpResult(response: AxiosResponse<ApiResponse<unknown>>): ApiResult<McpConnection> {
  const result = apiResultFromResponse(response)
  return { ...result, data: parseMcpConnection(result.data) }
}

function mcpListResult(response: AxiosResponse<ApiResponse<unknown>>): ApiResult<McpConnection[]> {
  const result = apiResultFromResponse(response)
  if (!Array.isArray(result.data)) throw contractError('data 必须是数组')
  return { ...result, data: result.data.map(parseMcpConnection) }
}

export const adminApi = {
  async mcpConnections(): Promise<ApiResult<McpConnection[]>> {
    return mcpListResult(await httpClient.get<ApiResponse<unknown>>('/admin/agent/mcp-connections'))
  },
  async createMcpConnection(input: McpConnectionInput): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>('/admin/agent/mcp-connections', input))
  },
  async updateMcpConnection(
    id: string,
    input: McpConnectionInput,
  ): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.patch<ApiResponse<unknown>>(`/admin/agent/mcp-connections/${id}`, input))
  },
  async testMcpConnection(id: string): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>(`/admin/agent/mcp-connections/${id}/test`))
  },
  async discoverMcpConnection(id: string): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>(`/admin/agent/mcp-connections/${id}/discover`))
  },
  async setMcpConnectionEnabled(
    item: McpConnection,
    enabled: boolean,
  ): Promise<ApiResult<McpConnection>> {
    return mcpResult(
      await httpClient.post<ApiResponse<unknown>>(
        `/admin/agent/mcp-connections/${item.id}/${enabled ? 'enable' : 'disable'}`,
        null,
        { params: { version: item.version } },
      ),
    )
  },
  async models(): Promise<ApiResult<ModelConfiguration[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelConfiguration[]>>('/admin/models'),
    )
  },
  async createModel(input: ModelConfigurationInput): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ModelConfiguration>>('/admin/models', input),
    )
  },
  async updateModel(
    id: string,
    input: ModelConfigurationInput,
  ): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<ModelConfiguration>>(`/admin/models/${id}`, input),
    )
  },
  async removeModel(id: string): Promise<void> {
    await httpClient.delete(`/admin/models/${id}`)
  },
  async testModel(id: string): Promise<void> {
    await httpClient.post(`/admin/models/${id}/test`)
  },
  async assignments(): Promise<ApiResult<ModelAssignment[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelAssignment[]>>('/admin/models/assignments'),
    )
  },
  async assign(purpose: ModelPurpose, configurationId: string): Promise<void> {
    await httpClient.put(`/admin/models/assignments/${purpose}`, { configurationId })
  },
  async users(): Promise<ApiResult<AdminUser[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<AdminUser[]>>('/admin/users'),
    )
  },
  async createUser(input: {
    username: string
    displayName: string
    password: string
  }): Promise<void> {
    await httpClient.post('/admin/test-users', input)
  },
  async setUserEnabled(id: string, enabled: boolean): Promise<void> {
    await httpClient.post(`/admin/users/${id}/${enabled ? 'enable' : 'disable'}`)
  },
}
