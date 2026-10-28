// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { staticCheck, useCapabilitySuite, SUITE_STEPS, suiteRequestBudget } from './use-capability-suite'
import type { UserAiProvider } from './user-ai-api'

const apiMocks = vi.hoisted(() => ({
  detectAndConfigure: vi.fn(),
  probeResults: vi.fn(),
  models: vi.fn(),
}))

vi.mock('./user-ai-api', () => ({
  userAiApi: {
    detectAndConfigure: apiMocks.detectAndConfigure,
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
  apiMocks.models.mockResolvedValue({ data: ['my-model'] })
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

describe('detect-and-configure suite', () => {
  it('issues one backend orchestration call and maps per-step verdicts', async () => {
    apiMocks.detectAndConfigure.mockResolvedValue({
      data: {
        steps: [
          { mode: 'CONNECTIVITY', status: 'PASS', detail: null, errorCode: null, latencyMs: 42 },
          { mode: 'STREAMING', status: 'PASS', detail: null, errorCode: null, latencyMs: 60 },
          { mode: 'NATIVE_TOOLS', status: 'PASS', detail: null, errorCode: null, latencyMs: 88 },
        ],
        writeBack: { applied: true, reason: null, capabilitiesAfter: ['CHAT', 'STREAMING', 'NATIVE_TOOLS'] },
        generationRequests: 3,
      },
    })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: true }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.STATIC).toMatchObject({ status: 'PASS' })
    expect(steps.DISCOVERY).toMatchObject({ status: 'PASS' })
    expect(steps.CONNECTIVITY).toMatchObject({ status: 'PASS' })
    expect(steps.STREAMING).toMatchObject({ status: 'PASS' })
    expect(steps.NATIVE_TOOLS).toMatchObject({ status: 'PASS' })
    expect(steps.PLANNING_JSON).toMatchObject({ status: 'SKIPPED' })
    expect(apiMocks.detectAndConfigure).toHaveBeenCalledTimes(1)
    expect(apiMocks.detectAndConfigure).toHaveBeenCalledWith('p-1', { planning: false, nativeTools: true })
    expect(suite.runs['p-1']!.writeBack).toMatchObject({ applied: true })
  })

  it('maps SKIPPED steps and failed verdicts from the backend result', async () => {
    apiMocks.detectAndConfigure.mockResolvedValue({
      data: {
        steps: [
          { mode: 'CONNECTIVITY', status: 'PASS', detail: null, errorCode: null, latencyMs: 30 },
          { mode: 'STREAMING', status: 'FAIL', detail: 'Key 无效', errorCode: 'AI_MODEL_CREDENTIAL_INVALID', latencyMs: 12 },
          { mode: 'PLANNING_JSON', status: 'SKIPPED', detail: '前序步骤确认失败，本步骤未发起请求', errorCode: null, latencyMs: null },
          { mode: 'NATIVE_TOOLS', status: 'SKIPPED', detail: '前序步骤确认失败，本步骤未发起请求', errorCode: null, latencyMs: null },
        ],
        writeBack: { applied: false, reason: '检测期间配置已变更', capabilitiesAfter: ['CHAT'] },
        generationRequests: 2,
      },
    })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: true, nativeTools: true }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.STREAMING).toMatchObject({ status: 'FAIL', detail: 'Key 无效' })
    expect(steps.PLANNING_JSON).toMatchObject({ status: 'SKIPPED' })
    expect(steps.NATIVE_TOOLS).toMatchObject({ status: 'SKIPPED' })
    expect(suite.runs['p-1']!.writeBack).toMatchObject({ applied: false, reason: '检测期间配置已变更' })
  })

  it('keeps discovery failure non-blocking: generation tests still run', async () => {
    apiMocks.models.mockRejectedValue(Object.assign(new Error('timeout'), {
      isAxiosError: true, response: { status: 504, data: { code: 'AI_MODEL_TIMEOUT', message: '超时' } },
    }))
    apiMocks.detectAndConfigure.mockResolvedValue({
      data: {
        steps: [
          { mode: 'CONNECTIVITY', status: 'PASS', detail: null, errorCode: null, latencyMs: 30 },
          { mode: 'STREAMING', status: 'PASS', detail: null, errorCode: null, latencyMs: 40 },
        ],
        writeBack: { applied: true, reason: null, capabilitiesAfter: ['CHAT', 'STREAMING'] },
        generationRequests: 2,
      },
    })
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider(), staticConfig,
    })
    const steps = suite.runs['p-1']!.steps
    // 发现失败不判 FAIL，也不阻断后续生成测试
    expect(steps.DISCOVERY).toMatchObject({ status: 'INCONCLUSIVE' })
    expect(steps.STREAMING).toMatchObject({ status: 'PASS' })
    expect(apiMocks.detectAndConfigure).toHaveBeenCalledTimes(1)
  })

  it('stops at static failure without any outbound request', async () => {
    const suite = useCapabilitySuite()
    await suite.run('p-1', { planning: false, nativeTools: false }, {
      provider: provider(), staticConfig: { ...staticConfig, baseUrl: 'http://insecure.example.com' },
    })
    const steps = suite.runs['p-1']!.steps
    expect(steps.STATIC).toMatchObject({ status: 'FAIL' })
    expect(apiMocks.models).not.toHaveBeenCalled()
    expect(apiMocks.detectAndConfigure).not.toHaveBeenCalled()
  })

  it('unsaved providers only run the static check', async () => {
    const suite = useCapabilitySuite()
    await suite.run(null, { planning: false, nativeTools: false }, {
      provider: null, staticConfig,
    })
    const steps = suite.runs['__unsaved__']!.steps
    expect(steps.STATIC).toMatchObject({ status: 'PASS' })
    expect(steps.DISCOVERY).toMatchObject({ status: 'SKIPPED' })
    expect(steps.CONNECTIVITY).toMatchObject({ status: 'SKIPPED' })
    expect(apiMocks.detectAndConfigure).not.toHaveBeenCalled()
  })

  it('ignores re-entrant invocation while a run is in flight', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    apiMocks.detectAndConfigure.mockImplementation(() => gate.then(() => ({
      data: { steps: [], writeBack: { applied: false, reason: null, capabilitiesAfter: [] }, generationRequests: 0 },
    })))
    const suite = useCapabilitySuite()
    const first = suite.run('p-1', { planning: false, nativeTools: false }, { provider: provider(), staticConfig })
    await Promise.resolve()
    const second = suite.run('p-1', { planning: false, nativeTools: false }, { provider: provider(), staticConfig })
    release()
    await Promise.all([first, second])
    expect(apiMocks.detectAndConfigure).toHaveBeenCalledTimes(1)
  })

  it('verifies preset admission against the admitted model list', async () => {
    apiMocks.detectAndConfigure.mockResolvedValue({
      data: { steps: [], writeBack: { applied: true, reason: null, capabilitiesAfter: [] }, generationRequests: 0 },
    })
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

  it('computes the total generation budget from selected deep probes', () => {
    expect(suiteRequestBudget({ planning: false, nativeTools: false })).toBe(2)
    expect(suiteRequestBudget({ planning: true, nativeTools: false })).toBe(6)
    expect(suiteRequestBudget({ planning: false, nativeTools: true })).toBe(3)
    expect(suiteRequestBudget({ planning: true, nativeTools: true })).toBe(7)
  })
})
