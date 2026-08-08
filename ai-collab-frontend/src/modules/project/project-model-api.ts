import { httpClient } from '../../api/http-client'
import { ApiContractError, apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type { AxiosResponse } from 'axios'

// ─── Model Types ───

export type ModelProviderType = 'OPENAI_COMPATIBLE' | 'ANTHROPIC' | 'GEMINI'
export type ModelPurpose = 'KNOWLEDGE_CHAT' | 'AGENT' | 'PLANNING'
export type ModelCapability = 'CHAT' | 'STREAMING' | 'STRUCTURED_OUTPUT' | 'NATIVE_TOOLS' | 'USAGE'

export interface ModelConfiguration {
  id: string
  name: string
  providerType: ModelProviderType
  baseUrl: string
  apiPath: string
  hasApiKey: boolean
  modelName: string
  enabled: boolean
  temperature: number
  maxOutputTokens: number
  capabilities: ModelCapability[]
  updatedAt: string
}

export interface ModelConfigurationInput {
  name: string
  providerType: ModelProviderType
  baseUrl: string
  apiPath: string
  apiKey: string | null
  modelName: string
  enabled: boolean
  temperature: number
  maxOutputTokens: number
  capabilities: ModelCapability[]
}

export interface ModelAssignment {
  purpose: ModelPurpose
  configurationId: string
}

// ─── Embedding Types ───

export interface EmbeddingConfig {
  provider: ModelProviderType | null
  baseUrl: string | null
  apiPath: string | null
  hasApiKey: boolean
  modelName: string | null
  dimensions: number
  batchSize: number
  enabled: boolean
}

export interface EmbeddingConfigInput {
  provider: ModelProviderType
  baseUrl: string
  apiPath: string
  apiKey: string | null
  modelName: string
  dimensions: number
  batchSize: number
}

// ─── MCP Types ───

export type McpTransport = 'STDIO' | 'SSE' | 'STREAMABLE_HTTP'
export type McpAuthType = 'NONE' | 'BEARER' | 'OAUTH21'

export interface McpConnectionInput {
  code: string
  name: string
  transport: McpTransport
  endpoint: string | null
  stdioCommand: unknown | null
  authType: McpAuthType
  credential: string | null
  timeoutMs: number
  maxResultBytes: number
  toolAllowlist: string[]
  resourceAllowlist: string[]
  version: number
}

export interface McpConnection {
  id: string
  projectId: string
  code: string
  name: string
  transport: McpTransport
  endpoint: string | null
  authType: McpAuthType
  credentialConfigured: boolean
  timeoutMs: number
  maxResultBytes: number
  toolAllowlist: string[]
  resourceAllowlist: string[]
  discoveredTools: McpDiscoveredTool[]
  discoveredResources: McpDiscoveredResource[]
  schemaHash: string | null
  confirmedSchemaHash: string | null
  schemaConfirmed: boolean
  enabled: boolean
  lastHealthStatus: string | null
  lastHealthMessage: string | null
  lastHealthAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface McpDiscoveredTool {
  name: string
  description: string | null
  inputSchema: unknown
  outputSchema: unknown
  annotations: unknown
}

export interface McpDiscoveredResource {
  uri: string
  name: string | null
  description: string | null
  mimeType: string | null
}

// ─── MCP Parsing ───

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
    id: requiredString(value, 'id'), projectId: requiredString(value, 'projectId'),
    code: requiredString(value, 'code'), name: requiredString(value, 'name'),
    transport: transport as McpConnection['transport'],
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

// ─── API Methods ───

const projectRoot = (projectId: string) => `/projects/${projectId}`

export const projectModelApi = {
  // ─── Model CRUD ───
  async listModels(projectId: string): Promise<ApiResult<ModelConfiguration[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelConfiguration[]>>(`${projectRoot(projectId)}/models`),
    )
  },
  async createModel(projectId: string, input: ModelConfigurationInput): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<ModelConfiguration>>(`${projectRoot(projectId)}/models`, input),
    )
  },
  async updateModel(
    projectId: string,
    id: string,
    input: ModelConfigurationInput,
  ): Promise<ApiResult<ModelConfiguration>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<ModelConfiguration>>(`${projectRoot(projectId)}/models/${id}`, input),
    )
  },
  async deleteModel(projectId: string, id: string): Promise<void> {
    await httpClient.delete(`${projectRoot(projectId)}/models/${id}`)
  },
  async testModel(projectId: string, id: string): Promise<void> {
    await httpClient.post(`${projectRoot(projectId)}/models/${id}/test`)
  },

  // ─── Model Assignments ───
  async listAssignments(projectId: string): Promise<ApiResult<ModelAssignment[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<ModelAssignment[]>>(`${projectRoot(projectId)}/models/assignments`),
    )
  },
  async assign(projectId: string, purpose: ModelPurpose, configurationId: string): Promise<void> {
    await httpClient.put(`${projectRoot(projectId)}/models/assignments/${purpose}`, { configurationId })
  },

  // ─── Embedding Config ───
  async getEmbeddingConfig(projectId: string): Promise<ApiResult<EmbeddingConfig>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<EmbeddingConfig>>(`${projectRoot(projectId)}/embedding-config`),
    )
  },
  async saveEmbeddingConfig(projectId: string, input: EmbeddingConfigInput): Promise<ApiResult<EmbeddingConfig>> {
    return apiResultFromResponse(
      await httpClient.put<ApiResponse<EmbeddingConfig>>(`${projectRoot(projectId)}/embedding-config`, input),
    )
  },
  async testEmbeddingConfig(projectId: string): Promise<void> {
    await httpClient.post(`${projectRoot(projectId)}/embedding-config/test`)
  },

  // ─── MCP Connections ───
  async listMcpConnections(projectId: string): Promise<ApiResult<McpConnection[]>> {
    return mcpListResult(await httpClient.get<ApiResponse<unknown>>(`${projectRoot(projectId)}/agent/mcp-connections`))
  },
  async createMcpConnection(projectId: string, input: McpConnectionInput): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>(`${projectRoot(projectId)}/agent/mcp-connections`, input))
  },
  async updateMcpConnection(
    projectId: string,
    id: string,
    input: McpConnectionInput,
  ): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.patch<ApiResponse<unknown>>(`${projectRoot(projectId)}/agent/mcp-connections/${id}`, input))
  },
  async testMcpConnection(projectId: string, id: string): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>(`${projectRoot(projectId)}/agent/mcp-connections/${id}/test`))
  },
  async discoverMcpConnection(projectId: string, id: string): Promise<ApiResult<McpConnection>> {
    return mcpResult(await httpClient.post<ApiResponse<unknown>>(`${projectRoot(projectId)}/agent/mcp-connections/${id}/discover`))
  },
  async setMcpConnectionEnabled(
    projectId: string,
    item: McpConnection,
    enabled: boolean,
  ): Promise<ApiResult<McpConnection>> {
    return mcpResult(
      await httpClient.post<ApiResponse<unknown>>(
        `${projectRoot(projectId)}/agent/mcp-connections/${item.id}/${enabled ? 'enable' : 'disable'}`,
        null,
        { params: { version: item.version } },
      ),
    )
  },
}

