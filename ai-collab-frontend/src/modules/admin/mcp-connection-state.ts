import type { McpAuthType, McpConnection, McpConnectionInput } from './types'

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
