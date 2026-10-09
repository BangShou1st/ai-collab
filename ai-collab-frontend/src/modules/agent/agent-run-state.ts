import type { AgentRun } from './types'

export interface AgentRunPresentation {
  terminal: boolean
  severity: 'success' | 'warning' | 'error' | 'info'
  title: string
  canRetry: boolean
}

const errorTitles: Record<string, string> = {
  AGENT_TOOL_EXECUTION_FAILED: 'Agent 无法完成这次操作：工具参数无效',
  AGENT_INVALID_DECISION: 'Agent 返回的操作格式无效',
  AGENT_NO_PROGRESS: 'Agent 重复执行相同操作，已停止本次运行',
  AGENT_BUDGET_EXCEEDED: 'Agent 已达到本次运行上限',
  AI_MODEL_TIMEOUT: '模型响应超时',
  AI_PROVIDER_QUOTA_EXCEEDED: '模型额度不足',
  AI_PROVIDER_UNAVAILABLE: '模型尚未配置或不可用',
  AI_MODEL_CREDENTIAL_INVALID: '模型凭据无法解密，请在个人 AI 设置中重新填写 API Key',
  AI_PROVIDER_ERROR: '模型服务暂时不可用',
  AI_PROVIDER_INVALID_RESPONSE: '模型返回了无法识别的内容',
  AI_PROVIDER_OUTPUT_TRUNCATED: '模型输出不完整',
  TOOL_EXECUTION_TIMEOUT: '工具执行超时，Agent 将继续尝试其他操作',
}

export function agentRunPresentation(run: AgentRun): AgentRunPresentation {
  if (run.status === 'WAITING_FOR_APPROVAL') {
    return {
      terminal: false,
      severity: 'warning',
      title: '任务提案等待批准',
      canRetry: false,
    }
  }
  if (run.status === 'WAITING_FOR_USER_INPUT') {
    return {
      terminal: false,
      severity: 'info',
      title: 'Agent 需要你的输入',
      canRetry: false,
    }
  }
  if (run.status === 'PAUSED') {
    return {
      terminal: false,
      severity: 'warning',
      title: '已暂停，进度已保留',
      canRetry: false,
    }
  }
  if (run.status === 'SUCCEEDED') {
    return {
      terminal: true,
      severity: 'success',
      title: 'Agent 已完成',
      canRetry: false,
    }
  }
  if (run.status === 'CANCELED') {
    return {
      terminal: true,
      severity: 'info',
      title: '本次运行已取消',
      canRetry: true,
    }
  }
  if (run.status === 'BUDGET_EXCEEDED') {
    return {
      terminal: true,
      severity: 'error',
      title: errorTitles[run.errorCode ?? ''] ?? 'Agent 已达到本次运行上限',
      canRetry: true,
    }
  }
  if (run.status === 'FAILED') {
    return {
      terminal: true,
      severity: 'error',
      title: errorTitles[run.errorCode ?? ''] ?? 'Agent 无法完成这次操作',
      canRetry: true,
    }
  }
  if (run.status === 'FAILED_RETRYABLE') {
    return {
      terminal: false,
      severity: 'warning',
      title: run.retryCount && run.retryCount > 0
        ? `模型调用暂时失败，等待自动重试（第 ${run.retryCount + 1} 次尝试）`
        : '模型调用暂时失败，等待自动重试',
      canRetry: false,
    }
  }
  return {
    terminal: false,
    severity: 'info',
    title: run.status === 'RUNNING' ? 'Agent 正在处理' : '等待 Agent 处理',
    canRetry: false,
  }
}

/** 供活动时间线复用的错误码可读名；未知错误码返回 null，由调用方决定兜底文案。 */
export function agentErrorTitle(errorCode: string | null | undefined): string | null {
  if (!errorCode) return null
  return errorTitles[errorCode] ?? null
}
