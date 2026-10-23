import type { AiPurpose, ProbeResultView, UserAiProvider } from './user-ai-api'

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

/** 落库探针状态（后端持久化口径）+ 读取派生态。 */
export type ProbeOutcomeStatus = 'PASS' | 'FAIL' | 'NOT_SUPPORTED' | 'INCONCLUSIVE'

/** 前端展示态：落库状态之外，还有“未测试”与“已过期”（配置变更后旧结论失效）。 */
export type ProbeDisplayStatus = ProbeOutcomeStatus | 'NOT_TESTED' | 'STALE'

export interface ProbeResultState {
  mode: ProbeMode
  status: ProbeDisplayStatus
  model: string | null
  detail: string | null
  errorCode: string | null
  latencyMs: number | null
  testedAt: string | null
}

export const PROBE_STATUS_LABELS: Record<ProbeDisplayStatus, string> = {
  PASS: '通过',
  FAIL: '未通过',
  NOT_SUPPORTED: '不支持',
  INCONCLUSIVE: '无法得出结论',
  NOT_TESTED: '未测试',
  STALE: '已过期（配置已变更）',
}

/** 落库结果 → 展示态：stale 优先于落库状态（旧结论不描述当前配置）。 */
export function toProbeResultState(view: ProbeResultView): ProbeResultState {
  return {
    mode: view.mode,
    status: view.stale ? 'STALE' : view.status,
    model: view.model,
    detail: view.detail,
    errorCode: view.errorCode,
    latencyMs: view.latencyMs,
    testedAt: view.testedAt,
  }
}

/** 全部模式的未测试初始态。 */
export function emptyProbeResults(): Record<ProbeMode, ProbeResultState | null> {
  return { CONNECTIVITY: null, PLANNING_JSON: null, STREAMING: null, NATIVE_TOOLS: null }
}

export function probeResultsFromViews(views: ProbeResultView[]): Record<ProbeMode, ProbeResultState | null> {
  const out = emptyProbeResults()
  for (const view of views) out[view.mode] = toProbeResultState(view)
  return out
}

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
 * 用途就绪度：配置解析之上叠加“能力声明 + 探针证据”。
 * CONNECTIVITY 通过不外推——每个用途只认它依赖的那项探针。
 */
export type PurposeReadiness = 'READY' | 'UNAVAILABLE' | 'UNVERIFIED' | 'NOT_SUPPORTED'

/** 各用途依赖的能力声明与探针（与后端 ModelCapabilityPolicy 门控一致）。 */
export const PURPOSE_REQUIREMENTS: Record<AiPurpose, { capabilities: string[]; probe: ProbeMode }> = {
  // 知识问答走流式 SSE：需要 STREAMING 声明 + 流式探针。
  KNOWLEDGE_CHAT: { capabilities: ['CHAT', 'STREAMING'], probe: 'STREAMING' },
  // 规划用 PROMPT_JSON 提示词（不触发 STRUCTURED_OUTPUT 门控）：需要规划契约探针。
  PLANNING: { capabilities: ['CHAT'], probe: 'PLANNING_JSON' },
  // Agent 全链路走 turn 网关：需要原生工具声明 + 探针。
  AGENT: { capabilities: ['CHAT', 'NATIVE_TOOLS'], probe: 'NATIVE_TOOLS' },
}

export const READINESS_LABELS: Record<PurposeReadiness, string> = {
  READY: '可用',
  UNAVAILABLE: '不可用',
  UNVERIFIED: '未验证',
  NOT_SUPPORTED: '未声明所需能力',
}

/**
 * 计算单个用途的就绪度：先看能力声明（声明缺失=NOT_SUPPORTED，探针也测不了），
 * 再看该用途依赖的探针结果（PASS=READY；FAIL=UNAVAILABLE；其余=UNVERIFIED）。
 * 探针结果过期（STALE）或无结论（INCONCLUSIVE）都算 UNVERIFIED，不能算可用。
 */
export function purposeReadiness(
  purpose: AiPurpose,
  provider: UserAiProvider | null,
  probeResults: Record<ProbeMode, ProbeResultState | null>,
): PurposeReadiness | null {
  if (!provider) return null
  const requirement = PURPOSE_REQUIREMENTS[purpose]
  const declared = requirement.capabilities.every((capability) => provider.capabilities.includes(capability))
  if (!declared) return 'NOT_SUPPORTED'
  const probe = probeResults[requirement.probe]
  if (!probe || probe.status === 'NOT_TESTED' || probe.status === 'STALE' || probe.status === 'INCONCLUSIVE') {
    return 'UNVERIFIED'
  }
  return probe.status === 'PASS' ? 'READY' : 'UNAVAILABLE'
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
