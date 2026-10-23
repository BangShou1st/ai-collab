import { reactive } from 'vue'
import { normalizeApiError } from '../../api/api-result'
import { userAiApi, type UserAiProvider } from './user-ai-api'
import {
  probeResultsFromViews,
  type ProbeDisplayStatus,
  type ProbeMode,
  type ProbeResultState,
} from './ai-purpose-status'

/**
 * 统一“测试连接与能力”套件：三个接入入口（自定义 / Zen / Kilo）共用同一编排。
 *
 * 步骤串行执行、逐步上报：静态检查（本地，无出站）→ 模型发现/准入（只读 GET）
 * → 连接（真实生成）→ 流式 → 规划（按需）→ 原生工具（按需）。
 * 只有用户点击按钮才发起；套件内每一步真实生成请求各至多一次（规划探针
 * 后端按生产口径含一次定向修复，最多两阶段共 4 次请求）。
 *
 * FAIL（确定失败）会停止后续步骤并标记 SKIPPED；INCONCLUSIVE（临时故障）
 * 不中断——临时网络问题不代表后续能力结论。探针结论以后端落库分类为准。
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

/** 套件步骤 → 对应探针模式（静态/发现无对应探针）。 */
function probeModeOf(step: SuiteStep): ProbeMode | null {
  return step.kind === 'PROBE' ? (step.key as ProbeMode) : null
}

export interface SuiteRunState {
  running: boolean
  steps: Record<string, SuiteStepState>
  /** 套件完成后刷新的落库探针结果（含 STALE 判定）。 */
  persisted: Record<ProbeMode, ProbeResultState | null>
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
      runs[providerId] = { running: false, steps: {}, persisted: probeResultsFromViews([]) }
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

  /** 失败异常 → 步骤终态：优先采用后端落库分类（重新拉取），否则用错误码推导。 */
  async function classifyFailure(providerId: string, step: SuiteStep, error: unknown): Promise<SuiteStepState> {
    const normalized = normalizeApiError(error)
    const mode = probeModeOf(step)
    if (mode) {
      // 后端已把失败分类落库；以后端分类为准。
      await refreshPersisted(providerId)
      const persisted = runState(providerId).persisted[mode]
      if (persisted && persisted.status !== 'NOT_TESTED' && persisted.status !== 'STALE') {
        return {
          status: persisted.status as 'FAIL' | 'INCONCLUSIVE' | 'NOT_SUPPORTED',
          detail: persisted.detail ?? normalized.message,
          errorCode: persisted.errorCode ?? normalized.code,
        }
      }
    }
    // 静态/发现步骤（无落库）按错误码归入临时故障口径：无法发现 ≠ 生成不可用。
    return { status: 'INCONCLUSIVE', detail: normalized.message, errorCode: normalized.code }
  }

  /**
   * 执行套件。串行逐步执行；provider 为 null 表示行尚未保存（静态检查仍可运行）。
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
    const selected = SUITE_STEPS.filter((step) => {
      if (!step.optional) return true
      if (step.key === 'PLANNING_JSON') return options.planning
      if (step.key === 'NATIVE_TOOLS') return options.nativeTools
      return false
    })
    for (const step of SUITE_STEPS) {
      state.steps[step.key] = selected.includes(step)
        ? { status: 'PENDING' }
        : { status: 'SKIPPED', detail: step.key === 'PLANNING_JSON' ? '未选择规划测试' : '未选择工具测试' }
    }
    try {
      let hardStop = false
      for (const step of selected) {
        if (hardStop) {
          state.steps[step.key] = { status: 'SKIPPED', detail: '前序步骤已确认失败，本步骤不再发起请求' }
          continue
        }
        state.steps[step.key] = { status: 'RUNNING' }
        if (step.kind === 'STATIC') {
          const check = staticCheck(context.staticConfig)
          state.steps[step.key] = check.ok
            ? { status: 'PASS' }
            : { status: 'FAIL', detail: check.detail ?? '静态配置不完整' }
          if (!check.ok) hardStop = true
          continue
        }
        if (!providerId) {
          state.steps[step.key] = { status: 'SKIPPED', detail: '配置尚未保存，请先保存再测试' }
          continue
        }
        if (step.kind === 'DISCOVERY') {
          state.steps[step.key] = await runDiscovery(providerId, context)
          continue
        }
        const mode = probeModeOf(step)
        if (!mode) continue
        try {
          await userAiApi.testCapability(providerId, mode)
          state.steps[step.key] = { status: 'PASS' }
        } catch (error) {
          const failure = await classifyFailure(providerId, step, error)
          state.steps[step.key] = failure
          if (failure.status === 'FAIL' || failure.status === 'NOT_SUPPORTED') hardStop = true
        }
      }
      if (providerId) await refreshPersisted(providerId)
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
      const normalized = normalizeApiError(error)
      return {
        status: 'INCONCLUSIVE',
        detail: `模型列表不可用（${normalized.message}）；不影响后续生成测试，可手动确认模型 ID`,
      }
    }
  }

  return { runs, run, reset, refreshPersisted, runState }
}
