const stageLabels: Record<string, string> = {
  SKELETON: '规划骨架',
  DETAIL: '任务细节',
  REPAIR: '规划修复',
}

const failureLabels: Record<string, string> = {
  MODEL_OUTPUT_INVALID: '生成结果无效',
  DOMAIN_VALIDATION_FAILED: '未通过业务规则校验',
  PROVIDER_ERROR: '模型服务暂时不可用',
  PLANNING_MODEL_INVALID_OUTPUT: '生成结果无效',
  PLANNING_MODEL_OUTPUT_TRUNCATED: '生成内容过长，请减少任务数量后重试',
  PLANNING_MODEL_TIMEOUT: '模型服务响应超时，请重试',
  PLANNING_PROVIDER_QUOTA_EXCEEDED: '模型服务额度不足，请稍后重试',
  PLANNING_MODEL_UNAVAILABLE: '模型服务暂时不可用',
}

export function planningFailureLabel(summary: string | null | undefined): string {
  if (!summary?.trim()) return ''
  const stage = Object.keys(stageLabels).find(value =>
    new RegExp(`(^|\\s|/)${value}(\\s|/|$)`).test(summary))
  const code = Object.keys(failureLabels).find(value => summary.includes(value))
  if (stage && code) return `${stageLabels[stage]}${failureLabels[code]}`
  if (stage) return `${stageLabels[stage]}处理失败，请重试`
  if (code) return failureLabels[code]
  return '系统暂时无法处理该规划，请稍后重试'
}
