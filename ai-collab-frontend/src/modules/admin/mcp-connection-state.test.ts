import { describe, expect, it } from 'vitest'
import { canToggleMcpConnection, mcpEditorValues, toMcpConnectionInput } from './mcp-connection-state'

describe('MCP connection presentation', () => {
  it('allows a newly discovered schema to be confirmed and enabled', () => {
    expect(canToggleMcpConnection({ enabled: false, schemaHash: 'sha256-value' })).toBe(true)
    expect(canToggleMcpConnection({ enabled: false, schemaHash: null })).toBe(false)
    expect(canToggleMcpConnection({ enabled: true, schemaHash: null })).toBe(true)
  })

  it('refills existing allowlists while keeping the edit credential blank', () => {
    const values = mcpEditorValues({
      code: 'github', name: 'GitHub', endpoint: 'https://mcp.example.com/mcp',
      authType: 'BEARER', timeoutMs: 10000, maxResultBytes: 65536,
      toolAllowlist: ['get_file', 'list_issues'], resourceAllowlist: ['repo://main'], version: 4,
    })

    expect(values.toolAllowlist).toBe('get_file, list_issues')
    expect(values.resourceAllowlist).toBe('repo://main')
    expect(values.credential).toBe('')
  })

  it('omits blank edit credentials and includes a newly entered rotation credential', () => {
    const values = {
      code: 'github', name: 'GitHub', endpoint: 'https://mcp.example.com/mcp', authType: 'BEARER' as const,
      credential: '   ', timeoutMs: 10000, maxResultBytes: 65536,
      toolAllowlist: 'get_file, get_file, list_issues', resourceAllowlist: '', version: 4,
    }

    expect(toMcpConnectionInput(values).credential).toBeNull()
    expect(toMcpConnectionInput({ ...values, credential: ' rotated ' }).credential).toBe('rotated')
    expect(toMcpConnectionInput(values).toolAllowlist).toEqual(['get_file', 'list_issues'])
  })
})