// ─── MCP Editor Helpers ───

export function canToggleMcpConnection(item: {
  enabled: boolean
  schemaHash: string | null
}): boolean {
  return item.enabled || Boolean(item.schemaHash)
}

export interface McpEditorValues {
  code: string
  name: string
  endpoint: string
  authType: McpAuthType
  credential: string
  timeoutMs: number
  maxResultBytes: number
  toolAllowlist: string
  resourceAllowlist: string
  version: number
}

export function mcpEditorValues(item: Pick<McpConnection,
  'code' | 'name' | 'endpoint' | 'authType' | 'timeoutMs' | 'maxResultBytes'
  | 'toolAllowlist' | 'resourceAllowlist' | 'version'>): McpEditorValues {
  return {
    code: item.code,
    name: item.name,
    endpoint: item.endpoint ?? '',
    authType: item.authType,
    credential: '',
    timeoutMs: item.timeoutMs,
    maxResultBytes: item.maxResultBytes,
    toolAllowlist: item.toolAllowlist.join(', '),
    resourceAllowlist: item.resourceAllowlist.join(', '),
    version: item.version,
  }
}

function splitAllowlist(value: string): string[] {
  return [...new Set(value.split(',').map(item => item.trim()).filter(Boolean))]
}

export function toMcpConnectionInput(values: McpEditorValues): McpConnectionInput {
  return {
    code: values.code.trim(),
    name: values.name.trim(),
    transport: 'STREAMABLE_HTTP',
    endpoint: values.endpoint.trim(),
    stdioCommand: null,
    authType: values.authType,
    credential: values.credential.trim() || null,
    timeoutMs: values.timeoutMs,
    maxResultBytes: values.maxResultBytes,
    toolAllowlist: splitAllowlist(values.toolAllowlist),
    resourceAllowlist: splitAllowlist(values.resourceAllowlist),
    version: values.version,
  }
}
