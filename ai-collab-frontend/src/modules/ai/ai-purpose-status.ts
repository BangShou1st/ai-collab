import type { AiPurpose, UserAiProvider } from './user-ai-api'

/** 三种 AI 用途的稳定顺序与显示名，与后端 `ModelPurpose` 枚举一致。 */
export const AI_PURPOSES: { value: AiPurpose; label: string }[] = [
  { value: 'KNOWLEDGE_CHAT', label: '知识问答' },
  { value: 'PLANNING', label: 'AI 规划' },
  { value: 'AGENT', label: 'Agent' },
]

/** 可选的探测能力，与后端 `AiCapabilityProbeService.Mode` 一致。 */
export type ProbeMode = 'CONNECTIVITY' | 'PLANNING_JSON' | 'STREAMING' | 'NATIVE_TOOLS'

export const PROBE_MODES: { value: ProbeMode; label: string }[] = [
  { value: 'CONNECTIVITY', label: '连接' },
  { value: 'PLANNING_JSON', label: '规划 JSON' },
  { value: 'STREAMING', label: '流式' },
  { value: 'NATIVE_TOOLS', label: '原生工具' },
]

/**
 * 用途覆盖失效原因。仅描述“为什么解析不到已启用模型”，
 * 不构成对真实调用成功、额度或服务健康的判断。
 */
export type PurposeBlockedReason =
  | 'OVERRIDE_DISABLED'
  | 'DEFAULT_DISABLED'
  | 'NO_DEFAULT'

export interface PurposeStatus {
  purpose: AiPurpose
  /** 解析来源：用途覆盖 / 默认模型 / 无可用配置 */
  resolution: 'OVERRIDE' | 'DEFAULT' | 'NONE'
  /** 解析到的 Provider 行（含预设）；NONE 时为 null */
  provider: UserAiProvider | null
  /** 覆盖指向的 Provider 已不存在，后端会回退到默认模型（外键级联下正常不会出现） */
  staleOverride: boolean
  /** 无法解析到已启用模型的原因；null 表示可解析 */
  blocked: PurposeBlockedReason | null
  /** 是否可解析到已启用的模型配置；不代表真实调用成功或服务持续健康 */
  resolvable: boolean
}

/**
 * 按后端 `UserAiProviderService.resolve` 的解析规则，在本地推导每个用途的配置状态：
 * 用途覆盖（Provider 存在时优先，即便已停用）→ 已启用默认模型 → 无可用配置。
 * 额外要求 Provider 已启用，因为停用的配置在调用时一定会被拒绝。
 *
 * 该函数只读取已加载的配置行，不触发任何模型调用；`hasApiKey` 不参与判断：
 * 免费预设（Zen/Kilo）无需用户密钥即可调用，凭据存在性不能证明可用性。
 */
export function resolvePurposeStatuses(
  providers: UserAiProvider[],
  overrides: Partial<Record<AiPurpose, string>>,
): PurposeStatus[] {
  const byId = new Map(providers.map((item) => [item.id, item]))
  const defaultProvider = providers.find((item) => item.isDefault) ?? null

  return AI_PURPOSES.map(({ value }) => {
    const assignedId = overrides[value]
    const target = assignedId ? byId.get(assignedId) ?? null : null
    if (target) {
      return finalize(value, 'OVERRIDE', target, false)
    }
    const staleOverride = Boolean(assignedId)
    return finalize(value, defaultProvider ? 'DEFAULT' : 'NONE', defaultProvider, staleOverride)
  })
}

function finalize(
  purpose: AiPurpose,
  resolution: PurposeStatus['resolution'],
  provider: UserAiProvider | null,
  staleOverride: boolean,
): PurposeStatus {
  if (!provider) {
    return { purpose, resolution: 'NONE', provider: null, staleOverride, blocked: 'NO_DEFAULT', resolvable: false }
  }
  if (!provider.enabled) {
    return {
      purpose,
      resolution,
      provider,
      staleOverride,
      blocked: resolution === 'OVERRIDE' ? 'OVERRIDE_DISABLED' : 'DEFAULT_DISABLED',
      resolvable: false,
    }
  }
  return { purpose, resolution, provider, staleOverride, blocked: null, resolvable: true }
}

/** 会话内探测结果键：按 Provider 与能力分离，避免不同能力互相替代。 */
export function probeKey(providerId: string, mode: ProbeMode): string {
  return `${providerId}:${mode}`
}

export interface ProbeOutcome {
  mode: ProbeMode
  ok: boolean
  at: number
  /** 失败时的可读原因（与提示条一致，不含凭据）。 */
  detail?: string
}
