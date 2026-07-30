import { describe, expect, it } from 'vitest'
import {
  auditActionLabel,
  auditEntityLabel,
  documentStatusLabel,
  projectStatusLabel,
  roleLabel,
  taskStatusLabel,
} from './display-labels'

describe('面向用户的中文显示映射', () => {
  it('maps current project, role, task and document values', () => {
    expect(projectStatusLabel('ACTIVE')).toBe('进行中')
    expect(projectStatusLabel('ARCHIVED')).toBe('已归档')
    expect(roleLabel('OWNER')).toBe('所有者')
    expect(taskStatusLabel('BLOCKED')).toBe('已阻塞')
    expect(documentStatusLabel('INDEXING')).toBe('向量化中')
  })

  it('maps audit values that the backend actually emits', () => {
    expect(auditActionLabel('PROJECT_MEMBER_ROLE_CHANGED')).toBe('变更成员角色')
    expect(auditActionLabel('DOCUMENT_INDEXED')).toBe('完成文档处理')
    expect(auditActionLabel('TASK_PLAN_CONFIRMED')).toBe('确认 AI 任务规划')
    expect(auditEntityLabel('PROJECT_MEMBER')).toBe('项目成员')
    expect(auditEntityLabel('PROJECT_DOCUMENT')).toBe('项目文档')
    expect(auditEntityLabel('AI_TASK_PLAN')).toBe('AI 任务规划')
  })

  it('does not expose unknown internal values', () => {
    expect(auditActionLabel('INTERNAL_SECRET_ACTION')).toBe('未知操作')
    expect(auditEntityLabel('RAW_DATABASE_ENTITY')).toBe('未知对象')
  })
})
