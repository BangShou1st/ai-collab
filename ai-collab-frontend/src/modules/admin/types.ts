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
