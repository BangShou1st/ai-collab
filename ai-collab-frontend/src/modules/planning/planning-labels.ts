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
  JSON_SYNTAX_INVALID: '生成内容格式无效',
  UNKNOWN_PROPERTY: '生成内容包含不支持的字段',
  MISSING_REQUIRED_FIELD: '生成内容缺少必要字段',
  INVALID_FIELD_TYPE: '生成内容字段类型无效',
  TEMP_KEY_DUPLICATE: '规划实体标识重复',
  MILESTONE_REF_INVALID: '任务引用了不存在的里程碑',
  SKELETON_MUTATED: '任务细节修改了规划骨架',
  DETAIL_DUPLICATE_MILESTONE_KEY: '任务细节包含重复里程碑',
  DETAIL_DUPLICATE_TASK_KEY: '任务细节包含重复任务',
  DETAIL_UNKNOWN_MILESTONE_KEY: '任务细节包含未知里程碑',
  DETAIL_UNKNOWN_TASK_KEY: '任务细节包含未知任务',
  DETAIL_MISSING_MILESTONE_KEY: '任务细节缺少里程碑',
  DETAIL_MISSING_TASK_KEY: '任务细节缺少任务',
  MILESTONE_LIMIT_EXCEEDED: '里程碑数量超过限制',
  MILESTONE_REQUIRED: '规划至少需要一个里程碑',
  TASK_LIMIT_EXCEEDED: '任务数量超过限制',
  TASK_REQUIRED: '规划至少需要一个任务',
  TASK_NULL: '任务内容不能为空',
  MILESTONE_NULL: '里程碑内容不能为空',
  SOURCE_LIMIT_EXCEEDED: '参考来源数量超过限制',
  SOURCE_INVALID: '参考来源内容无效',
  SOURCE_REF_FORMAT_INVALID: '参考来源标识格式无效',
  SOURCE_REF_DUPLICATE: '参考来源重复',
  PLAN_DATE_OUTSIDE_PROJECT: '规划日期超出项目范围',
  SUMMARY_REQUIRED: '规划摘要不能为空',
  SUMMARY_TOO_LONG: '规划摘要过长',
  ASSUMPTION_LIMIT_EXCEEDED: '规划假设数量超过限制',
  RISK_LIMIT_EXCEEDED: '规划风险数量超过限制',
  AI_GENERATED_ASSIGNEE_NOT_ALLOWED: 'AI 建议的负责人不可用',
  MILESTONE_TEXT_INVALID: '里程碑文字内容无效',
  TASK_TEXT_INVALID: '任务文字内容无效',
  DEPENDENCY_DATE_CONFLICT: '任务开始时间早于前置任务完成时间',
  DEPENDENCY_CYCLE: '任务依赖形成循环',
  SELF_DEPENDENCY: '任务不能依赖自身',
  DEPENDENCY_DUPLICATE: '任务包含重复依赖',
  DEPENDENCY_REF_INVALID: '任务引用了不存在的前置任务',
  DEPENDENCY_LIMIT_EXCEEDED: '任务前置依赖数量超过限制',
  ASSIGNEE_NOT_PROJECT_MEMBER: '负责人不是项目成员',
  SOURCE_REF_INVALID: '引用来源不可用',
  SOURCE_REF_LIMIT_EXCEEDED: '任务或里程碑引用来源过多',
  TASK_DATE_INVALID: '任务日期超出规划范围或先后顺序无效',
  MILESTONE_DATE_OUTSIDE_PLAN: '里程碑日期超出规划范围',
  ESTIMATED_HOURS_INVALID: '预估工时必须在 0.5 至 80 小时之间',
  TASK_PRIORITY_INVALID: '任务优先级无效',
  SORT_ORDER_INVALID: '规划排序无效',
  DUPLICATE_TITLE: '规划中存在重复标题',
  EXISTING_TITLE_SIMILAR: '任务标题与已有工作项相似',
  TASK_UNASSIGNED: '任务尚未分配负责人',
  AI_SUGGESTION_WITHOUT_SOURCE: 'AI 建议缺少参考来源',
}

const eventLabels: Record<string, string> = {
  PLAN_GENERATED: '规划已生成',
  PLAN_GENERATED_WITH_ISSUES: '规划已生成（含待处理问题）',
  PLAN_GENERATION_FAILED: '规划生成失败',
  PLAN_GENERATION_CANCELED: '规划生成已取消',
  TASK_PLAN_USER_EDITED: '用户修改了规划',
  PARTIAL_REPAIR: 'AI 完成局部修复',
  TASK_PLAN_PARTIAL_REGENERATED: 'AI 完成局部修复',
  TASK_PLAN_CANCELED: '规划生成已取消',
  TASK_PLAN_REGENERATED: '规划已重新生成',
  TASK_PLAN_CONFIRMED: '规划已确认',
  TASK_PLAN_CONFIRMATION_FAILED: '规划确认失败',
  TASK_PLAN_DELETED: '规划已删除',
  PLAN_VERSION_RESTORED: '已从历史版本恢复',
  TASK_PLAN_VERSION_RESTORED: '已从历史版本恢复',
  TASK_PLAN_VERSION_SAVED: '规划新版本已保存',
  TASK_PLAN_DETAIL_RETRIED: '已重新生成任务细节',
  TASK_PLAN_CREATED: '规划已创建',
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
  summary: '规划摘要', assumptions: '规划假设', risks: '规划风险',
  title: '标题', objective: '目标', milestoneTempKey: '所属里程碑',
  sortOrder: '排序', tasks: '任务', milestones: '里程碑', sources: '来源',
}[value ?? ''] ?? '相关字段')
