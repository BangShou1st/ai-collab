const fallback = '未知状态'

export const planStatusLabels: Record<string, string> = {
  SKELETON_GENERATING: '正在生成规划骨架',
  DETAIL_GENERATING: '正在补充任务细节',
  REPAIRING: '正在修复规划问题',
  READY: '规划已就绪',
  READY_WITH_ISSUES: '规划已生成，仍有待处理问题',
  DETAIL_GENERATION_FAILED: '细节生成失败',
  CONFIRMING: '正在创建正式任务',
  CONFIRMED: '已创建正式任务',
  FAILED: '生成失败',
  CANCELED: '已取消',
}

export const versionSourceLabels: Record<string, string> = {
  AI_SKELETON: 'AI 生成骨架',
  AI_COMPLETE: 'AI 完整生成',
  AI_PARTIAL: 'AI 生成的待完善版本',
  AI_REPAIR: 'AI 修复',
  AI_PARTIAL_REPAIR: 'AI 局部修复',
  AI_REGENERATED: 'AI 重新生成',
  MANUAL_EDIT: '用户修改',
  RESTORED: '历史版本恢复',
}

export const priorityLabels: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  URGENT: '紧急',
}

const issueLabels: Record<string, string> = {
  DEPENDENCY_DATE_CONFLICT: '任务开始时间早于前置任务完成时间',
  DEPENDENCY_CYCLE: '任务依赖形成循环',
  SELF_DEPENDENCY: '任务不能依赖自身',
  DUPLICATE_DEPENDENCY: '任务包含重复依赖',
  TASK_DATE_OUT_OF_RANGE: '任务日期超出规划范围',
  ASSIGNEE_NOT_PROJECT_MEMBER: '负责人不是项目成员',
  INVALID_SOURCE_REFERENCE: '引用来源不可用',
  INVALID_ESTIMATED_HOURS: '预估工时无效',
  INVALID_PRIORITY: '优先级无效',
}

const eventLabels: Record<string, string> = {
  PLAN_GENERATED: '规划已生成',
  PLAN_GENERATED_WITH_ISSUES: '规划已生成（含待处理问题）',
  TASK_PLAN_USER_EDITED: '用户修改了规划',
  PARTIAL_REPAIR: 'AI 完成局部修复',
  TASK_PLAN_PARTIAL_REGENERATED: 'AI 完成局部修复',
  TASK_PLAN_CANCELED: '规划生成已取消',
  TASK_PLAN_REGENERATED: '规划已重新生成',
  TASK_PLAN_CONFIRMED: '规划已确认',
  TASK_PLAN_CONFIRMATION_FAILED: '规划确认失败',
  TASK_PLAN_DELETED: '规划已删除',
}

export const planStatusLabel = (value: string) => planStatusLabels[value] ?? fallback
export const versionSourceLabel = (value: string) => versionSourceLabels[value] ?? '未知版本来源'
export const priorityLabel = (value: string) => priorityLabels[value] ?? '未知优先级'
export const issueLabel = (value: string) => issueLabels[value] ?? '规划数据需要检查'
export const eventLabel = (value: string) => eventLabels[value] ?? '规划状态已更新'

export const fieldLabel = (value: string | null) => ({
  startDate: '开始日期', dueDate: '截止日期', targetDate: '里程碑日期',
  dependencyTempKeys: '任务依赖', suggestedAssigneeId: '建议负责人',
  assigneeId: '负责人', priority: '优先级', estimatedHours: '预估工时',
  description: '描述', sourceRefs: '参考来源',
}[value ?? ''] ?? '相关字段')
