import { reactive } from 'vue'
import { normalizeApiError } from '../../api/api-result'
import { userAiApi, type UserAiProvider } from './user-ai-api'
import {
  probeResultsFromViews,
  type ProbeMode,
  type ProbeResultState,
} from './ai-purpose-status'

/**
 * 统一“检测并配置”编排：三个接入入口（自定义 / Zen / Kilo）共用同一面板。
 *
 * 用户一次确认后，前端只发起一次后端编排调用；串行步骤（静态检查 → 模型发现/准入
 * → 连接 → 流式 →（可选）规划 →（可选）原生工具）与 PASS 能力的 CAS 写回都在后端
 * 执行——同一 Provider 同时只允许一个检测任务由后端互斥保证，前端仅做防重复点击。
 *
 * 出站预算（与后端一致）：连接 1 次、流式 1 次、规划最多 4 次（骨架+修复+细节+修复）、
 * 原生工具 1 次；模型发现为只读 HTTP 请求另计。FAIL/NOT_SUPPORTED 中止后续步骤，
 * INCONCLUSIVE（临时故障）继续；探针结论以后端落库分类为准。
 */
export type SuiteStepKind = 'STATIC' | 'DISCOVERY' | 'PROBE'

export interface SuiteStep {
  key: string
  kind: SuiteStepKind
  label: string
  /** 真实生成请求预算：静态/发现为 0，普通探针 1，规划探针最多 4（骨架+修复+细节+修复）。 */
  requestBudget: number
  optional: boolean
}

export type SuiteStepState =
  | { status: 'PENDING' }
  | { status: 'RUNNING' }
  | { status: 'PASS'; detail?: string; latencyMs?: number }
  | { status: 'FAIL' | 'INCONCLUSIVE' | 'NOT_SUPPORTED'; detail: string; errorCode?: string }
  | { status: 'SKIPPED'; detail: string }

export interface SuiteOptions {
  /** 是否包含规划契约测试（PLANNING_JSON）。 */
  planning: boolean
  /** 是否包含原生工具测试（NATIVE_TOOLS）。 */
  nativeTools: boolean
}

export const SUITE_STEPS: SuiteStep[] = [
  { key: 'STATIC', kind: 'STATIC', label: '静态配置检查', requestBudget: 0, optional: false },
  { key: 'DISCOVERY', kind: 'DISCOVERY', label: '模型发现 / 准入', requestBudget: 0, optional: false },
  { key: 'CONNECTIVITY', kind: 'PROBE', label: '连接（真实生成）', requestBudget: 1, optional: false },
  { key: 'STREAMING', kind: 'PROBE', label: '流式输出', requestBudget: 1, optional: false },
  { key: 'PLANNING_JSON', kind: 'PROBE', label: '规划 JSON（骨架 + 细节契约）', requestBudget: 4, optional: true },
  { key: 'NATIVE_TOOLS', kind: 'PROBE', label: '原生工具调用', requestBudget: 1, optional: true },
]

/** 按用户选择的深度能力给出真实生成请求预算（与后端编排一致）。 */
export function suiteRequestBudget(options: SuiteOptions): number {
  return 2 + (options.planning ? 4 : 0) + (options.nativeTools ? 1 : 0)
}

/** 套件步骤 → 对应探针模式（静态/发现无对应探针）。 */
function probeModeOf(step: SuiteStep): ProbeMode | null {
  return step.kind === 'PROBE' ? (step.key as ProbeMode) : null
}

export interface SuiteRunState {
  running: boolean
  steps: Record<string, SuiteStepState>
  /** 套件完成后刷新的落库探针结果（含 STALE 判定）。 */
  persisted: Record<ProbeMode, ProbeResultState | null>
  /** 能力自动写回结果（applied=false 时 reason 说明原因）。 */
  writeBack: { applied: boolean; reason: string | null } | null
}

export interface StaticConfigInput {
  providerType: string
  baseUrl: string
  apiPath: string
  modelName: string
  capabilities: string[]
}

