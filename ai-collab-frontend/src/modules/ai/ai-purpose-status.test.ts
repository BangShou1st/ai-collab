import { describe, expect, it } from 'vitest'
import { probeKey, resolvePurposeStatuses } from './ai-purpose-status'
import type { UserAiProvider } from './user-ai-api'

function provider(over: Partial<UserAiProvider> = {}): UserAiProvider {
  return {
    id: 'p1', name: 'P1', presetCode: null, providerType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.example.com', apiPath: '/v1/chat/completions', hasApiKey: true,
    modelName: 'm1', enabled: true, isDefault: false, temperature: 0.2, maxOutputTokens: 1200,
    capabilities: ['CHAT'], updatedAt: '2026-10-20T00:00:00Z', ...over,
  }
}

function statusOf(providers: UserAiProvider[], overrides: Partial<Record<string, string>>, purpose: string) {
  return resolvePurposeStatuses(providers, overrides as never).find((item) => item.purpose === purpose)!
}

describe('resolvePurposeStatuses', () => {
  it('reports every purpose as unresolvable without saved config', () => {
    const statuses = resolvePurposeStatuses([], {})
    expect(statuses).toHaveLength(3)
    expect(statuses.every((item) => item.resolution === 'NONE' && item.blocked === 'NO_DEFAULT')).toBe(true)
    expect(statuses.every((item) => !item.resolvable)).toBe(true)
  })

  it('resolves to the enabled default without claiming it was tested', () => {
    const status = statusOf([provider({ isDefault: true })], {}, 'KNOWLEDGE_CHAT')
    expect(status.resolution).toBe('DEFAULT')
    expect(status.provider?.modelName).toBe('m1')
    expect(status.blocked).toBeNull()
    expect(status.resolvable).toBe(true)
  })

  it('treats a disabled default as saved-but-unresolvable', () => {
    const status = statusOf([provider({ isDefault: true, enabled: false })], {}, 'PLANNING')
    expect(status.resolution).toBe('DEFAULT')
    expect(status.blocked).toBe('DEFAULT_DISABLED')
    expect(status.resolvable).toBe(false)
  })

  it('does not resolve when an enabled provider exists but no default is set', () => {
    const status = statusOf([provider({ isDefault: false })], {}, 'AGENT')
    expect(status.resolution).toBe('NONE')
    expect(status.blocked).toBe('NO_DEFAULT')
    expect(status.resolvable).toBe(false)
  })

  it('prefers an enabled override even when the default is disabled', () => {
    const override = provider({ id: 'o1', name: 'Override', isDefault: false })
    const status = statusOf([provider({ id: 'd1', isDefault: true, enabled: false }), override], { AGENT: 'o1' }, 'AGENT')
    expect(status.resolution).toBe('OVERRIDE')
    expect(status.provider?.id).toBe('o1')
    expect(status.resolvable).toBe(true)
  })

  it('marks a disabled override as blocking even when the default is usable', () => {
    const disabled = provider({ id: 'o1', name: 'Disabled override', enabled: false })
    const status = statusOf([provider({ id: 'd1', isDefault: true, enabled: true }), disabled], { AGENT: 'o1' }, 'AGENT')
    expect(status.resolution).toBe('OVERRIDE')
    expect(status.blocked).toBe('OVERRIDE_DISABLED')
    // 覆盖优先于默认：停用的覆盖不能让该用途回退到默认模型
    expect(status.resolvable).toBe(false)
  })

  it('falls back to the default when the override target no longer exists', () => {
    const status = statusOf([provider({ isDefault: true })], { PLANNING: 'missing-id' }, 'PLANNING')
    expect(status.staleOverride).toBe(true)
    expect(status.resolution).toBe('DEFAULT')
    expect(status.resolvable).toBe(true)
  })

  it('reports no resolution when a stale override has no default to fall back to', () => {
    const status = statusOf([provider({ isDefault: false })], { PLANNING: 'missing-id' }, 'PLANNING')
    expect(status.staleOverride).toBe(true)
    expect(status.blocked).toBe('NO_DEFAULT')
    expect(status.resolvable).toBe(false)
  })

  it('does not require a credential: keyless free presets stay resolvable', () => {
    const status = statusOf([provider({ isDefault: true, hasApiKey: false, presetCode: 'KILO_GATEWAY_FREE' })], {}, 'KNOWLEDGE_CHAT')
    expect(status.resolvable).toBe(true)
  })

  it('keeps probe outcomes keyed per provider and per capability', () => {
    expect(probeKey('p1', 'CONNECTIVITY')).not.toBe(probeKey('p1', 'STREAMING'))
    expect(probeKey('p1', 'CONNECTIVITY')).not.toBe(probeKey('p2', 'CONNECTIVITY'))
  })
})
