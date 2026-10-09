const stageLabels: Record<string, string> = {
  SKELETON: '规划骨架',
  DETAIL: '任务细节',
  REPAIR: '规划修复',
}

export function repairRuleLabel(code: string): string {
  return ({ DUPLICATE_PATCH_TARGET: '同一对象不能重复修复', UNKNOWN_PATCH_TARGET: '修复对象不在原草稿中',
    PATCH_TARGET_OUT_OF_SCOPE: '对象不在本次修复范围', PATCH_FIELD_LOCKED: '该字段已锁定，禁止修改',
    PATCH_FIELD_NOT_ALLOWED: '该字段未获本次修复授权', ASSIGNEE_NOT_PROJECT_MEMBER: '建议负责人不是当前项目成员',
    UNKNOWN_SOURCE_REF: '引用不在允许的资料范围', SELF_DEPENDENCY: '任务不能依赖自身',
    UNKNOWN_DEPENDENCY: '依赖任务不在原草稿中', INVALID_PATCH_STRUCTURE: '补丁结构不符合契约',
  } as Record<string, string>)[code] ?? `违反业务规则（${code}）`
}

const failureLabels: Record<string, string> = {
  MODEL_OUTPUT_INVALID: '生成结果无效',
  DOMAIN_VALIDATION_FAILED: '未通过业务规则校验',
  PLAN_VALIDATION_FAILED: '未通过业务规则校验，原草稿已保留，请检查修复范围和内容',
  PROVIDER_ERROR: '模型服务暂时不可用',
  AI_MODEL_CREDENTIAL_INVALID: '模型认证失败或授权不足，请检查个人 AI 配置',
  AI_PROVIDER_REQUEST_REJECTED: '模型服务拒绝请求，请检查访问权限与请求方式；具体原因需核查服务端响应',
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