/** 本地静态检查：与后端保存校验同口径，不触网、不消耗请求。 */
export function staticCheck(config: StaticConfigInput): { ok: boolean; detail?: string } {
  if (!config.baseUrl.trim()) return { ok: false, detail: 'Base URL 不能为空' }
  if (!/^https:\/\/[^\s/]+/i.test(config.baseUrl.trim())) {
    return { ok: false, detail: 'Base URL 必须是 https:// 开头的完整地址' }
  }
  const apiPath = config.apiPath.trim()
  if (!apiPath) return { ok: false, detail: 'API Path 不能为空' }
  if (!apiPath.startsWith('/')) return { ok: false, detail: 'API Path 必须以 / 开头（相对路径）' }
  const sanitized = apiPath.replace(/\{model\}/g, 'model-placeholder')
  if (/[{}]/.test(sanitized)) return { ok: false, detail: 'API Path 仅支持 {model} 占位符' }
  if ((apiPath.match(/\{model\}/g) ?? []).length > 1) return { ok: false, detail: '{model} 占位符至多出现一次' }
  if (!config.modelName.trim()) return { ok: false, detail: '模型 ID 不能为空' }
  if (!config.capabilities.length) return { ok: false, detail: '至少声明一项能力' }
  return { ok: true }
}

export function useCapabilitySuite() {
  /** providerId → 套件运行状态（每 provider 独立，互不覆盖）。 */
  const runs = reactive<Record<string, SuiteRunState>>({})

  function runState(providerId: string): SuiteRunState {
    if (!runs[providerId]) {
      runs[providerId] = {
        running: false,
        steps: {},
        persisted: probeResultsFromViews([]),
        writeBack: null,
      }
    }
    return runs[providerId]
  }

  function reset(providerId: string): void {
    delete runs[providerId]
  }

  async function refreshPersisted(providerId: string): Promise<void> {
    try {
      const result = await userAiApi.probeResults(providerId)
      runState(providerId).persisted = probeResultsFromViews(result.data ?? [])
    } catch {
      // 落库结果刷新失败不阻断：套件即时结果仍然有效
    }
  }

  /** 静态/发现步骤（无落库）的失败归入临时故障口径：无法发现 ≠ 生成不可用。 */
  function inconclusive(error: unknown): SuiteStepState {
    const normalized = normalizeApiError(error)
    return { status: 'INCONCLUSIVE', detail: normalized.message, errorCode: normalized.code }
  }

  /**
   * 执行「检测并配置」。provider 为 null 表示行尚未保存（仅静态检查可运行）；
   * 运行中重复调用直接忽略（后端另有互斥兜底）。
   */
  async function run(
    providerId: string | null,
    options: SuiteOptions,
    context: {
      provider: UserAiProvider | null
      staticConfig: StaticConfigInput
      /** 预设/自定义的已知准入模型列表；预设用于准入核对，自定义用于发现降级提示。 */
      admittedModels?: string[]
    },
  ): Promise<void> {
    const stateKey = providerId ?? '__unsaved__'
    const state = runState(stateKey)
    if (state.running) return
    state.running = true
    state.writeBack = null
    try {
      for (const step of SUITE_STEPS) {
        const selected =
          (step.kind !== 'PROBE') ||
          (step.key === 'PLANNING_JSON' && options.planning) ||
          (step.key === 'NATIVE_TOOLS' && options.nativeTools)
        state.steps[step.key] = selected
          ? { status: 'PENDING' }
          : { status: 'SKIPPED', detail: step.key === 'PLANNING_JSON' ? '未选择规划测试' : '未选择工具测试' }
      }
      // 静态检查（0 次模型请求）
      state.steps.STATIC = { status: 'RUNNING' }
      const check = staticCheck(context.staticConfig)
      state.steps.STATIC = check.ok ? { status: 'PASS' } : { status: 'FAIL', detail: check.detail ?? '静态配置不完整' }
      if (!check.ok || !providerId) {
        if (!providerId && check.ok) state.steps.DISCOVERY = { status: 'SKIPPED', detail: '配置尚未保存，请先保存再测试' }
        for (const step of SUITE_STEPS) {
          if (step.kind === 'PROBE' && state.steps[step.key].status === 'PENDING') {
            state.steps[step.key] = { status: 'SKIPPED', detail: check.ok ? '配置尚未保存，请先保存再测试' : '静态检查未通过，未发起请求' }
          }
        }
        if (providerId) await refreshPersisted(providerId)
        return
      }
      // 模型发现/准入（只读请求）
      state.steps.DISCOVERY = await runDiscovery(providerId, context)
      // 探针编排：后端串行执行并写回，前端按步骤映射展示
      for (const step of SUITE_STEPS) {
        if (probeModeOf(step)) state.steps[step.key] = { status: 'RUNNING' }
      }
      try {
        const result = await userAiApi.detectAndConfigure(providerId, {
          planning: options.planning,
          nativeTools: options.nativeTools,
        })
        const steps = result.data?.steps ?? []
        for (const step of steps) {
          const suiteStep = SUITE_STEPS.find((item) => item.key === step.mode)
          if (!suiteStep) continue
          if (step.status === 'PASS') {
            state.steps[step.mode] = { status: 'PASS', latencyMs: step.latencyMs ?? undefined }
          } else if (step.status === 'SKIPPED') {
            state.steps[step.mode] = { status: 'SKIPPED', detail: step.detail ?? '前序步骤确认失败，未发起请求' }
          } else {
            state.steps[step.mode] = {
              status: step.status,
              detail: step.detail ?? '所选能力测试未满足输出契约',
              errorCode: step.errorCode ?? undefined,
            }
          }
        }
        // 后端只返回被选中的模式：未选中的保持“已跳过”，不得停留在 RUNNING
        for (const suiteStep of SUITE_STEPS) {
          const mode = probeModeOf(suiteStep)
          if (!mode) continue
          const current = state.steps[suiteStep.key]
          if (current.status === 'RUNNING' || current.status === 'PENDING') {
            state.steps[suiteStep.key] = { status: 'SKIPPED', detail: '未选择该能力测试' }
          }
        }
        state.writeBack = result.data?.writeBack ?? null
      } catch (error) {
        // 后端编排整体失败（如并发互斥拒绝）：全部探针步骤标记为该错误
        const normalized = normalizeApiError(error)
        for (const step of SUITE_STEPS) {
          if (!probeModeOf(step)) continue
          const current = state.steps[step.key]
          if (current.status === 'RUNNING' || current.status === 'PENDING') {
            state.steps[step.key] = { status: 'INCONCLUSIVE', detail: normalized.message, errorCode: normalized.code }
          }
        }
      }
      await refreshPersisted(providerId)
    } finally {
      state.running = false
    }
  }

  /**
   * 模型发现/准入：预设核对当前模型是否仍在准入列表；自定义尝试 /models。
   * 发现失败不判 FAIL——列表不可用不代表真实生成失败，可手动填写模型 ID。
   */
  async function runDiscovery(
    providerId: string,
    context: { provider: UserAiProvider | null; admittedModels?: string[] },
  ): Promise<SuiteStepState> {
    const provider = context.provider
    if (provider?.presetCode) {
      const admitted = context.admittedModels ?? []
      if (provider.modelName && admitted.includes(provider.modelName)) {
        return { status: 'PASS', detail: `${provider.modelName} 在准入列表中` }
      }
      return {
        status: 'INCONCLUSIVE',
        detail: admitted.length
          ? `当前模型 ${provider.modelName || '（未选择）'} 不在准入列表，请重新选择`
          : '模型列表暂不可用，无法核对准入状态',
      }
    }
    try {
      const result = await userAiApi.models(providerId, false)
      const models = result.data ?? []
      if (provider?.modelName && !models.includes(provider.modelName)) {
        return {
          status: 'INCONCLUSIVE',
          detail: `模型列表中未见 ${provider.modelName}（列表共 ${models.length} 项）；可能是自定义网关目录不完整，可保留手填值继续测试生成`,
        }
      }
      return { status: 'PASS', detail: `发现 ${models.length} 个模型` }
    } catch (error) {
      return inconclusive(error)
    }
  }

  return { runs, run, reset, refreshPersisted, runState }
}
