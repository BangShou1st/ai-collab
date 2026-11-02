import { describe, expect, it } from 'vitest'
import { agentRunPresentation } from './agent-run-state'
import type { AgentRun } from './types'

const run = (status: AgentRun['status'], errorCode: string | null = null): AgentRun => ({
  id: 'run-1',
  sessionId: 'session-1',
  projectId: 'project-1',
  goal: '创建任务',
  status,
  stepsUsed: 1,
  maxSteps: 12,
  toolCallsUsed: 0,
  maxToolCalls: 8,
  inputTokensUsed: 100,
  maxInputTokens: 50000,
  outputTokensUsed: 20,
  maxOutputTokens: 20000,
  errorCode,
})

describe('agent run presentation', () => {
  it('presents tool failures instead of silently dropping the run', () => {
    expect(agentRunPresentation(run('FAILED', 'AGENT_TOOL_EXECUTION_FAILED'))).toEqual({
      terminal: true,
      severity: 'error',
      title: 'Agent 无法完成这次操作：工具参数无效',
      canRetry: true,
    })
  })

  it('presents waiting approval as non-terminal since cancel stays allowed', () => {
    expect(agentRunPresentation(run('WAITING_FOR_APPROVAL'))).toEqual({
      terminal: false,
      severity: 'warning',
      title: '任务提案等待批准',
      canRetry: false,
    })
  })

  it('keeps queued and running states non-terminal', () => {
    expect(agentRunPresentation(run('RUNNING')).terminal).toBe(false)
    expect(agentRunPresentation(run('QUEUED')).terminal).toBe(false)
  })

  it('explains an unreadable model credential instead of showing a generic agent failure', () => {
    expect(agentRunPresentation(run('FAILED', 'AI_MODEL_CREDENTIAL_INVALID')).title)
      .toBe('模型凭据无法解密，请在个人 AI 设置中重新填写 API Key')
  })

  it('maps output truncated and format repair exhausted properly', () => {
    expect(agentRunPresentation(run('FAILED', 'AI_PROVIDER_OUTPUT_TRUNCATED')).title)
      .toBe('模型单次输出达到上限被截断（建议提高单次输出上限或精简任务）')
    expect(agentRunPresentation(run('FAILED', 'FORMAT_REPAIR_EXHAUSTED')).title)
      .toBe('模型格式修复尝试耗尽，无法解析响应')
    expect(agentRunPresentation(run('FAILED', 'UNKNOWN_CUSTOM_ERROR')).title)
      .toBe('Agent 运行失败（UNKNOWN_CUSTOM_ERROR）')
  })
})

describe('auto-retry presentation', () => {
  it('presents transient failure as waiting for automatic retry', () => {
    expect(agentRunPresentation(run('FAILED_RETRYABLE', 'AI_MODEL_TIMEOUT'))).toEqual({
      terminal: false,
      severity: 'warning',
      title: '模型调用暂时失败，等待自动重试',
      canRetry: false,
    })
  })

  it('announces the next attempt number from the existing retry counter', () => {
    const retried = { ...run('FAILED_RETRYABLE', 'AI_MODEL_TIMEOUT'), retryCount: 1 }
    expect(agentRunPresentation(retried).title).toBe('模型调用暂时失败，等待自动重试（第 2 次尝试）')
  })
})
