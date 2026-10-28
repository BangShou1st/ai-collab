import { userAiApi, type AiProviderType, type ProviderProfile } from './user-ai-api'

/**
 * 内置 fallback：后端 profiles 接口不可用时（如网络故障）表单仍能给出正确默认值。
 * 该表必须与后端 `ProviderProfileCatalog` 保持一致——单一事实源在后端，
 * 这里只是降级快照；正常路径总是优先使用后端返回。
 */
export const BUILTIN_PROVIDER_PROFILES: ProviderProfile[] = [
  {
    code: 'OPENAI_COMPATIBLE',
    providerType: 'OPENAI_COMPATIBLE',
    label: 'OpenAI 兼容',
    defaultBaseUrl: 'https://api.openai.com',
    defaultApiPath: '/v1/chat/completions',
    apiKeyRequired: true,
    modelDiscovery: true,
    candidateCapabilities: ['CHAT', 'STREAMING', 'STRUCTURED_OUTPUT', 'NATIVE_TOOLS'],
    apiPathHint: '推理端点路径，通常为 /v1/chat/completions；模型发现自动使用同前缀的 /models',
  },
  {
    code: 'MODELSCOPE',
    providerType: 'OPENAI_COMPATIBLE',
    label: '魔搭 ModelScope',
    defaultBaseUrl: 'https://api-inference.modelscope.cn',
    defaultApiPath: '/v1/chat/completions',
    apiKeyRequired: true,
    modelDiscovery: true,
    candidateCapabilities: ['CHAT', 'STREAMING', 'STRUCTURED_OUTPUT', 'NATIVE_TOOLS'],
    apiPathHint: '官方 OpenAI 兼容路径 /v1/chat/completions；Base URL 无需再带 /v1（默认已按此拆分，避免 /v1/v1 重复）',
  },
  {
    code: 'ANTHROPIC',
    providerType: 'ANTHROPIC',
    label: 'Anthropic',
    defaultBaseUrl: 'https://api.anthropic.com',
    defaultApiPath: '/v1/messages',
    apiKeyRequired: true,
    modelDiscovery: true,
    candidateCapabilities: ['CHAT', 'STREAMING', 'NATIVE_TOOLS'],
    apiPathHint: '推理端点路径，官方为 /v1/messages；模型发现自动使用同前缀的 /models',
  },
  {
    code: 'GEMINI',
    providerType: 'GEMINI',
    label: 'Google Gemini',
    defaultBaseUrl: 'https://generativelanguage.googleapis.com',
    defaultApiPath: '/v1beta/models/{model}:generateContent',
    apiKeyRequired: true,
    modelDiscovery: true,
    candidateCapabilities: ['CHAT', 'STREAMING', 'NATIVE_TOOLS'],
    apiPathHint: '官方路径为 /v1beta/models/{model}:generateContent，{model} 会替换为模型 ID；流式自动切换 :streamGenerateContent',
  },
]

/** 表单新配置的安全初始能力：所有协议都支持，用户可再勾选其余候选。 */
export const DEFAULT_DECLARED_CAPABILITIES = ['CHAT', 'STREAMING']

/** 按档案 code 查找；编辑既有配置时按协议类型匹配主档案（code 与类型同名）。 */
export function profileByCode(profiles: ProviderProfile[], code: string): ProviderProfile | undefined {
  return profiles.find((profile) => profile.code === code)
}

export function profileByType(profiles: ProviderProfile[], type: AiProviderType): ProviderProfile {
  return (
    profileByCode(profiles, type) ??
    profiles.find((profile) => profile.providerType === type && profile.code === type) ??
    BUILTIN_PROVIDER_PROFILES[0]
  )
}

/**
 * 加载接入档案：优先后端（单一事实源），失败时静默降级到内置快照，
 * 不阻塞页面加载——表单默认值不是关键数据，不该让整页报错。
 */
export async function loadProviderProfiles(): Promise<ProviderProfile[]> {
  try {
    const result = await userAiApi.profiles()
    if (result.data?.length) return result.data
  } catch {
    // 降级到内置快照
  }
  return BUILTIN_PROVIDER_PROFILES
}
