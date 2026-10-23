// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { staticCheck, useCapabilitySuite, SUITE_STEPS } from './use-capability-suite'
import type { UserAiProvider } from './user-ai-api'

const apiMocks = vi.hoisted(() => ({
  testCapability: vi.fn(),
  probeResults: vi.fn(),
  models: vi.fn(),
}))

vi.mock('./user-ai-api', () => ({
  userAiApi: {
    testCapability: apiMocks.testCapability,
    probeResults: apiMocks.probeResults,
    models: apiMocks.models,
  },
}))

const provider = (over: Partial<UserAiProvider> = {}): UserAiProvider => ({
  id: 'p-1', name: '我的网关', presetCode: null, providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.example.com', apiPath: '/v1/chat/completions', hasApiKey: true,
  modelName: 'my-model', enabled: true, isDefault: true, temperature: 0.2, maxOutputTokens: 1200,
  capabilities: ['CHAT', 'STREAMING'], updatedAt: '2026-10-20T00:00:00Z', ...over,
})

const staticConfig = {
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.example.com',
  apiPath: '/v1/chat/completions',
  modelName: 'my-model',
  capabilities: ['CHAT', 'STREAMING'],
}

beforeEach(() => {
  vi.resetAllMocks()
  apiMocks.probeResults.mockResolvedValue({ data: [] })
})

describe('staticCheck', () => {
  it('accepts a valid configuration including the {model} placeholder', () => {
    expect(staticCheck({
      providerType: 'GEMINI',
      baseUrl: 'https://generativelanguage.googleapis.com',
      apiPath: '/v1beta/models/{model}:generateContent',
      modelName: 'gemini-flash',
      capabilities: ['CHAT', 'STREAMING'],
    }).ok).toBe(true)
  })

  it('rejects non-https base urls, relative-less paths and unknown placeholders', () => {
    expect(staticCheck({ ...staticConfig, baseUrl: 'http://api.example.com' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, baseUrl: '' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, apiPath: 'v1/chat/completions' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, apiPath: '/v1/{modelId}:x' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, apiPath: '/v1/{model}/{model}:x' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, modelName: '' }).ok).toBe(false)
    expect(staticCheck({ ...staticConfig, capabilities: [] }).ok).toBe(false)
  })
})

describe('capability suite', () => {
  it('runs static → discovery → connectivity → streaming in order on one confirmation', async () => {
    apiMocks.models.mockResolvedValue({ data: ['my-model'] })
    apiMocks.testCapability.mockResolvedValue({ data: {} })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.STATIC).toMatchObject({ status: 'PASS' })
    expect(steps.DISCOVERY).toMatchObject({ status: 'PASS' })
    expect(steps.CONNECTIVITY).toMatchObject({ status: 'PASS' })
    expect(steps.STREAMING).toMatchObject({ status: 'PASS' })
    expect(apiMocks.models).toHaveBeenCalledWith('p-1', false)
    expect(apiMocks.testCapability.mock.calls.map((c) => c[1])).toEqual(['CONNECTIVITY', 'STREAMING'])
    // 未勾选的可选步骤被显式跳过，不发起请求
    expect(apiMocks.testCapability).not.toHaveBeenCalledWith('p-1', 'PLANNING_JSON')
    expect(steps.PLANNING_JSON).toMatchObject({ status: 'SKIPPED' })
  })

  it('stops after a confirmed connectivity failure and marks the rest skipped', async () => {
    apiMocks.models.mockResolvedValue({ data: [] })
    apiMocks.testCapability.mockRejectedValueOnce(Object.assign(new Error('401'), {
      isAxiosError: true, response: { status: 401, data: { code: 'AI_MODEL_CREDENTIAL_INVALID', message: 'Key 无效' } },
    }))
    apiMocks.probeResults.mockResolvedValue({
      data: [{ mode: 'CONNECTIVITY', status: 'FAIL', model: 'my-model', detail: 'Key 无效', errorCode: 'AI_MODEL_CREDENTIAL_INVALID', latencyMs: 12, testedAt: '2026-10-24T00:00:00Z', stale: false }],
    })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: true, nativeTools: false }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.CONNECTIVITY).toMatchObject({ status: 'FAIL' })
    expect(steps.STREAMING).toMatchObject({ status: 'SKIPPED' })
    expect(steps.PLANNING_JSON).toMatchObject({ status: 'SKIPPED' })
    expect(apiMocks.testCapability).toHaveBeenCalledTimes(1)
  })

  it('keeps going after an inconclusive step (transient failures are not verdicts)', async () => {
    apiMocks.models.mockRejectedValue(Object.assign(new Error('timeout'), {
      isAxiosError: true, response: { status: 504, data: { code: 'AI_MODEL_TIMEOUT', message: '超时' } },
    }))
    apiMocks.testCapability.mockResolvedValue({ data: {} })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    // 发现失败不判 FAIL，也不阻断后续生成测试
    expect(steps.DISCOVERY).toMatchObject({ status: 'INCONCLUSIVE' })
    expect(steps.STREAMING).toMatchObject({ status: 'PASS' })
  })

  it('classifies undeclared capability as NOT_SUPPORTED via persisted verdict', async () => {
    apiMocks.models.mockResolvedValue({ data: ['my-model'] })
    apiMocks.testCapability.mockResolvedValueOnce({ data: {} })
    apiMocks.testCapability.mockRejectedValueOnce(Object.assign(new Error('policy'), {
      isAxiosError: true, response: { status: 503, data: { code: 'AI_PROVIDER_UNAVAILABLE', message: '当前模型配置不支持 STREAMING' } },
    }))
    apiMocks.probeResults.mockResolvedValue({
      data: [{ mode: 'STREAMING', status: 'NOT_SUPPORTED', model: 'my-model', detail: '当前模型配置不支持 STREAMING', errorCode: 'AI_PROVIDER_UNAVAILABLE', latencyMs: 3, testedAt: '2026-10-24T00:00:00Z', stale: false }],
    })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider({ capabilities: ['CHAT'] }), staticConfig,
    })
    expect(suite.runs['p-1']!.steps.STREAMING).toMatchObject({ status: 'NOT_SUPPORTED' })
  })

  it('stops at static failure without any outbound request', async () => {
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider(), staticConfig: { ...staticConfig, baseUrl: 'http://insecure.example.com' },
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.STATIC).toMatchObject({ status: 'FAIL' })
    expect(apiMocks.models).not.toHaveBeenCalled()
    expect(apiMocks.testCapability).not.toHaveBeenCalled()
  })

  it('verifies preset admission against the admitted model list', async () => {
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider({ presetCode: 'OPENCODE_ZEN_FREE', modelName: 'mimo-v2.5-free' }),
      staticConfig,
      admittedModels: ['mimo-v2.5-free'],
    })
    expect(suite.runs['p-1']!.steps.DISCOVERY).toMatchObject({ status: 'PASS' })
    // 预设不走自定义发现通道
    expect(apiMocks.models).not.toHaveBeenCalled()
  })

  it('suite steps declare an explicit real-request budget', () => {
    // 每个真实生成步骤的预算必须显式声明，供 UI 展示请求量。
    const budgets = Object.fromEntries(SUITE_STEPS.map((s) => [s.key, s.requestBudget]))
    expect(budgets).toEqual({ STATIC: 0, DISCOVERY: 0, CONNECTIVITY: 1, STREAMING: 1, PLANNING_JSON: 4, NATIVE_TOOLS: 1 })
  })
})
