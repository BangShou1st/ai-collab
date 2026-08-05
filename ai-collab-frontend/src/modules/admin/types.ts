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

export interface AdminUser {
  id: string
  username: string
  displayName: string
  email: string | null
  status: 'ACTIVE' | 'DISABLED'
  systemAdmin: boolean
  lastLoginAt: string | null
  createdAt: string
}

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
